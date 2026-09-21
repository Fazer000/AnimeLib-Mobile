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
        return q.startsWith("скачанный") || q.startsWith("загруженное") || q.startsWith("локальн") || q.startsWith("офлайн");
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

        String r1 = q1.replaceAll("[^0-9]", "");
        String r2 = q2.replaceAll("[^0-9]", "");
        return !r1.isEmpty() && r1.equals(r2);
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
            Log.d(TAG, "Measured Bandwidth: " + kbps + " Kbps");
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
                Log.e(TAG, "Error resolving network quality: " + e.getMessage());
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
     * Подбирает наиболее подходящее доступное качество из списка на основе скорости/сети.
     */
    public static String resolveBestQuality(Context context, List<String> availableQualities, String preferredQuality, long exoPlayerBitrateEstimate) {
        if (availableQualities == null || availableQualities.isEmpty()) {
            return "720p";
        }

        // Если выбрано конкретное качество (не Авто) и оно есть в списке, используем его
        if (!isAutoQuality(preferredQuality)) {
            for (String q : availableQualities) {
                if (q != null && matchQuality(q, preferredQuality)) {
                    return q;
                }
            }
        }

        int targetRes = getOptimalQualityResolution(context, exoPlayerBitrateEstimate);
        Log.d(TAG, "Auto Quality target resolution: " + targetRes + "p");

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

        Log.d(TAG, "Auto Quality resolved: " + bestItem.qualityStr + " (Target: " + targetRes + "p)");
        return bestItem.qualityStr;
    }

    public static String resolveBestQuality(Context context, List<String> availableQualities, String preferredQuality) {
        return resolveBestQuality(context, availableQualities, preferredQuality, 0);
    }

    public static int extractResolution(String qualityStr) {
        if (qualityStr == null) return 0;
        String digits = qualityStr.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }
}
