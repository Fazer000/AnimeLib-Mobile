package com.example.animelib.controllers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import android.content.Context;
import com.example.animelib.R;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.managers.RelatedTitlesManager;
import com.example.animelib.managers.VerticalGesturesManager;
import com.example.animelib.ui.DraggableSidePanel;
import com.example.animelib.ui.M3LinearProgressIndicator;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.GesturePlaqueAnimationHelper;

/**
 * Координатор горизонтальных и вертикальных жестов плеера,
 * edge-drag панелей и плашек индикаторов громкости/яркости.
 */
public class PlayerGesturesCoordinator {

    private static final String TAG = "PlayerGesturesCoord";

    public interface GesturesHost {
        @Nullable Context getContext();
        @Nullable ExoPlayer getPlayer();
        @Nullable PlayerView getPlayerView();
        @Nullable EpisodesManager getEpisodesManager();
        @Nullable PlayersManager getPlayersManager();
        @Nullable PlayerCommentsController getCommentsController();
        @Nullable PlayerRelatedTitlesController getRelatedTitlesController();
        @Nullable PlayerControlsOverlayManager getControlsOverlayManager();
        @Nullable PlayerUIBinder getUiBinder();
        boolean isOfflineMode();
        boolean isPlayingDownloadedVideo();
        boolean isCommentsDisabledInPlayer();
    }

    private final GesturesHost host;
    private final Handler gestureIndicatorHandler = new Handler(Looper.getMainLooper());
    private Runnable hideGestureIndicatorsRunnable;

    private View gestureIndicatorPlaque;
    private ImageView gestureIndicatorIcon;
    private M3LinearProgressIndicator gestureIndicatorProgress;
    private TextView gestureIndicatorText;

    public PlayerGesturesCoordinator(GesturesHost host) {
        this.host = host;
    }

    public void bindIndicatorViews(@Nullable View rootView) {
        if (rootView == null) return;
        gestureIndicatorPlaque = rootView.findViewById(R.id.gestureIndicatorPlaque);
        gestureIndicatorIcon = rootView.findViewById(R.id.gestureIndicatorIcon);
        gestureIndicatorProgress = rootView.findViewById(R.id.gestureIndicatorProgress);
        gestureIndicatorText = rootView.findViewById(R.id.gestureIndicatorText);
        if (gestureIndicatorText != null) {
            int maxWidth = (int) Math.ceil(gestureIndicatorText.getPaint().measureText("100%"));
            gestureIndicatorText.setWidth(maxWidth);
        }
    }

    public void setupCallbacks(@Nullable GesturesManager gesturesManager,
                               @Nullable VerticalGesturesManager verticalGesturesManager) {
        if (gesturesManager != null) {
            gesturesManager.setGestureCallback(new GesturesManager.GestureCallback() {
                @Override
                public void onSeekGesture(long seekPosition) {
                    PlayerControlsOverlayManager overlay = host.getControlsOverlayManager();
                    if (overlay != null) {
                        overlay.startSeekingState();
                        overlay.scheduleEndSeekingState(600);
                    }
                }

                @Override
                public void onSpeedChange(float speed) {
                    Log.d(TAG, "Speed change: " + speed);
                }

                @Override
                public void updatePlayLoadingIndicator(int playbackState) {
                    PlayerControlsOverlayManager overlay = host.getControlsOverlayManager();
                    if (overlay != null) {
                        overlay.updatePlayPauseAndLoadingState(true);
                    }
                }

                @Override
                public void onEpisodesSwipeUp() {
                    EpisodesManager em = host.getEpisodesManager();
                    if (em != null) {
                        em.showEpisodesMenu();
                    }
                }

                @Override
                public void onEpisodesSwipeDown() {
                    EpisodesManager em = host.getEpisodesManager();
                    if (em != null) {
                        em.hideEpisodesMenu();
                    }
                }

                @Override
                public void onCommentsSwipeFromRight() {
                    if (host.isCommentsDisabledInPlayer()) return;
                    PlayerCommentsController cc = host.getCommentsController();
                    if (cc != null) {
                        cc.showCommentsPanel();
                    }
                }

                @Override
                public void onPlayersSwipeFromRight() {
                    if (host.isOfflineMode()) {
                        Context ctx = host.getContext();
                        if (ctx != null) {
                            CustomToast.showInfo(ctx, "Выбор озвучки недоступен в офлайн режиме");
                        }
                        return;
                    }
                    PlayersManager pm = host.getPlayersManager();
                    if (pm != null) {
                        pm.showMenu();
                    }
                }

                @Override
                public void onEpisodesDragProgress(float progress) {
                    // Вертикальные жесты эпизодов обрабатываются через VerticalGesturesManager
                }

                @Override
                public void onRelatedTitlesDragProgress(float progress) {
                    // Вертикальные жесты связанных тайтлов обрабатываются через VerticalGesturesManager
                }

                @Override
                public boolean canStartEdgePanelDrag(GesturesManager.EdgeSwipeType type) {
                    if (type == GesturesManager.EdgeSwipeType.COMMENTS_RIGHT) {
                        return !host.isOfflineMode() && !host.isCommentsDisabledInPlayer();
                    } else if (type == GesturesManager.EdgeSwipeType.PLAYERS_RIGHT) {
                        return !host.isOfflineMode();
                    }
                    return true;
                }

                @Override
                public void onPanelDragStart(GesturesManager.EdgeSwipeType type) {
                    PlayerView pv = host.getPlayerView();
                    if (pv != null) {
                        pv.hideController();
                    }
                    PlayerUIBinder ui = host.getUiBinder();
                    if (ui == null) return;
                    if (type == GesturesManager.EdgeSwipeType.COMMENTS_RIGHT && ui.commentsPanelContainer != null) {
                        ui.commentsPanelContainer.beginDragFromRight();
                    } else if (type == GesturesManager.EdgeSwipeType.PLAYERS_RIGHT && ui.menuPanelContainer != null) {
                        ui.menuPanelContainer.beginDragFromRight();
                    }
                }

                @Override
                public void onCommentsDragPx(float draggedLeftPx) {
                    if (host.isOfflineMode() || host.isCommentsDisabledInPlayer()) return;
                    PlayerUIBinder ui = host.getUiBinder();
                    DraggableSidePanel commentsContainer = ui != null ? ui.commentsPanelContainer : null;
                    if (commentsContainer != null) {
                        commentsContainer.updateDragByLeftPx(draggedLeftPx);
                    }
                }

                @Override
                public void onPlayersDragPx(float draggedLeftPx) {
                    if (host.isOfflineMode()) return;
                    PlayerUIBinder ui = host.getUiBinder();
                    DraggableSidePanel menuContainer = ui != null ? ui.menuPanelContainer : null;
                    if (menuContainer != null) {
                        menuContainer.updateDragByLeftPx(draggedLeftPx);
                    }
                }

                @Override
                public void onPanelDragRelease(GesturesManager.EdgeSwipeType type, float draggedLeftPx, float xVelocityPxPerSec) {
                    PlayerUIBinder ui = host.getUiBinder();
                    if (ui == null) return;
                    switch (type) {
                        case COMMENTS_RIGHT:
                            if (host.isOfflineMode() || host.isCommentsDisabledInPlayer()) return;
                            if (ui.commentsPanelContainer != null) {
                                ui.commentsPanelContainer.completeDragWithVelocity(xVelocityPxPerSec);
                            }
                            break;
                        case PLAYERS_RIGHT:
                            if (host.isOfflineMode()) return;
                            if (ui.menuPanelContainer != null) {
                                ui.menuPanelContainer.completeDragWithVelocity(xVelocityPxPerSec);
                            }
                            break;
                    }
                }

                @Override
                public void onBlockedEdgeSwipe(GesturesManager.EdgeSwipeType type) {
                    if (type == GesturesManager.EdgeSwipeType.PLAYERS_RIGHT && host.isOfflineMode() && host.getContext() != null) {
                        CustomToast.showInfo(host.getContext(), "Выбор озвучки недоступен в офлайн режиме");
                    }
                }

                @Override
                public void onCommentsDragProgress(float progress) {
                    // Обрабатывается через 1:1 пиксельный onCommentsDragPx
                }

                @Override
                public void onPlayersDragProgress(float progress) {
                    // Обрабатывается через 1:1 пиксельный onPlayersDragPx
                }

                @Override
                public void onPanelDragComplete(GesturesManager.EdgeSwipeType type, boolean shouldOpen) {
                    // Завершение и анимация выполняются в onPanelDragRelease;
                    // контроллеры/менеджеры обновляются по завершении анимации в OnPanelStateChangeListener.
                }

                @Override
                public boolean isEpisodesMenuVisible() {
                    EpisodesManager em = host.getEpisodesManager();
                    return em != null && em.isEpisodesMenuVisible();
                }

                @Override
                public boolean isRelatedTitlesMenuVisible() {
                    PlayerRelatedTitlesController rtc = host.getRelatedTitlesController();
                    RelatedTitlesManager rtm = rtc != null ? rtc.getRelatedTitlesManager() : null;
                    return rtm != null && rtm.isRelatedTitlesVisible();
                }

                @Override
                public void onDoubleTapSkip(boolean isForward, int skipDurationSeconds) {
                    ExoPlayer player = host.getPlayer();
                    if (player == null) {
                        Log.w(TAG, "Player is null, cannot perform skip");
                        return;
                    }

                    long currentPosition = player.getCurrentPosition();
                    long skipDuration = skipDurationSeconds * 1000L;
                    long newPosition = isForward ? currentPosition + skipDuration : currentPosition - skipDuration;
                    long duration = player.getDuration();

                    newPosition = Math.max(0, newPosition);
                    if (duration > 0) {
                        newPosition = Math.min(newPosition, duration);
                    }

                    PlayerControlsOverlayManager overlay = host.getControlsOverlayManager();
                    if (overlay != null) {
                        overlay.startSeekingState();
                    }
                    player.seekTo(newPosition);
                    if (overlay != null) {
                        overlay.scheduleEndSeekingState(600);
                    }
                }
            });
        }

        if (verticalGesturesManager != null) {
            verticalGesturesManager.setCallback(new VerticalGesturesManager.VerticalGestureCallback() {
                @Override
                public void onEpisodesDragProgress(float progress) {
                    PlayerView pv = host.getPlayerView();
                    if (progress > 0 && pv != null && !pv.isControllerFullyVisible()) {
                        pv.showController();
                    }
                    EpisodesManager em = host.getEpisodesManager();
                    if (em != null) {
                        em.setDragProgress(progress);
                    }
                }

                @Override
                public void onRelatedInfoDragProgress(float progress) {
                    // Отключено
                }

                @Override
                public void onEpisodesDragComplete(boolean shouldOpen) {
                    EpisodesManager em = host.getEpisodesManager();
                    if (em != null) {
                        em.completeDrag(shouldOpen);
                    }
                }

                @Override
                public void onRelatedInfoDragComplete(boolean shouldOpen) {
                    // Отключено
                }

                @Override
                public boolean isEpisodesOpen() {
                    EpisodesManager em = host.getEpisodesManager();
                    return em != null && em.isEpisodesMenuVisible();
                }

                @Override
                public boolean isRelatedInfoOpen() {
                    return false;
                }

                @Override
                public void onBrightnessChanged(float brightness) {
                    int percent = Math.round(brightness * 100f);
                    showGestureIndicator(R.drawable.ic_brightness, percent);
                }

                @Override
                public void onVolumeChanged(int currentVolume, int maxVolume) {
                    int percent = maxVolume > 0 ? Math.round((float) currentVolume / maxVolume * 100f) : 0;
                    showGestureIndicator(R.drawable.ic_volume, percent);
                }

                @Override
                public void onGestureCompleted() {
                    scheduleHideGestureIndicators();
                }
            });
        }
    }

    private void showGestureIndicator(int iconResId, int percent) {
        if (hideGestureIndicatorsRunnable != null) {
            gestureIndicatorHandler.removeCallbacks(hideGestureIndicatorsRunnable);
        }

        if (gestureIndicatorIcon != null) {
            gestureIndicatorIcon.setImageResource(iconResId);
        }
        if (gestureIndicatorProgress != null) {
            gestureIndicatorProgress.setProgress(percent, false);
        }
        if (gestureIndicatorText != null) {
            gestureIndicatorText.setText(percent + "%");
        }

        GesturePlaqueAnimationHelper.show(gestureIndicatorPlaque);
    }

    private void scheduleHideGestureIndicators() {
        if (hideGestureIndicatorsRunnable == null) {
            hideGestureIndicatorsRunnable = this::hideGestureIndicatorWithAnimation;
        }
        gestureIndicatorHandler.removeCallbacks(hideGestureIndicatorsRunnable);
        gestureIndicatorHandler.postDelayed(hideGestureIndicatorsRunnable, 800);
    }

    private void hideGestureIndicatorWithAnimation() {
        GesturePlaqueAnimationHelper.hide(gestureIndicatorPlaque);
    }

    public void cleanup() {
        gestureIndicatorHandler.removeCallbacksAndMessages(null);
        hideGestureIndicatorsRunnable = null;
    }
}
