package com.example.animelib.controllers;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.example.animelib.api.ApiService;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.subtitles.AssParser;
import com.example.animelib.subtitles.AssScript;
import com.example.animelib.ui.AssSubtitleOverlayView;
import com.example.animelib.ui.VideoUrlHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class PlayerSubtitlesController {

    private static final String TAG = "PlayerSubtitlesCtrl";
    private static final LruCache<String, AssScript> ASS_SCRIPT_CACHE = new LruCache<>(12);

    public interface SubtitlesCallback {
        boolean isOfflineMode();
        DownloadedEpisodeEntity getCurrentOfflineEpisode();
        PlayersManager getPlayersManager();
        String getCurrentVideoUrl();
        String getCurrentVideoDomain();
        ExoPlayer getPlayer();
        PlayerView getPlayerView();
        ApiService getApiService();
        Context getContext();
    }

    private final SubtitlesCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService assLoadExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong assLoadGeneration = new AtomicLong(0L);

    private boolean subtitlesEnabled = true;
    private String subtitleFormat = "ass";
    private float subtitleTextSize = 18f;
    private int subtitleTextColor = 0xFFFFFFFF;
    private int subtitleBackgroundColor = 0x00000000;
    private int subtitleEdgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE;
    private int subtitleEdgeColor = 0xFF000000;
    private int cachedPlayResX = 0;
    private int cachedPlayResY = 0;

    @Nullable private AssSubtitleOverlayView assOverlayView;
    @Nullable private AssScript activeAssScript;
    @Nullable private String activeAssSourceKey;
    private volatile boolean nativeAssActive = false;
    @Nullable private ExoPlayer attachedListenerPlayer;
    @Nullable private Player.Listener activePlayerListener;

    public PlayerSubtitlesController(SubtitlesCallback callback) {
        this.callback = callback;
    }

    public void loadSettingsFromApi(ApiService apiService) {
        if (apiService == null) return;
        subtitlesEnabled = apiService.loadSubtitlesEnabledSetting();
        subtitleFormat = apiService.loadSubtitleFormatSetting();
        subtitleTextSize = apiService.loadSubtitleTextSizeSetting();
        subtitleTextColor = apiService.loadSubtitleTextColorSetting();
        subtitleBackgroundColor = apiService.loadSubtitleBackgroundColorSetting();
        subtitleEdgeType = apiService.loadSubtitleEdgeTypeSetting();
        subtitleEdgeColor = apiService.loadSubtitleEdgeColorSetting();
    }

    public boolean isSubtitlesEnabled() {
        return subtitlesEnabled;
    }

    public void setSubtitlesEnabled(boolean subtitlesEnabled) {
        this.subtitlesEnabled = subtitlesEnabled;
    }

    public String getSubtitleFormat() {
        return subtitleFormat;
    }

    public void setSubtitleFormat(String subtitleFormat) {
        this.subtitleFormat = subtitleFormat;
    }

    public float getSubtitleTextSize() {
        return subtitleTextSize;
    }

    public int getSubtitleTextColor() {
        return subtitleTextColor;
    }

    public int getSubtitleBackgroundColor() {
        return subtitleBackgroundColor;
    }

    public int getSubtitleEdgeType() {
        return subtitleEdgeType;
    }

    public int getSubtitleEdgeColor() {
        return subtitleEdgeColor;
    }

    /**
     * Returns true when ASS/SSA is effectively selected (explicit "ass"/"ssa", or "auto" with an ASS track).
     */
    public boolean isAssSubtitleActive() {
        return !isCurrentSubtitleVttOrSrt();
    }

    public List<MediaItem.SubtitleConfiguration> buildSubtitleConfigurations() {
        return buildSubtitleConfigurations(null);
    }

    public List<MediaItem.SubtitleConfiguration> buildSubtitleConfigurations(@Nullable String videoUrlOverride) {
        List<MediaItem.SubtitleConfiguration> configs = new ArrayList<>();
        if (!subtitlesEnabled) {
            return configs;
        }

        boolean isOffline = callback.isOfflineMode();
        String effectiveVideoUrl = (videoUrlOverride != null && !videoUrlOverride.trim().isEmpty())
                ? videoUrlOverride
                : callback.getCurrentVideoUrl();

        if (isOffline || com.example.animelib.models.OfflineEpisodeMetadata.isLocalMediaPath(effectiveVideoUrl)) {
            List<LocalSubtitleEntry> localSubs = findLocalSubtitleEntries(effectiveVideoUrl);
            if (!localSubs.isEmpty()) {
                int preferredIdx = pickPreferredLocalEntryIndex(localSubs);
                List<Integer> order = buildIndexOrder(localSubs.size(), preferredIdx);
                for (int i = 0; i < order.size(); i++) {
                    int idx = order.get(i);
                    LocalSubtitleEntry entry = localSubs.get(idx);
                    File f = entry.file;
                    String format = entry.format;
                    String mimeType = getMimeTypeForSubtitle(format, f.getAbsolutePath());
                    boolean isPreferred = (idx == preferredIdx);
                    MediaItem.SubtitleConfiguration config = new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                            .setMimeType(mimeType)
                            .setLanguage("ru")
                            .setLabel(entry.label)
                            .setSelectionFlags(isPreferred ? (C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED) : 0)
                            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                            .build();
                    configs.add(config);
                }
                return configs;
            }
        }

        PlayersManager playersManager = callback.getPlayersManager();
        if (playersManager == null) {
            return configs;
        }

        EpisodeResponse.PlayerData playerData = playersManager.getCurrentPlayerData();
        if (playerData == null || playerData.getSubtitles() == null || playerData.getSubtitles().isEmpty()) {
            return configs;
        }

        List<EpisodeResponse.SubtitleData> subtitlesList = playerData.getSubtitles();
        int preferredIndex = pickPreferredOnlineIndex(subtitlesList);
        if (preferredIndex == -1) {
            return configs;
        }

        List<Integer> order = buildIndexOrder(subtitlesList.size(), preferredIndex);
        String currentVideoDomain = callback.getCurrentVideoDomain();

        for (int idx : order) {
            EpisodeResponse.SubtitleData sub = subtitlesList.get(idx);
            if (sub == null || sub.getSrc() == null || sub.getSrc().trim().isEmpty()) {
                continue;
            }

            String format = sub.getFormat() != null ? sub.getFormat().trim().toLowerCase(Locale.ROOT) : "";
            String mimeType = getMimeTypeForSubtitle(format, sub.getSrc());
            String absUrl = VideoUrlHelper.toAbsoluteVideoUrl(sub.getSrc(), currentVideoDomain);

            String label = sub.getName();
            if (label == null || label.isEmpty()) {
                label = sub.getFilename();
            }
            if (label == null || label.isEmpty()) {
                label = "Субтитры (" + (format.isEmpty() ? " track " + (idx + 1) : format.toUpperCase(Locale.ROOT)) + ")";
            }

            boolean isPreferred = (idx == preferredIndex);

            MediaItem.SubtitleConfiguration config = new MediaItem.SubtitleConfiguration.Builder(Uri.parse(absUrl))
                    .setMimeType(mimeType)
                    .setLanguage("ru")
                    .setLabel(label)
                    .setSelectionFlags(isPreferred ? (C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED) : 0)
                    .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                    .build();

            configs.add(config);
        }

        return configs;
    }

    public String getMimeTypeForSubtitle(String format, String url) {
        if (format != null && !format.trim().isEmpty()) {
            String fmt = format.trim().toLowerCase(Locale.ROOT);
            if ("ass".equals(fmt) || "ssa".equals(fmt)) {
                return MimeTypes.TEXT_SSA;
            } else if ("vtt".equals(fmt) || "webvtt".equals(fmt)) {
                return MimeTypes.TEXT_VTT;
            } else if ("srt".equals(fmt) || "subrip".equals(fmt)) {
                return MimeTypes.APPLICATION_SUBRIP;
            }
        }
        if (url != null) {
            String lowerUrl = url.toLowerCase(Locale.ROOT);
            if (lowerUrl.contains(".ass") || lowerUrl.contains(".ssa")) {
                return MimeTypes.TEXT_SSA;
            } else if (lowerUrl.contains(".vtt")) {
                return MimeTypes.TEXT_VTT;
            } else if (lowerUrl.contains(".srt")) {
                return MimeTypes.APPLICATION_SUBRIP;
            }
        }
        if ("vtt".equalsIgnoreCase(subtitleFormat) || "webvtt".equalsIgnoreCase(subtitleFormat)) {
            return MimeTypes.TEXT_VTT;
        }
        return MimeTypes.TEXT_SSA;
    }

    public MediaItem createMediaItemWithSubtitles(String videoUrl) {
        if (subtitlesEnabled) {
            loadPreferredAssSubtitleAsync(videoUrl);
        } else {
            cachedPlayResX = 0;
            cachedPlayResY = 0;
            clearNativeAssState();
        }

        MediaItem.Builder builder = new MediaItem.Builder().setUri(videoUrl);
        List<MediaItem.SubtitleConfiguration> subtitleConfigs = buildSubtitleConfigurations(videoUrl);
        if (!subtitleConfigs.isEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs);
            Log.d(TAG, "Attached " + subtitleConfigs.size() + " subtitle tracks to media item.");
        }
        return builder.build();
    }

    public void parsePlayResFromFile(File assFile) {
        if (assFile == null || !assFile.exists()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(assFile), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < 60) {
                count++;
                String trimmed = line.trim();
                if (trimmed.startsWith("[Events]")) break;
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("playresx:")) {
                    cachedPlayResX = Integer.parseInt(trimmed.substring(9).trim());
                } else if (trimmed.toLowerCase(Locale.ROOT).startsWith("playresy:")) {
                    cachedPlayResY = Integer.parseInt(trimmed.substring(9).trim());
                }
            }
            Log.d(TAG, "Parsed ASS PlayRes: " + cachedPlayResX + "x" + cachedPlayResY);
        } catch (Exception ignored) {}
    }

    public boolean isCurrentSubtitleVttOrSrt() {
        if ("vtt".equalsIgnoreCase(subtitleFormat)
                || "webvtt".equalsIgnoreCase(subtitleFormat)
                || "srt".equalsIgnoreCase(subtitleFormat)) {
            return true;
        }
        if ("ass".equalsIgnoreCase(subtitleFormat) || "ssa".equalsIgnoreCase(subtitleFormat)) {
            return false;
        }
        // "auto": check local files first in offline mode or when playing a local downloaded file
        boolean isOffline = callback.isOfflineMode();
        String currentVideoUrl = callback.getCurrentVideoUrl();
        if (isOffline || com.example.animelib.models.OfflineEpisodeMetadata.isLocalMediaPath(currentVideoUrl)) {
            List<LocalSubtitleEntry> localSubs = findLocalSubtitleEntries(currentVideoUrl);
            for (LocalSubtitleEntry entry : localSubs) {
                if ("ass".equals(entry.format) || "ssa".equals(entry.format)) {
                    return false;
                }
            }
            if (!localSubs.isEmpty()) {
                return true;
            }
        }
        PlayersManager playersManager = callback.getPlayersManager();
        if (playersManager != null) {
            EpisodeResponse.PlayerData playerData = playersManager.getCurrentPlayerData();
            if (playerData != null && playerData.getSubtitles() != null) {
                for (EpisodeResponse.SubtitleData sub : playerData.getSubtitles()) {
                    if (sub != null) {
                        String fmt = sub.getFormat() != null ? sub.getFormat().trim().toLowerCase(Locale.ROOT) : "";
                        String src = sub.getSrc() != null ? sub.getSrc().toLowerCase(Locale.ROOT) : "";
                        if ("ass".equals(fmt) || "ssa".equals(fmt) || src.contains(".ass") || src.contains(".ssa")) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    // ==================== NATIVE ASS LOADING & OVERLAY MANAGEMENT ====================

    @Nullable
    private AssSubtitleOverlayView ensureAssOverlayView(@Nullable PlayerView playerView) {
        if (playerView == null) return null;
        SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView == null) return null;

        ViewParent parent = subtitleView.getParent();
        if (!(parent instanceof ViewGroup)) return null;
        ViewGroup container = (ViewGroup) parent;

        // Reuse existing overlay if already attached to this PlayerView's content frame
        if (assOverlayView != null && assOverlayView.getParent() == container) {
            return assOverlayView;
        }

        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof AssSubtitleOverlayView) {
                assOverlayView = (AssSubtitleOverlayView) child;
                return assOverlayView;
            }
        }

        AssSubtitleOverlayView overlay = new AssSubtitleOverlayView(playerView.getContext());
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        int subIndex = container.indexOfChild(subtitleView);
        if (subIndex >= 0) {
            container.addView(overlay, subIndex + 1, lp);
        } else {
            container.addView(overlay, lp);
        }
        overlay.setVisibility(View.GONE);
        assOverlayView = overlay;
        return overlay;
    }

    private void clearNativeAssState() {
        final long clearGen = assLoadGeneration.incrementAndGet();
        activeAssScript = null;
        activeAssSourceKey = null;
        nativeAssActive = false;
        Runnable clearOverlay = () -> {
            if (assLoadGeneration.get() != clearGen) return;
            if (assOverlayView != null) {
                assOverlayView.setAssScript(null);
                assOverlayView.setVisibility(View.GONE);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearOverlay.run();
        } else {
            mainHandler.post(clearOverlay);
        }
    }

    private void loadPreferredAssSubtitleAsync() {
        loadPreferredAssSubtitleAsync(null);
    }

    private void loadPreferredAssSubtitleAsync(@Nullable String videoUrlOverride) {
        if (!subtitlesEnabled) {
            clearNativeAssState();
            return;
        }

        // Resolve local ASS file first if offline or playing a downloaded local file
        boolean isOffline = callback.isOfflineMode();
        String effectiveVideoUrl = (videoUrlOverride != null && !videoUrlOverride.trim().isEmpty())
                ? videoUrlOverride
                : callback.getCurrentVideoUrl();
        if (isOffline || com.example.animelib.models.OfflineEpisodeMetadata.isLocalMediaPath(effectiveVideoUrl)) {
            List<LocalSubtitleEntry> localSubs = findLocalSubtitleEntries(effectiveVideoUrl);
            if (!localSubs.isEmpty()) {
                int preferredIdx = pickPreferredLocalEntryIndex(localSubs);
                if (preferredIdx >= 0) {
                    LocalSubtitleEntry chosen = localSubs.get(preferredIdx);
                    if ("ass".equals(chosen.format) || "ssa".equals(chosen.format)) {
                        loadAssFromLocalFileAsync(chosen.file);
                        return;
                    } else {
                        clearNativeAssState();
                        return;
                    }
                }
            }
        }

        // Resolve online ASS subtitle track
        PlayersManager playersManager = callback.getPlayersManager();
        if (playersManager == null) {
            clearNativeAssState();
            return;
        }
        EpisodeResponse.PlayerData playerData = playersManager.getCurrentPlayerData();
        if (playerData == null || playerData.getSubtitles() == null || playerData.getSubtitles().isEmpty()) {
            clearNativeAssState();
            return;
        }

        List<EpisodeResponse.SubtitleData> subtitlesList = playerData.getSubtitles();
        int preferredIndex = pickPreferredOnlineIndex(subtitlesList);
        if (preferredIndex < 0) {
            clearNativeAssState();
            return;
        }

        EpisodeResponse.SubtitleData chosen = subtitlesList.get(preferredIndex);
        if (chosen == null || chosen.getSrc() == null || chosen.getSrc().trim().isEmpty()) {
            clearNativeAssState();
            return;
        }

        String fmt = chosen.getFormat() != null ? chosen.getFormat().trim().toLowerCase(Locale.ROOT) : "";
        String mime = getMimeTypeForSubtitle(fmt, chosen.getSrc());
        if (!MimeTypes.TEXT_SSA.equals(mime)) {
            clearNativeAssState();
            return;
        }

        String absUrl = VideoUrlHelper.toAbsoluteVideoUrl(chosen.getSrc(), callback.getCurrentVideoDomain());
        loadAssFromUrlAsync(absUrl);
    }

    private void loadAssFromLocalFileAsync(@NonNull File file) {
        final String key = "file:" + file.getAbsolutePath() + ":" + file.lastModified();
        if (key.equals(activeAssSourceKey) && activeAssScript != null) {
            applyParsedAssScript(key, activeAssScript);
            return;
        }
        AssScript cached = ASS_SCRIPT_CACHE.get(key);
        if (cached != null) {
            applyParsedAssScript(key, cached);
            return;
        }

        clearNativeAssState();
        final long gen = assLoadGeneration.incrementAndGet();
        assLoadExecutor.execute(() -> {
            try {
                String content = readFileUtf8(file);
                AssScript parsed = AssParser.parse(content);
                if (!parsed.isEmpty()) {
                    ASS_SCRIPT_CACHE.put(key, parsed);
                }
                mainHandler.post(() -> {
                    if (assLoadGeneration.get() == gen) {
                        applyParsedAssScript(key, parsed);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse local ASS subtitle file: " + file.getName(), e);
            }
        });
    }

    private void loadAssFromUrlAsync(@NonNull String url) {
        final String key = "url:" + url;
        if (key.equals(activeAssSourceKey) && activeAssScript != null) {
            applyParsedAssScript(key, activeAssScript);
            return;
        }
        AssScript cached = ASS_SCRIPT_CACHE.get(key);
        if (cached != null) {
            applyParsedAssScript(key, cached);
            return;
        }

        clearNativeAssState();
        final long gen = assLoadGeneration.incrementAndGet();
        final ApiService apiService = callback.getApiService();
        assLoadExecutor.execute(() -> {
            try {
                OkHttpClient client = (apiService != null && apiService.getHttpClient() != null)
                        ? apiService.getHttpClient()
                        : new OkHttpClient();

                Request.Builder reqBuilder = new Request.Builder().url(url).get();
                if (apiService != null) {
                    Map<String, String> headers = apiService.getVideoRequestHeadersForUrl(url);
                    if (headers != null) {
                        for (Map.Entry<String, String> entry : headers.entrySet()) {
                            if (entry.getKey() != null && entry.getValue() != null) {
                                reqBuilder.header(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }

                try (Response response = client.newCall(reqBuilder.build()).execute()) {
                    if (!response.isSuccessful()) {
                        Log.w(TAG, "ASS subtitle HTTP fetch failed (" + response.code() + ") for " + url);
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null) return;
                    byte[] bytes = body.bytes();
                    String content = decodeAssBytes(bytes);
                    AssScript parsed = AssParser.parse(content);
                    if (!parsed.isEmpty()) {
                        ASS_SCRIPT_CACHE.put(key, parsed);
                    }
                    mainHandler.post(() -> {
                        if (assLoadGeneration.get() == gen) {
                            applyParsedAssScript(key, parsed);
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch/parse online ASS subtitle: " + url, e);
            }
        });
    }

    private void applyParsedAssScript(String sourceKey, @NonNull AssScript script) {
        activeAssSourceKey = sourceKey;
        activeAssScript = script;
        if (script.playResX > 0f) cachedPlayResX = Math.round(script.playResX);
        if (script.playResY > 0f) cachedPlayResY = Math.round(script.playResY);

        nativeAssActive = subtitlesEnabled && !script.isEmpty() && !isCurrentSubtitleVttOrSrt();
        Log.d(TAG, "Loaded native ASS script: events=" + script.events.size()
                + ", styles=" + script.styles.size()
                + ", PlayRes=" + script.playResX + "x" + script.playResY
                + ", nativeActive=" + nativeAssActive);

        applySubtitlesStateToPlayer();
    }

    private static String readFileUtf8(File file) throws Exception {
        byte[] bytes;
        try (FileInputStream fis = new FileInputStream(file)) {
            int len = (int) Math.min(file.length(), 16 * 1024 * 1024);
            bytes = new byte[len];
            int offset = 0;
            while (offset < len) {
                int read = fis.read(bytes, offset, len - offset);
                if (read <= 0) break;
                offset += read;
            }
            if (offset != len) {
                byte[] trimmed = new byte[offset];
                System.arraycopy(bytes, 0, trimmed, 0, offset);
                bytes = trimmed;
            }
        }
        return decodeAssBytes(bytes);
    }

    private static String decodeAssBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 2) {
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            }
            if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==================== PLAYER LISTENER & STATE SYNC ====================

    public void setupSubtitlePlayerListener(ExoPlayer p) {
        if (p == null) return;
        if (attachedListenerPlayer == p && activePlayerListener != null) {
            AssSubtitleOverlayView overlay = ensureAssOverlayView(callback.getPlayerView());
            if (overlay != null) {
                overlay.setPlayer(p);
            }
            return;
        }
        if (attachedListenerPlayer != null && activePlayerListener != null) {
            try {
                attachedListenerPlayer.removeListener(activePlayerListener);
            } catch (Exception ignored) {}
        }

        Player.Listener listener = new Player.Listener() {
            @Override
            public void onCues(@NonNull CueGroup cueGroup) {
                PlayerView playerView = callback.getPlayerView();
                if (playerView == null || playerView.getSubtitleView() == null) return;

                if (!subtitlesEnabled || nativeAssActive) {
                    playerView.getSubtitleView().setCues(Collections.emptyList());
                    return;
                }
                if (cueGroup.cues == null || cueGroup.cues.isEmpty()) {
                    playerView.getSubtitleView().setCues(Collections.emptyList());
                    return;
                }

                List<Cue> processedCues = new ArrayList<>();
                for (Cue cue : cueGroup.cues) {
                    if (cue == null) continue;
                    if (cue.text != null && cue.text.length() > 0) {
                        String raw = cue.text.toString();
                        if (isAssDrawingPath(raw)) {
                            Cue vectorCue = processAssCue(cue);
                            if (vectorCue != null) {
                                processedCues.add(vectorCue);
                            }
                            continue;
                        }
                        Cue processed = processAssCue(cue);
                        if (processed != null && ((processed.text != null && processed.text.length() > 0) || processed.bitmap != null)) {
                            processedCues.add(processed);
                        } else if (processed == null && raw.trim().length() > 0) {
                            processedCues.add(cue);
                        }
                    } else if (cue.bitmap != null) {
                        processedCues.add(cue);
                    }
                }
                List<Cue> stackedCues = resolveCueCollisions(processedCues);
                playerView.getSubtitleView().setCues(stackedCues);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (assOverlayView != null && nativeAssActive) {
                    if (isPlaying) {
                        if (assOverlayView.getVisibility() != View.VISIBLE) {
                            assOverlayView.setVisibility(View.VISIBLE);
                        }
                        assOverlayView.syncNow();
                        assOverlayView.startRenderLoop();
                    } else {
                        assOverlayView.syncNow();
                    }
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (nativeAssActive && assOverlayView != null) {
                    if (assOverlayView.getVisibility() != View.VISIBLE) {
                        assOverlayView.setVisibility(View.VISIBLE);
                    }
                    assOverlayView.syncNow();
                } else if (subtitlesEnabled && !nativeAssActive) {
                    ExoPlayer active = callback.getPlayer();
                    if (active != null) {
                        ensureTextTrackSelected(active, active.getCurrentTracks());
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    if (nativeAssActive && assOverlayView != null) {
                        if (assOverlayView.getVisibility() != View.VISIBLE) {
                            assOverlayView.setVisibility(View.VISIBLE);
                        }
                        assOverlayView.syncNow();
                    } else if (subtitlesEnabled && activeAssScript != null && !activeAssScript.isEmpty() && isAssSubtitleActive()) {
                        applySubtitlesStateToPlayer();
                    } else if (subtitlesEnabled && !nativeAssActive) {
                        ExoPlayer active = callback.getPlayer();
                        if (active != null) {
                            ensureTextTrackSelected(active, active.getCurrentTracks());
                        }
                    }
                } else if (assOverlayView != null && nativeAssActive) {
                    assOverlayView.syncNow();
                }
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                                @NonNull Player.PositionInfo newPosition,
                                                int reason) {
                if (assOverlayView != null && nativeAssActive) {
                    assOverlayView.syncNow();
                }
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (assOverlayView != null) {
                    assOverlayView.updateVideoSize(videoSize.width, videoSize.height);
                }
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                ExoPlayer player = callback.getPlayer();
                if (!subtitlesEnabled || player == null) return;
                ensureTextTrackSelected(player, tracks);
            }
        };

        p.addListener(listener);
        attachedListenerPlayer = p;
        activePlayerListener = listener;

        AssSubtitleOverlayView overlay = ensureAssOverlayView(callback.getPlayerView());
        if (overlay != null) {
            overlay.setPlayer(p);
        }
    }

    private void ensureTextTrackSelected(@NonNull ExoPlayer player, @Nullable Tracks tracks) {
        if (!subtitlesEnabled || tracks == null) return;

        boolean hasSelectedTextTrack = false;
        Tracks.Group preferredTextGroup = null;
        Tracks.Group firstSupportedTextGroup = null;

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT && group.isSupported()) {
                if (group.isSelected()) {
                    hasSelectedTextTrack = true;
                    break;
                }
                if (firstSupportedTextGroup == null) {
                    firstSupportedTextGroup = group;
                }
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    String mime = format.sampleMimeType;
                    if ("ass".equalsIgnoreCase(subtitleFormat) && MimeTypes.TEXT_SSA.equals(mime)) {
                        preferredTextGroup = group;
                        break;
                    } else if (("vtt".equalsIgnoreCase(subtitleFormat) || "webvtt".equalsIgnoreCase(subtitleFormat))
                            && MimeTypes.TEXT_VTT.equals(mime)) {
                        preferredTextGroup = group;
                        break;
                    }
                }
            }
        }

        if (!hasSelectedTextTrack) {
            Tracks.Group targetGroup = preferredTextGroup != null ? preferredTextGroup : firstSupportedTextGroup;
            if (targetGroup != null) {
                Log.d(TAG, "Forcing selection of text track: " + targetGroup.getMediaTrackGroup());
                try {
                    TrackSelectionParameters newParams = player.getTrackSelectionParameters()
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(targetGroup.getMediaTrackGroup(), 0))
                            .build();
                    player.setTrackSelectionParameters(newParams);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to force text track selection", e);
                }
            }
        }
    }

    public void configureTrackSelectionForPlayer(@Nullable ExoPlayer targetPlayer) {
        if (targetPlayer == null) return;
        try {
            TrackSelectionParameters.Builder builder = targetPlayer.getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled);
            if (subtitlesEnabled) {
                builder.setPreferredTextLanguage("ru")
                        .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
                        .setSelectUndeterminedTextLanguage(true);
            }
            targetPlayer.setTrackSelectionParameters(builder.build());
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure track selection for player", e);
        }
    }

    public void applySubtitlesStateToPlayer() {
        ExoPlayer player = callback.getPlayer();
        boolean isAssMode = isAssSubtitleActive();
        nativeAssActive = subtitlesEnabled && isAssMode && activeAssScript != null && !activeAssScript.isEmpty();

        if (player != null) {
            configureTrackSelectionForPlayer(player);
            if (subtitlesEnabled && !nativeAssActive) {
                ensureTextTrackSelected(player, player.getCurrentTracks());
            }
        }

        PlayerView playerView = callback.getPlayerView();
        if (playerView != null && playerView.getSubtitleView() != null) {
            try {
                SubtitleView subtitleView = playerView.getSubtitleView();
                AssSubtitleOverlayView overlay = ensureAssOverlayView(playerView);

                if (overlay != null) {
                    if (nativeAssActive) {
                        overlay.setVisibility(View.VISIBLE);
                        if (overlay.getAssScript() != activeAssScript) {
                            overlay.setAssScript(activeAssScript);
                        }
                        overlay.setPlayer(player);
                        overlay.syncNow();
                    } else {
                        overlay.setPlayer(player);
                        overlay.stopRenderLoop();
                        overlay.setVisibility(View.GONE);
                    }
                }

                if (!subtitlesEnabled || nativeAssActive) {
                    subtitleView.setCues(Collections.emptyList());
                    subtitleView.setVisibility(View.GONE);
                } else {
                    subtitleView.setVisibility(View.VISIBLE);
                    subtitleView.setViewType(SubtitleView.VIEW_TYPE_CANVAS);

                    if (isAssMode) {
                        // ASS fallback mode: never apply VTT/SRT user customization overrides
                        subtitleView.setApplyEmbeddedStyles(true);
                        subtitleView.setApplyEmbeddedFontSizes(true);
                        CaptionStyleCompat defaultAssFallbackStyle = new CaptionStyleCompat(
                                Color.WHITE,
                                Color.TRANSPARENT,
                                Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                Color.BLACK,
                                null
                        );
                        subtitleView.setStyle(defaultAssFallbackStyle);
                        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION);
                    } else {
                        // VTT / SRT mode: apply user's custom style preferences
                        subtitleView.setApplyEmbeddedStyles(true);
                        subtitleView.setApplyEmbeddedFontSizes(true);
                        CaptionStyleCompat style = new CaptionStyleCompat(
                                subtitleTextColor,
                                subtitleBackgroundColor,
                                Color.TRANSPARENT,
                                subtitleEdgeType,
                                subtitleEdgeColor,
                                null
                        );
                        subtitleView.setStyle(style);
                        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSize);
                    }
                    subtitleView.setBottomPaddingFraction(0.06f);
                    if (player != null) {
                        CueGroup currentCues = player.getCurrentCues();
                        if (currentCues != null && currentCues.cues != null && !currentCues.cues.isEmpty()) {
                            subtitleView.setCues(currentCues.cues);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to style SubtitleView / AssSubtitleOverlayView", e);
            }
        }
    }

    public void reloadPlayerWithSubtitles() {
        ExoPlayer player = callback.getPlayer();
        if (player == null) return;
        long currentPos = Math.max(0L, player.getCurrentPosition());
        boolean wasPlaying = player.getPlayWhenReady();
        String currentVideoUrl = callback.getCurrentVideoUrl();

        if (currentVideoUrl != null && !currentVideoUrl.isEmpty()) {
            MediaItem mediaItem = createMediaItemWithSubtitles(currentVideoUrl);
            player.setMediaItem(mediaItem);
            player.seekTo(currentPos);
            player.prepare();
            if (wasPlaying) {
                player.play();
            }
            applySubtitlesStateToPlayer();
            Log.d(TAG, "Reloaded player with updated subtitle configurations at position " + currentPos);
        }
    }

    public void updateSubtitleSettings(boolean enabled, String format) {
        boolean formatChanged = !Objects.equals(subtitleFormat, format);
        boolean wasEnabled = subtitlesEnabled;
        subtitlesEnabled = enabled;
        subtitleFormat = format;
        ApiService apiService = callback.getApiService();
        if (apiService != null) {
            apiService.saveSubtitlesEnabledSetting(enabled);
            apiService.saveSubtitleFormatSetting(format);
        }
        Log.d(TAG, "Subtitle settings changed: enabled=" + enabled + ", format=" + format);

        if (!enabled) {
            nativeAssActive = false;
            applySubtitlesStateToPlayer();
            return;
        }

        if (formatChanged) {
            reloadPlayerWithSubtitles();
        } else {
            if (!wasEnabled && isAssSubtitleActive() && activeAssScript == null) {
                loadPreferredAssSubtitleAsync();
            }
            applySubtitlesStateToPlayer();
        }
    }

    public void updateSubtitleStyleSettings(float textSize, int textColor, int bgColor, int edgeType, int edgeColor) {
        // Lock customization when ASS subtitles are selected
        if (isAssSubtitleActive()) {
            Log.d(TAG, "Ignoring subtitle style customization while ASS subtitles are selected.");
            return;
        }
        subtitleTextSize = textSize;
        subtitleTextColor = textColor;
        subtitleBackgroundColor = bgColor;
        subtitleEdgeType = edgeType;
        subtitleEdgeColor = edgeColor;
        ApiService apiService = callback.getApiService();
        if (apiService != null) {
            apiService.saveSubtitleStyleSettings(textSize, textColor, bgColor, edgeType, edgeColor);
        }
        applySubtitlesStateToPlayer();
    }

    public static boolean isAssDrawingPath(String str) {
        return AssSubtitlesProcessor.isAssDrawingPath(str);
    }

    public Cue processAssCue(Cue cue) {
        VideoSize videoSize = (callback != null && callback.getPlayer() != null)
                ? callback.getPlayer().getVideoSize()
                : null;
        Context ctx = callback != null ? callback.getContext() : null;
        int fallbackColor = isAssSubtitleActive() ? Color.WHITE : subtitleTextColor;
        float fallbackSize = isAssSubtitleActive() ? 18f : subtitleTextSize;
        return AssSubtitlesProcessor.processAssCue(
                cue,
                ctx,
                fallbackColor,
                fallbackSize,
                cachedPlayResX,
                cachedPlayResY,
                videoSize
        );
    }

    public List<Cue> resolveCueCollisions(List<Cue> cues) {
        return AssSubtitlesProcessor.resolveCueCollisions(cues);
    }

    // ==================== HELPER METHODS ====================

    private static final class LocalSubtitleEntry {
        final File file;
        final String format;
        final String label;

        LocalSubtitleEntry(File file, String format, String label) {
            this.file = file;
            this.format = format;
            this.label = label;
        }
    }

    private List<LocalSubtitleEntry> findLocalSubtitleEntries(@Nullable String videoUrlOverride) {
        List<LocalSubtitleEntry> entries = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();

        DownloadedEpisodeEntity offlineEp = callback.getCurrentOfflineEpisode();

        // 1. Сначала читаем сохранённые метаданные субтитров из Room (сохраняют оригинальные названия дорожек)
        if (offlineEp != null) {
            List<com.example.animelib.models.OfflineEpisodeMetadata.DownloadedSubtitleInfo> savedSubs =
                    com.example.animelib.models.OfflineEpisodeMetadata.getExistingSubtitles(offlineEp);
            for (int i = 0; i < savedSubs.size(); i++) {
                com.example.animelib.models.OfflineEpisodeMetadata.DownloadedSubtitleInfo info = savedSubs.get(i);
                File f = new File(info.getLocalPath());
                if (f.exists() && f.length() > 0 && seenPaths.add(f.getAbsolutePath())) {
                    String fmt = (info.getFormat() != null && !info.getFormat().trim().isEmpty())
                            ? info.getFormat().trim().toLowerCase(Locale.ROOT)
                            : getFileExtensionLower(f.getName());
                    entries.add(new LocalSubtitleEntry(f, fmt, info.getDisplayLabel(i)));
                }
            }
        }

        // 2. Фолбэк/дополнение сканированием локальной папки видеофайла (поддержка file:// и старых загрузок)
        String rawLocalPath = (videoUrlOverride != null && !videoUrlOverride.trim().isEmpty())
                ? videoUrlOverride
                : ((offlineEp != null && offlineEp.getLocalFilePath() != null)
                        ? offlineEp.getLocalFilePath()
                        : callback.getCurrentVideoUrl());

        List<File> scannedFiles = com.example.animelib.util.FileUtils.findLocalSubtitleFilesForVideo(rawLocalPath);
        for (File f : scannedFiles) {
            if (seenPaths.add(f.getAbsolutePath())) {
                String fmt = getFileExtensionLower(f.getName());
                String label = "Субтитры (офлайн " + fmt.toUpperCase(Locale.ROOT) + ")";
                entries.add(new LocalSubtitleEntry(f, fmt, label));
            }
        }

        return entries;
    }

    private int pickPreferredLocalEntryIndex(List<LocalSubtitleEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            String ext = entries.get(i).format;
            if ("auto".equalsIgnoreCase(subtitleFormat)) {
                if ("ass".equals(ext) || "ssa".equals(ext)) return i;
            } else if (ext.equalsIgnoreCase(subtitleFormat)
                    || ("ass".equalsIgnoreCase(subtitleFormat) && "ssa".equals(ext))
                    || ("vtt".equalsIgnoreCase(subtitleFormat) && "webvtt".equals(ext))) {
                return i;
            }
        }
        return entries.isEmpty() ? -1 : 0;
    }

    private int pickPreferredOnlineIndex(List<EpisodeResponse.SubtitleData> subtitlesList) {
        for (int i = 0; i < subtitlesList.size(); i++) {
            EpisodeResponse.SubtitleData sub = subtitlesList.get(i);
            if (sub == null || sub.getSrc() == null || sub.getSrc().trim().isEmpty()) continue;
            String format = sub.getFormat() != null ? sub.getFormat().trim().toLowerCase(Locale.ROOT) : "";
            String src = sub.getSrc().toLowerCase(Locale.ROOT);
            if ("auto".equalsIgnoreCase(subtitleFormat)) {
                if ("ass".equals(format) || "ssa".equals(format) || src.contains(".ass") || src.contains(".ssa")) {
                    return i;
                }
            } else if (format.equalsIgnoreCase(subtitleFormat)
                    || ("ass".equalsIgnoreCase(subtitleFormat) && ("ssa".equals(format) || src.contains(".ass")))
                    || ("vtt".equalsIgnoreCase(subtitleFormat) && ("webvtt".equals(format) || src.contains(".vtt")))) {
                return i;
            }
        }
        for (int i = 0; i < subtitlesList.size(); i++) {
            EpisodeResponse.SubtitleData sub = subtitlesList.get(i);
            if (sub != null && sub.getSrc() != null && !sub.getSrc().trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static List<Integer> buildIndexOrder(int size, int preferredIdx) {
        List<Integer> order = new ArrayList<>(size);
        if (preferredIdx >= 0 && preferredIdx < size) {
            order.add(preferredIdx);
        }
        for (int i = 0; i < size; i++) {
            if (i != preferredIdx) {
                order.add(i);
            }
        }
        return order;
    }

    private static String getFileExtensionLower(String fileName) {
        if (fileName == null) return "";
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
