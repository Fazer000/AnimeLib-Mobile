package com.example.animelib.controllers;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.ui.PlayerView;
import com.example.animelib.managers.CommentsManager;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.settings.SettingsBottomSheet;
import com.example.animelib.ui.DraggableSidePanel;

public class PlayerControlsOverlayManager {

    private static final String TAG = "PlayerControlsOverlayManager";

    public void updateControllerAutoHide(PlayerView playerView, boolean enableAutoHide, int controllerShowTimeoutMs) {
        if (playerView != null) {
            if (enableAutoHide) {
                playerView.setControllerShowTimeoutMs(controllerShowTimeoutMs);
                playerView.setControllerAutoShow(true);
                playerView.setControllerHideOnTouch(true);
                Log.d(TAG, "Controller auto-hide enabled");
            } else {
                playerView.setControllerShowTimeoutMs(0);
                playerView.setControllerAutoShow(false);
                playerView.setControllerHideOnTouch(false);
                Log.d(TAG, "Controller auto-hide disabled");
            }
        }
    }

    public void hideAllUI(PlayerView playerView,
                         DraggableSidePanel menuPanelContainer,
                         CommentsManager commentsManager,
                         EpisodesManager episodesManager,
                         PlayersManager playersManager,
                         GesturesManager gesturesManager,
                         ImageButton pipButton,
                         SettingsBottomSheet settingsBottomSheet) {
        if (playerView != null) {
            playerView.setUseController(false);
        }

        if (menuPanelContainer != null) {
            menuPanelContainer.closePanel();
        }

        if (commentsManager != null && commentsManager.isCommentsVisible()) {
            commentsManager.hideCommentsPanel();
        }

        if (episodesManager != null) {
            episodesManager.hideEpisodesUIForPiP();
        }

        if (playersManager != null) {
            playersManager.hideAllPlayersUI();
        }

        if (gesturesManager != null) {
            gesturesManager.hideAllGesturesUI();
        }

        if (pipButton != null) {
            pipButton.setVisibility(View.GONE);
        }

        if (settingsBottomSheet != null && settingsBottomSheet.isShowing()) {
            settingsBottomSheet.dismiss();
        }
    }

    public void showAllUI(PlayerView playerView,
                          ImageButton pipButton,
                          EpisodesManager episodesManager,
                          PlayersManager playersManager,
                          GesturesManager gesturesManager,
                          CommentsManager commentsManager,
                          boolean wasCommentsVisibleBeforePiP) {
        if (playerView != null) {
            playerView.setUseController(true);
        }

        if (pipButton != null) {
            pipButton.setVisibility(View.VISIBLE);
        }

        if (episodesManager != null) {
            episodesManager.showAllEpisodesUI();
        }

        if (playersManager != null) {
            playersManager.showAllPlayersUI();
        }

        if (gesturesManager != null) {
            gesturesManager.showAllGesturesUI();
        }

        if (wasCommentsVisibleBeforePiP && commentsManager != null && !commentsManager.isCommentsVisible()) {
            commentsManager.showCommentsPanel();
        }
    }

    public void setupFullscreen(Window window, Context context) {
        if (window != null && context != null) {
            boolean isPortrait = context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;

            WindowCompat.setDecorFitsSystemWindows(window, false);

            WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(window, window.getDecorView());
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);

            if (isPortrait) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Color.TRANSPARENT);
                window.setNavigationBarColor(Color.TRANSPARENT);
                controller.show(WindowInsetsCompat.Type.statusBars());
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Color.TRANSPARENT);
                window.setNavigationBarColor(Color.TRANSPARENT);
                controller.hide(WindowInsetsCompat.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }
}
