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

    public interface EpisodesCallback {
        void onEpisodeHeaderUpdateQuick();
        void onResetBookmarkState();
        void onInitializeMenuWithoutAutoPlay();
        void onEpisodeChanged(EpisodesListResponse.EpisodeItem episode);
    }

    private EpisodesManager episodesManager;
    private PlayersManager playersManager;
    private ApiService apiService;
    private EpisodesCallback callback;

    private boolean isNewEpisodeSelection = true;
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

    public long getBookmarkTimecode() {
        return bookmarkTimecode;
    }

    public void setBookmarkTimecode(long timecode) {
        this.bookmarkTimecode = timecode;
    }

    public long getSavedPlayerPosition() {
        return savedPlayerPosition;
    }

    public void setSavedPlayerPosition(long position) {
        this.savedPlayerPosition = position;
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

    public void resetBookmarkAndPositionState() {
        this.bookmarkTimecode = 0;
        this.savedPlayerPosition = 0;
        this.autoBookmarkSaved = false;
        if (callback != null) {
            callback.onResetBookmarkState();
        }
    }

    public void loadEpisodes(String animeId, Intent intent) {
        Log.d(TAG, "Loading episodes for anime_id: " + animeId);
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
        resetBookmarkAndPositionState();

        if (episodesManager != null) {
            List<EpisodesListResponse.EpisodeItem> episodes = episodesManager.getEpisodes();
            if (episodes != null && !episodes.isEmpty()) {
                EpisodesListResponse.EpisodeItem firstEpisode = episodes.get(0);
                Log.d(TAG, "Loading first episode: " + firstEpisode.getNumber());

                episodesManager.setCurrentEpisode(firstEpisode);

                if (callback != null) {
                    callback.onEpisodeHeaderUpdateQuick();
                    callback.onEpisodeChanged(firstEpisode);
                }

                if (playersManager != null) {
                    playersManager.loadPlayersForEpisode(firstEpisode.getId());
                }
            } else {
                Log.d(TAG, "No episodes available, initializing menu without auto play");
                if (callback != null) {
                    callback.onInitializeMenuWithoutAutoPlay();
                }
            }
        }
    }

    public void fallbackToUrlDetection(Intent intent) {
        resetBookmarkAndPositionState();

        String animeUrl = (intent != null) ? intent.getStringExtra("anime_url") : null;
        if (episodesManager != null) {
            episodesManager.findAndSetCurrentEpisodeFromUrl(animeUrl);
            EpisodesListResponse.EpisodeItem currentEpisode = episodesManager.getCurrentEpisode();
            if (currentEpisode != null) {
                if (callback != null) {
                    callback.onEpisodeHeaderUpdateQuick();
                    callback.onEpisodeChanged(currentEpisode);
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
        Log.d(TAG, "Episode selected: " + episode.getNumber() + " (ID: " + episode.getId() + "), autoPlay: " + autoPlay);

        this.isNewEpisodeSelection = true;
        resetBookmarkAndPositionState();

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

