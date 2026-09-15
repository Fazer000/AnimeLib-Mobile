package com.example.animelib.controllers;

import android.net.Uri;
import android.util.Log;
import android.view.View;

import androidx.media3.exoplayer.ExoPlayer;

import com.example.animelib.api.ApiService;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.ui.VideoUrlHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Контроллер разрешения видеоисточников (AnimeLib, Kodik HLS, локальные скачанные файлы).
 */
public class PlayerVideoResolverController {

    private static final String TAG = "PlayerVideoResolver";

    public interface ResolverProvider {
        android.content.Context getContext();
        boolean isDownloadedQuality(String quality);
        DownloadedEpisodeEntity getDownloadedEpisodeForActive();
        void setCurrentVideoUrl(String url);
        String getPreferredQuality();
        void setPreferredQuality(String quality);
        boolean isEnable4K();
        String getCurrentVideoDomain();
        void showLoading(String message);
        void hideLoading();
        void showVideoErrorDialog(String title, String message, Runnable retryAction);
        void initializePlayer();
        ExoPlayer getPlayer();
        View getMenuLoadingOverlay();
        void setVideoLoading(boolean loading);
        void setHasRenderedFirstFrame(boolean rendered);
        void updatePlayPauseAndLoadingState(boolean animate);
        void setCurrentKodikResponse(KodikResponse response);
        void initializeHlsPlayer(String hlsUrl);
        void safeRunOnUiThread(Runnable runnable);
        void onPlayerSelected(EpisodeResponse.PlayerData playerData);
    }

    private final ApiService apiService;
    private final TimecodeManager timecodeManager;
    private final PlayersManager playersManager;
    private final ResolverProvider provider;

    public PlayerVideoResolverController(ApiService apiService, TimecodeManager timecodeManager, PlayersManager playersManager, ResolverProvider provider) {
        this.apiService = apiService;
        this.timecodeManager = timecodeManager;
        this.playersManager = playersManager;
        this.provider = provider;
    }

    public void handleAnimelibPlayer(EpisodeResponse.PlayerData playerData, long seekToPosition) {
        if (provider == null) return;
        Log.d(TAG, "Handling Animelib player");
        provider.setVideoLoading(true);
        provider.setHasRenderedFirstFrame(false);
        provider.updatePlayPauseAndLoadingState(true);

        // Если эпизод скачан локально и выбрано Авто/Скачанный режим — воспроизводим локальный файл
        String prefQualAnimelib = provider.getPreferredQuality();
        boolean isAutoAnimelib = com.example.animelib.util.AutoQualityHelper.isAutoQuality(prefQualAnimelib);
        boolean isDownloadedAnimelib = com.example.animelib.util.AutoQualityHelper.isDownloadedQuality(prefQualAnimelib);

        if (isAutoAnimelib || isDownloadedAnimelib || prefQualAnimelib == null || prefQualAnimelib.isEmpty()) {
            DownloadedEpisodeEntity downloadedEp = provider.getDownloadedEpisodeForActive();
            if (downloadedEp != null && downloadedEp.getLocalFilePath() != null) {
                File file = new File(downloadedEp.getLocalFilePath());
                if (file.exists() && file.length() > 0) {
                    provider.setCurrentVideoUrl(Uri.fromFile(file).toString());
                    if (timecodeManager != null) timecodeManager.setTimecodes(playerData);
                    provider.initializePlayer();
                    ExoPlayer player = provider.getPlayer();
                    if (seekToPosition > 0 && player != null) {
                        player.seekTo(seekToPosition);
                    }
                    Log.d(TAG, "Playing downloaded local file: " + file.getAbsolutePath());
                    return;
                }
            }
        }

        if (playerData.getVideo() != null && playerData.getVideo().getQuality() != null && !playerData.getVideo().getQuality().isEmpty()) {
            EpisodeResponse.QualityData selectedQuality = null;

            String preferredQuality = provider.getPreferredQuality();

            List<String> available = new ArrayList<>();
            List<EpisodeResponse.QualityData> qList = new ArrayList<>(playerData.getVideo().getQuality());
            qList.sort((q1, q2) -> Integer.compare(q2.getQuality(), q1.getQuality()));
            for (EpisodeResponse.QualityData q : qList) {
                int res = q.getQuality();
                if (res == 2160 && !provider.isEnable4K()) continue;
                available.add(res + "p");
            }

            boolean isAuto = com.example.animelib.util.AutoQualityHelper.isAutoQuality(preferredQuality);
            boolean hasMatch = false;
            if (!isAuto && preferredQuality != null) {
                for (String av : available) {
                    if (com.example.animelib.util.AutoQualityHelper.matchQuality(av, preferredQuality)) {
                        hasMatch = true;
                        break;
                    }
                }
            }

            if (!isAuto && !hasMatch) {
                Log.d(TAG, "Preferred quality '" + preferredQuality + "' not available in Animelib episode. Defaulting to Auto.");
                preferredQuality = "Авто";
                provider.setPreferredQuality("Авто");
                if (apiService != null && playerData.getPlayer() != null && playerData.getTeam() != null) {
                    apiService.savePlayerPreferences(playerData.getPlayer(), playerData.getTeam().getId(), "Авто");
                }
            }

            long estimate = 0;
            try {
                estimate = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(provider.getContext()).getBitrateEstimate();
            } catch (Exception ignored) {}

            String effectiveQuality = com.example.animelib.util.AutoQualityHelper.resolveBestQuality(
                    provider.getContext(), available, preferredQuality, estimate);
            Log.d(TAG, "Animelib quality resolved to: " + effectiveQuality + " (preferred mode: " + preferredQuality + ")");

            int targetRes = com.example.animelib.util.AutoQualityHelper.extractResolution(effectiveQuality);

            for (EpisodeResponse.QualityData qData : qList) {
                if (qData.getQuality() == targetRes) {
                    selectedQuality = qData;
                    break;
                }
            }

            if (selectedQuality == null && !qList.isEmpty()) {
                selectedQuality = qList.get(0);
            }

            if (selectedQuality == null) {
                Log.e(TAG, "No suitable quality found");
                provider.showVideoErrorDialog("Ошибка плеера AnimeLib", "Нет подходящего качества видео для этой озвучки.", () -> {
                    provider.onPlayerSelected(playerData);
                });
                return;
            }

            String videoUrl = selectedQuality.getHref();
            Log.d(TAG, "Selected quality: " + selectedQuality.getQuality() + "p, URL: " + videoUrl);

            videoUrl = VideoUrlHelper.toAbsoluteVideoUrl(videoUrl, provider.getCurrentVideoDomain());

            Log.d(TAG, "Final video URL: " + videoUrl);
            provider.setCurrentVideoUrl(videoUrl);
            
            if (timecodeManager != null) timecodeManager.setTimecodes(playerData);
            
            provider.initializePlayer();
            ExoPlayer player = provider.getPlayer();
            if (seekToPosition > 0 && player != null) {
                player.seekTo(seekToPosition);
            }
        } else {
            provider.showVideoErrorDialog("Видео недоступно", "У выбранной озвучки AnimeLib отсутствуют ссылки на видео.", () -> {
                provider.onPlayerSelected(playerData);
            });
        }
    }

    public void handleKodikPlayer(EpisodeResponse.PlayerData playerData, long seekToPosition) {
        if (provider == null) return;
        Log.d(TAG, "Handling Kodik player");

        // Если эпизод скачан локально и выбрано Авто/Скачанный режим — воспроизводим локальный файл
        String prefQualKodik = provider.getPreferredQuality();
        boolean isAutoKodik = com.example.animelib.util.AutoQualityHelper.isAutoQuality(prefQualKodik);
        boolean isDownloadedKodik = com.example.animelib.util.AutoQualityHelper.isDownloadedQuality(prefQualKodik);

        if (isAutoKodik || isDownloadedKodik || prefQualKodik == null || prefQualKodik.isEmpty()) {
            DownloadedEpisodeEntity downloadedEp = provider.getDownloadedEpisodeForActive();
            if (downloadedEp != null && downloadedEp.getLocalFilePath() != null) {
                File file = new File(downloadedEp.getLocalFilePath());
                if (file.exists() && file.length() > 0) {
                    provider.setCurrentVideoUrl(Uri.fromFile(file).toString());
                    if (timecodeManager != null) timecodeManager.setTimecodes(playerData);
                    provider.initializePlayer();
                    ExoPlayer player = provider.getPlayer();
                    if (seekToPosition > 0 && player != null) {
                        player.seekTo(seekToPosition);
                    }
                    Log.d(TAG, "Playing downloaded local file: " + file.getAbsolutePath());
                    return;
                }
            }
        }

        if (timecodeManager != null) timecodeManager.setTimecodes(playerData);

        if (playerData.getSrc() != null && !playerData.getSrc().isEmpty()) {
            String kodikSrc = playerData.getSrc();
            if (!kodikSrc.startsWith("http")) {
                kodikSrc = "https:" + kodikSrc;
            }
            Log.d(TAG, "Kodik src: " + kodikSrc);
            fetchKodikVideoLinks(kodikSrc, seekToPosition);
        } else {
            Log.w(TAG, "No src found in Kodik player data");
            provider.showVideoErrorDialog("Ошибка Kodik", "Ссылка на плеер Kodik отсутствует. Попробуйте выбрать другую озвучку.", () -> {
                handleKodikPlayer(playerData, seekToPosition);
            });
            ExoPlayer player = provider.getPlayer();
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
            View overlay = provider.getMenuLoadingOverlay();
            if (overlay != null) overlay.setVisibility(View.GONE);
        }
    }

    public void fetchKodikVideoLinks(String kodikSrc, long seekToPosition) {
        if (provider == null || apiService == null) return;
        Log.d(TAG, "Fetching HLS links for Kodik src: " + kodikSrc);
        provider.safeRunOnUiThread(() -> provider.showLoading("Получение HLS ссылок..."));

        apiService.fetchKodikVideoLinks(kodikSrc, new ApiService.KodikVideoCallback() {
            @Override
            public void onKodikVideoReceived(KodikResponse response) {
                provider.safeRunOnUiThread(() -> {
                    provider.hideLoading();
                    startHlsPlayer(response, seekToPosition);
                });
            }

            @Override
            public void onError(String error) {
                provider.safeRunOnUiThread(() -> {
                    provider.hideLoading();
                    provider.showVideoErrorDialog("Ошибка загрузки Kodik", "Не удалось загрузить HLS видеоссылки Kodik:\n" + error, () -> {
                        fetchKodikVideoLinks(kodikSrc, seekToPosition);
                    });
                    ExoPlayer player = provider.getPlayer();
                    if (player != null) {
                        player.stop();
                        player.clearMediaItems();
                    }
                    View overlay = provider.getMenuLoadingOverlay();
                    if (overlay != null) overlay.setVisibility(View.GONE);
                });
            }
        });
    }

    public void startHlsPlayer(KodikResponse kodikResponse, long seekToPosition) {
        if (provider == null) return;
        provider.setCurrentKodikResponse(kodikResponse);
        if (playersManager != null) {
            playersManager.setCurrentKodikResponse(kodikResponse);
        }

        String preferredQuality = provider.getPreferredQuality();

        List<String> availableQualities = new ArrayList<>();
        if (kodikResponse != null && kodikResponse.getData() != null && !kodikResponse.getData().isEmpty()) {
            List<String> rawKeys = new ArrayList<>(kodikResponse.getData().keySet());
            rawKeys.sort((k1, k2) -> Integer.compare(
                    com.example.animelib.util.AutoQualityHelper.extractResolution(k2),
                    com.example.animelib.util.AutoQualityHelper.extractResolution(k1)
            ));
            for (String key : rawKeys) {
                int res = com.example.animelib.util.AutoQualityHelper.extractResolution(key);
                if (res == 2160 && !provider.isEnable4K()) continue;
                String qStr = res > 0 ? res + "p" : key;
                if (!availableQualities.contains(qStr)) {
                    availableQualities.add(qStr);
                }
            }
        }
        if (availableQualities.isEmpty()) {
            availableQualities.add("720p");
            availableQualities.add("480p");
            availableQualities.add("360p");
        }

        boolean isAuto = com.example.animelib.util.AutoQualityHelper.isAutoQuality(preferredQuality);
        boolean hasMatch = false;
        if (!isAuto && preferredQuality != null) {
            for (String av : availableQualities) {
                if (com.example.animelib.util.AutoQualityHelper.matchQuality(av, preferredQuality)) {
                    hasMatch = true;
                    break;
                }
            }
        }

        if (!isAuto && !hasMatch) {
            Log.d(TAG, "Preferred quality '" + preferredQuality + "' not available in Kodik episode. Defaulting to Auto.");
            preferredQuality = "Авто";
            provider.setPreferredQuality("Авто");
            if (apiService != null && playersManager != null && playersManager.getCurrentPlayerData() != null) {
                EpisodeResponse.PlayerData cp = playersManager.getCurrentPlayerData();
                if (cp.getPlayer() != null && cp.getTeam() != null) {
                    apiService.savePlayerPreferences(cp.getPlayer(), cp.getTeam().getId(), "Авто");
                }
            }
        }

        long estimate = 0;
        try {
            estimate = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(provider.getContext()).getBitrateEstimate();
        } catch (Exception ignored) {}

        String effectiveQuality = com.example.animelib.util.AutoQualityHelper.resolveBestQuality(
                provider.getContext(), availableQualities, preferredQuality, estimate);
        Log.d(TAG, "Kodik quality resolved to: " + effectiveQuality + " (preferred mode: " + preferredQuality + ")");

        int targetRes = com.example.animelib.util.AutoQualityHelper.extractResolution(effectiveQuality);
        String hlsUrl = null;

        if (kodikResponse != null && kodikResponse.getData() != null) {
            for (String key : kodikResponse.getData().keySet()) {
                int keyRes = com.example.animelib.util.AutoQualityHelper.extractResolution(key);
                if (keyRes == targetRes || key.equalsIgnoreCase(effectiveQuality) || (key + "p").equalsIgnoreCase(effectiveQuality)) {
                    if (kodikResponse.getData().get(key) != null && kodikResponse.getData().get(key).length > 0) {
                        hlsUrl = kodikResponse.getData().get(key)[0].getSrc();
                        break;
                    }
                }
            }
            if (hlsUrl == null) {
                for (String key : kodikResponse.getData().keySet()) {
                    if (kodikResponse.getData().get(key) != null && kodikResponse.getData().get(key).length > 0) {
                        hlsUrl = kodikResponse.getData().get(key)[0].getSrc();
                        break;
                    }
                }
            }
        }

        if (hlsUrl != null) {
            if (!hlsUrl.startsWith("http")) {
                hlsUrl = "https:" + hlsUrl;
            }

            Log.d(TAG, "Starting HLS playback with URL: " + hlsUrl + " (" + effectiveQuality + ")");
            provider.setCurrentVideoUrl(hlsUrl);
            provider.initializeHlsPlayer(hlsUrl);
            ExoPlayer player = provider.getPlayer();
            if (seekToPosition > 0 && player != null) {
                player.seekTo(seekToPosition);
            }
        } else {
            provider.showVideoErrorDialog("HLS видео недоступно", "HLS ссылка для выбранного качества Kodik не найдена.", () -> {
                if (playersManager != null) {
                    handleKodikPlayer(playersManager.getCurrentPlayerData(), seekToPosition);
                }
            });
        }
    }
}
