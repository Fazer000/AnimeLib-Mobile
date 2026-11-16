package com.example.animelib.controllers;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.example.animelib.api.ApiService;
import com.example.animelib.api.KodikLinksExtractor;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.PlayerPreferences;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.AutoQualityHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Контроллер разрешения видеоисточников (AnimeLib, Kodik HLS, локальные скачанные файлы),
 * выбора/переключения озвучки и бесшовного авто-восстановления потоков Kodik / fallback CDN.
 */
public class PlayerVideoResolverController {

    private static final String TAG = "PlayerVideoResolver";
    private static final int MAX_SILENT_KODIK_REFETCHES = 2;

    public interface ResolverProvider {
        android.content.Context getContext();
        boolean isDownloadedQuality(String quality);
        boolean shouldPlayDownloadedFile();
        DownloadedEpisodeEntity getDownloadedEpisodeForActive();
        void setCurrentVideoUrl(String url);
        void onActiveQualityResolved(String resolvedQuality);
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

    public interface ResolverHost {
        String getCurrentAnimeId();
        boolean isAutoPlayEnabled();
        void setAutoPlayOnPrepare(boolean autoPlayOnPrepare);
        EpisodesManager getEpisodesManager();
        PlayerEpisodesController getEpisodesController();
        PlayerQualityController getQualityController();
        PlayerPlaybackController getPlaybackController();
        PlayerProgressController getProgressController();
        PlayerDialogsController getDialogsController();
        PlayerPanelsController getPanelsController();
        PlayerOfflineController getOfflineController();
        PlayerPortraitController getPortraitController();
        PlayerAnimeInfoController getAnimeInfoController();
        void markEpisodeAndVoiceoversLoaded();
        void onEpisodeHeaderUpdateQuick();
        void onAnimeInfoHeaderFull();
        void switchMediaSource(String newVideoUrl, long startPosition, boolean playWhenReady);
    }

    private final ApiService apiService;
    private final TimecodeManager timecodeManager;
    private final PlayersManager playersManager;
    private final ResolverProvider provider;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger activeRequestToken = new AtomicInteger(0);

    private ResolverHost host;
    private ExecutorService executor;
    private int playbackRestartGeneration = 0;

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

    public void setHost(ResolverHost host, ExecutorService executor) {
        this.host = host;
        this.executor = executor;
    }

    public int getPlaybackRestartGeneration() {
        return playbackRestartGeneration;
    }

    public int incrementPlaybackRestartGeneration() {
        return ++playbackRestartGeneration;
    }

    public void cancelPendingResolutions() {
        activeRequestToken.incrementAndGet();
        retryHandler.removeCallbacksAndMessages(null);
    }

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

    public void onPlayerSelected(EpisodeResponse.PlayerData playerData) {
        if (host == null || provider == null) return;

        Log.d(TAG, "Player selected: " + (playerData != null ? playerData.getPlayer() : "null"));
        host.markEpisodeAndVoiceoversLoaded();

        PlayerQualityController qualityCtrl = host.getQualityController();
        PlayerEpisodesController epCtrl = host.getEpisodesController();
        PlayerPlaybackController playbackCtrl = host.getPlaybackController();
        PlayerProgressController progressCtrl = host.getProgressController();
        PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
        PlayerPortraitController portraitCtrl = host.getPortraitController();

        if (playerData != null && "animelib".equalsIgnoreCase(playerData.getPlayer())) {
            if (playerData.getVideoDomain() != null && !playerData.getVideoDomain().isEmpty() && qualityCtrl != null) {
                qualityCtrl.setCurrentVideoDomain(playerData.getVideoDomain());
                Log.d(TAG, "Set currentVideoDomain from API for animelib: " + playerData.getVideoDomain());
            }
        }

        ExoPlayer player = playbackCtrl != null ? playbackCtrl.getPlayer() : null;
        boolean isNew = epCtrl == null || epCtrl.isNewEpisodeSelection();
        if (isNew) {
            host.setAutoPlayOnPrepare(host.isAutoPlayEnabled());
            if (epCtrl != null) {
                epCtrl.setNewEpisodeSelection(false);
                epCtrl.setSavedPlayerPosition(0);
            }
        } else if (player != null) {
            host.setAutoPlayOnPrepare(player.getPlayWhenReady());
        } else {
            host.setAutoPlayOnPrepare(host.isAutoPlayEnabled());
        }

        if (animeInfoCtrl != null) {
            animeInfoCtrl.hidePlaceholderAnimated();
        }
        host.onEpisodeHeaderUpdateQuick();

        if (playerData != null && playerData.getPlayer() != null && playerData.getTeam() != null && !provider.shouldPlayDownloadedFile()) {
            apiService.savePlayerPreferences(playerData.getPlayer(), playerData.getTeam().getId());
        }

        boolean isSeamless = playbackCtrl != null && playbackCtrl.isSeamlessSwitching();
        boolean isCurrentlyPlaying = player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY) && !isSeamless;

        long currentPos = 0;
        boolean wasPlaying = true;
        if (!isNew && player != null) {
            currentPos = player.getCurrentPosition();
            wasPlaying = player.getPlayWhenReady();
            if (currentPos > 0 && epCtrl != null) {
                epCtrl.setSavedPlayerPosition(currentPos);
            }
        }

        if (playerData != null) {
            playersManager.setCurrentPlayerData(playerData);
            if (progressCtrl != null) progressCtrl.enableBookmarkButton();
            host.onEpisodeHeaderUpdateQuick();
            if (portraitCtrl != null) portraitCtrl.updatePortraitVoiceoverPlayerUI();

            String prefQuality = qualityCtrl != null ? qualityCtrl.getPreferredQuality() : provider.getPreferredQuality();
            String currentVideoUrl = qualityCtrl != null ? qualityCtrl.getCurrentVideoUrl() : null;

            if (!isNew && !provider.shouldPlayDownloadedFile() && qualityCtrl != null) {
                if ("animelib".equalsIgnoreCase(playerData.getPlayer())) {
                    String newVideoUrl = qualityCtrl.resolveAnimelibUrl(playerData, prefQuality);
                    if (newVideoUrl != null && !newVideoUrl.equals(currentVideoUrl)) {
                        Log.d(TAG, "Seamlessly switching voiceover (Animelib)");
                        qualityCtrl.refreshQualityUiState();
                        if (isCurrentlyPlaying) {
                            qualityCtrl.switchQualitySeamlesslyInternal(prefQuality, prefQuality, newVideoUrl);
                        } else {
                            qualityCtrl.setCurrentVideoUrl(newVideoUrl);
                            if (timecodeManager != null) timecodeManager.setTimecodes(playerData);
                            host.onAnimeInfoHeaderFull();
                            host.switchMediaSource(newVideoUrl, currentPos, wasPlaying);
                        }
                        return;
                    }
                } else if ("kodik".equalsIgnoreCase(playerData.getPlayer())) {
                    String kodikSrc = playerData.getSrc();
                    if (kodikSrc != null && !kodikSrc.isEmpty()) {
                        if (!kodikSrc.startsWith("http")) kodikSrc = "https:" + kodikSrc;
                        final String finalKodikSrc = kodikSrc;
                        final long posToRestore = currentPos;
                        final boolean playState = wasPlaying;
                        final boolean shouldBeSeamless = isCurrentlyPlaying;
                        final int switchGen = playbackRestartGeneration;
                        if (!shouldBeSeamless) {
                            provider.showLoading("Загрузка озвучки...");
                        }
                        apiService.fetchKodikVideoLinks(finalKodikSrc, false, new ApiService.KodikVideoCallback() {
                            @Override
                            public void onKodikVideoReceived(KodikResponse response) {
                                provider.safeRunOnUiThread(() -> {
                                    if (switchGen != playbackRestartGeneration) {
                                        return;
                                    }
                                    if (!shouldBeSeamless) {
                                        provider.hideLoading();
                                    }
                                    provider.setCurrentKodikResponse(response);
                                    resetKodikRecoveryState();
                                    setActiveKodikContext(finalKodikSrc, response, prefQuality);

                                    String newVideoUrl = qualityCtrl.resolveKodikHlsUrlWithContext(response, prefQuality);
                                    qualityCtrl.refreshQualityUiState();
                                    String latestUrl = qualityCtrl.getCurrentVideoUrl();
                                    ExoPlayer livePlayer = playbackCtrl != null ? playbackCtrl.getPlayer() : null;
                                    if (newVideoUrl != null && !newVideoUrl.equals(latestUrl)) {
                                        if (shouldBeSeamless && livePlayer != null && (livePlayer.isPlaying() || livePlayer.getPlaybackState() == Player.STATE_READY)) {
                                            qualityCtrl.switchQualitySeamlesslyInternal(prefQuality, prefQuality, newVideoUrl);
                                        } else {
                                            qualityCtrl.setCurrentVideoUrl(newVideoUrl);
                                            if (timecodeManager != null) timecodeManager.setTimecodes(playerData);
                                            host.onAnimeInfoHeaderFull();
                                            host.switchMediaSource(newVideoUrl, posToRestore, playState);
                                        }
                                    } else {
                                        if (epCtrl != null) epCtrl.setSavedPlayerPosition(Math.max(0L, posToRestore));
                                        stopCurrentPlaybackAndRestartVoiceover(playerData);
                                    }
                                });
                            }

                            @Override
                            public void onError(String error) {
                                provider.safeRunOnUiThread(() -> {
                                    if (switchGen != playbackRestartGeneration) {
                                        return;
                                    }
                                    provider.hideLoading();
                                    if (epCtrl != null) epCtrl.setSavedPlayerPosition(Math.max(0L, posToRestore));
                                    stopCurrentPlaybackAndRestartVoiceover(playerData);
                                });
                            }
                        });
                        return;
                    }
                }
            }
        }

        stopCurrentPlaybackAndRestartVoiceover(playerData);
    }

    public void stopCurrentPlaybackAndRestartVoiceover(EpisodeResponse.PlayerData playerData) {
        if (host == null || provider == null) return;
        PlayerPlaybackController playback = host.getPlaybackController();
        PlayerPanelsController panels = host.getPanelsController();
        PlayerProgressController progressCtrl = host.getProgressController();
        PlayerEpisodesController epCtrl = host.getEpisodesController();
        PlayerQualityController qualityCtrl = host.getQualityController();

        if (progressCtrl != null) {
            progressCtrl.capturePlaybackSnapshot();
        }
        if (playback != null) {
            playback.preservePlayerAspectRatio();
            playback.stopCurrentPlayback();
        }
        if (panels != null) {
            panels.applyPlayerSidePanelTransform(0f);
        }

        cancelPendingResolutions();

        if (playerData == null) {
            return;
        }

        final int restartGen = incrementPlaybackRestartGeneration();
        final long startPosition = epCtrl != null ? epCtrl.consumeStartPosition() : 0L;

        playersManager.setCurrentPlayerData(playerData);
        if (progressCtrl != null) {
            progressCtrl.enableBookmarkButton();
        }

        Log.d(TAG, "Player selected, ready to start playback with startPosition=" + startPosition + "ms");

        if (qualityCtrl != null && (qualityCtrl.isPreferredQualityLoadedFromDb() || qualityCtrl.isPreferredQualityChangedByUser())) {
            qualityCtrl.setPreferredQuality(AutoQualityHelper.sanitizeSavedOnlineQuality(qualityCtrl.getPreferredQuality()));
            launchPlayerAfterQualityReady(playerData, restartGen, startPosition);
            return;
        }

        if (executor != null) {
            executor.execute(() -> {
                if (qualityCtrl != null) {
                    qualityCtrl.ensurePreferredQualityLoadedSync(apiService);
                }
                provider.safeRunOnUiThread(() -> launchPlayerAfterQualityReady(playerData, restartGen, startPosition));
            });
        } else {
            launchPlayerAfterQualityReady(playerData, restartGen, startPosition);
        }
    }

    private void launchPlayerAfterQualityReady(EpisodeResponse.PlayerData playerData, int restartGen, long startPosition) {
        if (restartGen != playbackRestartGeneration || host == null || provider == null) {
            Log.d(TAG, "Ignoring stale playback restart task (gen=" + restartGen + ")");
            return;
        }

        if (!provider.shouldPlayDownloadedFile() && playerData.getPlayer() != null && playerData.getTeam() != null && playerData.getTeam().getId() > 0) {
            apiService.savePlayerPreferences(playerData.getPlayer(), playerData.getTeam().getId());
        }

        PlayerQualityController qualityCtrl = host.getQualityController();
        if (qualityCtrl != null) {
            qualityCtrl.refreshQualityUiState();
        }

        if (playerData.getPlayer() != null && "animelib".equalsIgnoreCase(playerData.getPlayer())) {
            handleAnimelibPlayer(playerData, startPosition);
        } else if (playerData.getPlayer() != null && "kodik".equalsIgnoreCase(playerData.getPlayer())) {
            handleKodikPlayer(playerData, startPosition);
        } else if (provider.shouldPlayDownloadedFile()) {
            handleAnimelibPlayer(playerData, startPosition);
        }

        host.onAnimeInfoHeaderFull();
    }

    public void showPlayerSelectionDialogWithAutoSelect(List<EpisodeResponse.PlayerData> players) {
        if (provider == null || host == null) return;
        provider.hideLoading();

        DownloadedEpisodeEntity downloadedEp = provider.getDownloadedEpisodeForActive();
        if (downloadedEp != null && downloadedEp.getLocalFilePath() != null && new File(downloadedEp.getLocalFilePath()).exists() && provider.shouldPlayDownloadedFile()) {
            PlayerOfflineController offlineCtrl = host.getOfflineController();
            if (offlineCtrl != null) {
                offlineCtrl.syncCurrentPlayerDataForDownloadedIfNeeded();
            }
            provider.safeRunOnUiThread(() -> {
                if (host.getPortraitController() != null) {
                    host.getPortraitController().updatePortraitVoiceoverPlayerUI();
                }
                View overlay = provider.getMenuLoadingOverlay();
                if (overlay != null) overlay.setVisibility(View.GONE);
            });
            return;
        }

        if (executor == null) return;
        executor.execute(() -> {
            PlayerPreferences prefs = apiService.loadPlayerPreferences();
            EpisodeResponse.PlayerData matchingPlayer = (prefs != null)
                    ? PlayerMetadataHelper.findBestSavedPlayerMatch(players, prefs.getPlayer(), prefs.getTeamId())
                    : null;

            final EpisodeResponse.PlayerData finalMatchingPlayer = matchingPlayer;
            provider.safeRunOnUiThread(() -> {
                if (finalMatchingPlayer != null) {
                    onPlayerSelected(finalMatchingPlayer);
                } else if (players != null && !players.isEmpty()) {
                    onPlayerSelected(players.get(0));
                }
                View overlay = provider.getMenuLoadingOverlay();
                if (overlay != null) overlay.setVisibility(View.GONE);
            });
        });
    }

    public void handlePlaybackError(PlaybackException error) {
        if (host == null || provider == null) return;
        Log.e(TAG, "Playback error: " + (error != null ? error.getMessage() : "unknown"), error);

        PlayerQualityController qualityCtrl = host.getQualityController();
        PlayerEpisodesController epCtrl = host.getEpisodesController();
        PlayerPlaybackController playback = host.getPlaybackController();
        PlayerDialogsController dialogsCtrl = host.getDialogsController();
        EpisodesManager em = host.getEpisodesManager();

        ExoPlayer player = playback != null ? playback.getPlayer() : null;
        long resumePos = player != null ? Math.max(0L, player.getCurrentPosition()) : 0L;
        String currentVideoUrl = qualityCtrl != null ? qualityCtrl.getCurrentVideoUrl() : null;

        boolean isKodikActive = (playersManager != null
                && playersManager.getCurrentPlayerData() != null
                && "kodik".equalsIgnoreCase(playersManager.getCurrentPlayerData().getPlayer()))
                || ApiService.isKodikStreamUrl(currentVideoUrl);
        if (isKodikActive && tryRecoverKodikPlayback(currentVideoUrl, resumePos)) {
            return;
        }

        if (qualityCtrl != null) {
            String nextDomain = PlayerMetadataHelper.findNextCdnFallbackDomain(currentVideoUrl, qualityCtrl.getCurrentVideoDomain());
            if (nextDomain != null) {
                Log.w(TAG, "CDN server " + qualityCtrl.getCurrentVideoDomain() + " failed. Trying fallback CDN server: " + nextDomain);
                qualityCtrl.setCurrentVideoDomain(nextDomain);
                if (epCtrl != null) epCtrl.setSavedPlayerPosition(resumePos);
                if (playersManager != null && playersManager.getCurrentPlayerData() != null) {
                    onPlayerSelected(playersManager.getCurrentPlayerData());
                    return;
                }
            }
        }

        String errorMsg = "Ошибка воспроизведения: " + (error != null ? error.getMessage() : "неизвестная ошибка");
        String logDetails = buildErrorLog(error);
        if (dialogsCtrl != null) {
            dialogsCtrl.showVideoErrorDialog("Ошибка воспроизведения", errorMsg, logDetails, () -> {
                resetKodikRecoveryState();
                if (playersManager != null && playersManager.getCurrentPlayerData() != null) {
                    EpisodeResponse.PlayerData pd = playersManager.getCurrentPlayerData();
                    if ("kodik".equalsIgnoreCase(pd.getPlayer()) && apiService != null && pd.getSrc() != null) {
                        apiService.invalidateKodikCache(pd.getSrc());
                    }
                    if (epCtrl != null) epCtrl.setSavedPlayerPosition(resumePos);
                    onPlayerSelected(pd);
                } else if (currentVideoUrl != null) {
                    retryWithUrl(currentVideoUrl);
                }
            }, false);
        }
    }

    private String buildErrorLog(PlaybackException error) {
        PlayerQualityController qualityCtrl = host != null ? host.getQualityController() : null;
        EpisodesManager em = host != null ? host.getEpisodesManager() : null;
        EpisodeResponse.PlayerData pd = playersManager != null ? playersManager.getCurrentPlayerData() : null;
        EpisodesListResponse.EpisodeItem ep = em != null ? em.getCurrentEpisode() : null;

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String deviceSummary = Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")";

        String errorCodeName = null;
        Integer errorCode = null;
        String errorMessage = null;
        String causeClassName = null;
        String causeMessage = null;
        String httpRequestUri = null;
        Integer httpResponseCode = null;
        String httpResponseMessage = null;
        String stackTrace = null;

        if (error != null) {
            errorCodeName = error.getErrorCodeName();
            errorCode = error.errorCode;
            errorMessage = error.getMessage();
            Throwable cause = error.getCause();
            if (cause != null) {
                causeClassName = cause.getClass().getName();
                causeMessage = cause.getMessage();
                if (cause instanceof HttpDataSource.HttpDataSourceException) {
                    HttpDataSource.HttpDataSourceException httpEx = (HttpDataSource.HttpDataSourceException) cause;
                    if (httpEx.dataSpec != null) {
                        httpRequestUri = String.valueOf(httpEx.dataSpec.uri);
                    }
                    if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                        HttpDataSource.InvalidResponseCodeException codeEx = (HttpDataSource.InvalidResponseCodeException) cause;
                        httpResponseCode = codeEx.responseCode;
                        httpResponseMessage = codeEx.responseMessage;
                    }
                }
            }
            stackTrace = Log.getStackTraceString(error);
        }

        return PlayerMetadataHelper.formatPlaybackErrorDiagnostic(
                timestamp,
                qualityCtrl != null ? qualityCtrl.getCurrentVideoUrl() : null,
                host != null ? host.getCurrentAnimeId() : null,
                ep != null ? ep.getNumber() : null,
                pd != null ? pd.getPlayer() : null,
                (pd != null && pd.getTeam() != null) ? pd.getTeam().getName() : null,
                qualityCtrl != null ? qualityCtrl.getPreferredQuality() : "Авто",
                qualityCtrl != null ? qualityCtrl.getCurrentVideoDomain() : VideoUrlHelper.DOMAIN_MAIN,
                deviceSummary,
                errorCodeName,
                errorCode,
                errorMessage,
                causeClassName,
                causeMessage,
                httpRequestUri,
                httpResponseCode,
                httpResponseMessage,
                stackTrace
        );
    }

    public void retryWithUrl(String newUrl) {
        if (provider == null || newUrl == null) return;
        provider.safeRunOnUiThread(() -> {
            Log.d(TAG, "Retrying playback with new URL: " + newUrl);
            provider.setCurrentVideoUrl(newUrl);
            boolean isHls = newUrl.contains(".m3u8") || newUrl.contains(":hls:");
            if (isHls) {
                provider.initializeHlsPlayer(newUrl);
            } else if (host != null && host.getPlaybackController() != null) {
                host.switchMediaSource(newUrl, 0L, true);
            } else {
                provider.initializePlayer();
            }
        });
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

        if (provider.shouldPlayDownloadedFile()) {
            DownloadedEpisodeEntity downloadedEp = provider.getDownloadedEpisodeForActive();
            if (downloadedEp != null && downloadedEp.getLocalFilePath() != null) {
                File file = new File(downloadedEp.getLocalFilePath());
                if (file.exists() && file.length() > 0) {
                    provider.setCurrentVideoUrl(Uri.fromFile(file).toString());
                    provider.onActiveQualityResolved(downloadedEp.getQuality());
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

            long estimate = 0;
            try {
                estimate = DefaultBandwidthMeter.getSingletonInstance(provider.getContext()).getBitrateEstimate();
            } catch (Exception ignored) {}

            String effectiveQuality = AutoQualityHelper.resolveBestQuality(
                    provider.getContext(), available, preferredQuality, estimate);

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
                provider.showVideoErrorDialog("Ошибка плеера AnimeLib", "Нет подходящего качества видео для этой озвучки.", () -> {
                    provider.onPlayerSelected(playerData);
                });
                return;
            }

            String videoUrl = VideoUrlHelper.toAbsoluteVideoUrl(selectedQuality.getHref(), provider.getCurrentVideoDomain());
            provider.setCurrentVideoUrl(videoUrl);
            provider.onActiveQualityResolved(selectedQuality.getQuality() + "p");

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

        if (provider.shouldPlayDownloadedFile()) {
            DownloadedEpisodeEntity downloadedEp = provider.getDownloadedEpisodeForActive();
            if (downloadedEp != null && downloadedEp.getLocalFilePath() != null) {
                File file = new File(downloadedEp.getLocalFilePath());
                if (file.exists() && file.length() > 0) {
                    provider.setCurrentVideoUrl(Uri.fromFile(file).toString());
                    provider.onActiveQualityResolved(downloadedEp.getQuality());
                    if (timecodeManager != null) timecodeManager.setTimecodes(playerData);
                    provider.initializePlayer(seekToPosition);
                    ExoPlayer player = provider.getPlayer();
                    if (seekToPosition > 0 && player != null) {
                        player.seekTo(seekToPosition);
                    }
                    return;
                }
            }
        }

        if (timecodeManager != null) timecodeManager.setTimecodes(playerData);

        if (playerData.getSrc() != null && !playerData.getSrc().isEmpty()) {
            String kodikSrc = playerData.getSrc().trim();
            if (!kodikSrc.startsWith("http")) {
                kodikSrc = "https:" + kodikSrc;
            }
            activeKodikSrc = kodikSrc;
            fetchKodikVideoLinks(kodikSrc, seekToPosition, false);
        } else {
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

        provider.safeRunOnUiThread(() -> {
            if (requestToken != activeRequestToken.get()) return;
            provider.showLoading("Получение HLS ссылок...");
        });

        apiService.fetchKodikVideoLinks(normalizedSrc, forceRefresh, new ApiService.KodikVideoCallback() {
            @Override
            public void onKodikVideoReceived(KodikResponse response) {
                provider.safeRunOnUiThread(() -> {
                    if (requestToken != activeRequestToken.get()) return;
                    provider.hideLoading();
                    startHlsPlayer(response, seekToPosition);
                });
            }

            @Override
            public void onError(String error) {
                provider.safeRunOnUiThread(() -> {
                    if (requestToken != activeRequestToken.get()) return;
                    if (!forceRefresh) {
                        retryHandler.post(() -> {
                            if (requestToken == activeRequestToken.get()) {
                                fetchKodikVideoLinks(normalizedSrc, seekToPosition, true);
                            }
                        });
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
            provider.setCurrentVideoUrl(hlsUrl);
            provider.onActiveQualityResolved(effectiveQuality);
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

    public boolean tryRecoverKodikPlayback(String failedUrl, long resumePosition) {
        if (provider == null || apiService == null) return false;

        if (failedUrl != null && !failedUrl.isEmpty()) {
            triedKodikUrls.add(failedUrl);
        }

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
            final String finalSrc = srcToRefresh;
            final int requestToken = activeRequestToken.incrementAndGet();

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

                        String freshUrl = selectNextUntriedCandidate(freshCandidates);
                        if (freshUrl == null && !freshCandidates.isEmpty()) {
                            triedKodikUrls.clear();
                            freshUrl = freshCandidates.get(0);
                        }

                        if (freshUrl != null && !freshUrl.isEmpty()) {
                            triedKodikUrls.add(freshUrl);
                            provider.setCurrentVideoUrl(freshUrl);
                            provider.onActiveQualityResolved(effectiveQuality);
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

        return AutoQualityHelper.resolveBestQuality(
                provider.getContext(), availableQualities, preferredQuality, estimate);
    }
}
