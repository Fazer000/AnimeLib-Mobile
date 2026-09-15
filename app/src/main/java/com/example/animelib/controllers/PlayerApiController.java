package com.example.animelib.controllers;

import android.content.Context;

import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.PlayerPreferences;

public class PlayerApiController {

    private final ApiService apiService;

    public PlayerApiController(Context context) {
        this.apiService = new ApiService(context);
    }

    public ApiService getApiService() {
        return apiService;
    }

    public DatabaseManager getDatabaseManager() {
        return apiService != null ? apiService.getDatabaseManager() : null;
    }

    public boolean isAuthorized() {
        return apiService != null && apiService.isAuthorized();
    }

    public String extractAnimeSlug(String url) {
        return apiService != null ? apiService.extractAnimeSlug(url) : null;
    }

    public String extractAnimeId(String url) {
        return apiService != null ? apiService.extractAnimeId(url) : null;
    }

    public void fetchAnimeInfo(String slugOrId, ApiService.AnimeInfoCallback callback) {
        if (apiService != null) {
            apiService.fetchAnimeInfo(slugOrId, callback);
        }
    }

    public void getRelatedTitles(String animeSlug, ApiService.RelatedTitlesCallback callback) {
        if (apiService != null) {
            apiService.getRelatedTitles(animeSlug, callback);
        }
    }

    public void fetchKodikVideoLinks(String kodikSrc, ApiService.KodikVideoCallback callback) {
        if (apiService != null) {
            apiService.fetchKodikVideoLinks(kodikSrc, callback);
        }
    }

    public void savePlayerPreferences(String player, Integer teamId) {
        if (apiService != null) {
            apiService.savePlayerPreferences(player, teamId);
        }
    }

    public void savePlayerPreferences(String player, Long teamId) {
        if (apiService != null) {
            apiService.savePlayerPreferences(player, teamId != null ? teamId.intValue() : null);
        }
    }

    public void savePlayerPreferences(String player, Integer teamId, String quality) {
        if (apiService != null) {
            apiService.savePlayerPreferences(player, teamId, quality);
        }
    }

    public void savePlayerPreferences(String player, Long teamId, String quality) {
        if (apiService != null) {
            apiService.savePlayerPreferences(player, teamId != null ? teamId.intValue() : null, quality);
        }
    }

    public PlayerPreferences loadPlayerPreferences() {
        return apiService != null ? apiService.loadPlayerPreferences() : null;
    }

    public void shutdown() {
        if (apiService != null) {
            apiService.shutdown();
        }
    }
}
