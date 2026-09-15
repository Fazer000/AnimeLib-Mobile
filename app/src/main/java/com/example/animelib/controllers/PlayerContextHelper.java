package com.example.animelib.controllers;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.api.ApiService;
import com.example.animelib.models.AnimeInfoResponse;

/**
 * Хелпер для получения единообразных данных о тайтле (ID, Название, Постер) с fallback-проверками.
 */
public class PlayerContextHelper {

    @NonNull
    public static String getAnimeTitle(@Nullable String currentTitle, @Nullable AnimeInfoResponse animeInfo, @Nullable Intent intent) {
        if (currentTitle != null && !currentTitle.isEmpty()) {
            return currentTitle;
        }
        if (animeInfo != null && animeInfo.getData() != null) {
            String rusName = animeInfo.getData().getRus_name();
            if (rusName != null && !rusName.isEmpty()) return rusName;
            String name = animeInfo.getData().getName();
            if (name != null && !name.isEmpty()) return name;
        }
        if (intent != null) {
            String titleExtra = intent.getStringExtra("EXTRA_ANIME_TITLE");
            if (titleExtra != null && !titleExtra.isEmpty()) return titleExtra;
            String titleAlt = intent.getStringExtra("anime_title");
            if (titleAlt != null && !titleAlt.isEmpty()) return titleAlt;
        }
        return "Аниме";
    }

    @Nullable
    public static String getAnimeId(@Nullable String currentId, @Nullable Intent intent, @Nullable ApiService apiService) {
        if (currentId != null && !currentId.isEmpty()) {
            return currentId;
        }
        if (intent != null) {
            String idExtra = intent.getStringExtra("EXTRA_ANIME_ID");
            if (idExtra != null && !idExtra.isEmpty()) return idExtra;
            String urlExtra = intent.getStringExtra("anime_url");
            if (urlExtra != null && !urlExtra.isEmpty() && apiService != null) {
                return apiService.extractAnimeId(urlExtra);
            }
        }
        return null;
    }

    @Nullable
    public static String getPosterUrl(@Nullable String currentPosterUrl, @Nullable AnimeInfoResponse animeInfo, @Nullable Intent intent) {
        if (currentPosterUrl != null && !currentPosterUrl.isEmpty()) {
            return currentPosterUrl;
        }
        if (animeInfo != null && animeInfo.getData() != null && animeInfo.getData().getCover() != null) {
            String defaultCover = animeInfo.getData().getCover().getDefaultUrl();
            if (defaultCover != null && !defaultCover.isEmpty()) return defaultCover;
        }
        if (intent != null) {
            String posterExtra = intent.getStringExtra("EXTRA_POSTER_URL");
            if (posterExtra != null && !posterExtra.isEmpty()) return posterExtra;
        }
        return null;
    }
}
