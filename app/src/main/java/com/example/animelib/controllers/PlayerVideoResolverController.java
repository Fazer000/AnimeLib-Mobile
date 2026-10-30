package com.example.animelib.controllers;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.example.animelib.api.ApiService;
import com.example.animelib.api.KodikLinksExtractor;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.AutoQualityHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Контроллер разрешения видеоисточников (AnimeLib, Kodik HLS, локальные скачанные файлы)
 * с защитой от гонок запросов и бесшовным авто-восстановлением потоков Kodik.
 */
public class PlayerVideoResolverController {

    private static final String TAG = "PlayerVideoResolver";
    private static final int MAX_SILENT_KODIK_REFETCHES = 2;

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
        void initializePlayer(long startPosition);
        ExoPlayer getPlayer();
        View getMenuLoadingOverlay();
        void setVideoLoading(boolean loading);
        void setHasRenderedFirstFrame(boolean rendered);
        void updatePlayPauseAndLoadingState(boolean animate);
        void setCurrentKodikResponse(KodikResponse response);
        void initializeHlsPlayer(String hlsUrl);
        void initializeHlsPlayer(String hlsUrl, long startPosition);
        void safeRunOnUiThread(Runnable runnable);
        void onPlayerSelected(EpisodeResponse.PlayerData playerData);
    }

    private final ApiService apiService;
    private final TimecodeManager timecodeManager;
    private final PlayersManager playersManager;
    private final ResolverProvider provider;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger activeRequestToken = new AtomicInteger(0);

    // Состояние авто-восстановления для текущего выбранного источника Kodik
    private String activeKodikSrc = null;
    private List<String> currentKodikCandidates = Collections.emptyList();
    private final Set<String> triedKodikUrls = new LinkedHashSet<>();
    private int kodikSilentRefetchCount = 0;

    public PlayerVideoResolverController(ApiService apiService, TimecodeManager timecodeManager, PlayersManager playersManager, ResolverProvider provider) {
        this.apiService = apiService;
        this.timecodeManager = timecodeManager;
        this.playersManager = playersManager;
        this.provider = provider;
    }

    /**
     * Отменяет все ожидающие асинхронные запросы разрешения ссылок (например, при переключении серии/озвучки).
     */
    public void cancelPendingResolutions() {
        activeRequestToken.incrementAndGet();
        retryHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Сбрасывает счётчики ошибок Kodik после успешного рендера первого кадра или смены серии.
     */
    public void onPlaybackStartedSuccessfully() {
        kodikSilentRefetchCount = 0;
        triedKodikUrls.clear();
    }

    public void resetKodikRecoveryState() {
        kodikSilentRefetchCount = 0;
        triedKodikUrls.clear();
        currentKodikCandidates = Collections.emptyList();
    }

    public void setActiveKodikContext(String kodikSrc, KodikResponse response, String effectiveQuality) {
        if (kodikSrc != null && !kodikSrc.isEmpty()) {
            this.activeKodikSrc = kodikSrc.startsWith("http") ? kodikSrc : ("https:" + kodikSrc);
        }
        boolean enable4K = provider != null && provider.isEnable4K();
        this.currentKodikCandidates = KodikLinksExtractor.collectCandidateUrls(response, effectiveQuality, enable4K);
    }

    public void handleAnimelibPlayer(EpisodeResponse.PlayerData playerData, long seekToPosition) {
        if (provider == null) return;
        cancelPendingResolutions();
        resetKodikRecoveryState();
        activeKodikSrc = null;

        Log.d(TAG, "Handling Animelib player");
        provider.setVideoLoading(true);
        provider.setHasRenderedFirstFrame(false);
        provider.updatePlayPauseAndLoadingState(true);

        // Если эпизод скачан локально — воспроизводим локальный файл в любом случае
        DownloadedEpisodeEntity downloadedEp = provider.getDownloadedEpisodeForActive();
        if (downloadedEp != null && downloadedEp.getLocalFilePath() != null) {
            File file = new File(downloadedEp.getLocalFilePath());
            if (file.exists() && file.length() > 0) {
                provider.setCurrentVideoUrl(Uri.fromFile(file).toString());
                if (timecodeManager != null) timecodeManager.setTimecodes(playerData);
                provider.initializePlayer(seekToPosition);
                ExoPlayer player = provider.getPlayer();
                if (seekToPosition > 0 && player != null) {
                    player.seekTo(seekToPosition);
                }
                Log.d(TAG, "Playing downloaded local file: " + file.getAbsolutePath());
                return;
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

            boolean isAuto = AutoQualityHelper.isAutoQuality(preferredQuality);
            boolean hasMatch = false;
            if (!isAuto && preferredQuality != null) {
                for (String av : available) {
                    if (AutoQualityHelper.matchQuality(av, preferredQuality)) {
                        hasMatch = true;
                        break;
                    }
                }
            }

            if (!isAuto && !hasMatch) {
                Log.d(TAG, "Preferred quality '" + preferredQuality + "' not in exact list for this Animelib episode. Using auto-fallback for resolution without modifying saved preference.");
            }

            long estimate = 0;
            try {
                estimate = DefaultBandwidthMeter.getSingletonInstance(provider.getContext()).getBitrateEstimate();
            } catch (Exception ignored) {}

            String effectiveQuality = AutoQualityHelper.resolveBestQuality(
                    provider.getContext(), available, preferredQuality, estimate);
            Log.d(TAG, "Animelib quality resolved to: " + effectiveQuality + " (preferred mode: " + preferredQuality + ")");

            int targetRes = AutoQualityHelper.extractResolution(effectiveQuality);

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

            provider.initializePlayer(seekToPosition);
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
        cancelPendingResolutions();
        resetKodikRecoveryState();

        Log.d(TAG, "Handling Kodik player");

        // Если эпизод скачан локально — воспроизводим локальный файл в любом случае
        DownloadedEpisodeEntity downloadedEp = provider.getDownloadedEpisodeForActive();
        if (downloadedEp != null && downloadedEp.getLocalFilePath() != null) {
            File file = new File(downloadedEp.getLocalFilePath());
            if (file.exists() && file.length() > 0) {
                provider.setCurrentVideoUrl(Uri.fromFile(file).toString());
                if (timecodeManager != null) timecodeManager.setTimecodes(playerData);
                provider.initializePlayer(seekToPosition);
                ExoPlayer player = provider.getPlayer();
                if (seekToPosition > 0 && player != null) {
                    player.seekTo(seekToPosition);
                }
                Log.d(TAG, "Playing downloaded local file: " + file.getAbsolutePath());
                return;
            }
        }

        if (timecodeManager != null) timecodeManager.setTimecodes(playerData);

        if (playerData.getSrc() != null && !playerData.getSrc().isEmpty()) {
            String kodikSrc = playerData.getSrc().trim();
            if (!kodikSrc.startsWith("http")) {
                kodikSrc = "https:" + kodikSrc;
            }
            activeKodikSrc = kodikSrc;
            Log.d(TAG, "Kodik src: " + kodikSrc);
            fetchKodikVideoLinks(kodikSrc, seekToPosition, false);
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
        fetchKodikVideoLinks(kodikSrc, seekToPosition, false);
    }

    public void fetchKodikVideoLinks(String kodikSrc, long seekToPosition, boolean forceRefresh) {
        if (provider == null || apiService == null) return;
        final int requestToken = activeRequestToken.incrementAndGet();
        final String normalizedSrc = (kodikSrc != null && !kodikSrc.startsWith("http"))
                ? ("https:" + kodikSrc) : kodikSrc;
        activeKodikSrc = normalizedSrc;

        Log.d(TAG, "Fetching HLS links for Kodik src: " + normalizedSrc + " (forceRefresh=" + forceRefresh + ")");
        provider.safeRunOnUiThread(() -> {
            if (requestToken != activeRequestToken.get()) return;
            provider.showLoading("Получение HLS ссылок...");
        });

        apiService.fetchKodikVideoLinks(normalizedSrc, forceRefresh, new ApiService.KodikVideoCallback() {
            @Override
            public void onKodikVideoReceived(KodikResponse response) {
                provider.safeRunOnUiThread(() -> {
                    if (requestToken != activeRequestToken.get()) {
                        Log.d(TAG, "Ignoring stale Kodik response (token=" + requestToken + ")");
                        return;
                    }
                    provider.hideLoading();
                    startHlsPlayer(response, seekToPosition);
                });
            }

            @Override
            public void onError(String error) {
                provider.safeRunOnUiThread(() -> {
                    if (requestToken != activeRequestToken.get()) {
                        Log.d(TAG, "Ignoring stale Kodik error (token=" + requestToken + ")");
                        return;
                    }
                    // Автоматический повтор в обход кэша перед показом диалога ошибки пользователю
                    if (!forceRefresh) {
                        Log.w(TAG, "Initial Kodik link resolution failed (" + error + "), retrying with forceRefresh...");
                        retryHandler.postDelayed(() -> {
                            if (requestToken == activeRequestToken.get()) {
                                fetchKodikVideoLinks(normalizedSrc, seekToPosition, true);
                            }
                        }, 250L);
                        return;
                    }

                    provider.hideLoading();
                    provider.showVideoErrorDialog("Ошибка загрузки Kodik", "Не удалось загрузить HLS видеоссылки Kodik:\n" + error, () -> {
                        fetchKodikVideoLinks(normalizedSrc, seekToPosition, true);
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

        String effectiveQuality = computeEffectiveKodikQuality(kodikResponse, provider.getPreferredQuality());
        List<String> candidates = KodikLinksExtractor.collectCandidateUrls(
                kodikResponse, effectiveQuality, provider.isEnable4K());
        currentKodikCandidates = candidates;

        String hlsUrl = selectNextUntriedCandidate(candidates);
        if (hlsUrl == null && !candidates.isEmpty()) {
            hlsUrl = candidates.get(0);
        }

        if (hlsUrl != null && !hlsUrl.isEmpty()) {
            triedKodikUrls.add(hlsUrl);
            Log.d(TAG, "Starting HLS playback with URL: " + hlsUrl + " (" + effectiveQuality + ", candidates=" + candidates.size() + ")");
            provider.setCurrentVideoUrl(hlsUrl);
            provider.initializeHlsPlayer(hlsUrl, seekToPosition);
            ExoPlayer player = provider.getPlayer();
            if (seekToPosition > 0 && player != null) {
                player.seekTo(seekToPosition);
            }
        } else {
            provider.showVideoErrorDialog("HLS видео недоступно", "HLS ссылка для выбранного качества Kodik не найдена.", () -> {
                if (activeKodikSrc != null && !activeKodikSrc.isEmpty()) {
                    fetchKodikVideoLinks(activeKodikSrc, seekToPosition, true);
                } else if (playersManager != null && playersManager.getCurrentPlayerData() != null) {
                    handleKodikPlayer(playersManager.getCurrentPlayerData(), seekToPosition);
                }
            });
        }
    }

    /**
     * Пытается бесшовно восстановить поток Kodik при ошибке воспроизведения ExoPlayer
     * без показа модального окна ошибки пользователю:
     * 1) Переключается на резервное зеркало / качество из текущего ответа.
     * 2) Принудительно запрашивает свежие HLS-ссылки у балансировщика Kodik в обход кэша.
     *
     * @return true, если запущено автоматическое восстановление; false, если все попытки исчерпаны.
     */
    public boolean tryRecoverKodikPlayback(String failedUrl, long resumePosition) {
        if (provider == null || apiService == null) return false;

        if (failedUrl != null && !failedUrl.isEmpty()) {
            triedKodikUrls.add(failedUrl);
        }

        // Этап 1: пробуем следующее неиспробованное зеркало / резервный поток из уже полученных ссылок
        String nextCandidate = selectNextUntriedCandidate(currentKodikCandidates);
        if (nextCandidate != null) {
            triedKodikUrls.add(nextCandidate);
            Log.w(TAG, "Kodik stream failed, switching to fallback candidate URL: " + nextCandidate);
            provider.setVideoLoading(true);
            provider.setHasRenderedFirstFrame(false);
            provider.updatePlayPauseAndLoadingState(true);
            provider.setCurrentVideoUrl(nextCandidate);
            provider.initializeHlsPlayer(nextCandidate, Math.max(0L, resumePosition));
            return true;
        }

        // Этап 2: запрашиваем свежие подписанные ссылки у Kodik (балансировщик выдаёт новую ноду kodik-storage)
        String srcToRefresh = activeKodikSrc;
        if ((srcToRefresh == null || srcToRefresh.isEmpty())
                && playersManager != null
                && playersManager.getCurrentPlayerData() != null
                && "kodik".equalsIgnoreCase(playersManager.getCurrentPlayerData().getPlayer())) {
            String rawSrc = playersManager.getCurrentPlayerData().getSrc();
            if (rawSrc != null && !rawSrc.isEmpty()) {
                srcToRefresh = rawSrc.startsWith("http") ? rawSrc : ("https:" + rawSrc);
                activeKodikSrc = srcToRefresh;
            }
        }

        if (srcToRefresh != null && !srcToRefresh.isEmpty() && kodikSilentRefetchCount < MAX_SILENT_KODIK_REFETCHES) {
            kodikSilentRefetchCount++;
            final int attemptNum = kodikSilentRefetchCount;
            final String finalSrc = srcToRefresh;
            final int requestToken = activeRequestToken.incrementAndGet();

            Log.w(TAG, "All cached Kodik URLs failed. Silently refreshing Kodik links (attempt "
                    + attemptNum + "/" + MAX_SILENT_KODIK_REFETCHES + ") for " + finalSrc);

            apiService.invalidateKodikCache(finalSrc);
            provider.setVideoLoading(true);
            provider.setHasRenderedFirstFrame(false);
            provider.updatePlayPauseAndLoadingState(true);
            provider.showLoading("Переподключение к серверу Kodik...");

            apiService.fetchKodikVideoLinks(finalSrc, true, new ApiService.KodikVideoCallback() {
                @Override
                public void onKodikVideoReceived(KodikResponse response) {
                    provider.safeRunOnUiThread(() -> {
                        if (requestToken != activeRequestToken.get()) return;
                        provider.hideLoading();

                        provider.setCurrentKodikResponse(response);
                        if (playersManager != null) {
                            playersManager.setCurrentKodikResponse(response);
                        }

                        String effectiveQuality = computeEffectiveKodikQuality(response, provider.getPreferredQuality());
                        List<String> freshCandidates = KodikLinksExtractor.collectCandidateUrls(
                                response, effectiveQuality, provider.isEnable4K());
                        currentKodikCandidates = freshCandidates;

                        // Предпочитаем новую ноду/URL, которую ещё не пробовали; иначе берём свежую первую ссылку
                        String freshUrl = selectNextUntriedCandidate(freshCandidates);
                        if (freshUrl == null && !freshCandidates.isEmpty()) {
                            triedKodikUrls.clear();
                            freshUrl = freshCandidates.get(0);
                        }

                        if (freshUrl != null && !freshUrl.isEmpty()) {
                            triedKodikUrls.add(freshUrl);
                            provider.setCurrentVideoUrl(freshUrl);
                            provider.initializeHlsPlayer(freshUrl, Math.max(0L, resumePosition));
                        } else {
                            provider.showVideoErrorDialog("Ошибка воспроизведения Kodik",
                                    "Не удалось получить рабочую видеоссылку Kodik.",
                                    () -> fetchKodikVideoLinks(finalSrc, Math.max(0L, resumePosition), true));
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    provider.safeRunOnUiThread(() -> {
                        if (requestToken != activeRequestToken.get()) return;
                        provider.hideLoading();
                        provider.showVideoErrorDialog("Ошибка загрузки Kodik",
                                "Сервер Kodik временно недоступен:\n" + error,
                                () -> {
                                    resetKodikRecoveryState();
                                    fetchKodikVideoLinks(finalSrc, Math.max(0L, resumePosition), true);
                                });
                    });
                }
            });
            return true;
        }

        return false;
    }

    private String selectNextUntriedCandidate(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        for (String url : candidates) {
            if (url != null && !url.isEmpty() && !triedKodikUrls.contains(url)) {
                return url;
            }
        }
        return null;
    }

    private String computeEffectiveKodikQuality(KodikResponse kodikResponse, String preferredQuality) {
        List<String> availableQualities = new ArrayList<>();
        if (kodikResponse != null && kodikResponse.getData() != null && !kodikResponse.getData().isEmpty()) {
            List<String> rawKeys = new ArrayList<>(kodikResponse.getData().keySet());
            rawKeys.sort((k1, k2) -> Integer.compare(
                    AutoQualityHelper.extractResolution(k2),
                    AutoQualityHelper.extractResolution(k1)
            ));
            for (String key : rawKeys) {
                int res = AutoQualityHelper.extractResolution(key);
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

        long estimate = 0;
        try {
            estimate = DefaultBandwidthMeter.getSingletonInstance(provider.getContext()).getBitrateEstimate();
        } catch (Exception ignored) {}

        String effectiveQuality = AutoQualityHelper.resolveBestQuality(
                provider.getContext(), availableQualities, preferredQuality, estimate);
        Log.d(TAG, "Kodik quality resolved to: " + effectiveQuality + " (preferred mode: " + preferredQuality + ")");
        return effectiveQuality;
    }
}
