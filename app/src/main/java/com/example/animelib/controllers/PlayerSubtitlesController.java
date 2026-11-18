package com.example.animelib.controllers;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.api.ApiService;
import com.example.animelib.ui.VideoUrlHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class PlayerSubtitlesController {

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

    private boolean subtitlesEnabled = true;
    private String subtitleFormat = "ass";
    private float subtitleTextSize = 18f;
    private int subtitleTextColor = 0xFFFFFFFF;
    private int subtitleBackgroundColor = 0x00000000;
    private int subtitleEdgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE;
    private int subtitleEdgeColor = 0xFF000000;
    private int cachedPlayResX = 0;
    private int cachedPlayResY = 0;

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

    public List<MediaItem.SubtitleConfiguration> buildSubtitleConfigurations() {
        List<MediaItem.SubtitleConfiguration> configs = new ArrayList<>();
        if (!subtitlesEnabled) {
            return configs;
        }

        boolean isOffline = callback.isOfflineMode();
        String currentVideoUrl = callback.getCurrentVideoUrl();

        if (isOffline || (currentVideoUrl != null && currentVideoUrl.startsWith("/"))) {
            DownloadedEpisodeEntity offlineEp = callback.getCurrentOfflineEpisode();
            String localPath = (offlineEp != null && offlineEp.getLocalFilePath() != null) ? offlineEp.getLocalFilePath() : currentVideoUrl;
            if (localPath != null && localPath.startsWith("/")) {
                File videoFile = new File(localPath);
                File dir = videoFile.getParentFile();
                if (dir != null && dir.exists()) {
                    String baseName = videoFile.getName();
                    int dotIdx = baseName.lastIndexOf('.');
                    if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);

                    File[] files = dir.listFiles();
                    if (files != null) {
                        int trackIdx = 1;
                        for (File f : files) {
                            if (f.getName().startsWith(baseName + "_sub_") && f.length() > 0) {
                                String name = f.getName();
                                String format = "";
                                int lastDot = name.lastIndexOf('.');
                                if (lastDot > 0) format = name.substring(lastDot + 1).toLowerCase();

                                String mimeType = getMimeTypeForSubtitle(format, f.getAbsolutePath());
                                MediaItem.SubtitleConfiguration config = new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                                        .setMimeType(mimeType)
                                        .setLanguage("ru")
                                        .setLabel("Субтитры (офлайн " + format.toUpperCase() + ")")
                                        .setSelectionFlags(trackIdx == 1 ? C.SELECTION_FLAG_DEFAULT : 0)
                                        .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                                        .build();
                                configs.add(config);
                                trackIdx++;
                            }
                        }
                    }
                }
            }
            if (!configs.isEmpty()) {
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
        if (subtitlesList.isEmpty()) {
            return configs;
        }

        int preferredIndex = -1;
        for (int i = 0; i < subtitlesList.size(); i++) {
            EpisodeResponse.SubtitleData sub = subtitlesList.get(i);
            if (sub == null || sub.getSrc() == null || sub.getSrc().trim().isEmpty()) {
                continue;
            }
            String format = sub.getFormat() != null ? sub.getFormat().trim().toLowerCase() : "";
            if ("auto".equalsIgnoreCase(subtitleFormat)) {
                if ("ass".equals(format) || "ssa".equals(format)) {
                    preferredIndex = i;
                    break;
                }
            } else if (format.equalsIgnoreCase(subtitleFormat)) {
                preferredIndex = i;
                break;
            }
        }

        if (preferredIndex == -1) {
            for (int i = 0; i < subtitlesList.size(); i++) {
                EpisodeResponse.SubtitleData sub = subtitlesList.get(i);
                if (sub != null && sub.getSrc() != null && !sub.getSrc().trim().isEmpty()) {
                    preferredIndex = i;
                    break;
                }
            }
        }

        if (preferredIndex == -1) {
            return configs;
        }

        List<Integer> order = new ArrayList<>();
        order.add(preferredIndex);
        for (int i = 0; i < subtitlesList.size(); i++) {
            if (i != preferredIndex) {
                order.add(i);
            }
        }

        String currentVideoDomain = callback.getCurrentVideoDomain();

        for (int idx : order) {
            EpisodeResponse.SubtitleData sub = subtitlesList.get(idx);
            if (sub == null || sub.getSrc() == null || sub.getSrc().trim().isEmpty()) {
                continue;
            }

            String format = sub.getFormat() != null ? sub.getFormat().trim().toLowerCase() : "";
            String mimeType = getMimeTypeForSubtitle(format, sub.getSrc());
            String absUrl = VideoUrlHelper.toAbsoluteVideoUrl(sub.getSrc(), currentVideoDomain);

            String label = sub.getName();
            if (label == null || label.isEmpty()) {
                label = sub.getFilename();
            }
            if (label == null || label.isEmpty()) {
                label = "Субтитры (" + (format.isEmpty() ? " track " + (idx + 1) : format.toUpperCase()) + ")";
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
            String fmt = format.trim().toLowerCase();
            if ("ass".equals(fmt) || "ssa".equals(fmt)) {
                return MimeTypes.TEXT_SSA;
            } else if ("vtt".equals(fmt) || "webvtt".equals(fmt)) {
                return MimeTypes.TEXT_VTT;
            } else if ("srt".equals(fmt) || "subrip".equals(fmt)) {
                return MimeTypes.APPLICATION_SUBRIP;
            }
        }
        if (url != null) {
            String lowerUrl = url.toLowerCase();
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
        cachedPlayResX = 0;
        cachedPlayResY = 0;
        boolean isOffline = callback.isOfflineMode();
        String currentVideoUrl = callback.getCurrentVideoUrl();
        if (isOffline || (currentVideoUrl != null && currentVideoUrl.startsWith("/"))) {
            DownloadedEpisodeEntity offlineEp = callback.getCurrentOfflineEpisode();
            String localPath = (offlineEp != null && offlineEp.getLocalFilePath() != null) ? offlineEp.getLocalFilePath() : currentVideoUrl;
            if (localPath != null && localPath.startsWith("/")) {
                File videoFile = new File(localPath);
                File dir = videoFile.getParentFile();
                if (dir != null && dir.exists()) {
                    String baseName = videoFile.getName();
                    int dotIdx = baseName.lastIndexOf('.');
                    if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().startsWith(baseName + "_sub_") && f.getName().toLowerCase().endsWith(".ass") && f.length() > 0) {
                                parsePlayResFromFile(f);
                                break;
                            }
                        }
                    }
                }
            }
        }

        MediaItem.Builder builder = new MediaItem.Builder().setUri(videoUrl);
        List<MediaItem.SubtitleConfiguration> subtitleConfigs = buildSubtitleConfigurations();
        if (!subtitleConfigs.isEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs);
            Log.d("PlayerSubtitlesController", "Attached " + subtitleConfigs.size() + " subtitle tracks to media item.");
        }
        return builder.build();
    }

    public void parsePlayResFromFile(File assFile) {
        if (assFile == null || !assFile.exists()) return;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(assFile))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < 60) {
                count++;
                String trimmed = line.trim();
                if (trimmed.startsWith("[Events]")) break;
                if (trimmed.toLowerCase().startsWith("playresx:")) {
                    String val = trimmed.substring(9).trim();
                    cachedPlayResX = Integer.parseInt(val);
                } else if (trimmed.toLowerCase().startsWith("playresy:")) {
                    String val = trimmed.substring(9).trim();
                    cachedPlayResY = Integer.parseInt(val);
                }
            }
            Log.d("PlayerSubtitlesController", "Parsed ASS PlayRes: " + cachedPlayResX + "x" + cachedPlayResY);
        } catch (Exception ignored) {}
    }

    public boolean isCurrentSubtitleVttOrSrt() {
        if ("vtt".equalsIgnoreCase(subtitleFormat) || "webvtt".equalsIgnoreCase(subtitleFormat) || "srt".equalsIgnoreCase(subtitleFormat)) {
            return true;
        }
        if ("ass".equalsIgnoreCase(subtitleFormat) || "ssa".equalsIgnoreCase(subtitleFormat)) {
            return false;
        }
        PlayersManager playersManager = callback.getPlayersManager();
        if (playersManager != null) {
            EpisodeResponse.PlayerData playerData = playersManager.getCurrentPlayerData();
            if (playerData != null && playerData.getSubtitles() != null) {
                for (EpisodeResponse.SubtitleData sub : playerData.getSubtitles()) {
                    if (sub != null && sub.getFormat() != null) {
                        String fmt = sub.getFormat().trim().toLowerCase();
                        if ("ass".equals(fmt) || "ssa".equals(fmt)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    public void setupSubtitlePlayerListener(ExoPlayer p) {
        if (p == null) return;
        p.addListener(new Player.Listener() {
            @Override
            public void onCues(@NonNull CueGroup cueGroup) {
                PlayerView playerView = callback.getPlayerView();
                if (!subtitlesEnabled || playerView == null || playerView.getSubtitleView() == null) {
                    if (playerView != null && playerView.getSubtitleView() != null) {
                        playerView.getSubtitleView().setCues(Collections.emptyList());
                    }
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
                            // Do not display drawing commands as raw text
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
            public void onTracksChanged(@NonNull Tracks tracks) {
                ExoPlayer player = callback.getPlayer();
                if (!subtitlesEnabled || player == null) return;

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
                            } else if (("vtt".equalsIgnoreCase(subtitleFormat) || "webvtt".equalsIgnoreCase(subtitleFormat)) && MimeTypes.TEXT_VTT.equals(mime)) {
                                preferredTextGroup = group;
                                break;
                            }
                        }
                    }
                }

                if (!hasSelectedTextTrack) {
                    Tracks.Group targetGroup = preferredTextGroup != null ? preferredTextGroup : firstSupportedTextGroup;
                    if (targetGroup != null) {
                        Log.d("PlayerSubtitlesController", "No text track auto-selected by Media3. Forcing selection of text track: " + targetGroup.getMediaTrackGroup());
                        try {
                            TrackSelectionParameters newParams = player.getTrackSelectionParameters()
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(new TrackSelectionOverride(targetGroup.getMediaTrackGroup(), 0))
                                    .build();
                            player.setTrackSelectionParameters(newParams);
                        } catch (Exception e) {
                            Log.e("PlayerSubtitlesController", "Failed to force text track selection", e);
                        }
                    }
                }
            }
        });
    }

    public void applySubtitlesStateToPlayer() {
        ExoPlayer player = callback.getPlayer();
        if (player != null) {
            try {
                TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled);
                if (subtitlesEnabled) {
                    builder.setPreferredTextLanguage("ru")
                           .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
                           .setSelectUndeterminedTextLanguage(true);
                }
                player.setTrackSelectionParameters(builder.build());
                Log.d("PlayerSubtitlesController", "Updated subtitle track selection state: enabled=" + subtitlesEnabled);
            } catch (Exception e) {
                Log.e("PlayerSubtitlesController", "Failed to setTrackSelectionParameters for subtitles", e);
            }
        }
        PlayerView playerView = callback.getPlayerView();
        if (playerView != null && playerView.getSubtitleView() != null) {
            try {
                SubtitleView subtitleView = playerView.getSubtitleView();
                subtitleView.setVisibility(subtitlesEnabled ? View.VISIBLE : View.GONE);
                subtitleView.setApplyEmbeddedStyles(true);
                subtitleView.setApplyEmbeddedFontSizes(true);
                subtitleView.setViewType(SubtitleView.VIEW_TYPE_CANVAS);
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
                subtitleView.setBottomPaddingFraction(0.06f);
            } catch (Exception e) {
                Log.w("PlayerSubtitlesController", "Failed to style SubtitleView", e);
            }
        }
    }

    public void reloadPlayerWithSubtitles() {
        ExoPlayer player = callback.getPlayer();
        if (player == null) return;
        long currentPos = player.getCurrentPosition();
        boolean wasPlaying = player.isPlaying();
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
            Log.d("PlayerSubtitlesController", "Reloaded player with updated subtitle configurations at position " + currentPos);
        }
    }

    public void updateSubtitleSettings(boolean enabled, String format) {
        boolean formatChanged = !Objects.equals(subtitleFormat, format);
        subtitlesEnabled = enabled;
        subtitleFormat = format;
        ApiService apiService = callback.getApiService();
        if (apiService != null) {
            apiService.saveSubtitlesEnabledSetting(enabled);
            apiService.saveSubtitleFormatSetting(format);
        }
        Log.d("PlayerSubtitlesController", "Subtitle settings changed: enabled=" + enabled + ", format=" + format);
        applySubtitlesStateToPlayer();
        if (formatChanged) {
            reloadPlayerWithSubtitles();
        }
    }

    public void updateSubtitleStyleSettings(float textSize, int textColor, int bgColor, int edgeType, int edgeColor) {
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
        androidx.media3.common.VideoSize videoSize = (callback != null && callback.getPlayer() != null)
                ? callback.getPlayer().getVideoSize()
                : null;
        Context ctx = callback != null ? callback.getContext() : null;
        return AssSubtitlesProcessor.processAssCue(
                cue,
                ctx,
                subtitleTextColor,
                subtitleTextSize,
                cachedPlayResX,
                cachedPlayResY,
                videoSize
        );
    }

    public List<Cue> resolveCueCollisions(List<Cue> cues) {
        return AssSubtitlesProcessor.resolveCueCollisions(cues);
    }
}
