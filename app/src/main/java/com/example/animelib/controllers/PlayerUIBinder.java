package com.example.animelib.controllers;

import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.ui.AmbientVignetteOverlayView;
import com.example.animelib.ui.DraggableSidePanel;
import com.google.android.material.button.MaterialButton;

/**
 * Единый UI Binder для корневых и контроллерных элементов плеера (`VideoPlayerActivity`).
 */
public class PlayerUIBinder {

    // Root & Player views
    public PlayerView playerView;
    public FrameLayout playerContainer;
    public View mainVideoLayout;
    public View portraitBottomContainer;
    public View loadingOverlay;
    public View controllerView;
    public DefaultTimeBar timeBar;

    // Panels & Overlays
    public DraggableSidePanel menuPanelContainer;
    public DraggableSidePanel commentsPanelContainer;
    public LinearLayout slidingMenuPanel;
    public View menuOverlay;
    public View menuLoadingIndicator;
    public View menuLoadingOverlay;
    public ImageButton closeMenuButton;
    public View commentsPanel;
    public ImageButton closeCommentsButton;
    public RecyclerView commentsRecyclerView;
    public View commentsLoadingOverlay;
    public View commentsOptionsButton;
    public TextView emptyCommentsText;

    // Related titles overlays
    public FrameLayout relatedTitlesOverlay;
    public View relatedTitlesDimOverlay;
    public RecyclerView relatedTitlesRecyclerView;

    // Gestures & Buffering plaques
    public View seekPreviewText;
    public View holdSpeedToast;
    public View skipIndicatorLeft;
    public View skipIndicatorRight;
    public View playerBufferingIndicator;

    // Ambient light views
    public View ambientContainer;
    public PlayerView ambientPlayerView;
    public AmbientVignetteOverlayView ambientVignetteOverlay;

    // Controller bar & Landscape episodes views
    public ImageButton ibClosePlayer;
    public ImageButton settingsButton;
    public TextView settingsQualityTag;
    public ImageButton menuToggleFullscreen;
    public ImageButton skipForwardButton;
    public ImageButton pipButton;
    public ImageButton btnLockScreen;
    public TextView animeTitleView;
    public TextView currentTeamName;
    public TextView currentEpisodeName;
    public TextView currentEpisodeNumberView;

    public ImageButton episodesMenuButton;
    public ImageButton prevEpisodeButton;
    public ImageButton nextEpisodeButton;
    public View landscapeEpisodesContainer;
    public View btnLandscapeEpisodeSearch;
    public EditText etLandscapeEpisodeNumber;
    public View landscapeEpisodeNumpadRoot;

    public ImageButton menuToggleButton;
    public View playersControlBar;

    public RecyclerView episodesHorizontalRecyclerView;
    public ImageButton commentsButton;
    public ImageButton bookmarkButton;
    public ImageButton btnDownloadFromMenu;
    public TextView downloadProgressText;
    public MaterialButton skipSegmentButton;

    public ImageButton btnPlayerPlayPause;
    public ImageButton btnPlayerPlay;
    public ImageButton btnPlayerPause;
    public View playLoadingIndicator;

    public void bindRootViews(@Nullable View rootView) {
        if (rootView == null) return;

        mainVideoLayout = rootView.findViewById(R.id.mainVideoLayout);
        portraitBottomContainer = rootView.findViewById(R.id.portraitBottomContainer);
        playerView = rootView.findViewById(R.id.playerView);
        playerContainer = rootView.findViewById(R.id.playerContainer);
        loadingOverlay = rootView.findViewById(R.id.loadingOverlay);

        controllerView = playerView != null ? playerView.findViewById(R.id.exo_controller) : null;
        if (controllerView != null) {
            timeBar = controllerView.findViewById(R.id.exo_progress);
            bindControllerViews(controllerView);
        }

        menuPanelContainer = rootView.findViewById(R.id.menuPanelContainer);
        commentsPanelContainer = rootView.findViewById(R.id.commentsPanelContainer);
        slidingMenuPanel = rootView.findViewById(R.id.slidingMenuPanel);
        menuOverlay = rootView.findViewById(R.id.menuOverlay);
        menuLoadingIndicator = rootView.findViewById(R.id.menuLoadingIndicator);
        menuLoadingOverlay = rootView.findViewById(R.id.menuLoadingOverlay);
        closeMenuButton = rootView.findViewById(R.id.closeMenuButton);

        commentsPanel = rootView.findViewById(R.id.commentsPanel);
        closeCommentsButton = rootView.findViewById(R.id.closeCommentsButton);
        commentsRecyclerView = rootView.findViewById(R.id.commentsRecyclerView);
        commentsLoadingOverlay = rootView.findViewById(R.id.commentsLoadingOverlay);
        commentsOptionsButton = rootView.findViewById(R.id.btnSortComments);
        emptyCommentsText = rootView.findViewById(R.id.emptyCommentsText);

        relatedTitlesOverlay = rootView.findViewById(R.id.relatedTitlesOverlay);
        relatedTitlesDimOverlay = rootView.findViewById(R.id.relatedTitlesDimOverlay);
        relatedTitlesRecyclerView = rootView.findViewById(R.id.relatedTitlesRecyclerView);

        seekPreviewText = rootView.findViewById(R.id.seekPreviewPlaque);
        holdSpeedToast = rootView.findViewById(R.id.holdSpeedPlaque);
        skipIndicatorLeft = rootView.findViewById(R.id.skipIndicatorLeft);
        skipIndicatorRight = rootView.findViewById(R.id.skipIndicatorRight);
        playerBufferingIndicator = rootView.findViewById(R.id.playerBufferingIndicator);

        ambientContainer = rootView.findViewById(R.id.ambientContainer);
        ambientPlayerView = rootView.findViewById(R.id.ambientPlayerView);
        ambientVignetteOverlay = rootView.findViewById(R.id.ambientVignetteOverlay);

        if (pipButton == null) {
            pipButton = rootView.findViewById(R.id.pipButton);
        }
        if (btnDownloadFromMenu == null) {
            btnDownloadFromMenu = rootView.findViewById(R.id.btnDownloadFromMenu);
        }
        if (downloadProgressText == null) {
            downloadProgressText = rootView.findViewById(R.id.downloadProgressText);
        }
        if (landscapeEpisodesContainer == null) {
            landscapeEpisodesContainer = rootView.findViewById(R.id.landscapeEpisodesContainer);
        }
        if (btnLandscapeEpisodeSearch == null) {
            btnLandscapeEpisodeSearch = rootView.findViewById(R.id.btnLandscapeEpisodeSearch);
        }
        if (etLandscapeEpisodeNumber == null) {
            etLandscapeEpisodeNumber = rootView.findViewById(R.id.etLandscapeEpisodeNumber);
        }
        if (landscapeEpisodeNumpadRoot == null) {
            landscapeEpisodeNumpadRoot = rootView.findViewById(R.id.landscapeEpisodeNumpadRoot);
        }
        if (skipSegmentButton == null) {
            skipSegmentButton = rootView.findViewById(R.id.skipSegmentButton);
        }
    }

    public void bindControllerViews(@Nullable View controllerView) {
        if (controllerView == null) return;
        this.controllerView = controllerView;

        ibClosePlayer = controllerView.findViewById(R.id.ibClosePlayer);
        settingsButton = controllerView.findViewById(R.id.settingsButton);
        settingsQualityTag = controllerView.findViewById(R.id.settingsQualityTag);
        menuToggleFullscreen = controllerView.findViewById(R.id.menuToggleFullscreen);
        skipForwardButton = controllerView.findViewById(R.id.skipForwardButton);
        pipButton = controllerView.findViewById(R.id.pipButton);
        btnLockScreen = controllerView.findViewById(R.id.btnLockScreen);

        animeTitleView = controllerView.findViewById(R.id.animeTitle);
        currentTeamName = controllerView.findViewById(R.id.currentTeamName);
        currentEpisodeName = controllerView.findViewById(R.id.currentEpisodeName);
        currentEpisodeNumberView = controllerView.findViewById(R.id.currentEpisodeNumber);

        episodesMenuButton = controllerView.findViewById(R.id.episodesMenuButton);
        prevEpisodeButton = controllerView.findViewById(R.id.prevEpisodeButton);
        nextEpisodeButton = controllerView.findViewById(R.id.nextEpisodeButton);
        landscapeEpisodesContainer = controllerView.findViewById(R.id.landscapeEpisodesContainer);
        btnLandscapeEpisodeSearch = controllerView.findViewById(R.id.btnLandscapeEpisodeSearch);
        etLandscapeEpisodeNumber = controllerView.findViewById(R.id.etLandscapeEpisodeNumber);
        landscapeEpisodeNumpadRoot = controllerView.findViewById(R.id.landscapeEpisodeNumpadRoot);

        menuToggleButton = controllerView.findViewById(R.id.menuToggleButton);
        playersControlBar = controllerView.findViewById(R.id.playersControlBar);

        episodesHorizontalRecyclerView = controllerView.findViewById(R.id.episodesHorizontalRecyclerView);
        commentsButton = controllerView.findViewById(R.id.commentsButton);
        bookmarkButton = controllerView.findViewById(R.id.bookmarkButton);
        btnDownloadFromMenu = controllerView.findViewById(R.id.btnDownloadFromMenu);
        downloadProgressText = controllerView.findViewById(R.id.downloadProgressText);
        skipSegmentButton = controllerView.findViewById(R.id.skipSegmentButton);

        btnPlayerPlayPause = controllerView.findViewById(R.id.btnPlayerPlayPause);
        btnPlayerPlay = controllerView.findViewById(R.id.btnPlayerPlay);
        btnPlayerPause = controllerView.findViewById(R.id.btnPlayerPause);
        playLoadingIndicator = controllerView.findViewById(R.id.playLoadingIndicator);
    }
}
