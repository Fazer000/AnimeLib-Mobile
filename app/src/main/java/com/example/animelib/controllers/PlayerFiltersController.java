package com.example.animelib.controllers;

import android.content.Context;
import androidx.media3.ui.PlayerView;

import com.example.animelib.managers.VideoFiltersManager;
import com.example.animelib.api.ApiService;

public class PlayerFiltersController {

    private VideoFiltersManager videoFiltersManager;
    private float filterBrightness = 0f;
    private float filterContrast = 100f;
    private float filterSaturation = 100f;
    private float filterGamma = 1.0f;
    private float filterHue = 0f;

    public void init(Context context, PlayerView playerView) {
        if (context != null && playerView != null) {
            videoFiltersManager = new VideoFiltersManager(context, playerView);
            applyFilters();
        }
    }

    public void loadSettingsFromApi(ApiService apiService) {
        if (apiService == null) return;
        float[] filters = apiService.loadVideoFilters();
        if (filters != null && filters.length >= 5) {
            filterBrightness = filters[0];
            filterContrast = filters[1];
            filterSaturation = filters[2];
            filterGamma = filters[3];
            filterHue = filters[4];
            applyFilters();
        }
    }

    public void setFilters(float b, float c, float s, float g, float h) {
        this.filterBrightness = b;
        this.filterContrast = c;
        this.filterSaturation = s;
        this.filterGamma = g;
        this.filterHue = h;
        applyFilters();
    }

    public void applyFilters() {
        if (videoFiltersManager != null) {
            videoFiltersManager.setFilters(filterBrightness, filterContrast, filterSaturation, filterGamma, filterHue);
        }
    }

    public float getFilterBrightness() {
        return filterBrightness;
    }

    public float getFilterContrast() {
        return filterContrast;
    }

    public float getFilterSaturation() {
        return filterSaturation;
    }

    public float getFilterGamma() {
        return filterGamma;
    }

    public float getFilterHue() {
        return filterHue;
    }

    public VideoFiltersManager getVideoFiltersManager() {
        return videoFiltersManager;
    }
}
