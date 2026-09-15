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
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
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
                    .setSelectionFlags(isPreferred ? C.SELECTION_FLAG_DEFAULT : 0)
                    .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                    .build();

            configs.add(config);
        }

        return configs;
    }

    public String getMimeTypeForSubtitle(String format, String url) {
        if (format != null) {
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
            if (lowerUrl.endsWith(".ass") || lowerUrl.endsWith(".ssa")) {
                return MimeTypes.TEXT_SSA;
            } else if (lowerUrl.endsWith(".vtt")) {
                return MimeTypes.TEXT_VTT;
            } else if (lowerUrl.endsWith(".srt")) {
                return MimeTypes.APPLICATION_SUBRIP;
            }
        }
        return MimeTypes.TEXT_UNKNOWN;
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
                List<Cue> processedCues = new ArrayList<>();
                for (Cue cue : cueGroup.cues) {
                    if (cue.text != null) {
                        Cue processed = processAssCue(cue);
                        if (processed != null && ((processed.text != null && processed.text.length() > 0) || processed.bitmap != null)) {
                            processedCues.add(processed);
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
                Tracks.Group firstSupportedTextGroup = null;

                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getType() == C.TRACK_TYPE_TEXT) {
                        if (group.isSelected()) {
                            hasSelectedTextTrack = true;
                            break;
                        } else if (firstSupportedTextGroup == null && group.isSupported()) {
                            firstSupportedTextGroup = group;
                        }
                    }
                }

                if (!hasSelectedTextTrack && firstSupportedTextGroup != null) {
                    Log.d("PlayerSubtitlesController", "No text track auto-selected by Media3. Forcing selection of text track");
                    try {
                        TrackSelectionParameters newParams = player.getTrackSelectionParameters()
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(new TrackSelectionOverride(firstSupportedTextGroup.getMediaTrackGroup(), 0))
                                .build();
                        player.setTrackSelectionParameters(newParams);
                    } catch (Exception e) {
                        Log.e("PlayerSubtitlesController", "Failed to force text track selection", e);
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

    public static class AssPathSpan extends ReplacementSpan {
        private final Path rawPath;
        private final int fillColor;
        private final int strokeColor;
        private final float strokeWidth;
        private final float posX;
        private final float posY;
        private final float playResX;
        private final float playResY;

        public AssPathSpan(Path rawPath, int fillColor, int strokeColor, float strokeWidth, float posX, float posY, float playResX, float playResY) {
            this.rawPath = rawPath;
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            this.strokeWidth = strokeWidth;
            this.posX = posX;
            this.posY = posY;
            this.playResX = playResX > 0 ? playResX : 1280.0f;
            this.playResY = playResY > 0 ? playResY : 720.0f;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                fm.ascent = 0;
                fm.top = 0;
                fm.descent = 0;
                fm.bottom = 0;
            }
            return 1;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            if (rawPath == null) return;
            canvas.save();

            float canvasW = canvas.getWidth();
            float canvasH = canvas.getHeight();
            if (canvasW <= 0) canvasW = 1280.0f;
            if (canvasH <= 0) canvasH = 720.0f;

            float scaleX = canvasW / playResX;
            float scaleY = canvasH / playResY;

            Matrix matrix = new Matrix();
            if (posX >= 0 && posY >= 0) {
                matrix.postTranslate(posX, posY);
            }
            matrix.postScale(scaleX, scaleY);

            Path transformedPath = new Path();
            rawPath.transform(matrix, transformedPath);

            if (fillColor != Color.TRANSPARENT) {
                Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(fillColor);
                canvas.drawPath(transformedPath, fillPaint);
            }

            if (strokeColor != Color.TRANSPARENT && strokeWidth > 0) {
                Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setColor(strokeColor);
                strokePaint.setStrokeWidth(strokeWidth * ((scaleX + scaleY) / 2.0f));
                canvas.drawPath(transformedPath, strokePaint);
            }

            canvas.restore();
        }
    }

    private static Path parseAssPath(String drawingCommands, float scale) {
        if (drawingCommands == null || drawingCommands.trim().isEmpty()) return null;
        try {
            Path path = new Path();
            String[] tokens = drawingCommands.trim().split("[\\s,]+");
            char currentCmd = 'm';
            int i = 0;
            boolean hasPoints = false;
            while (i < tokens.length) {
                String token = tokens[i].trim();
                if (token.isEmpty()) { i++; continue; }
                char c = Character.toLowerCase(token.charAt(0));
                if (c == 'm' || c == 'n' || c == 'l' || c == 'b' || c == 's' || c == 'p' || c == 'c') {
                    currentCmd = c;
                    i++;
                    if (c == 'c') {
                        path.close();
                        continue;
                    }
                    if (i >= tokens.length) break;
                }
                if (currentCmd == 'm' || currentCmd == 'n') {
                    if (i + 1 < tokens.length) {
                        float x = Float.parseFloat(tokens[i]) * scale;
                        float y = Float.parseFloat(tokens[i + 1]) * scale;
                        path.moveTo(x, y);
                        hasPoints = true;
                        i += 2;
                        currentCmd = 'l';
                    } else { i++; }
                } else if (currentCmd == 'l' || currentCmd == 's' || currentCmd == 'p') {
                    if (i + 1 < tokens.length) {
                        float x = Float.parseFloat(tokens[i]) * scale;
                        float y = Float.parseFloat(tokens[i + 1]) * scale;
                        path.lineTo(x, y);
                        hasPoints = true;
                        i += 2;
                    } else { i++; }
                } else if (currentCmd == 'b') {
                    if (i + 5 < tokens.length) {
                        float x1 = Float.parseFloat(tokens[i]) * scale;
                        float y1 = Float.parseFloat(tokens[i + 1]) * scale;
                        float x2 = Float.parseFloat(tokens[i + 2]) * scale;
                        float y2 = Float.parseFloat(tokens[i + 3]) * scale;
                        float x3 = Float.parseFloat(tokens[i + 4]) * scale;
                        float y3 = Float.parseFloat(tokens[i + 5]) * scale;
                        path.cubicTo(x1, y1, x2, y2, x3, y3);
                        hasPoints = true;
                        i += 6;
                    } else { i++; }
                } else {
                    i++;
                }
            }
            return hasPoints ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap renderAssVectorPath(Path rawPath, int fillColor, int strokeColor, float strokeWidth, float pScale) {
        if (rawPath == null) return null;
        try {
            Path scaledPath = new Path();
            Matrix scaleMatrix = new Matrix();
            scaleMatrix.setScale(pScale, pScale);
            rawPath.transform(scaleMatrix, scaledPath);

            RectF bounds = new RectF();
            scaledPath.computeBounds(bounds, true);

            int padding = Math.max(4, Math.round(strokeWidth * 2));
            int bmpWidth = Math.max(1, Math.round(bounds.width()) + padding * 2);
            int bmpHeight = Math.max(1, Math.round(bounds.height()) + padding * 2);

            if (bmpWidth > 2048) bmpWidth = 2048;
            if (bmpHeight > 2048) bmpHeight = 2048;

            Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Matrix translate = new Matrix();
            translate.postTranslate(-bounds.left + padding, -bounds.top + padding);
            Path drawPath = new Path();
            scaledPath.transform(translate, drawPath);

            if (fillColor != Color.TRANSPARENT) {
                Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(fillColor);
                canvas.drawPath(drawPath, fillPaint);
            }

            if (strokeColor != Color.TRANSPARENT && strokeWidth > 0) {
                Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setColor(strokeColor);
                strokePaint.setStrokeWidth(strokeWidth);
                strokePaint.setStrokeJoin(Paint.Join.ROUND);
                strokePaint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawPath(drawPath, strokePaint);
            }

            return bitmap;
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
            case 2:
                builder.setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                       .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.ANCHOR_TYPE_END)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER);
                break;
            case 3:
                builder.setPosition(0.95f).setPositionAnchor(Cue.ANCHOR_TYPE_END)
                       .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.ANCHOR_TYPE_END)
                       .setTextAlignment(android.text.Layout.Alignment.ALIGN_OPPOSITE);
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

        if (trimmed.matches("(?i).*\\{\\\\p[1-9]\\}.*")) {
            return true;
        }

        String cleanText = trimmed.replaceAll("\\{([^\\}]+)\\}", "").trim();
        if (cleanText.matches("(?i)^(?:[mlbspcn]\\s+-?\\d+(?:\\.\\d+)?(?:\\s+|$))+.*")) {
            return true;
        }

        String[] tokens = cleanText.split("\\s+");
        if (tokens.length < 3) return false;
        int drawingTokens = 0;
        boolean hasDrawingCmd = false;
        for (String t : tokens) {
            if (t.matches("-?\\d+(?:\\.\\d+)?")) {
                drawingTokens++;
            } else if (t.matches("(?i)^[mlbspcn]$")) {
                drawingTokens++;
                hasDrawingCmd = true;
            }
        }
        return hasDrawingCmd && ((double) drawingTokens / tokens.length) >= 0.5;
    }

    public Cue processAssCue(Cue cue) {
        if (cue == null || cue.text == null) return null;
        CharSequence text = cue.text;
        if (text.length() == 0) return null;

        String raw = text.toString();
        if (raw.trim().isEmpty()) return null;

        Cue.Builder builder = cue.buildUpon();

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

            float playResX = (posX > 1280 || posY > 720 || raw.contains("1920") || raw.contains("1080")) ? 1920.0f : 1280.0f;
            float playResY = (posX > 1280 || posY > 720 || raw.contains("1920") || raw.contains("1080")) ? 1080.0f : 720.0f;

            Path path = parseAssPath(drawingCommands, pScale);
            if (path != null) {
                Bitmap vectorBmp = renderAssVectorPath(path, fillColor, strokeColor, strokeWidth, pScale);
                if (vectorBmp != null) {
                    builder.setBitmap(vectorBmp);
                    if (posX >= 0 && posY >= 0) {
                        float xRatio = Math.max(0.0f, Math.min(1.0f, posX / playResX));
                        float yRatio = Math.max(0.0f, Math.min(1.0f, posY / playResY));
                        builder.setPosition(xRatio)
                               .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                               .setLine(yRatio, Cue.LINE_TYPE_FRACTION)
                               .setLineAnchor(Cue.ANCHOR_TYPE_START)
                               .setSize((float) vectorBmp.getWidth() / playResX);
                    } else {
                        builder.setPosition(0.5f)
                               .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                               .setLine(0.5f, Cue.LINE_TYPE_FRACTION)
                               .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE);
                    }
                    return builder.build();
                }
            }
        }

        builder = cue.buildUpon();
        SpannableStringBuilder ssb = new SpannableStringBuilder(text);

        String str = ssb.toString();
        java.util.regex.Matcher pBlockMatcher = java.util.regex.Pattern.compile("(?i)\\{\\\\p[1-9]\\}[^\\{]*(\\{\\\\p0\\})?").matcher(str);
        while (pBlockMatcher.find()) {
            ssb.delete(pBlockMatcher.start(), pBlockMatcher.end());
            str = ssb.toString();
            pBlockMatcher = java.util.regex.Pattern.compile("(?i)\\{\\\\p[1-9]\\}[^\\{]*(\\{\\\\p0\\})?").matcher(str);
        }

        str = ssb.toString();
        java.util.regex.Matcher drawingPathMatcher = java.util.regex.Pattern.compile("(?i)(?:^|\\s)(?:m|n|l|b|s|p|c)(?:\\s+-?\\d+(?:\\.\\d+)?\\s*)+").matcher(str);
        while (drawingPathMatcher.find()) {
            ssb.delete(drawingPathMatcher.start(), drawingPathMatcher.end());
            str = ssb.toString();
            drawingPathMatcher = java.util.regex.Pattern.compile("(?i)(?:^|\\s)(?:m|n|l|b|s|p|c)(?:\\s+-?\\d+(?:\\.\\d+)?\\s*)+").matcher(str);
        }

        if (ssb.toString().replaceAll("\\{([^\\}]+)\\}", "").trim().isEmpty()) {
            return null;
        }

        str = ssb.toString();
        int idx;
        while ((idx = str.indexOf("\\N")) != -1) {
            ssb.replace(idx, idx + 2, "\n");
            str = ssb.toString();
        }
        while ((idx = str.indexOf("\\n")) != -1) {
            ssb.replace(idx, idx + 2, "\n");
            str = ssb.toString();
        }
        while ((idx = str.indexOf("\\h")) != -1) {
            ssb.replace(idx, idx + 2, " ");
            str = ssb.toString();
        }

        str = ssb.toString();
        if (str.contains("{")) {
            java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile("\\{([^\\}]+)\\}");

            Integer currentColor = null;
            Integer currentOutlineColor = null;
            String currentFont = null;
            Integer currentSize = null;
            Boolean isBold = null;
            Boolean isItalic = null;
            Boolean isUnderline = null;

            int safetyCounter = 0;
            while (safetyCounter++ < 50) {
                java.util.regex.Matcher matcher = tagPattern.matcher(str);
                if (!matcher.find()) break;

                int tagStart = matcher.start();
                int tagEnd = matcher.end();
                String tagBlock = matcher.group(1);

                int anVal = 2;
                java.util.regex.Matcher anMatcher = java.util.regex.Pattern.compile("(?i)\\\\an([1-9])").matcher(tagBlock);
                if (anMatcher.find()) {
                    anVal = Integer.parseInt(anMatcher.group(1));
                    applyAnAlignment(builder, anVal);
                }

                java.util.regex.Matcher posMatcher = java.util.regex.Pattern.compile("(?i)\\\\pos\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)").matcher(tagBlock);
                if (posMatcher.find()) {
                    try {
                        float px = Float.parseFloat(posMatcher.group(1));
                        float py = Float.parseFloat(posMatcher.group(2));
                        float scriptResX = (px > 1280 || py > 720 || raw.contains("1920") || raw.contains("1080")) ? 1920.0f : 1280.0f;
                        float scriptResY = (px > 1280 || py > 720 || raw.contains("1920") || raw.contains("1080")) ? 1080.0f : 720.0f;

                        float normX = Math.max(0.0f, Math.min(1.0f, px / scriptResX));
                        float normY = Math.max(0.0f, Math.min(1.0f, py / scriptResY));

                        int xAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                        int yAnchor = Cue.ANCHOR_TYPE_END;
                        if (anVal == 1 || anVal == 4 || anVal == 7) xAnchor = Cue.ANCHOR_TYPE_START;
                        else if (anVal == 3 || anVal == 6 || anVal == 9) xAnchor = Cue.ANCHOR_TYPE_END;

                        if (anVal >= 7) yAnchor = Cue.ANCHOR_TYPE_START;
                        else if (anVal >= 4) yAnchor = Cue.ANCHOR_TYPE_MIDDLE;

                        builder.setPosition(normX).setPositionAnchor(xAnchor)
                               .setLine(normY, Cue.LINE_TYPE_FRACTION).setLineAnchor(yAnchor);
                    } catch (Exception ignored) {}
                }

                java.util.regex.Matcher colorMatcher = java.util.regex.Pattern.compile("(?i)\\\\(?:1c|c)[&H#]*([0-9a-fA-F]{1,8})&?").matcher(tagBlock);
                if (colorMatcher.find()) {
                    currentColor = parseAssColor(colorMatcher.group(1));
                }

                java.util.regex.Matcher outlineColorMatcher = java.util.regex.Pattern.compile("(?i)\\\\3c[&H#]*([0-9a-fA-F]{1,8})&?").matcher(tagBlock);
                if (outlineColorMatcher.find()) {
                    currentOutlineColor = parseAssColor(outlineColorMatcher.group(1));
                }

                java.util.regex.Matcher fontMatcher = java.util.regex.Pattern.compile("(?i)\\\\fn([^\\\\}]+)").matcher(tagBlock);
                if (fontMatcher.find()) {
                    currentFont = fontMatcher.group(1).trim();
                    if (currentFont.isEmpty()) currentFont = null;
                }

                java.util.regex.Matcher sizeMatcher = java.util.regex.Pattern.compile("(?i)\\\\fs(\\d+)").matcher(tagBlock);
                if (sizeMatcher.find()) {
                    try {
                        currentSize = Integer.parseInt(sizeMatcher.group(1));
                    } catch (Exception ignored) {}
                }

                java.util.regex.Matcher boldMatcher = java.util.regex.Pattern.compile("(?i)\\\\b([01]|\\d{3})").matcher(tagBlock);
                if (boldMatcher.find()) {
                    String val = boldMatcher.group(1);
                    isBold = "1".equals(val) || (val.length() == 3 && !val.equals("000"));
                }

                java.util.regex.Matcher italicMatcher = java.util.regex.Pattern.compile("(?i)\\\\i([01])").matcher(tagBlock);
                if (italicMatcher.find()) {
                    isItalic = "1".equals(italicMatcher.group(1));
                }

                java.util.regex.Matcher underlineMatcher = java.util.regex.Pattern.compile("(?i)\\\\u([01])").matcher(tagBlock);
                if (underlineMatcher.find()) {
                    isUnderline = "1".equals(underlineMatcher.group(1));
                }

                if (tagBlock.matches("(?i).*\\\\r.*")) {
                    currentColor = null;
                    currentOutlineColor = null;
                    currentFont = null;
                    currentSize = null;
                    isBold = null;
                    isItalic = null;
                    isUnderline = null;
                }

                ssb.delete(tagStart, tagEnd);

                str = ssb.toString();
                int nextTagIndex = str.indexOf('{', tagStart);
                int textSegmentEnd = (nextTagIndex != -1) ? nextTagIndex : str.length();

                Context ctx = callback != null ? callback.getContext() : null;

                if (textSegmentEnd > tagStart) {
                    if (currentColor != null) {
                        ssb.setSpan(new ForegroundColorSpan(currentColor), tagStart, textSegmentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (currentFont != null && ctx != null) {
                        Typeface tf = FontResolver.resolveTypeface(ctx, currentFont, isBold != null && isBold, isItalic != null && isItalic);
                        ssb.setSpan(new CustomTypefaceSpan(tf), tagStart, textSegmentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (currentSize != null && currentSize > 0) {
                        ssb.setSpan(new AbsoluteSizeSpan(currentSize, true), tagStart, textSegmentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (isBold != null || isItalic != null) {
                        boolean b = (isBold != null && isBold);
                        boolean it = (isItalic != null && isItalic);
                        if (b && it) {
                            ssb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), tagStart, textSegmentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        } else if (b) {
                            ssb.setSpan(new StyleSpan(Typeface.BOLD), tagStart, textSegmentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        } else if (it) {
                            ssb.setSpan(new StyleSpan(Typeface.ITALIC), tagStart, textSegmentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    }
                    if (isUnderline != null && isUnderline) {
                        ssb.setSpan(new UnderlineSpan(), tagStart, textSegmentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
        }

        if (ssb.toString().trim().isEmpty()) {
            return null;
        }

        return builder.setText(ssb).build();
    }

    public List<Cue> resolveCueCollisions(List<Cue> cues) {
        if (cues == null || cues.size() <= 1) return cues;

        List<Cue> resolved = new ArrayList<>();
        int unpositionedBottomCount = 0;

        for (Cue cue : cues) {
            if (cue == null) continue;
            boolean isBottomUnpositioned = (cue.line == Cue.DIMEN_UNSET || cue.lineType == Cue.TYPE_UNSET);
            if (isBottomUnpositioned && cue.bitmap == null) {
                Cue.Builder b = cue.buildUpon();
                float linePos = 0.92f - (unpositionedBottomCount * 0.08f);
                b.setLine(Math.max(0.1f, linePos), Cue.LINE_TYPE_FRACTION)
                 .setLineAnchor(Cue.ANCHOR_TYPE_END);
                unpositionedBottomCount++;
                resolved.add(b.build());
            } else {
                resolved.add(cue);
            }
        }
        return resolved;
    }
}
