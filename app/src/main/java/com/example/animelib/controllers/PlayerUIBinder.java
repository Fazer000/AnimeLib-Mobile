package com.example.animelib.controllers;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;

/**
 * Класс-связка (UI Binder) для элементов контроллера плеера (`controllerView`).
 */
public class PlayerUIBinder {

    public ImageButton ibClosePlayer;
    public ImageButton settingsButton;
    public TextView settingsQualityTag;
    public ImageButton menuToggleFullscreen;
    public ImageButton pipButton;
    public TextView animeTitleView;
    public TextView currentTeamName;
    public TextView currentEpisodeName;
    public TextView currentEpisodeNumberView;

    public ImageButton episodesMenuButton;
    public ImageButton prevEpisodeButton;
    public ImageButton nextEpisodeButton;

    public ImageButton menuToggleButton;
    public View playersControlBar;

    public RecyclerView episodesHorizontalRecyclerView;
    public ImageButton commentsButton;
    public ImageButton bookmarkButton;

    public ImageButton btnPlayerPlay;
    public ImageButton btnPlayerPause;
    public View playLoadingIndicator;

    public void bindControllerViews(@Nullable View controllerView) {
        if (controllerView == null) return;

        ibClosePlayer = controllerView.findViewById(R.id.ibClosePlayer);
        settingsButton = controllerView.findViewById(R.id.settingsButton);
        settingsQualityTag = controllerView.findViewById(R.id.settingsQualityTag);
        menuToggleFullscreen = controllerView.findViewById(R.id.menuToggleFullscreen);
        pipButton = controllerView.findViewById(R.id.pipButton);

        animeTitleView = controllerView.findViewById(R.id.animeTitle);
        currentTeamName = controllerView.findViewById(R.id.currentTeamName);
        currentEpisodeName = controllerView.findViewById(R.id.currentEpisodeName);
        currentEpisodeNumberView = controllerView.findViewById(R.id.currentEpisodeNumber);

        episodesMenuButton = controllerView.findViewById(R.id.episodesMenuButton);
        prevEpisodeButton = controllerView.findViewById(R.id.prevEpisodeButton);
        nextEpisodeButton = controllerView.findViewById(R.id.nextEpisodeButton);

        menuToggleButton = controllerView.findViewById(R.id.menuToggleButton);
        playersControlBar = controllerView.findViewById(R.id.playersControlBar);

        episodesHorizontalRecyclerView = controllerView.findViewById(R.id.episodesHorizontalRecyclerView);
        commentsButton = controllerView.findViewById(R.id.commentsButton);
        bookmarkButton = controllerView.findViewById(R.id.bookmarkButton);

        btnPlayerPlay = controllerView.findViewById(R.id.btnPlayerPlay);
        btnPlayerPause = controllerView.findViewById(R.id.btnPlayerPause);
        playLoadingIndicator = controllerView.findViewById(R.id.playLoadingIndicator);
    }
}
