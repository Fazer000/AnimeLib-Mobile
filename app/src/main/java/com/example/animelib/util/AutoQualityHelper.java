package com.example.animelib.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class AutoQualityHelper {
    private static final String TAG = "AutoQualityHelper";

    private static void logDebug(String msg) {
        try {
            Log.d(TAG, msg);
        } catch (RuntimeException ignored) {}
    }

    private static void logError(String msg) {
        try {
            Log.e(TAG, msg);
        } catch (RuntimeException ignored) {}
    }

    /**
     * Проверяет, выбрано ли авто-качество.
     */
    public static boolean isAutoQuality(String quality) {
        if (quality == null || quality.trim().isEmpty()) return true;
        String q = quality.toLowerCase().trim();
        return q.equals("авто") || q.equals("auto") || q.startsWith("авто ") || q.startsWith("auto ");
    }

    /**
     * Проверяет, является ли строка описанием скачанного/локального файла.
     */
    public static boolean isDownloadedQuality(String quality) {
        if (quality == null) return false;
        String q = quality.toLowerCase().trim();
        return q.startsWith("скачанный") || q.startsWith("скачанное") || q.startsWith("загруженное")
                || q.startsWith("локальн") || q.startsWith("офлайн");
    }

    /**
     * Нормализует строковое представление онлайн-качества для сохранения в БД.
     * Никогда не возвращает скачанный источник; возвращает "Авто" или "<res>p" (например "1080p", "720p").
     */
    public static String sanitizeSavedOnlineQuality(String quality) {
        if (quality == null || quality.trim().isEmpty()) {
            return "Авто";
        }
        if (isAutoQuality(quality) || isDownloadedQuality(quality)) {
            return "Авто";
        }
        int res = extractResolution(quality);
        if (res > 0) {
            return res + "p";
        }
        return "Авто";
    }

    /**
     * Нормализует строку конкретного разрешения к виду "<res>p" (например "1080" / "FHD" -> "1080p"),
     * либо возвращает null, если определить разрешение невозможно.
     */
    public static String normalizeResolutionLabel(String quality) {
        if (quality == null || quality.trim().isEmpty() || isAutoQuality(quality)) {
            return null;
        }
        int res = extractResolution(quality);
        return res > 0 ? (res + "p") : null;
    }

    /**
     * Преобразует реальные декодированные размеры видеокадра ExoPlayer в стандартную метку качества.
     */
    public static String formatVideoDimensionsToQuality(int width, int height) {
        if (width <= 0 && height <= 0) {
            return null;
        }
        if (height >= 1800 || width >= 3200) return "2160p";
        if (height >= 1300 || width >= 2300) return "1440p";
        if (height >= 950 || width >= 1700) return "1080p";
        if (height >= 650 || width >= 1150) return "720p";
        if (height >= 420 || width >= 760) return "480p";
        return "360p";
    }

    /**
     * Сравнивает две строки качества с учетом гибридного форматирования ("1080p" == "1080", "Авто" == "auto").
     */
    public static boolean matchQuality(String q1, String q2) {
        if (q1 == null || q2 == null) return false;
        if (q1.equalsIgnoreCase(q2)) return true;

        boolean auto1 = isAutoQuality(q1);
        boolean auto2 = isAutoQuality(q2);
        if (auto1 || auto2) return auto1 && auto2;

        boolean down1 = isDownloadedQuality(q1);
        boolean down2 = isDownloadedQuality(q2);
        if (down1 || down2) return down1 && down2;

        int r1 = extractResolution(q1);
        int r2 = extractResolution(q2);
        return r1 > 0 && r1 == r2;
    }

    /**
     * Определяет оптимальное разрешение в p (2160, 1080, 720, 480, 360) на основе сети и битрейта.
     */
    public static int getOptimalQualityResolution(Context context, long exoPlayerBitrateEstimate) {
        long estimate = exoPlayerBitrateEstimate;
        if (estimate <= 0 && context != null) {
            try {
                estimate = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(context).getBitrateEstimate();
            } catch (Exception ignored) {}
        }

        if (estimate > 0) {
            long kbps = estimate / 1000;
            logDebug("Measured Bandwidth: " + kbps + " Kbps");
            if (kbps >= 12000) return 2160;
            if (kbps >= 4500) return 1080;
            if (kbps >= 1800) return 720;
            if (kbps >= 600) return 480;
            return 360;
        }

        if (context != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Network activeNetwork = cm.getActiveNetwork();
                        if (activeNetwork != null) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                            if (caps != null) {
                                boolean isWifiOrEth = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                                      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);

                                if (isWifiOrEth) {
                                    return 720; // Safe initial start for Wi-Fi before bitrate measurement
                                } else {
                                    return 480; // Safe initial start for mobile data
                                }
                            }
                        }
                    } else {
                        android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                        if (info != null && info.isConnected()) {
                            if (info.getType() == ConnectivityManager.TYPE_WIFI || info.getType() == ConnectivityManager.TYPE_ETHERNET) {
                                return 720;
                            } else if (info.getType() == ConnectivityManager.TYPE_MOBILE) {
                                return 480;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logError("Error resolving network quality: " + e.getMessage());
            }
        }

        return 480;
    }

    public static int getOptimalQualityResolution(Context context) {
        return getOptimalQualityResolution(context, 0);
    }

    private static class QualityItem {
        final String qualityStr;
        final int res;
        QualityItem(String qualityStr, int res) {
            this.qualityStr = qualityStr;
            this.res = res;
        }
    }

    /**
     * Для ручного качества (например "1080p" из БД) находит точное совпадение,
     * либо ближайшее доступное качество НИЖЕ (например "720p"),
     * а если все доступные выше целевого — минимальное доступное.
     */
    public static String resolveClosestManualQuality(List<String> availableQualities, String manualQuality) {
        if (availableQualities == null || availableQualities.isEmpty() || manualQuality == null) {
            return null;
        }
        if (isAutoQuality(manualQuality) || isDownloadedQuality(manualQuality)) {
            return null;
        }

        int targetRes = extractResolution(manualQuality);
        if (targetRes <= 0) {
            return null;
        }

        List<QualityItem> validList = new ArrayList<>();
        for (String qStr : availableQualities) {
            if (qStr == null || isAutoQuality(qStr) || isDownloadedQuality(qStr)) continue;
            int res = extractResolution(qStr);
            if (res > 0) {
                validList.add(new QualityItem(qStr, res));
            }
        }

        if (validList.isEmpty()) {
            return null;
        }

        validList.sort((a, b) -> Integer.compare(a.res, b.res));

        QualityItem bestLowerOrEqual = null;
        for (QualityItem item : validList) {
            if (item.res <= targetRes) {
                bestLowerOrEqual = item;
            }
        }

        if (bestLowerOrEqual != null) {
            return bestLowerOrEqual.qualityStr;
        }
        return validList.get(0).qualityStr;
    }

    /**
     * Подбирает наиболее подходящее доступное качество из списка:
     * - если выбрано конкретное качество, выбирает его или ближайшее ниже без перезаписи настройки;
     * - если выбран режим "Авто", подбирает по текущей пропускной способности сети.
     */
    public static String resolveBestQuality(Context context, List<String> availableQualities, String preferredQuality, long exoPlayerBitrateEstimate) {
        if (availableQualities == null || availableQualities.isEmpty()) {
            return "720p";
        }

        if (!isAutoQuality(preferredQuality) && !isDownloadedQuality(preferredQuality)) {
            String manualResolved = resolveClosestManualQuality(availableQualities, preferredQuality);
            if (manualResolved != null) {
                return manualResolved;
            }
        }

        int targetRes = getOptimalQualityResolution(context, exoPlayerBitrateEstimate);
        logDebug("Auto Quality target resolution: " + targetRes + "p");

        List<QualityItem> validList = new ArrayList<>();
        for (String qStr : availableQualities) {
            if (qStr == null || isAutoQuality(qStr) || isDownloadedQuality(qStr)) continue;
            int res = extractResolution(qStr);
            if (res > 0) {
                validList.add(new QualityItem(qStr, res));
            }
        }

        if (validList.isEmpty()) {
            for (String q : availableQualities) {
                if (!isAutoQuality(q) && !isDownloadedQuality(q)) return q;
            }
            return availableQualities.get(0);
        }

        // Сортировка доступных качеств по возрастанию разрешения (например: 360, 480, 720, 1080)
        validList.sort((a, b) -> Integer.compare(a.res, b.res));

        // Находим наибольшее качество, которое <= targetRes
        QualityItem bestItem = null;
        for (QualityItem item : validList) {
            if (item.res <= targetRes) {
                bestItem = item;
            }
        }

        // Если нет качества <= targetRes (например, target=360, а в доступных только 480 и 720), берем МИНИМАЛЬНО доступное!
        if (bestItem == null) {
            bestItem = validList.get(0);
        }

        logDebug("Auto Quality resolved: " + bestItem.qualityStr + " (Target: " + targetRes + "p)");
        return bestItem.qualityStr;
    }

    public static String resolveBestQuality(Context context, List<String> availableQualities, String preferredQuality) {
        return resolveBestQuality(context, availableQualities, preferredQuality, 0);
    }

    public static int extractResolution(String qualityStr) {
        if (qualityStr == null) return 0;
        String q = qualityStr.toLowerCase().trim();
        if (q.isEmpty()) return 0;

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{3,4})").matcher(q);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (Exception ignored) {}
        }

        if (q.contains("4k") || q.contains("uhd")) return 2160;
        if (q.contains("2k") || q.contains("qhd")) return 1440;
        if (q.contains("fhd") || q.contains("fullhd") || q.contains("full hd")) return 1080;
        if (q.contains("hd")) return 720;
        if (q.contains("sd")) return 480;

        String digits = q.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            int parsed = Integer.parseInt(digits);
            return parsed >= 144 ? parsed : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
