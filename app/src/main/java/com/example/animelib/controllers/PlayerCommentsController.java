package com.example.animelib.controllers;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.api.ApiService;
import com.example.animelib.managers.CommentsManager;
import com.example.animelib.models.EpisodesListResponse;

public class PlayerCommentsController {

    public interface CommentsCallback {
        void onHideControllerRequested();
        void onUpdateNavigationRequested();
        default void onCommentsInPlayerToggled(boolean disabled) {}
    }

    private final CommentsManager commentsManager;
    private CommentsCallback callback;

    public PlayerCommentsController(Context context, ApiService apiService) {
        this.commentsManager = new CommentsManager(context, apiService);
    }

    public void setCallback(CommentsCallback callback) {
        this.callback = callback;
        setupCallbacks();
    }

    private void setupCallbacks() {
        if (commentsManager == null) return;
        commentsManager.setVisibilityCallback(isVisible -> {
            if (callback != null) {
                if (isVisible) {
                    callback.onHideControllerRequested();
                }
                callback.onUpdateNavigationRequested();
            }
        });
        commentsManager.setSettingsCallback(new CommentsManager.CommentsSettingsCallback() {
            @Override
            public void onCommentsInPlayerToggled(boolean disabled) {
                if (callback != null) {
                    callback.onCommentsInPlayerToggled(disabled);
                }
            }

            @Override
            public void onRepliesLimitChanged(int limit) {
                // Handled in CommentsManager
            }
        });
    }

    public boolean isCommentsDisabledInPlayer() {
        return commentsManager != null && commentsManager.isCommentsDisabledInPlayer();
    }

    public CommentsManager getCommentsManager() {
        return commentsManager;
    }

    public void initializePanelViews(View commentsPanel, ImageButton closeCommentsButton,
                                     RecyclerView commentsRecyclerView, View commentsLoadingOverlay,
                                     ImageButton commentsButton, View commentsOptionsButton,
                                     View menuOverlay, TextView emptyCommentsText) {
        if (commentsManager != null) {
            commentsManager.initializeViews(commentsPanel, closeCommentsButton, commentsRecyclerView,
                    commentsLoadingOverlay, commentsButton, commentsOptionsButton, menuOverlay, emptyCommentsText);
        }
    }

    public void initializePortraitViews(RecyclerView portraitCommentsRecyclerView, View portraitCommentsLoadingOverlay,
                                        TextView tvPortraitEmptyComments, View btnPortraitSortComments,
                                        TextView tvPortraitSortComments, View btnPortraitCommentRules,
                                        boolean isOfflineMode) {
        if (commentsManager != null && portraitCommentsRecyclerView != null) {
            commentsManager.setPortraitViews(portraitCommentsRecyclerView, portraitCommentsLoadingOverlay,
                    tvPortraitEmptyComments, btnPortraitSortComments, tvPortraitSortComments, btnPortraitCommentRules);
            if (isOfflineMode) {
                commentsManager.setOfflineMode(true);
            }
        }
    }

    public void setCurrentEpisode(EpisodesListResponse.EpisodeItem episode) {
        if (commentsManager != null) {
            commentsManager.setCurrentEpisode(episode);
        }
    }

    public void resetCommentsOnEpisodeChange(boolean clearAdapter) {
        if (commentsManager != null) {
            commentsManager.resetCommentsOnEpisodeChange(clearAdapter);
        }
    }

    public void setOfflineMode(boolean offlineMode) {
        if (commentsManager != null) {
            commentsManager.setOfflineMode(offlineMode);
        }
    }

    public boolean isCommentsVisible() {
        return commentsManager != null && commentsManager.isCommentsVisible();
    }

    public void showCommentsPanel() {
        if (commentsManager != null) {
            commentsManager.showCommentsPanel();
        }
    }

    public void hideCommentsPanel() {
        if (commentsManager != null) {
            commentsManager.hideCommentsPanel();
        }
    }

    public void forceHideCommentsPanel() {
        if (commentsManager != null) {
            commentsManager.forceHideCommentsPanel();
        }
    }

    public void updateCommentsButtonVisibility(boolean visible) {
        if (commentsManager != null) {
            commentsManager.updateCommentsButtonVisibility(visible);
        }
    }

    public void updateDragState(boolean shouldOpen) {
        if (commentsManager != null) {
            commentsManager.updateDragState(shouldOpen);
        }
    }

    public void loadNextCommentsPageIfAvailable() {
        if (commentsManager != null) {
            commentsManager.loadNextCommentsPageIfAvailable();
        }
    }

    public void loadCommentsForPortraitIfNeeded() {
        if (commentsManager != null) {
            commentsManager.loadCommentsForPortraitIfNeeded();
        }
    }

    public void notifyPlayerReady() {
        if (commentsManager != null) {
            commentsManager.notifyPlayerReady();
        }
    }

    public void onPortraitScrollChanged(androidx.core.widget.NestedScrollView scrollView, int scrollY, int oldScrollY) {
        if (commentsManager != null) {
            commentsManager.onPortraitScrollChanged(scrollView, scrollY, oldScrollY);
        }
    }

    public void attachWindowRoot(View decorView) {
        if (commentsManager != null) {
            commentsManager.attachWindowRoot(decorView);
        }
    }

    public void onWindowInsetsChanged(int imeBottom, int navBarBottom) {
        if (commentsManager != null) {
            commentsManager.onWindowInsetsChanged(imeBottom, navBarBottom);
        }
    }

    public boolean isImeVisible() {
        return commentsManager != null && commentsManager.isImeVisible();
    }

    public void cleanup() {
        if (commentsManager != null) {
            commentsManager.cleanup();
        }
    }
}
