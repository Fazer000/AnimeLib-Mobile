package com.example.animelib.controllers;

import android.content.Intent;
import android.util.Log;

import com.example.animelib.api.ApiService;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.EpisodesListResponse;

import java.util.List;

/**
 * Контроллер для загрузки, выбора, таймкодов и навигации по эпизодам аниме.
 */
public class PlayerEpisodesController {

    private static final String TAG = "PlayerEpisodesCtrl";

    private static void logDebug(String message) {
        try {
            Log.d(TAG, message);
        } catch (RuntimeException ignored) {
            // Safe no-op in pure JVM unit tests
        }
    }

    public interface EpisodesCallback {
        void onEpisodeHeaderUpdateQuick();
        void onResetBookmarkState();
        void onInitializeMenuWithoutAutoPlay();
        void onEpisodeChanged(EpisodesListResponse.EpisodeItem episode);
        default boolean tryStartDownloadedEpisodeImmediately(EpisodesListResponse.EpisodeItem episode, long initialBookmarkMs) {
            return false;
        }
    }

    private EpisodesManager episodesManager;
    private PlayersManager playersManager;
    private ApiService apiService;
    private EpisodesCallback callback;

    private boolean isNewEpisodeSelection = true;
    private boolean initialBookmarkConsumed = false;
    private boolean userSwitchedEpisode = false;
    private long bookmarkTimecode = 0;
    private long savedPlayerPosition = 0;
    private boolean autoBookmarkSaved = false;

    public PlayerEpisodesController() {}

    public void initialize(EpisodesManager episodesManager, PlayersManager playersManager, ApiService apiService, EpisodesCallback callback) {
        this.episodesManager = episodesManager;
        this.playersManager = playersManager;
        this.apiService = apiService;
        this.callback = callback;
    }

    public boolean isNewEpisodeSelection() {
        return isNewEpisodeSelection;
    }

    public void setNewEpisodeSelection(boolean newEpisodeSelection) {
        isNewEpisodeSelection = newEpisodeSelection;
    }

    public boolean hasUserSwitchedEpisode() {
        return userSwitchedEpisode;
    }

    public boolean isInitialBookmarkConsumed() {
        return initialBookmarkConsumed;
    }

    public void markInitialBookmarkConsumed() {
        this.initialBookmarkConsumed = true;
        this.bookmarkTimecode = 0;
    }

    public long getBookmarkTimecode() {
        return bookmarkTimecode;
    }

    public void setBookmarkTimecode(long timecode) {
        if (timecode > 0 && (userSwitchedEpisode || initialBookmarkConsumed)) {
            logDebug("Ignoring bookmark timecode " + timecode + "ms (switched=" + userSwitchedEpisode + ", consumed=" + initialBookmarkConsumed + ")");
            return;
        }
        this.bookmarkTimecode = Math.max(0L, timecode);
    }

    public long getSavedPlayerPosition() {
        return savedPlayerPosition;
    }

    public void setSavedPlayerPosition(long position) {
        this.savedPlayerPosition = Math.max(0L, position);
    }

    public boolean isAutoBookmarkSaved() {
        return autoBookmarkSaved;
    }

    public void setAutoBookmarkSaved(boolean saved) {
        this.autoBookmarkSaved = saved;
    }

    public long getStartPosition() {
        return savedPlayerPosition > 0 ? savedPlayerPosition : bookmarkTimecode;
    }

    /**
     * Возвращает стартовую позицию для запуска источника и сразу сбрасывает одноразовые значения
     * (таймкод закладки при входе в плеер или сохраненную позицию при смене озвучки),
     * чтобы при последующем переключении эпизода воспроизведение всегда начиналось с 00:00.
     */
    public long consumeStartPosition() {
        long startPos = savedPlayerPosition > 0 ? savedPlayerPosition : bookmarkTimecode;
        this.savedPlayerPosition = 0;
        if (this.bookmarkTimecode > 0) {
            this.initialBookmarkConsumed = true;
            this.bookmarkTimecode = 0;
        }
        return Math.max(0L, startPos);
    }

    public void resetForNewAnime() {
        this.isNewEpisodeSelection = true;
        this.initialBookmarkConsumed = false;
        this.userSwitchedEpisode = false;
        resetBookmarkAndPositionState();
    }

    public void markEpisodeSwitched() {
        this.userSwitchedEpisode = true;
        this.initialBookmarkConsumed = true;
        this.isNewEpisodeSelection = true;
        resetBookmarkAndPositionState();
    }

    public void resetBookmarkAndPositionState() {
        this.bookmarkTimecode = 0;
        this.savedPlayerPosition = 0;
        this.autoBookmarkSaved = false;
        if (callback != null) {
            callback.onResetBookmarkState();
        }
    }

    public void loadEpisodes(String animeId, Intent intent) {
        logDebug("Loading episodes for anime_id: " + animeId);
        resetForNewAnime();
        String animeUrl = (intent != null) ? intent.getStringExtra("anime_url") : null;
        String mediaSlug = null;
        if (animeUrl != null && !animeUrl.isEmpty()) {
            mediaSlug = ApiService.extractMediaSlugFromUrl(animeUrl);
        }

        if (episodesManager != null) {
            if (mediaSlug != null) {
                episodesManager.loadEpisodesWithBookmark(animeId, mediaSlug);
            } else {
                episodesManager.loadEpisodes(animeId);
            }
        }
    }

    public void loadFirstEpisode() {
        this.isNewEpisodeSelection = true;
        this.initialBookmarkConsumed = true;
        resetBookmarkAndPositionState();

        if (episodesManager != null) {
            List<EpisodesListResponse.EpisodeItem> episodes = episodesManager.getEpisodes();
            if (episodes != null && !episodes.isEmpty()) {
                EpisodesListResponse.EpisodeItem firstEpisode = episodes.get(0);
                logDebug("Loading first episode: " + firstEpisode.getNumber());

                episodesManager.setCurrentEpisode(firstEpisode);

                if (callback != null) {
                    callback.onEpisodeHeaderUpdateQuick();
                    callback.onEpisodeChanged(firstEpisode);
                    if (callback.tryStartDownloadedEpisodeImmediately(firstEpisode, 0L)) {
                        return;
                    }
                }

                if (playersManager != null) {
                    playersManager.loadPlayersForEpisode(firstEpisode.getId());
                }
            } else {
                logDebug("No episodes available, initializing menu without auto play");
                if (callback != null) {
                    callback.onInitializeMenuWithoutAutoPlay();
                }
            }
        }
    }

    public void fallbackToUrlDetection(Intent intent) {
        this.isNewEpisodeSelection = true;
        this.initialBookmarkConsumed = true;
        resetBookmarkAndPositionState();

        String animeUrl = (intent != null) ? intent.getStringExtra("anime_url") : null;
        if (episodesManager != null) {
            episodesManager.findAndSetCurrentEpisodeFromUrl(animeUrl);
            EpisodesListResponse.EpisodeItem currentEpisode = episodesManager.getCurrentEpisode();
            if (currentEpisode != null) {
                if (callback != null) {
                    callback.onEpisodeHeaderUpdateQuick();
                    callback.onEpisodeChanged(currentEpisode);
                    if (callback.tryStartDownloadedEpisodeImmediately(currentEpisode, 0L)) {
                        return;
                    }
                }

                if (playersManager != null) {
                    playersManager.loadPlayersForEpisode(currentEpisode.getId());
                }
            } else {
                loadFirstEpisode();
            }
        }
    }

    public void onEpisodeSelected(EpisodesListResponse.EpisodeItem episode, boolean autoPlay) {
        if (episode == null) return;
        logDebug("Episode selected: " + episode.getNumber() + " (ID: " + episode.getId() + "), autoPlay: " + autoPlay);

        markEpisodeSwitched();

        if (episodesManager != null) {
            episodesManager.setCurrentEpisode(episode);
            episodesManager.updateEpisodeNavigationButtonsVisibility();
            episodesManager.updateEpisodesRecyclerView();
        }

        if (callback != null) {
            callback.onEpisodeHeaderUpdateQuick();
            callback.onEpisodeChanged(episode);
        }

        if (playersManager != null) {
            playersManager.loadPlayersForEpisode(episode.getId());
        }
    }
}

