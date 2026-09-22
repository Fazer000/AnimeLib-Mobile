package com.example.animelib.controllers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;
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
import com.example.animelib.util.CustomTypefaceSpan;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.FontResolver;

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
        MediaItem.Builder builder = new MediaItem.Builder().setUri(videoUrl);
        List<MediaItem.SubtitleConfiguration> subtitleConfigs = buildSubtitleConfigurations();
        if (!subtitleConfigs.isEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs);
            Log.d("PlayerSubtitlesController", "Attached " + subtitleConfigs.size() + " subtitle tracks to media item.");
        }
        return builder.build();
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
                        Cue processed = processAssCue(cue);
                        if (processed != null && ((processed.text != null && processed.text.length() > 0) || processed.bitmap != null)) {
                            processedCues.add(processed);
                        } else {
                            processedCues.add(cue);
                        }
                    } else if (cue.bitmap != null) {
                        processedCues.add(cue);
                    }
                }
                List<Cue> stackedCues = resolveCueCollisions(processedCues);
                playerView.getSubtitleView().setCues(stackedCues.isEmpty() ? cueGroup.cues : stackedCues);
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

    public static class AssDrawingResult {
        public final Path path;
        public final RectF bounds;

        public AssDrawingResult(Path path, RectF bounds) {
            this.path = path;
            this.bounds = bounds;
        }
    }

    public static AssDrawingResult parseAssPath(String drawingCommands, float scale) {
        if (drawingCommands == null || drawingCommands.trim().isEmpty()) return null;
        try {
            java.util.regex.Pattern tokenPattern = java.util.regex.Pattern.compile("([a-zA-Z])|(-?\\d+(?:\\.\\d+)?)");
            java.util.regex.Matcher matcher = tokenPattern.matcher(drawingCommands);

            List<String> tokens = new ArrayList<>();
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
            if (tokens.isEmpty()) return null;

            Path path = new Path();
            char currentCmd = 'm';
            int i = 0;
            boolean hasPoints = false;

            while (i < tokens.size()) {
                String token = tokens.get(i);
                char firstChar = Character.toLowerCase(token.charAt(0));

                if (Character.isLetter(firstChar)) {
                    currentCmd = firstChar;
                    i++;
                    if (currentCmd == 'c') {
                        path.close();
                        continue;
                    }
                    if (i >= tokens.size()) break;
                }

                if (currentCmd == 'm' || currentCmd == 'n') {
                    if (i + 1 < tokens.size() && isNumeric(tokens.get(i)) && isNumeric(tokens.get(i + 1))) {
                        float x = Float.parseFloat(tokens.get(i)) * scale;
                        float y = Float.parseFloat(tokens.get(i + 1)) * scale;
                        path.moveTo(x, y);
                        hasPoints = true;
                        i += 2;
                        currentCmd = 'l';
                    } else {
                        i++;
                    }
                } else if (currentCmd == 'l' || currentCmd == 's' || currentCmd == 'p') {
                    if (i + 1 < tokens.size() && isNumeric(tokens.get(i)) && isNumeric(tokens.get(i + 1))) {
                        float x = Float.parseFloat(tokens.get(i)) * scale;
                        float y = Float.parseFloat(tokens.get(i + 1)) * scale;
                        path.lineTo(x, y);
                        hasPoints = true;
                        i += 2;
                    } else {
                        i++;
                    }
                } else if (currentCmd == 'b') {
                    if (i + 5 < tokens.size() && isNumeric(tokens.get(i)) && isNumeric(tokens.get(i + 1))
                            && isNumeric(tokens.get(i + 2)) && isNumeric(tokens.get(i + 3))
                            && isNumeric(tokens.get(i + 4)) && isNumeric(tokens.get(i + 5))) {
                        float x1 = Float.parseFloat(tokens.get(i)) * scale;
                        float y1 = Float.parseFloat(tokens.get(i + 1)) * scale;
                        float x2 = Float.parseFloat(tokens.get(i + 2)) * scale;
                        float y2 = Float.parseFloat(tokens.get(i + 3)) * scale;
                        float x3 = Float.parseFloat(tokens.get(i + 4)) * scale;
                        float y3 = Float.parseFloat(tokens.get(i + 5)) * scale;
                        path.cubicTo(x1, y1, x2, y2, x3, y3);
                        hasPoints = true;
                        i += 6;
                    } else {
                        i++;
                    }
                } else {
                    i++;
                }
            }

            if (!hasPoints) return null;

            RectF bounds = new RectF();
            path.computeBounds(bounds, true);
            if (bounds.width() <= 0 || bounds.height() <= 0) return null;

            return new AssDrawingResult(path, bounds);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        char c = str.charAt(0);
        return Character.isDigit(c) || c == '-' || c == '+' || c == '.';
    }

    private Cue renderAssVectorCue(Path rawPath, RectF bounds, int fillColor, int strokeColor, float strokeWidth,
                                   float posX, float posY, int anVal, float playResX, float playResY) {
        if (rawPath == null || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return null;
        try {
            float padding = Math.max(3.0f, strokeWidth * 2.0f);
            float boundsW = bounds.width() + padding * 2;
            float boundsH = bounds.height() + padding * 2;

            float renderScale = Math.max(1.0f, Math.min(3.0f, 1080.0f / Math.max(1.0f, playResY)));
            int bmpWidth = Math.max(1, Math.min(2048, Math.round(boundsW * renderScale)));
            int bmpHeight = Math.max(1, Math.min(2048, Math.round(boundsH * renderScale)));

            Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Matrix matrix = new Matrix();
            matrix.postTranslate(-bounds.left + padding, -bounds.top + padding);
            matrix.postScale(renderScale, renderScale);

            Path drawPath = new Path();
            rawPath.transform(matrix, drawPath);

            if (Color.alpha(fillColor) > 0) {
                Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(fillColor);
                canvas.drawPath(drawPath, fillPaint);
            }

            if (strokeWidth > 0 && Color.alpha(strokeColor) > 0) {
                Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setColor(strokeColor);
                strokePaint.setStrokeWidth(strokeWidth * renderScale);
                strokePaint.setStrokeJoin(Paint.Join.ROUND);
                strokePaint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawPath(drawPath, strokePaint);
            }

            float scriptX;
            float scriptY;

            if (posX >= 0 && posY >= 0) {
                scriptX = posX + bounds.left - padding;
                scriptY = posY + bounds.top - padding;
            } else {
                scriptX = bounds.left - padding;
                scriptY = bounds.top - padding;

                if (scriptX < 0 || scriptY < 0) {
                    if (anVal == 7 || anVal == 8 || anVal == 9) {
                        scriptX = (anVal == 8) ? ((playResX - boundsW) / 2.0f) : ((anVal == 9) ? (playResX - boundsW - 20) : 20);
                        scriptY = 0.05f * playResY;
                    } else if (anVal == 4 || anVal == 5 || anVal == 6) {
                        scriptX = (anVal == 5) ? ((playResX - boundsW) / 2.0f) : ((anVal == 6) ? (playResX - boundsW - 20) : 20);
                        scriptY = (playResY - boundsH) / 2.0f;
                    } else {
                        scriptX = (anVal == 2) ? ((playResX - boundsW) / 2.0f) : ((anVal == 3) ? (playResX - boundsW - 20) : 20);
                        scriptY = playResY - boundsH - (0.06f * playResY);
                    }
                }
            }

            float normX = Math.max(0.0f, Math.min(1.0f, scriptX / playResX));
            float normY = Math.max(0.0f, Math.min(1.0f, scriptY / playResY));
            float normW = Math.max(0.001f, Math.min(1.0f, boundsW / playResX));
            float normH = Math.max(0.001f, Math.min(1.0f, boundsH / playResY));

            return new Cue.Builder()
                    .setBitmap(bitmap)
                    .setPosition(normX)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                    .setLine(normY, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_START)
                    .setSize(normW)
                    .setBitmapHeight(normH)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private static void applyAnAlignment(Cue.Builder builder, int an) {
        switch (an) {
            case 7:
                builder.setPosition(0.05f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                       .setLine(0.05f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_NORMAL);
                break;
            case 8:
                builder.setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                       .setLine(0.05f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER);
                break;
            case 9:
                builder.setPosition(0.95f).setPositionAnchor(Cue.ANCHOR_TYPE_END)
                       .setLine(0.05f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_OPPOSITE);
                break;
            case 4:
                builder.setPosition(0.05f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                       .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_NORMAL);
                break;
            case 5:
                builder.setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                       .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER);
                break;
            case 6:
                builder.setPosition(0.95f).setPositionAnchor(Cue.ANCHOR_TYPE_END)
                       .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_OPPOSITE);
                break;
            case 1:
                builder.setPosition(0.05f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                       .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.ANCHOR_TYPE_END)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_NORMAL);
                break;
            case 3:
                builder.setPosition(0.95f).setPositionAnchor(Cue.ANCHOR_TYPE_END)
                       .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.ANCHOR_TYPE_END)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_OPPOSITE);
                break;
            case 2:
            default:
                builder.setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                       .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.ANCHOR_TYPE_END)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER);
                break;
        }
    }

    private static Integer parseAssColor(String rawHex) {
        if (rawHex == null) return null;
        String clean = rawHex.replaceAll("(?i)[&H#]", "").trim();
        if (clean.isEmpty()) return null;

        while (clean.length() < 6) {
            clean = "0" + clean;
        }

        try {
            if (clean.length() == 6) {
                int b = Integer.parseInt(clean.substring(0, 2), 16);
                int g = Integer.parseInt(clean.substring(2, 4), 16);
                int r = Integer.parseInt(clean.substring(4, 6), 16);
                return Color.argb(255, r, g, b);
            } else if (clean.length() >= 8) {
                String hex8 = clean.substring(clean.length() - 8);
                int assAlpha = Integer.parseInt(hex8.substring(0, 2), 16);
                int alpha = Math.max(0, Math.min(255, 255 - assAlpha));
                int b = Integer.parseInt(hex8.substring(2, 4), 16);
                int g = Integer.parseInt(hex8.substring(4, 6), 16);
                int r = Integer.parseInt(hex8.substring(6, 8), 16);
                return Color.argb(alpha, r, g, b);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isAssDrawingPath(String str) {
        if (str == null || str.trim().isEmpty()) return false;
        String trimmed = str.trim();

        // 1. Explicit ASS drawing tag \p1, \p2, etc. (must not be \p0)
        if (java.util.regex.Pattern.compile("(?i)\\{\\\\p[1-9][^\\}]*\\}").matcher(trimmed).find()) {
            return true;
        }

        // 2. Pure drawing path syntax (only vector command letters followed by numbers, no regular words)
        String cleanText = trimmed.replaceAll("\\{([^\\}]+)\\}", "").trim();
        if (cleanText.isEmpty()) return false;

        return cleanText.matches("(?i)^[mlbspcn0-9\\.\\-\\s]+$")
                && cleanText.matches("(?i)^(?:[mlbspcn]\\s+-?\\d+(?:\\.\\d+)?(?:\\s+|$)){2,}.*");
    }

    private static class AssStyleState {
        Integer color = null;
        Integer outlineColor = null;
        String font = null;
        Integer fontSize = null;
        Boolean bold = null;
        Boolean italic = null;
        Boolean underline = null;
        Boolean strikethrough = null;

        void reset() {
            color = null;
            outlineColor = null;
            font = null;
            fontSize = null;
            bold = null;
            italic = null;
            underline = null;
            strikethrough = null;
        }
    }

    private float getScriptResX(float posX, float posY, String raw) {
        ExoPlayer p = callback != null ? callback.getPlayer() : null;
        if (p != null && p.getVideoSize() != null && p.getVideoSize().width > 0) {
            return (float) p.getVideoSize().width;
        }
        if (posX > 1280 || posY > 720 || (raw != null && (raw.contains("1920") || raw.contains("1080")))) {
            return 1920.0f;
        }
        return 1280.0f;
    }

    private float getScriptResY(float posX, float posY, String raw) {
        ExoPlayer p = callback != null ? callback.getPlayer() : null;
        if (p != null && p.getVideoSize() != null && p.getVideoSize().height > 0) {
            return (float) p.getVideoSize().height;
        }
        if (posX > 1280 || posY > 720 || (raw != null && (raw.contains("1920") || raw.contains("1080")))) {
            return 1080.0f;
        }
        return 720.0f;
    }

    public Cue processAssCue(Cue cue) {
        if (cue == null || cue.text == null) return null;
        CharSequence text = cue.text;
        if (text.length() == 0) return null;

        String raw = text.toString();
        if (raw.trim().isEmpty()) return null;

        // 1. Check for ASS vector drawing
        if (isAssDrawingPath(raw)) {
            float posX = -1.0f;
            float posY = -1.0f;
            java.util.regex.Matcher posMatcher = java.util.regex.Pattern.compile("(?i)\\\\pos\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)").matcher(raw);
            if (posMatcher.find()) {
                try {
                    posX = Float.parseFloat(posMatcher.group(1));
                    posY = Float.parseFloat(posMatcher.group(2));
                } catch (Exception ignored) {}
            }

            int anVal = 7;
            java.util.regex.Matcher anMatcher = java.util.regex.Pattern.compile("(?i)\\\\an([1-9])").matcher(raw);
            if (anMatcher.find()) {
                try {
                    anVal = Integer.parseInt(anMatcher.group(1));
                } catch (Exception ignored) {}
            }

            int fillColor = Color.WHITE;
            java.util.regex.Matcher colorMatcher = java.util.regex.Pattern.compile("(?i)\\\\(?:1c|c)[&H#]*([0-9a-fA-F]{1,8})&?").matcher(raw);
            if (colorMatcher.find()) {
                Integer c = parseAssColor(colorMatcher.group(1));
                if (c != null) fillColor = c;
            }

            int strokeColor = Color.BLACK;
            java.util.regex.Matcher outlineColorMatcher = java.util.regex.Pattern.compile("(?i)\\\\3c[&H#]*([0-9a-fA-F]{1,8})&?").matcher(raw);
            if (outlineColorMatcher.find()) {
                Integer c = parseAssColor(outlineColorMatcher.group(1));
                if (c != null) strokeColor = c;
            }

            float strokeWidth = 2.0f;
            java.util.regex.Matcher bordMatcher = java.util.regex.Pattern.compile("(?i)\\\\bord(\\d+(?:\\.\\d+)?)").matcher(raw);
            if (bordMatcher.find()) {
                try {
                    strokeWidth = Float.parseFloat(bordMatcher.group(1));
                } catch (Exception ignored) {}
            }

            float pScale = 1.0f;
            java.util.regex.Matcher pMatcher = java.util.regex.Pattern.compile("(?i)\\\\p([1-9])").matcher(raw);
            if (pMatcher.find()) {
                int pLevel = Integer.parseInt(pMatcher.group(1));
                pScale = 1.0f / (float) (1 << (pLevel - 1));
            }

            String drawingCommands = null;
            java.util.regex.Matcher pBlockMatcher = java.util.regex.Pattern.compile("(?i)\\{\\\\p[1-9]\\}(.*?)(?:\\{\\\\p0\\}|$)", java.util.regex.Pattern.DOTALL).matcher(raw);
            if (pBlockMatcher.find()) {
                drawingCommands = pBlockMatcher.group(1).replaceAll("\\{([^\\}]+)\\}", "").trim();
            } else {
                drawingCommands = raw.replaceAll("\\{([^\\}]+)\\}", "").trim();
            }

            float playResX = getScriptResX(posX, posY, raw);
            float playResY = getScriptResY(posX, posY, raw);

            AssDrawingResult drawingResult = parseAssPath(drawingCommands, pScale);
            if (drawingResult != null) {
                Cue vectorCue = renderAssVectorCue(drawingResult.path, drawingResult.bounds, fillColor, strokeColor, strokeWidth,
                        posX, posY, anVal, playResX, playResY);
                if (vectorCue != null) {
                    return vectorCue;
                }
            }
        }

        // If no ASS tags or escape sequences exist, return original cue directly (preserves Media3 spans and default positioning)
        if (!raw.contains("{") && !raw.contains("\\N") && !raw.contains("\\n") && !raw.contains("\\h")) {
            return cue;
        }

        // 2. Process ASS Dialogue / Subtitle Text with formatting tags
        Cue.Builder builder = cue.buildUpon();

        String rawCleaned = raw;
        rawCleaned = rawCleaned.replaceAll("(?i)\\{\\\\p[1-9]\\}[^\\{]*(\\{\\\\p0\\})?", "");

        if (rawCleaned.replaceAll("\\{([^\\}]+)\\}", "").trim().isEmpty()) {
            return null;
        }

        SpannableStringBuilder cleanSsb = new SpannableStringBuilder();
        AssStyleState styleState = new AssStyleState();
        Context ctx = callback != null ? callback.getContext() : null;

        int anVal = -1;

        int index = 0;
        int len = rawCleaned.length();

        while (index < len) {
            int tagOpen = rawCleaned.indexOf('{', index);
            if (tagOpen == -1) {
                String textSegment = rawCleaned.substring(index);
                appendCleanSegment(cleanSsb, textSegment, styleState, ctx);
                break;
            }

            if (tagOpen > index) {
                String textSegment = rawCleaned.substring(index, tagOpen);
                appendCleanSegment(cleanSsb, textSegment, styleState, ctx);
            }

            int tagClose = rawCleaned.indexOf('}', tagOpen);
            if (tagClose == -1) {
                String textSegment = rawCleaned.substring(tagOpen);
                appendCleanSegment(cleanSsb, textSegment, styleState, ctx);
                break;
            }

            String tagBlock = rawCleaned.substring(tagOpen + 1, tagClose);
            index = tagClose + 1;

            // Extract alignment \an
            java.util.regex.Matcher anMatcher = java.util.regex.Pattern.compile("(?i)\\\\an([1-9])").matcher(tagBlock);
            if (anMatcher.find()) {
                anVal = Integer.parseInt(anMatcher.group(1));
                applyAnAlignment(builder, anVal);
            }

            // Extract position \pos(x,y) or \move(x1,y1,x2,y2)
            java.util.regex.Matcher posMatcher = java.util.regex.Pattern.compile("(?i)\\\\(?:pos|move)\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)[^\\)]*\\)").matcher(tagBlock);
            if (posMatcher.find()) {
                try {
                    float px = Float.parseFloat(posMatcher.group(1));
                    float py = Float.parseFloat(posMatcher.group(2));
                    float playResX = getScriptResX(px, py, rawCleaned);
                    float playResY = getScriptResY(px, py, rawCleaned);

                    float normX = Math.max(0.0f, Math.min(1.0f, px / playResX));
                    float normY = Math.max(0.0f, Math.min(1.0f, py / playResY));

                    int targetAn = anVal > 0 ? anVal : 2;
                    int xAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                    int yAnchor = Cue.ANCHOR_TYPE_END;
                    if (targetAn == 1 || targetAn == 4 || targetAn == 7) xAnchor = Cue.ANCHOR_TYPE_START;
                    else if (targetAn == 3 || targetAn == 6 || targetAn == 9) xAnchor = Cue.ANCHOR_TYPE_END;

                    if (targetAn >= 7) yAnchor = Cue.ANCHOR_TYPE_START;
                    else if (targetAn >= 4) yAnchor = Cue.ANCHOR_TYPE_MIDDLE;

                    builder.setPosition(normX).setPositionAnchor(xAnchor)
                           .setLine(normY, Cue.LINE_TYPE_FRACTION).setLineAnchor(yAnchor);
                } catch (Exception ignored) {}
            }

            // Color \c or \1c
            java.util.regex.Matcher colorMatcher = java.util.regex.Pattern.compile("(?i)\\\\(?:1c|c)[&H#]*([0-9a-fA-F]{1,8})&?").matcher(tagBlock);
            if (colorMatcher.find()) {
                styleState.color = parseAssColor(colorMatcher.group(1));
            }

            // Outline Color \3c
            java.util.regex.Matcher outlineColorMatcher = java.util.regex.Pattern.compile("(?i)\\\\3c[&H#]*([0-9a-fA-F]{1,8})&?").matcher(tagBlock);
            if (outlineColorMatcher.find()) {
                styleState.outlineColor = parseAssColor(outlineColorMatcher.group(1));
            }

            // Font \fn
            java.util.regex.Matcher fontMatcher = java.util.regex.Pattern.compile("(?i)\\\\fn([^\\\\}]+)").matcher(tagBlock);
            if (fontMatcher.find()) {
                String fn = fontMatcher.group(1).trim();
                styleState.font = fn.isEmpty() ? null : fn;
            }

            // Font Size \fs
            java.util.regex.Matcher sizeMatcher = java.util.regex.Pattern.compile("(?i)\\\\fs(\\d+)").matcher(tagBlock);
            if (sizeMatcher.find()) {
                try {
                    styleState.fontSize = Integer.parseInt(sizeMatcher.group(1));
                } catch (Exception ignored) {}
            }

            // Bold \b
            java.util.regex.Matcher boldMatcher = java.util.regex.Pattern.compile("(?i)\\\\b([01]|\\d{3})").matcher(tagBlock);
            if (boldMatcher.find()) {
                String val = boldMatcher.group(1);
                styleState.bold = "1".equals(val) || (val.length() == 3 && !val.equals("000"));
            }

            // Italic \i
            java.util.regex.Matcher italicMatcher = java.util.regex.Pattern.compile("(?i)\\\\i([01])").matcher(tagBlock);
            if (italicMatcher.find()) {
                styleState.italic = "1".equals(italicMatcher.group(1));
            }

            // Underline tag
            java.util.regex.Matcher underlineMatcher = java.util.regex.Pattern.compile("(?i)\\\\u([01])").matcher(tagBlock);
            if (underlineMatcher.find()) {
                styleState.underline = "1".equals(underlineMatcher.group(1));
            }

            // Strikethrough \s
            java.util.regex.Matcher strikeMatcher = java.util.regex.Pattern.compile("(?i)\\\\s([01])").matcher(tagBlock);
            if (strikeMatcher.find()) {
                styleState.strikethrough = "1".equals(strikeMatcher.group(1));
            }

            // Reset \r
            if (tagBlock.matches("(?i).*\\\\r.*")) {
                styleState.reset();
            }
        }

        if (cleanSsb.toString().trim().isEmpty()) {
            return null;
        }

        return builder.setText(cleanSsb).build();
    }

    private void appendCleanSegment(SpannableStringBuilder ssb, String segment, AssStyleState style, Context ctx) {
        if (segment == null || segment.isEmpty()) return;

        String converted = segment.replace("\\N", "\n")
                                  .replace("\\n", "\n")
                                  .replace("\\h", "\u00A0");

        int start = ssb.length();
        ssb.append(converted);
        int end = ssb.length();

        if (end > start) {
            if (style.color != null) {
                ssb.setSpan(new ForegroundColorSpan(style.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (style.font != null && ctx != null) {
                Typeface tf = FontResolver.resolveTypeface(ctx, style.font,
                        style.bold != null && style.bold,
                        style.italic != null && style.italic);
                ssb.setSpan(new CustomTypefaceSpan(tf), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (style.fontSize != null && style.fontSize > 0) {
                // Scale ASS script font size proportionally so it does not explode on high density screens
                float scaledSp = (style.fontSize / 34.0f) * subtitleTextSize;
                scaledSp = Math.max(11f, Math.min(32f, scaledSp));
                ssb.setSpan(new AbsoluteSizeSpan(Math.round(scaledSp), true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (style.bold != null || style.italic != null) {
                boolean b = (style.bold != null && style.bold);
                boolean it = (style.italic != null && style.italic);
                if (b && it) {
                    ssb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (b) {
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (it) {
                    ssb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (style.underline != null && style.underline) {
                ssb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (style.strikethrough != null && style.strikethrough) {
                ssb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    public List<Cue> resolveCueCollisions(List<Cue> cues) {
        if (cues == null || cues.isEmpty()) return Collections.emptyList();
        if (cues.size() == 1) return cues;

        // 1. Deduplicate identical layer/karaoke cues
        List<Cue> uniqueCues = new ArrayList<>();
        for (Cue cue : cues) {
            if (cue == null) continue;
            boolean isDuplicate = false;
            if (cue.text != null) {
                String str1 = cue.text.toString().trim();
                for (Cue existing : uniqueCues) {
                    if (existing != null && existing.text != null) {
                        String str2 = existing.text.toString().trim();
                        if (str1.equals(str2) && Math.abs(cue.position - existing.position) < 0.05f) {
                            isDuplicate = true;
                            break;
                        }
                    }
                }
            }
            if (!isDuplicate) {
                uniqueCues.add(cue);
            }
        }

        if (uniqueCues.size() <= 1) return uniqueCues;

        // 2. Check if multiple bottom unpositioned cues collide
        List<Cue> unpositionedBottomCues = new ArrayList<>();
        List<Cue> otherCues = new ArrayList<>();

        for (Cue cue : uniqueCues) {
            if (cue.bitmap == null && (cue.line == Cue.DIMEN_UNSET || cue.lineType == Cue.TYPE_UNSET)) {
                unpositionedBottomCues.add(cue);
            } else {
                otherCues.add(cue);
            }
        }

        if (unpositionedBottomCues.size() <= 1) {
            return uniqueCues;
        }

        List<Cue> result = new ArrayList<>(otherCues);
        result.add(unpositionedBottomCues.get(0));

        float currentLine = 0.85f;
        for (int i = 1; i < unpositionedBottomCues.size(); i++) {
            Cue cue = unpositionedBottomCues.get(i);
            int lineCount = 1;
            if (cue.text != null) {
                String s = cue.text.toString();
                for (int c = 0; c < s.length(); c++) {
                    if (s.charAt(c) == '\n') lineCount++;
                }
            }
            float cueHeightFraction = Math.max(0.045f, 0.038f * lineCount + 0.008f);

            Cue.Builder b = cue.buildUpon();
            b.setLine(Math.max(0.15f, currentLine), Cue.LINE_TYPE_FRACTION)
             .setLineAnchor(Cue.ANCHOR_TYPE_END);
            result.add(b.build());

            currentLine -= (cueHeightFraction + 0.012f);
        }

        return result;
    }
}
