package com.example.animelib.controllers;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.OfflineBookmarkEntity;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.AnimeBookmarkResponse;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.util.AutoQualityHelper;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.SkeletonHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Контроллер офлайн-режима и бесшовного воспроизведения скачанных серий
 * как в полностью автономном режиме, так и при наличии интернета с фоновой синхронизацией озвучек.
 */
public class PlayerOfflineController {

    private static final String TAG = "PlayerOfflineCtrl";

    public interface OfflineHost {
        Intent getIntent();
        String getCurrentAnimeId();
        void setCurrentAnimeId(String animeId);
        void setAutoPlayOnPrepare(boolean autoPlayOnPrepare);
        boolean isAutoPlayOnPrepare();
        ApiService getApiService();
        DatabaseManager getDatabaseManager();
        ExecutorService getExecutor();
        EpisodesManager getEpisodesManager();
        PlayersManager getPlayersManager();
        PlayerUIBinder getUiBinder();
        PlayerPlaybackController getPlaybackController();
        PlayerControlsOverlayManager getControlsOverlayManager();
        PlayerQualityController getQualityController();
        PlayerVideoResolverController getVideoResolverController();
        PlayerEpisodesController getEpisodesController();
        PlayerProgressController getProgressController();
        PlayerCommentsController getCommentsController();
        PlayerAnimeInfoController getAnimeInfoController();
        PlayerSplashScreenController getSplashScreenController();
        PlayerPanelsController getPanelsController();
        PlayerDownloadController getDownloadController();
        PlayerSubtitlesController getSubtitlesController();
        PlayerPortraitController getPortraitController();
        void markAnimeInfoLoaded();
        void markEpisodeAndVoiceoversLoaded();
        void showLoading(String message);
        void hideLoading();
        void updateControllerAutoHide();
        void safeRunOnUiThread(Runnable runnable);
        void finishActivity();
    }

    private final Activity activity;
    private final OfflineHost host;

    private boolean isOfflineMode = false;
    private boolean isFirstOfflineLaunch = true;
    private final Map<String, DownloadedEpisodeEntity> offlineEpisodesMap = new HashMap<>();
    private volatile boolean isSyncingPlayerData = false;

    public PlayerOfflineController(Activity activity, OfflineHost host) {
        this.activity = activity;
        this.host = host;
    }

    public boolean isOfflineMode() {
        return isOfflineMode;
    }

    public void setOfflineMode(boolean offlineMode) {
        this.isOfflineMode = offlineMode;
    }

    public boolean isFirstOfflineLaunch() {
        return isFirstOfflineLaunch;
    }

    public void setFirstOfflineLaunch(boolean firstOfflineLaunch) {
        this.isFirstOfflineLaunch = firstOfflineLaunch;
    }

    public void resetForNewAnime() {
        isOfflineMode = false;
        isFirstOfflineLaunch = true;
        offlineEpisodesMap.clear();
    }

    @Nullable
    public DownloadedEpisodeEntity getOfflineEpisode(@Nullable EpisodesListResponse.EpisodeItem episode) {
        if (episode == null) return null;
        DownloadedEpisodeEntity offlineEp = offlineEpisodesMap.get(episode.getNumber());
        if (offlineEp == null) {
            offlineEp = offlineEpisodesMap.get(String.valueOf(episode.getId()));
        }
        return offlineEp;
    }

    @Nullable
    public DownloadedEpisodeEntity getDownloadedEpisodeForActive() {
        PlayerDownloadController downloadCtrl = host.getDownloadController();
        if (downloadCtrl == null) return null;
        Intent intent = host.getIntent();
        String animeId = host.getCurrentAnimeId() != null
                ? host.getCurrentAnimeId()
                : (intent != null ? intent.getStringExtra("EXTRA_ANIME_ID") : null);
        String localPath = intent != null ? intent.getStringExtra("EXTRA_LOCAL_FILE_PATH") : null;
        return downloadCtrl.getDownloadedEpisodeForActive(animeId, host.getEpisodesManager(), host.getPlayersManager(), localPath);
    }

    public boolean hasValidDownloadedFile(@Nullable DownloadedEpisodeEntity downloadedEp) {
        if (downloadedEp == null || downloadedEp.getLocalFilePath() == null) return false;
        File f = new File(downloadedEp.getLocalFilePath());
        return f.exists() && f.length() > 0;
    }

    public boolean isSyntheticPlayerData(@Nullable EpisodeResponse.PlayerData pd) {
        return PlayerMetadataHelper.isSyntheticPlayerData(pd);
    }

    @Nullable
    public EpisodeResponse.PlayerData findMatchingOnlinePlayerForDownloaded(
            List<EpisodeResponse.PlayerData> allPlayers,
            @Nullable DownloadedEpisodeEntity downloadedEp) {
        String targetTeam = downloadedEp != null ? downloadedEp.getTeamName() : null;
        String targetType = downloadedEp != null ? downloadedEp.getPlayerType() : null;
        return PlayerMetadataHelper.findMatchingOnlinePlayerForDownloaded(allPlayers, targetTeam, targetType);
    }

    public EpisodeResponse.PlayerData createSyntheticPlayerDataForDownloaded(@Nullable DownloadedEpisodeEntity downloadedEp) {
        String playerType = downloadedEp != null ? downloadedEp.getPlayerType() : "animelib";
        String teamName = downloadedEp != null ? downloadedEp.getTeamName() : "Офлайн";
        return PlayerMetadataHelper.createSyntheticPlayerData(playerType, teamName);
    }

    public List<String> getQualitiesWithDownloadedOption(List<String> onlineQualities) {
        PlayerDownloadController downloadCtrl = host.getDownloadController();
        if (downloadCtrl != null) {
            return downloadCtrl.getQualitiesWithDownloadedOption(onlineQualities, getDownloadedEpisodeForActive());
        }
        return onlineQualities != null ? onlineQualities : new ArrayList<>();
    }

    public boolean isPlayingDownloadedVideo() {
        if (isOfflineMode) {
            return true;
        }
        PlayerQualityController qualityCtrl = host.getQualityController();
        String currentVideoUrl = qualityCtrl != null ? qualityCtrl.getCurrentVideoUrl() : null;
        if (currentVideoUrl != null && (currentVideoUrl.startsWith("file:") || currentVideoUrl.startsWith("/"))) {
            return true;
        }
        return qualityCtrl != null
                && qualityCtrl.isUsingDownloadedSource()
                && hasValidDownloadedFile(getDownloadedEpisodeForActive());
    }

    @Nullable
    public String resolveOfflineAnimeId() {
        Intent intent = host.getIntent();
        String currentAnimeId = host.getCurrentAnimeId();
        String animeId = (currentAnimeId != null && !currentAnimeId.isEmpty())
                ? currentAnimeId
                : (intent != null ? intent.getStringExtra("EXTRA_ANIME_ID") : null);
        if ((animeId == null || animeId.isEmpty()) && intent != null) {
            String localPath = intent.getStringExtra("EXTRA_LOCAL_FILE_PATH");
            if (localPath != null && !localPath.isEmpty()) {
                DatabaseManager dbMgr = host.getDatabaseManager();
                if (dbMgr == null && host.getApiService() != null) {
                    dbMgr = host.getApiService().getDatabaseManager();
                }
                if (dbMgr != null) {
                    DownloadedEpisodeEntity dep = dbMgr.findEpisodeByPath(localPath);
                    if (dep != null) {
                        animeId = dep.getAnimeId();
                    }
                }
            }
        }
        return animeId;
    }

    @Nullable
    public DownloadedEpisodeEntity getCurrentOfflineEpisode() {
        DownloadedEpisodeEntity activeDownloaded = getDownloadedEpisodeForActive();
        if (activeDownloaded != null) {
            return activeDownloaded;
        }
        if (!isOfflineMode) return null;

        EpisodesManager episodesManager = host.getEpisodesManager();
        EpisodesListResponse.EpisodeItem cur = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
        if (cur != null) {
            if (cur.getNumber() != null && offlineEpisodesMap.containsKey(cur.getNumber())) {
                return offlineEpisodesMap.get(cur.getNumber());
            }
            if (offlineEpisodesMap.containsKey(String.valueOf(cur.getId()))) {
                return offlineEpisodesMap.get(String.valueOf(cur.getId()));
            }
        }
        Intent intent = host.getIntent();
        String epNum = intent != null ? intent.getStringExtra("EXTRA_EPISODE_NUMBER") : null;
        if (epNum != null && offlineEpisodesMap.containsKey(epNum)) {
            return offlineEpisodesMap.get(epNum);
        }
        if (!offlineEpisodesMap.isEmpty()) {
            return offlineEpisodesMap.values().iterator().next();
        }
        return null;
    }

    public String getOfflineVoiceoverName() {
        DownloadedEpisodeEntity ep = getCurrentOfflineEpisode();
        if (ep != null && ep.getTeamName() != null && !ep.getTeamName().trim().isEmpty()) {
            return ep.getTeamName().trim();
        }
        return "Офлайн";
    }

    public String getOfflinePlayerTypeName() {
        DownloadedEpisodeEntity ep = getCurrentOfflineEpisode();
        if (ep != null && ep.getPlayerType() != null && !ep.getPlayerType().trim().isEmpty()) {
            return PlayerMetadataHelper.capitalizeFirst(ep.getPlayerType(), "Офлайн");
        }
        return "Офлайн";
    }

    public boolean tryStartDownloadedEpisodeImmediately(EpisodesListResponse.EpisodeItem episode, long initialBookmarkMs) {
        EpisodesManager episodesManager = host.getEpisodesManager();
        PlayersManager playersManager = host.getPlayersManager();
        if (isOfflineMode || episode == null || episodesManager == null || playersManager == null) {
            return false;
        }
        episodesManager.setCurrentEpisode(episode);
        DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
        if (!hasValidDownloadedFile(downloadedEp)) {
            return false;
        }

        Log.d(TAG, "Starting downloaded episode immediately with online switch capability: ep=" + episode.getNumber());
        PlayerQualityController qualityCtrl = host.getQualityController();
        if (qualityCtrl != null) {
            qualityCtrl.setUsingDownloadedSource(true);
            qualityCtrl.setActivePlayingQuality(AutoQualityHelper.normalizeResolutionLabel(downloadedEp.getQuality()));
        }
        host.markEpisodeAndVoiceoversLoaded();

        PlayerEpisodesController epCtrl = host.getEpisodesController();
        if (epCtrl != null) {
            epCtrl.setNewEpisodeSelection(false);
            epCtrl.setSavedPlayerPosition(0);
            if (initialBookmarkMs > 0) {
                epCtrl.setBookmarkTimecode(initialBookmarkMs);
            }
        }

        PlayerCommentsController commentsCtrl = host.getCommentsController();
        if (commentsCtrl != null) {
            commentsCtrl.setCurrentEpisode(episode);
            if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                commentsCtrl.loadCommentsForPortraitIfNeeded();
            }
        }

        episodesManager.updateEpisodeNavigationButtonsVisibility();
        episodesManager.updateEpisodesRecyclerView();

        PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
        if (animeInfoCtrl != null) {
            animeInfoCtrl.hidePlaceholderAnimated();
        }
        EpisodeResponse.PlayerData syntheticData = createSyntheticPlayerDataForDownloaded(downloadedEp);
        playersManager.setCurrentPlayerData(syntheticData);
        playersManager.hideMenu();
        PlayerUIBinder ui = host.getUiBinder();
        if (ui != null && ui.menuLoadingOverlay != null) {
            ui.menuLoadingOverlay.setVisibility(View.GONE);
        }
        if (ui != null && ui.menuToggleButton != null) {
            ui.menuToggleButton.setEnabled(true);
            ui.menuToggleButton.setClickable(true);
            ui.menuToggleButton.setAlpha(0.3f);
        }
        host.hideLoading();

        PlayerPortraitController portraitCtrl = host.getPortraitController();
        if (portraitCtrl != null) {
            portraitCtrl.enableBookmarkButtons();
            portraitCtrl.updateEpisodeHeaderQuick();
            portraitCtrl.updatePortraitVoiceoverPlayerUI();
            portraitCtrl.updatePortraitHeaderTitlesUI();
        }

        PlayerVideoResolverController resolverCtrl = host.getVideoResolverController();
        if (resolverCtrl != null) {
            resolverCtrl.stopCurrentPlaybackAndRestartVoiceover(syntheticData);
        }
        playersManager.loadPlayersForEpisode(episode.getId(), false);
        return true;
    }

    public boolean handleOnlinePlayersErrorWithOfflineFallback() {
        DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
        if (!hasValidDownloadedFile(downloadedEp)) {
            return false;
        }
        PlayerQualityController qualityCtrl = host.getQualityController();
        if (qualityCtrl != null) {
            qualityCtrl.setUsingDownloadedSource(true);
            qualityCtrl.setActivePlayingQuality(AutoQualityHelper.normalizeResolutionLabel(downloadedEp.getQuality()));
        }
        PlayerUIBinder ui = host.getUiBinder();
        if (ui != null && ui.menuLoadingOverlay != null) {
            ui.menuLoadingOverlay.setVisibility(View.GONE);
        }
        if (ui != null && ui.menuToggleButton != null) {
            ui.menuToggleButton.setEnabled(true);
            ui.menuToggleButton.setClickable(true);
            ui.menuToggleButton.setAlpha(0.3f);
        }
        host.hideLoading();

        EpisodeResponse.PlayerData synthetic = createSyntheticPlayerDataForDownloaded(downloadedEp);
        PlayersManager playersManager = host.getPlayersManager();
        if (playersManager != null) {
            playersManager.setCurrentPlayerData(synthetic);
        }
        PlayerPortraitController portraitCtrl = host.getPortraitController();
        if (portraitCtrl != null) {
            portraitCtrl.updateEpisodeHeaderQuick();
            portraitCtrl.updatePortraitVoiceoverPlayerUI();
        }
        PlayerVideoResolverController resolverCtrl = host.getVideoResolverController();
        if (resolverCtrl != null) {
            resolverCtrl.stopCurrentPlaybackAndRestartVoiceover(synthetic);
        }
        return true;
    }

    public void syncCurrentPlayerDataForDownloadedIfNeeded() {
        if (isSyncingPlayerData) return;
        isSyncingPlayerData = true;
        try {
            DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
            if (hasValidDownloadedFile(downloadedEp) && isPlayingDownloadedVideo()) {
                PlayersManager playersManager = host.getPlayersManager();
                if (playersManager == null) return;

                EpisodeResponse.PlayerData currentPD = playersManager.getCurrentPlayerData();
                if (currentPD != null && !isSyntheticPlayerData(currentPD)) {
                    return;
                }

                List<EpisodeResponse.PlayerData> allPlayers = new ArrayList<>();
                if (playersManager.getAnimelibPlayers() != null) allPlayers.addAll(playersManager.getAnimelibPlayers());
                if (playersManager.getKodikPlayers() != null) allPlayers.addAll(playersManager.getKodikPlayers());

                EpisodeResponse.PlayerData matchedPlayer = findMatchingOnlinePlayerForDownloaded(allPlayers, downloadedEp);
                if (matchedPlayer != null) {
                    playersManager.setCurrentPlayerData(matchedPlayer);
                } else if (currentPD == null) {
                    playersManager.setCurrentPlayerData(createSyntheticPlayerDataForDownloaded(downloadedEp));
                }
            }
        } finally {
            isSyncingPlayerData = false;
        }
    }

    public void onBackgroundOnlinePlayersLoadedForDownloaded(List<EpisodeResponse.PlayerData> players) {
        host.safeRunOnUiThread(() -> {
            host.markEpisodeAndVoiceoversLoaded();
            PlayerUIBinder ui = host.getUiBinder();
            if (ui != null && ui.menuLoadingOverlay != null) {
                ui.menuLoadingOverlay.setVisibility(View.GONE);
            }
            if (isPlayingDownloadedVideo()) {
                syncCurrentPlayerDataForDownloadedIfNeeded();
                PlayerPortraitController portraitCtrl = host.getPortraitController();
                if (portraitCtrl != null) {
                    portraitCtrl.updateEpisodeHeaderQuick();
                    portraitCtrl.updatePortraitVoiceoverPlayerUI();
                }
                PlayerQualityController qualityCtrl = host.getQualityController();
                if (qualityCtrl != null) {
                    qualityCtrl.refreshQualityUiState();
                }
            }
        });
    }

    public void playOfflineFile(String localPath, String animeTitle, String episodeTitle) {
        host.markAnimeInfoLoaded();
        host.markEpisodeAndVoiceoversLoaded();
        PlayerSplashScreenController splashCtrl = host.getSplashScreenController();
        if (splashCtrl != null) {
            splashCtrl.dismissImmediately();
        }
        isOfflineMode = true;

        File file = new File(localPath);
        if (!file.exists()) {
            CustomToast.showWarning(activity, "Офлайн файл не найден: " + localPath);
            host.finishActivity();
            return;
        }

        PlayerCommentsController commentsCtrl = host.getCommentsController();
        if (commentsCtrl != null) {
            commentsCtrl.setOfflineMode(true);
        }

        PlayerUIBinder ui = host.getUiBinder();
        PlayerPortraitController portraitCtrl = host.getPortraitController();
        if (ui != null && ui.animeTitleView != null) {
            SkeletonHelper.hideSkeleton(ui.animeTitleView, animeTitle != null ? animeTitle : "");
        }
        if (portraitCtrl != null) {
            portraitCtrl.applyOfflineTitleViews(animeTitle, episodeTitle);
        }

        Uri videoUri = Uri.fromFile(file);
        PlayerPlaybackController playbackCtrl = host.getPlaybackController();
        PlayerSubtitlesController subsCtrl = host.getSubtitlesController();
        if (playbackCtrl != null) {
            MediaItem mediaItem = subsCtrl != null
                    ? subsCtrl.createMediaItemWithSubtitles(videoUri.toString())
                    : MediaItem.fromUri(videoUri);
            playbackCtrl.initializeOfflinePlayer(videoUri.toString(), mediaItem, host.isAutoPlayOnPrepare());
            host.setAutoPlayOnPrepare(true);
        }

        if (ui != null && ui.playerView != null) {
            ui.playerView.setUseController(true);
        }
        host.updateControllerAutoHide();
        host.hideLoading();
        setupOfflineEpisodes(localPath);
        applyOfflineUIState();
        if (portraitCtrl != null) {
            portraitCtrl.hideAllSkeletonsForOffline();
        }
    }

    public void setupOfflineEpisodes(String localPath) {
        if (!isOfflineMode) return;
        ExecutorService executor = host.getExecutor();
        ApiService apiService = host.getApiService();
        if (executor == null || apiService == null) return;

        executor.execute(() -> {
            DatabaseManager databaseManager = apiService.getDatabaseManager();
            if (databaseManager == null) return;

            Intent intent = host.getIntent();
            String animeId = intent != null ? intent.getStringExtra("EXTRA_ANIME_ID") : null;
            String intentEpNumber = intent != null ? intent.getStringExtra("EXTRA_EPISODE_NUMBER") : null;

            if (animeId == null && localPath != null) {
                DownloadedEpisodeEntity currentEp = databaseManager.findEpisodeByPath(localPath);
                if (currentEp != null) {
                    animeId = currentEp.getAnimeId();
                }
            }

            if (animeId == null) return;
            host.setCurrentAnimeId(animeId);

            List<DownloadedEpisodeEntity> downloadedEps = databaseManager.getEpisodesForAnimeSync(animeId);
            if (downloadedEps == null || downloadedEps.isEmpty()) return;

            EpisodesManager episodesManager = host.getEpisodesManager();
            List<EpisodesListResponse.EpisodeItem> episodeItems = new ArrayList<>();
            EpisodesListResponse.EpisodeItem currentItem = null;

            for (DownloadedEpisodeEntity dep : downloadedEps) {
                EpisodesListResponse.EpisodeItem item = new EpisodesListResponse.EpisodeItem();
                int id = dep.getEpisodeId();
                if (id <= 0) {
                    try {
                        id = Integer.parseInt(dep.getEpisodeNumber());
                    } catch (Exception ignored) {
                        id = Math.abs(dep.getId().hashCode());
                    }
                }
                item.setId(id);
                item.setNumber(dep.getEpisodeNumber());
                item.setName(PlayerMetadataHelper.cleanEpisodeName(dep.getEpisodeName(), dep.getEpisodeNumber()));

                if (dep.getEpisodeNumber() != null) {
                    offlineEpisodesMap.put(dep.getEpisodeNumber(), dep);
                }
                offlineEpisodesMap.put(String.valueOf(id), dep);
                episodeItems.add(item);

                // Priority 1: Exact match by localPath
                if (localPath != null && dep.getLocalFilePath() != null) {
                    String normLocal = localPath.startsWith("file://") ? localPath.substring(7) : localPath;
                    String normDep = dep.getLocalFilePath().startsWith("file://") ? dep.getLocalFilePath().substring(7) : dep.getLocalFilePath();
                    File f1 = new File(normLocal);
                    File f2 = new File(normDep);
                    if (normLocal.equalsIgnoreCase(normDep)
                            || f1.getAbsolutePath().equalsIgnoreCase(f2.getAbsolutePath())
                            || (f1.getName() != null && f1.getName().equalsIgnoreCase(f2.getName()))) {
                        currentItem = item;
                    }
                }
            }

            // Priority 2: Match by existing episodesManager current episode
            if (currentItem == null && episodesManager != null) {
                EpisodesListResponse.EpisodeItem existingCur = episodesManager.getCurrentEpisode();
                if (existingCur != null) {
                    for (EpisodesListResponse.EpisodeItem item : episodeItems) {
                        if (item.getNumber() != null && item.getNumber().equals(existingCur.getNumber())) {
                            currentItem = item;
                            break;
                        }
                        if (item.getId() == existingCur.getId()) {
                            currentItem = item;
                            break;
                        }
                    }
                }
            }

            // Priority 3: Match by intent episode number (only on first launch)
            if (currentItem == null && isFirstOfflineLaunch && intentEpNumber != null && !intentEpNumber.isEmpty()) {
                for (EpisodesListResponse.EpisodeItem item : episodeItems) {
                    if (item.getNumber() != null && item.getNumber().equals(intentEpNumber)) {
                        currentItem = item;
                        break;
                    }
                }
            }

            // Priority 4: Offline bookmark (only on first launch)
            OfflineBookmarkEntity offlineBookmark = databaseManager.getOfflineBookmarkSync(animeId);
            if (currentItem == null && isFirstOfflineLaunch && offlineBookmark != null) {
                for (EpisodesListResponse.EpisodeItem item : episodeItems) {
                    if (item.getNumber() != null && item.getNumber().equals(offlineBookmark.getEpisodeNumber())) {
                        currentItem = item;
                        break;
                    }
                    if (item.getId() == offlineBookmark.getEpisodeId()) {
                        currentItem = item;
                        break;
                    }
                }
            }

            if (currentItem == null && !episodeItems.isEmpty()) {
                currentItem = episodeItems.get(0);
            }

            final List<EpisodesListResponse.EpisodeItem> finalItems = episodeItems;
            final EpisodesListResponse.EpisodeItem finalCurrent = currentItem;

            host.safeRunOnUiThread(() -> {
                if (episodesManager != null) {
                    episodesManager.setEpisodes(finalItems);
                    if (finalCurrent != null) {
                        episodesManager.setCurrentEpisode(finalCurrent);
                    }
                    PlayerProgressController progressCtrl = host.getProgressController();
                    if (offlineBookmark != null) {
                        AnimeBookmarkResponse.BookmarkData bmData = new AnimeBookmarkResponse.BookmarkData();
                        bmData.setItemId(offlineBookmark.getEpisodeId());
                        bmData.setProgress(offlineBookmark.getTimecode());
                        episodesManager.updateBookmarkInAdapter(bmData);

                        boolean isCurrentBookmarked = finalCurrent != null &&
                                (finalCurrent.getId() == offlineBookmark.getEpisodeId()
                                        || (finalCurrent.getNumber() != null && finalCurrent.getNumber().equals(offlineBookmark.getEpisodeNumber())));

                        if (progressCtrl != null) {
                            progressCtrl.updateBookmarkButtonColor(isCurrentBookmarked);
                        }
                        if (isCurrentBookmarked && offlineBookmark.getPositionMs() > 0 && isFirstOfflineLaunch) {
                            PlayerPlaybackController playbackCtrl = host.getPlaybackController();
                            ExoPlayer player = playbackCtrl != null ? playbackCtrl.getPlayer() : null;
                            if (player != null) {
                                player.seekTo(offlineBookmark.getPositionMs());
                                Log.d(TAG, "Seeked player to offline bookmark position: " + offlineBookmark.getPositionMs() + "ms");
                            }
                        }
                    } else if (progressCtrl != null) {
                        progressCtrl.updateBookmarkButtonColor(false);
                    }
                    episodesManager.updateEpisodeNavigationButtonsVisibility();
                }

                isFirstOfflineLaunch = false;
                PlayerUIBinder ui = host.getUiBinder();
                if (ui != null && ui.episodesMenuButton != null) {
                    boolean isPortrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                    ui.episodesMenuButton.setEnabled(true);
                    ui.episodesMenuButton.setAlpha(1.0f);
                    ui.episodesMenuButton.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
                }

                PlayerPortraitController portraitCtrl = host.getPortraitController();
                if (portraitCtrl != null) {
                    portraitCtrl.updateEpisodeHeaderQuick();
                    portraitCtrl.hideAllSkeletonsForOffline();
                }
            });
        });
    }

    public void applyOfflineUIState() {
        isOfflineMode = true;

        PlayerCommentsController commentsCtrl = host.getCommentsController();
        if (commentsCtrl != null) {
            commentsCtrl.setOfflineMode(true);
        }

        PlayerUIBinder ui = host.getUiBinder();
        if (ui != null) {
            if (ui.menuPanelContainer != null) {
                ui.menuPanelContainer.forceClose();
                ui.menuPanelContainer.setVisibility(View.GONE);
            }
            if (ui.commentsPanelContainer != null) {
                ui.commentsPanelContainer.forceClose();
                ui.commentsPanelContainer.setVisibility(View.GONE);
            }
            if (ui.menuOverlay != null) {
                ui.menuOverlay.setVisibility(View.GONE);
            }
            if (ui.slidingMenuPanel != null) {
                ui.slidingMenuPanel.setVisibility(View.GONE);
            }
        }

        PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
        if (animeInfoCtrl != null) {
            animeInfoCtrl.hidePlaceholderAnimated();
            animeInfoCtrl.setOfflineMode(true);
        }

        PlayerPanelsController panelsCtrl = host.getPanelsController();
        if (panelsCtrl != null) {
            panelsCtrl.applyPlayerSidePanelTransform(0f);
        }

        if (ui != null) {
            if (ui.playerContainer != null) {
                ui.playerContainer.setPivotX(0f);
                ui.playerContainer.setPivotY(0f);
                ui.playerContainer.setScaleX(1f);
                ui.playerContainer.setScaleY(1f);
                ui.playerContainer.setTranslationX(0f);
                ui.playerContainer.setTranslationY(0f);
            }

            if (ui.settingsButton != null) {
                ui.settingsButton.setEnabled(true);
                ui.settingsButton.setAlpha(1.0f);
                ui.settingsButton.setVisibility(View.VISIBLE);
            }

            if (ui.menuToggleButton != null) {
                ui.menuToggleButton.setEnabled(true);
                ui.menuToggleButton.setClickable(true);
                ui.menuToggleButton.setAlpha(0.3f);
            }

            EpisodesManager episodesManager = host.getEpisodesManager();
            boolean hasEpisodes = episodesManager != null && !episodesManager.getEpisodes().isEmpty();
            if (ui.episodesMenuButton != null) {
                boolean isPortrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                ui.episodesMenuButton.setEnabled(hasEpisodes);
                ui.episodesMenuButton.setAlpha(hasEpisodes ? 1.0f : 0.3f);
                ui.episodesMenuButton.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
            }

            if (ui.commentsButton != null) {
                ui.commentsButton.setEnabled(false);
                ui.commentsButton.setAlpha(0.3f);
            }

            if (ui.bookmarkButton != null) {
                ui.bookmarkButton.setEnabled(true);
                ui.bookmarkButton.setClickable(true);
                ui.bookmarkButton.setAlpha(1.0f);
            }

            if (episodesManager != null) {
                episodesManager.updateEpisodeNavigationButtonsVisibility();
            }

            if (ui.btnDownloadFromMenu != null) {
                ui.btnDownloadFromMenu.setEnabled(false);
                ui.btnDownloadFromMenu.setAlpha(0.3f);
                ui.btnDownloadFromMenu.setVisibility(View.GONE);
            }
        }

        PlayerPortraitController portraitCtrl = host.getPortraitController();
        if (portraitCtrl != null) {
            portraitCtrl.applyOfflineButtonStates();
            portraitCtrl.hideAllSkeletonsForOffline();
        }

        host.hideLoading();
        PlayerControlsOverlayManager overlayCtrl = host.getControlsOverlayManager();
        if (overlayCtrl != null) {
            overlayCtrl.updatePlayPauseAndLoadingState(false);
        }
    }
}
