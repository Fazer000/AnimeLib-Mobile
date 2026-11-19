package com.example.animelib.controllers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;

import com.example.animelib.R;
import com.example.animelib.managers.CommentsManager;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.services.DownloadService;
import com.example.animelib.settings.SettingsBottomSheet;
import com.example.animelib.ui.DraggableSidePanel;
import com.example.animelib.ui.PlayPauseDrawable;
import com.example.animelib.util.ItemAnimationUtils;
import com.google.android.material.progressindicator.CircularProgressIndicator;

/**
 * Контроллер управления видимостью оверлея плеера, кнопками воспроизведения/паузы,
 * состоянием перемотки таймбара, морфингом иконки Play/Pause и авто-скрытием контроллера.
 */
public class PlayerControlsOverlayManager {

    private static final String TAG = "PlayerControlsOverlayManager";

    public interface ControlsCallback {
        Player getPlayer();
        PlayerView getPlayerView();
        EpisodesManager getEpisodesManager();
        PlayersManager getPlayersManager();
        TimecodeManager getTimecodeManager();
        PlayerCommentsController getCommentsController();
        PlayerNextEpisodeController getNextEpisodeController();
        boolean isOfflineMode();
        boolean isPlayingDownloadedVideo();
        boolean isCommentsDisabledInPlayer();
        int getLongSkipDuration();
        boolean isAutoPlayEnabled();
        boolean isAutoPlayOnPrepare();
        void startBufferingMonitoring();
        void startViewProgressTracking();
        void stopViewProgressTracking();
        void updateSettingsQualityTag();
        void ensureAnimeTitleSet();
        void openSettingsDialog();
        void toggleOrientation();
        void enterPipMode();
        void closePlayer();
        void toggleEpisodesMenu();
        void togglePlayersMenu();
        boolean isCurrentTitleInDownloadQueue();
        void showDownloadBottomSheet();
        void showDownloadProgressBottomSheet();
        void addBookmark();
        void onPipParamsUpdateNeeded();
        void safeRunOnUiThread(Runnable runnable);
    }

    private Context context;
    private PlayerUIBinder ui;
    private ControlsCallback callback;

    private volatile boolean isVideoLoading = false;
    private volatile boolean hasRenderedFirstFrame = false;
    private volatile boolean isSeeking = false;
    private volatile boolean isScrubbingTimeBar = false;
    private boolean isControllerVisible = false;
    private int currentControlState = -1; // -1 = uninitialized, 0 = play, 1 = pause, 2 = loading
    private PlayPauseDrawable playPauseDrawable;
    private final Handler seekResetHandler = new Handler(Looper.getMainLooper());

    public void bind(Context context, PlayerUIBinder ui, ControlsCallback callback) {
        this.context = context;
        this.ui = ui;
        this.callback = callback;
    }

    public boolean isVideoLoading() {
        return isVideoLoading;
    }

    public void setVideoLoading(boolean loading) {
        this.isVideoLoading = loading;
    }

    public boolean hasRenderedFirstFrame() {
        return hasRenderedFirstFrame;
    }

    public void setHasRenderedFirstFrame(boolean rendered) {
        this.hasRenderedFirstFrame = rendered;
    }

    public void setSeeking(boolean seeking) {
        this.isSeeking = seeking;
    }

    public boolean isControllerVisible() {
        return isControllerVisible;
    }

    public void configureInitialState() {
        updateControllerButtonsVisibility(false);
        if (ui != null) {
            if (ui.prevEpisodeButton != null) ui.prevEpisodeButton.setVisibility(View.VISIBLE);
            if (ui.nextEpisodeButton != null) ui.nextEpisodeButton.setVisibility(View.VISIBLE);
        }
    }

    public void setupOverlayAndControlListeners() {
        setupControllerVisibilityListener();
        setupButtonClickListeners();
        setupOverlayTouchListener();
        setupTimeBarListener();
    }

    private void setupTimeBarListener() {
        if (ui == null || ui.timeBar == null) return;
        ui.timeBar.addListener(new androidx.media3.ui.TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(@NonNull androidx.media3.ui.TimeBar timeBar, long position) {
                isScrubbingTimeBar = true;
                startSeekingState();
            }

            @Override
            public void onScrubMove(@NonNull androidx.media3.ui.TimeBar timeBar, long position) {
                isScrubbingTimeBar = true;
                startSeekingState();
            }

            @Override
            public void onScrubStop(@NonNull androidx.media3.ui.TimeBar timeBar, long position, boolean canceled) {
                isScrubbingTimeBar = false;
                if (!canceled) {
                    startSeekingState();
                    Player p = callback != null ? callback.getPlayer() : null;
                    if (p != null) {
                        p.seekTo(position);
                    }
                    scheduleEndSeekingState(600);
                } else {
                    scheduleEndSeekingState(0);
                }
            }
        });
    }

    private void setupControllerVisibilityListener() {
        PlayerView pv = callback != null ? callback.getPlayerView() : (ui != null ? ui.playerView : null);
        if (pv == null) return;
        pv.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            boolean shouldBeVisible = visibility == View.VISIBLE;
            animateControllerVisibility(shouldBeVisible);
            isControllerVisible = shouldBeVisible;

            EpisodesManager em = callback != null ? callback.getEpisodesManager() : null;
            if (em != null) {
                em.updateEpisodeNavigationButtonsVisibility();
            }
            updateControllerButtonsVisibility(isControllerVisible);
            updatePlayPauseAndLoadingState(false);
        });
    }

    private void animateControllerVisibility(boolean visible) {
        View controllerView = ui != null ? ui.controllerView : null;
        if (controllerView == null) return;

        controllerView.animate().cancel();
        if (ui.downloadProgressText != null) {
            ui.downloadProgressText.animate().cancel();
        }

        TimecodeManager timecodeManager = callback != null ? callback.getTimecodeManager() : null;

        if (visible) {
            controllerView.setVisibility(View.VISIBLE);
            controllerView.animate()
                    .alpha(1.0f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .withStartAction(() -> {
                        if (timecodeManager != null) {
                            timecodeManager.setControllerVisibility(true);
                        }
                    })
                    .start();

            if (ui.downloadProgressText != null) {
                ui.downloadProgressText.setVisibility(View.GONE);
            }
        } else {
            controllerView.animate()
                    .alpha(0.0f)
                    .setDuration(150)
                    .setInterpolator(new AccelerateInterpolator())
                    .withStartAction(() -> {
                        if (timecodeManager != null) {
                            timecodeManager.setControllerVisibility(false);
                        }
                    })
                    .withEndAction(() -> {
                        if (controllerView.getAlpha() == 0.0f) {
                            controllerView.setVisibility(View.GONE);
                        }
                        updatePlayPauseAndLoadingState(true);
                    })
                    .start();

            if (ui.downloadProgressText != null && ui.downloadProgressText.getVisibility() == View.VISIBLE) {
                ui.downloadProgressText.animate()
                        .alpha(0.0f)
                        .setDuration(150)
                        .withEndAction(() -> ui.downloadProgressText.setVisibility(View.GONE))
                        .start();
            }
        }
        updatePlayPauseAndLoadingState(true);
    }

    public void updateControllerButtonsVisibility(boolean visible) {
        if (ui == null || context == null) return;
        boolean isPortrait = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        int visibility = visible ? View.VISIBLE : View.GONE;

        if (ui.settingsButton != null) ui.settingsButton.setVisibility(visibility);
        if (callback != null) {
            callback.updateSettingsQualityTag();
        }

        if (ui.skipForwardButton != null) {
            ui.skipForwardButton.setVisibility(visibility);
        }

        if (ui.menuToggleFullscreen != null) {
            ui.menuToggleFullscreen.setVisibility(visibility);
            ui.menuToggleFullscreen.setImageResource(isPortrait ? R.drawable.ic_player_ctrl_fullscreen : R.drawable.ic_player_ctrl_fullscreen_exit);
        }

        boolean commentsDisabled = callback != null && callback.isCommentsDisabledInPlayer();
        PlayerCommentsController commentsCtrl = callback != null ? callback.getCommentsController() : null;

        if (!isPortrait) {
            if (ui.episodesMenuButton != null) ui.episodesMenuButton.setVisibility(visibility);
            if (ui.menuToggleButton != null) {
                ui.menuToggleButton.setVisibility(visibility);
                boolean isOffline = callback != null && callback.isOfflineMode();
                ui.menuToggleButton.setEnabled(true);
                ui.menuToggleButton.setClickable(true);
                ui.menuToggleButton.setAlpha(isOffline ? 0.3f : 1.0f);
            }
            if (ui.commentsButton != null) {
                ui.commentsButton.setVisibility(!commentsDisabled && visible ? View.VISIBLE : View.GONE);
            }
            if (commentsCtrl != null) {
                commentsCtrl.updateCommentsButtonVisibility(!commentsDisabled && visible);
            }
        } else {
            if (ui.episodesMenuButton != null) ui.episodesMenuButton.setVisibility(View.GONE);
            if (ui.menuToggleButton != null) ui.menuToggleButton.setVisibility(View.GONE);
            if (ui.commentsButton != null) ui.commentsButton.setVisibility(View.GONE);
            if (commentsCtrl != null) {
                commentsCtrl.updateCommentsButtonVisibility(false);
            }
        }
    }

    private void setupButtonClickListeners() {
        if (ui == null) return;

        if (ui.prevEpisodeButton != null) {
            ui.prevEpisodeButton.setOnClickListener(v -> ItemAnimationUtils.animateItemClick(v, () -> {
                EpisodesManager em = callback != null ? callback.getEpisodesManager() : null;
                if (ui.prevEpisodeButton.isEnabled() && em != null) {
                    em.navigateToPreviousEpisode();
                }
            }));
        }

        if (ui.nextEpisodeButton != null) {
            ui.nextEpisodeButton.setOnClickListener(v -> ItemAnimationUtils.animateItemClick(v, () -> {
                EpisodesManager em = callback != null ? callback.getEpisodesManager() : null;
                if (ui.nextEpisodeButton.isEnabled() && em != null) {
                    em.navigateToNextEpisode();
                }
            }));
        }

        if (ui.menuToggleButton != null) {
            ui.menuToggleButton.setOnClickListener(v -> {
                if (callback != null) callback.togglePlayersMenu();
            });
        }

        if (ui.closeMenuButton != null) {
            ui.closeMenuButton.setOnClickListener(v -> {
                PlayersManager pm = callback != null ? callback.getPlayersManager() : null;
                if (pm != null && pm.getCurrentPlayerData() != null) {
                    pm.hideMenu();
                } else if (callback != null) {
                    callback.closePlayer();
                }
            });
        }

        if (ui.settingsButton != null) {
            ui.settingsButton.setOnClickListener(v -> {
                if (callback != null) callback.openSettingsDialog();
            });
        }

        if (ui.menuToggleFullscreen != null) {
            ui.menuToggleFullscreen.setOnClickListener(v -> {
                if (callback != null) callback.toggleOrientation();
            });
        }

        if (ui.pipButton != null) {
            ui.pipButton.setOnClickListener(v -> {
                if (callback != null) callback.enterPipMode();
            });
        }

        if (ui.ibClosePlayer != null) {
            ui.ibClosePlayer.setOnClickListener(v -> {
                if (callback != null) callback.closePlayer();
            });
        }

        if (ui.episodesMenuButton != null) {
            ui.episodesMenuButton.setOnClickListener(v -> {
                if (callback != null) callback.toggleEpisodesMenu();
            });
        }

        if (ui.btnDownloadFromMenu != null) {
            ui.btnDownloadFromMenu.setOnClickListener(v -> {
                if (callback == null) return;
                if (callback.isCurrentTitleInDownloadQueue()) {
                    callback.showDownloadProgressBottomSheet();
                } else {
                    callback.showDownloadBottomSheet();
                }
            });
        }

        if (ui.bookmarkButton != null) {
            ui.bookmarkButton.setEnabled(false);
            ui.bookmarkButton.setClickable(false);
            ui.bookmarkButton.setOnClickListener(v -> {
                if (callback != null) callback.addBookmark();
            });
        }

        setupPlayerControlButtons();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupOverlayTouchListener() {
        if (ui == null || ui.menuOverlay == null) return;

        ui.menuOverlay.setVisibility(View.GONE);
        ui.menuOverlay.setAlpha(0f);
        ui.menuOverlay.setClickable(false);

        ui.menuOverlay.setOnTouchListener((v, event) -> {
            PlayersManager pm = callback != null ? callback.getPlayersManager() : null;
            PlayerCommentsController commentsCtrl = callback != null ? callback.getCommentsController() : null;
            boolean menuVisible = pm != null && pm.isMenuVisible();
            boolean commentsVisible = commentsCtrl != null && commentsCtrl.isCommentsVisible();
            if (!menuVisible && !commentsVisible) return false;
            if (event.getAction() != MotionEvent.ACTION_DOWN) return false;

            int x = (int) event.getRawX();
            int y = (int) event.getRawY();
            boolean insidePanel = false;

            if (ui.slidingMenuPanel != null && menuVisible) {
                Rect panelRect = new Rect();
                ui.slidingMenuPanel.getGlobalVisibleRect(panelRect);
                insidePanel = panelRect.contains(x, y);
            }

            if (!insidePanel) {
                if (menuVisible && pm != null) pm.hideMenu();
                if (commentsVisible && commentsCtrl != null) {
                    commentsCtrl.hideCommentsPanel();
                }
                return true;
            }
            return false;
        });
    }

    public void setupPlayerControlButtons() {
        PlayerView pv = callback != null ? callback.getPlayerView() : (ui != null ? ui.playerView : null);
        if (pv == null) return;
        View controllerView = pv.findViewById(R.id.exo_controller);
        if (controllerView == null) {
            Log.w(TAG, "Controller view not found");
            return;
        }

        ImageButton skipForwardButton = controllerView.findViewById(R.id.skipForwardButton);
        if (skipForwardButton != null) {
            skipForwardButton.setOnClickListener(v -> {
                Player p = callback != null ? callback.getPlayer() : null;
                if (p != null && p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                    long currentPosition = p.getCurrentPosition();
                    int skipSec = callback != null ? callback.getLongSkipDuration() : 85;
                    long skipDuration = skipSec * 1000L;
                    long newPosition = currentPosition + skipDuration;
                    long duration = p.getDuration();
                    if (newPosition > duration && duration > 0) {
                        newPosition = duration;
                    }
                    startSeekingState();
                    p.seekTo(newPosition);
                    scheduleEndSeekingState(600);
                }
            });
        }

        updatePlayerControlsState();
        updatePlayPauseAndLoadingState(false);

        if (playPauseDrawable == null) {
            playPauseDrawable = new PlayPauseDrawable(Color.WHITE);
        }

        ImageButton btnPlayerPlayPause = controllerView.findViewById(R.id.btnPlayerPlayPause);
        ImageButton btnPlayerPlay = controllerView.findViewById(R.id.btnPlayerPlay);
        ImageButton btnPlayerPause = controllerView.findViewById(R.id.btnPlayerPause);

        if (btnPlayerPlayPause != null) {
            btnPlayerPlayPause.setImageDrawable(playPauseDrawable);
            btnPlayerPlayPause.setOnClickListener(v -> {
                Player p = callback != null ? callback.getPlayer() : null;
                if (p != null) {
                    if (p.getPlaybackState() == Player.STATE_ENDED) {
                        p.seekTo(0);
                        p.play();
                    } else if (p.isPlaying() || p.getPlayWhenReady()) {
                        p.pause();
                    } else {
                        p.play();
                    }
                    updatePlayPauseAndLoadingState(true);
                }
            });
        }

        if (btnPlayerPlay != null) {
            btnPlayerPlay.setOnClickListener(v -> {
                Player p = callback != null ? callback.getPlayer() : null;
                if (p != null) {
                    p.play();
                    updatePlayPauseAndLoadingState(true);
                }
            });
        }

        if (btnPlayerPause != null) {
            btnPlayerPause.setOnClickListener(v -> {
                Player p = callback != null ? callback.getPlayer() : null;
                if (p != null) {
                    p.pause();
                    updatePlayPauseAndLoadingState(true);
                }
            });
        }
    }

    public void updatePlayerControlsState() {
        Player p = callback != null ? callback.getPlayer() : null;
        if (p == null) return;

        runOnUi(() -> {
            PlayerView pv = callback != null ? callback.getPlayerView() : (ui != null ? ui.playerView : null);
            View controllerView = pv != null ? pv.findViewById(R.id.exo_controller) : null;
            if (controllerView != null) {
                ImageButton skipForwardButton = controllerView.findViewById(R.id.skipForwardButton);
                boolean canSeek = p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
                if (skipForwardButton != null) {
                    skipForwardButton.setEnabled(canSeek);
                    skipForwardButton.setAlpha(canSeek ? 1.0f : 0.5f);
                }
                updatePlayPauseAndLoadingState(true);
            }
        });
    }

    public void startSeekingState() {
        isSeeking = true;
        seekResetHandler.removeCallbacksAndMessages(null);
        if (callback != null) {
            callback.startBufferingMonitoring();
        }
        updatePlayPauseAndLoadingState(true);
    }

    public void scheduleEndSeekingState(long delayMs) {
        seekResetHandler.removeCallbacksAndMessages(null);
        Runnable r = () -> {
            if (!isScrubbingTimeBar) {
                Player p = callback != null ? callback.getPlayer() : null;
                long currentPos = p != null ? p.getCurrentPosition() : 0;
                long bufferedPos = p != null ? p.getBufferedPosition() : 0;
                long duration = p != null ? p.getDuration() : 0;
                long bufferedAhead = bufferedPos - currentPos;
                boolean isNearEnd = duration > 0 && currentPos >= (duration - 2000);

                boolean isReadyAndBuffered = p != null
                        && p.getPlaybackState() == Player.STATE_READY
                        && (hasRenderedFirstFrame || p.isPlaying())
                        && (isNearEnd || bufferedAhead >= 1500);

                if (p == null || !p.getPlayWhenReady() || isReadyAndBuffered) {
                    isSeeking = false;
                    updatePlayPauseAndLoadingState(true);
                } else {
                    seekResetHandler.postDelayed(() -> scheduleEndSeekingState(0), 250);
                }
            }
        };
        if (delayMs <= 0) {
            r.run();
        } else {
            seekResetHandler.postDelayed(r, delayMs);
        }
    }

    public void showLoading(String message) {
        runOnUi(() -> {
            isVideoLoading = true;
            updatePlayPauseAndLoadingState(true);
            if (ui != null && ui.loadingOverlay != null) {
                ui.loadingOverlay.setVisibility(View.GONE);
            }
        });
    }

    public void hideLoading() {
        runOnUi(() -> {
            if (ui != null && ui.loadingOverlay != null) {
                ui.loadingOverlay.setVisibility(View.GONE);
            }
            isVideoLoading = false;
            updatePlayPauseAndLoadingState(true);
        });
    }

    public void onPlaybackStateChanged(int playbackState) {
        Player p = callback != null ? callback.getPlayer() : null;
        if (playbackState == Player.STATE_READY) {
            if (hasRenderedFirstFrame || (p != null && p.isPlaying())) {
                isVideoLoading = false;
            }
            PlayerCommentsController commentsCtrl = callback != null ? callback.getCommentsController() : null;
            if (callback != null && !callback.isAutoPlayOnPrepare() && commentsCtrl != null) {
                commentsCtrl.notifyPlayerReady();
            }
        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            isSeeking = false;
            isVideoLoading = false;
            seekResetHandler.removeCallbacksAndMessages(null);
        }
        updatePlayerControlsState();
        EpisodesManager em = callback != null ? callback.getEpisodesManager() : null;
        if (em != null) {
            em.updateEpisodeNavigationButtonsVisibility();
        }
        if (callback != null) {
            callback.startBufferingMonitoring();
        }
        updatePlayPauseAndLoadingState(true);

        if (playbackState == Player.STATE_ENDED && callback != null) {
            PlayerNextEpisodeController nextCtrl = callback.getNextEpisodeController();
            if (nextCtrl != null) {
                nextCtrl.handlePlaybackEnded(callback.isAutoPlayEnabled());
            }
        }
    }

    public void onPositionDiscontinuity(int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            startSeekingState();
            scheduleEndSeekingState(600);
        } else {
            if (callback != null) {
                callback.startBufferingMonitoring();
            }
            updatePlayPauseAndLoadingState(true);
        }
    }

    public void onFirstFrameRendered() {
        hasRenderedFirstFrame = true;
        isVideoLoading = false;
        isSeeking = false;
        if (callback != null) {
            callback.startBufferingMonitoring();
            callback.ensureAnimeTitleSet();
            PlayerCommentsController commentsCtrl = callback.getCommentsController();
            if (commentsCtrl != null) {
                commentsCtrl.notifyPlayerReady();
            }
        }
        updatePlayPauseAndLoadingState(true);
    }

    public void onIsLoadingChanged(boolean isLoading) {
        if (callback != null) {
            callback.startBufferingMonitoring();
        }
        updatePlayPauseAndLoadingState(true);
        if (!isLoading && callback != null) {
            callback.ensureAnimeTitleSet();
        }
    }

    public void onIsPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            hasRenderedFirstFrame = true;
            isVideoLoading = false;
            if (!isScrubbingTimeBar) {
                isSeeking = false;
                seekResetHandler.removeCallbacksAndMessages(null);
            }
            if (callback != null) {
                callback.startViewProgressTracking();
                callback.ensureAnimeTitleSet();
            }
        } else if (callback != null) {
            callback.stopViewProgressTracking();
        }
        updatePlayerControlsState();
        if (callback != null) {
            callback.startBufferingMonitoring();
            callback.onPipParamsUpdateNeeded();
        }
        updatePlayPauseAndLoadingState(true);
    }

    public void onPlayerErrorUi() {
        isSeeking = false;
        isVideoLoading = false;
        seekResetHandler.removeCallbacksAndMessages(null);
        updatePlayerControlsState();
        updatePlayPauseAndLoadingState(true);
    }

    public void updatePlayPauseAndLoadingState(boolean animate) {
        runOnUi(() -> {
            Player p = callback != null ? callback.getPlayer() : null;
            boolean isEnded = p != null && p.getPlaybackState() == Player.STATE_ENDED;
            boolean isPlayWhenReady = p != null && p.getPlayWhenReady();
            int playbackState = p != null ? p.getPlaybackState() : Player.STATE_IDLE;
            boolean realIsPlaying = p != null && p.isPlaying();

            long currentPos = p != null ? p.getCurrentPosition() : 0;
            long duration = p != null ? p.getDuration() : 0;
            boolean isNearEnd = duration > 0 && currentPos >= (duration - 2000);
            boolean isNetworkBuffering = isPlayWhenReady && !isNearEnd && playbackState == Player.STATE_BUFFERING;

            boolean isBuffering = !isEnded && (
                    (isVideoLoading && !hasRenderedFirstFrame && playbackState != Player.STATE_READY)
                            || isSeeking
                            || isScrubbingTimeBar
                            || isNetworkBuffering
            );

            View loadingOverlay = ui != null ? ui.loadingOverlay : null;
            boolean showBuffering = isBuffering && (loadingOverlay == null || loadingOverlay.getVisibility() != View.VISIBLE);

            View playerBufferingIndicator = ui != null ? ui.playerBufferingIndicator : null;
            if (playerBufferingIndicator != null) {
                if (showBuffering && !isControllerVisible) {
                    if (playerBufferingIndicator.getVisibility() != View.VISIBLE) {
                        playerBufferingIndicator.animate().cancel();
                        playerBufferingIndicator.setVisibility(View.VISIBLE);
                        playerBufferingIndicator.setAlpha(0f);
                        playerBufferingIndicator.setScaleX(0.7f);
                        playerBufferingIndicator.setScaleY(0.7f);
                        playerBufferingIndicator.animate()
                                .alpha(1.0f)
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(200)
                                .start();
                    }
                    playerBufferingIndicator.bringToFront();
                } else if (playerBufferingIndicator.getVisibility() == View.VISIBLE) {
                    playerBufferingIndicator.animate().cancel();
                    playerBufferingIndicator.animate()
                            .alpha(0f)
                            .scaleX(0.7f)
                            .scaleY(0.7f)
                            .setDuration(150)
                            .withEndAction(() -> playerBufferingIndicator.setVisibility(View.GONE))
                            .start();
                }
            }

            PlayerView pv = callback != null ? callback.getPlayerView() : (ui != null ? ui.playerView : null);
            if (pv == null) return;
            View controllerView = pv.findViewById(R.id.exo_controller);
            if (controllerView == null) return;

            View playPauseButton = controllerView.findViewById(R.id.btnPlayerPlayPause);
            if (playPauseButton == null) {
                playPauseButton = controllerView.findViewById(R.id.btnPlayerPlay);
            }
            View spinner = controllerView.findViewById(R.id.playLoadingIndicator);
            if (playPauseButton == null || spinner == null) return;

            if (playPauseDrawable == null) {
                playPauseDrawable = new PlayPauseDrawable(Color.WHITE);
            }
            if (playPauseButton instanceof ImageButton) {
                ImageButton ib = (ImageButton) playPauseButton;
                if (ib.getDrawable() != playPauseDrawable) {
                    ib.setImageDrawable(playPauseDrawable);
                }
            }

            int targetState;
            if (showBuffering) {
                targetState = 2; // LOADING
            } else if (!isEnded && (realIsPlaying || (isPlayWhenReady && playbackState == Player.STATE_READY))) {
                targetState = 1; // PAUSE
            } else {
                targetState = 0; // PLAY
            }

            boolean isPlayingState = (targetState == 1);
            boolean isLoadingState = (targetState == 2);
            boolean shouldAnimate = animate && (currentControlState != -1);

            if (currentControlState == targetState && currentControlState != -1) {
                if (isLoadingState) {
                    if (spinner.getVisibility() != View.VISIBLE) {
                        spinner.setVisibility(View.VISIBLE);
                        spinner.setAlpha(1.0f);
                    }
                    playPauseButton.setVisibility(View.GONE);
                } else {
                    if (playPauseButton.getVisibility() != View.VISIBLE) {
                        playPauseButton.setVisibility(View.VISIBLE);
                        playPauseButton.setAlpha(1.0f);
                    }
                    spinner.setVisibility(View.GONE);
                    playPauseDrawable.setPause(isPlayingState, shouldAnimate);
                }
                return;
            }

            currentControlState = targetState;
            final View targetButton = playPauseButton;
            final View targetSpinner = spinner;

            if (isLoadingState) {
                if (targetButton.getVisibility() == View.VISIBLE) {
                    if (shouldAnimate) {
                        targetButton.animate().cancel();
                        targetButton.animate()
                                .alpha(0f)
                                .scaleX(0.6f)
                                .scaleY(0.6f)
                                .setDuration(150)
                                .withEndAction(() -> targetButton.setVisibility(View.GONE))
                                .start();
                    } else {
                        targetButton.setVisibility(View.GONE);
                    }
                }
                targetSpinner.animate().cancel();
                targetSpinner.setVisibility(View.VISIBLE);
                targetSpinner.setAlpha(1.0f);
                targetSpinner.setScaleX(1.0f);
                targetSpinner.setScaleY(1.0f);
                if (targetSpinner instanceof CircularProgressIndicator) {
                    ((CircularProgressIndicator) targetSpinner).show();
                }
            } else {
                if (targetSpinner.getVisibility() == View.VISIBLE) {
                    targetSpinner.animate().cancel();
                    if (shouldAnimate) {
                        targetSpinner.animate()
                                .alpha(0f)
                                .scaleX(0.6f)
                                .scaleY(0.6f)
                                .setDuration(150)
                                .withEndAction(() -> targetSpinner.setVisibility(View.GONE))
                                .start();
                    } else {
                        targetSpinner.setVisibility(View.GONE);
                    }
                }

                if (targetButton.getVisibility() != View.VISIBLE) {
                    targetButton.animate().cancel();
                    targetButton.setVisibility(View.VISIBLE);
                    if (shouldAnimate) {
                        targetButton.setAlpha(0f);
                        targetButton.setScaleX(0.7f);
                        targetButton.setScaleY(0.7f);
                        targetButton.animate()
                                .alpha(1.0f)
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(200)
                                .start();
                    } else {
                        targetButton.setAlpha(1.0f);
                        targetButton.setScaleX(1.0f);
                        targetButton.setScaleY(1.0f);
                    }
                }
                playPauseDrawable.setPause(isPlayingState, shouldAnimate);
            }
        });
    }

    public void cleanup() {
        seekResetHandler.removeCallbacksAndMessages(null);
    }

    private void runOnUi(Runnable action) {
        if (callback != null) {
            callback.safeRunOnUiThread(action);
        } else {
            action.run();
        }
    }

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
            boolean isPortrait = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
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
