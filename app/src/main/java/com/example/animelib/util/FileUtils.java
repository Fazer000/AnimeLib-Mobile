package com.example.animelib.util;

import android.os.StatFs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.models.OfflineEpisodeMetadata;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Общие файловые утилиты для безопасного удаления временных сегментов,
 * скачанных субтитров, частичных загрузок и проверки свободного места на диске.
 */
public final class FileUtils {

    /** Минимальный запас свободного места на диске (50 МБ), чтобы не забивать хранилище устройства в ноль. */
    public static final long DEFAULT_STORAGE_SAFETY_BUFFER_BYTES = 50L * 1024L * 1024L;

    private FileUtils() {
    }

    public static void deleteDirectoryContents(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteRecursively(f);
                } else {
                    f.delete();
                }
            }
        }
    }

    public static void deleteRecursively(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) {
            return;
        }
        if (fileOrDir.isDirectory()) {
            deleteDirectoryContents(fileOrDir);
        }
        fileOrDir.delete();
    }

    /**
     * Возвращает базовое имя файла без последнего расширения (например, {@code ep_1_1080p} для {@code ep_1_1080p.mp4}).
     */
    @NonNull
    public static String getBaseNameWithoutExtension(@NonNull File file) {
        String name = file.getName();
        int dotIdx = name.lastIndexOf('.');
        return dotIdx > 0 ? name.substring(0, dotIdx) : name;
    }

    /**
     * Находит все локальные файлы субтитров ({@code <baseName>_sub_*}) рядом с видеофайлом.
     * Игнорирует пустые и незавершённые ({@code .tmp}) файлы.
     */
    @NonNull
    public static List<File> findLocalSubtitleFilesForVideo(@Nullable String localPathOrUri) {
        List<File> result = new ArrayList<>();
        String normalized = OfflineEpisodeMetadata.normalizeLocalFilePath(localPathOrUri);
        if (normalized == null) {
            return result;
        }
        File videoFile = new File(normalized);
        File dir = videoFile.getParentFile();
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return result;
        }

        String prefix = getBaseNameWithoutExtension(videoFile) + "_sub_";
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            if (f != null && f.isFile() && f.length() > 0) {
                String name = f.getName();
                if (name.startsWith(prefix) && !name.endsWith(".tmp")) {
                    result.add(f);
                }
            }
        }
        return result;
    }

    /**
     * Удаляет локальный видеофайл эпизода, временный {@code .part}-файл,
     * папку HLS-сегментов ({@code <file>_segs}) и все сопутствующие файлы субтитров ({@code <base>_sub_*}).
     */
    public static void deleteDownloadedEpisodeFiles(@Nullable String localFilePath) {
        String normalized = OfflineEpisodeMetadata.normalizeLocalFilePath(localFilePath);
        if (normalized == null) {
            return;
        }
        File file = new File(normalized);
        if (file.exists()) {
            file.delete();
        }
        File partFile = new File(normalized + ".part");
        if (partFile.exists()) {
            partFile.delete();
        }
        File parent = file.getParentFile();
        if (parent != null && parent.exists()) {
            File segsDir = new File(parent, file.getName() + "_segs");
            deleteRecursively(segsDir);

            String subPrefix = getBaseNameWithoutExtension(file) + "_sub_";
            File[] siblings = parent.listFiles();
            if (siblings != null) {
                for (File sibling : siblings) {
                    if (sibling != null && sibling.isFile() && sibling.getName().startsWith(subPrefix)) {
                        sibling.delete();
                    }
                }
            }
            deleteDirectoryIfEmpty(parent);
        }
    }

    /**
     * Удаляет только временные артефакты незавершённой загрузки ({@code .part}, {@code _segs}, {@code .tmp} субтитров),
     * не трогая уже готовый {@code .mp4} файл (если он есть).
     */
    public static void deleteDownloadTempFiles(@Nullable String localFilePath) {
        String normalized = OfflineEpisodeMetadata.normalizeLocalFilePath(localFilePath);
        if (normalized == null) {
            return;
        }
        File file = new File(normalized);
        File partFile = new File(normalized + ".part");
        if (partFile.exists()) {
            partFile.delete();
        }
        File parent = file.getParentFile();
        if (parent != null && parent.exists()) {
            File segsDir = new File(parent, file.getName() + "_segs");
            deleteRecursively(segsDir);

            String subPrefix = getBaseNameWithoutExtension(file) + "_sub_";
            File[] siblings = parent.listFiles();
            if (siblings != null) {
                for (File sibling : siblings) {
                    if (sibling != null && sibling.isFile()
                            && sibling.getName().startsWith(subPrefix)
                            && sibling.getName().endsWith(".tmp")) {
                        sibling.delete();
                    }
                }
            }
            deleteDirectoryIfEmpty(parent);
        }
    }

    /**
     * Удаляет локальный постер аниме (если он сохранён на диске) и пустую папку тайтла.
     */
    public static void deleteLocalPosterAndEmptyDir(@Nullable String posterPathOrUrl) {
        String normalized = OfflineEpisodeMetadata.normalizeLocalFilePath(posterPathOrUrl);
        if (normalized == null) {
            return;
        }
        File posterFile = new File(normalized);
        File parent = posterFile.getParentFile();
        if (posterFile.exists() && posterFile.isFile()) {
            posterFile.delete();
        }
        if (parent != null) {
            deleteDirectoryIfEmpty(parent);
        }
    }

    public static void deleteDirectoryIfEmpty(@Nullable File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] remaining = dir.listFiles();
        if (remaining == null || remaining.length == 0) {
            dir.delete();
        }
    }

    /**
     * Подсчитывает суммарный размер эпизода на диске (видеофайл + все скачанные субтитры).
     */
    public static long getEpisodeTotalDiskSize(@Nullable String localFilePath) {
        String normalized = OfflineEpisodeMetadata.normalizeLocalFilePath(localFilePath);
        if (normalized == null) {
            return 0L;
        }
        long total = 0L;
        File videoFile = new File(normalized);
        if (videoFile.exists() && videoFile.isFile()) {
            total += videoFile.length();
        }
        for (File sub : findLocalSubtitleFilesForVideo(normalized)) {
            total += sub.length();
        }
        return total;
    }

    /**
     * Возвращает доступный объём свободной памяти в байтах для указанной директории,
     * либо {@code -1} если определить не удалось.
     */
    public static long getAvailableStorageBytes(@Nullable File dir) {
        if (dir == null) {
            return -1L;
        }
        File target = dir;
        while (target != null && !target.exists()) {
            target = target.getParentFile();
        }
        if (target == null) {
            return -1L;
        }
        try {
            StatFs statFs = new StatFs(target.getAbsolutePath());
            return statFs.getAvailableBytes();
        } catch (Throwable ignored) {
            return target.getUsableSpace() > 0 ? target.getUsableSpace() : -1L;
        }
    }

    /**
     * Проверяет, достаточно ли свободного места на диске с учётом резерва.
     */
    public static boolean hasEnoughStorageSpace(@Nullable File dir, long requiredBytes, long safetyBufferBytes) {
        long available = getAvailableStorageBytes(dir);
        if (available < 0) {
            return true; // Не удалось прочитать StatFs — не блокируем загрузку ложноположительно
        }
        long safeRequired = Math.max(0L, requiredBytes);
        long safeBuffer = Math.max(0L, safetyBufferBytes);
        return available >= (safeRequired + safeBuffer);
    }
}
