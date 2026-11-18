package com.example.animelib.util;

import java.io.File;

/**
 * Общие файловые утилиты для безопасного удаления временных сегментов и кэша скачанных эпизодов.
 */
public final class FileUtils {

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
     * Удаляет локальный видеофайл эпизода и сопутствующую папку HLS-сегментов ({@code <file>_segs}).
     */
    public static void deleteDownloadedEpisodeFiles(String localFilePath) {
        if (localFilePath == null || localFilePath.trim().isEmpty()) {
            return;
        }
        File file = new File(localFilePath);
        if (file.exists()) {
            file.delete();
        }
        File parent = file.getParentFile();
        if (parent != null) {
            File segsDir = new File(parent, file.getName() + "_segs");
            deleteRecursively(segsDir);
        }
    }
}
