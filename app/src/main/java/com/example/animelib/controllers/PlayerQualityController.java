package com.example.animelib.controllers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.animelib.api.ApiService;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.ui.VideoUrlHelper;

import java.util.List;

/**
 * Контроллер выборов и смены качества видеопотока (включая HLS Kodik).
 */
public class PlayerQualityController {

    private static final String TAG = "PlayerQualityCtrl";

    private String preferredQuality = "1080p";
    private KodikResponse currentKodikResponse;

    public interface QualityCallback {
        void onQualityChanged(String newQuality, String newVideoUrl, boolean isHls);
        void onError(String title, String message, Runnable retryAction);
    }

    private QualityCallback callback;

    public PlayerQualityController() {}

    public void setCallback(QualityCallback callback) {
        this.callback = callback;
    }

    public String getPreferredQuality() {
        return preferredQuality;
    }

    public void setPreferredQuality(String quality) {
        this.preferredQuality = quality;
    }

    public KodikResponse getCurrentKodikResponse() {
        return currentKodikResponse;
    }

    public void setCurrentKodikResponse(KodikResponse response) {
        this.currentKodikResponse = response;
    }

    @Nullable
    public String resolveDownloadUrl(PlayersManager playersManager, String currentVideoDomain, String quality) {
        if (playersManager == null) return null;
        EpisodeResponse.PlayerData playerData = playersManager.getCurrentPlayerData();
        if (playerData == null || playerData.getVideo() == null || playerData.getVideo().getQuality() == null) {
            return null;
        }

        String effectiveQuality = quality;
        if (com.example.animelib.util.AutoQualityHelper.isAutoQuality(quality)) {
            List<String> available = playersManager.getAvailableQualities();
            effectiveQuality = com.example.animelib.util.AutoQualityHelper.resolveBestQuality(null, available, quality, 0);
        }

        int target = com.example.animelib.util.AutoQualityHelper.extractResolution(effectiveQuality);
        for (EpisodeResponse.QualityData data : playerData.getVideo().getQuality()) {
            if (data.getQuality() == target) {
                String domain = ("animelib".equalsIgnoreCase(playerData.getPlayer()) && playerData.getVideoDomain() != null && !playerData.getVideoDomain().isEmpty())
                        ? playerData.getVideoDomain() : currentVideoDomain;
                return VideoUrlHelper.toAbsoluteVideoUrl(data.getHref(), domain);
            }
        }
        if (!playerData.getVideo().getQuality().isEmpty()) {
            EpisodeResponse.QualityData data = playerData.getVideo().getQuality().get(0);
            String domain = ("animelib".equalsIgnoreCase(playerData.getPlayer()) && playerData.getVideoDomain() != null && !playerData.getVideoDomain().isEmpty())
                    ? playerData.getVideoDomain() : currentVideoDomain;
            return VideoUrlHelper.toAbsoluteVideoUrl(data.getHref(), domain);
        }
        return null;
    }

    public static class KodikHlsResult {
        public final String url;
        public final String quality;

        public KodikHlsResult(String url, String quality) {
            this.url = url;
            this.quality = quality;
        }
    }

    @Nullable
    public KodikHlsResult resolveKodikHlsResult(KodikResponse response, String targetQuality) {
        if (response == null || response.getData() == null) return null;

        String qualityToUse = targetQuality;
        if (com.example.animelib.util.AutoQualityHelper.isAutoQuality(targetQuality)) {
            List<String> available = new java.util.ArrayList<>();
            for (String key : response.getData().keySet()) {
                int r = com.example.animelib.util.AutoQualityHelper.extractResolution(key);
                if (r > 0) available.add(r + "p");
            }
            qualityToUse = com.example.animelib.util.AutoQualityHelper.resolveBestQuality(null, available, targetQuality, 0);
        }

        int targetRes = com.example.animelib.util.AutoQualityHelper.extractResolution(qualityToUse);

        for (String key : response.getData().keySet()) {
            int keyRes = com.example.animelib.util.AutoQualityHelper.extractResolution(key);
            if (keyRes == targetRes || key.equalsIgnoreCase(qualityToUse) || (key + "p").equalsIgnoreCase(qualityToUse)) {
                if (response.getData().get(key) != null && response.getData().get(key).length > 0) {
                    String url = response.getData().get(key)[0].getSrc();
                    if (url != null && !url.startsWith("http")) {
                        url = "https:" + url;
                    }
                    return new KodikHlsResult(url, targetQuality);
                }
            }
        }

        // Fallbacks
        for (String key : response.getData().keySet()) {
            if (response.getData().get(key) != null && response.getData().get(key).length > 0) {
                String url = response.getData().get(key)[0].getSrc();
                if (url != null && !url.startsWith("http")) {
                    url = "https:" + url;
                }
                return new KodikHlsResult(url, targetQuality);
            }
        }
        return null;
    }

    @Nullable
    public String resolveKodikHlsUrl(KodikResponse response, String targetQuality) {
        KodikHlsResult res = resolveKodikHlsResult(response, targetQuality);
        return res != null ? res.url : null;
    }

    public void switchQuality(PlayersManager playersManager, String currentVideoDomain, String newQuality) {
        if (playersManager == null) return;

        EpisodeResponse.PlayerData playerData = playersManager.getCurrentPlayerData();
        if (playerData == null) return;

        if ("kodik".equalsIgnoreCase(playerData.getPlayer())) {
            if (currentKodikResponse != null) {
                KodikHlsResult result = resolveKodikHlsResult(currentKodikResponse, newQuality);
                if (result != null && result.url != null) {
                    this.preferredQuality = result.quality;
                    if (callback != null) {
                        callback.onQualityChanged(result.quality, result.url, true);
                    }
                } else if (callback != null) {
                    callback.onError("Ошибка смены качества", "Не удалось сформировать HLS ссылку для качества " + newQuality, null);
                }
            }
        } else {
            this.preferredQuality = newQuality;
            String videoUrl = resolveDownloadUrl(playersManager, currentVideoDomain, newQuality);
            if (videoUrl != null && callback != null) {
                callback.onQualityChanged(newQuality, videoUrl, false);
            } else if (callback != null) {
                callback.onError("Ошибка смены качества", "Ссылка для качества " + newQuality + " не найдена", null);
            }
        }
    }
}
