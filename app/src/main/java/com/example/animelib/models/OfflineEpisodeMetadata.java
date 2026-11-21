package com.example.animelib.models;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Модели и утилиты сериализации метаданных скачанных эпизодов:
 * локальные субтитры, сегменты (таймкоды), нормализация путей и сортировка серий.
 */
public final class OfflineEpisodeMetadata {

    private static final Gson GSON = new Gson();
    private static final Type SUBTITLE_LIST_TYPE = new TypeToken<List<DownloadedSubtitleInfo>>() {}.getType();
    private static final Type TIMECODE_LIST_TYPE = new TypeToken<List<EpisodeResponse.TimecodeData>>() {}.getType();
    private static final Pattern LEADING_NUMBER_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)");

    private OfflineEpisodeMetadata() {
    }

    /**
     * Метаданные одной скачанной дорожки субтитров.
     */
    public static class DownloadedSubtitleInfo {
        private int id;
        private String format;
        private String name;
        private String filename;
        private String localPath;
        private long fileSize;

        public DownloadedSubtitleInfo() {
        }

        public DownloadedSubtitleInfo(int id, String format, String name, String filename, String localPath, long fileSize) {
            this.id = id;
            this.format = format;
            this.name = name;
            this.filename = filename;
            this.localPath = localPath;
            this.fileSize = fileSize;
        }

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }

        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }

        public String getLocalPath() { return localPath; }
        public void setLocalPath(String localPath) { this.localPath = localPath; }

        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }

        public String getDisplayLabel(int fallbackIndex) {
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
            if (filename != null && !filename.trim().isEmpty()) {
                return filename.trim();
            }
            String fmt = format != null && !format.trim().isEmpty()
                    ? format.trim().toUpperCase(Locale.ROOT)
                    : "SUB";
            return "Субтитры (" + fmt + (fallbackIndex > 0 ? " #" + (fallbackIndex + 1) : "") + ")";
        }
    }

    @Nullable
    public static String toSubtitlesJson(@Nullable List<DownloadedSubtitleInfo> subtitles) {
        if (subtitles == null || subtitles.isEmpty()) {
            return null;
        }
        try {
            return GSON.toJson(subtitles, SUBTITLE_LIST_TYPE);
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    public static List<DownloadedSubtitleInfo> parseSubtitlesJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<DownloadedSubtitleInfo> parsed = GSON.fromJson(json, SUBTITLE_LIST_TYPE);
            if (parsed == null) return Collections.emptyList();
            List<DownloadedSubtitleInfo> valid = new ArrayList<>();
            for (DownloadedSubtitleInfo info : parsed) {
                if (info != null && info.getLocalPath() != null && !info.getLocalPath().trim().isEmpty()) {
                    valid.add(info);
                }
            }
            return valid;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    @NonNull
    public static List<DownloadedSubtitleInfo> getExistingSubtitles(@Nullable DownloadedEpisodeEntity ep) {
        if (ep == null) return Collections.emptyList();
        List<DownloadedSubtitleInfo> parsed = parseSubtitlesJson(ep.getSubtitlesJson());
        if (parsed.isEmpty()) return Collections.emptyList();
        List<DownloadedSubtitleInfo> existing = new ArrayList<>();
        for (DownloadedSubtitleInfo info : parsed) {
            File f = new File(info.getLocalPath());
            if (f.exists() && f.length() > 0) {
                existing.add(info);
            }
        }
        return existing;
    }

    @Nullable
    public static String toTimecodesJson(@Nullable List<EpisodeResponse.TimecodeData> timecodes) {
        if (timecodes == null || timecodes.isEmpty()) {
            return null;
        }
        List<EpisodeResponse.TimecodeData> clean = new ArrayList<>();
        for (EpisodeResponse.TimecodeData tc : timecodes) {
            if (tc != null && tc.getType() != null && !tc.getType().trim().isEmpty() && tc.getToMs() > tc.getFromMs()) {
                clean.add(tc);
            }
        }
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return GSON.toJson(clean, TIMECODE_LIST_TYPE);
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    public static List<EpisodeResponse.TimecodeData> parseTimecodesJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<EpisodeResponse.TimecodeData> parsed = GSON.fromJson(json, TIMECODE_LIST_TYPE);
            if (parsed == null) return Collections.emptyList();
            List<EpisodeResponse.TimecodeData> valid = new ArrayList<>();
            for (EpisodeResponse.TimecodeData tc : parsed) {
                if (tc != null && tc.getType() != null && !tc.getType().trim().isEmpty() && tc.getToMs() > tc.getFromMs()) {
                    valid.add(tc);
                }
            }
            return valid;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public static boolean hasSubtitles(@Nullable DownloadedEpisodeEntity ep) {
        if (ep == null) return false;
        if (!getExistingSubtitles(ep).isEmpty()) {
            return true;
        }
        return !com.example.animelib.util.FileUtils.findLocalSubtitleFilesForVideo(ep.getLocalFilePath()).isEmpty();
    }

    public static boolean hasTimecodes(@Nullable DownloadedEpisodeEntity ep) {
        return ep != null && !parseTimecodesJson(ep.getTimecodesJson()).isEmpty();
    }

    /**
     * Нормализует локальный путь или URI формата {@code file:///...} к обычному абсолютному пути ФС.
     * Возвращает {@code null} для сетевых URL ({@code http://}, {@code https://}) и пустых строк.
     */
    @Nullable
    public static String normalizeLocalFilePath(@Nullable String rawPathOrUri) {
        if (rawPathOrUri == null) return null;
        String trimmed = rawPathOrUri.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("/")) {
            return trimmed;
        }
        if (trimmed.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                URI uri = URI.create(trimmed);
                if (uri.getPath() != null && !uri.getPath().isEmpty()) {
                    return uri.getPath();
                }
            } catch (Exception ignored) {
                // Fallback for unescaped characters in file: URI
            }
            String stripped = trimmed.substring(5);
            if (stripped.startsWith("//localhost/")) {
                stripped = stripped.substring("//localhost".length());
            } else if (stripped.startsWith("//")) {
                stripped = stripped.substring(2);
            }
            return stripped.startsWith("/") ? stripped : "/" + stripped;
        }
        return null;
    }

    public static boolean isLocalMediaPath(@Nullable String rawPathOrUri) {
        return normalizeLocalFilePath(rawPathOrUri) != null;
    }

    /**
     * Извлекает числовой порядок серии с поддержкой дробных номеров (например, "12.5", "12,5", "OVA 2").
     */
    public static double parseEpisodeOrder(@Nullable String episodeNumber) {
        if (episodeNumber == null) return Double.MAX_VALUE;
        String trimmed = episodeNumber.trim();
        if (trimmed.isEmpty()) return Double.MAX_VALUE;
        try {
            return Double.parseDouble(trimmed.replace(',', '.'));
        } catch (NumberFormatException ignored) {
        }
        Matcher m = LEADING_NUMBER_PATTERN.matcher(trimmed);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1).replace(',', '.'));
            } catch (NumberFormatException ignored) {
            }
        }
        return Double.MAX_VALUE;
    }

    /**
     * Корректное русское склонение количества серий ("1 серия", "2 серии", "5 серий", "21 серия").
     */
    @NonNull
    public static String formatRussianEpisodesCount(int count) {
        int abs = Math.abs(count);
        int mod100 = abs % 100;
        int mod10 = abs % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return count + " серий";
        }
        if (mod10 == 1) {
            return count + " серия";
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return count + " серии";
        }
        return count + " серий";
    }
}
