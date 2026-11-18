package com.example.animelib;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;

import com.example.animelib.api.ApiService;
import com.example.animelib.controllers.PlayerAnimeInfoController;
import com.example.animelib.controllers.PlayerApiController;
import com.example.animelib.controllers.PlayerAudioController;
import com.example.animelib.controllers.PlayerChronologyController;
import com.example.animelib.controllers.PlayerCommentsController;
import com.example.animelib.controllers.PlayerContextHelper;
import com.example.animelib.controllers.PlayerControlsOverlayManager;
import com.example.animelib.controllers.PlayerDialogsController;
import com.example.animelib.controllers.PlayerDownloadController;
import com.example.animelib.controllers.PlayerEpisodesController;
import com.example.animelib.controllers.PlayerFiltersController;
import com.example.animelib.controllers.PlayerGesturesCoordinator;
import com.example.animelib.controllers.PlayerLockController;
import com.example.animelib.controllers.PlayerMetadataHelper;
import com.example.animelib.controllers.PlayerNextEpisodeController;
import com.example.animelib.controllers.PlayerOfflineController;
import com.example.animelib.controllers.PlayerOrientationController;
import com.example.animelib.controllers.PlayerPanelsController;
import com.example.animelib.controllers.PlayerPipController;
import com.example.animelib.controllers.PlayerPlaybackController;
import com.example.animelib.controllers.PlayerPortraitController;
import com.example.animelib.controllers.PlayerProgressController;
import com.example.animelib.controllers.PlayerQualityController;
import com.example.animelib.controllers.PlayerRelatedTitlesController;
import com.example.animelib.controllers.PlayerSplashScreenController;
import com.example.animelib.controllers.PlayerSubtitlesController;
import com.example.animelib.controllers.PlayerUIBinder;
import com.example.animelib.controllers.PlayerVideoResolverController;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.PlayerPreferences;
import com.example.animelib.managers.AmbientLightManager;
import com.example.animelib.managers.BookmarkManager;
import com.example.animelib.managers.CommentsManager;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.managers.RelatedTitlesManager;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.managers.VerticalGesturesManager;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.settings.SettingsBottomSheet;
import com.example.animelib.ui.RoundedCornerFrameLayout;
import com.example.animelib.util.AutoQualityHelper;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.MediaCacheManager;
import com.example.animelib.util.SkeletonHelper;
import com.example.animelib.util.ThemeUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayer";
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_ANIME_URL = "anime_url";

    private static final int CONTROLLER_SHOW_TIMEOUT_MS = 4000;
    private static final int REQUEST_POST_NOTIFICATIONS = 4201;

    private static final List<WeakReference<VideoPlayerActivity>> ACTIVE_INSTANCES = new CopyOnWriteArrayList<>();

    // Core services & managers
    private ApiService apiService;
    private DatabaseManager databaseManager;
    private DefaultHttpDataSource.Factory httpDataSourceFactory;
    private ExecutorService executor;
    private EpisodesManager episodesManager;
    private PlayersManager playersManager;
    private GesturesManager gesturesManager;
    private VerticalGesturesManager verticalGesturesManager;
    private TimecodeManager timecodeManager;
    private AmbientLightManager ambientLightManager;

    // Controllers
    private PlayerUIBinder playerUIBinder;
    private PlayerApiController playerApiController;
    private PlayerCommentsController playerCommentsController;
    private PlayerAudioController playerAudioController;
    private PlayerPipController playerPipController;
    private PlayerPanelsController playerPanelsController;
    private PlayerControlsOverlayManager playerControlsOverlayManager;
    private PlayerSubtitlesController playerSubtitlesController;
    private PlayerFiltersController playerFiltersController;
    private PlayerNextEpisodeController playerNextEpisodeController;
    private PlayerOrientationController playerOrientationController;
    private PlayerAnimeInfoController playerAnimeInfoController;
    private PlayerDownloadController playerDownloadController;
    private PlayerPlaybackController playerPlaybackController;
    private PlayerProgressController playerProgressController;
    private PlayerQualityController playerQualityController;
    private PlayerDialogsController playerDialogsController;
    private PlayerRelatedTitlesController playerRelatedTitlesController;
    private PlayerEpisodesController playerEpisodesController;
    private PlayerVideoResolverController playerVideoResolverController;
    private PlayerLockController playerLockController;
    private PlayerChronologyController playerChronologyController;
    private PlayerSplashScreenController playerSplashScreenController;
    private PlayerOfflineController playerOfflineController;
    private PlayerPortraitController playerPortraitController;
    private PlayerGesturesCoordinator playerGesturesCoordinator;

    // Activity / session state
    private String animeUrl;
    private String currentAnimeId;
    private AnimeInfoResponse currentAnimeInfo;
    private String currentPosterUrl = "";
    private boolean isAnimeInfoLoaded = false;
    private boolean isEpisodeAndVoiceoversLoaded = false;
    private boolean shouldAutoHideControls = true;
    private boolean enableAmbientLight = true;
    private boolean autoPlay = true;
    private boolean autoPlayOnPrepare = true;
    private int longSkipDuration = 85;
    private int currentTheme = ThemeUtils.THEME_SYSTEM;
    private boolean wasCommentsVisibleBeforePiP = false;
    private boolean wasPlayingBeforeBackground = false;
    private boolean isClosingOrFinishing = false;
    private boolean suppressUserLeavePip = false;

    public static void closeAllExistingInstances() {
        closeOtherInstances(null);
    }

    private static void closeOtherInstances(@Nullable VideoPlayerActivity keepInstance) {
        for (WeakReference<VideoPlayerActivity> ref : ACTIVE_INSTANCES) {
            VideoPlayerActivity instance = ref != null ? ref.get() : null;
            if (instance == null) {
                ACTIVE_INSTANCES.remove(ref);
                continue;
            }
            if (instance != keepInstance) {
                ACTIVE_INSTANCES.remove(ref);
                instance.closeImmediately();
            }
        }
    }

    private static void registerActiveInstance(VideoPlayerActivity instance) {
        if (instance == null) return;
        for (WeakReference<VideoPlayerActivity> ref : ACTIVE_INSTANCES) {
            VideoPlayerActivity existing = ref != null ? ref.get() : null;
            if (existing == null) {
                ACTIVE_INSTANCES.remove(ref);
            } else if (existing == instance) {
                return;
            }
        }
        ACTIVE_INSTANCES.add(new WeakReference<>(instance));
    }

    private static void unregisterActiveInstance(VideoPlayerActivity instance) {
        for (WeakReference<VideoPlayerActivity> ref : ACTIVE_INSTANCES) {
            VideoPlayerActivity existing = ref != null ? ref.get() : null;
            if (existing == null || existing == instance) {
                ACTIVE_INSTANCES.remove(ref);
            }
        }
    }

    public boolean isInPipMode() {
        if (playerPipController != null && playerPipController.isInPictureInPictureMode()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return isInPictureInPictureMode();
        }
        return false;
    }

    public void closeImmediately() {
        if (isClosingOrFinishing) return;
        boolean wasInPip = isInPipMode();
        isClosingOrFinishing = true;
        suppressUserLeavePip = true;
        try {
            if (playerPipController != null) {
                playerPipController.disableAutoEnterPip();
                playerPipController.setInPictureInPictureMode(false);
                playerPipController.unregisterPipReceiver();
            }
            if (playerProgressController != null) {
                playerProgressController.autoSaveBookmark();
                playerProgressController.stopViewProgressTracking();
            }
            if (playerVideoResolverController != null) {
                playerVideoResolverController.cancelPendingResolutions();
            }
            if (playersManager != null) {
                playersManager.cancelPendingPlayerLoads();
            }
            if (playerPlaybackController != null) {
                playerPlaybackController.stopCurrentPlayback();
                playerPlaybackController.release();
                playerPlaybackController = null;
            }
            if (!isFinishing() && !isDestroyed()) {
                if (wasInPip && isTaskRoot() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask();
                } else {
                    super.finish();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing old VideoPlayerActivity instance", e);
        }
    }

    public static void startFromAnimePage(Context context, String animeUrl) {
        Intent intent = new Intent(context, VideoPlayerActivity.class);
        intent.putExtra(EXTRA_ANIME_URL, animeUrl);
        if (context instanceof VideoPlayerActivity) {
            VideoPlayerActivity current = (VideoPlayerActivity) context;
            if (current.isInPipMode()) {
                closeAllExistingInstances();
            } else {
                closeOtherInstances(current);
                current.suppressUserLeavePip = true;
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            }
        } else {
            closeAllExistingInstances();
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
        }
        context.startActivity(intent);
    }

    public static void startForOfflineEpisode(Context context, String localFilePath, String animeTitle, String episodeTitle, String animeId, String episodeNumber) {
        closeAllExistingInstances();
        Intent intent = new Intent(context, VideoPlayerActivity.class);
        intent.putExtra("EXTRA_LOCAL_FILE_PATH", localFilePath);
        intent.putExtra("EXTRA_ANIME_TITLE", animeTitle);
        intent.putExtra("EXTRA_EPISODE_TITLE", episodeTitle);
        intent.putExtra("EXTRA_ANIME_ID", animeId);
        intent.putExtra("EXTRA_EPISODE_NUMBER", episodeNumber);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    public static void startForOfflineEpisode(Context context, String localFilePath, String animeTitle, String episodeTitle) {
        startForOfflineEpisode(context, localFilePath, animeTitle, episodeTitle, null, null);
    }

    public static String cleanEpisodeName(String name, String epNumber) {
        return PlayerMetadataHelper.cleanEpisodeName(name, epNumber);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!isTaskRoot()) {
            Intent intent = getIntent();
            if (intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN.equals(intent.getAction())) {
                finish();
                return;
            }
        }
        closeOtherInstances(this);
        registerActiveInstance(this);
        ThemeUtils.applyTheme(ThemeUtils.getSavedThemePreference(this));
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_AnimeLIB_VideoPlayer);
        setContentView(R.layout.activity_video_player);

        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.setFitsSystemWindows(false);
        }

        playerSplashScreenController = new PlayerSplashScreenController();
        playerSplashScreenController.initViews(rootView);
        if (getIntent() != null && getIntent().getStringExtra("EXTRA_LOCAL_FILE_PATH") == null) {
            playerSplashScreenController.showOnEntry();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerUIBinder = new PlayerUIBinder();
        playerUIBinder.bindRootViews(rootView);
        if (playerUIBinder.playerContainer != null) {
            playerUIBinder.playerContainer.setBackgroundColor(Color.TRANSPARENT);
            playerUIBinder.playerContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                    if (isPortrait && playerPanelsController != null) {
                        playerPanelsController.applyPlayerSidePanelTransform(0f);
                    }
                    if (ambientLightManager != null) {
                        ambientLightManager.updateTransform(1f, 0f, 0f, false);
                        ambientLightManager.refreshAmbientFrame();
                    }
                }
            });
        }
        if (playerUIBinder.playerView != null) {
            playerUIBinder.playerView.setControllerAnimationEnabled(false);
        }

        executor = Executors.newSingleThreadExecutor();
        playerApiController = new PlayerApiController(this);
        apiService = playerApiController.getApiService();
        databaseManager = DatabaseManager.getInstance(this);

        httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(apiService.getVideoRequestHeaders());

        initControllers(rootView);
        loadAndApplyTheme();
        updateControllerAutoHide();

        MediaCacheManager.warmUpAsync(this);
        if (playerSubtitlesController != null) {
            playerSubtitlesController.loadSettingsFromApi(apiService);
        }
        loadAsyncSettings();

        String localFilePath = getIntent().getStringExtra("EXTRA_LOCAL_FILE_PATH");
        String initialVideoUrl = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        animeUrl = getIntent().getStringExtra(EXTRA_ANIME_URL);
        if (playerQualityController != null) {
            playerQualityController.setCurrentVideoUrl(initialVideoUrl);
        }
        if (localFilePath != null && playerOfflineController != null) {
            playerOfflineController.setOfflineMode(true);
        }

        setupFullscreen();
        setupMenu(rootView);

        if (localFilePath != null && playerOfflineController != null) {
            String animeTitle = getIntent().getStringExtra("EXTRA_ANIME_TITLE");
            String episodeTitle = getIntent().getStringExtra("EXTRA_EPISODE_TITLE");
            playerOfflineController.playOfflineFile(localFilePath, animeTitle, episodeTitle);
        } else if (initialVideoUrl != null) {
            initializeDirectPlayback();
        } else if (animeUrl != null) {
            if (playerControlsOverlayManager != null) {
                playerControlsOverlayManager.showLoading("Загрузка видео...");
            }
            loadAnimeFromUrl(animeUrl);
        } else {
            CustomToast.showWarning(this, "Ошибка: Не указан URL видео или страницы аниме");
            finish();
        }
    }

    private void initControllers(View rootView) {
        playerOfflineController = new PlayerOfflineController(this, createOfflineHost());
        playerPortraitController = new PlayerPortraitController(this, createPortraitHost());
        playerGesturesCoordinator = new PlayerGesturesCoordinator(createGesturesHost());
        playerGesturesCoordinator.bindIndicatorViews(rootView);

        playerCommentsController = new PlayerCommentsController(this, apiService);
        episodesManager = new EpisodesManager(this, apiService);
        playersManager = new PlayersManager(this, apiService);
        gesturesManager = new GesturesManager(this);
        verticalGesturesManager = new VerticalGesturesManager(this);
        gesturesManager.setVerticalGesturesManager(verticalGesturesManager);
        verticalGesturesManager.setGesturesManager(gesturesManager);
        timecodeManager = new TimecodeManager(this);

        playerAudioController = new PlayerAudioController();
        playerAudioController.init(this, apiService);

        playerFiltersController = new PlayerFiltersController();
        playerOrientationController = new PlayerOrientationController(this);
        playerOrientationController.init();
        playerLockController = new PlayerLockController(this);

        playerDialogsController = new PlayerDialogsController(this);
        playerDialogsController.setCallback(new PlayerDialogsController.DialogCallback() {
            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public String getPreferredQuality() {
                return playerQualityController != null
                        ? playerQualityController.getCurrentDisplayQualityForSettings() : "Авто";
            }

            @Override
            public void hideLoading() {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.hideLoading();
                }
            }

            @Override
            public void showLoading(String message) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.showLoading(message);
                }
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        });

        playerPanelsController = new PlayerPanelsController(this, new PlayerPanelsController.PanelsCallback() {
            @Override
            public ExoPlayer getPlayer() {
                return getActivePlayer();
            }

            @Override
            public PlayerView getPlayerView() {
                return playerUIBinder != null ? playerUIBinder.playerView : null;
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public CommentsManager getCommentsManager() {
                return playerCommentsController != null ? playerCommentsController.getCommentsManager() : null;
            }

            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public boolean isPlayingDownloadedVideo() {
                return VideoPlayerActivity.this.isPlayingDownloadedVideo();
            }

            @Override
            public int getStatusBarHeight() {
                return playerOrientationController != null ? playerOrientationController.getStatusBarHeight() : 0;
            }

            @Override
            public void updateAmbientPlayerTransform(float scale, float translationX, float translationY, boolean isCropped, float availRight) {
                if (ambientLightManager != null) {
                    ambientLightManager.updateTransform(scale, translationX, translationY, isCropped, availRight);
                }
            }

            @Override
            public void setAmbientTransitionAnimating(boolean animating) {
                if (ambientLightManager != null) {
                    ambientLightManager.setPanelTransitioning(animating);
                }
            }

            @Override
            public void onOutlineValuesChanged(float left, float top, float right, float bottom, float radiusPx) {
                if (playerUIBinder != null && playerUIBinder.playerContainer != null) {
                    if (playerUIBinder.playerContainer instanceof RoundedCornerFrameLayout) {
                        ((RoundedCornerFrameLayout) playerUIBinder.playerContainer).setCornerRadius(radiusPx);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        playerUIBinder.playerContainer.invalidateOutline();
                    }
                }
            }
        });

        playerControlsOverlayManager = new PlayerControlsOverlayManager();

        playerPipController = new PlayerPipController(this, new PlayerPipController.PipCallback() {
            @Override
            public ExoPlayer getPlayer() {
                return getActivePlayer();
            }

            @Override
            public void onPipEnterUIState() {
                wasCommentsVisibleBeforePiP = playerCommentsController != null && playerCommentsController.isCommentsVisible();
                hideAllUI();
            }

            @Override
            public void onPipExitUIState() {
                showAllUI();
            }

            @Override
            public void onPipSeekingStarted() {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.startSeekingState();
                }
            }

            @Override
            public void onPipSeekingEnded() {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.scheduleEndSeekingState(600);
                }
            }
        });
        playerPipController.setupPipReceiver();

        playerSubtitlesController = new PlayerSubtitlesController(new PlayerSubtitlesController.SubtitlesCallback() {
            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public DownloadedEpisodeEntity getCurrentOfflineEpisode() {
                return playerOfflineController != null ? playerOfflineController.getCurrentOfflineEpisode() : null;
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public String getCurrentVideoUrl() {
                return playerQualityController != null ? playerQualityController.getCurrentVideoUrl() : null;
            }

            @Override
            public String getCurrentVideoDomain() {
                return playerQualityController != null
                        ? playerQualityController.getCurrentVideoDomain() : com.example.animelib.ui.VideoUrlHelper.DOMAIN_MAIN;
            }

            @Override
            public ExoPlayer getPlayer() {
                return getActivePlayer();
            }

            @Override
            public PlayerView getPlayerView() {
                return playerUIBinder != null ? playerUIBinder.playerView : null;
            }

            @Override
            public ApiService getApiService() {
                return apiService;
            }

            @Override
            public Context getContext() {
                return VideoPlayerActivity.this;
            }
        });

        playerNextEpisodeController = new PlayerNextEpisodeController();
        playerNextEpisodeController.initViews(rootView);
        playerNextEpisodeController.setEpisodesManager(episodesManager);
        playerNextEpisodeController.setPrefetchDependencies(apiService, playersManager, new PlayerNextEpisodeController.PrefetchHost() {
            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public boolean isFinishingOrDestroyed() {
                return isFinishing() || isDestroyed();
            }
        });

        playerEpisodesController = new PlayerEpisodesController();
        playerEpisodesController.initialize(episodesManager, playersManager, apiService, new PlayerEpisodesController.EpisodesCallback() {
            @Override
            public void onEpisodeHeaderUpdateQuick() {
                if (playerPortraitController != null) {
                    playerPortraitController.updateEpisodeHeaderQuick();
                }
            }

            @Override
            public void onResetBookmarkState() {
                if (playerProgressController != null) {
                    playerProgressController.setAutoBookmarkSaved(false);
                    playerProgressController.updateBookmarkButtonColor(false);
                }
            }

            @Override
            public void onInitializeMenuWithoutAutoPlay() {
                initializeMenuWithoutAutoPlay();
            }

            @Override
            public void onEpisodeChanged(EpisodesListResponse.EpisodeItem episode) {
                if (playerCommentsController != null) {
                    playerCommentsController.setCurrentEpisode(episode);
                    if (!isOfflineMode() && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                        playerCommentsController.loadCommentsForPortraitIfNeeded();
                    }
                }
                if (playerPortraitController != null) {
                    playerPortraitController.updatePortraitHeaderTitlesUI();
                }
            }

            @Override
            public boolean tryStartDownloadedEpisodeImmediately(EpisodesListResponse.EpisodeItem episode, long initialBookmarkMs) {
                return playerOfflineController != null
                        && playerOfflineController.tryStartDownloadedEpisodeImmediately(episode, initialBookmarkMs);
            }
        });

        playerAnimeInfoController = new PlayerAnimeInfoController();
        playerAnimeInfoController.initViews(rootView);

        playerChronologyController = new PlayerChronologyController(new PlayerChronologyController.ChronologyCallback() {
            @Override
            public Context getContext() {
                return VideoPlayerActivity.this;
            }

            @Override
            public ApiService getApiService() {
                return apiService;
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }

            @Override
            public PlayerDialogsController getPlayerDialogsController() {
                return playerDialogsController;
            }

            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }
        });
        playerChronologyController.initViews(rootView);

        playerDownloadController = new PlayerDownloadController(this, databaseManager);
        playerDownloadController.initViews(rootView);
        playerDownloadController.setCallback(new PlayerDownloadController.DownloadCallback() {
            @Override
            public String getAnimeId() {
                return currentAnimeId;
            }

            @Override
            public String getAnimeTitle() {
                String titleText = (playerUIBinder != null && playerUIBinder.animeTitleView != null)
                        ? playerUIBinder.animeTitleView.getText().toString() : null;
                return PlayerContextHelper.getAnimeTitle(titleText, currentAnimeInfo, getIntent());
            }

            @Override
            public String getPosterUrl() {
                return currentPosterUrl;
            }

            @Override
            public String getCurrentVideoUrl() {
                return playerQualityController != null ? playerQualityController.getCurrentVideoUrl() : null;
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        });

        playerPlaybackController = new PlayerPlaybackController(
                this,
                playerUIBinder != null ? playerUIBinder.playerView : null,
                httpDataSourceFactory
        );
        playerPlaybackController.setCallback(createPlaybackCallback());

        playerProgressController = new PlayerProgressController(this, apiService);
        playerProgressController.setCallback(new PlayerProgressController.ProgressCallback() {
            @Override
            public String getAnimeId() {
                return currentAnimeId;
            }

            @Override
            public String getAnimeUrl() {
                return animeUrl;
            }

            @Override
            public String getMediaSlug() {
                return VideoPlayerActivity.this.getMediaSlug();
            }

            @Override
            public String resolveOfflineAnimeId() {
                return VideoPlayerActivity.this.resolveOfflineAnimeId();
            }

            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public boolean hasUserSwitchedEpisode() {
                return playerEpisodesController != null && playerEpisodesController.hasUserSwitchedEpisode();
            }

            @Override
            public boolean hasRenderedFirstFrame() {
                return playerControlsOverlayManager != null && playerControlsOverlayManager.hasRenderedFirstFrame();
            }

            @Override
            public Player getPlayer() {
                return getActivePlayer();
            }

            @Override
            public EpisodesManager getEpisodesManager() {
                return episodesManager;
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public ImageView getBookmarkButton() {
                return playerUIBinder != null ? playerUIBinder.bookmarkButton : null;
            }

            @Override
            public ImageView getPortraitBookmarkButton() {
                return playerPortraitController != null ? playerPortraitController.getPortraitBookmarkButton() : null;
            }

            @Override
            public Object getCurrentWatchStatusId() {
                return playerProgressController.getCurrentWatchStatusId();
            }

            @Override
            public void saveLatestViewOnExit() {
                VideoPlayerActivity.this.saveLatestViewOnExit();
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        });

        playerQualityController = new PlayerQualityController();
        playerQualityController.setHost(createQualityHost());
        playerQualityController.setCallback(new PlayerQualityController.QualityCallback() {
            @Override
            public void onQualityChanged(String newQuality, String newVideoUrl, boolean isHls) {
                if (playerDialogsController != null) {
                    playerQualityController.refreshQualityUiState();
                }
            }

            @Override
            public void onError(String title, String message, Runnable retryAction) {
                if (playerDialogsController != null) {
                    playerDialogsController.showVideoErrorDialog(title, message, retryAction);
                }
            }
        });

        playerRelatedTitlesController = new PlayerRelatedTitlesController(new PlayerRelatedTitlesController.RelatedTitlesCallback() {
            @Override
            public Context getContext() {
                return VideoPlayerActivity.this;
            }

            @Override
            public ApiService getApiService() {
                return apiService;
            }

            @Override
            public AmbientLightManager getAmbientLightManager() {
                return ambientLightManager;
            }

            @Override
            public View getExoController() {
                return findViewById(R.id.exo_controller);
            }

            @Override
            public PlayerDialogsController getPlayerDialogsController() {
                return playerDialogsController;
            }

            @Override
            public void saveLatestViewOnExit() {
                if (playerProgressController != null) {
                    playerProgressController.autoSaveBookmark();
                }
            }

            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        });

        playerVideoResolverController = new PlayerVideoResolverController(
                apiService,
                timecodeManager,
                playersManager,
                createResolverProvider()
        );
        playerVideoResolverController.setHost(createResolverHost(), executor);
    }

    private void loadAsyncSettings() {
        executor.execute(() -> {
            PlayerPreferences savedPrefs = apiService.loadPlayerPreferences();
            if (savedPrefs != null && savedPrefs.getPreferredQuality() != null && !savedPrefs.getPreferredQuality().trim().isEmpty()) {
                applyLoadedPreferredQualityIfUnset(savedPrefs.getPreferredQuality());
            }
            boolean loaded4K = apiService.load4KSetting();
            if (playerQualityController != null) {
                playerQualityController.setEnable4K(loaded4K);
            }
            enableAmbientLight = apiService.loadAmbientLightSetting();
            if (playerAudioController != null) {
                playerAudioController.loadSettings(apiService);
            }
            autoPlay = apiService.loadAutoPlaySetting();
            if (playerEpisodesController != null) {
                playerEpisodesController.setNewEpisodeSelection(true);
            }
            autoPlayOnPrepare = autoPlay;
            longSkipDuration = apiService.loadLongSkipDurationSetting();
            currentTheme = apiService.loadThemeSetting();
            if (playerSubtitlesController != null) {
                playerSubtitlesController.loadSettingsFromApi(apiService);
            }
            if (playerFiltersController != null) {
                playerFiltersController.loadSettingsFromApi(apiService);
            }

            runOnUiThread(() -> {
                if (playersManager != null) {
                    playersManager.setEnable4K(loaded4K);
                }
                if (ambientLightManager != null) {
                    ambientLightManager.setEnabled(enableAmbientLight);
                }
                if (playerFiltersController != null) {
                    playerFiltersController.applyFilters();
                }
                if (playerAudioController != null) {
                    playerAudioController.applyToManager();
                }
                if (playerQualityController != null) {
                    playerQualityController.refreshQualityUiState();
                }
            });
        });
    }

    @SuppressLint({"ClickableViewAccessibility", "RtlHardcoded"})
    private void setupMenu(View rootView) {
        if (playerUIBinder != null) {
            playerUIBinder.bindRootViews(rootView);
            if (playerUIBinder.controllerView != null) {
                playerUIBinder.controllerView.setAlpha(1.0f);
                if (playerQualityController != null) {
                    playerQualityController.updateSettingsQualityTag();
                }
                boolean isPortraitInit = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                if (playerUIBinder.commentsButton != null) {
                    playerUIBinder.commentsButton.setVisibility((isPortraitInit || isCommentsDisabledInPlayer()) ? View.GONE : View.VISIBLE);
                }
            }
            if (playerUIBinder.menuOverlay != null) {
                playerUIBinder.menuOverlay.setBackgroundColor(Color.TRANSPARENT);
                playerUIBinder.menuOverlay.setOnClickListener(v -> {
                    if (isMenuPanelOpen()) {
                        closeMenuPanel();
                    }
                    if (isCommentsPanelOpen()) {
                        closeCommentsPanel();
                    }
                });
            }
        }

        if (playerDownloadController != null) {
            playerDownloadController.setupDownloadListener(playersManager);
        }

        // Initialize managers
        gesturesManager.initializeViews(
                playerUIBinder.playerView,
                null,
                playerUIBinder.holdSpeedToast,
                playerUIBinder.seekPreviewText,
                playerUIBinder.skipIndicatorLeft,
                playerUIBinder.skipIndicatorRight
        );

        if (playerPanelsController != null) {
            playerPanelsController.initViews(
                    playerUIBinder.menuPanelContainer,
                    playerUIBinder.commentsPanelContainer,
                    playerUIBinder.slidingMenuPanel,
                    playerUIBinder.commentsPanel,
                    playerUIBinder.menuOverlay,
                    playerUIBinder.playerContainer
            );
        }

        if (playerLockController != null) {
            playerLockController.init(
                    playerUIBinder.playerView,
                    playerOrientationController,
                    gesturesManager,
                    playerUIBinder.btnLockScreen,
                    rootView
            );
        }

        if (playerCommentsController != null) {
            playerCommentsController.initializePanelViews(
                    playerUIBinder.commentsPanel,
                    playerUIBinder.closeCommentsButton,
                    playerUIBinder.commentsRecyclerView,
                    playerUIBinder.commentsLoadingOverlay,
                    playerUIBinder.commentsButton,
                    playerUIBinder.commentsOptionsButton,
                    playerUIBinder.menuOverlay,
                    playerUIBinder.emptyCommentsText
            );
        }

        playersManager.initializeViews(
                playerUIBinder.slidingMenuPanel,
                playerUIBinder.closeMenuButton,
                playerUIBinder.menuOverlay,
                playerUIBinder.menuLoadingOverlay,
                playerUIBinder.menuLoadingIndicator
        );

        episodesManager.initializeViews(
                null,
                playerUIBinder.episodesMenuButton,
                playerUIBinder.episodesHorizontalRecyclerView,
                null,
                playerUIBinder.prevEpisodeButton,
                playerUIBinder.nextEpisodeButton,
                playerUIBinder.menuOverlay,
                playerUIBinder.playersControlBar
        );
        if (playerUIBinder.landscapeEpisodesContainer != null) {
            episodesManager.setLandscapeEpisodesContainer(playerUIBinder.landscapeEpisodesContainer);
        }
        if (playerUIBinder.btnLandscapeEpisodeSearch != null && playerUIBinder.etLandscapeEpisodeNumber != null) {
            episodesManager.setupLandscapeSearchViews(playerUIBinder.btnLandscapeEpisodeSearch, playerUIBinder.etLandscapeEpisodeNumber);
        }

        ambientLightManager = new AmbientLightManager(
                this,
                playerUIBinder.playerView,
                playerUIBinder.ambientContainer,
                playerUIBinder.ambientPlayerView,
                playerUIBinder.ambientVignetteOverlay
        );
        if (httpDataSourceFactory != null) {
            ambientLightManager.setDataSourceFactory(httpDataSourceFactory);
        }
        ambientLightManager.setEnabled(enableAmbientLight);

        if (playerFiltersController != null && playerUIBinder.playerView != null) {
            playerFiltersController.init(this, playerUIBinder.playerView);
        }

        if (playerPortraitController != null) {
            playerPortraitController.initializePortraitViews();
        }
        if (playerRelatedTitlesController != null && playerPortraitController != null) {
            playerRelatedTitlesController.initialize(
                    playerUIBinder.relatedTitlesOverlay,
                    playerUIBinder.relatedTitlesDimOverlay,
                    playerUIBinder.relatedTitlesRecyclerView,
                    playerPortraitController.getPortraitRelatedTitlesContainer(),
                    playerPortraitController.getPortraitRelatedTitlesRecyclerView(),
                    rootView
            );
        }

        // Setup controls overlay & gestures
        if (playerControlsOverlayManager != null) {
            playerControlsOverlayManager.bind(this, playerUIBinder, createControlsCallback());
            playerControlsOverlayManager.setupOverlayAndControlListeners();
            playerControlsOverlayManager.configureInitialState();
        }
        if (playerGesturesCoordinator != null) {
            playerGesturesCoordinator.setupCallbacks(gesturesManager, verticalGesturesManager);
        }

        checkAndUpdateOrientation();
        setupManagerCallbacks();

        // Start initial loading
        if (!isOfflineMode()) {
            boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
            if (!isPortrait) {
                playersManager.showMenu();
            }
            if (playerUIBinder.menuLoadingOverlay != null) {
                playerUIBinder.menuLoadingOverlay.setVisibility(View.VISIBLE);
            }
            String currentUrl = playerQualityController != null ? playerQualityController.getCurrentVideoUrl() : null;
            if (currentUrl != null && animeUrl != null && playerPortraitController != null) {
                playerPortraitController.loadAnimeInfoForPlaceholder();
            }
        }
    }

    private void setupManagerCallbacks() {
        episodesManager.setOnBookmarkLoadedListener(bookmarkData -> {
            if (bookmarkData == null) return;
            if (playerProgressController != null) {
                playerProgressController.updatePortraitWatchStatusUI(bookmarkData.getStatus());
            }

            EpisodesListResponse.EpisodeItem curEp = episodesManager.getCurrentEpisode();
            boolean isMatchingEpisode = curEp != null && (
                    curEp.getId() == bookmarkData.getItemId()
                            || (bookmarkData.getItemId() <= 0 && curEp.getNumber() != null
                            && bookmarkData.getMeta() != null && bookmarkData.getMeta().getItemNumber() > 0
                            && curEp.getNumber().equals(String.valueOf(bookmarkData.getMeta().getItemNumber())))
            );
            if (!isMatchingEpisode) return;

            if (playerProgressController != null) {
                playerProgressController.updateBookmarkButtonColor(true);
            }

            boolean canApplyInitialBookmark = playerEpisodesController == null
                    || (!playerEpisodesController.hasUserSwitchedEpisode() && !playerEpisodesController.isInitialBookmarkConsumed());
            if (canApplyInitialBookmark && bookmarkData.getProgress() != null && !bookmarkData.getProgress().trim().isEmpty()) {
                long bmMs = episodesManager.getBookmarkManager().parseTimecodeToMilliseconds(bookmarkData.getProgress());
                if (bmMs > 0) {
                    ExoPlayer active = getActivePlayer();
                    if (active != null && active.getCurrentPosition() < 5000) {
                        active.seekTo(bmMs);
                        if (playerEpisodesController != null) {
                            playerEpisodesController.markInitialBookmarkConsumed();
                        }
                    } else if (playerEpisodesController != null) {
                        playerEpisodesController.setBookmarkTimecode(bmMs);
                    }
                }
            }
        });

        episodesManager.setPlayerControlsCallback(shouldAutoHide -> {
            shouldAutoHideControls = shouldAutoHide;
            updateControllerAutoHide();
        });

        if (playerCommentsController != null) {
            playerCommentsController.setCallback(new PlayerCommentsController.CommentsCallback() {
                @Override
                public void onHideControllerRequested() {
                    if (playerUIBinder != null && playerUIBinder.playerView != null) {
                        playerUIBinder.playerView.hideController();
                    }
                }

                @Override
                public void onUpdateNavigationRequested() {
                    if (episodesManager != null) {
                        episodesManager.updateEpisodeNavigationButtonsVisibility();
                    }
                }

                @Override
                public void onCommentsInPlayerToggled(boolean disabled) {
                    runOnUiThread(() -> {
                        if (playerUIBinder != null && playerUIBinder.commentsButton != null && playerUIBinder.playerView != null) {
                            boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                            if (disabled || isPortrait) {
                                playerUIBinder.commentsButton.setVisibility(View.GONE);
                            } else if (playerUIBinder.playerView.isControllerFullyVisible()) {
                                playerUIBinder.commentsButton.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                }
            });
        }

        episodesManager.setEpisodeSelectionCallback(this::onEpisodeSelected);
        episodesManager.setVisibilityCallback(isVisible -> {
            if (ambientLightManager != null) {
                ambientLightManager.resume();
            }
        });

        RelatedTitlesManager rtm = playerRelatedTitlesController != null ? playerRelatedTitlesController.getRelatedTitlesManager() : null;
        if (rtm != null) {
            rtm.setVisibilityCallback(isVisible -> {
                if (ambientLightManager != null) {
                    ambientLightManager.resume();
                }
            });
        }

        episodesManager.setDataCallback(new EpisodesManager.EpisodesDataCallback() {
            @Override
            public void onEpisodesLoaded(List<EpisodesListResponse.EpisodeItem> episodes) {
                if (episodes == null || episodes.isEmpty()) {
                    isEpisodeAndVoiceoversLoaded = true;
                    checkAndDismissSplashScreen();
                }

                if (currentAnimeId != null) {
                    String rawUrl = getIntent() != null ? getIntent().getStringExtra("anime_url") : null;
                    if (rawUrl == null || rawUrl.isEmpty()) {
                        rawUrl = getIntent() != null ? getIntent().getStringExtra(EXTRA_ANIME_URL) : null;
                    }
                    if (rawUrl == null || rawUrl.isEmpty()) {
                        rawUrl = VideoPlayerActivity.this.animeUrl;
                    }
                    String mediaSlug = rawUrl != null && !rawUrl.isEmpty() ? ApiService.extractMediaSlugFromUrl(rawUrl) : null;

                    if (mediaSlug != null) {
                        episodesManager.getBookmarkManager().getLastEpisodeFromBookmark(mediaSlug, episodes,
                                new BookmarkManager.LastEpisodeCallback() {
                                    @Override
                                    public void onLastEpisodeFound(EpisodesListResponse.EpisodeItem episode, String progress) {
                                        safeRunOnUiThread(() -> {
                                            if (playerEpisodesController != null && playerEpisodesController.hasUserSwitchedEpisode()) {
                                                return;
                                            }
                                            long parsedBm = episodesManager.getBookmarkManager().parseTimecodeToMilliseconds(progress);
                                            if (playerEpisodesController != null) {
                                                playerEpisodesController.setNewEpisodeSelection(true);
                                                playerEpisodesController.setBookmarkTimecode(parsedBm);
                                            }
                                            if (playerProgressController != null) {
                                                playerProgressController.updateBookmarkButtonColor(true);
                                            }
                                            autoPlayOnPrepare = VideoPlayerActivity.this.autoPlay;
                                            if (playerOfflineController != null
                                                    && playerOfflineController.tryStartDownloadedEpisodeImmediately(episode, parsedBm)) {
                                                return;
                                            }
                                            episodesManager.setCurrentEpisode(episode);
                                            if (playerCommentsController != null) {
                                                playerCommentsController.setCurrentEpisode(episode);
                                            }
                                            if (playerPortraitController != null) {
                                                playerPortraitController.updateEpisodeHeaderQuick();
                                            }
                                            playersManager.loadPlayersForEpisode(episode.getId());
                                        });
                                    }

                                    @Override
                                    public void onNoBookmarkFound() {
                                        safeRunOnUiThread(() -> {
                                            if (playerEpisodesController != null && playerEpisodesController.hasUserSwitchedEpisode()) {
                                                return;
                                            }
                                            String activeUrl = VideoPlayerActivity.this.animeUrl;
                                            if (activeUrl != null && activeUrl.contains("episode=")) {
                                                if (playerEpisodesController != null) {
                                                    playerEpisodesController.fallbackToUrlDetection(getIntent());
                                                }
                                            } else if (playerEpisodesController != null) {
                                                autoPlayOnPrepare = VideoPlayerActivity.this.autoPlay;
                                                playerEpisodesController.loadFirstEpisode();
                                            }
                                        });
                                    }
                                });
                    } else if (playerEpisodesController != null) {
                        playerEpisodesController.fallbackToUrlDetection(getIntent());
                    }
                } else if (playerEpisodesController != null) {
                    playerEpisodesController.fallbackToUrlDetection(getIntent());
                }
            }

            @Override
            public void onEpisodesError(String error) {
                Log.e(TAG, "Error loading episodes: " + error);
                isEpisodeAndVoiceoversLoaded = true;
                if (playerSplashScreenController != null && playerSplashScreenController.isShowing()) {
                    playerSplashScreenController.dismissWithAnimation(null);
                }
                if (playerDialogsController != null) {
                    playerDialogsController.showVideoErrorDialog(
                            "Ошибка загрузки озвучек",
                            "Не удалось загрузить список эпизодов и озвучек.",
                            error,
                            () -> {
                                if (currentAnimeId != null && playerEpisodesController != null) {
                                    playerEpisodesController.loadEpisodes(currentAnimeId, getIntent());
                                }
                            },
                            true
                    );
                }
                initializeMenuWithoutAutoPlay();
            }
        });

        playersManager.setPlayerSelectionCallback(playerData -> {
            if (playerVideoResolverController != null) {
                playerVideoResolverController.onPlayerSelected(playerData);
            }
        });

        playersManager.setDataCallback(new PlayersManager.PlayersDataCallback() {
            @Override
            public void onPlayersLoaded(List<EpisodeResponse.PlayerData> players) {
                isEpisodeAndVoiceoversLoaded = true;
                checkAndDismissSplashScreen();

                if (playersManager.getCurrentPlayerData() == null) {
                    if (playerVideoResolverController != null) {
                        playerVideoResolverController.showPlayerSelectionDialogWithAutoSelect(players);
                    }
                } else if (playerUIBinder != null && playerUIBinder.menuLoadingOverlay != null) {
                    playerUIBinder.menuLoadingOverlay.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayersError(String error) {
                Log.e(TAG, "Error loading players: " + error);
                isEpisodeAndVoiceoversLoaded = true;
                if (playerSplashScreenController != null && playerSplashScreenController.isShowing()) {
                    playerSplashScreenController.dismissWithAnimation(null);
                }
                if (playerOfflineController != null && playerOfflineController.handleOnlinePlayersErrorWithOfflineFallback()) {
                    return;
                }
                if (playerDialogsController != null) {
                    playerDialogsController.showVideoErrorDialog(
                            "Ошибка загрузки плееров",
                            "Не удалось загрузить список плееров.",
                            error,
                            () -> {
                                EpisodesListResponse.EpisodeItem episode = episodesManager.getCurrentEpisode();
                                if (episode != null) {
                                    playersManager.loadPlayersForEpisode(episode.getId());
                                }
                            },
                            true
                    );
                }
            }
        });

        playersManager.setVisibilityCallback(isVisible -> {
            if (isVisible && playerUIBinder != null && playerUIBinder.playerView != null) {
                playerUIBinder.playerView.hideController();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        suppressUserLeavePip = false;
        closeOtherInstances(this);
        if (playerProgressController != null) {
            playerProgressController.autoSaveBookmark();
        }
        setIntent(intent);

        if (playerPlaybackController != null) {
            playerPlaybackController.stopCurrentPlayback();
        }

        String localFilePath = intent.getStringExtra("EXTRA_LOCAL_FILE_PATH");
        String newVideoUrl = intent.getStringExtra(EXTRA_VIDEO_URL);
        animeUrl = intent.getStringExtra(EXTRA_ANIME_URL);

        currentAnimeInfo = null;
        isAnimeInfoLoaded = false;
        isEpisodeAndVoiceoversLoaded = false;
        if (playerOfflineController != null) {
            playerOfflineController.resetForNewAnime();
        }
        if (playerQualityController != null) {
            playerQualityController.resetForNewAnime();
            playerQualityController.setCurrentVideoUrl(newVideoUrl);
        }
        if (playerControlsOverlayManager != null) {
            playerControlsOverlayManager.setHasRenderedFirstFrame(false);
        }
        if (playersManager != null) {
            playersManager.cancelPendingPlayerLoads();
        }
        if (playerEpisodesController != null) {
            playerEpisodesController.resetForNewAnime();
        }
        if (playerProgressController != null) {
            playerProgressController.resetForNewAnime();
        }
        if (playerAnimeInfoController != null) {
            playerAnimeInfoController.collapsePortraitInfo();
            playerAnimeInfoController.setOfflineMode(localFilePath != null);
            if (localFilePath == null) {
                playerAnimeInfoController.showSkeletons();
            }
        }
        if (playerUIBinder != null && playerUIBinder.animeTitleView != null) {
            SkeletonHelper.showSkeleton(playerUIBinder.animeTitleView, 160);
        }
        if (playerPortraitController != null) {
            playerPortraitController.updatePortraitHeaderTitlesUI();
        }
        if (playerChronologyController != null) {
            playerChronologyController.cancelPendingPreload();
        }
        if (playerCommentsController != null) {
            playerCommentsController.resetCommentsOnEpisodeChange(true);
        }

        if (localFilePath != null && playerOfflineController != null) {
            playerOfflineController.setOfflineMode(true);
            String animeTitle = intent.getStringExtra("EXTRA_ANIME_TITLE");
            String episodeTitle = intent.getStringExtra("EXTRA_EPISODE_TITLE");
            playerOfflineController.playOfflineFile(localFilePath, animeTitle, episodeTitle);
        } else if (newVideoUrl != null) {
            initializeDirectPlayback();
        } else if (animeUrl != null) {
            if (playerControlsOverlayManager != null) {
                playerControlsOverlayManager.showLoading("Загрузка видео...");
            }
            loadAnimeFromUrl(animeUrl);
        }
    }

    private void initializeDirectPlayback() {
        String url = playerQualityController != null ? playerQualityController.getCurrentVideoUrl() : null;
        if (url == null || playerPlaybackController == null) return;
        long startPos = playerEpisodesController != null ? playerEpisodesController.consumeStartPosition() : 0L;
        if (playerSubtitlesController != null) {
            playerPlaybackController.initializePlayer(
                    url,
                    playerSubtitlesController.createMediaItemWithSubtitles(url),
                    playerPlaybackController.getCurrentResizeMode(),
                    autoPlayOnPrepare,
                    startPos
            );
        }
        autoPlayOnPrepare = true;
    }

    private void loadAnimeFromUrl(String url) {
        this.animeUrl = url;
        if (getIntent() != null) {
            getIntent().putExtra(EXTRA_ANIME_URL, url);
            getIntent().putExtra("anime_url", url);
        }

        currentAnimeInfo = null;
        currentAnimeId = apiService.extractAnimeId(url);
        if (currentAnimeId == null) {
            CustomToast.showWarning(this, "Не удалось извлечь ID аниме из URL");
            finish();
            return;
        }

        String animeSlug = apiService.extractAnimeSlug(url);
        if (animeSlug == null) animeSlug = currentAnimeId;

        if (playerAnimeInfoController != null) {
            playerAnimeInfoController.setOfflineMode(false);
            playerAnimeInfoController.setOnRetryListener(() -> {
                if (playerPortraitController != null) {
                    playerPortraitController.loadAnimeInfoForPlaceholder();
                }
            });
            playerAnimeInfoController.showSkeletons();
        }

        // Этап 1 (Приоритет 1): Список эпизодов + единый запрос закладки (если авторизован) для быстрого старта видео
        if (playerEpisodesController != null) {
            playerEpisodesController.loadEpisodes(currentAnimeId, getIntent());
        }

        // Этап 1 (Приоритет 2): Метаданные тайтла (после получения запускает Этап 3: связанные тайтлы и предзагрузку хронологии)
        apiService.fetchAnimeInfo(animeSlug, new ApiService.AnimeInfoCallback() {
            @Override
            public void onAnimeInfoReceived(AnimeInfoResponse response) {
                if (response != null && response.getData() != null) {
                    safeRunOnUiThread(() -> {
                        if (playerPortraitController != null) {
                            playerPortraitController.displayAnimeInfo(response);
                        }
                    });
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.w(TAG, "Failed to fetch anime info in loadAnimeFromUrl: " + errorMessage);
                safeRunOnUiThread(() -> {
                    isAnimeInfoLoaded = true;
                    if (playerSplashScreenController != null && playerSplashScreenController.isShowing()) {
                        playerSplashScreenController.dismissWithAnimation(null);
                    }
                    if (playerAnimeInfoController != null && currentAnimeInfo == null) {
                        playerAnimeInfoController.showError(() -> {
                            if (playerPortraitController != null) {
                                playerPortraitController.loadAnimeInfoForPlaceholder();
                            }
                        });
                    }
                    // Fallback: если метаданные не загрузились, всё равно подгружаем связанные тайтлы
                    if (playerRelatedTitlesController != null && currentAnimeId != null && !isOfflineMode()) {
                        playerRelatedTitlesController.loadRelatedTitles(currentAnimeId, animeUrl);
                    }
                });
            }
        });
    }

    private void onEpisodeSelected(EpisodesListResponse.EpisodeItem episode, boolean autoPlayRequested) {
        if (episode == null) return;
        Log.d(TAG, "Episode selected: " + episode.getNumber() + " (ID: " + episode.getId() + ")");

        autoPlayOnPrepare = this.autoPlay;
        if (playerNextEpisodeController != null) {
            playerNextEpisodeController.cancelPendingNextEpisodePrefetch();
        }
        if (playersManager != null) {
            playersManager.cancelPendingPlayerLoads();
        }

        if (playerProgressController != null) {
            playerProgressController.capturePlaybackSnapshot();
        }
        if (playerVideoResolverController != null) {
            playerVideoResolverController.incrementPlaybackRestartGeneration();
            playerVideoResolverController.cancelPendingResolutions();
        }
        if (playerPlaybackController != null) {
            playerPlaybackController.stopCurrentPlayback();
        }
        if (playerQualityController != null) {
            playerQualityController.resetForNewEpisode();
        }
        if (playerControlsOverlayManager != null) {
            playerControlsOverlayManager.setHasRenderedFirstFrame(false);
        }

        if (playerProgressController != null) {
            playerProgressController.resetProgressForNewEpisode();
        }
        if (playerEpisodesController != null) {
            playerEpisodesController.markEpisodeSwitched();
        }

        if (episodesManager != null && playerProgressController != null) {
            com.example.animelib.models.AnimeBookmarkResponse.BookmarkData bm = episodesManager.getAnimeBookmark();
            if (bm != null && bm.getItemId() == episode.getId()) {
                playerProgressController.updateBookmarkButtonColor(true);
            }
        }

        if (episodesManager != null) {
            episodesManager.hideEpisodesMenu();
        }
        if (playerCommentsController != null) {
            playerCommentsController.resetCommentsOnEpisodeChange(true);
        }

        if (isOfflineMode() && playerOfflineController != null) {
            episodesManager.setCurrentEpisode(episode);
            if (playerCommentsController != null) {
                playerCommentsController.setCurrentEpisode(episode);
            }
            episodesManager.updateEpisodeNavigationButtonsVisibility();
            episodesManager.updateEpisodesRecyclerView();
            if (playerPortraitController != null) {
                playerPortraitController.updateEpisodeHeaderQuick();
            }

            DownloadedEpisodeEntity offlineEp = playerOfflineController.getOfflineEpisode(episode);
            if (offlineEp != null && offlineEp.getLocalFilePath() != null) {
                playerOfflineController.playOfflineFile(offlineEp.getLocalFilePath(), offlineEp.getAnimeTitle(), offlineEp.getEpisodeName());
            } else {
                CustomToast.showWarning(this, "Файл серии не найден");
            }
            return;
        }

        if (playerOfflineController != null && playerOfflineController.tryStartDownloadedEpisodeImmediately(episode, 0L)) {
            return;
        }

        runOnUiThread(() -> {
            if (playerControlsOverlayManager != null) {
                playerControlsOverlayManager.showLoading("Загрузка плееров для эпизода...");
            }
            if (playerUIBinder != null && playerUIBinder.menuLoadingOverlay != null) {
                playerUIBinder.menuLoadingOverlay.setVisibility(View.VISIBLE);
            }
        });

        if (playerEpisodesController != null) {
            playerEpisodesController.onEpisodeSelected(episode, autoPlayRequested);
        }
    }

    private void initializeMenuWithoutAutoPlay() {
        episodesManager.updateEpisodeNavigationButtonsVisibility();
        episodesManager.updateEpisodesRecyclerView();

        EpisodesListResponse.EpisodeItem currentEpisode = episodesManager.getCurrentEpisode();
        if (currentEpisode != null) {
            playersManager.loadPlayersForEpisode(currentEpisode.getId());
        }

        if (playerUIBinder != null) {
            if (playerUIBinder.menuLoadingOverlay != null) playerUIBinder.menuLoadingOverlay.setVisibility(View.GONE);
            if (playerUIBinder.menuLoadingIndicator != null) playerUIBinder.menuLoadingIndicator.setVisibility(View.GONE);
        }
    }

    private void showSettingsDialog() {
        if (playerDialogsController == null) return;

        playerDialogsController.showSettingsDialog(new com.example.animelib.controllers.PlayerSettingsBridge(
                new com.example.animelib.controllers.PlayerSettingsBridge.Host() {
                    @Override public Context getContext() { return VideoPlayerActivity.this; }
                    @Override public boolean isOfflineMode() { return VideoPlayerActivity.this.isOfflineMode(); }
                    @Override public boolean isPlayingDownloadedVideo() { return VideoPlayerActivity.this.isPlayingDownloadedVideo(); }
                    @Override public ExoPlayer getActivePlayer() { return VideoPlayerActivity.this.getActivePlayer(); }
                    @Override public ApiService getApiService() { return apiService; }
                    @Override public PlayersManager getPlayersManager() { return playersManager; }
                    @Override public AmbientLightManager getAmbientLightManager() { return ambientLightManager; }
                    @Override public PlayerOfflineController getPlayerOfflineController() { return playerOfflineController; }
                    @Override public PlayerQualityController getPlayerQualityController() { return playerQualityController; }
                    @Override public PlayerFiltersController getPlayerFiltersController() { return playerFiltersController; }
                    @Override public PlayerAudioController getPlayerAudioController() { return playerAudioController; }
                    @Override public PlayerPlaybackController getPlayerPlaybackController() { return playerPlaybackController; }
                    @Override public PlayerSubtitlesController getPlayerSubtitlesController() { return playerSubtitlesController; }
                    @Override public boolean isEnableAmbientLight() { return enableAmbientLight; }
                    @Override public void setEnableAmbientLight(boolean enabled) { enableAmbientLight = enabled; }
                    @Override public boolean isAutoPlay() { return autoPlay; }
                    @Override public void setAutoPlayAndPrepare(boolean enabled) {
                        autoPlay = enabled;
                        autoPlayOnPrepare = enabled;
                    }
                    @Override public int getLongSkipDuration() { return longSkipDuration; }
                    @Override public void setLongSkipDuration(int duration) { longSkipDuration = duration; }
                    @Override public int getCurrentTheme() { return currentTheme; }
                    @Override public void applyThemeAndOrientation(int themeMode) {
                        currentTheme = themeMode;
                        ThemeUtils.applyThemeToActivity(VideoPlayerActivity.this, themeMode);
                        setupFullscreen();
                        checkAndUpdateOrientation();
                    }
                }
        ));
    }

    private void loadAndApplyTheme() {
        final int initialSavedTheme = ThemeUtils.getSavedThemePreference(this);
        executor.execute(() -> {
            try {
                int sharedPrefTheme = ThemeUtils.getSavedThemePreference(this);
                int themeMode = apiService.loadThemeSetting();
                int finalTheme = (sharedPrefTheme >= 0 && sharedPrefTheme <= 2) ? sharedPrefTheme : themeMode;

                safeRunOnUiThread(() -> {
                    boolean currentNight = ThemeUtils.isDarkTheme(VideoPlayerActivity.this);
                    boolean needsChange = (finalTheme != initialSavedTheme)
                            || (finalTheme == ThemeUtils.THEME_DARK && !currentNight)
                            || (finalTheme == ThemeUtils.THEME_LIGHT && currentNight);
                    if (needsChange) {
                        ThemeUtils.applyThemeToActivity(VideoPlayerActivity.this, finalTheme);
                    }
                    setupFullscreen();
                    checkAndUpdateOrientation();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load and apply theme", e);
                safeRunOnUiThread(() -> {
                    setupFullscreen();
                    checkAndUpdateOrientation();
                });
            }
        });
    }

    private void setupFullscreen() {
        if (playerOrientationController != null) {
            playerOrientationController.setupFullscreen(playerCommentsController);
        }
    }

    private void checkAndUpdateOrientation() {
        setupFullscreen();
        if (playerOrientationController != null) {
            playerOrientationController.applyOrientationLayout(createOrientationLayoutHost());
        }
    }

    private void updateControllerAutoHide() {
        if (playerControlsOverlayManager != null && playerUIBinder != null) {
            playerControlsOverlayManager.updateControllerAutoHide(
                    playerUIBinder.playerView, shouldAutoHideControls, CONTROLLER_SHOW_TIMEOUT_MS);
        }
    }

    private void hideAllUI() {
        if (playerControlsOverlayManager != null && playerUIBinder != null) {
            SettingsBottomSheet sbs = playerDialogsController != null ? playerDialogsController.getCurrentSettingsBottomSheet() : null;
            playerControlsOverlayManager.hideAllUI(
                    playerUIBinder.playerView,
                    playerUIBinder.menuPanelContainer,
                    playerCommentsController != null ? playerCommentsController.getCommentsManager() : null,
                    episodesManager,
                    playersManager,
                    gesturesManager,
                    playerUIBinder.pipButton,
                    sbs
            );
        }
    }

    private void showAllUI() {
        if (playerControlsOverlayManager != null && playerUIBinder != null) {
            playerControlsOverlayManager.showAllUI(
                    playerUIBinder.playerView,
                    playerUIBinder.pipButton,
                    episodesManager,
                    playersManager,
                    gesturesManager,
                    playerCommentsController != null ? playerCommentsController.getCommentsManager() : null,
                    wasCommentsVisibleBeforePiP
            );
        }
    }

    private void checkAndDismissSplashScreen() {
        if (playerSplashScreenController == null) return;
        if (isAnimeInfoLoaded && isEpisodeAndVoiceoversLoaded) {
            playerSplashScreenController.dismissWithAnimation(null);
        }
    }

    private void saveLatestViewOnExit() {
        if (playerProgressController == null) return;
        EpisodesListResponse.EpisodeItem currentEpisode = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
        EpisodeResponse.PlayerData currentPlayer = playersManager != null ? playersManager.getCurrentPlayerData() : null;
        ExoPlayer active = getActivePlayer();
        long currentPosition = active != null ? active.getCurrentPosition() : 0;
        long duration = active != null && active.getDuration() > 0 ? active.getDuration() : 0;
        String intentTitle = getIntent() != null ? getIntent().getStringExtra("EXTRA_ANIME_TITLE") : null;

        playerProgressController.saveLatestViewOnExit(
                currentAnimeInfo,
                currentAnimeId,
                animeUrl,
                intentTitle,
                currentEpisode,
                currentPlayer,
                currentPosition,
                duration
        );
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void safeRunOnUiThread(Runnable runnable) {
        if (runnable == null) return;
        try {
            runOnUiThread(runnable);
        } catch (Exception e) {
            Log.e(TAG, "Error calling UI thread", e);
            try {
                runnable.run();
            } catch (Exception ex) {
                Log.e(TAG, "Error in fallback callback", ex);
            }
        }
    }

    @Nullable
    private ExoPlayer getActivePlayer() {
        return playerPlaybackController != null ? playerPlaybackController.getPlayer() : null;
    }

    private boolean isOfflineMode() {
        return playerOfflineController != null && playerOfflineController.isOfflineMode();
    }

    private Context getPlayerContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Context attributionContext = createAttributionContext("default");
                if (attributionContext != null) {
                    return attributionContext;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error creating attribution context", e);
            }
        }
        return this;
    }

    // ---- Activity lifecycle callbacks ----

    @Override
    protected void onStart() {
        super.onStart();
        ExoPlayer p = getActivePlayer();
        if (p != null && wasPlayingBeforeBackground && p.getPlaybackState() != Player.STATE_ENDED) {
            p.play();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        suppressUserLeavePip = false;
        if (playerOrientationController != null) {
            playerOrientationController.enable();
        }
        setupFullscreen();
        if (playerQualityController != null) {
            playerQualityController.startBufferingMonitoring();
        }
        if (playerDownloadController != null) {
            if (com.example.animelib.services.DownloadService.isRunning()) {
                playerDownloadController.showDownloadProgress(
                        com.example.animelib.services.DownloadService.getCurrentTaskPercent(), playersManager);
            } else {
                playerDownloadController.resetDownloadUi(playersManager);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (playerQualityController != null) {
            playerQualityController.stopBufferingMonitoring();
        }
        if (playerOrientationController != null) {
            playerOrientationController.disable();
        }
        if (playerProgressController != null) {
            playerProgressController.autoSaveBookmark();
        }
        ExoPlayer p = getActivePlayer();
        if (p != null) {
            wasPlayingBeforeBackground = p.getPlayWhenReady();
            if (!isInPipMode()) {
                p.pause();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (playerProgressController != null) {
            playerProgressController.autoSaveBookmark();
        }
        ExoPlayer p = getActivePlayer();
        if (p != null) {
            p.pause();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupFullscreen();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (playerOrientationController != null) {
            playerOrientationController.onConfigurationChanged(newConfig);
        }
        setupFullscreen();
        checkAndUpdateOrientation();
        if (ambientLightManager != null) {
            ambientLightManager.onConfigurationChanged();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (playerPipController != null) {
            playerPipController.onPictureInPictureModeChanged(isInPictureInPictureMode);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isClosingOrFinishing || suppressUserLeavePip || isFinishing() || isDestroyed()) {
            return;
        }
        ExoPlayer p = getActivePlayer();
        if (p != null && p.isPlaying() && playerPipController != null && !isInPipMode()) {
            if (playerProgressController != null) {
                playerProgressController.autoSaveBookmark();
            }
            playerPipController.enterPictureInPictureMode();
        }
    }

    @Override
    public void finish() {
        isClosingOrFinishing = true;
        suppressUserLeavePip = true;
        if (playerPipController != null) {
            playerPipController.disableAutoEnterPip();
        }
        if (playerProgressController != null) {
            playerProgressController.autoSaveBookmark();
        }
        super.finish();
    }

    @Override
    public void onBackPressed() {
        if (playerLockController != null && playerLockController.isLocked()) {
            playerLockController.unlockScreen();
            return;
        }
        isClosingOrFinishing = true;
        suppressUserLeavePip = true;
        if (playerPipController != null) {
            playerPipController.disableAutoEnterPip();
        }
        if (playerProgressController != null) {
            playerProgressController.autoSaveBookmark();
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        isClosingOrFinishing = true;
        unregisterActiveInstance(this);
        if (playerProgressController != null) {
            playerProgressController.autoSaveBookmark();
        }
        super.onDestroy();

        if (playerNextEpisodeController != null) {
            playerNextEpisodeController.cleanup();
            playerNextEpisodeController = null;
        }
        if (playerProgressController != null) {
            playerProgressController.stopViewProgressTracking();
        }
        if (playerQualityController != null) {
            playerQualityController.cleanup();
            playerQualityController = null;
        }
        if (playerControlsOverlayManager != null) {
            playerControlsOverlayManager.cleanup();
            playerControlsOverlayManager = null;
        }
        if (playerGesturesCoordinator != null) {
            playerGesturesCoordinator.cleanup();
            playerGesturesCoordinator = null;
        }
        if (playerVideoResolverController != null) {
            playerVideoResolverController.cancelPendingResolutions();
            playerVideoResolverController = null;
        }
        if (playerSplashScreenController != null) {
            playerSplashScreenController.dismissImmediately();
            playerSplashScreenController = null;
        }
        if (playerOrientationController != null) {
            playerOrientationController.cleanup();
            playerOrientationController = null;
        }
        if (playerLockController != null) {
            playerLockController.cleanup();
            playerLockController = null;
        }

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (playerPlaybackController != null) {
            playerPlaybackController.release();
            playerPlaybackController = null;
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        if (playerApiController != null) {
            playerApiController.shutdown();
        }
        if (playerCommentsController != null) {
            playerCommentsController.cleanup();
        }
        if (playersManager != null) {
            playersManager.cleanup();
        }
        if (gesturesManager != null) {
            gesturesManager.cleanup();
        }
        if (timecodeManager != null) {
            timecodeManager.cleanup();
        }
        if (ambientLightManager != null) {
            ambientLightManager.cleanup();
        }
        if (playerAudioController != null) {
            playerAudioController.release();
            playerAudioController = null;
        }
        if (playerPipController != null) {
            playerPipController.unregisterPipReceiver();
            playerPipController = null;
        }
        if (playerDownloadController != null) {
            playerDownloadController.cleanup();
            playerDownloadController = null;
        }
        if (playerChronologyController != null) {
            playerChronologyController.cancelPendingPreload();
        }
        if (playerDialogsController != null) {
            playerDialogsController.dismissErrorDialog();
            playerDialogsController.dismissSettingsBottomSheet();
        }
    }

    // ---- Public API methods used by external managers / UI sheets ----

    public void openMenuPanel() {
        if (playerPanelsController != null) {
            playerPanelsController.openMenuPanel();
        }
    }

    public void closeMenuPanel() {
        if (playerPanelsController != null) {
            playerPanelsController.closeMenuPanel();
        }
    }

    public void openCommentsPanel() {
        if (playerPanelsController != null) {
            playerPanelsController.openCommentsPanel();
        }
    }

    public void closeCommentsPanel() {
        if (playerPanelsController != null) {
            playerPanelsController.closeCommentsPanel();
        }
    }

    public boolean isMenuPanelOpen() {
        return playerPanelsController != null && playerPanelsController.isMenuPanelOpen();
    }

    public boolean isCommentsPanelOpen() {
        return playerPanelsController != null && playerPanelsController.isCommentsPanelOpen();
    }

    public boolean isCommentsDisabledInPlayer() {
        if (databaseManager != null) {
            return databaseManager.loadDisableCommentsInPlayerSetting();
        }
        return apiService != null && apiService.loadDisableCommentsInPlayerSetting();
    }

    public void showDownloadBottomSheet() {
        showDownloadBottomSheet(null);
    }

    public void showDownloadBottomSheet(Runnable onBackPressedAction) {
        if (isOfflineMode()) {
            CustomToast.showInfo(this, "Воспроизводится скачанный файл");
            return;
        }
        requestNotificationPermission();
        if (playerDownloadController != null) {
            playerDownloadController.showDownloadBottomSheet(episodesManager, playersManager, onBackPressedAction);
        }
    }

    public void showDownloadProgressBottomSheet() {
        showDownloadProgressBottomSheet(null);
    }

    public void showDownloadProgressBottomSheet(Runnable onBackPressedAction) {
        if (playerDownloadController != null) {
            playerDownloadController.showDownloadProgressBottomSheet(onBackPressedAction);
        }
    }

    public void applyLoadedPreferredQualityIfUnset(String dbQuality) {
        if (playerQualityController != null) {
            playerQualityController.applyLoadedPreferredQualityIfUnset(dbQuality);
        }
    }

    public void onUserManualVoiceoverSelected(EpisodeResponse.PlayerData playerData) {
        if (playerQualityController != null) {
            playerQualityController.onUserManualVoiceoverSelected(playerData);
        }
    }

    public void syncCurrentPlayerDataForDownloadedIfNeeded() {
        if (playerOfflineController != null) {
            playerOfflineController.syncCurrentPlayerDataForDownloadedIfNeeded();
        }
    }

    public EpisodesManager getEpisodesManager() {
        return episodesManager;
    }

    public void updateEpisodeHeaderQuick() {
        if (playerPortraitController != null) {
            playerPortraitController.updateEpisodeHeaderQuick();
        }
    }

    public void onBackgroundOnlinePlayersLoadedForDownloaded(List<EpisodeResponse.PlayerData> players) {
        if (playerOfflineController != null) {
            playerOfflineController.onBackgroundOnlinePlayersLoadedForDownloaded(players);
        }
    }

    public boolean isPlayingDownloadedVideo() {
        return playerOfflineController != null && playerOfflineController.isPlayingDownloadedVideo();
    }

    public void updatePortraitWatchStatusUI(Object statusId) {
        if (playerProgressController != null) {
            playerProgressController.updatePortraitWatchStatusUI(statusId);
        }
    }

    public void fetchAndApplyWatchStatus() {
        if (playerProgressController != null) {
            playerProgressController.fetchAndApplyWatchStatus();
        }
    }

    public void showWatchStatusBottomSheet() {
        if (playerProgressController != null) {
            playerProgressController.showWatchStatusBottomSheet();
        }
    }

    public String resolveOfflineAnimeId() {
        return playerOfflineController != null ? playerOfflineController.resolveOfflineAnimeId() : null;
    }

    public String getMediaSlug() {
        String infoSlugUrl = (currentAnimeInfo != null && currentAnimeInfo.getData() != null)
                ? currentAnimeInfo.getData().getSlug_url() : null;
        String extraAnimeUrl = getIntent() != null ? getIntent().getStringExtra(EXTRA_ANIME_URL) : null;
        String legacyAnimeUrl = getIntent() != null ? getIntent().getStringExtra("anime_url") : null;

        String slug = PlayerMetadataHelper.resolveMediaSlug(infoSlugUrl, animeUrl, extraAnimeUrl, legacyAnimeUrl, currentAnimeId);
        if (slug == null && apiService != null) {
            String rawUrl = animeUrl != null && !animeUrl.isEmpty()
                    ? animeUrl
                    : (extraAnimeUrl != null && !extraAnimeUrl.isEmpty() ? extraAnimeUrl : legacyAnimeUrl);
            if (rawUrl != null && !rawUrl.isEmpty()) {
                slug = apiService.extractAnimeSlug(rawUrl);
            }
            if (slug == null && infoSlugUrl != null && !infoSlugUrl.isEmpty()) {
                slug = apiService.extractAnimeSlug(infoSlugUrl);
            }
        }
        return slug;
    }

    // ---- Host / Callback Factories ----

    private PlayerOfflineController.OfflineHost createOfflineHost() {
        return new PlayerOfflineController.OfflineHost() {
            @Override
            public Intent getIntent() {
                return VideoPlayerActivity.this.getIntent();
            }

            @Override
            public String getCurrentAnimeId() {
                return currentAnimeId;
            }

            @Override
            public void setCurrentAnimeId(String animeId) {
                currentAnimeId = animeId;
            }

            @Override
            public void setAutoPlayOnPrepare(boolean value) {
                autoPlayOnPrepare = value;
            }

            @Override
            public boolean isAutoPlayOnPrepare() {
                return autoPlayOnPrepare;
            }

            @Override
            public ApiService getApiService() {
                return apiService;
            }

            @Override
            public DatabaseManager getDatabaseManager() {
                return databaseManager;
            }

            @Override
            public ExecutorService getExecutor() {
                return executor;
            }

            @Override
            public EpisodesManager getEpisodesManager() {
                return episodesManager;
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public PlayerUIBinder getUiBinder() {
                return playerUIBinder;
            }

            @Override
            public PlayerPlaybackController getPlaybackController() {
                return playerPlaybackController;
            }

            @Override
            public PlayerControlsOverlayManager getControlsOverlayManager() {
                return playerControlsOverlayManager;
            }

            @Override
            public PlayerQualityController getQualityController() {
                return playerQualityController;
            }

            @Override
            public PlayerVideoResolverController getVideoResolverController() {
                return playerVideoResolverController;
            }

            @Override
            public PlayerEpisodesController getEpisodesController() {
                return playerEpisodesController;
            }

            @Override
            public PlayerProgressController getProgressController() {
                return playerProgressController;
            }

            @Override
            public PlayerCommentsController getCommentsController() {
                return playerCommentsController;
            }

            @Override
            public PlayerAnimeInfoController getAnimeInfoController() {
                return playerAnimeInfoController;
            }

            @Override
            public PlayerSplashScreenController getSplashScreenController() {
                return playerSplashScreenController;
            }

            @Override
            public PlayerPanelsController getPanelsController() {
                return playerPanelsController;
            }

            @Override
            public PlayerDownloadController getDownloadController() {
                return playerDownloadController;
            }

            @Override
            public PlayerSubtitlesController getSubtitlesController() {
                return playerSubtitlesController;
            }

            @Override
            public PlayerPortraitController getPortraitController() {
                return playerPortraitController;
            }

            @Override
            public void markAnimeInfoLoaded() {
                isAnimeInfoLoaded = true;
            }

            @Override
            public void markEpisodeAndVoiceoversLoaded() {
                isEpisodeAndVoiceoversLoaded = true;
                checkAndDismissSplashScreen();
            }

            @Override
            public void showLoading(String message) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.showLoading(message);
                }
            }

            @Override
            public void hideLoading() {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.hideLoading();
                }
            }

            @Override
            public void updateControllerAutoHide() {
                VideoPlayerActivity.this.updateControllerAutoHide();
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }

            @Override
            public void finishActivity() {
                VideoPlayerActivity.this.finish();
            }
        };
    }

    private PlayerPortraitController.PortraitHost createPortraitHost() {
        return new PlayerPortraitController.PortraitHost() {
            @Override
            public Intent getIntent() {
                return VideoPlayerActivity.this.getIntent();
            }

            @Override
            public String getCurrentAnimeId() {
                return currentAnimeId;
            }

            @Override
            public String getAnimeUrl() {
                return animeUrl;
            }

            @Override
            public AnimeInfoResponse getCurrentAnimeInfo() {
                return currentAnimeInfo;
            }

            @Override
            public void setCurrentAnimeInfo(AnimeInfoResponse animeInfo) {
                currentAnimeInfo = animeInfo;
            }

            @Override
            public void setCurrentPosterUrl(String posterUrl) {
                currentPosterUrl = posterUrl;
            }

            @Override
            public ApiService getApiService() {
                return apiService;
            }

            @Override
            public EpisodesManager getEpisodesManager() {
                return episodesManager;
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public PlayerUIBinder getUiBinder() {
                return playerUIBinder;
            }

            @Override
            public PlayerOfflineController getOfflineController() {
                return playerOfflineController;
            }

            @Override
            public PlayerCommentsController getCommentsController() {
                return playerCommentsController;
            }

            @Override
            public PlayerAnimeInfoController getAnimeInfoController() {
                return playerAnimeInfoController;
            }

            @Override
            public PlayerChronologyController getChronologyController() {
                return playerChronologyController;
            }

            @Override
            public PlayerRelatedTitlesController getRelatedTitlesController() {
                return playerRelatedTitlesController;
            }

            @Override
            public PlayerSplashScreenController getSplashScreenController() {
                return playerSplashScreenController;
            }

            @Override
            public PlayerProgressController getProgressController() {
                return playerProgressController;
            }

            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public void markAnimeInfoLoaded() {
                isAnimeInfoLoaded = true;
                checkAndDismissSplashScreen();
            }

            @Override
            public void showDownloadBottomSheet() {
                VideoPlayerActivity.this.showDownloadBottomSheet();
            }

            @Override
            public void showDownloadProgressBottomSheet() {
                VideoPlayerActivity.this.showDownloadProgressBottomSheet();
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        };
    }

    private PlayerGesturesCoordinator.GesturesHost createGesturesHost() {
        return new PlayerGesturesCoordinator.GesturesHost() {
            @Override
            public Context getContext() {
                return VideoPlayerActivity.this;
            }

            @Override
            public ExoPlayer getPlayer() {
                return getActivePlayer();
            }

            @Override
            public PlayerView getPlayerView() {
                return playerUIBinder != null ? playerUIBinder.playerView : null;
            }

            @Override
            public EpisodesManager getEpisodesManager() {
                return episodesManager;
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public PlayerCommentsController getCommentsController() {
                return playerCommentsController;
            }

            @Override
            public PlayerRelatedTitlesController getRelatedTitlesController() {
                return playerRelatedTitlesController;
            }

            @Override
            public PlayerControlsOverlayManager getControlsOverlayManager() {
                return playerControlsOverlayManager;
            }

            @Override
            public PlayerUIBinder getUiBinder() {
                return playerUIBinder;
            }

            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public boolean isPlayingDownloadedVideo() {
                return VideoPlayerActivity.this.isPlayingDownloadedVideo();
            }

            @Override
            public boolean isCommentsDisabledInPlayer() {
                return VideoPlayerActivity.this.isCommentsDisabledInPlayer();
            }
        };
    }

    private PlayerControlsOverlayManager.ControlsCallback createControlsCallback() {
        return new PlayerControlsOverlayManager.ControlsCallback() {
            @Override
            public Player getPlayer() {
                return getActivePlayer();
            }

            @Override
            public PlayerView getPlayerView() {
                return playerUIBinder != null ? playerUIBinder.playerView : null;
            }

            @Override
            public EpisodesManager getEpisodesManager() {
                return episodesManager;
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public TimecodeManager getTimecodeManager() {
                return timecodeManager;
            }

            @Override
            public PlayerCommentsController getCommentsController() {
                return playerCommentsController;
            }

            @Override
            public PlayerNextEpisodeController getNextEpisodeController() {
                return playerNextEpisodeController;
            }

            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public boolean isPlayingDownloadedVideo() {
                return VideoPlayerActivity.this.isPlayingDownloadedVideo();
            }

            @Override
            public boolean isCommentsDisabledInPlayer() {
                return VideoPlayerActivity.this.isCommentsDisabledInPlayer();
            }

            @Override
            public int getLongSkipDuration() {
                return longSkipDuration;
            }

            @Override
            public boolean isAutoPlayEnabled() {
                return autoPlay;
            }

            @Override
            public boolean isAutoPlayOnPrepare() {
                return autoPlayOnPrepare;
            }

            @Override
            public void startBufferingMonitoring() {
                if (playerQualityController != null) {
                    playerQualityController.startBufferingMonitoring();
                }
            }

            @Override
            public void startViewProgressTracking() {
                if (playerProgressController != null) {
                    playerProgressController.startViewProgressTracking();
                }
            }

            @Override
            public void stopViewProgressTracking() {
                if (playerProgressController != null) {
                    playerProgressController.capturePlaybackSnapshot();
                    playerProgressController.stopViewProgressTracking();
                }
            }

            @Override
            public void updateSettingsQualityTag() {
                if (playerQualityController != null) {
                    playerQualityController.updateSettingsQualityTag();
                }
            }

            @Override
            public void ensureAnimeTitleSet() {
                if (playerPortraitController != null) {
                    playerPortraitController.ensureAnimeTitleSet();
                }
            }

            @Override
            public void openSettingsDialog() {
                showSettingsDialog();
            }

            @Override
            public void toggleOrientation() {
                if (playerOrientationController != null) {
                    playerOrientationController.toggleFullscreenOrientation();
                }
            }

            @Override
            public void enterPipMode() {
                if (playerProgressController != null) {
                    playerProgressController.autoSaveBookmark();
                }
                if (playerPipController != null) {
                    playerPipController.enterPictureInPictureMode();
                }
            }

            @Override
            public void closePlayer() {
                if (playerProgressController != null) {
                    playerProgressController.autoSaveBookmark();
                }
                finish();
            }

            @Override
            public void toggleEpisodesMenu() {
                if (episodesManager != null) {
                    episodesManager.toggleEpisodesMenu();
                }
            }

            @Override
            public void togglePlayersMenu() {
                if (isOfflineMode() || isPlayingDownloadedVideo()) {
                    CustomToast.showInfo(VideoPlayerActivity.this, "Выбор озвучки недоступен для скачанного файла");
                    return;
                }
                if (playersManager != null) {
                    playersManager.toggleMenu();
                }
            }

            @Override
            public void showDownloadBottomSheet() {
                VideoPlayerActivity.this.showDownloadBottomSheet();
            }

            @Override
            public void showDownloadProgressBottomSheet() {
                VideoPlayerActivity.this.showDownloadProgressBottomSheet();
            }

            @Override
            public void addBookmark() {
                if (playerProgressController != null) {
                    playerProgressController.addBookmark();
                }
            }

            @Override
            public void onPipParamsUpdateNeeded() {
                if (playerPipController != null && !isClosingOrFinishing && !isFinishing() && !isDestroyed()) {
                    playerPipController.updatePictureInPictureParams();
                }
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        };
    }

    private PlayerPlaybackController.PlaybackCallback createPlaybackCallback() {
        return new PlayerPlaybackController.PlaybackCallback() {
            @Override
            public Context getPlayerContext() {
                return VideoPlayerActivity.this.getPlayerContext();
            }

            @Override
            public PlayerAudioController getPlayerAudioController() {
                return playerAudioController;
            }

            @Override
            public AmbientLightManager getAmbientLightManager() {
                return ambientLightManager;
            }

            @Override
            public GesturesManager getGesturesManager() {
                return gesturesManager;
            }

            @Override
            public TimecodeManager getTimecodeManager() {
                return timecodeManager;
            }

            @Override
            public DefaultHttpDataSource.Factory getHttpDataSourceFactory() {
                return httpDataSourceFactory;
            }

            @Override
            public ApiService getApiService() {
                return apiService;
            }

            @Override
            public void onPlayerAttached(ExoPlayer attachedPlayer) {
                if (playerSubtitlesController != null) {
                    playerSubtitlesController.setupSubtitlePlayerListener(attachedPlayer);
                    playerSubtitlesController.applySubtitlesStateToPlayer();
                }
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.setupPlayerControlButtons();
                }
                if (playerProgressController != null) {
                    playerProgressController.capturePlaybackSnapshot();
                }
            }

            @Override
            public void onFirstFrameRendered() {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.onFirstFrameRendered();
                    playerControlsOverlayManager.hideLoading();
                    playerControlsOverlayManager.updatePlayPauseAndLoadingState(false);
                }
                if (playerProgressController != null) {
                    playerProgressController.onPlaybackReady();
                }
                if (playerVideoResolverController != null) {
                    playerVideoResolverController.onPlaybackStartedSuccessfully();
                }
                if (playerNextEpisodeController != null) {
                    playerNextEpisodeController.scheduleNextEpisodePrefetch();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state, boolean playWhenReady) {
                if (state == Player.STATE_READY && playerProgressController != null) {
                    playerProgressController.onPlaybackReady();
                }
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.onPlaybackStateChanged(state);
                }
            }

            @Override
            public void onPositionDiscontinuity(int reason) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.onPositionDiscontinuity(reason);
                }
            }

            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.onIsLoadingChanged(isLoading);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.onIsPlayingChanged(isPlaying);
                }
            }

            @Override
            public void onEvents() {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.updatePlayPauseAndLoadingState(true);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.onPlayerErrorUi();
                }
                if (playerVideoResolverController != null) {
                    playerVideoResolverController.handlePlaybackError(error);
                }
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                String detected = AutoQualityHelper.formatVideoDimensionsToQuality(videoSize.width, videoSize.height);
                if (detected != null && playerQualityController != null) {
                    playerQualityController.onActiveQualityResolved(detected);
                }
            }

            @Override
            public void onSeamlessSwitchTimedOutOrFailed() {
                if (playerQualityController != null) {
                    playerQualityController.restartPlayerWithNewQuality();
                }
            }

            @Override
            public void onSeamlessSwitchCompleted(String newQuality, String newVideoUrl) {
                if (playerQualityController != null) {
                    playerQualityController.setCurrentVideoUrl(newVideoUrl);
                    if (AutoQualityHelper.isDownloadedQuality(newQuality)) {
                        playerQualityController.setUsingDownloadedSource(true);
                        DownloadedEpisodeEntity dlEp = playerOfflineController != null
                                ? playerOfflineController.getDownloadedEpisodeForActive() : null;
                        if (dlEp != null && dlEp.getQuality() != null) {
                            playerQualityController.setActivePlayingQuality(
                                    AutoQualityHelper.normalizeResolutionLabel(dlEp.getQuality()));
                        }
                    } else {
                        playerQualityController.setUsingDownloadedSource(false);
                        String resolved = AutoQualityHelper.normalizeResolutionLabel(newQuality);
                        if (resolved == null || resolved.isEmpty()) {
                            ExoPlayer active = getActivePlayer();
                            if (active != null && active.getVideoFormat() != null) {
                                resolved = AutoQualityHelper.formatVideoDimensionsToQuality(
                                        active.getVideoFormat().width, active.getVideoFormat().height);
                            }
                            if (resolved == null) {
                                resolved = PlayerMetadataHelper.extractResolutionFromUrl(newVideoUrl);
                            }
                        }
                        if (resolved != null && !resolved.isEmpty()) {
                            playerQualityController.setActivePlayingQuality(resolved);
                        }
                    }
                    playerQualityController.refreshQualityUiState();
                }
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.setVideoLoading(false);
                    playerControlsOverlayManager.setHasRenderedFirstFrame(true);
                    playerControlsOverlayManager.hideLoading();
                    playerControlsOverlayManager.updatePlayPauseAndLoadingState(true);
                }
                if (playerPortraitController != null) {
                    playerPortraitController.updateEpisodeHeaderQuick();
                }
                if (playerNextEpisodeController != null) {
                    playerNextEpisodeController.scheduleNextEpisodePrefetch();
                }
                if (playerProgressController != null) {
                    playerProgressController.startViewProgressTracking();
                }
            }

            @Override
            public boolean isActivityFinishingOrDestroyed() {
                return isFinishing() || isDestroyed();
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        };
    }

    private PlayerOrientationController.OrientationLayoutHost createOrientationLayoutHost() {
        return new PlayerOrientationController.OrientationLayoutHost() {
            @Override
            public PlayerUIBinder getUiBinder() {
                return playerUIBinder;
            }

            @Override
            public PlayerPanelsController getPanelsController() {
                return playerPanelsController;
            }

            @Override
            public PlayerCommentsController getCommentsController() {
                return playerCommentsController;
            }

            @Override
            public PlayerAnimeInfoController getAnimeInfoController() {
                return playerAnimeInfoController;
            }

            @Override
            public PlayerRelatedTitlesController getRelatedTitlesController() {
                return playerRelatedTitlesController;
            }

            @Override
            public PlayerDialogsController getDialogsController() {
                return playerDialogsController;
            }

            @Override
            public PlayerLockController getLockController() {
                return playerLockController;
            }

            @Override
            public PlayerDownloadController getDownloadController() {
                return playerDownloadController;
            }

            @Override
            public PlayerPortraitController getPortraitController() {
                return playerPortraitController;
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public EpisodesManager getEpisodesManager() {
                return episodesManager;
            }

            @Override
            public GesturesManager getGesturesManager() {
                return gesturesManager;
            }

            @Override
            public VerticalGesturesManager getVerticalGesturesManager() {
                return verticalGesturesManager;
            }

            @Override
            public TimecodeManager getTimecodeManager() {
                return timecodeManager;
            }

            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public boolean isPlayingDownloadedVideo() {
                return VideoPlayerActivity.this.isPlayingDownloadedVideo();
            }

            @Override
            public String getCurrentAnimeId() {
                return currentAnimeId;
            }

            @Override
            public String getAnimeUrl() {
                return animeUrl;
            }
        };
    }

    private PlayerQualityController.QualityHost createQualityHost() {
        return new PlayerQualityController.QualityHost() {
            @Override
            public Context getContext() {
                return VideoPlayerActivity.this;
            }

            @Override
            public ApiService getApiService() {
                return apiService;
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public EpisodesManager getEpisodesManager() {
                return episodesManager;
            }

            @Override
            public TimecodeManager getTimecodeManager() {
                return timecodeManager;
            }

            @Override
            public PlayerPlaybackController getPlaybackController() {
                return playerPlaybackController;
            }

            @Override
            public PlayerControlsOverlayManager getControlsOverlayManager() {
                return playerControlsOverlayManager;
            }

            @Override
            public PlayerDialogsController getDialogsController() {
                return playerDialogsController;
            }

            @Override
            public PlayerDownloadController getDownloadController() {
                return playerDownloadController;
            }

            @Override
            public PlayerOfflineController getOfflineController() {
                return playerOfflineController;
            }

            @Override
            public PlayerVideoResolverController getVideoResolverController() {
                return playerVideoResolverController;
            }

            @Override
            public PlayerPanelsController getPanelsController() {
                return playerPanelsController;
            }

            @Override
            public PlayerSubtitlesController getSubtitlesController() {
                return playerSubtitlesController;
            }

            @Override
            public PlayerPortraitController getPortraitController() {
                return playerPortraitController;
            }

            @Override
            public PlayerUIBinder getUiBinder() {
                return playerUIBinder;
            }

            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode();
            }

            @Override
            public boolean isPlayingDownloadedVideo() {
                return VideoPlayerActivity.this.isPlayingDownloadedVideo();
            }

            @Override
            public boolean isFinishingOrDestroyed() {
                return isFinishing() || isDestroyed();
            }

            @Override
            public void showLoading(String message) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.showLoading(message);
                }
            }

            @Override
            public void hideLoading() {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.hideLoading();
                }
            }

            @Override
            public void onEpisodeHeaderUpdateQuick() {
                if (playerPortraitController != null) {
                    playerPortraitController.updateEpisodeHeaderQuick();
                }
            }

            @Override
            public void onAnimeInfoHeaderFull() {
                if (playerPortraitController != null) {
                    playerPortraitController.updateAnimeInfoHeaderFull();
                }
            }

            @Override
            public void stopCurrentPlaybackAndRestartVoiceover(EpisodeResponse.PlayerData playerData) {
                if (playerVideoResolverController != null) {
                    playerVideoResolverController.stopCurrentPlaybackAndRestartVoiceover(playerData);
                }
            }

            @Override
            public void setSavedPlayerPosition(long pos) {
                if (playerEpisodesController != null) {
                    playerEpisodesController.setSavedPlayerPosition(pos);
                }
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        };
    }

    private PlayerVideoResolverController.ResolverProvider createResolverProvider() {
        return new PlayerVideoResolverController.ResolverProvider() {
            @Override
            public Context getContext() {
                return VideoPlayerActivity.this;
            }

            @Override
            public boolean isDownloadedQuality(String quality) {
                return playerDownloadController != null && playerDownloadController.isDownloadedQuality(quality);
            }

            @Override
            public boolean shouldPlayDownloadedFile() {
                return isPlayingDownloadedVideo();
            }

            @Override
            public DownloadedEpisodeEntity getDownloadedEpisodeForActive() {
                return playerOfflineController != null ? playerOfflineController.getDownloadedEpisodeForActive() : null;
            }

            @Override
            public void setCurrentVideoUrl(String url) {
                if (playerQualityController != null) {
                    playerQualityController.setCurrentVideoUrl(url);
                }
            }

            @Override
            public void onActiveQualityResolved(String resolvedQuality) {
                if (playerQualityController != null) {
                    playerQualityController.onActiveQualityResolved(resolvedQuality);
                }
            }

            @Override
            public String getPreferredQuality() {
                return playerQualityController != null ? playerQualityController.getPreferredQuality() : "Авто";
            }

            @Override
            public void setPreferredQuality(String quality) {
                if (playerQualityController != null) {
                    playerQualityController.setPreferredQuality(quality);
                    safeRunOnUiThread(playerQualityController::refreshQualityUiState);
                }
            }

            @Override
            public boolean isEnable4K() {
                return playerQualityController != null && playerQualityController.isEnable4K();
            }

            @Override
            public String getCurrentVideoDomain() {
                return playerQualityController != null
                        ? playerQualityController.getCurrentVideoDomain() : com.example.animelib.ui.VideoUrlHelper.DOMAIN_MAIN;
            }

            @Override
            public void showLoading(String message) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.showLoading(message);
                }
            }

            @Override
            public void hideLoading() {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.hideLoading();
                }
            }

            @Override
            public void showVideoErrorDialog(String title, String message, Runnable retryAction) {
                if (playerDialogsController != null) {
                    playerDialogsController.showVideoErrorDialog(title, message, retryAction);
                }
            }

            @Override
            public void initializePlayer() {
                long startPos = playerEpisodesController != null ? playerEpisodesController.consumeStartPosition() : 0L;
                initializePlayer(startPos);
            }

            @Override
            public void initializePlayer(long startPosition) {
                String url = playerQualityController != null ? playerQualityController.getCurrentVideoUrl() : null;
                if (url == null || playerPlaybackController == null || playerSubtitlesController == null) return;
                playerPlaybackController.initializePlayer(
                        url,
                        playerSubtitlesController.createMediaItemWithSubtitles(url),
                        playerPlaybackController.getCurrentResizeMode(),
                        autoPlayOnPrepare,
                        startPosition
                );
                autoPlayOnPrepare = true;
            }

            @Override
            public ExoPlayer getPlayer() {
                return getActivePlayer();
            }

            @Override
            public View getMenuLoadingOverlay() {
                return playerUIBinder != null ? playerUIBinder.menuLoadingOverlay : null;
            }

            @Override
            public void setVideoLoading(boolean loading) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.setVideoLoading(loading);
                }
            }

            @Override
            public void setHasRenderedFirstFrame(boolean rendered) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.setHasRenderedFirstFrame(rendered);
                }
            }

            @Override
            public void updatePlayPauseAndLoadingState(boolean animate) {
                if (playerControlsOverlayManager != null) {
                    playerControlsOverlayManager.updatePlayPauseAndLoadingState(animate);
                }
            }

            @Override
            public void setCurrentKodikResponse(KodikResponse response) {
                if (playerQualityController != null) {
                    playerQualityController.setCurrentKodikResponse(response);
                }
                if (playersManager != null) {
                    playersManager.setCurrentKodikResponse(response);
                }
            }

            @Override
            public void initializeHlsPlayer(String hlsUrl) {
                long startPos = playerEpisodesController != null ? playerEpisodesController.consumeStartPosition() : 0L;
                initializeHlsPlayer(hlsUrl, startPos);
            }

            @Override
            public void initializeHlsPlayer(String hlsUrl, long startPosition) {
                if (hlsUrl == null || playerPlaybackController == null || playerSubtitlesController == null) return;
                playerPlaybackController.initializeHlsPlayer(
                        hlsUrl,
                        playerSubtitlesController.createMediaItemWithSubtitles(hlsUrl),
                        playerPlaybackController.getCurrentResizeMode(),
                        autoPlayOnPrepare,
                        startPosition
                );
                autoPlayOnPrepare = true;
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }

            @Override
            public void onPlayerSelected(EpisodeResponse.PlayerData playerData) {
                if (playerVideoResolverController != null) {
                    playerVideoResolverController.onPlayerSelected(playerData);
                }
            }
        };
    }

    private PlayerVideoResolverController.ResolverHost createResolverHost() {
        return new PlayerVideoResolverController.ResolverHost() {
            @Override
            public String getCurrentAnimeId() {
                return currentAnimeId;
            }

            @Override
            public boolean isAutoPlayEnabled() {
                return autoPlay;
            }

            @Override
            public void setAutoPlayOnPrepare(boolean value) {
                autoPlayOnPrepare = value;
            }

            @Override
            public EpisodesManager getEpisodesManager() {
                return episodesManager;
            }

            @Override
            public PlayerEpisodesController getEpisodesController() {
                return playerEpisodesController;
            }

            @Override
            public PlayerQualityController getQualityController() {
                return playerQualityController;
            }

            @Override
            public PlayerPlaybackController getPlaybackController() {
                return playerPlaybackController;
            }

            @Override
            public PlayerProgressController getProgressController() {
                return playerProgressController;
            }

            @Override
            public PlayerDialogsController getDialogsController() {
                return playerDialogsController;
            }

            @Override
            public PlayerPanelsController getPanelsController() {
                return playerPanelsController;
            }

            @Override
            public PlayerOfflineController getOfflineController() {
                return playerOfflineController;
            }

            @Override
            public PlayerPortraitController getPortraitController() {
                return playerPortraitController;
            }

            @Override
            public PlayerAnimeInfoController getAnimeInfoController() {
                return playerAnimeInfoController;
            }

            @Override
            public void markEpisodeAndVoiceoversLoaded() {
                isEpisodeAndVoiceoversLoaded = true;
                checkAndDismissSplashScreen();
            }

            @Override
            public void onEpisodeHeaderUpdateQuick() {
                if (playerPortraitController != null) {
                    playerPortraitController.updateEpisodeHeaderQuick();
                }
            }

            @Override
            public void onAnimeInfoHeaderFull() {
                if (playerPortraitController != null) {
                    playerPortraitController.updateAnimeInfoHeaderFull();
                }
            }

            @Override
            public void switchMediaSource(String newVideoUrl, long startPosition, boolean playWhenReady) {
                if (playerQualityController != null) {
                    playerQualityController.switchMediaSourceInternal(newVideoUrl, startPosition, playWhenReady);
                }
            }
        };
    }
}
