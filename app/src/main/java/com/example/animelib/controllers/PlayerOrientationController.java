package com.example.animelib.controllers;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.animelib.R;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.managers.RelatedTitlesManager;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.managers.VerticalGesturesManager;
import com.example.animelib.services.DownloadService;
import com.example.animelib.ui.RoundedCornerFrameLayout;

import java.lang.reflect.Field;

/**
 * Контроллер отслеживания физической ориентации устройства, настройки fullscreen window insets
 * и перестройки portrait/landscape разметки плеера.
 */
public class PlayerOrientationController {

    private static final String TAG = "PlayerOrientationCtrl";

    public interface OrientationLayoutHost {
        PlayerUIBinder getUiBinder();
        PlayerPanelsController getPanelsController();
        PlayerCommentsController getCommentsController();
        PlayerAnimeInfoController getAnimeInfoController();
        PlayerRelatedTitlesController getRelatedTitlesController();
        PlayerDialogsController getDialogsController();
        PlayerLockController getLockController();
        PlayerDownloadController getDownloadController();
        PlayerPortraitController getPortraitController();
        PlayersManager getPlayersManager();
        EpisodesManager getEpisodesManager();
        GesturesManager getGesturesManager();
        VerticalGesturesManager getVerticalGesturesManager();
        TimecodeManager getTimecodeManager();
        boolean isOfflineMode();
        boolean isPlayingDownloadedVideo();
        String getCurrentAnimeId();
        String getAnimeUrl();
    }

    private final Activity activity;
    private OrientationEventListener orientationEventListener;
    private int lastPhysicalOrientation = -1;
    private boolean manualOrientationOverride = false;
    private boolean isLocked = false;

    public PlayerOrientationController(Activity activity) {
        this.activity = activity;
    }

    public void init() {
        orientationEventListener = new OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (isLocked) return;
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;

                int currentPhysicalOrientation;
                if ((orientation >= 315 || orientation < 45) || (orientation >= 135 && orientation < 225)) {
                    currentPhysicalOrientation = Configuration.ORIENTATION_PORTRAIT;
                } else if ((orientation >= 45 && orientation < 135) || (orientation >= 225 && orientation < 315)) {
                    currentPhysicalOrientation = Configuration.ORIENTATION_LANDSCAPE;
                } else {
                    return;
                }

                if (lastPhysicalOrientation != -1 && currentPhysicalOrientation != lastPhysicalOrientation) {
                    if (manualOrientationOverride) {
                        manualOrientationOverride = false;
                        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                    }
                }
                lastPhysicalOrientation = currentPhysicalOrientation;
            }
        };

        enable();
    }

    public void setLocked(boolean locked) {
        this.isLocked = locked;
        if (locked) {
            disable();
            int currentOrientation = activity.getResources().getConfiguration().orientation;
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                if (rotation == android.view.Surface.ROTATION_270 || rotation == android.view.Surface.ROTATION_180) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                } else {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                }
            } else {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            }
        } else {
            manualOrientationOverride = false;
            enable();
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        }
    }

    public boolean isLocked() {
        return isLocked;
    }

    public void toggleFullscreenOrientation() {
        if (isLocked) return;
        boolean isPortrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        manualOrientationOverride = true;
        if (isPortrait) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (manualOrientationOverride && lastPhysicalOrientation == newConfig.orientation) {
            manualOrientationOverride = false;
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        }
    }

    public void setupFullscreen(@Nullable PlayerCommentsController commentsController) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window != null) {
            boolean isPortrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            View decorView = window.getDecorView();
            ViewCompat.setOnApplyWindowInsetsListener(decorView, (v, insets) -> {
                v.setPadding(0, 0, 0, 0);
                View content = v.findViewById(android.R.id.content);
                if (content != null) {
                    content.setPadding(0, 0, 0, 0);
                }
                int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                int navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                if (commentsController != null) {
                    commentsController.attachWindowRoot(decorView);
                    commentsController.onWindowInsetsChanged(imeBottom, navBottom);
                }
                return WindowInsetsCompat.CONSUMED;
            });
            ViewCompat.requestApplyInsets(decorView);
        }

        View rootView = activity.findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.setFitsSystemWindows(false);
            rootView.setPadding(0, 0, 0, 0);
        }
    }

    public int getStatusBarHeight() {
        if (activity == null) return 0;
        int sbHeight = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                sbHeight = insets.getInsets(WindowInsets.Type.statusBars()).top;
            }
        }
        if (sbHeight <= 0) {
            int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                sbHeight = activity.getResources().getDimensionPixelSize(resourceId);
            }
        }
        return sbHeight;
    }

    public void applyRoundedCornersToTimeBar(@Nullable View timeBarView) {
        if (activity == null || timeBarView == null) return;
        try {
            float density = activity.getResources().getDisplayMetrics().density;
            float radiusPx = 2f * density;
            CornerPathEffect pathEffect = new CornerPathEffect(radiusPx);

            Class<?> clazz = timeBarView.getClass();
            while (clazz != null && clazz != View.class) {
                Field[] fields = clazz.getDeclaredFields();
                for (Field field : fields) {
                    if (Paint.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Paint paint = (Paint) field.get(timeBarView);
                        if (paint != null) {
                            paint.setPathEffect(pathEffect);
                            paint.setStrokeCap(Paint.Cap.ROUND);
                            paint.setStrokeJoin(Paint.Join.ROUND);
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                timeBarView.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                    }
                });
                timeBarView.setClipToOutline(true);
            }
            timeBarView.invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Failed to round timebar corners", e);
        }
    }

    public void applyOrientationLayout(OrientationLayoutHost host) {
        if (activity == null || host == null) return;
        PlayerUIBinder ui = host.getUiBinder();
        if (ui == null) return;

        try {
            PlayerCommentsController commentsCtrl = host.getCommentsController();
            setupFullscreen(commentsCtrl);
            boolean isPortrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
            boolean isOfflineMode = host.isOfflineMode();

            if (ui.menuPanelContainer != null) {
                ui.menuPanelContainer.forceClose();
                ui.menuPanelContainer.setVisibility(View.GONE);
            }
            if (ui.commentsPanelContainer != null) {
                ui.commentsPanelContainer.forceClose();
                ui.commentsPanelContainer.setVisibility(View.GONE);
            }
            if (ui.menuOverlay != null) {
                ui.menuOverlay.setVisibility(View.GONE);
                ui.menuOverlay.setAlpha(0f);
            }
            PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
            if (animeInfoCtrl != null) {
                animeInfoCtrl.hidePlaceholderAnimated();
            }
            if (ui.slidingMenuPanel != null) {
                ui.slidingMenuPanel.setVisibility(View.GONE);
            }
            if (ui.commentsPanel != null) {
                ui.commentsPanel.setVisibility(View.GONE);
            }
            if (commentsCtrl != null) {
                commentsCtrl.forceHideCommentsPanel();
            }
            PlayersManager playersManager = host.getPlayersManager();
            if (playersManager != null) {
                playersManager.forceHideMenu();
            }
            EpisodesManager episodesManager = host.getEpisodesManager();
            if (episodesManager != null) {
                episodesManager.hideEpisodesMenu();
            }
            PlayerRelatedTitlesController relatedCtrl = host.getRelatedTitlesController();
            RelatedTitlesManager rtm = relatedCtrl != null ? relatedCtrl.getRelatedTitlesManager() : null;
            if (rtm != null) {
                rtm.hideRelatedTitles();
            }
            GesturesManager gesturesManager = host.getGesturesManager();
            if (gesturesManager != null) {
                gesturesManager.hideAllGesturesUI();
            }
            PlayerDialogsController dialogsCtrl = host.getDialogsController();
            if (dialogsCtrl != null) {
                dialogsCtrl.dismissSettingsBottomSheet();
            }

            PlayerPanelsController panelsCtrl = host.getPanelsController();
            if (panelsCtrl != null) {
                panelsCtrl.applyPlayerSidePanelTransform(0f);
            }
            if (ui.playerContainer != null) {
                ui.playerContainer.setPivotX(0f);
                ui.playerContainer.setPivotY(0f);
                ui.playerContainer.setScaleX(1f);
                ui.playerContainer.setScaleY(1f);
                ui.playerContainer.setTranslationX(0f);
                ui.playerContainer.setTranslationY(0f);
            }

            int sbHeight = isPortrait ? getStatusBarHeight() : 0;

            if (ui.mainVideoLayout != null) {
                ui.mainVideoLayout.setPadding(0, sbHeight, 0, 0);
                if (ui.mainVideoLayout instanceof ViewGroup) {
                    ((ViewGroup) ui.mainVideoLayout).setClipChildren(false);
                    ((ViewGroup) ui.mainVideoLayout).setClipToPadding(false);
                }
            }

            View portraitBottomContainer = ui.portraitBottomContainer;
            if (ui.playerContainer != null) {
                ui.playerContainer.setPadding(0, 0, 0, 0);
                ui.playerContainer.setClipChildren(false);
                ui.playerContainer.setClipToPadding(false);
                ViewCompat.setElevation(ui.playerContainer, isPortrait ? 10f : 0f);

                ViewGroup.LayoutParams rawParams = ui.playerContainer.getLayoutParams();
                if (rawParams != null) {
                    if (rawParams instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) rawParams;
                        mlp.topMargin = 0;
                        mlp.leftMargin = 0;
                        mlp.rightMargin = 0;
                        mlp.bottomMargin = 0;
                    }
                    if (isPortrait) {
                        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                        int portraitWidth = Math.min(dm.widthPixels, dm.heightPixels);
                        rawParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                        rawParams.height = portraitWidth * 9 / 16;
                        if (rawParams instanceof LinearLayout.LayoutParams) {
                            ((LinearLayout.LayoutParams) rawParams).weight = 0;
                        }
                        if (portraitBottomContainer != null) {
                            portraitBottomContainer.setVisibility(View.VISIBLE);
                            ViewCompat.setElevation(portraitBottomContainer, 0f);
                            PlayerPortraitController portraitCtrl = host.getPortraitController();
                            if (portraitCtrl != null) {
                                portraitCtrl.updatePortraitVoiceoverPlayerUI();
                                portraitCtrl.updatePortraitHeaderTitlesUI();
                            }
                            if (relatedCtrl != null && host.getCurrentAnimeId() != null && !isOfflineMode) {
                                relatedCtrl.loadRelatedTitles(host.getCurrentAnimeId(), host.getAnimeUrl());
                            }
                            if (commentsCtrl != null && !isOfflineMode) {
                                commentsCtrl.loadCommentsForPortraitIfNeeded();
                            }
                        }
                    } else {
                        rawParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                        rawParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                        if (rawParams instanceof LinearLayout.LayoutParams) {
                            ((LinearLayout.LayoutParams) rawParams).weight = 0;
                        }
                        if (portraitBottomContainer != null) {
                            portraitBottomContainer.setVisibility(View.GONE);
                        }
                    }
                    ui.playerContainer.setLayoutParams(rawParams);
                    if (!isPortrait && ui.playerContainer instanceof RoundedCornerFrameLayout) {
                        ((RoundedCornerFrameLayout) ui.playerContainer).clearVideoShadowBounds();
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ui.playerContainer.invalidateOutline();
                    }
                }
            }

            if (ui.playerView != null) {
                ui.playerView.setClipChildren(false);
                ui.playerView.setClipToPadding(false);
            }

            View controllerView = ui.controllerView;
            if (controllerView == null) {
                return;
            }

            if (controllerView instanceof ViewGroup) {
                ((ViewGroup) controllerView).setClipChildren(false);
                ((ViewGroup) controllerView).setClipToPadding(false);
            }

            View ibClosePlayer = controllerView.findViewById(R.id.ibClosePlayer);
            if (ibClosePlayer != null) ibClosePlayer.setVisibility(View.VISIBLE);

            View settingsButtonContainer = controllerView.findViewById(R.id.settingsButtonContainer);
            if (settingsButtonContainer != null) settingsButtonContainer.setVisibility(View.VISIBLE);
            if (ui.settingsButton != null) ui.settingsButton.setVisibility(View.VISIBLE);

            if (ui.pipButton == null) ui.pipButton = controllerView.findViewById(R.id.pipButton);
            if (ui.pipButton != null) ui.pipButton.setVisibility(View.VISIBLE);

            if (ui.btnLockScreen == null) ui.btnLockScreen = controllerView.findViewById(R.id.btnLockScreen);
            if (ui.btnLockScreen != null) ui.btnLockScreen.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
            PlayerLockController lockCtrl = host.getLockController();
            if (lockCtrl != null) lockCtrl.updateVisibilityForOrientation(isPortrait);

            View animeInfoContainer = controllerView.findViewById(R.id.animeInfoContainer);
            if (animeInfoContainer != null) {
                animeInfoContainer.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
            }

            View topBarContainer = controllerView.findViewById(R.id.topBarContainer);
            if (topBarContainer != null) {
                ViewGroup.LayoutParams lp = topBarContainer.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams topParams = (FrameLayout.LayoutParams) lp;
                    float density = activity.getResources().getDisplayMetrics().density;
                    if (isPortrait) {
                        topParams.topMargin = (int) (12 * density);
                        topParams.leftMargin = (int) (12 * density);
                        topParams.rightMargin = (int) (12 * density);
                    } else {
                        topParams.topMargin = (int) (16 * density);
                        topParams.leftMargin = (int) (16 * density);
                        topParams.rightMargin = (int) (16 * density);
                    }
                    topBarContainer.setLayoutParams(topParams);
                }
            }

            View centerControlsContainer = controllerView.findViewById(R.id.centerControlsContainer);
            if (centerControlsContainer != null) {
                centerControlsContainer.setVisibility(View.VISIBLE);
            }

            View skipForwardBtn = controllerView.findViewById(R.id.skipForwardButton);
            View bookmarkBtn = controllerView.findViewById(R.id.bookmarkButton);
            View commentsBtn = controllerView.findViewById(R.id.commentsButton);
            View episodesMenuBtn = controllerView.findViewById(R.id.episodesMenuButton);
            View menuToggleBtn = controllerView.findViewById(R.id.menuToggleButton);
            View menuToggleFullscreenBtn = controllerView.findViewById(R.id.menuToggleFullscreen);

            if (skipForwardBtn != null) {
                skipForwardBtn.setVisibility(View.VISIBLE);
                float d = activity.getResources().getDisplayMetrics().density;
                int btnSize = Math.round((isPortrait ? 32f : 36f) * d);
                int btnPad = Math.round((isPortrait ? 5f : 6f) * d);
                int btnMarginEnd = Math.round((isPortrait ? 6f : 8f) * d);
                skipForwardBtn.setPadding(btnPad, btnPad, btnPad, btnPad);
                ViewGroup.LayoutParams sfLp = skipForwardBtn.getLayoutParams();
                if (sfLp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams sfMlp = (ViewGroup.MarginLayoutParams) sfLp;
                    sfMlp.width = btnSize;
                    sfMlp.height = btnSize;
                    sfMlp.setMarginEnd(btnMarginEnd);
                    skipForwardBtn.setLayoutParams(sfMlp);
                }
            }

            TimecodeManager timecodeManager = host.getTimecodeManager();
            if (timecodeManager != null) {
                timecodeManager.updateForOrientation();
            }

            if (bookmarkBtn != null) {
                bookmarkBtn.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
                if (isOfflineMode) {
                    bookmarkBtn.setEnabled(true);
                    bookmarkBtn.setAlpha(1.0f);
                }
            }
            if (commentsBtn != null) {
                commentsBtn.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
                if (isOfflineMode) {
                    commentsBtn.setEnabled(false);
                    commentsBtn.setAlpha(0.3f);
                }
            }
            if (episodesMenuBtn != null) episodesMenuBtn.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
            if (menuToggleBtn != null) {
                menuToggleBtn.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
                menuToggleBtn.setEnabled(true);
                menuToggleBtn.setClickable(true);
                menuToggleBtn.setAlpha(isOfflineMode ? 0.3f : 1.0f);
            }

            PlayerPortraitController portraitCtrl = host.getPortraitController();
            if (isOfflineMode && portraitCtrl != null) {
                portraitCtrl.applyOfflineButtonStates();
            } else if (portraitCtrl != null) {
                portraitCtrl.updateVoiceoverButtonState();
            }

            if (menuToggleFullscreenBtn != null) {
                menuToggleFullscreenBtn.setVisibility(View.VISIBLE);
                float d = activity.getResources().getDisplayMetrics().density;
                int fsSize = Math.round((isPortrait ? 32f : 36f) * d);
                int fsPad = Math.round((isPortrait ? 5f : 6f) * d);
                menuToggleFullscreenBtn.setPadding(fsPad, fsPad, fsPad, fsPad);
                ViewGroup.LayoutParams fsLp = menuToggleFullscreenBtn.getLayoutParams();
                if (fsLp != null) {
                    fsLp.width = fsSize;
                    fsLp.height = fsSize;
                    menuToggleFullscreenBtn.setLayoutParams(fsLp);
                }
                if (menuToggleFullscreenBtn instanceof ImageButton) {
                    ((ImageButton) menuToggleFullscreenBtn).setImageResource(isPortrait ? R.drawable.ic_player_ctrl_fullscreen : R.drawable.ic_player_ctrl_fullscreen_exit);
                }
            }

            View landscapeTargetView = controllerView.findViewById(R.id.landscapeEpisodesContainer);
            if (landscapeTargetView == null) {
                landscapeTargetView = ui.episodesHorizontalRecyclerView;
            }

            if (landscapeTargetView != null) {
                if (isPortrait) {
                    landscapeTargetView.setVisibility(View.GONE);
                } else {
                    if (episodesManager != null && episodesManager.isEpisodesMenuVisible()) {
                        landscapeTargetView.setVisibility(View.VISIBLE);
                        if (ui.episodesHorizontalRecyclerView != null) {
                            ui.episodesHorizontalRecyclerView.setVisibility(View.VISIBLE);
                        }
                    } else {
                        landscapeTargetView.setVisibility(View.INVISIBLE);
                    }
                }
            }

            LinearLayout playersControlBarView = controllerView.findViewById(R.id.playersControlBar);
            View exoProgress = controllerView.findViewById(R.id.timeBarContainer);
            if (exoProgress == null) {
                exoProgress = controllerView.findViewById(R.id.exo_progress);
            }
            View timeAndControlsContainer = controllerView.findViewById(R.id.timeAndControlsContainer);
            View overlayBadgesContainer = controllerView.findViewById(R.id.overlayBadgesContainer);

            if (playersControlBarView != null && exoProgress != null && timeAndControlsContainer != null) {
                playersControlBarView.setClipChildren(false);
                playersControlBarView.setClipToPadding(false);

                if (overlayBadgesContainer != null && overlayBadgesContainer.getParent() instanceof ViewGroup) {
                    ((ViewGroup) overlayBadgesContainer.getParent()).removeView(overlayBadgesContainer);
                }
                if (exoProgress.getParent() instanceof ViewGroup) {
                    ((ViewGroup) exoProgress.getParent()).removeView(exoProgress);
                }
                if (timeAndControlsContainer.getParent() instanceof ViewGroup) {
                    ((ViewGroup) timeAndControlsContainer.getParent()).removeView(timeAndControlsContainer);
                }
                if (landscapeTargetView != null && landscapeTargetView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) landscapeTargetView.getParent()).removeView(landscapeTargetView);
                }

                if (overlayBadgesContainer != null) {
                    ViewGroup.LayoutParams obLp = overlayBadgesContainer.getLayoutParams();
                    LinearLayout.LayoutParams obParams = (obLp instanceof LinearLayout.LayoutParams) ?
                            (LinearLayout.LayoutParams) obLp :
                            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    float d = activity.getResources().getDisplayMetrics().density;
                    obParams.leftMargin = (int) ((isPortrait ? 12 : 16) * d);
                    obParams.rightMargin = (int) ((isPortrait ? 12 : 16) * d);
                    obParams.bottomMargin = (int) (6 * d);
                    overlayBadgesContainer.setLayoutParams(obParams);
                }

                ViewGroup.LayoutParams tcLp = timeAndControlsContainer.getLayoutParams();
                LinearLayout.LayoutParams timeAndControlsParams = (tcLp instanceof LinearLayout.LayoutParams) ?
                        (LinearLayout.LayoutParams) tcLp :
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

                ViewGroup.LayoutParams exLp = exoProgress.getLayoutParams();
                LinearLayout.LayoutParams progressParams = (exLp instanceof LinearLayout.LayoutParams) ?
                        (LinearLayout.LayoutParams) exLp :
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

                float density = activity.getResources().getDisplayMetrics().density;

                if (isPortrait) {
                    playersControlBarView.setTranslationY(0f);

                    timeAndControlsParams.topMargin = (int) (2 * density);
                    timeAndControlsParams.bottomMargin = (int) (2 * density);
                    timeAndControlsParams.leftMargin = (int) (12 * density);
                    timeAndControlsParams.rightMargin = (int) (12 * density);

                    progressParams.topMargin = (int) (2 * density);
                    progressParams.bottomMargin = (int) (12 * density);
                    progressParams.leftMargin = (int) (7 * density);
                    progressParams.rightMargin = (int) (7 * density);
                } else {
                    if (episodesManager != null && episodesManager.isEpisodesMenuVisible()) {
                        playersControlBarView.setTranslationY(0f);
                    } else {
                        playersControlBarView.setTranslationY(60f * density);
                    }

                    timeAndControlsParams.topMargin = (int) (2 * density);
                    timeAndControlsParams.bottomMargin = (int) (2 * density);
                    timeAndControlsParams.leftMargin = (int) (16 * density);
                    timeAndControlsParams.rightMargin = (int) (16 * density);

                    progressParams.topMargin = (int) (2 * density);
                    progressParams.bottomMargin = (int) (16 * density);
                    progressParams.leftMargin = (int) (11 * density);
                    progressParams.rightMargin = (int) (11 * density);
                }

                timeAndControlsContainer.setLayoutParams(timeAndControlsParams);
                exoProgress.setLayoutParams(progressParams);
                exoProgress.setTranslationY(0f);
                applyRoundedCornersToTimeBar(exoProgress);

                if (overlayBadgesContainer != null) {
                    playersControlBarView.addView(overlayBadgesContainer);
                }
                playersControlBarView.addView(timeAndControlsContainer);
                playersControlBarView.addView(exoProgress);
                if (landscapeTargetView != null) {
                    playersControlBarView.addView(landscapeTargetView);
                }

                playersControlBarView.setVisibility(View.VISIBLE);
                exoProgress.setVisibility(View.VISIBLE);
                timeAndControlsContainer.setVisibility(View.VISIBLE);

                View timeCodeContainer = controllerView.findViewById(R.id.timeCodeContainer);
                if (timeCodeContainer != null) timeCodeContainer.setVisibility(View.VISIBLE);
            }

            if (ui.menuPanelContainer != null) ui.menuPanelContainer.setDragEnabled(!isPortrait);
            if (ui.commentsPanelContainer != null) ui.commentsPanelContainer.setDragEnabled(!isPortrait);
            if (gesturesManager != null) gesturesManager.setPortraitMode(isPortrait);
            VerticalGesturesManager verticalGesturesManager = host.getVerticalGesturesManager();
            if (verticalGesturesManager != null) verticalGesturesManager.setPortraitMode(isPortrait);

            PlayerDownloadController downloadCtrl = host.getDownloadController();
            if (downloadCtrl != null) {
                downloadCtrl.syncDownloadUiState(playersManager);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in applyOrientationLayout", e);
        }
    }

    public void enable() {
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    public void disable() {
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    public void cleanup() {
        disable();
        orientationEventListener = null;
    }
}
