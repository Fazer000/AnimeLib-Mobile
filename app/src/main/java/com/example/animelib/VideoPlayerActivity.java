package com.example.animelib;

import com.example.animelib.controllers.PlayerApiController;
import com.example.animelib.controllers.PlayerAudioController;
import com.example.animelib.controllers.PlayerCommentsController;
import com.example.animelib.controllers.PlayerControlsOverlayManager;
import com.example.animelib.controllers.PlayerDialogsController;
import com.example.animelib.controllers.PlayerFiltersController;
import com.example.animelib.controllers.PlayerPanelsController;
import com.example.animelib.controllers.PlayerPipController;
import com.example.animelib.controllers.PlayerSubtitlesController;


import java.io.File;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Bitmap;
import com.example.animelib.util.CustomTypefaceSpan;
import com.example.animelib.util.FontResolver;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.SkeletonHelper;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.text.style.ReplacementSpan;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Canvas;
import android.graphics.Paint;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.C;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.example.animelib.models.AnimeInfoResponse;

import com.example.animelib.api.ApiService;
import com.example.animelib.settings.SettingsBottomSheet;
import com.example.animelib.managers.BookmarkManager;
import com.example.animelib.managers.CommentsManager;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.managers.VerticalGesturesManager;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.managers.AmbientLightManager;
import com.example.animelib.adapters.HorizontalRelatedTitlesAdapter;
import com.example.animelib.managers.RelatedTitlesManager;
import com.example.animelib.util.ThemeUtils;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.models.RelatedTitlesResponse;
import com.example.animelib.services.DownloadService;
import com.example.animelib.settings.QualityBottomSheet;
import com.example.animelib.ui.TitleWebViewBottomSheet;
import com.example.animelib.ui.VideoUrlHelper;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;


@UnstableApi
public class VideoPlayerActivity extends AppCompatActivity {
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_ANIME_URL = "anime_url";

    private PlayerView playerView;
    private ExoPlayer player;
    private View loadingOverlay;
    private androidx.media3.ui.DefaultTimeBar timeBar;
    private View controllerView;

    private String currentVideoUrl;
    private String animeUrl;
    private boolean isNavigatingToAnimePage = false;
    private Map<String, String> videoQualities;

    private ExecutorService executor;
    private DefaultHttpDataSource.Factory httpDataSourceFactory;
    private ApiService apiService;

    // Menu components
    private ImageButton ibClosePlayer;
    private ImageButton menuToggleButton;
    private ImageButton settingsButton;
    private TextView settingsQualityTag;
    private ImageButton menuToggleFullscreen;
    private LinearLayout slidingMenuPanel;
    private View menuLoadingIndicator;
    private View menuLoadingOverlay;
    
    // Draggable panels
    private FrameLayout playerContainer;
    private float currentCornerRadiusPx = 0f;
    private float currentOutlineLeft = 0f;
    private float currentOutlineTop = 0f;
    private float currentOutlineRight = 0f;
    private float currentOutlineBottom = 0f;
    private com.example.animelib.ui.DraggableSidePanel menuPanelContainer;
    private com.example.animelib.ui.DraggableSidePanel commentsPanelContainer;
    
    private boolean hasShownInitialAnimeInfo = false;
    private String currentPosterUrl = "";
    
    // UI components for managers
    private View menuOverlay;
    private ImageButton closeMenuButton;
    private View commentsPanel;
    private ImageButton closeCommentsButton;
    private RecyclerView commentsRecyclerView;
    private View commentsLoadingOverlay;
    private View commentsOptionsButton;
    private TextView emptyCommentsText;
    private TextView seekPreviewText;
    private TextView holdSpeedToast;
    private ImageButton pipButton;
    private RecyclerView episodesHorizontalRecyclerView;
    private ImageButton commentsButton;
    private ImageButton bookmarkButton;
    private View skipIndicatorLeft;
    private View skipIndicatorRight;
    private View playerBufferingIndicator;
    private volatile boolean isVideoLoading = false;
    private volatile boolean hasRenderedFirstFrame = false;
    private volatile boolean isSeeking = false;
    private volatile boolean isScrubbingTimeBar = false;
    private int currentControlState = -1; // -1 = uninitialized, 0 = play, 1 = pause, 2 = loading
    private final Handler seekResetHandler = new Handler(Looper.getMainLooper());
    private Player.Listener playerEventListener;

    // Comments manager
    private CommentsManager commentsManager;
    private com.example.animelib.data.DatabaseManager databaseManager;
    private String currentVideoDomain = VideoUrlHelper.DOMAIN_MAIN;

    // Orientation listener for autorotation
    private android.view.OrientationEventListener orientationEventListener;
    private int lastPhysicalOrientation = -1;
    private boolean manualOrientationOverride = false;

    // Episodes manager
    private EpisodesManager episodesManager;
    
    // Episodes UI components (for EpisodesManager)
    private ImageButton episodesMenuButton;
    
    // Related titles components
    private FrameLayout relatedTitlesOverlay;
    private View relatedTitlesDimOverlay;
    private RecyclerView relatedTitlesRecyclerView;

    // Players manager
    private PlayersManager playersManager;
    
    // Gestures manager
    private GesturesManager gesturesManager;
    private VerticalGesturesManager verticalGesturesManager;

    // Unified gesture indicator plaque (Brightness & Volume)
    private View gestureIndicatorPlaque;
    private ImageView gestureIndicatorIcon;
    private ProgressBar gestureIndicatorProgress;
    private TextView gestureIndicatorText;
    private final Handler gestureIndicatorHandler = new Handler(Looper.getMainLooper());
    private Runnable hideGestureIndicatorsRunnable;
    
    // Timecode manager
    private TimecodeManager timecodeManager;

    // View progress tracking (60% threshold)
    private boolean isCurrentEpisodeMarkedViewed = false;
    private final Handler viewProgressHandler = new Handler(Looper.getMainLooper());
    private Runnable viewProgressRunnable;
    
    // Ambient light manager
    private AmbientLightManager ambientLightManager;
    private com.example.animelib.ui.AmbientVignetteOverlayView ambientVignetteOverlay;
    private float lastCornerRadiusPx = -1f;

    // Video filters manager
    private com.example.animelib.managers.VideoFiltersManager videoFiltersManager;
    private float filterBrightness = 0f;
    private float filterContrast = 100f;
    private float filterSaturation = 100f;
    private float filterGamma = 1.0f;
    private float filterHue = 0f;

    // Controllers
    private PlayerApiController playerApiController;
    private PlayerCommentsController playerCommentsController;
    private PlayerAudioController playerAudioController;
    private PlayerPipController playerPipController;
    private PlayerPanelsController playerPanelsController;
    private PlayerControlsOverlayManager playerControlsOverlayManager;
    private PlayerSubtitlesController playerSubtitlesController;
    private PlayerFiltersController playerFiltersController;
    private com.example.animelib.controllers.PlayerNextEpisodeController playerNextEpisodeController;
    private com.example.animelib.controllers.PlayerOrientationController playerOrientationController;
    private com.example.animelib.controllers.PlayerAnimeInfoController playerAnimeInfoController;
    private com.example.animelib.controllers.PlayerDownloadController playerDownloadController;
    private com.example.animelib.controllers.PlayerPlaybackController playerPlaybackController;
    private com.example.animelib.controllers.PlayerProgressController playerProgressController;
    private com.example.animelib.controllers.PlayerQualityController playerQualityController;
    private com.example.animelib.controllers.PlayerDialogsController playerDialogsController;
    private com.example.animelib.controllers.PlayerUIBinder playerUIBinder;
    private com.example.animelib.controllers.PlayerRelatedTitlesController playerRelatedTitlesController;
    private com.example.animelib.controllers.PlayerEpisodesController playerEpisodesController;
    private com.example.animelib.controllers.PlayerVideoResolverController playerVideoResolverController;

    // Subtitle settings
    private boolean subtitlesEnabled = true;
    private String subtitleFormat = "ass";
    private float subtitleTextSize = 18f;
    private int subtitleTextColor = 0xFFFFFFFF;
    private int subtitleBackgroundColor = 0x00000000;
    private int subtitleEdgeType = androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE;
    private int subtitleEdgeColor = 0xFF000000;

    private boolean wasCommentsVisibleBeforePiP = false;
    private boolean wasPlayingBeforeBackground = false;

    // Buffering monitor for MP4 and HLS streams
    private final Handler bufferingMonitorHandler = new Handler(Looper.getMainLooper());
    private long bufferingStartTimeMs = 0;
    private boolean isAutoQualityDowngrading = false;

    private void checkAutoQualityDowngradeOnStalling() {
        if (isAutoQualityDowngrading || !com.example.animelib.util.AutoQualityHelper.isAutoQuality(preferredQuality) || playersManager == null) {
            return;
        }

        List<String> available = playersManager.getAvailableQualities();
        if (available == null || available.isEmpty()) return;

        EpisodeResponse.PlayerData currentPlayerData = playersManager.getCurrentPlayerData();
        if (currentPlayerData == null) return;

        int currentRes = 1080;
        if (currentVideoUrl != null) {
            if (currentVideoUrl.contains("360")) currentRes = 360;
            else if (currentVideoUrl.contains("480")) currentRes = 480;
            else if (currentVideoUrl.contains("720")) currentRes = 720;
            else if (currentVideoUrl.contains("1080")) currentRes = 1080;
            else if (currentVideoUrl.contains("2160")) currentRes = 2160;
        }

        int targetRes = 360;
        if (currentRes > 1080) targetRes = 720;
        else if (currentRes > 720) targetRes = 480;
        else if (currentRes > 480) targetRes = 360;
        else targetRes = 360;

        String lowerQuality = null;
        for (String q : available) {
            if (com.example.animelib.util.AutoQualityHelper.isAutoQuality(q) || com.example.animelib.util.AutoQualityHelper.isDownloadedQuality(q)) continue;
            int res = com.example.animelib.util.AutoQualityHelper.extractResolution(q);
            if (res > 0 && res <= targetRes) {
                if (lowerQuality == null || res > com.example.animelib.util.AutoQualityHelper.extractResolution(lowerQuality)) {
                    lowerQuality = q;
                }
            }
        }

        if (lowerQuality != null) {
            String newVideoUrl = resolveUrlForQuality(currentPlayerData, lowerQuality);
            if (newVideoUrl != null && !newVideoUrl.equals(currentVideoUrl)) {
                isAutoQualityDowngrading = true;
                bufferingStartTimeMs = 0;
                Log.w("VideoPlayer", "Auto Quality: Network stalling detected! Downgrading to " + lowerQuality);
                changeQuality(lowerQuality, preferredQuality);
            }
        }
    }

    private final Runnable bufferingMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && !isFinishing()) {
                updatePlayPauseAndLoadingState(false);

                boolean isBuffering = player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING;
                if (isBuffering) {
                    if (bufferingStartTimeMs == 0) {
                        bufferingStartTimeMs = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - bufferingStartTimeMs > 3500) {
                        checkAutoQualityDowngradeOnStalling();
                    }
                } else {
                    bufferingStartTimeMs = 0;
                    isAutoQualityDowngrading = false;
                }

                if (player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED) {
                    bufferingMonitorHandler.postDelayed(this, 800);
                }
            }
        }
    };

    private void startBufferingMonitoring() {
        bufferingMonitorHandler.removeCallbacks(bufferingMonitorRunnable);
        bufferingMonitorHandler.post(bufferingMonitorRunnable);
    }

    private void stopBufferingMonitoring() {
        bufferingMonitorHandler.removeCallbacks(bufferingMonitorRunnable);
    }

    // Episode navigation buttons (for EpisodesManager)
    private ImageButton prevEpisodeButton;
    private ImageButton nextEpisodeButton;
    
    // Other UI components
    private View playersControlBar;
    private TextView animeTitleView;
    private TextView currentEpisodeNumberView;
    private TextView currentTeamName;
    private TextView currentEpisodeName;

    // Controller visibility state
    private boolean isControllerVisible = false;
    
    // Fullscreen state
    private boolean isFullscreenMode = false;
    private int currentResizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
    private float lastValidAspectRatio = 16f / 9f;

    private void preservePlayerAspectRatio() {
        if (playerView != null) {
            androidx.media3.ui.AspectRatioFrameLayout contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
            if (contentFrame != null && lastValidAspectRatio > 0f) {
                contentFrame.setAspectRatio(lastValidAspectRatio);
            }
        }
    }

    // Player data is now managed by PlayersManager
    private boolean isOfflineMode = false;
    private boolean isFirstOfflineLaunch = true;
    private final java.util.Map<String, com.example.animelib.data.entity.DownloadedEpisodeEntity> offlineEpisodesMap = new java.util.HashMap<>();
    private KodikResponse currentKodikResponse;
    private String currentAnimeId;
    private AnimeInfoResponse currentAnimeInfo;
    private long bookmarkTimecode = 0; // Таймкод из закладки в миллисекундах
    private long savedPlayerPosition = 0; // Сохраненная позиция при смене плеера
    private boolean autoBookmarkSaved = false; // Флаг для предотвращения дублирования автосохранения

    // Portrait UI components
    private View portraitVoiceoverPlayerButton;
    private TextView tvPortraitVoiceover;
    private TextView tvPortraitPlayer;
    private ImageView ivPortraitVoiceoverChevron;
    private ImageButton portraitBookmarkButton;
    private ImageButton portraitDownloadButton;
    private View portraitStatusDropdownButton;
    private ImageView ivPortraitStatusBookmarkIcon;
    private TextView tvPortraitStatus;
    private Object currentWatchStatusId = null;
    private RecyclerView portraitEpisodesRecyclerView;
    private TextView tvPortraitAnimeTitle;
    private TextView tvPortraitEpisodeTitle;
    private View btnPortraitSortComments;
    private TextView tvPortraitSortComments;
    private View btnPortraitCommentRules;
    private EditText portraitCommentInputField;
    private RecyclerView portraitCommentsRecyclerView;
    private TextView tvPortraitEmptyComments;
    private View portraitCommentsLoadingOverlay;
    private androidx.core.widget.NestedScrollView portraitScrollView;
    private FloatingActionButton btnPortraitScrollToTop;
    private View portraitDownloadProgressContainer;
    private View portraitDownloadProgressCard;
    private TextView tvPortraitDownloadPercent;
    private View portraitRelatedTitlesContainer;
    private RecyclerView portraitRelatedTitlesRecyclerView;
    private HorizontalRelatedTitlesAdapter portraitRelatedTitlesAdapter;

    // User preferences are now managed by PlayersManager
    private String preferredQuality = "Авто";
    private ImageButton downloadButton;
    private ImageButton downloadButtonTop;
    private ImageButton btnDownloadFromMenu;
    private TextView downloadProgressText;
    private boolean isDownloading = false;
    private int currentDownloadPercent = 0;
    private boolean enable4K = false;
    private boolean enableAmbientLight = true;
    private boolean autoPlay = true;
    private int longSkipDuration = 85; // seconds
    private int currentTheme = ThemeUtils.THEME_SYSTEM;
    private boolean isNewEpisodeSelection = true;
    private boolean autoPlayOnPrepare = true;

    // Menu state
    // isMenuVisible is now managed by PlayersManager
    private int menuWidth = 300; // dp

    private final int controllerShowTimeoutMs = 4000;
    private boolean shouldAutoHideControls = true; // Контроль автоматического скрытия

    public static void startFromAnimePage(Context context, String animeUrl) {
        Intent intent = new Intent(context, VideoPlayerActivity.class);
        intent.putExtra(EXTRA_ANIME_URL, animeUrl);
        if (context instanceof VideoPlayerActivity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // Save bookmark and latest-view for the previous anime before switching
        autoSaveBookmark();

        setIntent(intent);

        // Stop current player if active
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Exception e) {
                Log.w("VideoPlayer", "Error stopping player in onNewIntent", e);
            }
        }

        // Process new intent data
        String localFilePath = intent.getStringExtra("EXTRA_LOCAL_FILE_PATH");
        currentVideoUrl = intent.getStringExtra(EXTRA_VIDEO_URL);
        animeUrl = intent.getStringExtra(EXTRA_ANIME_URL);

        // Reset anime-specific metadata and state
        currentAnimeInfo = null;
        autoBookmarkSaved = false;
        bookmarkTimecode = 0;
        savedPlayerPosition = 0;

        if (playerAnimeInfoController != null) {
            playerAnimeInfoController.collapsePortraitInfo();
            playerAnimeInfoController.showSkeletons();
        }
        if (animeTitleView != null) {
            SkeletonHelper.showSkeleton(animeTitleView, 160);
            updatePortraitHeaderTitlesUI();
        }
        if (playerCommentsController != null) {
            playerCommentsController.resetCommentsOnEpisodeChange(true);
        }

        if (localFilePath != null) {
            isOfflineMode = true;
            String animeTitle = intent.getStringExtra("EXTRA_ANIME_TITLE");
            String episodeTitle = intent.getStringExtra("EXTRA_EPISODE_TITLE");
            playOfflineFile(localFilePath, animeTitle, episodeTitle);
        } else if (currentVideoUrl != null) {
            initializePlayer();
        } else if (animeUrl != null) {
            showLoading("Загрузка видео...");
            loadAnimeFromUrl(animeUrl);
        }
    }

    public static void startForOfflineEpisode(Context context, String localFilePath, String animeTitle, String episodeTitle, String animeId, String episodeNumber) {
        Intent intent = new Intent(context, VideoPlayerActivity.class);
        intent.putExtra("EXTRA_LOCAL_FILE_PATH", localFilePath);
        intent.putExtra("EXTRA_ANIME_TITLE", animeTitle);
        intent.putExtra("EXTRA_EPISODE_TITLE", episodeTitle);
        intent.putExtra("EXTRA_ANIME_ID", animeId);
        intent.putExtra("EXTRA_EPISODE_NUMBER", episodeNumber);
        context.startActivity(intent);
    }

    public static void startForOfflineEpisode(Context context, String localFilePath, String animeTitle, String episodeTitle) {
        startForOfflineEpisode(context, localFilePath, animeTitle, episodeTitle, null, null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_AnimeLIB_VideoPlayer);
        setContentView(R.layout.activity_video_player);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.playerView);
        playerContainer = findViewById(R.id.playerContainer);
        if (playerContainer != null) {
            playerContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            playerContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    boolean isPortrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
                    if (isPortrait) {
                        applyPlayerSidePanelTransform(0f);
                    }
                }
            });
        }
        loadingOverlay = findViewById(R.id.loadingOverlay);

        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.setFitsSystemWindows(false);
        }

        updateControllerAutoHide();

        playerView.setControllerAnimationEnabled(false);

        executor = Executors.newSingleThreadExecutor();
        playerApiController = new PlayerApiController(this);
        apiService = playerApiController.getApiService();

        // Initialize HTTP data source with full headers matching API/comments requests
        httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(apiService.getVideoRequestHeaders());

        loadAndApplyTheme();

        playerCommentsController = new PlayerCommentsController(this, apiService);
        commentsManager = playerCommentsController.getCommentsManager();

        episodesManager = new EpisodesManager(this, apiService);
        episodesManager.setOnBookmarkLoadedListener(bookmarkData -> {
            if (bookmarkData != null) {
                currentWatchStatusId = bookmarkData.getStatus();
                updatePortraitWatchStatusUI(currentWatchStatusId);
            }
        });

        episodesManager.setPlayerControlsCallback(shouldAutoHide -> {
            shouldAutoHideControls = shouldAutoHide;
            updateControllerAutoHide();
        });

        playersManager = new PlayersManager(this, apiService);

        gesturesManager = new GesturesManager(this);
        verticalGesturesManager = new VerticalGesturesManager(this);

        gesturesManager.setVerticalGesturesManager(verticalGesturesManager);
        verticalGesturesManager.setGesturesManager(gesturesManager);

        timecodeManager = new TimecodeManager(this);
        playerAudioController = new PlayerAudioController();
        playerAudioController.init(this, apiService);

        playerPanelsController = new PlayerPanelsController(this, new PlayerPanelsController.PanelsCallback() {
            @Override
            public androidx.media3.exoplayer.ExoPlayer getPlayer() {
                return player;
            }

            @Override
            public androidx.media3.ui.PlayerView getPlayerView() {
                return playerView;
            }

            @Override
            public com.example.animelib.managers.PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public com.example.animelib.managers.CommentsManager getCommentsManager() {
                return commentsManager;
            }

            @Override
            public boolean isOfflineMode() {
                return isOfflineMode;
            }

            @Override
            public int getStatusBarHeight() {
                return VideoPlayerActivity.this.getStatusBarHeight();
            }

            @Override
            public void updateAmbientPlayerTransform(float scale, float translationX, float translationY, boolean isCropped) {
                VideoPlayerActivity.this.updateAmbientPlayerTransform(scale, translationX, translationY, isCropped);
            }

            @Override
            public void onOutlineValuesChanged(float left, float top, float right, float bottom, float radiusPx) {
                currentOutlineLeft = left;
                currentOutlineTop = top;
                currentOutlineRight = right;
                currentOutlineBottom = bottom;
                currentCornerRadiusPx = radiusPx;
                if (playerContainer instanceof com.example.animelib.ui.RoundedCornerFrameLayout) {
                    ((com.example.animelib.ui.RoundedCornerFrameLayout) playerContainer).setCornerRadius(radiusPx);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (playerContainer != null) {
                        playerContainer.invalidateOutline();
                    }
                }
            }
        });

        playerControlsOverlayManager = new PlayerControlsOverlayManager();

        playerPipController = new PlayerPipController(this, new PlayerPipController.PipCallback() {
            @Override
            public androidx.media3.exoplayer.ExoPlayer getPlayer() {
                return player;
            }

            @Override
            public void onPipEnterUIState() {
                wasCommentsVisibleBeforePiP = commentsManager != null && commentsManager.isCommentsVisible();
                hideAllUI();
            }

            @Override
            public void onPipExitUIState() {
                showAllUI();
            }

            @Override
            public void onPipSeekingStarted() {
                startSeekingState();
            }

            @Override
            public void onPipSeekingEnded() {
                scheduleEndSeekingState(600);
            }
        });
        playerPipController.setupPipReceiver();

        playerSubtitlesController = new PlayerSubtitlesController(new PlayerSubtitlesController.SubtitlesCallback() {
            @Override
            public boolean isOfflineMode() {
                return isOfflineMode;
            }

            @Override
            public com.example.animelib.data.entity.DownloadedEpisodeEntity getCurrentOfflineEpisode() {
                return VideoPlayerActivity.this.getCurrentOfflineEpisode();
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public String getCurrentVideoUrl() {
                return currentVideoUrl;
            }

            @Override
            public String getCurrentVideoDomain() {
                return currentVideoDomain;
            }

            @Override
            public ExoPlayer getPlayer() {
                return player;
            }

            @Override
            public PlayerView getPlayerView() {
                return playerView;
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

        playerFiltersController = new PlayerFiltersController();

        playerNextEpisodeController = new com.example.animelib.controllers.PlayerNextEpisodeController();
        playerNextEpisodeController.initViews(findViewById(android.R.id.content));
        playerNextEpisodeController.setEpisodesManager(episodesManager);

        playerEpisodesController = new com.example.animelib.controllers.PlayerEpisodesController();
        playerEpisodesController.initialize(episodesManager, playersManager, apiService, new com.example.animelib.controllers.PlayerEpisodesController.EpisodesCallback() {
            @Override
            public void onEpisodeHeaderUpdateQuick() {
                updateEpisodeHeaderQuick();
            }

            @Override
            public void onResetBookmarkState() {
                bookmarkTimecode = 0;
                savedPlayerPosition = 0;
                autoBookmarkSaved = false;
                updateBookmarkButtonColor(false);
            }

            @Override
            public void onInitializeMenuWithoutAutoPlay() {
                initializeMenuWithoutAutoPlay();
            }

            @Override
            public void onEpisodeChanged(EpisodesListResponse.EpisodeItem episode) {
                if (playerCommentsController != null) {
                    playerCommentsController.setCurrentEpisode(episode);
                    if (!isOfflineMode && getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                        playerCommentsController.loadCommentsForPortraitIfNeeded();
                    }
                }
                updatePortraitHeaderTitlesUI();
            }
        });

        playerAnimeInfoController = new com.example.animelib.controllers.PlayerAnimeInfoController();
        playerAnimeInfoController.initViews(findViewById(android.R.id.content));

        playerOrientationController = new com.example.animelib.controllers.PlayerOrientationController(this);
        playerOrientationController.init();

        databaseManager = new com.example.animelib.data.DatabaseManager(this);
        playerDownloadController = new com.example.animelib.controllers.PlayerDownloadController(this, databaseManager);
        playerDownloadController.initViews(findViewById(android.R.id.content));
        playerDownloadController.setCallback(new com.example.animelib.controllers.PlayerDownloadController.DownloadCallback() {
            @Override
            public String getAnimeId() {
                return currentAnimeId;
            }

            @Override
            public String getAnimeTitle() {
                return com.example.animelib.controllers.PlayerContextHelper.getAnimeTitle(
                        animeTitleView != null ? animeTitleView.getText().toString() : null,
                        currentAnimeInfo,
                        getIntent()
                );
            }

            @Override
            public String getPosterUrl() {
                return currentPosterUrl;
            }

            @Override
            public String getCurrentVideoUrl() {
                return currentVideoUrl;
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        });

        playerUIBinder = new com.example.animelib.controllers.PlayerUIBinder();

        playerPlaybackController = new com.example.animelib.controllers.PlayerPlaybackController(this, playerView, httpDataSourceFactory);
        playerPlaybackController.setCallback(new com.example.animelib.controllers.PlayerPlaybackController.PlaybackCallback() {
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
            public void onFirstFrameRendered() {
                hasRenderedFirstFrame = true;
                isVideoLoading = false;
                isSeeking = false;
                hideLoading();
                updatePlayPauseAndLoadingState(false);
            }

            @Override
            public void onPlaybackStateChanged(int state, boolean playWhenReady) {
                if (state == Player.STATE_READY) {
                    hasRenderedFirstFrame = true;
                    isVideoLoading = false;
                }
                updatePlayLoadingIndicator(state);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                handlePlaybackError(error);
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        });

        playerProgressController = new com.example.animelib.controllers.PlayerProgressController(this, apiService);
        playerProgressController.setCallback(new com.example.animelib.controllers.PlayerProgressController.ProgressCallback() {
            @Override
            public String getAnimeId() {
                return currentAnimeId;
            }

            @Override
            public String getAnimeUrl() {
                return animeUrl;
            }

            @Override
            public boolean isOfflineMode() {
                return isOfflineMode;
            }

            @Override
            public Player getPlayer() {
                return playerPlaybackController != null ? playerPlaybackController.getPlayer() : player;
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
                return bookmarkButton;
            }

            @Override
            public ImageView getPortraitBookmarkButton() {
                return portraitBookmarkButton;
            }

            @Override
            public Object getCurrentWatchStatusId() {
                return currentWatchStatusId;
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

        playerQualityController = new com.example.animelib.controllers.PlayerQualityController();
        playerQualityController.setCallback(new com.example.animelib.controllers.PlayerQualityController.QualityCallback() {
            @Override
            public void onQualityChanged(String newQuality, String newVideoUrl, boolean isHls) {
                preferredQuality = newQuality;
                currentVideoUrl = newVideoUrl;
                if (isHls) {
                    initializeHlsPlayer(newVideoUrl);
                } else {
                    restartPlayerWithNewQuality();
                }
            }

            @Override
            public void onError(String title, String message, Runnable retryAction) {
                showVideoErrorDialog(title, message, retryAction);
            }
        });

        playerDialogsController = new com.example.animelib.controllers.PlayerDialogsController(this);
        playerDialogsController.setCallback(new com.example.animelib.controllers.PlayerDialogsController.DialogCallback() {
            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public String getPreferredQuality() {
                return preferredQuality;
            }

            @Override
            public void hideLoading() {
                VideoPlayerActivity.this.hideLoading();
            }

            @Override
            public void showLoading(String message) {
                VideoPlayerActivity.this.showLoading(message);
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        });

        playerRelatedTitlesController = new com.example.animelib.controllers.PlayerRelatedTitlesController(new com.example.animelib.controllers.PlayerRelatedTitlesController.RelatedTitlesCallback() {
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
            public com.example.animelib.controllers.PlayerDialogsController getPlayerDialogsController() {
                return playerDialogsController;
            }

            @Override
            public void saveLatestViewOnExit() {
                VideoPlayerActivity.this.saveLatestViewOnExit();
            }

            @Override
            public boolean isOfflineMode() {
                return isOfflineMode;
            }

            @Override
            public void safeRunOnUiThread(Runnable runnable) {
                VideoPlayerActivity.this.safeRunOnUiThread(runnable);
            }
        });

        playerVideoResolverController = new com.example.animelib.controllers.PlayerVideoResolverController(
                apiService, timecodeManager, playersManager, new com.example.animelib.controllers.PlayerVideoResolverController.ResolverProvider() {
                    @Override
                    public android.content.Context getContext() {
                        return VideoPlayerActivity.this;
                    }

                    @Override
                    public boolean isDownloadedQuality(String quality) {
                        return VideoPlayerActivity.this.isDownloadedQuality(quality);
                    }

                    @Override
                    public com.example.animelib.data.entity.DownloadedEpisodeEntity getDownloadedEpisodeForActive() {
                        return VideoPlayerActivity.this.getDownloadedEpisodeForActive();
                    }

                    @Override
                    public void setCurrentVideoUrl(String url) {
                        currentVideoUrl = url;
                    }

                    @Override
                    public String getPreferredQuality() {
                        return preferredQuality;
                    }

                    @Override
                    public void setPreferredQuality(String quality) {
                        preferredQuality = quality;
                        updateSettingsQualityTag();
                        if (playerDialogsController != null && playersManager != null) {
                            List<String> newQualities = getQualitiesWithDownloadedOption(playersManager.getAvailableQualities());
                            playerDialogsController.updateSettingsQualities(newQualities, preferredQuality);
                        }
                    }

                    @Override
                    public boolean isEnable4K() {
                        return enable4K;
                    }

                    @Override
                    public String getCurrentVideoDomain() {
                        return currentVideoDomain;
                    }

                    @Override
                    public void showLoading(String message) {
                        VideoPlayerActivity.this.showLoading(message);
                    }

                    @Override
                    public void hideLoading() {
                        VideoPlayerActivity.this.hideLoading();
                    }

                    @Override
                    public void showVideoErrorDialog(String title, String message, Runnable retryAction) {
                        VideoPlayerActivity.this.showVideoErrorDialog(title, message, retryAction);
                    }

                    @Override
                    public void initializePlayer() {
                        VideoPlayerActivity.this.initializePlayer();
                    }

                    @Override
                    public androidx.media3.exoplayer.ExoPlayer getPlayer() {
                        return player;
                    }

                    @Override
                    public View getMenuLoadingOverlay() {
                        return menuLoadingOverlay;
                    }

                    @Override
                    public void setVideoLoading(boolean loading) {
                        isVideoLoading = loading;
                    }

                    @Override
                    public void setHasRenderedFirstFrame(boolean rendered) {
                        hasRenderedFirstFrame = rendered;
                    }

                    @Override
                    public void updatePlayPauseAndLoadingState(boolean animate) {
                        VideoPlayerActivity.this.updatePlayPauseAndLoadingState(animate);
                    }

                    @Override
                    public void setCurrentKodikResponse(KodikResponse response) {
                        currentKodikResponse = response;
                        if (playerQualityController != null) {
                            playerQualityController.setCurrentKodikResponse(response);
                        }
                        if (playersManager != null) {
                            playersManager.setCurrentKodikResponse(response);
                        }
                    }

                    @Override
                    public void initializeHlsPlayer(String hlsUrl) {
                        VideoPlayerActivity.this.initializeHlsPlayer(hlsUrl);
                    }

                    @Override
                    public void safeRunOnUiThread(Runnable runnable) {
                        VideoPlayerActivity.this.safeRunOnUiThread(runnable);
                    }

                    @Override
                    public void onPlayerSelected(EpisodeResponse.PlayerData playerData) {
                        VideoPlayerActivity.this.onPlayerSelected(playerData);
                    }
                });

        try {
            android.webkit.WebView webView = new android.webkit.WebView(this);
            webView.clearCache(true);
            webView.clearHistory();
            webView.destroy();
        } catch (Exception e) {
            Log.w("VideoPlayer", "Failed to clear WebView cache", e);
        }

        if (playerSubtitlesController != null) {
            playerSubtitlesController.loadSettingsFromApi(apiService);
        }

        executor.execute(() -> {
            enable4K = apiService.load4KSetting();
            enableAmbientLight = apiService.loadAmbientLightSetting();
            if (playerAudioController != null) {
                playerAudioController.loadSettings(apiService);
            }
            autoPlay = apiService.loadAutoPlaySetting();
            isNewEpisodeSelection = true;
            autoPlayOnPrepare = autoPlay;
            longSkipDuration = apiService.loadLongSkipDurationSetting();
            currentTheme = apiService.loadThemeSetting();
            if (playerSubtitlesController != null) {
                playerSubtitlesController.loadSettingsFromApi(apiService);
            }
            if (playerFiltersController != null) {
                playerFiltersController.loadSettingsFromApi(apiService);
            }
            Log.d("VideoPlayer", "Loaded settings - 4K: " + enable4K + ", AmbientLight: " + enableAmbientLight + ", AutoPlay: " + autoPlay + ", SkipDuration: " + longSkipDuration + ", Theme: " + currentTheme);

            runOnUiThread(() -> {
                if (playersManager != null) {
                    playersManager.setEnable4K(enable4K);
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
            });
        });

        Log.d("VideoPlayer", "Initialized HTTP data source with custom headers: User-Agent, Referer, Accept, Accept-Encoding, Accept-Language, Origin, Sec-Fetch-*");

        // Get data from intent
        String localFilePath = getIntent().getStringExtra("EXTRA_LOCAL_FILE_PATH");
        currentVideoUrl = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        animeUrl = getIntent().getStringExtra(EXTRA_ANIME_URL);

        if (localFilePath != null) {
            isOfflineMode = true;
        }

        // Setup fullscreen
        setupFullscreen();

        // Setup menu
        setupMenu();

        if (localFilePath != null) {
            String animeTitle = getIntent().getStringExtra("EXTRA_ANIME_TITLE");
            String episodeTitle = getIntent().getStringExtra("EXTRA_EPISODE_TITLE");
            playOfflineFile(localFilePath, animeTitle, episodeTitle);
        } else if (currentVideoUrl != null) {
            // Direct video URL provided - start player immediately
            initializePlayer();
        } else if (animeUrl != null) {
            // Anime page URL provided - fetch video links first
            showLoading("Загрузка видео...");
            loadAnimeFromUrl(animeUrl);
        } else {
            CustomToast.showWarning(this, "Ошибка: Не указан URL видео или страницы аниме");
            finish();
        }
    }

    /**
     * Безопасно вызывает код в главном потоке
     */
    private void safeRunOnUiThread(Runnable runnable) {
        try {
            runOnUiThread(runnable);
        } catch (Exception e) {
            Log.e("VideoPlayer", "Error calling UI thread", e);
            // Fallback - вызываем в текущем потоке
            try {
                runnable.run();
            } catch (Exception ex) {
                Log.e("VideoPlayer", "Error in fallback callback", ex);
            }
        }
    }

    private void loadAndApplyTheme() {
        executor.execute(() -> {
            try {
                int sharedPrefTheme = ThemeUtils.getSavedThemePreference(this);
                int themeMode = apiService.loadThemeSetting();
                Log.d("VideoPlayerTheme", "Loaded theme - SharedPref: " + sharedPrefTheme + ", DB: " + themeMode);
                
                int finalTheme = (sharedPrefTheme >= 0 && sharedPrefTheme <= 2) ? sharedPrefTheme : themeMode;
                
                // Применяем тему в главном потоке
                safeRunOnUiThread(() -> {
                    ThemeUtils.applyThemeToActivity(VideoPlayerActivity.this, finalTheme);
                    setupFullscreen();
                    checkAndUpdateOrientation();
                    Log.d("VideoPlayerTheme", "Theme applied on startup: " + finalTheme);
                });
                
            } catch (Exception e) {
                Log.e("VideoPlayerTheme", "Failed to load and apply theme", e);
                safeRunOnUiThread(() -> {
                    ThemeUtils.applyThemeToActivity(VideoPlayerActivity.this, ThemeUtils.getSavedThemePreference(VideoPlayerActivity.this));
                    setupFullscreen();
                    checkAndUpdateOrientation();
                });
            }
        });
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (playerPipController != null) {
            playerPipController.onPictureInPictureModeChanged(isInPictureInPictureMode);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (player != null && player.isPlaying() && playerPipController != null && !playerPipController.isInPictureInPictureMode()) {
            autoSaveBookmark();
            playerPipController.enterPictureInPictureMode();
        }
    }

    /**
     * Переключает ориентацию экрана (портретная / альбомная)
     */
    private void toggleOrientation() {
        if (playerOrientationController != null) {
            playerOrientationController.toggleFullscreenOrientation();
        }
    }

    private void setVideoResizeMode(int resizeMode) {
        this.currentResizeMode = resizeMode;
        if (playerView != null) {
            playerView.setResizeMode(resizeMode);
        }
        if (player != null) {
            if (resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            } else {
                player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            }
        }
    }

    /**
     * Обновление настроек автоматического скрытия контроллера
     */
    private void updateControllerAutoHide() {
        if (playerControlsOverlayManager != null) {
            playerControlsOverlayManager.updateControllerAutoHide(playerView, shouldAutoHideControls, controllerShowTimeoutMs);
        }
    }

    /**
     * Обновление настроек автоматического скрытия контроллера с принудительным значением
     */
    private void updateControllerAutoHide(boolean enableAutoHide) {
        if (playerControlsOverlayManager != null) {
            playerControlsOverlayManager.updateControllerAutoHide(playerView, enableAutoHide, controllerShowTimeoutMs);
        }
    }

    /**
     * Открыть панель меню плееров
     */
    public void openMenuPanel() {
        if (playerPanelsController != null) {
            playerPanelsController.openMenuPanel();
        }
    }

    /**
     * Закрыть панель меню плееров
     */
    public void closeMenuPanel() {
        if (playerPanelsController != null) {
            playerPanelsController.closeMenuPanel();
        }
    }

    /**
     * Открыть панель комментариев
     */
    public void openCommentsPanel() {
        if (playerPanelsController != null) {
            playerPanelsController.openCommentsPanel();
        }
    }

    /**
     * Закрыть панель комментариев
     */
    public void closeCommentsPanel() {
        if (playerPanelsController != null) {
            playerPanelsController.closeCommentsPanel();
        }
    }

    /**
     * Проверить открыта ли панель меню
     */
    public boolean isMenuPanelOpen() {
        return playerPanelsController != null && playerPanelsController.isMenuPanelOpen();
    }

    /**
     * Проверить открыта ли панель комментариев
     */
    public boolean isCommentsPanelOpen() {
        return playerPanelsController != null && playerPanelsController.isCommentsPanelOpen();
    }

    private void hideAllUI() {
        if (playerControlsOverlayManager != null) {
            ImageButton pipButton = findViewById(R.id.pipButton);
            SettingsBottomSheet sbs = playerDialogsController != null ? playerDialogsController.getCurrentSettingsBottomSheet() : null;
            playerControlsOverlayManager.hideAllUI(playerView, menuPanelContainer, commentsManager, episodesManager, playersManager, gesturesManager, pipButton, sbs);
        }
    }

    private void showAllUI() {
        if (playerControlsOverlayManager != null) {
            ImageButton pipButton = findViewById(R.id.pipButton);
            playerControlsOverlayManager.showAllUI(playerView, pipButton, episodesManager, playersManager, gesturesManager, commentsManager, wasCommentsVisibleBeforePiP);
        }
    }

    private void setupFullscreen() {
        Window window = getWindow();
        if (window != null) {
            boolean isPortrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;

            WindowCompat.setDecorFitsSystemWindows(window, false);

            WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(window, window.getDecorView());
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);

            if (isPortrait) {
                // В вертикальном положении показывай системную шторку (status bar)
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
                controller.show(WindowInsetsCompat.Type.statusBars());
            } else {
                // В горизонтальном положении скрываем системные панели, с прозрачным фоном для шторки
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
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
                return WindowInsetsCompat.CONSUMED;
            });
        }

        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.setFitsSystemWindows(false);
            rootView.setPadding(0, 0, 0, 0);
        }
    }

    @SuppressLint({"ClickableViewAccessibility", "RtlHardcoded"})
    private void setupMenu() {
        // === 1. Initialize UI Components ===
        initializeUIComponents();
        
        // === 2. Initialize Managers ===
        initializeManagers();
        
        // === 3. Setup Event Listeners ===
        setupEventListeners();
        
        // === 4. Configure Initial State ===
        configureInitialState();
        checkAndUpdateOrientation();
        
        // === 5. Setup Manager Callbacks ===
        setupManagerCallbacks();
        
        // === 6. Start Initial Loading ===
        startInitialLoading();
    }
    
    /**
     * Инициализация всех UI компонентов
     */
    private void initializeUIComponents() {

        // Main menu components
        controllerView = playerView.findViewById(R.id.exo_controller);
        timeBar = controllerView.findViewById(R.id.exo_progress);
        
        // Set initial alpha for controller (will be animated by our custom logic)
        if (controllerView != null) {
            controllerView.setAlpha(1.0f);
        }

        // Initialize draggable panels
        menuPanelContainer = findViewById(R.id.menuPanelContainer);
        commentsPanelContainer = findViewById(R.id.commentsPanelContainer);
        
        slidingMenuPanel = findViewById(R.id.slidingMenuPanel);
        menuOverlay = findViewById(R.id.menuOverlay);
        menuLoadingIndicator = findViewById(R.id.menuLoadingIndicator);
        menuLoadingOverlay = findViewById(R.id.menuLoadingOverlay);
        
        // Setup menu overlay click to close panels
        if (menuOverlay != null) {
            menuOverlay.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            menuOverlay.setOnClickListener(v -> {
                // Закрываем открытые панели при клике на overlay
                if (menuPanelContainer != null && menuPanelContainer.isOpen()) {
                    closeMenuPanel();
                }
                if (commentsPanelContainer != null && commentsPanelContainer.isOpen()) {
                    closeCommentsPanel();
                }
            });
        }
        
        // Initialize anime info placeholder
        // Gesture components
        TextView seekPreviewText = findViewById(R.id.seekPreviewText);
        TextView holdSpeedToast = findViewById(R.id.holdSpeedToast);
        View skipIndicatorLeft = findViewById(R.id.skipIndicatorLeft);
        View skipIndicatorRight = findViewById(R.id.skipIndicatorRight);
        this.gestureIndicatorPlaque = findViewById(R.id.gestureIndicatorPlaque);
        this.gestureIndicatorIcon = findViewById(R.id.gestureIndicatorIcon);
        this.gestureIndicatorProgress = findViewById(R.id.gestureIndicatorProgress);
        this.gestureIndicatorText = findViewById(R.id.gestureIndicatorText);
        
        // Comments components
        View commentsPanel = findViewById(R.id.commentsPanel);
        ImageButton closeCommentsButton = findViewById(R.id.closeCommentsButton);
        RecyclerView commentsRecyclerView = findViewById(R.id.commentsRecyclerView);
        View commentsLoadingOverlay = findViewById(R.id.commentsLoadingOverlay);
        View commentsOptionsButton = findViewById(R.id.btnSortComments);
        TextView emptyCommentsText = findViewById(R.id.emptyCommentsText);
        
        // Related titles components
        relatedTitlesOverlay = findViewById(R.id.relatedTitlesOverlay);
        relatedTitlesDimOverlay = findViewById(R.id.relatedTitlesDimOverlay);
        relatedTitlesRecyclerView = findViewById(R.id.relatedTitlesRecyclerView);
        
        // Player control components
        if (controllerView != null) {
            initializeControllerComponents();
        }
        
        // Menu control components
        ImageButton closeMenuButton = findViewById(R.id.closeMenuButton);
        ImageButton pipButton = findViewById(R.id.pipButton);
        
        // Store components for manager initialization
        this.menuOverlay = menuOverlay;
        this.closeMenuButton = closeMenuButton;
        this.commentsPanel = commentsPanel;
        this.closeCommentsButton = closeCommentsButton;
        this.commentsRecyclerView = commentsRecyclerView;
        this.commentsLoadingOverlay = commentsLoadingOverlay;
        this.commentsOptionsButton = commentsOptionsButton;
        this.emptyCommentsText = emptyCommentsText;
        this.seekPreviewText = seekPreviewText;
        this.holdSpeedToast = holdSpeedToast;
        this.pipButton = pipButton;
        this.skipIndicatorLeft = skipIndicatorLeft;
        this.skipIndicatorRight = skipIndicatorRight;
        this.playerBufferingIndicator = findViewById(R.id.playerBufferingIndicator);
    }
    
    /**
     * Инициализация компонентов контроллера плеера
     */
    private void initializeControllerComponents() {
        if (playerUIBinder != null && controllerView != null) {
            playerUIBinder.bindControllerViews(controllerView);
            ibClosePlayer = playerUIBinder.ibClosePlayer;
            settingsButton = playerUIBinder.settingsButton;
            settingsQualityTag = playerUIBinder.settingsQualityTag;
            menuToggleFullscreen = playerUIBinder.menuToggleFullscreen;
            if (pipButton == null) pipButton = playerUIBinder.pipButton;
            animeTitleView = playerUIBinder.animeTitleView;
            currentTeamName = playerUIBinder.currentTeamName;
            currentEpisodeName = playerUIBinder.currentEpisodeName;
            currentEpisodeNumberView = playerUIBinder.currentEpisodeNumberView;
            episodesMenuButton = playerUIBinder.episodesMenuButton;
            prevEpisodeButton = playerUIBinder.prevEpisodeButton;
            nextEpisodeButton = playerUIBinder.nextEpisodeButton;
            menuToggleButton = playerUIBinder.menuToggleButton;
            playersControlBar = playerUIBinder.playersControlBar;
            episodesHorizontalRecyclerView = playerUIBinder.episodesHorizontalRecyclerView;
            commentsButton = playerUIBinder.commentsButton;
            bookmarkButton = playerUIBinder.bookmarkButton;
        } else if (controllerView != null) {
            ibClosePlayer = controllerView.findViewById(R.id.ibClosePlayer);
            settingsButton = controllerView.findViewById(R.id.settingsButton);
            settingsQualityTag = controllerView.findViewById(R.id.settingsQualityTag);
            menuToggleFullscreen = controllerView.findViewById(R.id.menuToggleFullscreen);
            if (pipButton == null) pipButton = controllerView.findViewById(R.id.pipButton);
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
        }
        updateSettingsQualityTag();
        downloadButton = null;
        downloadButtonTop = null;
        btnDownloadFromMenu = findViewById(R.id.btnDownloadFromMenu);
        downloadProgressText = findViewById(R.id.downloadProgressText);
        setupDownloadListener();
    }
    
    /**
     * Применяет трансформацию плеера при открытии боковых панелей
     */
    private void applyPlayerSidePanelTransform(float openProgress) {
        if (playerPanelsController != null) {
            playerPanelsController.applyPlayerSidePanelTransform(openProgress);
        }
    }

    private void updateAmbientPlayerTransform(float scale, float translationX, float translationY, boolean isCroppedToVideo) {
        androidx.media3.ui.PlayerView ambientPlayerView = findViewById(R.id.ambientPlayerView);
        if (ambientPlayerView != null) {
            android.view.ViewGroup.LayoutParams lp = ambientPlayerView.getLayoutParams();
            if (lp != null && (lp.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT || lp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT)) {
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                if (lp instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) lp).gravity = Gravity.CENTER;
                }
                ambientPlayerView.setLayoutParams(lp);
            }

            int viewW = ambientPlayerView.getWidth();
            int viewH = ambientPlayerView.getHeight();
            if (viewW <= 0 || viewH <= 0) {
                viewW = getResources().getDisplayMetrics().widthPixels;
                viewH = getResources().getDisplayMetrics().heightPixels;
            }

            // Pivot at center of full screen
            ambientPlayerView.setPivotX(viewW / 2f);
            ambientPlayerView.setPivotY(viewH / 2f);

            // Scale slightly larger than screen so blurred edges bleed offscreen cleanly
            ambientPlayerView.setScaleX(1.08f);
            ambientPlayerView.setScaleY(1.08f);
            
            // Subtle parallax following card if offset, otherwise centered
            ambientPlayerView.setTranslationX(translationX * 0.15f);
            ambientPlayerView.setTranslationY(translationY * 0.15f);
        }
    }
    
    /**
     * Инициализация всех менеджеров
     */
    private void initializeManagers() {
        // Initialize gestures manager
        gesturesManager.initializeViews(playerView, null, holdSpeedToast, seekPreviewText,
                skipIndicatorLeft, skipIndicatorRight);
        
        // Setup draggable panels
        if (playerPanelsController != null) {
            playerPanelsController.initViews(menuPanelContainer, commentsPanelContainer, slidingMenuPanel, commentsPanel, menuOverlay, playerContainer);
        }
        
        // Initialize comments manager
        if (playerCommentsController != null) {
            playerCommentsController.initializePanelViews(commentsPanel, closeCommentsButton, commentsRecyclerView,
                    commentsLoadingOverlay, commentsButton, commentsOptionsButton, menuOverlay, emptyCommentsText);
        }
        
        // Initialize players manager
        playersManager.initializeViews(slidingMenuPanel, closeMenuButton, null, null,
                menuOverlay, menuLoadingOverlay, menuLoadingIndicator);
        
        // Initialize episodes manager
        episodesManager.initializeViews(null, episodesMenuButton, episodesHorizontalRecyclerView,
                null, prevEpisodeButton, nextEpisodeButton, menuOverlay, playersControlBar);

        View landscapeEpisodesContainer = findViewById(R.id.landscapeEpisodesContainer);
        if (landscapeEpisodesContainer != null) {
            episodesManager.setLandscapeEpisodesContainer(landscapeEpisodesContainer);
        }

        View btnLandscapeSearch = findViewById(R.id.btnLandscapeEpisodeSearch);
        EditText etLandscapeNum = findViewById(R.id.etLandscapeEpisodeNumber);
        if (btnLandscapeSearch != null && etLandscapeNum != null) {
            episodesManager.setupLandscapeSearchViews(btnLandscapeSearch, etLandscapeNum);
        }
        
        // Configure menu width
        float density = getResources().getDisplayMetrics().density;
        menuWidth = (int) (menuWidth * density);
        playersManager.setMenuWidth(menuWidth);
        
        // Initialize ambient light manager with secondary ambient player view & vignette overlay
        View ambientContainer = findViewById(R.id.ambientContainer);
        androidx.media3.ui.PlayerView ambientPlayerView = findViewById(R.id.ambientPlayerView);
        com.example.animelib.ui.AmbientVignetteOverlayView ambientVignetteOverlay = findViewById(R.id.ambientVignetteOverlay);
        ambientLightManager = new AmbientLightManager(this, playerView, ambientContainer, ambientPlayerView, ambientVignetteOverlay);
        if (ambientLightManager != null) {
            if (httpDataSourceFactory != null) {
                ambientLightManager.setDataSourceFactory(httpDataSourceFactory);
            }
            ambientLightManager.setEnabled(enableAmbientLight);
        }

        View playerContainer = findViewById(R.id.playerContainer);
        if (playerContainer != null) {
            playerContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    updateAmbientPlayerTransform(1f, 0f, 0f, false);
                    if (ambientLightManager != null) {
                        ambientLightManager.refreshAmbientFrame();
                    }
                }
            });
        }
        
        // Initialize video filters manager
        if (playerFiltersController != null) {
            playerFiltersController.init(this, playerView);
        }
        
        // Initialize portrait views
        initializePortraitViews();

        // Initialize related titles
        initializeRelatedTitles();
    }

    public static String cleanEpisodeName(String name, String epNumber) {
        if (name == null) return "";
        String clean = name.trim();
        if (clean.isEmpty()) return "";

        // Normalize spaces
        clean = clean.replaceAll("\\s+", " ");

        // Remove leading episode patterns e.g. "Серия 1 - ", "Серия 1: ", "1 серия - ", "Серия 1"
        if (epNumber != null && !epNumber.trim().isEmpty()) {
            String num = epNumber.trim();
            clean = clean.replaceAll("^(?i)(?:серия\\s*" + java.util.regex.Pattern.quote(num) + "|" + java.util.regex.Pattern.quote(num) + "\\s*серия)\\s*[-:–—.]*\\s*", "");
        } else {
            clean = clean.replaceAll("^(?i)(?:серия\\s*\\d+|\\d+\\s*серия)\\s*[-:–—.]*\\s*", "");
        }

        clean = clean.trim();
        if (clean.equalsIgnoreCase("серия") || clean.matches("^(?i)серия\\s*\\d*$") || clean.matches("^\\d+$")) {
            return "";
        }
        return clean;
    }

    private String getOfflineVoiceoverName() {
        com.example.animelib.data.entity.DownloadedEpisodeEntity ep = getCurrentOfflineEpisode();
        if (ep != null && ep.getTeamName() != null && !ep.getTeamName().trim().isEmpty()) {
            return ep.getTeamName().trim();
        }
        return "Офлайн";
    }

    private String getOfflinePlayerTypeName() {
        com.example.animelib.data.entity.DownloadedEpisodeEntity ep = getCurrentOfflineEpisode();
        if (ep != null && ep.getPlayerType() != null && !ep.getPlayerType().trim().isEmpty()) {
            String pt = ep.getPlayerType().trim();
            return pt.substring(0, 1).toUpperCase() + pt.substring(1);
        }
        return "Офлайн";
    }

    private com.example.animelib.data.entity.DownloadedEpisodeEntity getCurrentOfflineEpisode() {
        com.example.animelib.data.entity.DownloadedEpisodeEntity activeDownloaded = getDownloadedEpisodeForActive();
        if (activeDownloaded != null) {
            return activeDownloaded;
        }
        if (!isOfflineMode) return null;
        EpisodesListResponse.EpisodeItem cur = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
        if (cur != null) {
            if (cur.getNumber() != null && offlineEpisodesMap.containsKey(cur.getNumber())) {
                return offlineEpisodesMap.get(cur.getNumber());
            }
            if (offlineEpisodesMap.containsKey(String.valueOf(cur.getId()))) {
                return offlineEpisodesMap.get(String.valueOf(cur.getId()));
            }
        }
        String epNum = getIntent() != null ? getIntent().getStringExtra("EXTRA_EPISODE_NUMBER") : null;
        if (epNum != null && offlineEpisodesMap.containsKey(epNum)) {
            return offlineEpisodesMap.get(epNum);
        }
        if (!offlineEpisodesMap.isEmpty()) {
            return offlineEpisodesMap.values().iterator().next();
        }
        return null;
    }

    private void hideAllSkeletonsForOffline() {
        safeRunOnUiThread(() -> {
            String animeTitle = getIntent() != null ? getIntent().getStringExtra("EXTRA_ANIME_TITLE") : null;
            if (animeTitle == null || animeTitle.isEmpty()) {
                com.example.animelib.data.entity.DownloadedEpisodeEntity cur = getCurrentOfflineEpisode();
                if (cur != null) animeTitle = cur.getAnimeTitle();
            }
            if (animeTitle == null) animeTitle = "";

            if (animeTitleView != null) {
                SkeletonHelper.hideSkeleton(animeTitleView, animeTitle);
            }
            if (tvPortraitAnimeTitle != null) {
                SkeletonHelper.hideSkeleton(tvPortraitAnimeTitle, animeTitle);
            }
            if (currentTeamName != null) {
                SkeletonHelper.hideSkeleton(currentTeamName, getOfflineVoiceoverName());
            }
            if (tvPortraitVoiceover != null) {
                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, getOfflineVoiceoverName());
            }
            if (tvPortraitPlayer != null) {
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, getOfflinePlayerTypeName());
            }

            EpisodesListResponse.EpisodeItem curEp = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
            String epNum = curEp != null && curEp.getNumber() != null ? curEp.getNumber() : (getIntent() != null ? getIntent().getStringExtra("EXTRA_EPISODE_NUMBER") : null);
            String rawName = curEp != null && curEp.getName() != null ? curEp.getName() : (getIntent() != null ? getIntent().getStringExtra("EXTRA_EPISODE_TITLE") : null);
            String cleanName = cleanEpisodeName(rawName, epNum);

            if (currentEpisodeNumberView != null) {
                if (epNum != null && !epNum.isEmpty()) {
                    SkeletonHelper.hideSkeleton(currentEpisodeNumberView, epNum + " серия");
                } else {
                    SkeletonHelper.hideSkeleton(currentEpisodeNumberView, "");
                }
            }
            if (currentEpisodeName != null) {
                if (!cleanName.isEmpty()) {
                    SkeletonHelper.hideSkeleton(currentEpisodeName, ", " + cleanName);
                } else {
                    SkeletonHelper.hideSkeleton(currentEpisodeName, "");
                }
            }
            if (tvPortraitEpisodeTitle != null) {
                if (!cleanName.isEmpty()) {
                    SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, cleanName);
                    tvPortraitEpisodeTitle.setVisibility(View.VISIBLE);
                } else {
                    SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, "");
                    tvPortraitEpisodeTitle.setVisibility(View.GONE);
                }
            }

            if (playerAnimeInfoController != null) {
                playerAnimeInfoController.setOfflineMode(true);
                playerAnimeInfoController.hideSkeletons(animeTitle);
            }
            if (portraitStatusDropdownButton != null) {
                portraitStatusDropdownButton.setVisibility(View.VISIBLE);
            }
        });
    }

    private void initializePortraitViews() {
        portraitScrollView = findViewById(R.id.portraitScrollView);
        btnPortraitScrollToTop = findViewById(R.id.btnPortraitScrollToTop);
        portraitVoiceoverPlayerButton = findViewById(R.id.portraitVoiceoverPlayerButton);
        tvPortraitVoiceover = findViewById(R.id.tvPortraitVoiceover);
        tvPortraitPlayer = findViewById(R.id.tvPortraitPlayer);
        ivPortraitVoiceoverChevron = findViewById(R.id.ivPortraitVoiceoverChevron);
        portraitBookmarkButton = findViewById(R.id.portraitBookmarkButton);
        portraitDownloadButton = findViewById(R.id.portraitDownloadButton);
        portraitStatusDropdownButton = findViewById(R.id.portraitStatusDropdownButton);
        ivPortraitStatusBookmarkIcon = findViewById(R.id.ivPortraitStatusBookmarkIcon);
        tvPortraitStatus = findViewById(R.id.tvPortraitStatus);
        portraitEpisodesRecyclerView = findViewById(R.id.portraitEpisodesRecyclerView);
        tvPortraitAnimeTitle = findViewById(R.id.tvPortraitAnimeTitle);
        tvPortraitEpisodeTitle = findViewById(R.id.tvPortraitEpisodeTitle);
        btnPortraitSortComments = findViewById(R.id.btnPortraitSortComments);
        tvPortraitSortComments = findViewById(R.id.tvPortraitSortComments);
        btnPortraitCommentRules = findViewById(R.id.btnPortraitCommentRules);
        portraitCommentInputField = findViewById(R.id.portraitCommentInputField);
        portraitCommentsRecyclerView = findViewById(R.id.portraitCommentsRecyclerView);
        tvPortraitEmptyComments = findViewById(R.id.tvPortraitEmptyComments);
        portraitCommentsLoadingOverlay = findViewById(R.id.portraitCommentsLoadingOverlay);
        portraitDownloadProgressContainer = findViewById(R.id.portraitDownloadProgressContainer);
        portraitDownloadProgressCard = findViewById(R.id.portraitDownloadProgressCard);
        tvPortraitDownloadPercent = findViewById(R.id.tvPortraitDownloadPercent);
        portraitRelatedTitlesContainer = findViewById(R.id.portraitRelatedTitlesContainer);
        portraitRelatedTitlesRecyclerView = findViewById(R.id.portraitRelatedTitlesRecyclerView);

        if (isOfflineMode) {
            if (portraitRelatedTitlesContainer != null) {
                portraitRelatedTitlesContainer.setVisibility(View.GONE);
            }
            if (portraitStatusDropdownButton != null) {
                portraitStatusDropdownButton.setVisibility(View.VISIBLE);
            }
            if (playerAnimeInfoController != null) {
                playerAnimeInfoController.setOfflineMode(true);
            }
        }

        if (portraitDownloadProgressCard != null) {
            portraitDownloadProgressCard.setOnClickListener(v -> showDownloadProgressBottomSheet());
        }

        if (!isOfflineMode) {
            showPlayerTextSkeletons();
        } else {
            hideAllSkeletonsForOffline();
        }

        if (portraitVoiceoverPlayerButton != null) {
            portraitVoiceoverPlayerButton.setOnClickListener(v -> {
                com.example.animelib.util.ItemAnimationUtils.animateItemClick(v, () -> {
                    if (isOfflineMode) {
                        CustomToast.showInfo(this, "Выбор озвучки недоступен в офлайн режиме");
                        return;
                    }
                    if (playersManager != null) {
                        playersManager.toggleMenu();
                    }
                });
            });
        }

        if (portraitStatusDropdownButton != null) {
            portraitStatusDropdownButton.setOnClickListener(v -> showWatchStatusBottomSheet());
        }

        if (portraitBookmarkButton != null) {
            portraitBookmarkButton.setOnClickListener(v -> {
                addBookmark();
            });
        }

        if (portraitDownloadButton != null) {
            portraitDownloadButton.setOnClickListener(v -> {
                if (isOfflineMode) {
                    CustomToast.showInfo(this, "Воспроизводится скачанный файл");
                    return;
                }
                if (DownloadService.isRunning()) {
                    showDownloadProgressBottomSheet();
                } else {
                    showDownloadBottomSheet();
                }
            });
        }

        if (episodesManager != null && portraitEpisodesRecyclerView != null) {
            episodesManager.setPortraitEpisodesRecyclerView(portraitEpisodesRecyclerView);

            View btnPortraitSearch = findViewById(R.id.btnPortraitEpisodeSearch);
            EditText etPortraitNum = findViewById(R.id.etPortraitEpisodeNumber);
            if (btnPortraitSearch != null && etPortraitNum != null) {
                episodesManager.setupPortraitSearchViews(btnPortraitSearch, etPortraitNum);
            }
        }

        if (playerCommentsController != null && portraitCommentsRecyclerView != null) {
            playerCommentsController.initializePortraitViews(portraitCommentsRecyclerView, portraitCommentsLoadingOverlay,
                    tvPortraitEmptyComments, btnPortraitSortComments, tvPortraitSortComments, btnPortraitCommentRules, isOfflineMode);
        }

        if (btnPortraitScrollToTop != null) {
            btnPortraitScrollToTop.setOnClickListener(v -> {
                if (portraitScrollView != null) {
                    portraitScrollView.smoothScrollTo(0, 0);
                }
            });
        }

        if (portraitScrollView != null) {
            portraitScrollView.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (btnPortraitScrollToTop != null) {
                    if (scrollY > 300) {
                        if (btnPortraitScrollToTop.getVisibility() != View.VISIBLE) {
                            btnPortraitScrollToTop.show();
                        }
                    } else {
                        if (btnPortraitScrollToTop.getVisibility() == View.VISIBLE) {
                            btnPortraitScrollToTop.hide();
                        }
                    }
                }

                if (scrollY >= 150) {
                    if (playerCommentsController != null && !isOfflineMode && getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                        playerCommentsController.loadCommentsForPortraitIfNeeded();
                    }
                }

                if (scrollY >= (v.getChildAt(0).getMeasuredHeight() - v.getMeasuredHeight() - 200)) {
                    if (playerCommentsController != null && !isOfflineMode && getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                        playerCommentsController.loadNextCommentsPageIfAvailable();
                    }
                }
            });
        }
    }

    /**
     * Показывает скелетоны загрузки для всех текстовых элементов плеера
     */
    private void showPlayerTextSkeletons() {
        if (isOfflineMode) return;
        safeRunOnUiThread(() -> {
            if (animeTitleView != null) SkeletonHelper.showSkeleton(animeTitleView, 160);
            if (currentTeamName != null) SkeletonHelper.showSkeleton(currentTeamName, 80);
            if (currentEpisodeNumberView != null) SkeletonHelper.showSkeleton(currentEpisodeNumberView, 65);
            if (currentEpisodeName != null) SkeletonHelper.showSkeleton(currentEpisodeName, 100);

            if (tvPortraitAnimeTitle != null) SkeletonHelper.showSkeleton(tvPortraitAnimeTitle, 180);
            if (tvPortraitEpisodeTitle != null) SkeletonHelper.showSkeleton(tvPortraitEpisodeTitle, 120);
            if (tvPortraitVoiceover != null) SkeletonHelper.showSkeleton(tvPortraitVoiceover, 90);
            if (tvPortraitPlayer != null) SkeletonHelper.showSkeleton(tvPortraitPlayer, 60);

            if (playerAnimeInfoController != null) {
                playerAnimeInfoController.showSkeletons();
            }
        });
    }

    private void updatePortraitVoiceoverPlayerUI() {
        safeRunOnUiThread(() -> {
            if (tvPortraitVoiceover == null || tvPortraitPlayer == null) return;
            com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
            boolean isPlayingDownloaded = isOfflineMode || isPlayingDownloadedVideo() ||
                    (downloadedEp != null && downloadedEp.getLocalFilePath() != null && new File(downloadedEp.getLocalFilePath()).exists() &&
                     (currentVideoUrl == null || currentVideoUrl.startsWith("file:") || currentVideoUrl.startsWith("/") || isDownloadedQuality(preferredQuality)));

            if (isPlayingDownloaded && downloadedEp != null) {
                String voiceoverName = downloadedEp.getTeamName() != null && !downloadedEp.getTeamName().trim().isEmpty()
                        ? downloadedEp.getTeamName().trim() : getOfflineVoiceoverName();
                String playerTypeName = downloadedEp.getPlayerType() != null && !downloadedEp.getPlayerType().trim().isEmpty()
                        ? (downloadedEp.getPlayerType().substring(0, 1).toUpperCase() + downloadedEp.getPlayerType().substring(1))
                        : getOfflinePlayerTypeName();

                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, voiceoverName);
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, playerTypeName);
                com.example.animelib.util.ItemAnimationUtils.animateTextChange(tvPortraitVoiceover);
                return;
            }

            if (isOfflineMode) {
                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, getOfflineVoiceoverName());
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, getOfflinePlayerTypeName());
                com.example.animelib.util.ItemAnimationUtils.animateTextChange(tvPortraitVoiceover);
                return;
            }
            if (playersManager == null) return;
            EpisodeResponse.PlayerData cur = playersManager.getCurrentPlayerData();
            if (cur != null) {
                String teamName = (cur.getTeam() != null && cur.getTeam().getName() != null && !cur.getTeam().getName().isEmpty())
                        ? cur.getTeam().getName()
                        : ((cur.getTranslationType() != null && cur.getTranslationType().getLabel() != null)
                                ? cur.getTranslationType().getLabel() : "Озвучка");
                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, teamName);

                String playerName = cur.getPlayer();
                if (playerName != null && !playerName.isEmpty()) {
                    playerName = playerName.substring(0, 1).toUpperCase() + playerName.substring(1);
                } else {
                    playerName = "Плеер";
                }
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, playerName);
                com.example.animelib.util.ItemAnimationUtils.animateTextChange(tvPortraitVoiceover);
            } else {
                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, "Выбрать озвучку");
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, "");
            }
        });
    }

    private void ensureAnimeTitleSet() {
        safeRunOnUiThread(() -> {
            String currentText = animeTitleView != null ? animeTitleView.getText().toString() : "";
            boolean isSkeleton = animeTitleView != null && Boolean.TRUE.equals(animeTitleView.getTag(R.id.tag_skeleton_active));
            
            if (currentText.isEmpty() || currentText.equals("Аниме") || isSkeleton) {
                String realTitle = null;
                if (currentAnimeInfo != null && currentAnimeInfo.getData() != null) {
                    realTitle = currentAnimeInfo.getData().getRus_name();
                    if (realTitle == null || realTitle.isEmpty()) {
                        realTitle = currentAnimeInfo.getData().getName();
                    }
                }
                if ((realTitle == null || realTitle.isEmpty()) && getIntent() != null) {
                    realTitle = getIntent().getStringExtra("EXTRA_ANIME_TITLE");
                    if (realTitle == null || realTitle.isEmpty()) {
                        realTitle = getIntent().getStringExtra("anime_title");
                    }
                }
                if (realTitle != null && !realTitle.isEmpty() && !realTitle.equals("Аниме")) {
                    if (animeTitleView != null) {
                        SkeletonHelper.hideSkeleton(animeTitleView, realTitle);
                    }
                    updatePortraitHeaderTitlesUI();
                }
            }
        });
    }

    private void updatePortraitHeaderTitlesUI() {
        safeRunOnUiThread(() -> {
            if (tvPortraitAnimeTitle != null) {
                String animeTitleStr = "";
                if (currentAnimeInfo != null && currentAnimeInfo.getData() != null) {
                    animeTitleStr = currentAnimeInfo.getData().getRus_name();
                    if (animeTitleStr == null || animeTitleStr.trim().isEmpty()) {
                        animeTitleStr = currentAnimeInfo.getData().getName();
                    }
                }
                if ((animeTitleStr == null || animeTitleStr.trim().isEmpty()) && getIntent() != null) {
                    String extra = getIntent().getStringExtra("EXTRA_ANIME_TITLE");
                    if (extra == null || extra.isEmpty()) {
                        extra = getIntent().getStringExtra("anime_title");
                    }
                    if (extra != null && !extra.trim().isEmpty()) {
                        animeTitleStr = extra.trim();
                    }
                }
                if ((animeTitleStr == null || animeTitleStr.trim().isEmpty()) && animeTitleView != null) {
                    boolean isSkeleton = Boolean.TRUE.equals(animeTitleView.getTag(R.id.tag_skeleton_active));
                    if (!isSkeleton) {
                        String viewText = animeTitleView.getText().toString().trim();
                        if (!viewText.isEmpty() && !viewText.equals("Аниме")) {
                            animeTitleStr = viewText;
                        }
                    }
                }

                if (animeTitleStr != null && !animeTitleStr.trim().isEmpty()) {
                    SkeletonHelper.hideSkeleton(tvPortraitAnimeTitle, animeTitleStr.trim());
                    tvPortraitAnimeTitle.setVisibility(View.VISIBLE);
                } else if (!isOfflineMode) {
                    SkeletonHelper.showSkeleton(tvPortraitAnimeTitle, 180);
                    tvPortraitAnimeTitle.setVisibility(View.VISIBLE);
                } else {
                    SkeletonHelper.hideSkeleton(tvPortraitAnimeTitle, "Аниме");
                    tvPortraitAnimeTitle.setVisibility(View.VISIBLE);
                }
            }
            if (tvPortraitEpisodeTitle != null) {
                EpisodesListResponse.EpisodeItem curEp = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
                if (curEp != null) {
                    String num = curEp.getNumber() != null ? curEp.getNumber().trim() : "";
                    String name = cleanEpisodeName(curEp.getName(), num);
                    String displayEpTitle;
                    if (!num.isEmpty() && !name.isEmpty()) {
                        displayEpTitle = num + " серия · " + name;
                    } else if (!num.isEmpty()) {
                        displayEpTitle = num + " серия";
                    } else if (!name.isEmpty()) {
                        displayEpTitle = name;
                    } else {
                        displayEpTitle = "";
                    }
                    if (!displayEpTitle.isEmpty()) {
                        SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, displayEpTitle);
                        tvPortraitEpisodeTitle.setVisibility(View.VISIBLE);
                    } else {
                        SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, "");
                        tvPortraitEpisodeTitle.setVisibility(View.GONE);
                    }
                } else if (isOfflineMode) {
                    String epNum = getIntent() != null ? getIntent().getStringExtra("EXTRA_EPISODE_NUMBER") : null;
                    String epName = getIntent() != null ? getIntent().getStringExtra("EXTRA_EPISODE_TITLE") : null;
                    String cleanName = cleanEpisodeName(epName, epNum);
                    String displayEpTitle;
                    if (epNum != null && !epNum.trim().isEmpty() && !cleanName.isEmpty()) {
                        displayEpTitle = epNum.trim() + " серия · " + cleanName;
                    } else if (epNum != null && !epNum.trim().isEmpty()) {
                        displayEpTitle = epNum.trim() + " серия";
                    } else if (!cleanName.isEmpty()) {
                        displayEpTitle = cleanName;
                    } else {
                        displayEpTitle = "";
                    }
                    if (!displayEpTitle.isEmpty()) {
                        SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, displayEpTitle);
                        tvPortraitEpisodeTitle.setVisibility(View.VISIBLE);
                    } else {
                        SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, "");
                        tvPortraitEpisodeTitle.setVisibility(View.GONE);
                    }
                } else {
                    SkeletonHelper.showSkeleton(tvPortraitEpisodeTitle, 120);
                    tvPortraitEpisodeTitle.setVisibility(View.VISIBLE);
                }
            }
        });
    }
    
    /**
     * Инициализация связанных тайтлов
     */
    private void initializeRelatedTitles() {
        if (playerRelatedTitlesController != null) {
            playerRelatedTitlesController.initialize(
                    relatedTitlesOverlay,
                    relatedTitlesDimOverlay,
                    relatedTitlesRecyclerView,
                    portraitRelatedTitlesContainer,
                    portraitRelatedTitlesRecyclerView,
                    findViewById(android.R.id.content)
            );
            if (currentAnimeId != null) {
                loadRelatedTitles();
            }
        }
    }

    private void loadRelatedTitles() {
        if (playerRelatedTitlesController != null) {
            playerRelatedTitlesController.loadRelatedTitles(currentAnimeId, animeUrl);
        }
    }

    private void showRelatedTitles(List<RelatedTitlesResponse.RelatedTitle> relatedTitles) {
        if (playerRelatedTitlesController != null) {
            playerRelatedTitlesController.showRelatedTitles(relatedTitles);
        }
    }

    private void setAnimeInfoToRelatedPanel(AnimeInfoResponse.Data animeData) {
        if (playerRelatedTitlesController != null) {
            playerRelatedTitlesController.setAnimeInfoToRelatedPanel(animeData);
        }
    }

    private void onRelatedTitleSelected(RelatedTitlesResponse.Media media) {
        if (playerRelatedTitlesController != null) {
            playerRelatedTitlesController.onRelatedTitleSelected(media);
        }
    }

    /**
     * Настройка всех event listeners
     */
    private void setupEventListeners() {
        // Setup controller visibility listener
        setupControllerVisibilityListener();
        
        // Setup button click listeners
        setupButtonClickListeners();
        
        // Setup overlay touch listener
        setupOverlayTouchListener();
        
        // Setup player listener
        setupPlayerListener();
        
        // Setup gestures callback
        setupGesturesCallback();
        
        // Setup timebar scrub listener
        setupTimeBarListener();
    }
    
    /**
     * Настройка listener'а перемотки таймбара
     */
    private void setupTimeBarListener() {
        if (timeBar != null) {
            timeBar.addListener(new androidx.media3.ui.TimeBar.OnScrubListener() {
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
                        if (player != null) {
                            player.seekTo(position);
                        }
                        scheduleEndSeekingState(600);
                    } else {
                        scheduleEndSeekingState(0);
                    }
                }
            });
        }
    }
    
    /**
     * Настройка listener'а видимости контроллера
     */
    private void setupControllerVisibilityListener() {
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            boolean shouldBeVisible = visibility == View.VISIBLE;
            
            // Animate controller visibility with alpha
            animateControllerVisibility(shouldBeVisible);
            
            isControllerVisible = shouldBeVisible;
            
            // Update navigation buttons visibility
            episodesManager.updateEpisodeNavigationButtonsVisibility();
            
            // Update button visibility
            updateControllerButtonsVisibility(isControllerVisible);

            updatePlayPauseAndLoadingState(false);
        });
    }
    
    /**
     * Анимация видимости контроллера через alpha
     */
    private void animateControllerVisibility(boolean visible) {
        if (controllerView == null) return;
        
        // Cancel any existing animation
        controllerView.animate().cancel();
        if (downloadProgressText != null) {
            downloadProgressText.animate().cancel();
        }
        
        if (visible) {
            // Show controller with fade in
            controllerView.setVisibility(View.VISIBLE);
            controllerView.animate()
                    .alpha(1.0f)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withStartAction(() -> {
                        // Уведомляем TimecodeManager что контроллер стал видимым
                        if (timecodeManager != null) {
                            timecodeManager.setControllerVisibility(true);
                        }
                    })
                    .start();

            boolean isPortrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            if (isDownloading && downloadProgressText != null && !isPortrait) {
                downloadProgressText.setVisibility(View.VISIBLE);
                downloadProgressText.animate()
                        .alpha(1.0f)
                        .setDuration(200)
                        .start();
            } else if (downloadProgressText != null) {
                downloadProgressText.setVisibility(View.GONE);
            }
        } else {
            // Hide controller with fade out
            controllerView.animate()
                    .alpha(0.0f)
                    .setDuration(150)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withStartAction(() -> {
                        // Уведомляем TimecodeManager что контроллер стал скрытым
                        if (timecodeManager != null) {
                            timecodeManager.setControllerVisibility(false);
                        }
                    })
                    .withEndAction(() -> {
                        // Set visibility to GONE after animation completes
                        if (controllerView.getAlpha() == 0.0f) {
                            controllerView.setVisibility(View.GONE);
                        }
                        updatePlayPauseAndLoadingState(true);
                    })
                    .start();

            if (downloadProgressText != null && downloadProgressText.getVisibility() == View.VISIBLE) {
                downloadProgressText.animate()
                        .alpha(0.0f)
                        .setDuration(150)
                        .withEndAction(() -> {
                            downloadProgressText.setVisibility(View.GONE);
                        })
                        .start();
            }
        }
        updatePlayPauseAndLoadingState(true);
    }
    
    
    /**
     * Обновление видимости кнопок контроллера
     */
    private void updateControllerButtonsVisibility(boolean visible) {
        boolean isPortrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        int visibility = visible ? View.VISIBLE : View.GONE;
        
        if (settingsButton != null) settingsButton.setVisibility(visibility);
        updateSettingsQualityTag();
        
        if (menuToggleFullscreen != null) {
            menuToggleFullscreen.setVisibility(visibility);
            menuToggleFullscreen.setImageResource(isPortrait ? R.drawable.ic_fullscreen : R.drawable.ic_fullscreen_exit);
        }

        if (!isPortrait) {
            if (episodesMenuButton != null) episodesMenuButton.setVisibility(visibility);
            if (menuToggleButton != null) menuToggleButton.setVisibility(visibility);
            if (playerCommentsController != null) {
                playerCommentsController.updateCommentsButtonVisibility(visible);
            }
        } else {
            if (episodesMenuButton != null) episodesMenuButton.setVisibility(View.GONE);
            if (menuToggleButton != null) menuToggleButton.setVisibility(View.GONE);
            if (playerCommentsController != null) {
                playerCommentsController.updateCommentsButtonVisibility(false);
            }
        }
    }

    private void updateSettingsQualityTag() {
        if (settingsQualityTag != null) {
            if (settingsButton != null && settingsButton.getVisibility() != View.VISIBLE) {
                settingsQualityTag.setVisibility(View.GONE);
                return;
            }
            String qualityToUse = null;
            com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
            if (downloadedEp != null && downloadedEp.getLocalFilePath() != null && new java.io.File(downloadedEp.getLocalFilePath()).exists()) {
                String dq = downloadedEp.getQuality();
                if (dq == null || dq.isEmpty()) dq = "1080p";
                qualityToUse = dq;
            } else if (player != null && player.getVideoFormat() != null && player.getVideoFormat().height > 0) {
                int height = player.getVideoFormat().height;
                if (height >= 2160) qualityToUse = "2160p";
                else if (height >= 1440) qualityToUse = "1440p";
                else if (height >= 1080) qualityToUse = "1080p";
                else if (height >= 720) qualityToUse = "720p";
                else qualityToUse = "480p";
            } else if (currentVideoUrl != null && !currentVideoUrl.isEmpty()) {
                if (currentVideoUrl.contains("2160") || currentVideoUrl.contains("4k")) qualityToUse = "2160p";
                else if (currentVideoUrl.contains("1080") || currentVideoUrl.contains("fhd")) qualityToUse = "1080p";
                else if (currentVideoUrl.contains("720") || currentVideoUrl.contains("hd")) qualityToUse = "720p";
                else if (currentVideoUrl.contains("480") || currentVideoUrl.contains("360") || currentVideoUrl.contains("240")) qualityToUse = "480p";
            }

            if (qualityToUse == null || qualityToUse.isEmpty()) {
                if (!com.example.animelib.util.AutoQualityHelper.isAutoQuality(preferredQuality)) {
                    qualityToUse = preferredQuality;
                } else if (playersManager != null) {
                    List<String> available = playersManager.getAvailableQualities();
                    if (available != null && !available.isEmpty()) {
                        long estimate = 0;
                        try {
                            estimate = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).getBitrateEstimate();
                        } catch (Exception ignored) {}
                        String resolved = com.example.animelib.util.AutoQualityHelper.resolveBestQuality(this, available, preferredQuality, estimate);
                        if (resolved != null && !resolved.isEmpty()) {
                            qualityToUse = resolved;
                        }
                    }
                }
            }

            if (qualityToUse == null || qualityToUse.isEmpty()) {
                qualityToUse = preferredQuality;
            }

            String tag = com.example.animelib.util.FloatingBottomSheetUtils.getQualityTag(qualityToUse);
            if (tag != null && !tag.isEmpty()) {
                settingsQualityTag.setText(tag);
                settingsQualityTag.setVisibility(View.VISIBLE);
            } else {
                settingsQualityTag.setVisibility(View.GONE);
            }
        }
    }

    private void showSurroundSoundToast(boolean enabled) {
        // Toast notification disabled by user request
    }
    
    /**
     * Настройка click listeners для кнопок
     */
    private void setupButtonClickListeners() {
        // Navigation buttons
        if (prevEpisodeButton != null) {
            prevEpisodeButton.setOnClickListener(v -> {
                com.example.animelib.util.ItemAnimationUtils.animateItemClick(v, () -> {
                    if (prevEpisodeButton.isEnabled()) {
                        episodesManager.navigateToPreviousEpisode();
                    }
                });
            });
        }
        
        if (nextEpisodeButton != null) {
            nextEpisodeButton.setOnClickListener(v -> {
                com.example.animelib.util.ItemAnimationUtils.animateItemClick(v, () -> {
                    if (nextEpisodeButton.isEnabled()) {
                        episodesManager.navigateToNextEpisode();
                    }
                });
            });
        }
        
        // Menu buttons
        if (menuToggleButton != null) {
            menuToggleButton.setOnClickListener(v -> toggleMenu());
        }
        
        if (closeMenuButton != null) {
            closeMenuButton.setOnClickListener(v -> {
                if (playersManager.getCurrentPlayerData() != null) {
                    playersManager.hideMenu();
                } else {
                    finish();
                }
            });
        }
        
        // Control buttons
        if (settingsButton != null) {
            settingsButton.setOnClickListener(v -> showSettingsDialog());
        }
        
        if (menuToggleFullscreen != null) {
            menuToggleFullscreen.setOnClickListener(v -> toggleOrientation());
        }
        
        if (pipButton != null) {
            pipButton.setOnClickListener(v -> {
                // Автоматически сохраняем закладку перед переходом в PiP
                autoSaveBookmark();
                if (playerPipController != null) {
                    playerPipController.enterPictureInPictureMode();
                }
            });
        }
        
        if (ibClosePlayer != null) {
            ibClosePlayer.setOnClickListener(v -> {
                // Автоматически сохраняем закладку перед закрытием
                autoSaveBookmark();
                finish();
            });
        }

        if (episodesMenuButton != null) {
            episodesMenuButton.setOnClickListener(v -> toggleEpisodesInController());
        }

        View.OnClickListener downloadClickListener = v -> {
            if (DownloadService.isRunning()) {
                showDownloadProgressBottomSheet();
            } else {
                showDownloadBottomSheet();
            }
        };

        if (downloadButton != null) {
            downloadButton.setOnClickListener(downloadClickListener);
        }
        if (downloadButtonTop != null) {
            downloadButtonTop.setOnClickListener(downloadClickListener);
        }
        if (btnDownloadFromMenu != null) {
            btnDownloadFromMenu.setOnClickListener(downloadClickListener);
        }
        if (portraitDownloadButton != null) {
            portraitDownloadButton.setOnClickListener(downloadClickListener);
        }
        
        if (bookmarkButton != null) {
            Log.d("VideoPlayer", "Bookmark button found, visibility: " + bookmarkButton.getVisibility() + 
                               ", enabled: " + bookmarkButton.isEnabled() + 
                               ", clickable: " + bookmarkButton.isClickable());
            
            // Изначально отключаем кнопку до готовности плеера
            bookmarkButton.setEnabled(false);
            bookmarkButton.setClickable(false);
            
            bookmarkButton.setOnClickListener(v -> {
                Log.d("VideoPlayer", "Bookmark button clicked!");
                addBookmark();
            });
        } else {
            Log.e("VideoPlayer", "Bookmark button is null!");
        }
        
        // Setup player control buttons
        setupPlayerControlButtons();
    }
    
    /**
     * Настройка touch listener для overlay
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupOverlayTouchListener() {
        if (menuOverlay == null) return;
        
        menuOverlay.setVisibility(View.GONE);
        menuOverlay.setAlpha(0f);
        menuOverlay.setClickable(false);
        
        menuOverlay.setOnTouchListener((v, event) -> {
            boolean commentsVisible = playerCommentsController != null && playerCommentsController.isCommentsVisible();
            if (!playersManager.isMenuVisible() && !commentsVisible) return false;
            if (event.getAction() != MotionEvent.ACTION_DOWN) return false;
            
            int x = (int) event.getRawX();
            int y = (int) event.getRawY();
            boolean insidePanel = false;
            
            if (slidingMenuPanel != null && playersManager.isMenuVisible()) {
                Rect panelRect = new Rect();
                slidingMenuPanel.getGlobalVisibleRect(panelRect);
                insidePanel = panelRect.contains(x, y);
            }
            
            if (!insidePanel) {
                if (playersManager.isMenuVisible()) playersManager.hideMenu();
                if (playerCommentsController != null && playerCommentsController.isCommentsVisible()) {
                    playerCommentsController.hideCommentsPanel();
                }
                return true;
            }
            return false;
        });
    }
    
    /**
     * Настройка player listener
     */
    private void setupPlayerListener() {
        if (player == null) return;
        if (playerEventListener != null) {
            player.removeListener(playerEventListener);
        }
        playerEventListener = new Player.Listener() {
            @Override
            public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    lastValidAspectRatio = (float) (videoSize.width * videoSize.pixelWidthHeightRatio) / (float) videoSize.height;
                }
                preservePlayerAspectRatio();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d("PlayerControls", "Playback state changed: " + playbackState);
                if (playbackState == Player.STATE_READY) {
                    if (hasRenderedFirstFrame || (player != null && player.isPlaying())) {
                        isVideoLoading = false;
                    }
                } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    isSeeking = false;
                    isVideoLoading = false;
                    if (seekResetHandler != null) {
                        seekResetHandler.removeCallbacksAndMessages(null);
                    }
                }
                updatePlayerControlsState();
                if (episodesManager != null) {
                    episodesManager.updateEpisodeNavigationButtonsVisibility();
                }
                startBufferingMonitoring();
                updatePlayPauseAndLoadingState(true);

                // Handle auto-play next episode
                if (playbackState == Player.STATE_ENDED && playerNextEpisodeController != null) {
                    playerNextEpisodeController.handlePlaybackEnded(autoPlay);
                }
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    startSeekingState();
                    scheduleEndSeekingState(600);
                } else {
                    startBufferingMonitoring();
                    updatePlayPauseAndLoadingState(true);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                Log.d("PlayerControls", "First frame rendered!");
                hasRenderedFirstFrame = true;
                isVideoLoading = false;
                isSeeking = false;
                startBufferingMonitoring();
                updatePlayPauseAndLoadingState(true);
                ensureAnimeTitleSet();
            }

            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                startBufferingMonitoring();
                updatePlayPauseAndLoadingState(true);
                if (!isLoading) {
                    ensureAnimeTitleSet();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                Log.d("PlayerControls", "Is playing changed: " + isPlaying);
                if (isPlaying) {
                    hasRenderedFirstFrame = true;
                    isVideoLoading = false;
                    if (!isScrubbingTimeBar) {
                        isSeeking = false;
                        if (seekResetHandler != null) {
                            seekResetHandler.removeCallbacksAndMessages(null);
                        }
                    }
                    startViewProgressTracking();
                    ensureAnimeTitleSet();
                } else {
                    stopViewProgressTracking();
                }
                updatePlayerControlsState();
                startBufferingMonitoring();
                updatePlayPauseAndLoadingState(true);
                if (playerPipController != null && playerPipController.isInPictureInPictureMode()) {
                    playerPipController.updatePictureInPictureParams();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e("PlayerControls", "Player error: " + error.getMessage());
                isSeeking = false;
                isVideoLoading = false;
                if (seekResetHandler != null) {
                    seekResetHandler.removeCallbacksAndMessages(null);
                }
                updatePlayerControlsState();
                updatePlayPauseAndLoadingState(true);
            }

            @Override
            public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
                updatePlayPauseAndLoadingState(true);
            }
        };
        player.addListener(playerEventListener);
    }

    private void startViewProgressTracking() {
        if (playerProgressController != null) {
            playerProgressController.startViewProgressTracking();
        }
    }

    private void stopViewProgressTracking() {
        if (playerProgressController != null) {
            playerProgressController.stopViewProgressTracking();
        }
    }
    
    /**
     * Настройка gestures callback
     */
    private void setupGesturesCallback() {
        gesturesManager.setGestureCallback(new GesturesManager.GestureCallback() {
            @Override
            public void onSeekGesture(long seekPosition) {
                Log.d("VideoPlayer", "Seek gesture: " + seekPosition);
                startSeekingState();
                scheduleEndSeekingState(600);
            }
            
            @Override
            public void onSpeedChange(float speed) {
                Log.d("VideoPlayer", "Speed change: " + speed);
            }
            
            @Override
            public void updatePlayLoadingIndicator(int playbackState) {
                VideoPlayerActivity.this.updatePlayLoadingIndicator(playbackState);
            }
            
            @Override
            public void onEpisodesSwipeUp() {
                Log.d("VideoPlayer", "Episodes swipe up detected");
                // Открываем панель эпизодов
                if (episodesManager != null) {
                    episodesManager.showEpisodesMenu();
                }
            }
            
            @Override
            public void onEpisodesSwipeDown() {
                Log.d("VideoPlayer", "Episodes swipe down detected");
                // Закрываем панель эпизодов
                if (episodesManager != null) {
                    episodesManager.hideEpisodesMenu();
                }
            }
            
            @Override
            public void onCommentsSwipeFromRight() {
                Log.d("VideoPlayer", "Comments swipe from right detected");
                // Открываем панель комментариев
                if (playerCommentsController != null) {
                    playerCommentsController.showCommentsPanel();
                }
            }
            
            @Override
            public void onPlayersSwipeFromRight() {
                Log.d("VideoPlayer", "Players swipe from right detected");
                // Открываем панель озвучек
                if (playersManager != null) {
                    playersManager.showMenu();
                }
            }
            
            @Override
            public void onEpisodesDragProgress(float progress) {
                // ВЕРТИКАЛЬНЫЕ ЖЕСТЫ: Эпизоды теперь обрабатываются в VerticalGesturesManager
                // Этот метод больше не вызывается для вертикальных свайпов
            }
            
            @Override
            public void onRelatedTitlesDragProgress(float progress) {
                // ВЕРТИКАЛЬНЫЕ ЖЕСТЫ: Related titles теперь обрабатываются в VerticalGesturesManager
                // Этот метод больше не вызывается для вертикальных свайпов
            }
            
            @Override
            public void onCommentsDragProgress(float progress) {
                if (isOfflineMode) return;
                
                // Скрываем худ плеера при начале drag панели комментариев
                if (progress > 0 && playerView != null) {
                    playerView.hideController();
                }
                
                // Обновляем прогресс вытягивания панели комментариев
                if (commentsPanelContainer != null) {
                    commentsPanelContainer.setDragProgress(progress);
                }
            }
            
            @Override
            public void onPlayersDragProgress(float progress) {
                if (isOfflineMode) return;
                
                // Скрываем худ плеера при начале drag панели озвучек
                if (progress > 0 && playerView != null) {
                    playerView.hideController();
                }
                
                // Обновляем прогресс вытягивания панели озвучек
                if (menuPanelContainer != null) {
                    menuPanelContainer.setDragProgress(progress);
                }
            }
            
            @Override
            public void onPanelDragComplete(GesturesManager.EdgeSwipeType type, boolean shouldOpen) {
                if (isOfflineMode) return;
                Log.d("VideoPlayer", "Panel drag complete: " + type + ", shouldOpen=" + shouldOpen);
                
                // ВЕРТИКАЛЬНЫЕ ЖЕСТЫ (эпизоды и связанные тайтлы) ОБРАБАТЫВАЮТСЯ В VerticalGesturesManager
                // Здесь только горизонтальные (комментарии и озвучки)
                
                switch (type) {
                    case COMMENTS_RIGHT:
                        // Даем DraggableSidePanel завершить анимацию, затем обновляем состояние
                        if (commentsPanelContainer != null) {
                            commentsPanelContainer.completeDrag(shouldOpen);
                        }
                        // Обновляем состояние менеджера после завершения анимации
                        if (playerCommentsController != null) {
                            playerCommentsController.updateDragState(shouldOpen);
                        }
                        break;
                        
                    case PLAYERS_RIGHT:
                        // Даем DraggableSidePanel завершить анимацию, затем обновляем состояние
                        if (menuPanelContainer != null) {
                            menuPanelContainer.completeDrag(shouldOpen);
                        }
                        // Обновляем состояние менеджера после завершения анимации
                        if (playersManager != null) {
                            playersManager.updateDragState(shouldOpen);
                        }
                        break;
                }
            }
            
            @Override
            public boolean isEpisodesMenuVisible() {
                return episodesManager != null && episodesManager.isEpisodesMenuVisible();
            }
            
            @Override
            public boolean isRelatedTitlesMenuVisible() {
                RelatedTitlesManager rtm = playerRelatedTitlesController != null ? playerRelatedTitlesController.getRelatedTitlesManager() : null;
                return rtm != null && rtm.isRelatedTitlesVisible();
            }
            
            @Override
            public void onDoubleTapSkip(boolean isForward, int skipDurationSeconds) {
                if (player == null) {
                    Log.w("VideoPlayer", "Player is null, cannot perform skip");
                    return;
                }

                long currentPosition = player.getCurrentPosition();
                long skipDuration = skipDurationSeconds * 1000L;
                long newPosition = isForward ? currentPosition + skipDuration : currentPosition - skipDuration;
                long duration = player.getDuration();
                
                // Ограничиваем позицию границами видео
                newPosition = Math.max(0, newPosition);
                if (duration > 0) {
                    newPosition = Math.min(newPosition, duration);
                }
                
                startSeekingState();
                player.seekTo(newPosition);
                scheduleEndSeekingState(600);
                Log.d("VideoPlayer", "Double tap skip: " + (isForward ? "forward" : "backward") + 
                      " to " + (newPosition / 1000) + "s (skip=" + (skipDuration / 1000) + "s)");
            }
        });
        
        // Setup vertical gestures callback
        verticalGesturesManager.setCallback(new VerticalGesturesManager.VerticalGestureCallback() {
            @Override
            public void onEpisodesDragProgress(float progress) {
                // Показываем интерфейс плеера при начале drag (первый вызов с progress > 0)
                if (progress > 0 && playerView != null && !playerView.isControllerFullyVisible()) {
                    playerView.showController();
                    Log.d("VideoPlayer", "Showing controller on episodes drag start");
                }
                
                if (episodesManager != null) {
                    episodesManager.setDragProgress(progress);
                }
            }
            
            @Override
            public void onRelatedInfoDragProgress(float progress) {
                // Отключено по запросу (верхняя шторка с инфой)
            }
            
            @Override
            public void onEpisodesDragComplete(boolean shouldOpen) {
                Log.d("VideoPlayer", "Episodes drag complete: shouldOpen=" + shouldOpen);
                if (episodesManager != null) {
                    episodesManager.completeDrag(shouldOpen);
                }
            }
            
            @Override
            public void onRelatedInfoDragComplete(boolean shouldOpen) {
                // Отключено по запросу
            }
            
            @Override
            public boolean isEpisodesOpen() {
                return episodesManager != null && episodesManager.isEpisodesMenuVisible();
            }
            
            @Override
            public boolean isRelatedInfoOpen() {
                return false; // Отключена верхняя шторка
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
    
    private void showGestureIndicator(int iconResId, int percent) {
        if (gestureIndicatorHandler != null && hideGestureIndicatorsRunnable != null) {
            gestureIndicatorHandler.removeCallbacks(hideGestureIndicatorsRunnable);
        }
        
        if (gestureIndicatorIcon != null) {
            gestureIndicatorIcon.setImageResource(iconResId);
        }
        if (gestureIndicatorProgress != null) {
            gestureIndicatorProgress.setProgress(percent);
        }
        if (gestureIndicatorText != null) {
            gestureIndicatorText.setText(percent + "%");
        }
        
        if (gestureIndicatorPlaque != null) {
            if (gestureIndicatorPlaque.getVisibility() != View.VISIBLE) {
                gestureIndicatorPlaque.animate().cancel();
                gestureIndicatorPlaque.setVisibility(View.VISIBLE);
                gestureIndicatorPlaque.setAlpha(0f);
                gestureIndicatorPlaque.setScaleX(0.85f);
                gestureIndicatorPlaque.setScaleY(0.85f);
                gestureIndicatorPlaque.animate()
                        .alpha(1.0f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                        .start();
            } else {
                if (gestureIndicatorPlaque.getAlpha() < 1.0f) {
                    gestureIndicatorPlaque.animate().cancel();
                    gestureIndicatorPlaque.setAlpha(1.0f);
                    gestureIndicatorPlaque.setScaleX(1.0f);
                    gestureIndicatorPlaque.setScaleY(1.0f);
                }
            }
        }
    }

    private void scheduleHideGestureIndicators() {
        if (gestureIndicatorHandler != null) {
            if (hideGestureIndicatorsRunnable == null) {
                hideGestureIndicatorsRunnable = this::hideGestureIndicatorWithAnimation;
            }
            gestureIndicatorHandler.removeCallbacks(hideGestureIndicatorsRunnable);
            gestureIndicatorHandler.postDelayed(hideGestureIndicatorsRunnable, 800);
        }
    }

    private void hideGestureIndicatorWithAnimation() {
        if (gestureIndicatorPlaque != null && gestureIndicatorPlaque.getVisibility() == View.VISIBLE) {
            gestureIndicatorPlaque.animate().cancel();
            gestureIndicatorPlaque.animate()
                    .alpha(0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        gestureIndicatorPlaque.setVisibility(View.GONE);
                        gestureIndicatorPlaque.setAlpha(1.0f);
                        gestureIndicatorPlaque.setScaleX(1.0f);
                        gestureIndicatorPlaque.setScaleY(1.0f);
                    })
                    .start();
        }
    }

    /**
     * Настройка начального состояния UI
     */
    private void configureInitialState() {
        // Hide controller buttons initially
        updateControllerButtonsVisibility(false);
        
        // Set initial episode navigation buttons visibility
        if (prevEpisodeButton != null) prevEpisodeButton.setVisibility(View.VISIBLE);
        if (nextEpisodeButton != null) nextEpisodeButton.setVisibility(View.VISIBLE);
        
        // Players control bar position is now managed by EpisodesManager
    }
    
    /**
     * Настройка callbacks для менеджеров
     */
    private void setupManagerCallbacks() {
        // Comments controller callback
        if (playerCommentsController != null) {
            playerCommentsController.setCallback(new PlayerCommentsController.CommentsCallback() {
                @Override
                public void onHideControllerRequested() {
                    if (playerView != null) {
                        playerView.hideController();
                    }
                }

                @Override
                public void onUpdateNavigationRequested() {
                    if (episodesManager != null) {
                        episodesManager.updateEpisodeNavigationButtonsVisibility();
                    }
                }
            });
        }
        
        // Episodes manager callbacks
        episodesManager.setEpisodeSelectionCallback(this::onEpisodeSelected);
        episodesManager.setVisibilityCallback(isVisible -> {
            if (ambientLightManager != null) {
                ambientLightManager.resume();
            }
        });
        
        // Related titles manager callbacks
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
                Log.d("VideoPlayer", "Episodes loaded: " + episodes.size());
                
                // Сначала пытаемся загрузить эпизод из закладки
                if (currentAnimeId != null) {
                    Log.d("VideoPlayer", "Trying to load episode from bookmark for anime: " + currentAnimeId);
                    
                    // Получаем media_slug для закладки
                    String animeUrl = getIntent().getStringExtra("anime_url");
                    String mediaSlug = null;
                    if (animeUrl != null && !animeUrl.isEmpty()) {
                        mediaSlug = ApiService.extractMediaSlugFromUrl(animeUrl);
                    }
                    
                    if (mediaSlug != null) {
                        episodesManager.getBookmarkManager().getLastEpisodeFromBookmark(mediaSlug, episodes, 
                            new BookmarkManager.LastEpisodeCallback() {
                                @Override
                                public void onLastEpisodeFound(EpisodesListResponse.EpisodeItem episode, String progress) {
                                    Log.d("VideoPlayer", "Found bookmarked episode: " + episode.getNumber() + 
                                                         ", progress: " + progress);
                                    
                                    // Сохраняем таймкод из закладки
                                    long parsedBm = episodesManager.getBookmarkManager().parseTimecodeToMilliseconds(progress);
                                    if (playerEpisodesController != null) {
                                        playerEpisodesController.setBookmarkTimecode(parsedBm);
                                        playerEpisodesController.setNewEpisodeSelection(true);
                                    } else {
                                        bookmarkTimecode = parsedBm;
                                        isNewEpisodeSelection = true;
                                    }
                                    Log.d("VideoPlayer", "Parsed bookmark timecode: " + progress + " -> " + parsedBm + "ms");
                                    
                                    // Устанавливаем красный цвет кнопки для эпизода с закладкой
                                    updateBookmarkButtonColor(true);
                                    autoPlayOnPrepare = VideoPlayerActivity.this.autoPlay;
                                    episodesManager.setCurrentEpisode(episode);
                                    if (playerCommentsController != null) {
                                        playerCommentsController.setCurrentEpisode(episode);
                                    }
                                    
                                    // СРАЗУ обновляем заголовок с номером эпизода
                                    updateEpisodeHeaderQuick();
                                    
                                    // ВАЖНО: Загружаем плееры для ПРАВИЛЬНОГО эпизода
                                    Log.d("VideoPlayer", "Loading players for bookmarked episode: " + episode.getId());
                                    playersManager.loadPlayersForEpisode(episode.getId());
                                }
                                
                                @Override
                                public void onNoBookmarkFound() {
                                    Log.d("VideoPlayer", "No bookmark found, loading first episode");
                                    loadFirstEpisode();
                                }
                            });
                    } else {
                        Log.d("VideoPlayer", "No media slug available, falling back to URL detection");
                        fallbackToUrlDetection();
                    }
                } else {
                    // Нет anime ID, используем URL detection
                    fallbackToUrlDetection();
                }
            }
            
            @Override
            public void onEpisodesError(String error) {
                Log.e("VideoPlayer", "Error loading episodes: " + error);
                showVideoErrorDialog("Ошибка загрузки озвучек", "Не удалось загрузить список эпизодов и озвучек.", error, () -> {
                    if (currentAnimeId != null) {
                        loadEpisodes(currentAnimeId);
                    }
                }, true);
                initializeMenuWithoutAutoPlay();
            }
        });
        
        // Players manager callbacks
        playersManager.setPlayerSelectionCallback(this::onPlayerSelected);
        
        // Устанавливаем callback для загрузки плееров
        playersManager.setDataCallback(new PlayersManager.PlayersDataCallback() {
            @Override
            public void onPlayersLoaded(List<EpisodeResponse.PlayerData> players) {
                Log.d("VideoPlayer", "Players loaded: " + players.size() + " for episode: " + 
                      (episodesManager.getCurrentEpisode() != null ? episodesManager.getCurrentEpisode().getNumber() : "unknown"));
                
                // Проверяем нужно ли показывать меню
                // Показываем только если нет выбранной озвучки (первая загрузка)
                if (playersManager.getCurrentPlayerData() == null) {
                    Log.d("VideoPlayer", "No player selected yet, showing menu for first time");
                    showPlayerSelectionDialogWithAutoSelect(players);
                } else {
                    Log.d("VideoPlayer", "Player already selected, skipping menu (episode switch)");
                    // При переключении эпизода меню не показываем
                    // Автовыбор уже сработал в PlayersManager
                    if (menuLoadingOverlay != null) menuLoadingOverlay.setVisibility(View.GONE);
                }
            }
            
            @Override
            public void onPlayersError(String error) {
                Log.e("VideoPlayer", "Error loading players: " + error);
                com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
                if (downloadedEp != null && downloadedEp.getLocalFilePath() != null && new File(downloadedEp.getLocalFilePath()).exists()) {
                    Log.d("VideoPlayer", "Online players fetch failed, but episode is downloaded locally: " + downloadedEp.getLocalFilePath());
                    if (menuLoadingOverlay != null) menuLoadingOverlay.setVisibility(View.GONE);
                    hideLoading();
                    EpisodeResponse.PlayerData syntheticData = createSyntheticPlayerDataForDownloaded(downloadedEp);
                    playersManager.setCurrentPlayerData(syntheticData);
                    onPlayerSelected(syntheticData);
                    return;
                }
                showVideoErrorDialog("Ошибка загрузки плееров", "Не удалось загрузить список плееров.", error, () -> {
                    EpisodesListResponse.EpisodeItem episode = episodesManager.getCurrentEpisode();
                    if (episode != null) {
                        playersManager.loadPlayersForEpisode(episode.getId());
                    }
                }, true);
            }
        });
        
        // Показываем placeholder когда нет выбранной озвучки
        playersManager.setVisibilityCallback(isVisible -> {
            if (isVisible && playerView != null) {
                playerView.hideController();
            }
            if (!isVisible && playersManager.getCurrentPlayerData() == null) {
                // Меню закрыто и нет выбранной озвучки - показываем placeholder
                showAnimeInfoPlaceholder();
            }
        });
    }
    
    /**
     * Запуск начальной загрузки
     */
    private void startInitialLoading() {
        if (isOfflineMode) {
            return;
        }
        boolean isPortrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        if (!isPortrait) {
            playersManager.showMenu();
            showAnimeInfoPlaceholder();
        }
        if (menuLoadingOverlay != null) {
            menuLoadingOverlay.setVisibility(View.VISIBLE);
        }
        
        // Показываем anime info placeholder и загружаем информацию
        // Placeholder будет скрыт автоматически при выборе озвучки
        loadAnimeInfoForPlaceholder();
    }
    
    /**
     * Показывает placeholder с информацией об аниме (вырезано по запросу пользователя)
     */
    private void showAnimeInfoPlaceholder() {
        // Вырезано по запросу пользователя: боковое окно информации об аниме в горизонтальном режиме не показывается
    }

    private void hideAnimeInfoPlaceholder() {
        if (playerAnimeInfoController != null) {
            playerAnimeInfoController.hidePlaceholderAnimated();
        }
    }

    private void loadAnimeInfoForPlaceholder() {
        if (animeUrl == null) return;
        String animeSlug = apiService.extractAnimeSlug(animeUrl);
        if (animeSlug == null) return;

        if (playerAnimeInfoController != null) {
            playerAnimeInfoController.showSkeletons();
        }

        apiService.fetchAnimeInfo(animeSlug, new ApiService.AnimeInfoCallback() {
            @Override
            public void onAnimeInfoReceived(AnimeInfoResponse response) {
                runOnUiThread(() -> displayAnimeInfo(response));
            }

            @Override
            public void onError(String errorMessage) {
                Log.e("VideoPlayer", "Failed to load anime info: " + errorMessage);
                runOnUiThread(() -> {
                    if (playerAnimeInfoController != null) {
                        playerAnimeInfoController.showError(() -> loadAnimeInfoForPlaceholder());
                    }
                });
            }
        });
    }

    @SuppressLint("SetTextI18n")
    private void displayAnimeInfo(AnimeInfoResponse animeInfo) {
        if (animeInfo == null || animeInfo.getData() == null) return;
        this.currentAnimeInfo = animeInfo;
        setAnimeInfoToRelatedPanel(animeInfo.getData());

        if (playerAnimeInfoController != null) {
            playerAnimeInfoController.displayAnimeInfo(this, animeInfo);
            if (playerAnimeInfoController.getCurrentPosterUrl() != null) {
                currentPosterUrl = playerAnimeInfoController.getCurrentPosterUrl();
            }
        }

        String rus = animeInfo.getData().getRus_name();
        if (rus == null || rus.isEmpty()) rus = animeInfo.getData().getName();
        if (rus != null && !rus.isEmpty() && animeTitleView != null) {
            SkeletonHelper.hideSkeleton(animeTitleView, rus);
            updatePortraitHeaderTitlesUI();
        }
    }

    private void toggleMenu() {
        if (isOfflineMode) return;
        playersManager.toggleMenu();
    }
    
    private void toggleEpisodesInController() {
        // Episodes are now managed by EpisodesManager through the side menu
        if (episodesManager != null) {
            episodesManager.toggleEpisodesMenu();
        }
    }

    private void hideEpisodesInController() {
        // Episodes are now managed by EpisodesManager through the side menu
        if (episodesManager != null) {
            episodesManager.hideEpisodesMenu();
        }
    }

    private void updatePlayerControlsState() {
        if (player == null) return;

        runOnUiThread(() -> {
            View controllerView = playerView.findViewById(R.id.exo_controller);
            if (controllerView != null) {
                ImageButton skipForwardButton = controllerView.findViewById(R.id.skipForwardButton);

                // Включаем/выключаем кнопки в зависимости от состояния плеера
                boolean canSeek = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);

                if (skipForwardButton != null) {
                    skipForwardButton.setEnabled(canSeek);
                    skipForwardButton.setAlpha(canSeek ? 1.0f : 0.5f);
                }

                // Обновляем видимость play/pause кнопок
                updatePlayPauseButtonsVisibility();
            }
        });
    }

    private void updatePlayPauseButtonsVisibility() {
        updatePlayPauseAndLoadingState(true);
    }

    private void onPlayerSelected(EpisodeResponse.PlayerData playerData) {
        Log.d("VideoPlayer", "Player selected: " + playerData.getPlayer());

        if (playerData != null && "animelib".equalsIgnoreCase(playerData.getPlayer())) {
            if (playerData.getVideoDomain() != null && !playerData.getVideoDomain().isEmpty()) {
                currentVideoDomain = playerData.getVideoDomain();
                Log.d("VideoPlayer", "Set currentVideoDomain from API for animelib: " + currentVideoDomain);
            }
        }

        boolean isNew = playerEpisodesController != null && playerEpisodesController.isNewEpisodeSelection();
        if (isNew) {
            autoPlayOnPrepare = this.autoPlay;
            if (playerEpisodesController != null) {
                playerEpisodesController.setNewEpisodeSelection(false);
            }
        } else if (player != null) {
            autoPlayOnPrepare = player.getPlayWhenReady();
        } else {
            autoPlayOnPrepare = this.autoPlay;
        }

        hideAnimeInfoPlaceholder();

        updateEpisodeHeaderQuick();

        if (playerData.getPlayer() != null && playerData.getTeam() != null) {
            apiService.savePlayerPreferences(playerData.getPlayer(), playerData.getTeam().getId());
            Log.d("VideoPlayer", "Immediately saved player preferences: player=" + playerData.getPlayer() + 
                  ", teamId=" + playerData.getTeam().getId());
        }

        if (player != null) {
            long currentPos = player.getCurrentPosition();
            if (currentPos > 0) {
                if (playerEpisodesController != null) {
                    playerEpisodesController.setSavedPlayerPosition(currentPos);
                } else {
                    savedPlayerPosition = currentPos;
                }
            }
            Log.d("VideoPlayer", "Saved player position before switching to: " + playerData.getPlayer() + " (pos: " + currentPos + "ms)");
        }

        boolean isCurrentlyPlaying = player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY) && !isSeamlessSwitching;

        long currentPos = 0;
        boolean wasPlaying = true;
        if (player != null) {
            currentPos = player.getCurrentPosition();
            wasPlaying = player.getPlayWhenReady();
            if (currentPos > 0) {
                if (playerEpisodesController != null) {
                    playerEpisodesController.setSavedPlayerPosition(currentPos);
                } else {
                    savedPlayerPosition = currentPos;
                }
            }
        }

        if (playerData != null) {
            playersManager.setCurrentPlayerData(playerData);
            enableBookmarkButton();
            updateEpisodeHeaderQuick();
            updatePortraitVoiceoverPlayerUI();

            if ("animelib".equalsIgnoreCase(playerData.getPlayer())) {
                String newVideoUrl = resolveAnimelibUrl(playerData, preferredQuality);
                if (newVideoUrl != null && !newVideoUrl.equals(currentVideoUrl)) {
                    Log.d("VideoPlayer", "Seamlessly switching voiceover (Animelib) to: " + (playerData.getTeam() != null ? playerData.getTeam().getName() : ""));
                    if (isCurrentlyPlaying) {
                        switchQualitySeamlessly(preferredQuality, preferredQuality, newVideoUrl);
                    } else {
                        currentVideoUrl = newVideoUrl;
                        if (timecodeManager != null) {
                            timecodeManager.setTimecodes(playerData);
                        }
                        updateAnimeInfoHeaderFull();
                        switchMediaSource(newVideoUrl, currentPos, wasPlaying);
                    }
                    return;
                }
            } else if ("kodik".equalsIgnoreCase(playerData.getPlayer())) {
                String kodikSrc = playerData.getSrc();
                if (kodikSrc != null && !kodikSrc.isEmpty()) {
                    if (!kodikSrc.startsWith("http")) kodikSrc = "https:" + kodikSrc;
                    final String finalKodikSrc = kodikSrc;
                    final long posToRestore = currentPos;
                    final boolean playState = wasPlaying;
                    final boolean shouldBeSeamless = isCurrentlyPlaying;
                    Log.d("VideoPlayer", "Fetching Kodik voiceover links for: " + (playerData.getTeam() != null ? playerData.getTeam().getName() : ""));
                    if (!shouldBeSeamless) {
                        showLoading("Загрузка озвучки...");
                    }
                    apiService.fetchKodikVideoLinks(finalKodikSrc, new ApiService.KodikVideoCallback() {
                        @Override
                        public void onKodikVideoReceived(KodikResponse response) {
                            safeRunOnUiThread(() -> {
                                if (!shouldBeSeamless) {
                                    hideLoading();
                                }
                                currentKodikResponse = response;
                                if (playerQualityController != null) {
                                    playerQualityController.setCurrentKodikResponse(response);
                                }
                                if (playersManager != null) {
                                    playersManager.setCurrentKodikResponse(response);
                                }
                                String newVideoUrl = resolveKodikHlsUrl(response, preferredQuality);
                                if (newVideoUrl != null && !newVideoUrl.equals(currentVideoUrl)) {
                                    Log.d("VideoPlayer", "Seamlessly switching voiceover (Kodik) to: " + (playerData.getTeam() != null ? playerData.getTeam().getName() : ""));
                                    if (shouldBeSeamless && player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY)) {
                                        switchQualitySeamlessly(preferredQuality, preferredQuality, newVideoUrl);
                                    } else {
                                        currentVideoUrl = newVideoUrl;
                                        if (timecodeManager != null) {
                                            timecodeManager.setTimecodes(playerData);
                                        }
                                        updateAnimeInfoHeaderFull();
                                        switchMediaSource(newVideoUrl, posToRestore, playState);
                                    }
                                } else {
                                    stopCurrentPlaybackAndRestartVoiceover(playerData);
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            safeRunOnUiThread(() -> {
                                hideLoading();
                                stopCurrentPlaybackAndRestartVoiceover(playerData);
                            });
                        }
                    });
                    return;
                }
            }
        }

        stopCurrentPlaybackAndRestartVoiceover(playerData);
    }

    private void stopCurrentPlaybackAndRestartVoiceover(EpisodeResponse.PlayerData playerData) {
        preservePlayerAspectRatio();
        applyPlayerSidePanelTransform(0f);
        stopCurrentPlayback();

        playersManager.setCurrentPlayerData(playerData);

        List<String> onlineQualities = playersManager.getAvailableQualities();
        List<String> newQualities = getQualitiesWithDownloadedOption(onlineQualities);

        enableBookmarkButton();

        Log.d("VideoPlayer", "Player selected, ready to start playback");
        
        if (!newQualities.isEmpty()) {
            executor.execute(() -> {
                com.example.animelib.data.entity.PlayerPreferences prefs = apiService.loadPlayerPreferences();
                String savedQuality = (prefs != null) ? prefs.getPreferredQuality() : null;
                
                safeRunOnUiThread(() -> {
                    String newPreferredQuality = null;
                    
                    com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
                    if (downloadedEp != null && downloadedEp.getLocalFilePath() != null && new java.io.File(downloadedEp.getLocalFilePath()).exists()) {
                        String dq = downloadedEp.getQuality();
                        if (dq == null || dq.isEmpty()) dq = "1080p";
                        else if (!dq.endsWith("p") && !dq.equalsIgnoreCase("4k")) dq += "p";
                        newPreferredQuality = "Скачанный файл (" + dq + ")";
                        Log.d("VideoPlayer", "Downloaded episode found, preferring downloaded quality: " + newPreferredQuality);
                    } else {
                        if (savedQuality != null && !savedQuality.trim().isEmpty()) {
                            if (com.example.animelib.util.AutoQualityHelper.isAutoQuality(savedQuality)) {
                                newPreferredQuality = "Авто";
                            } else {
                                for (String q : newQualities) {
                                    if (!com.example.animelib.util.AutoQualityHelper.isAutoQuality(q) &&
                                            com.example.animelib.util.AutoQualityHelper.matchQuality(q, savedQuality)) {
                                        newPreferredQuality = q;
                                        break;
                                    }
                                }
                            }
                        }
                        if (newPreferredQuality == null) {
                            if (newQualities.contains("Авто")) {
                                newPreferredQuality = "Авто";
                                Log.d("VideoPlayer", "Defaulting to Auto quality option");
                            } else if (!newQualities.isEmpty()) {
                                newPreferredQuality = newQualities.get(0);
                                Log.d("VideoPlayer", "Saved quality not found, using top quality: " + newPreferredQuality);
                            }
                        }
                    }
                    
                    preferredQuality = newPreferredQuality;
                    updateSettingsQualityTag();
                    Log.d("VideoPlayer", "Updated preferred quality to: " + newPreferredQuality + " for player: " + playerData.getPlayer());
                    
                    if (playerData.getPlayer() != null && playerData.getTeam() != null) {
                        if (!com.example.animelib.util.AutoQualityHelper.isDownloadedQuality(preferredQuality)) {
                            apiService.savePlayerPreferences(playerData.getPlayer(), playerData.getTeam().getId(), preferredQuality);
                            Log.d("VideoPlayer", "Updated player preferences with quality: player=" + playerData.getPlayer() + 
                                  ", teamId=" + playerData.getTeam().getId() + ", quality=" + preferredQuality);
                        }
                    }
                    
                    if (playerDialogsController != null) {
                        playerDialogsController.updateSettingsQualities(newQualities, preferredQuality);
                    }
                    
                    long startPosition = playerEpisodesController != null ? playerEpisodesController.getStartPosition() : (savedPlayerPosition > 0 ? savedPlayerPosition : bookmarkTimecode);
                    Log.d("VideoPlayer", "Starting player with position: " + startPosition + "ms");
                    
                    if (playerData.getPlayer() != null && "animelib".equalsIgnoreCase(playerData.getPlayer())) {
                        handleAnimelibPlayer(playerData, startPosition);
                    } else if (playerData.getPlayer() != null && "kodik".equalsIgnoreCase(playerData.getPlayer())) {
                        handleKodikPlayer(playerData, startPosition);
                    }
                    
                    updateAnimeInfoHeaderFull();
                });
            });
        } else {
            long startPosition = playerEpisodesController != null ? playerEpisodesController.getStartPosition() : (savedPlayerPosition > 0 ? savedPlayerPosition : bookmarkTimecode);
            Log.d("VideoPlayer", "Starting player with position: " + startPosition + "ms (no qualities available)");
            
            if (playerData.getPlayer() != null && "animelib".equalsIgnoreCase(playerData.getPlayer())) {
                handleAnimelibPlayer(playerData, startPosition);
            } else if (playerData.getPlayer() != null && "kodik".equalsIgnoreCase(playerData.getPlayer())) {
                handleKodikPlayer(playerData, startPosition);
            }
            
            updateAnimeInfoHeaderFull();
        }
    }

    private void onEpisodeSelected(EpisodesListResponse.EpisodeItem episode, boolean autoPlay) {
        Log.d("VideoPlayer", "Episode selected: " + episode.getNumber() + " (ID: " + episode.getId() + "), autoPlay: " + autoPlay);

        autoPlayOnPrepare = this.autoPlay;
        isCurrentEpisodeMarkedViewed = false;

        // Auto-save bookmark for previous episode if applicable before switching
        autoSaveBookmark();

        // Stop current playback if playing
        stopCurrentPlayback();

        // Hide episodes in controller
        hideEpisodesInController();

        if (playerCommentsController != null) {
            playerCommentsController.resetCommentsOnEpisodeChange(true);
        }

        if (isOfflineMode) {
            episodesManager.setCurrentEpisode(episode);
            if (playerCommentsController != null) {
                playerCommentsController.setCurrentEpisode(episode);
            }
            episodesManager.updateEpisodeNavigationButtonsVisibility();
            episodesManager.updateEpisodesRecyclerView();
            updateEpisodeHeaderQuick();

            com.example.animelib.data.entity.DownloadedEpisodeEntity offlineEp = offlineEpisodesMap.get(episode.getNumber());
            if (offlineEp == null) {
                offlineEp = offlineEpisodesMap.get(String.valueOf(episode.getId()));
            }
            if (offlineEp != null && offlineEp.getLocalFilePath() != null) {
                playOfflineFile(offlineEp.getLocalFilePath(), offlineEp.getAnimeTitle(), offlineEp.getEpisodeName());
            } else {
                CustomToast.showWarning(this, "Файл серии не найден");
            }
            return;
        }

        episodesManager.setCurrentEpisode(episode);
        com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
        if ((isDownloadedQuality(preferredQuality) || isOfflineMode) && downloadedEp != null && downloadedEp.getLocalFilePath() != null && new File(downloadedEp.getLocalFilePath()).exists()) {
            Log.d("VideoPlayer", "Downloaded episode playback directly on episode change.");
            if (playerCommentsController != null) {
                playerCommentsController.setCurrentEpisode(episode);
            }
            episodesManager.updateEpisodeNavigationButtonsVisibility();
            episodesManager.updateEpisodesRecyclerView();
            updateEpisodeHeaderQuick();

            if (menuLoadingOverlay != null) menuLoadingOverlay.setVisibility(View.GONE);
            hideLoading();
            EpisodeResponse.PlayerData syntheticData = createSyntheticPlayerDataForDownloaded(downloadedEp);
            playersManager.setCurrentPlayerData(syntheticData);
            onPlayerSelected(syntheticData);
            return;
        }

        // Show loading and load players for this episode
        runOnUiThread(() -> {
            showLoading("Загрузка плееров для эпизода...");
            if (menuLoadingOverlay != null) menuLoadingOverlay.setVisibility(View.VISIBLE);
        });

        if (playerEpisodesController != null) {
            playerEpisodesController.onEpisodeSelected(episode, autoPlay);
        }
    }

    private void stopCurrentPlayback() {
        if (backgroundPlayer != null) {
            try {
                backgroundPlayer.stop();
                backgroundPlayer.release();
            } catch (Exception ignored) {}
            backgroundPlayer = null;
        }
        isSeamlessSwitching = false;
        if (player != null) {
            Log.d("VideoPlayer", "Stopping current playback");
            player.stop();
            player.clearMediaItems();
        }
    }

    private com.example.animelib.data.entity.DownloadedEpisodeEntity getDownloadedEpisodeForActive() {
        if (playerDownloadController != null) {
            String animeId = currentAnimeId != null ? currentAnimeId : (getIntent() != null ? getIntent().getStringExtra("EXTRA_ANIME_ID") : null);
            String localPath = getIntent() != null ? getIntent().getStringExtra("EXTRA_LOCAL_FILE_PATH") : null;
            return playerDownloadController.getDownloadedEpisodeForActive(animeId, episodesManager, playersManager, localPath);
        }
        return null;
    }

    private EpisodeResponse.PlayerData createSyntheticPlayerDataForDownloaded(com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp) {
        EpisodeResponse.PlayerData pd = new EpisodeResponse.PlayerData();
        pd.setPlayer(downloadedEp.getPlayerType() != null ? downloadedEp.getPlayerType() : "animelib");
        EpisodeResponse.Team team = new EpisodeResponse.Team();
        team.setName(downloadedEp.getTeamName() != null ? downloadedEp.getTeamName() : "Офлайн");
        pd.setTeam(team);
        return pd;
    }

    private List<String> getQualitiesWithDownloadedOption(List<String> onlineQualities) {
        if (playerDownloadController != null) {
            com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
            return playerDownloadController.getQualitiesWithDownloadedOption(onlineQualities, downloadedEp);
        }
        return onlineQualities != null ? onlineQualities : new ArrayList<>();
    }

    private boolean isDownloadedQuality(String quality) {
        if (playerDownloadController != null) {
            return playerDownloadController.isDownloadedQuality(quality);
        }
        return false;
    }

    public boolean isPlayingDownloadedVideo() {
        if (isOfflineMode) {
            return true;
        }
        if (isDownloadedQuality(preferredQuality)) {
            return true;
        }
        if (currentVideoUrl != null && (currentVideoUrl.startsWith("file:") || currentVideoUrl.startsWith("/"))) {
            return true;
        }
        return false;
    }

    private void showSettingsDialog() {
        if (playerDialogsController == null) return;

        playerDialogsController.showSettingsDialog(new PlayerDialogsController.SettingsDataProvider() {
            @Override
            public boolean isOfflineMode() {
                return VideoPlayerActivity.this.isOfflineMode;
            }

            @Override
            public boolean isPlayingDownloadedVideo() {
                return VideoPlayerActivity.this.isPlayingDownloadedVideo();
            }

            @Override
            public PlayersManager getPlayersManager() {
                return playersManager;
            }

            @Override
            public List<String> getAvailableQualities() {
                if (isOfflineMode) {
                    return getQualitiesWithDownloadedOption(new ArrayList<>());
                } else {
                    return getQualitiesWithDownloadedOption(playersManager.getAvailableQualities());
                }
            }

            @Override
            public String getPreferredQuality() {
                return preferredQuality;
            }

            @Override
            public void onQualitySelected(String quality) {
                String oldQuality = preferredQuality;
                preferredQuality = quality;
                updateSettingsQualityTag();
                Log.d("VideoPlayer", "Selected quality: " + quality);

                EpisodeResponse.PlayerData currentPlayer = playersManager.getCurrentPlayerData();
                if (currentPlayer != null && currentPlayer.getPlayer() != null && currentPlayer.getTeam() != null) {
                    if (!com.example.animelib.util.AutoQualityHelper.isDownloadedQuality(quality)) {
                        apiService.savePlayerPreferences(currentPlayer.getPlayer(), 
                                                        currentPlayer.getTeam().getId(), 
                                                        quality);
                        Log.d("VideoPlayer", "Saved quality preference: " + quality);
                    }
                }

                if (!quality.equals(oldQuality) && player != null) {
                    Log.d("VideoPlayer", "Changing quality to " + quality);
                    changeQuality(quality, oldQuality);
                }
            }

            @Override
            public float getPlaybackSpeed() {
                return player != null ? player.getPlaybackParameters().speed : 1.0f;
            }

            @Override
            public void onSpeedChanged(float speed) {
                if (player != null) {
                    player.setPlaybackSpeed(speed);
                    Log.d("VideoPlayer", "Speed changed via dialog: " + speed);
                }
            }

            @Override
            public boolean isEnable4K() {
                return enable4K;
            }

            @Override
            public void onEnable4KChanged(boolean enabled) {
                enable4K = enabled;
                apiService.save4KSetting(enabled);
                playersManager.setEnable4K(enabled);
                Log.d("VideoPlayer", "4K setting changed to: " + enabled);
                List<String> newQualities = playersManager.getAvailableQualities();
                Log.d("VideoPlayer", "New qualities after 4K toggle: " + newQualities);
                if (!newQualities.isEmpty()) {
                    if (!newQualities.contains(preferredQuality)) {
                        String oldQuality = preferredQuality;
                        preferredQuality = newQualities.get(0);
                        Log.d("VideoPlayer", "Preferred quality changed from " + oldQuality + " to " + preferredQuality);
                    }
                    if (playerDialogsController != null) {
                        Log.d("VideoPlayer", "Updating SettingsBottomSheet with new qualities");
                        playerDialogsController.updateSettingsQualities(newQualities, preferredQuality);
                    }
                } else {
                    Log.w("VideoPlayer", "New qualities list is empty!");
                }
            }

            @Override
            public boolean isEnableAmbientLight() {
                return enableAmbientLight;
            }

            @Override
            public void onEnableAmbientLightChanged(boolean enabled) {
                enableAmbientLight = enabled;
                apiService.saveAmbientLightSetting(enabled);
                if (ambientLightManager != null) {
                    ambientLightManager.setEnabled(enabled);
                }
                Log.d("VideoPlayer", "Ambient light setting changed to: " + enabled);
            }

            @Override
            public boolean isAutoPlay() {
                return autoPlay;
            }

            @Override
            public void onAutoPlayChanged(boolean enabled) {
                autoPlay = enabled;
                autoPlayOnPrepare = enabled;
                apiService.saveAutoPlaySetting(enabled);
                Log.d("VideoPlayer", "AutoPlay enabled: " + enabled);
            }

            @Override
            public int getLongSkipDuration() {
                return longSkipDuration;
            }

            @Override
            public void onLongSkipDurationChanged(int duration) {
                longSkipDuration = duration;
                apiService.saveLongSkipDurationSetting(duration);
                Log.d("VideoPlayer", "LongSkipDuration changed: " + duration);
            }

            @Override
            public int getCurrentTheme() {
                return currentTheme;
            }

            @Override
            public void onThemeChanged(int themeMode) {
                currentTheme = themeMode;
                ThemeUtils.applyThemeToActivity(VideoPlayerActivity.this, themeMode);
                setupFullscreen();
                checkAndUpdateOrientation();
                apiService.saveThemeSetting(themeMode);
                Log.d("VideoPlayer", "Theme changed: " + themeMode);
            }

            @Override
            public float[] getVideoFilters() {
                return new float[] {
                    playerFiltersController != null ? playerFiltersController.getFilterBrightness() : 0f,
                    playerFiltersController != null ? playerFiltersController.getFilterContrast() : 100f,
                    playerFiltersController != null ? playerFiltersController.getFilterSaturation() : 100f,
                    playerFiltersController != null ? playerFiltersController.getFilterGamma() : 1.0f,
                    playerFiltersController != null ? playerFiltersController.getFilterHue() : 0f
                };
            }

            @Override
            public void onVideoFiltersChanged(float b, float c, float s, float g, float h) {
                if (playerFiltersController != null) {
                    playerFiltersController.setFilters(b, c, s, g, h);
                }
                apiService.saveVideoFilters(b, c, s, g, h);
                Log.d("VideoPlayer", "Video filters changed: b=" + b + ", c=" + c + ", s=" + s + ", g=" + g + ", h=" + h);
            }

            @Override
            public boolean isSurround3DEnabled() {
                return playerAudioController != null && playerAudioController.isEnableSurroundSound();
            }

            @Override
            public int getSurroundMode() {
                return playerAudioController != null ? playerAudioController.getSurroundMode() : 0;
            }

            @Override
            public float getSurroundSpatialWidth() {
                return playerAudioController != null ? playerAudioController.getSurroundSpatialWidth() : 1.0f;
            }

            @Override
            public float getSurroundDialogueBoost() {
                return playerAudioController != null ? playerAudioController.getSurroundDialogueBoost() : 1.0f;
            }

            @Override
            public float getSurroundBassBoost() {
                return playerAudioController != null ? playerAudioController.getSurroundBassBoost() : 1.0f;
            }

            @Override
            public float getSurroundTrebleBoost() {
                return playerAudioController != null ? playerAudioController.getSurroundTrebleBoost() : 1.0f;
            }

            @Override
            public void onSurround3DChanged(boolean enabled, int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost) {
                if (playerAudioController != null) {
                    playerAudioController.updateSettings(enabled, mode, spatialWidth, dialogueBoost, bassBoost, trebleBoost, apiService);
                }
                showSurroundSoundToast(enabled);
                Log.d("VideoPlayer", "3D Surround sound settings changed: enabled=" + enabled + ", mode=" + mode + ", width=" + spatialWidth + ", dialogue=" + dialogueBoost + ", bass=" + bassBoost + ", treble=" + trebleBoost);
            }

            @Override
            public int getCurrentResizeMode() {
                return currentResizeMode;
            }

            @Override
            public void onResizeModeChanged(int newMode) {
                setVideoResizeMode(newMode);
            }

            @Override
            public boolean isSubtitlesEnabled() {
                return playerSubtitlesController != null && playerSubtitlesController.isSubtitlesEnabled();
            }

            @Override
            public String getSubtitleFormat() {
                return playerSubtitlesController != null ? playerSubtitlesController.getSubtitleFormat() : "ass";
            }

            @Override
            public List<EpisodeResponse.SubtitleData> getSubtitles() {
                return (playersManager != null && playersManager.getCurrentPlayerData() != null) ?
                        playersManager.getCurrentPlayerData().getSubtitles() : null;
            }

            @Override
            public void onSubtitleSettingsChanged(boolean enabled, String format) {
                if (playerSubtitlesController != null) {
                    playerSubtitlesController.updateSubtitleSettings(enabled, format);
                }
            }

            @Override
            public float getSubtitleTextSize() {
                return playerSubtitlesController != null ? playerSubtitlesController.getSubtitleTextSize() : 18f;
            }

            @Override
            public int getSubtitleTextColor() {
                return playerSubtitlesController != null ? playerSubtitlesController.getSubtitleTextColor() : 0xFFFFFFFF;
            }

            @Override
            public int getSubtitleBackgroundColor() {
                return playerSubtitlesController != null ? playerSubtitlesController.getSubtitleBackgroundColor() : 0x00000000;
            }

            @Override
            public int getSubtitleEdgeType() {
                return playerSubtitlesController != null ? playerSubtitlesController.getSubtitleEdgeType() : CaptionStyleCompat.EDGE_TYPE_OUTLINE;
            }

            @Override
            public int getSubtitleEdgeColor() {
                return playerSubtitlesController != null ? playerSubtitlesController.getSubtitleEdgeColor() : 0xFF000000;
            }

            @Override
            public void onSubtitleStyleSettingsChanged(float textSize, int textColor, int bgColor, int edgeType, int edgeColor) {
                if (playerSubtitlesController != null) {
                    playerSubtitlesController.updateSubtitleStyleSettings(textSize, textColor, bgColor, edgeType, edgeColor);
                }
            }

            @Override
            public String getCurrentVideoDomain() {
                return currentVideoDomain;
            }

            @Override
            public void onVideoDomainChanged(String domain) {
                if (isPlayingDownloadedVideo()) {
                    com.example.animelib.util.CustomToast.showWarning(VideoPlayerActivity.this, "Смена сервера недоступна для скачанного видео");
                    return;
                }
                if (!Objects.equals(currentVideoDomain, domain)) {
                    currentVideoDomain = domain;
                    Log.d("VideoPlayer", "Selected video server domain: " + domain);
                    boolean isKodik = playersManager != null && playersManager.getCurrentPlayerData() != null &&
                            "kodik".equalsIgnoreCase(playersManager.getCurrentPlayerData().getPlayer());
                    if (!isKodik && player != null) {
                        restartPlayerWithNewQuality();
                    }
                }
            }
        });
    }

    private Context getPlayerContext() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                Context attributionContext = createAttributionContext("default");
                if (attributionContext != null) {
                    return attributionContext;
                }
            } catch (Exception e) {
                Log.e("VideoPlayer", "Error creating attribution context", e);
            }
        }
        return this;
    }

    private void playOfflineFile(String localPath, String animeTitle, String episodeTitle) {
        isOfflineMode = true;
        java.io.File file = new java.io.File(localPath);
        if (!file.exists()) {
            CustomToast.showWarning(this, "Офлайн файл не найден: " + localPath);
            finish();
            return;
        }

        if (playerCommentsController != null) {
            playerCommentsController.setOfflineMode(true);
        }

        if (animeTitleView != null) {
            SkeletonHelper.hideSkeleton(animeTitleView, animeTitle != null ? animeTitle : "");
        }
        if (tvPortraitAnimeTitle != null) {
            SkeletonHelper.hideSkeleton(tvPortraitAnimeTitle, animeTitle != null ? animeTitle : "");
        }
        if (tvPortraitEpisodeTitle != null) {
            EpisodesListResponse.EpisodeItem curEp = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
            String epNum = curEp != null && curEp.getNumber() != null ? curEp.getNumber() : (getIntent() != null ? getIntent().getStringExtra("EXTRA_EPISODE_NUMBER") : null);
            String cleanName = cleanEpisodeName(episodeTitle, epNum);
            if (!cleanName.isEmpty()) {
                SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, cleanName);
                tvPortraitEpisodeTitle.setVisibility(View.VISIBLE);
            } else {
                SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, "");
                tvPortraitEpisodeTitle.setVisibility(View.GONE);
            }
        }

        Uri videoUri = Uri.fromFile(file);
        if (player == null) {
            com.example.animelib.util.SurroundRenderersFactory rf = new com.example.animelib.util.SurroundRenderersFactory(
                    getPlayerContext(), playerAudioController != null ? playerAudioController.getSurroundAudioProcessor() : null);
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 1_500, 2_500)
                    .setBackBuffer(10_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
            player = new androidx.media3.exoplayer.ExoPlayer.Builder(getPlayerContext(), rf)
                    .setLoadControl(loadControl)
                    .setSeekBackIncrementMs(10000)
                    .setSeekForwardIncrementMs(10000)
                    .build();
            playerView.setPlayer(player);
            setVideoResizeMode(currentResizeMode);
            setupPlayerListener();
        } else {
            player.stop();
            player.clearMediaItems();
            if (playerView != null) {
                playerView.setPlayer(player);
                preservePlayerAspectRatio();
                setVideoResizeMode(currentResizeMode);
            }
            setupPlayerListener();
        }

        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(player);
        }

        if (playerAudioController != null) {
            playerAudioController.attachPlayer(player);
        }

        playerView.setUseController(true);
        updateControllerAutoHide();

        if (gesturesManager != null) {
            gesturesManager.updatePlayer(player);
        }

        com.google.android.material.button.MaterialButton skipSegmentButton = findViewById(R.id.skipSegmentButton);
        if (timecodeManager != null) {
            timecodeManager.initializeViews(player, playerView, skipSegmentButton);
        }

        MediaItem mediaItem = createMediaItemWithSubtitles(videoUri.toString());
        player.setMediaItem(mediaItem);
        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(player, mediaItem, videoUri.toString());
        }
        player.prepare();
        setupSubtitlePlayerListener(player);
        applySubtitlesStateToPlayer();
        player.setPlayWhenReady(autoPlayOnPrepare);
        autoPlayOnPrepare = true;

        setupPlayerControlButtons();

        hideLoading();
        setupOfflineEpisodes(localPath);
        applyOfflineUIState();
        hideAllSkeletonsForOffline();
    }

    private void setupOfflineEpisodes(String localPath) {
        if (!isOfflineMode) return;

        executor.execute(() -> {
            com.example.animelib.data.DatabaseManager databaseManager = apiService.getDatabaseManager();
            if (databaseManager == null) return;

            String animeId = getIntent().getStringExtra("EXTRA_ANIME_ID");
            String intentEpNumber = getIntent().getStringExtra("EXTRA_EPISODE_NUMBER");

            if (animeId == null && localPath != null) {
                com.example.animelib.data.entity.DownloadedEpisodeEntity currentEp = databaseManager.findEpisodeByPath(localPath);
                if (currentEp != null) {
                    animeId = currentEp.getAnimeId();
                }
            }

            if (animeId != null) {
                currentAnimeId = animeId;
                List<com.example.animelib.data.entity.DownloadedEpisodeEntity> downloadedEps = databaseManager.getEpisodesForAnimeSync(animeId);
                if (downloadedEps != null && !downloadedEps.isEmpty()) {
                    List<EpisodesListResponse.EpisodeItem> episodeItems = new ArrayList<>();
                    EpisodesListResponse.EpisodeItem currentItem = null;

                    for (com.example.animelib.data.entity.DownloadedEpisodeEntity dep : downloadedEps) {
                        EpisodesListResponse.EpisodeItem item = new EpisodesListResponse.EpisodeItem();
                        int id = dep.getEpisodeId();
                        if (id <= 0) {
                            try {
                                id = Integer.parseInt(dep.getEpisodeNumber());
                            } catch (Exception ignored) {
                                id = Math.abs(dep.getId().hashCode());
                            }
                        }
                        item.setId(id);
                        item.setNumber(dep.getEpisodeNumber());
                        String cleanName = cleanEpisodeName(dep.getEpisodeName(), dep.getEpisodeNumber());
                        item.setName(cleanName);

                        if (dep.getEpisodeNumber() != null) {
                            offlineEpisodesMap.put(dep.getEpisodeNumber(), dep);
                        }
                        offlineEpisodesMap.put(String.valueOf(id), dep);

                        episodeItems.add(item);

                        // Priority 1: Exact match by localPath
                        if (localPath != null && dep.getLocalFilePath() != null) {
                            String normLocal = localPath.startsWith("file://") ? localPath.substring(7) : localPath;
                            String normDep = dep.getLocalFilePath().startsWith("file://") ? dep.getLocalFilePath().substring(7) : dep.getLocalFilePath();
                            java.io.File f1 = new java.io.File(normLocal);
                            java.io.File f2 = new java.io.File(normDep);
                            if (normLocal.equalsIgnoreCase(normDep) ||
                                f1.getAbsolutePath().equalsIgnoreCase(f2.getAbsolutePath()) ||
                                (f1.getName() != null && f1.getName().equalsIgnoreCase(f2.getName()))) {
                                currentItem = item;
                            }
                        }
                    }

                    // Priority 2: Match by existing episodesManager current episode
                    if (currentItem == null && episodesManager != null) {
                        EpisodesListResponse.EpisodeItem existingCur = episodesManager.getCurrentEpisode();
                        if (existingCur != null) {
                            for (EpisodesListResponse.EpisodeItem item : episodeItems) {
                                if (item.getNumber() != null && item.getNumber().equals(existingCur.getNumber())) {
                                    currentItem = item;
                                    break;
                                }
                                if (item.getId() == existingCur.getId()) {
                                    currentItem = item;
                                    break;
                                }
                            }
                        }
                    }

                    // Priority 3: Match by intent episode number (only on first launch)
                    if (currentItem == null && isFirstOfflineLaunch && intentEpNumber != null && !intentEpNumber.isEmpty()) {
                        for (EpisodesListResponse.EpisodeItem item : episodeItems) {
                            if (item.getNumber() != null && item.getNumber().equals(intentEpNumber)) {
                                currentItem = item;
                                break;
                            }
                        }
                    }

                    // Priority 4: Offline bookmark (only on first launch)
                    com.example.animelib.data.entity.OfflineBookmarkEntity offlineBookmark = databaseManager.getOfflineBookmarkSync(animeId);
                    if (currentItem == null && isFirstOfflineLaunch && offlineBookmark != null) {
                        for (EpisodesListResponse.EpisodeItem item : episodeItems) {
                            if (item.getNumber() != null && item.getNumber().equals(offlineBookmark.getEpisodeNumber())) {
                                currentItem = item;
                                break;
                            }
                            if (item.getId() == offlineBookmark.getEpisodeId()) {
                                currentItem = item;
                                break;
                            }
                        }
                    }

                    if (currentItem == null && !episodeItems.isEmpty()) {
                        currentItem = episodeItems.get(0);
                    }

                    final List<EpisodesListResponse.EpisodeItem> finalItems = episodeItems;
                    final EpisodesListResponse.EpisodeItem finalCurrent = currentItem;

                    runOnUiThread(() -> {
                        if (episodesManager != null) {
                            episodesManager.setEpisodes(finalItems);
                            if (finalCurrent != null) {
                                episodesManager.setCurrentEpisode(finalCurrent);
                            }
                            if (offlineBookmark != null) {
                                com.example.animelib.models.AnimeBookmarkResponse.BookmarkData bmData = new com.example.animelib.models.AnimeBookmarkResponse.BookmarkData();
                                bmData.setItemId(offlineBookmark.getEpisodeId());
                                bmData.setProgress(offlineBookmark.getTimecode());
                                episodesManager.updateBookmarkInAdapter(bmData);

                                boolean isCurrentBookmarked = finalCurrent != null &&
                                        (finalCurrent.getId() == offlineBookmark.getEpisodeId() ||
                                         (finalCurrent.getNumber() != null && finalCurrent.getNumber().equals(offlineBookmark.getEpisodeNumber())));

                                if (isCurrentBookmarked) {
                                    updateBookmarkButtonColor(true);
                                    if (offlineBookmark.getPositionMs() > 0 && player != null && isFirstOfflineLaunch) {
                                        player.seekTo(offlineBookmark.getPositionMs());
                                        Log.d("VideoPlayer", "Seeked player to offline bookmark position: " + offlineBookmark.getPositionMs() + "ms");
                                    }
                                } else {
                                    updateBookmarkButtonColor(false);
                                }
                            } else {
                                updateBookmarkButtonColor(false);
                            }
                            episodesManager.updateEpisodeNavigationButtonsVisibility();
                        }
                        isFirstOfflineLaunch = false;
                        if (episodesMenuButton != null) {
                            boolean isPortrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
                            episodesMenuButton.setEnabled(true);
                            episodesMenuButton.setAlpha(1.0f);
                            episodesMenuButton.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
                        }
                        updateEpisodeHeaderQuick();
                        hideAllSkeletonsForOffline();
                    });
                }
            }
        });
    }

    private void applyOfflineUIState() {
        isOfflineMode = true;

        if (playerCommentsController != null) {
            playerCommentsController.setOfflineMode(true);
        }

        if (menuPanelContainer != null) {
            menuPanelContainer.forceClose();
            menuPanelContainer.setVisibility(View.GONE);
        }
        if (commentsPanelContainer != null) {
            commentsPanelContainer.forceClose();
            commentsPanelContainer.setVisibility(View.GONE);
        }
        if (menuOverlay != null) {
            menuOverlay.setVisibility(View.GONE);
        }
        if (playerAnimeInfoController != null) {
            playerAnimeInfoController.hidePlaceholderAnimated();
        }
        if (slidingMenuPanel != null) {
            slidingMenuPanel.setVisibility(View.GONE);
        }

        // Сбрасываем трансформацию плеера к полноэкранному состоянию
        applyPlayerSidePanelTransform(0f);
        if (playerContainer != null) {
            playerContainer.setPivotX(0f);
            playerContainer.setPivotY(0f);
            playerContainer.setScaleX(1f);
            playerContainer.setScaleY(1f);
            playerContainer.setTranslationX(0f);
            playerContainer.setTranslationY(0f);
        }

        if (settingsButton != null) {
            settingsButton.setEnabled(true);
            settingsButton.setAlpha(1.0f);
            settingsButton.setVisibility(View.VISIBLE);
        }

        if (menuToggleButton != null) {
            menuToggleButton.setEnabled(false);
            menuToggleButton.setAlpha(0.3f);
        }
        boolean hasEpisodes = episodesManager != null && !episodesManager.getEpisodes().isEmpty();
        if (episodesMenuButton != null) {
            boolean isPortrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            episodesMenuButton.setEnabled(hasEpisodes);
            episodesMenuButton.setAlpha(hasEpisodes ? 1.0f : 0.3f);
            episodesMenuButton.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
        }
        if (commentsButton != null) {
            commentsButton.setEnabled(false);
            commentsButton.setAlpha(0.3f);
        }
        if (bookmarkButton != null) {
            bookmarkButton.setEnabled(true);
            bookmarkButton.setClickable(true);
            bookmarkButton.setAlpha(1.0f);
        }
        if (episodesManager != null) {
            episodesManager.updateEpisodeNavigationButtonsVisibility();
        }
        if (downloadButton != null) {
            downloadButton.setEnabled(false);
            downloadButton.setAlpha(0.3f);
            downloadButton.setVisibility(View.GONE);
        }
        if (downloadButtonTop != null) {
            downloadButtonTop.setEnabled(false);
            downloadButtonTop.setAlpha(0.3f);
            downloadButtonTop.setVisibility(View.GONE);
        }
        if (btnDownloadFromMenu != null) {
            btnDownloadFromMenu.setEnabled(false);
            btnDownloadFromMenu.setAlpha(0.3f);
            btnDownloadFromMenu.setVisibility(View.GONE);
        }
        if (portraitDownloadButton != null) {
            portraitDownloadButton.setEnabled(false);
            portraitDownloadButton.setClickable(false);
            portraitDownloadButton.setFocusable(false);
            portraitDownloadButton.setAlpha(0.35f);
        }
        if (portraitBookmarkButton != null) {
            portraitBookmarkButton.setEnabled(true);
            portraitBookmarkButton.setClickable(true);
            portraitBookmarkButton.setFocusable(true);
            portraitBookmarkButton.setAlpha(1.0f);
        }
        if (portraitVoiceoverPlayerButton != null) {
            portraitVoiceoverPlayerButton.setEnabled(false);
            portraitVoiceoverPlayerButton.setClickable(false);
            portraitVoiceoverPlayerButton.setFocusable(false);
            portraitVoiceoverPlayerButton.setAlpha(0.6f);
        }
        if (ivPortraitVoiceoverChevron != null) {
            ivPortraitVoiceoverChevron.setVisibility(View.GONE);
        }
        if (portraitStatusDropdownButton != null) {
            portraitStatusDropdownButton.setVisibility(View.VISIBLE);
        }
        if (playerAnimeInfoController != null) {
            playerAnimeInfoController.setOfflineMode(true);
        }

        hideLoading();
        hideAllSkeletonsForOffline();

        updatePlayPauseAndLoadingState(false);
    }

    /**
     * Показывает окно выбора серий для скачивания (BottomSheet)
     */
    public void showDownloadBottomSheet() {
        if (isOfflineMode) {
            CustomToast.showInfo(this, "Воспроизводится скачанный файл");
            return;
        }
        requestNotificationPermission();
        if (playerDownloadController != null) {
            playerDownloadController.showDownloadBottomSheet(episodesManager, playersManager);
        }
    }

    public void showDownloadProgressBottomSheet() {
        if (playerDownloadController != null) {
            playerDownloadController.showDownloadProgressBottomSheet();
        }
    }

    private void setupDownloadListener() {
        if (playerDownloadController != null) {
            playerDownloadController.setupDownloadListener(playersManager);
        }
    }

    private void showDownloadProgress(int percent) {
        if (playerDownloadController != null) {
            playerDownloadController.showDownloadProgress(percent, playersManager);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4201);
        }
    }

    private void resetDownloadUi() {
        if (playerDownloadController != null) {
            playerDownloadController.resetDownloadUi(playersManager);
        }
    }

    private ExoPlayer backgroundPlayer = null;
    private boolean isSeamlessSwitching = false;
    private android.os.Handler seamlessTimeoutHandler = null;
    private Runnable seamlessTimeoutRunnable = null;

    private void cleanupBackgroundPlayer() {
        if (seamlessTimeoutHandler != null && seamlessTimeoutRunnable != null) {
            seamlessTimeoutHandler.removeCallbacks(seamlessTimeoutRunnable);
            seamlessTimeoutHandler = null;
            seamlessTimeoutRunnable = null;
        }
        isSeamlessSwitching = false;
        if (backgroundPlayer != null) {
            try {
                backgroundPlayer.stop();
                backgroundPlayer.release();
            } catch (Exception ignored) {}
            backgroundPlayer = null;
        }
    }

    private String resolveUrlForQuality(EpisodeResponse.PlayerData playerData, String targetQuality) {
        if (playerData == null) return null;

        if (isDownloadedQuality(targetQuality)) {
            com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
            if (downloadedEp != null && downloadedEp.getLocalFilePath() != null) {
                java.io.File file = new java.io.File(downloadedEp.getLocalFilePath());
                if (file.exists() && file.length() > 0) {
                    return Uri.fromFile(file).toString();
                }
            }
        }

        if ("kodik".equalsIgnoreCase(playerData.getPlayer())) {
            if (currentKodikResponse != null && currentKodikResponse.getData() != null) {
                String resolvedQuality = targetQuality;
                if (com.example.animelib.util.AutoQualityHelper.isAutoQuality(targetQuality)) {
                    List<String> available = playersManager != null ? playersManager.getAvailableQualities() : new ArrayList<>();
                    long estimate = 0;
                    try {
                        estimate = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).getBitrateEstimate();
                    } catch (Exception ignored) {}
                    resolvedQuality = com.example.animelib.util.AutoQualityHelper.resolveBestQuality(this, available, targetQuality, estimate);
                }
                String qualityKey = resolvedQuality != null ? resolvedQuality.replace("p", "") : null;
                if (qualityKey != null && currentKodikResponse.getData().containsKey(qualityKey) &&
                        Objects.requireNonNull(currentKodikResponse.getData().get(qualityKey)).length > 0) {
                    String newHlsUrl = Objects.requireNonNull(currentKodikResponse.getData().get(qualityKey))[0].getSrc();
                    if (newHlsUrl != null && !newHlsUrl.isEmpty()) {
                        if (!newHlsUrl.startsWith("http")) {
                            newHlsUrl = "https:" + newHlsUrl;
                        }
                        return newHlsUrl;
                    }
                }
            }
        } else if ("animelib".equalsIgnoreCase(playerData.getPlayer())) {
            if (playerData.getVideo() != null && playerData.getVideo().getQuality() != null && !playerData.getVideo().getQuality().isEmpty()) {
                String resolvedQuality = targetQuality;
                if (com.example.animelib.util.AutoQualityHelper.isAutoQuality(targetQuality)) {
                    List<String> available = playersManager != null ? playersManager.getAvailableQualities() : new ArrayList<>();
                    long estimate = 0;
                    try {
                        estimate = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).getBitrateEstimate();
                    } catch (Exception ignored) {}
                    resolvedQuality = com.example.animelib.util.AutoQualityHelper.resolveBestQuality(this, available, targetQuality, estimate);
                }
                int targetRes = com.example.animelib.util.AutoQualityHelper.extractResolution(resolvedQuality);
                EpisodeResponse.QualityData selectedQuality = null;
                for (EpisodeResponse.QualityData qData : playerData.getVideo().getQuality()) {
                    if (qData.getQuality() == targetRes) {
                        selectedQuality = qData;
                        break;
                    }
                }
                if (selectedQuality == null && !playerData.getVideo().getQuality().isEmpty()) {
                    selectedQuality = playerData.getVideo().getQuality().get(0);
                }
                if (selectedQuality != null && selectedQuality.getHref() != null) {
                    String domain = currentVideoDomain;
                    return com.example.animelib.ui.VideoUrlHelper.toAbsoluteVideoUrl(selectedQuality.getHref(), domain);
                }
            }
        }
        return null;
    }

    private void changeQuality(String newQuality, String oldQuality) {
        preferredQuality = newQuality;
        EpisodeResponse.PlayerData currentPlayerData = playersManager != null ? playersManager.getCurrentPlayerData() : null;
        String newVideoUrl = resolveUrlForQuality(currentPlayerData, newQuality);

        boolean isCurrentlyPlaying = player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY) && !isSeamlessSwitching;

        if (isCurrentlyPlaying && newVideoUrl != null && !newVideoUrl.equals(currentVideoUrl)) {
            switchQualitySeamlessly(newQuality, oldQuality, newVideoUrl);
        } else if (newVideoUrl != null && !newVideoUrl.equals(currentVideoUrl)) {
            long currentPos = player != null ? player.getCurrentPosition() : 0;
            boolean wasPlaying = player == null || player.getPlayWhenReady();
            currentVideoUrl = newVideoUrl;
            switchMediaSource(newVideoUrl, currentPos, wasPlaying);
            updateSettingsQualityTag();
        } else {
            restartPlayerWithNewQuality();
        }
    }

    private void switchMediaSource(String newVideoUrl, long startPosition, boolean playWhenReady) {
        cleanupBackgroundPlayer();
        isVideoLoading = true;
        hasRenderedFirstFrame = false;
        updatePlayPauseAndLoadingState(true);

        if (playerPlaybackController != null) {
            MediaItem mediaItem = createMediaItemWithSubtitles(newVideoUrl);
            player = playerPlaybackController.switchMediaSource(newVideoUrl, mediaItem, startPosition, playWhenReady);
            if (player != null) {
                setupSubtitlePlayerListener(player);
                applySubtitlesStateToPlayer();
                setupPlayerControlButtons();
            }
        }
    }

    private void switchQualitySeamlessly(String newQuality, String oldQuality, String newVideoUrl) {
        cleanupBackgroundPlayer();

        isSeamlessSwitching = true;

        Context playerContext = this;

        // Use a separate DefaultRenderersFactory so background player doesn't interrupt or share the main player's SurroundAudioProcessor
        androidx.media3.exoplayer.DefaultRenderersFactory rf = new androidx.media3.exoplayer.DefaultRenderersFactory(playerContext);

        // Low buffer thresholds so background player reaches STATE_READY fast without starving bandwidth
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_500, 10_000, 500, 1_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        ExoPlayer.Builder builder = new ExoPlayer.Builder(playerContext, rf)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000);

        backgroundPlayer = builder.build();
        backgroundPlayer.setVolume(0f); // Mute background player while buffering

        androidx.media3.datasource.DefaultHttpDataSource.Factory httpFactory = new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        if (apiService != null) {
            httpFactory.setDefaultRequestProperties(apiService.getVideoRequestHeaders());
        }

        androidx.media3.datasource.DataSource.Factory dsFactory = new androidx.media3.datasource.DefaultDataSource.Factory(playerContext, httpFactory);

        MediaItem mediaItem = createMediaItemWithSubtitles(newVideoUrl);
        androidx.media3.exoplayer.source.MediaSource mediaSource;
        if (newVideoUrl.contains(".m3u8") || newVideoUrl.contains("hls")) {
            mediaSource = new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dsFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem);
        } else {
            androidx.media3.extractor.DefaultExtractorsFactory extractorsFactory =
                    new androidx.media3.extractor.DefaultExtractorsFactory()
                            .setConstantBitrateSeekingEnabled(true);
            mediaSource = new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dsFactory, extractorsFactory)
                    .createMediaSource(mediaItem);
        }

        backgroundPlayer.setMediaSource(mediaSource);

        long currentPosition = player != null ? player.getCurrentPosition() : 0;
        float currentSpeed = player != null ? player.getPlaybackParameters().speed : 1.0f;
        boolean wasPlaying = player != null && player.getPlayWhenReady();

        backgroundPlayer.seekTo(currentPosition);
        backgroundPlayer.setPlaybackSpeed(currentSpeed);
        backgroundPlayer.setPlayWhenReady(true);
        backgroundPlayer.prepare();

        seamlessTimeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        seamlessTimeoutRunnable = () -> {
            if (isSeamlessSwitching && backgroundPlayer != null) {
                Log.w("VideoPlayer", "Seamless quality switch timed out, falling back to restart");
                cleanupBackgroundPlayer();
                restartPlayerWithNewQuality();
            }
        };
        seamlessTimeoutHandler.postDelayed(seamlessTimeoutRunnable, 10000);

        backgroundPlayer.addListener(new Player.Listener() {
            private boolean switched = false;

            @Override
            public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    lastValidAspectRatio = (float) (videoSize.width * videoSize.pixelWidthHeightRatio) / (float) videoSize.height;
                    preservePlayerAspectRatio();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && !switched) {
                    switched = true;
                    if (seamlessTimeoutHandler != null && seamlessTimeoutRunnable != null) {
                        seamlessTimeoutHandler.removeCallbacks(seamlessTimeoutRunnable);
                    }
                    safeRunOnUiThread(() -> performSeamlessSwitch(newQuality, oldQuality, newVideoUrl, wasPlaying));
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (seamlessTimeoutHandler != null && seamlessTimeoutRunnable != null) {
                    seamlessTimeoutHandler.removeCallbacks(seamlessTimeoutRunnable);
                }
                safeRunOnUiThread(() -> {
                    Log.e("VideoPlayer", "Failed background load for quality " + newQuality + ", falling back to restart", error);
                    cleanupBackgroundPlayer();
                    restartPlayerWithNewQuality();
                });
            }
        });
    }

    private void performSeamlessSwitch(String newQuality, String oldQuality, String newVideoUrl, boolean wasPlaying) {
        if (backgroundPlayer == null || isFinishing() || isDestroyed()) return;

        Log.d("VideoPlayer", "Background player READY! Performing seamless switch to " + newQuality);

        ExoPlayer oldPlayer = player;
        long livePosition = oldPlayer != null ? oldPlayer.getCurrentPosition() : backgroundPlayer.getCurrentPosition();

        if (playerView != null) {
            playerView.setPlayer(backgroundPlayer);
            preservePlayerAspectRatio();
            setVideoResizeMode(currentResizeMode);
        }

        if (Math.abs(livePosition - backgroundPlayer.getCurrentPosition()) > 1000) {
            backgroundPlayer.seekTo(livePosition);
        }
        backgroundPlayer.setVolume(1.0f);
        backgroundPlayer.setPlayWhenReady(wasPlaying);

        if (playerPlaybackController != null) {
            playerPlaybackController.attachNewPlayer(backgroundPlayer);
        }

        if (playerAudioController != null) {
            playerAudioController.attachPlayer(backgroundPlayer);
        }
        if (gesturesManager != null) {
            gesturesManager.updatePlayer(backgroundPlayer);
        }
        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(backgroundPlayer, MediaItem.fromUri(newVideoUrl), newVideoUrl);
        }

        player = backgroundPlayer;
        backgroundPlayer = null;
        currentVideoUrl = newVideoUrl;
        isSeamlessSwitching = false;
        hasRenderedFirstFrame = true;
        isVideoLoading = false;
        isSeeking = false;
        hideLoading();
        updatePlayPauseAndLoadingState(false);

        setupPlayerListener();
        setupSubtitlePlayerListener(player);
        applySubtitlesStateToPlayer();
        setupPlayerControlButtons();

        if (oldPlayer != null && oldPlayer != player) {
            try {
                oldPlayer.stop();
                oldPlayer.release();
            } catch (Exception ignored) {}
        }

        updateSettingsQualityTag();
        EpisodeResponse.PlayerData currentPlayerData = playersManager != null ? playersManager.getCurrentPlayerData() : null;
        if (currentPlayerData != null) {
            playersManager.setCurrentPlayerData(currentPlayerData);
            if (timecodeManager != null) {
                timecodeManager.setTimecodes(currentPlayerData);
            }
        }

        applyPlayerSidePanelTransform(0f);
        if (playerPanelsController != null) {
            playerPanelsController.applyPlayerSidePanelTransform(0f);
        }
    }

    @androidx.annotation.Nullable
    private String resolveAnimelibUrl(EpisodeResponse.PlayerData playerData, String preferredQuality) {
        if (playerData == null || playerData.getVideo() == null || playerData.getVideo().getQuality() == null || playerData.getVideo().getQuality().isEmpty()) {
            return null;
        }
        List<EpisodeResponse.QualityData> qList = new ArrayList<>(playerData.getVideo().getQuality());
        qList.sort((q1, q2) -> Integer.compare(q2.getQuality(), q1.getQuality()));

        List<String> available = new ArrayList<>();
        for (EpisodeResponse.QualityData q : qList) {
            int res = q.getQuality();
            if (res == 2160 && !enable4K) continue;
            available.add(res + "p");
        }

        long estimate = 0;
        try {
            estimate = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).getBitrateEstimate();
        } catch (Exception ignored) {}

        String effectiveQuality = com.example.animelib.util.AutoQualityHelper.resolveBestQuality(this, available, preferredQuality, estimate);
        int targetRes = com.example.animelib.util.AutoQualityHelper.extractResolution(effectiveQuality);

        EpisodeResponse.QualityData selectedQuality = null;
        for (EpisodeResponse.QualityData qData : qList) {
            if (qData.getQuality() == targetRes) {
                selectedQuality = qData;
                break;
            }
        }
        if (selectedQuality == null && !qList.isEmpty()) {
            selectedQuality = qList.get(0);
        }
        if (selectedQuality != null && selectedQuality.getHref() != null) {
            String domain = (playerData.getVideoDomain() != null && !playerData.getVideoDomain().isEmpty())
                    ? playerData.getVideoDomain() : currentVideoDomain;
            return VideoUrlHelper.toAbsoluteVideoUrl(selectedQuality.getHref(), domain);
        }
        return null;
    }

    @androidx.annotation.Nullable
    private String resolveKodikHlsUrl(KodikResponse kodikResponse, String preferredQuality) {
        if (kodikResponse == null || kodikResponse.getData() == null || kodikResponse.getData().isEmpty()) {
            return null;
        }
        List<String> availableQualities = new ArrayList<>();
        List<String> rawKeys = new ArrayList<>(kodikResponse.getData().keySet());
        rawKeys.sort((k1, k2) -> Integer.compare(
                com.example.animelib.util.AutoQualityHelper.extractResolution(k2),
                com.example.animelib.util.AutoQualityHelper.extractResolution(k1)
        ));
        for (String key : rawKeys) {
            int res = com.example.animelib.util.AutoQualityHelper.extractResolution(key);
            if (res == 2160 && !enable4K) continue;
            String qStr = res > 0 ? res + "p" : key;
            if (!availableQualities.contains(qStr)) {
                availableQualities.add(qStr);
            }
        }
        if (availableQualities.isEmpty()) {
            availableQualities.add("720p");
        }

        long estimate = 0;
        try {
            estimate = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).getBitrateEstimate();
        } catch (Exception ignored) {}

        String effectiveQuality = com.example.animelib.util.AutoQualityHelper.resolveBestQuality(this, availableQualities, preferredQuality, estimate);
        int targetRes = com.example.animelib.util.AutoQualityHelper.extractResolution(effectiveQuality);

        String hlsUrl = null;
        for (String key : kodikResponse.getData().keySet()) {
            int keyRes = com.example.animelib.util.AutoQualityHelper.extractResolution(key);
            if (keyRes == targetRes || key.equalsIgnoreCase(effectiveQuality) || (key + "p").equalsIgnoreCase(effectiveQuality)) {
                if (kodikResponse.getData().get(key) != null && kodikResponse.getData().get(key).length > 0) {
                    hlsUrl = kodikResponse.getData().get(key)[0].getSrc();
                    break;
                }
            }
        }
        if (hlsUrl == null) {
            for (String key : kodikResponse.getData().keySet()) {
                if (kodikResponse.getData().get(key) != null && kodikResponse.getData().get(key).length > 0) {
                    hlsUrl = kodikResponse.getData().get(key)[0].getSrc();
                    break;
                }
            }
        }
        if (hlsUrl != null && !hlsUrl.startsWith("http")) {
            hlsUrl = "https:" + hlsUrl;
        }
        return hlsUrl;
    }

    private void restartPlayerWithNewQuality() {
        preservePlayerAspectRatio();
        applyPlayerSidePanelTransform(0f);
        EpisodeResponse.PlayerData currentPlayerData = playersManager.getCurrentPlayerData();
        if (currentPlayerData == null) {
            return;
        }

        long currentPosition = player != null ? player.getCurrentPosition() : 0;

        autoPlayOnPrepare = player != null && player.getPlayWhenReady();

        stopCurrentPlayback();

        if (isDownloadedQuality(preferredQuality)) {
            com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
            if (downloadedEp != null && downloadedEp.getLocalFilePath() != null) {
                java.io.File file = new java.io.File(downloadedEp.getLocalFilePath());
                if (file.exists() && file.length() > 0) {
                    currentVideoUrl = Uri.fromFile(file).toString();
                    timecodeManager.setTimecodes(currentPlayerData);
                    initializePlayer();
                    if (currentPosition > 0 && player != null) {
                        player.seekTo(currentPosition);
                    }
                    playersManager.setCurrentPlayerData(currentPlayerData);
                    Log.d("VideoPlayer", "Restarted player with local downloaded file: " + file.getAbsolutePath());
                    return;
                }
            }
        }

        if ("animelib".equalsIgnoreCase(currentPlayerData.getPlayer())) {
            handleAnimelibPlayer(currentPlayerData, currentPosition);
        } else if ("kodik".equalsIgnoreCase(currentPlayerData.getPlayer())) {
            if (currentKodikResponse != null && currentKodikResponse.getData() != null) {
                String qualityKey = preferredQuality != null ? preferredQuality.replace("p", "") : null;
                if (qualityKey != null && currentKodikResponse.getData().containsKey(qualityKey) &&
                        Objects.requireNonNull(currentKodikResponse.getData().get(qualityKey)).length > 0) {
                    String newHlsUrl = Objects.requireNonNull(currentKodikResponse.getData().get(qualityKey))[0].getSrc();
                    if (newHlsUrl != null && !newHlsUrl.isEmpty()) {
                        if (!newHlsUrl.startsWith("http")) {
                            newHlsUrl = "https:" + newHlsUrl;
                        }
                        currentVideoUrl = newHlsUrl;
                        Log.d("VideoPlayer", "Updated HLS URL for quality " + preferredQuality + ": " + currentVideoUrl);
                    }
                }
                startHlsPlayer(currentKodikResponse, currentPosition);
                playersManager.setCurrentPlayerData(currentPlayerData);
                return;
            }
            handleKodikPlayer(currentPlayerData, currentPosition);
        }
        
        // Обновляем currentPlayerData в PlayersManager для правильной подсветки после смены качества
        playersManager.setCurrentPlayerData(currentPlayerData);
    }

    private void applySettingsFromDialog(SettingsBottomSheet dialog) {
        if (player != null) {
            // Apply playback speed
            float speed = dialog.getCurrentPlaybackSpeed();
            player.setPlaybackSpeed(speed);
            Log.d("VideoPlayer", "Applied playback speed: " + speed);
        }
    }

    private void showLoading(String message) {
        safeRunOnUiThread(() -> {
            isVideoLoading = true;
            updatePlayPauseAndLoadingState(true);
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(View.GONE);
            }
        });
    }

    private void hideLoading() {
        safeRunOnUiThread(() -> {
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(View.GONE);
            }
            isVideoLoading = false;
            updatePlayPauseAndLoadingState(true);
        });
    }

    private void showVideoErrorDialog(String title, String message, Runnable retryAction) {
        if (playerDialogsController != null) {
            playerDialogsController.showVideoErrorDialog(title, message, null, retryAction, false);
        }
    }

    private void showVideoErrorDialog(String title, String message, Runnable retryAction, boolean isVoiceoverError) {
        if (playerDialogsController != null) {
            playerDialogsController.showVideoErrorDialog(title, message, null, retryAction, isVoiceoverError);
        }
    }

    private void showVideoErrorDialog(String title, String message, String logDetails, Runnable retryAction, boolean isVoiceoverError) {
        if (playerDialogsController != null) {
            playerDialogsController.showVideoErrorDialog(title, message, logDetails, retryAction, isVoiceoverError);
        }
    }

    private String buildErrorLog(PlaybackException error) {
        StringBuilder log = new StringBuilder();
        log.append("Time: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date())).append("\n");
        log.append("Current Video URL: ").append(currentVideoUrl != null ? currentVideoUrl : "null").append("\n");
        log.append("Anime ID: ").append(currentAnimeId != null ? currentAnimeId : "null").append("\n");
        if (episodesManager != null && episodesManager.getCurrentEpisode() != null) {
            log.append("Episode: ").append(episodesManager.getCurrentEpisode().getNumber()).append("\n");
        }
        if (playersManager != null && playersManager.getCurrentPlayerData() != null) {
            EpisodeResponse.PlayerData pd = playersManager.getCurrentPlayerData();
            log.append("Player: ").append(pd.getPlayer()).append("\n");
            if (pd.getTeam() != null) {
                log.append("Team: ").append(pd.getTeam().getName()).append("\n");
            }
        }
        log.append("Preferred Quality: ").append(preferredQuality).append("\n");
        log.append("CDN Server Domain: ").append(currentVideoDomain).append("\n");
        log.append("Device: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
                .append(" (Android ").append(android.os.Build.VERSION.RELEASE)
                .append(", API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");

        if (error != null) {
            log.append("\nExoPlayer Error Code: ").append(error.getErrorCodeName()).append(" (").append(error.errorCode).append(")\n");
            log.append("Exception Message: ").append(error.getMessage()).append("\n");

            Throwable cause = error.getCause();
            if (cause != null) {
                log.append("Cause: ").append(cause.getClass().getName()).append(": ").append(cause.getMessage()).append("\n");
                if (cause instanceof androidx.media3.datasource.HttpDataSource.HttpDataSourceException) {
                    androidx.media3.datasource.HttpDataSource.HttpDataSourceException httpEx = (androidx.media3.datasource.HttpDataSource.HttpDataSourceException) cause;
                    if (httpEx.dataSpec != null) {
                        log.append("HTTP Request URI: ").append(httpEx.dataSpec.uri).append("\n");
                    }
                    if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException codeEx = (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause;
                        log.append("HTTP Response Code: ").append(codeEx.responseCode).append("\n");
                        log.append("HTTP Response Message: ").append(codeEx.responseMessage).append("\n");
                    }
                }
            }
            log.append("\nStacktrace:\n").append(android.util.Log.getStackTraceString(error));
        }
        return log.toString();
    }

    private void handlePlaybackError(PlaybackException error) {
        Log.e("VideoPlayer", "Playback error: " + (error != null ? error.getMessage() : "unknown"), error);

        if (currentVideoUrl != null && (currentVideoUrl.contains("cdnlibs.org") || currentVideoUrl.contains("imglib.info"))) {
            String nextDomain = null;
            if (VideoUrlHelper.DOMAIN_MAIN.equals(currentVideoDomain)) {
                nextDomain = VideoUrlHelper.DOMAIN_SECONDARY_1;
            } else if (VideoUrlHelper.DOMAIN_SECONDARY_1.equals(currentVideoDomain)) {
                nextDomain = VideoUrlHelper.DOMAIN_SECONDARY_2;
            }

            if (nextDomain != null) {
                Log.w("VideoPlayer", "CDN server " + currentVideoDomain + " failed. Trying fallback CDN server: " + nextDomain);
                currentVideoDomain = nextDomain;
                if (playersManager != null && playersManager.getCurrentPlayerData() != null) {
                    onPlayerSelected(playersManager.getCurrentPlayerData());
                    return;
                }
            }
        }

        String errorMsg = "Ошибка воспроизведения: " + (error != null ? error.getMessage() : "неизвестная ошибка");
        String logDetails = buildErrorLog(error);
        showVideoErrorDialog("Ошибка воспроизведения", errorMsg, logDetails, () -> {
            if (playersManager != null && playersManager.getCurrentPlayerData() != null) {
                onPlayerSelected(playersManager.getCurrentPlayerData());
            } else if (currentVideoUrl != null) {
                retryWithUrl(currentVideoUrl);
            }
        }, false);
    }

    private void initializePlayer() {
        if (playerPlaybackController != null) {
            MediaItem mediaItem = createMediaItemWithSubtitles(currentVideoUrl);
            player = playerPlaybackController.initializePlayer(
                    currentVideoUrl,
                    mediaItem,
                    currentResizeMode,
                    autoPlayOnPrepare
            );
            autoPlayOnPrepare = true;
            if (player != null) {
                setupSubtitlePlayerListener(player);
                applySubtitlesStateToPlayer();
                setupPlayerControlButtons();
            }
        }
    }

    private void retryWithUrl(String newUrl) {
        safeRunOnUiThread(() -> {
            Log.d("VideoPlayer", "Retrying playback with new URL: " + newUrl);
            currentVideoUrl = newUrl;

            // Check if this is an HLS URL (contains .m3u8 or hls in path)
            boolean isHls = newUrl.contains(".m3u8") || newUrl.contains(":hls:");

            if (isHls) {
                // For HLS, reinitialize the player with HLS support
                Log.d("VideoPlayer", "Retrying with HLS player");
                initializeHlsPlayer(newUrl);
            } else {
                // For regular video, just change the media item
                Log.d("VideoPlayer", "Retrying with regular player");
                MediaItem mediaItem = createMediaItemWithSubtitles(newUrl);
                if (player != null) {
                    player.setMediaItem(mediaItem);
                    player.prepare();
                    applySubtitlesStateToPlayer();
                    player.play();
                } else {
                    initializePlayer();
                }
            }
        });
    }

    private void loadAnimeFromUrl(String url) {
        this.animeUrl = url;
        if (getIntent() != null) {
            getIntent().putExtra(EXTRA_ANIME_URL, url);
            getIntent().putExtra("anime_url", url);
        }

        currentAnimeInfo = null;

        // Извлекаем anime ID из URL
        currentAnimeId = apiService.extractAnimeId(url);
        Log.d("VideoPlayer", "Extracted anime ID from URL: " + currentAnimeId);
        
        if (currentAnimeId == null) {
            CustomToast.showWarning(this, "Не удалось извлечь ID аниме из URL");
            finish();
            return;
        }

        // Асинхронно получаем метаданные аниме (AnimeInfo), чтобы заголовок, обложка и slug были точными
        String animeSlug = apiService.extractAnimeSlug(url);
        if (animeSlug == null) animeSlug = currentAnimeId;

        if (playerAnimeInfoController != null) {
            playerAnimeInfoController.showSkeletons();
        }

        apiService.fetchAnimeInfo(animeSlug, new ApiService.AnimeInfoCallback() {
            @Override
            public void onAnimeInfoReceived(AnimeInfoResponse response) {
                if (response != null && response.getData() != null) {
                    safeRunOnUiThread(() -> {
                        displayAnimeInfo(response);
                        Log.d("VideoPlayer", "Fetched and displayed anime info for " + response.getData().getRus_name());
                    });
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.w("VideoPlayer", "Failed to fetch anime info in loadAnimeFromUrl: " + errorMessage);
                safeRunOnUiThread(() -> {
                    if (playerAnimeInfoController != null && currentAnimeInfo == null) {
                        playerAnimeInfoController.showError(() -> loadAnimeFromUrl(url));
                    }
                });
            }
        });
        
        // Load related titles
        loadRelatedTitles();
        
        // ВАЖНО: Сначала загружаем эпизоды, потом плееры!
        // Это позволит правильно определить эпизод из закладки
        Log.d("VideoPlayer", "Loading episodes first, then players will be loaded for correct episode");
        loadEpisodes(currentAnimeId);
        fetchAndApplyWatchStatus();
    }

    private void showPlayerSelectionDialogWithAutoSelect(List<EpisodeResponse.PlayerData> players) {
        hideLoading();

        Log.d("VideoPlayer", "showPlayerSelectionDialogWithAutoSelect called with " + players.size() + " players");
        Log.d("VideoPlayer", "NOTE: setPlayersData already called by PlayersManager, only checking for auto-select");

        // Попытка автоматического выбора плеера на основе сохраненных предпочтений
        // ВАЖНО: setPlayersData уже вызван в PlayersManager.loadPlayersForEpisode()!
        executor.execute(() -> {
            com.example.animelib.data.entity.PlayerPreferences prefs = apiService.loadPlayerPreferences();
            
            Log.d("VideoPlayer", "Loaded preferences from DB: " + (prefs != null ? 
                  ("player=" + prefs.getPlayer() + ", teamId=" + prefs.getTeamId() + ", quality=" + prefs.getPreferredQuality()) : 
                  "null"));
            
            EpisodeResponse.PlayerData matchingPlayer = null;
            
            if (prefs != null && prefs.getPlayer() != null && prefs.getTeamId() != null) {
                Log.d("VideoPlayer", "Found saved preferences: player=" + prefs.getPlayer() + ", teamId=" + prefs.getTeamId());
                
                // Сначала ищем точное совпадение (сохраненный плеер + озвучка)
                for (EpisodeResponse.PlayerData player : players) {
                    if (player.getPlayer() != null && 
                        player.getPlayer().equals(prefs.getPlayer()) && 
                        player.getTeam() != null && 
                        player.getTeam().getId() == prefs.getTeamId()) {
                        matchingPlayer = player;
                        Log.d("VideoPlayer", "Found exact match in saved player: " + player.getPlayer() + 
                              ", team: " + player.getTeam().getName());
                        break;
                    }
                }
                
                // Если не найдено в сохраненном плеере, ищем озвучку в других плеерах
                if (matchingPlayer == null) {
                    Log.d("VideoPlayer", "Team not found in saved player, searching in other players");
                    for (EpisodeResponse.PlayerData player : players) {
                        if (player.getTeam() != null && 
                            player.getTeam().getId() == prefs.getTeamId()) {
                            matchingPlayer = player;
                            Log.d("VideoPlayer", "Found team in different player: " + player.getPlayer() + 
                                  ", team: " + player.getTeam().getName());
                            break;
                        }
                    }
                }
            } else {
                Log.d("VideoPlayer", "No saved player preferences found");
            }
            
            // Финальный результат в UI потоке
            EpisodeResponse.PlayerData finalMatchingPlayer = matchingPlayer;
            safeRunOnUiThread(() -> {
                if (finalMatchingPlayer != null) {
                    // Автоматически выбираем найденный плеер
                    Log.d("VideoPlayer", "Auto-selecting player based on preferences");
                    onPlayerSelected(finalMatchingPlayer);
                } else if (players != null && !players.isEmpty()) {
                    // Ставим первую доступную озвучку, если не сохранена последняя
                    Log.d("VideoPlayer", "No matching saved preference, auto-selecting first available player");
                    onPlayerSelected(players.get(0));
                } else {
                    Log.d("VideoPlayer", "No players available");
                }
                
                // Скрываем loading overlay в любом случае
                if (menuLoadingOverlay != null) menuLoadingOverlay.setVisibility(View.GONE);
            });
        });
    }

    private void handleAnimelibPlayer(EpisodeResponse.PlayerData playerData, long seekToPosition) {
        if (playerVideoResolverController != null) {
            playerVideoResolverController.handleAnimelibPlayer(playerData, seekToPosition);
        }
    }

    private void handleKodikPlayer(EpisodeResponse.PlayerData playerData, long seekToPosition) {
        if (playerVideoResolverController != null) {
            playerVideoResolverController.handleKodikPlayer(playerData, seekToPosition);
        }
    }

    private void fetchKodikVideoLinks(String kodikSrc, long seekToPosition) {
        if (playerVideoResolverController != null) {
            playerVideoResolverController.fetchKodikVideoLinks(kodikSrc, seekToPosition);
        }
    }

    private void loadEpisodes(String animeId) {
        if (playerEpisodesController != null) {
            playerEpisodesController.loadEpisodes(animeId, getIntent());
        }
    }

    private void loadFirstEpisode() {
        if (playerEpisodesController != null) {
            autoPlayOnPrepare = this.autoPlay;
            playerEpisodesController.loadFirstEpisode();
        }
    }
    
    private void fallbackToUrlDetection() {
        if (playerEpisodesController != null) {
            playerEpisodesController.fallbackToUrlDetection(getIntent());
        }
    }

    /**
     * Включает кнопку закладки когда плеер готов
     */
    private void enableBookmarkButton() {
        if (bookmarkButton != null) {
            bookmarkButton.setEnabled(true);
            bookmarkButton.setClickable(true);
            bookmarkButton.setAlpha(1.0f);
            Log.d("VideoPlayer", "Bookmark button enabled");
        }
        if (portraitBookmarkButton != null) {
            portraitBookmarkButton.setEnabled(true);
            portraitBookmarkButton.setClickable(true);
            portraitBookmarkButton.setFocusable(true);
            portraitBookmarkButton.setAlpha(1.0f);
        }
    }
    
    private void saveLatestViewOnExit() {
        if (playerProgressController != null) {
            EpisodesListResponse.EpisodeItem currentEpisode = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
            EpisodeResponse.PlayerData currentPlayer = playersManager != null ? playersManager.getCurrentPlayerData() : null;
            long currentPosition = player != null ? player.getCurrentPosition() : 0;
            long duration = player != null && player.getDuration() > 0 ? player.getDuration() : 0;
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
    }

    /**
     * Автоматически сохраняет закладку с текущим таймкодом
     */
    private void autoSaveBookmark() {
        if (playerProgressController != null) {
            playerProgressController.autoSaveBookmark();
        }
    }
    
    private static class ColorAnimHolder {
        int color;
        android.animation.ValueAnimator animator;
    }

    private void animateImageColorFilter(android.widget.ImageView imageView, int targetColor) {
        if (imageView == null) return;
        ColorAnimHolder holder = (ColorAnimHolder) imageView.getTag();
        if (holder == null) {
            holder = new ColorAnimHolder();
            holder.color = targetColor;
            imageView.setTag(holder);
            imageView.setColorFilter(targetColor);
            return;
        }

        if (holder.animator != null) {
            holder.animator.cancel();
            holder.animator = null;
        }

        if (holder.color == targetColor) {
            imageView.setColorFilter(targetColor);
            return;
        }

        final ColorAnimHolder finalHolder = holder;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), holder.color, targetColor);
        anim.setDuration(220);
        anim.addUpdateListener(animation -> {
            int val = (int) animation.getAnimatedValue();
            finalHolder.color = val;
            imageView.setColorFilter(val);
        });
        holder.animator = anim;
        anim.start();
    }

    /**
     * Обновляет цвет кнопки закладки с плавной анимацией перехода
     * @param isBookmarked true если закладка добавлена, false если нет
     */
    private void updateBookmarkButtonColor(boolean isBookmarked) {
        if (playerProgressController != null) {
            playerProgressController.updateBookmarkButtonColor(isBookmarked);
        }
    }

    private void updateEpisodesListAfterBookmark() {
        if (playerProgressController != null) {
            playerProgressController.updateEpisodesListAfterBookmark();
        }
    }

    private void initializeMenuWithoutAutoPlay() {
        // Players are now managed by PlayersManager
        episodesManager.updateEpisodeNavigationButtonsVisibility();
        episodesManager.updateEpisodesRecyclerView(); // Call this last to ensure currentEpisode is set

        // Загружаем плееры для текущего эпизода
        EpisodesListResponse.EpisodeItem currentEpisode = episodesManager.getCurrentEpisode();
        if (currentEpisode != null) {
            Log.d("VideoPlayer", "Loading players for episode in initializeMenuWithoutAutoPlay: " + currentEpisode.getNumber());
            playersManager.loadPlayersForEpisode(currentEpisode.getId());
        } else {
            Log.d("VideoPlayer", "No current episode available for loading players");
        }

        // PlayersManager handles auto-selection and menu display
        // Ensure loading overlay is hidden once players are available
        if (menuLoadingOverlay != null) menuLoadingOverlay.setVisibility(View.GONE);
        if (menuLoadingIndicator != null) menuLoadingIndicator.setVisibility(View.GONE);
    }

    /**
     * Быстрое обновление заголовка с номером эпизода (синхронно, без API)
     */
    private void updateEpisodeHeaderQuick() {
        safeRunOnUiThread(() -> {
            com.example.animelib.data.entity.DownloadedEpisodeEntity downloadedEp = getDownloadedEpisodeForActive();
            boolean isPlayingDownloaded = isOfflineMode || isPlayingDownloadedVideo() ||
                    (downloadedEp != null && downloadedEp.getLocalFilePath() != null && new File(downloadedEp.getLocalFilePath()).exists() &&
                     (currentVideoUrl == null || currentVideoUrl.startsWith("file:") || currentVideoUrl.startsWith("/") || isDownloadedQuality(preferredQuality)));

            if (isPlayingDownloaded && downloadedEp != null) {
                hideAllSkeletonsForOffline();
                return;
            } else if (isOfflineMode) {
                hideAllSkeletonsForOffline();
                return;
            }

            EpisodeResponse.PlayerData currentPlayerData = playersManager != null ? playersManager.getCurrentPlayerData() : null;
            EpisodesListResponse.EpisodeItem currentEpisode = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
            
            String tm = (currentPlayerData != null && currentPlayerData.getTeam() != null)
                    ? currentPlayerData.getTeam().getName() : null;
            if (downloadedEp != null && isPlayingDownloadedVideo()) {
                tm = downloadedEp.getTeamName();
            }
            String ep = (currentEpisode != null) ? currentEpisode.getNumber() : null;
            String rawEm = (currentEpisode != null && currentEpisode.getName() != null && !Objects.equals(currentEpisode.getName(), ""))
                    ? currentEpisode.getName() : null;
            String em = cleanEpisodeName(rawEm, ep);

            if (currentTeamName != null) {
                if (tm != null && !tm.isEmpty()) {
                    SkeletonHelper.hideSkeleton(currentTeamName, tm);
                } else if (currentPlayerData == null) {
                    SkeletonHelper.showSkeleton(currentTeamName, 80);
                } else {
                    SkeletonHelper.hideSkeleton(currentTeamName, "");
                }
            }

            if (currentEpisodeNumberView != null) {
                if (ep != null && !ep.isEmpty()) {
                    SkeletonHelper.hideSkeleton(currentEpisodeNumberView, ep + " серия");
                    Log.d("VideoPlayer", "Quick header update: episode " + ep);
                } else if (currentEpisode == null) {
                    SkeletonHelper.showSkeleton(currentEpisodeNumberView, 65);
                } else {
                    SkeletonHelper.hideSkeleton(currentEpisodeNumberView, "");
                }
            }

            if (currentEpisodeName != null) {
                if (em != null && !em.isEmpty()) {
                    SkeletonHelper.hideSkeleton(currentEpisodeName, ", " + em);
                } else {
                    SkeletonHelper.hideSkeleton(currentEpisodeName, "");
                }
            }

            updatePortraitVoiceoverPlayerUI();
            updatePortraitHeaderTitlesUI();
        });
    }
    
    /**
     * Полное обновление заголовка с названием аниме (асинхронно с API)
     */
    private void updateAnimeInfoHeaderFull() {
        // Сначала быстро обновляем эпизод
        updateEpisodeHeaderQuick();

        // Показываем сначала имеющееся название из Intent или прошлых данных
        String initialTitle = getIntent() != null ? getIntent().getStringExtra("EXTRA_ANIME_TITLE") : null;
        if (initialTitle == null || initialTitle.isEmpty()) {
            initialTitle = getIntent() != null ? getIntent().getStringExtra("anime_title") : null;
        }
        if (initialTitle != null && !initialTitle.isEmpty() && animeTitleView != null) {
            SkeletonHelper.hideSkeleton(animeTitleView, initialTitle);
            updatePortraitHeaderTitlesUI();
        }
        
        // Затем асинхронно загружаем название аниме
        if (animeTitleView == null) return;
        String slugOrId = apiService.extractAnimeSlug(animeUrl);
        if (slugOrId == null) return;

        apiService.fetchAnimeInfo(slugOrId, new ApiService.AnimeInfoCallback() {
            @Override
            public void onAnimeInfoReceived(AnimeInfoResponse response) {
                safeRunOnUiThread(() -> {
                    if (response != null && response.getData() != null) {
                        displayAnimeInfo(response);
                        Log.d("VideoPlayer", "Full header update: anime title and info set");
                    }
                });
            }

            @Override
            public void onError(String error) {
                safeRunOnUiThread(() -> {
                    String fallback = getIntent() != null ? getIntent().getStringExtra("EXTRA_ANIME_TITLE") : null;
                    if (fallback == null || fallback.isEmpty()) {
                        fallback = getIntent() != null ? getIntent().getStringExtra("anime_title") : null;
                    }
                    if ((fallback == null || fallback.isEmpty()) && currentAnimeInfo != null && currentAnimeInfo.getData() != null) {
                        fallback = currentAnimeInfo.getData().getRus_name();
                    }
                    if (fallback == null || fallback.isEmpty()) {
                        fallback = "Аниме";
                    }
                    SkeletonHelper.hideSkeleton(animeTitleView, fallback);
                    updatePortraitHeaderTitlesUI();
                    if (playerAnimeInfoController != null && currentAnimeInfo == null) {
                        playerAnimeInfoController.showError(() -> updateAnimeInfoHeaderFull());
                    }
                });
                Log.w("VideoPlayer", "Failed to load anime title: " + error);
            }
        });
    }
    
    /**
     * Старый метод для обратной совместимости
     */
    private void updateAnimeInfoHeader() {
        updateAnimeInfoHeaderFull();
    }

    private void startHlsPlayer(KodikResponse kodikResponse, long seekToPosition) {
        if (playerVideoResolverController != null) {
            playerVideoResolverController.startHlsPlayer(kodikResponse, seekToPosition);
        }
    }

    private void initializeHlsPlayer(String hlsUrl) {
        if (playerPlaybackController != null) {
            MediaItem mediaItem = createMediaItemWithSubtitles(hlsUrl);
            player = playerPlaybackController.initializeHlsPlayer(
                    hlsUrl,
                    mediaItem,
                    currentResizeMode,
                    autoPlayOnPrepare
            );
            autoPlayOnPrepare = true;
            if (player != null) {
                setupSubtitlePlayerListener(player);
                applySubtitlesStateToPlayer();
                setupPlayerControlButtons();
            }
        }
    }

    // ================= Subtitle Helpers =================

    private List<MediaItem.SubtitleConfiguration> buildSubtitleConfigurations() {
        if (playerSubtitlesController != null) {
            return playerSubtitlesController.buildSubtitleConfigurations();
        }
        return new ArrayList<>();
    }

    private String getMimeTypeForSubtitle(String format, String url) {
        if (playerSubtitlesController != null) {
            return playerSubtitlesController.getMimeTypeForSubtitle(format, url);
        }
        return androidx.media3.common.MimeTypes.TEXT_UNKNOWN;
    }

    private MediaItem createMediaItemWithSubtitles(String videoUrl) {
        if (playerSubtitlesController != null) {
            return playerSubtitlesController.createMediaItemWithSubtitles(videoUrl);
        }
        return new MediaItem.Builder().setUri(videoUrl).build();
    }

    private boolean isCurrentSubtitleVttOrSrt() {
        if (playerSubtitlesController != null) {
            return playerSubtitlesController.isCurrentSubtitleVttOrSrt();
        }
        return true;
    }

    private void setupSubtitlePlayerListener(ExoPlayer p) {
        if (playerSubtitlesController != null) {
            playerSubtitlesController.setupSubtitlePlayerListener(p);
        }
    }

    private void applySubtitlesStateToPlayer() {
        if (playerSubtitlesController != null) {
            playerSubtitlesController.applySubtitlesStateToPlayer();
        }
    }

    private void reloadPlayerWithSubtitles() {
        if (playerSubtitlesController != null) {
            playerSubtitlesController.reloadPlayerWithSubtitles();
        }
    }

    private void startSeekingState() {
        isSeeking = true;
        if (seekResetHandler != null) {
            seekResetHandler.removeCallbacksAndMessages(null);
        }
        startBufferingMonitoring();
        updatePlayPauseAndLoadingState(true);
    }

    private void scheduleEndSeekingState(long delayMs) {
        if (seekResetHandler != null) {
            seekResetHandler.removeCallbacksAndMessages(null);
            Runnable r = () -> {
                if (!isScrubbingTimeBar) {
                    long currentPos = (player != null) ? player.getCurrentPosition() : 0;
                    long bufferedPos = (player != null) ? player.getBufferedPosition() : 0;
                    long duration = (player != null) ? player.getDuration() : 0;
                    long bufferedAhead = bufferedPos - currentPos;
                    boolean isNearEnd = duration > 0 && currentPos >= (duration - 2000);

                    boolean isReadyAndBuffered = (player != null)
                            && (player.getPlaybackState() == Player.STATE_READY)
                            && (hasRenderedFirstFrame || player.isPlaying())
                            && (isNearEnd || bufferedAhead >= 1500);

                    if (player == null || !player.getPlayWhenReady() || isReadyAndBuffered) {
                        isSeeking = false;
                        updatePlayPauseAndLoadingState(true);
                    } else {
                        if (seekResetHandler != null) {
                            seekResetHandler.postDelayed(() -> scheduleEndSeekingState(0), 250);
                        }
                    }
                }
            };
            if (delayMs <= 0) {
                r.run();
            } else {
                seekResetHandler.postDelayed(r, delayMs);
            }
        } else {
            if (!isScrubbingTimeBar) {
                isSeeking = false;
                updatePlayPauseAndLoadingState(true);
            }
        }
    }

    private void setupPlayerControlButtons() {
        View controllerView = playerView.findViewById(R.id.exo_controller);
        if (controllerView == null) {
            Log.w("PlayerControls", "Controller view not found");
            return;
        }

        // НЕ трогаем стандартные ExoPlayer кнопки (exo_play, exo_pause)
        // ExoPlayer сам их обрабатывает автоматически

        // Настраиваем только наши кастомные кнопки
        ImageButton skipForwardButton = controllerView.findViewById(R.id.skipForwardButton);

        // Setup skip forward button (1 минута 25 секунд)
        if (skipForwardButton != null) {
            skipForwardButton.setOnClickListener(v -> {
                Log.d("PlayerControls", "Skip forward clicked (1:25)");
                if (player != null && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                    long currentPosition = player.getCurrentPosition();
                    long skipDuration = longSkipDuration * 1000L; // Convert seconds to milliseconds
                    long newPosition = currentPosition + skipDuration;
                    long duration = player.getDuration();

                    // Не даем перемотать дальше конца видео
                    if (newPosition > duration && duration > 0) {
                        newPosition = duration;
                    }

                    startSeekingState();
                    player.seekTo(newPosition);
                    scheduleEndSeekingState(600);
                    Log.d("PlayerControls", "Skipped forward to: " + (newPosition / 1000) + "s (+" + (skipDuration / 1000) + "s)");
                } else {
                    Log.w("PlayerControls", "Skip forward not available");
                }
            });
        }

        // Подключаем listener для синхронизации состояния плеера и кнопок
        setupPlayerListener();

        // Инициализируем состояние кнопок
        updatePlayerControlsState();

        // Устанавливаем начальную видимость play/pause кнопок и индикатора
        updatePlayPauseAndLoadingState(false);

        // Проверяем, что найдены кнопки управления
        ImageButton btnPlayerPlay = controllerView.findViewById(R.id.btnPlayerPlay);
        ImageButton btnPlayerPause = controllerView.findViewById(R.id.btnPlayerPause);

        Log.d("PlayerControls", "Player buttons found - Play: " + (btnPlayerPlay != null) +
                ", Pause: " + (btnPlayerPause != null));

        if (btnPlayerPlay != null) {
            btnPlayerPlay.setOnClickListener(v -> {
                Log.d("PlayerControls", "Play button clicked!");
                if (player != null) {
                    player.play();
                    updatePlayPauseAndLoadingState(true);
                }
            });
        }

        if (btnPlayerPause != null) {
            btnPlayerPause.setOnClickListener(v -> {
                Log.d("PlayerControls", "Pause button clicked!");
                if (player != null) {
                    player.pause();
                    updatePlayPauseAndLoadingState(true);
                }
            });
        }

        Log.d("PlayerControls", "Player control buttons setup completed");
    }

    private void updatePlayLoadingIndicator(int playbackState) {
        updatePlayPauseAndLoadingState(true);
    }

    private void updatePlayPauseAndLoadingState(boolean animate) {
        safeRunOnUiThread(() -> {
            boolean isEnded = (player != null) && (player.getPlaybackState() == Player.STATE_ENDED);
            boolean isPlayWhenReady = (player != null) && player.getPlayWhenReady();
            int playbackState = (player != null) ? player.getPlaybackState() : Player.STATE_IDLE;
            boolean realIsPlaying = (player != null) && player.isPlaying();
            boolean isLoading = (player != null) && player.isLoading();

            long currentPos = (player != null) ? player.getCurrentPosition() : 0;
            long bufferedPos = (player != null) ? player.getBufferedPosition() : 0;
            long duration = (player != null) ? player.getDuration() : 0;
            long bufferedAheadMs = bufferedPos - currentPos;

            boolean isNearEnd = duration > 0 && currentPos >= (duration - 2000);

            // Детектируем реальную подгрузку/буферизацию сети (особенно для MP4 AnimeLib и HLS):
            boolean isNetworkBuffering = isPlayWhenReady && !isNearEnd && playbackState == Player.STATE_BUFFERING;

            // Буферизация / загрузка / перемотка:
            boolean isBuffering = !isEnded && (
                    (isVideoLoading && !hasRenderedFirstFrame && playbackState != Player.STATE_READY)
                    || isSeeking
                    || isScrubbingTimeBar
                    || isNetworkBuffering
            );

            // Показываем индикатор буферизации поверх плеера/контролов, если не активен полноэкранный loadingOverlay
            boolean showBuffering = isBuffering && (loadingOverlay == null || loadingOverlay.getVisibility() != View.VISIBLE);

            // Оверлейный индикатор на самом плеере (отображается, когда контролы скрыты)
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

            if (playerView == null) return;
            View controllerView = playerView.findViewById(R.id.exo_controller);
            if (controllerView == null) return;

            View play = controllerView.findViewById(R.id.btnPlayerPlay);
            View pause = controllerView.findViewById(R.id.btnPlayerPause);
            View spinner = controllerView.findViewById(R.id.playLoadingIndicator);

            if (play == null || pause == null || spinner == null) return;

            int targetState;
            if (showBuffering) {
                targetState = 2; // LOADING
            } else if (realIsPlaying || (isPlayWhenReady && playbackState == Player.STATE_READY)) {
                targetState = 1; // PAUSE
            } else {
                targetState = 0; // PLAY
            }

            if (currentControlState == targetState && currentControlState != -1) {
                // Убедимся, что нужная view точно видима при совпадении состояния
                View activeView = (targetState == 2) ? spinner : ((targetState == 1) ? pause : play);
                if (activeView.getVisibility() != View.VISIBLE) {
                    activeView.setVisibility(View.VISIBLE);
                    activeView.setAlpha(1.0f);
                }
                return;
            }

            boolean shouldAnimate = animate && (currentControlState != -1);
            currentControlState = targetState;

            View showView = (targetState == 2) ? spinner : ((targetState == 1) ? pause : play);
            View hideView1 = (targetState == 2) ? play : ((targetState == 1) ? play : pause);
            View hideView2 = (targetState == 2) ? pause : ((targetState == 1) ? spinner : spinner);

            animateControlViewSwitch(showView, hideView1, hideView2, shouldAnimate);
        });
    }

    private void animateControlViewSwitch(View showView, View hideView1, View hideView2, boolean animate) {
        if (showView == null) return;

        for (View v : new View[]{hideView1, hideView2}) {
            if (v != null) {
                v.animate().cancel();
                if (animate && v.getVisibility() == View.VISIBLE) {
                    v.animate()
                            .alpha(0f)
                            .scaleX(0.6f)
                            .scaleY(0.6f)
                            .setDuration(150)
                            .withEndAction(() -> {
                                v.setVisibility(View.GONE);
                                v.setAlpha(1.0f);
                                v.setScaleX(1.0f);
                                v.setScaleY(1.0f);
                            })
                            .start();
                } else {
                    v.setVisibility(View.GONE);
                    v.setAlpha(1.0f);
                    v.setScaleX(1.0f);
                    v.setScaleY(1.0f);
                }
            }
        }

        showView.animate().cancel();
        showView.setVisibility(View.VISIBLE);

        if (showView instanceof com.google.android.material.progressindicator.CircularProgressIndicator) {
            ((com.google.android.material.progressindicator.CircularProgressIndicator) showView).show();
        }

        if (animate) {
            showView.setAlpha(0f);
            showView.setScaleX(0.6f);
            showView.setScaleY(0.6f);
            showView.animate()
                    .alpha(1.0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start();
        } else {
            showView.setAlpha(1.0f);
            showView.setScaleX(1.0f);
            showView.setScaleY(1.0f);
        }
    }
    
    /**
     * Добавляет текущую серию в закладки
     */
    private void addBookmark() {
        Log.d("VideoPlayer", "Add bookmark method called");

        EpisodesListResponse.EpisodeItem currentEpisode = episodesManager.getCurrentEpisode();
        if (currentEpisode == null) {
            Log.e("VideoPlayer", "Current episode is null, cannot add bookmark");
            CustomToast.showWarning(this, "Эпизод не выбран");
            return;
        }

        long currentPosition = player != null ? player.getCurrentPosition() : 0;
        String timecode = ApiService.formatTimecode(currentPosition);

        if (isOfflineMode) {
            String animeId = currentAnimeId != null ? currentAnimeId : (getIntent() != null ? getIntent().getStringExtra("EXTRA_ANIME_ID") : null);
            if (animeId == null && getIntent() != null && getIntent().getStringExtra("EXTRA_LOCAL_FILE_PATH") != null && apiService != null && apiService.getDatabaseManager() != null) {
                com.example.animelib.data.entity.DownloadedEpisodeEntity dep =
                        apiService.getDatabaseManager().findEpisodeByPath(getIntent().getStringExtra("EXTRA_LOCAL_FILE_PATH"));
                if (dep != null) animeId = dep.getAnimeId();
            }

            if (animeId != null && apiService != null && apiService.getDatabaseManager() != null) {
                apiService.getDatabaseManager().saveOfflineBookmark(animeId, currentEpisode.getId(), currentEpisode.getNumber(), timecode, currentPosition);
                autoBookmarkSaved = true;
                updateBookmarkButtonColor(true);

                com.example.animelib.models.AnimeBookmarkResponse.BookmarkData offlineBookmark =
                        new com.example.animelib.models.AnimeBookmarkResponse.BookmarkData();
                offlineBookmark.setItemId(currentEpisode.getId());
                offlineBookmark.setProgress(timecode);
                episodesManager.updateBookmarkInAdapter(offlineBookmark);

                CustomToast.showSuccess(this, "Закладка сохранена (" + timecode + ")");
            } else {
                CustomToast.showWarning(this, "Не удалось сохранить закладку");
            }
            return;
        }

        if (apiService == null || !apiService.isAuthorized()) {
            CustomToast.showWarning(this, "Без авторизации поставить закладку нельзя");
            return;
        }

        // Получаем текущие данные эпизода
        EpisodeResponse.PlayerData currentPlayer = playersManager.getCurrentPlayerData();
        if (currentPlayer == null) {
            Log.e("VideoPlayer", "Current player is null, cannot add bookmark");
            return;
        }

        String animeUrl = getIntent().getStringExtra("anime_url");
        String mediaSlug = null;
        if (animeUrl != null && !animeUrl.isEmpty()) {
            mediaSlug = ApiService.extractMediaSlugFromUrl(animeUrl);
        }
        
        if (mediaSlug == null) {
            Log.e("VideoPlayer", "Media slug is null, cannot add bookmark");
            CustomToast.showWarning(this, "Не удалось определить аниме");
            return;
        }
        
        Log.d("VideoPlayer", "Adding bookmark with data:");
        Log.d("VideoPlayer", "  - mediaSlug: " + mediaSlug);
        Log.d("VideoPlayer", "  - episodeId: " + currentEpisode.getId());
        Log.d("VideoPlayer", "  - episodeNumber: " + currentEpisode.getNumber());
        Log.d("VideoPlayer", "  - teamId: " + (currentPlayer.getTeam() != null ? currentPlayer.getTeam().getId() : "null"));
        Log.d("VideoPlayer", "  - currentPosition: " + currentPosition + "ms");

        // Используем BookmarkManager для добавления закладки
        episodesManager.getBookmarkManager().addBookmark(
            mediaSlug,
            currentPlayer,
            currentEpisode,
            currentPosition,
            currentWatchStatusId,
            new BookmarkManager.BookmarkAddCallback() {
                @Override
                public void onBookmarkAdded(int episodeId) {
                    // Меняем цвет кнопки на красный
                    updateBookmarkButtonColor(true);
                    
                    // Обновляем список эпизодов
                    updateEpisodesListAfterBookmark();
                }
                
                @Override
                public void onBookmarkError(String error) {
                    // Кнопка остается белой при ошибке
                    updateBookmarkButtonColor(false);
                }
            },
            true // Показываем Toast при успехе для ручного добавления
        );
    }

    public void updatePortraitWatchStatusUI(Object statusId) {
        this.currentWatchStatusId = statusId;
        if (portraitStatusDropdownButton != null) {
            portraitStatusDropdownButton.setVisibility(View.VISIBLE);
        }
        com.example.animelib.models.WatchStatusItem item = com.example.animelib.managers.WatchStatusManager.getStatusById(statusId);
        if (tvPortraitStatus == null) return;

        if (item != null) {
            tvPortraitStatus.setText(item.getLabel());
            try {
                int color = android.graphics.Color.parseColor(item.getColorHex());
                tvPortraitStatus.setTextColor(color);
                if (ivPortraitStatusBookmarkIcon != null) {
                    ivPortraitStatusBookmarkIcon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
                }
            } catch (Exception e) {
                Log.e("VideoPlayer", "Error parsing status color: " + item.getColorHex(), e);
            }
        } else {
            tvPortraitStatus.setText("Добавить в список");
            int defaultColor = androidx.core.content.ContextCompat.getColor(this, R.color.primary_text_color);
            tvPortraitStatus.setTextColor(defaultColor);
            if (ivPortraitStatusBookmarkIcon != null) {
                ivPortraitStatusBookmarkIcon.setImageTintList(android.content.res.ColorStateList.valueOf(defaultColor));
            }
        }
    }

    public void fetchAndApplyWatchStatus() {
        if (isOfflineMode) return;
        String animeUrl = getIntent().getStringExtra("anime_url");
        if (animeUrl == null || animeUrl.isEmpty()) {
            animeUrl = this.animeUrl;
        }
        String mediaSlug = com.example.animelib.api.ApiService.extractMediaSlugFromUrl(animeUrl);
        if (mediaSlug == null && animeUrl != null) {
            mediaSlug = apiService.extractAnimeSlug(animeUrl);
        }
        if (mediaSlug != null && !mediaSlug.isEmpty()) {
            apiService.fetchAnimeBookmark(mediaSlug, new com.example.animelib.api.ApiService.AnimeBookmarkCallback() {
                @Override
                public void onBookmarkReceived(com.example.animelib.models.AnimeBookmarkResponse response) {
                    if (response != null && response.getData() != null) {
                        int status = response.getData().getStatus();
                        if (status > 0) {
                            runOnUiThread(() -> updatePortraitWatchStatusUI(status));
                        }
                    }
                }

                @Override
                public void onError(String error) {
                    Log.d("VideoPlayer", "Error fetching watch status: " + error);
                }
            });
        }
    }

    public void showWatchStatusBottomSheet() {
        if (isOfflineMode) {
            CustomToast.showInfo(this, "Смена статуса недоступна в офлайн режиме");
            return;
        }

        String animeUrl = getIntent().getStringExtra("anime_url");
        if (animeUrl == null || animeUrl.isEmpty()) {
            animeUrl = this.animeUrl;
        }
        String mediaSlug = com.example.animelib.api.ApiService.extractMediaSlugFromUrl(animeUrl);
        if (mediaSlug == null && animeUrl != null) {
            mediaSlug = apiService.extractAnimeSlug(animeUrl);
        }

        if (mediaSlug == null || mediaSlug.isEmpty()) {
            CustomToast.showWarning(this, "Не удалось определить аниме для обновления статуса");
            return;
        }

        final String finalMediaSlug = mediaSlug;

        com.example.animelib.ui.WatchStatusBottomSheet bottomSheet =
                new com.example.animelib.ui.WatchStatusBottomSheet(this, currentWatchStatusId, item -> {
                    updatePortraitWatchStatusUI(item.getId());
                    apiService.updateWatchStatus(finalMediaSlug, item.getId(), new com.example.animelib.api.ApiService.BookmarkCallback() {
                        @Override
                        public void onSuccess(String message) {
                            CustomToast.showSuccess(VideoPlayerActivity.this, "Статус изменен: " + item.getLabel());
                        }

                        @Override
                        public void onError(String error) {
                            CustomToast.showWarning(VideoPlayerActivity.this, "Не удалось изменить статус просмотра");
                        }
                    });
                });
        bottomSheet.show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && wasPlayingBeforeBackground
                && player.getPlaybackState() != Player.STATE_ENDED) {
            player.play();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        autoSaveBookmark();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopBufferingMonitoring();

        if (playerOrientationController != null) {
            playerOrientationController.disable();
        }

        autoSaveBookmark();

        if (player != null) {
            wasPlayingBeforeBackground = player.getPlayWhenReady();
            player.pause();
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
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        
        if (manualOrientationOverride && lastPhysicalOrientation == newConfig.orientation) {
            manualOrientationOverride = false;
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        }

        setupFullscreen();
        checkAndUpdateOrientation();
        if (ambientLightManager != null) {
            ambientLightManager.onConfigurationChanged();
        }
    }

    private int getStatusBarHeight() {
        int sbHeight = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                sbHeight = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
            }
        }
        if (sbHeight <= 0) {
            int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                sbHeight = getResources().getDimensionPixelSize(resourceId);
            }
        }
        return sbHeight;
    }

    private void applyRoundedCornersToTimeBar(View timeBarView) {
        if (timeBarView == null) return;
        try {
            float density = getResources().getDisplayMetrics().density;
            float radiusPx = 2f * density;
            android.graphics.CornerPathEffect pathEffect = new android.graphics.CornerPathEffect(radiusPx);

            Class<?> clazz = timeBarView.getClass();
            while (clazz != null && clazz != View.class) {
                java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
                for (java.lang.reflect.Field field : fields) {
                    if (android.graphics.Paint.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        android.graphics.Paint paint = (android.graphics.Paint) field.get(timeBarView);
                        if (paint != null) {
                            paint.setPathEffect(pathEffect);
                            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                timeBarView.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                    }
                });
                timeBarView.setClipToOutline(true);
            }
            timeBarView.invalidate();
        } catch (Exception e) {
            Log.e("VideoPlayer", "Failed to round timebar corners", e);
        }
    }

    private void checkAndUpdateOrientation() {
        try {
            setupFullscreen();
            boolean isPortrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            
            // Always force close landscape side panels and overlays on orientation change
            if (menuPanelContainer != null) {
                menuPanelContainer.forceClose();
                menuPanelContainer.setVisibility(View.GONE);
            }
            if (commentsPanelContainer != null) {
                commentsPanelContainer.forceClose();
                commentsPanelContainer.setVisibility(View.GONE);
            }
            if (menuOverlay != null) {
                menuOverlay.setVisibility(View.GONE);
                menuOverlay.setAlpha(0f);
            }
            if (playerAnimeInfoController != null) {
                playerAnimeInfoController.hidePlaceholderAnimated();
            }
            if (slidingMenuPanel != null) {
                slidingMenuPanel.setVisibility(View.GONE);
            }
            if (commentsPanel != null) {
                commentsPanel.setVisibility(View.GONE);
            }
            if (playerCommentsController != null) {
                playerCommentsController.forceHideCommentsPanel();
            }
            if (playersManager != null) {
                playersManager.forceHideMenu();
            }
            if (episodesManager != null) {
                episodesManager.hideEpisodesMenu();
            }
            RelatedTitlesManager rtm = playerRelatedTitlesController != null ? playerRelatedTitlesController.getRelatedTitlesManager() : null;
            if (rtm != null) {
                rtm.hideRelatedTitles();
            }
            if (gesturesManager != null) {
                gesturesManager.hideAllGesturesUI();
            }
            if (playerDialogsController != null) {
                playerDialogsController.dismissSettingsBottomSheet();
            }

            applyPlayerSidePanelTransform(0f);
            if (playerContainer != null) {
                playerContainer.setPivotX(0f);
                playerContainer.setPivotY(0f);
                playerContainer.setScaleX(1f);
                playerContainer.setScaleY(1f);
                playerContainer.setTranslationX(0f);
                playerContainer.setTranslationY(0f);
            }

            int sbHeight = isPortrait ? getStatusBarHeight() : 0;

            View mainVideoLayout = findViewById(R.id.mainVideoLayout);
            if (mainVideoLayout != null) {
                mainVideoLayout.setPadding(0, sbHeight, 0, 0);
                if (mainVideoLayout instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) mainVideoLayout).setClipChildren(false);
                    ((android.view.ViewGroup) mainVideoLayout).setClipToPadding(false);
                }
            }

            // 1. Adjust player container height and show/hide bottom empty area
            View portraitBottomContainer = findViewById(R.id.portraitBottomContainer);
            if (playerContainer != null) {
                playerContainer.setPadding(0, 0, 0, 0);
                playerContainer.setClipChildren(false);
                playerContainer.setClipToPadding(false);
                ViewCompat.setElevation(playerContainer, isPortrait ? 10f : 0f);

                android.view.ViewGroup.LayoutParams rawParams = playerContainer.getLayoutParams();
                if (rawParams != null) {
                    if (rawParams instanceof android.view.ViewGroup.MarginLayoutParams) {
                        android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) rawParams;
                        mlp.topMargin = 0;
                        mlp.leftMargin = 0;
                        mlp.rightMargin = 0;
                        mlp.bottomMargin = 0;
                    }
                    if (isPortrait) {
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int portraitWidth = Math.min(dm.widthPixels, dm.heightPixels);
                        rawParams.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                        rawParams.height = portraitWidth * 9 / 16;
                        if (rawParams instanceof android.widget.LinearLayout.LayoutParams) {
                            ((android.widget.LinearLayout.LayoutParams) rawParams).weight = 0;
                        }
                        if (portraitBottomContainer != null) {
                            portraitBottomContainer.setVisibility(View.VISIBLE);
                            ViewCompat.setElevation(portraitBottomContainer, 0f);
                            updatePortraitVoiceoverPlayerUI();
                            updatePortraitHeaderTitlesUI();
                            if (playerRelatedTitlesController != null && currentAnimeId != null && !isOfflineMode) {
                                playerRelatedTitlesController.loadRelatedTitles(currentAnimeId, animeUrl);
                            }
                            if (playerCommentsController != null && !isOfflineMode) {
                                playerCommentsController.loadCommentsForPortraitIfNeeded();
                            }
                        }
                    } else {
                        rawParams.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                        rawParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                        if (rawParams instanceof android.widget.LinearLayout.LayoutParams) {
                            ((android.widget.LinearLayout.LayoutParams) rawParams).weight = 0;
                        }
                        if (portraitBottomContainer != null) {
                            portraitBottomContainer.setVisibility(View.GONE);
                        }
                    }
                    playerContainer.setLayoutParams(rawParams);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        playerContainer.invalidateOutline();
                    }
                }
            }

            if (playerView != null) {
                playerView.setClipChildren(false);
                playerView.setClipToPadding(false);
            }

            if (controllerView == null) {
                return;
            }

            if (controllerView instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) controllerView).setClipChildren(false);
                ((android.view.ViewGroup) controllerView).setClipToPadding(false);
            }

            // 2. Hide top anime info in portrait, keep close, settings, pip buttons visible
            View ibClosePlayer = controllerView.findViewById(R.id.ibClosePlayer);
            if (ibClosePlayer != null) ibClosePlayer.setVisibility(View.VISIBLE);

            View settingsButtonContainer = controllerView.findViewById(R.id.settingsButtonContainer);
            if (settingsButtonContainer != null) settingsButtonContainer.setVisibility(View.VISIBLE);
            if (settingsButton != null) settingsButton.setVisibility(View.VISIBLE);

            if (pipButton == null) pipButton = controllerView.findViewById(R.id.pipButton);
            if (pipButton != null) pipButton.setVisibility(View.VISIBLE);

            View animeInfoContainer = controllerView.findViewById(R.id.animeInfoContainer);
            if (animeInfoContainer != null) {
                animeInfoContainer.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
            }

            View topBarContainer = controllerView.findViewById(R.id.topBarContainer);
            if (topBarContainer != null) {
                android.view.ViewGroup.LayoutParams lp = topBarContainer.getLayoutParams();
                if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                    android.widget.FrameLayout.LayoutParams topParams = (android.widget.FrameLayout.LayoutParams) lp;
                    float density = getResources().getDisplayMetrics().density;
                    if (isPortrait) {
                        topParams.topMargin = (int) (10 * density);
                        topParams.leftMargin = (int) (10 * density);
                        topParams.rightMargin = (int) (10 * density);
                    } else {
                        topParams.topMargin = (int) (16 * density);
                        topParams.leftMargin = (int) (24 * density);
                        topParams.rightMargin = (int) (24 * density);
                    }
                    topBarContainer.setLayoutParams(topParams);
                }
            }

            // 3. Show center episode & play controls in both portrait and landscape
            View centerControlsContainer = controllerView.findViewById(R.id.centerControlsContainer);
            if (centerControlsContainer != null) {
                centerControlsContainer.setVisibility(View.VISIBLE);
            }

            // 4. Hide / show bottom buttons in portrait
            View skipForwardButton = controllerView.findViewById(R.id.skipForwardButton);
            View bookmarkBtn = controllerView.findViewById(R.id.bookmarkButton);
            View commentsBtn = controllerView.findViewById(R.id.commentsButton);
            View episodesMenuBtn = controllerView.findViewById(R.id.episodesMenuButton);
            View menuToggleBtn = controllerView.findViewById(R.id.menuToggleButton);
            View menuToggleFullscreenBtn = controllerView.findViewById(R.id.menuToggleFullscreen);

            if (skipForwardButton != null) skipForwardButton.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
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
                if (isOfflineMode) {
                    menuToggleBtn.setEnabled(false);
                    menuToggleBtn.setAlpha(0.3f);
                }
            }
            if (isOfflineMode) {
                if (portraitVoiceoverPlayerButton != null) {
                    portraitVoiceoverPlayerButton.setEnabled(false);
                    portraitVoiceoverPlayerButton.setClickable(false);
                    portraitVoiceoverPlayerButton.setFocusable(false);
                    portraitVoiceoverPlayerButton.setAlpha(0.6f);
                }
                if (ivPortraitVoiceoverChevron != null) {
                    ivPortraitVoiceoverChevron.setVisibility(View.GONE);
                }
                if (portraitBookmarkButton != null) {
                    portraitBookmarkButton.setEnabled(true);
                    portraitBookmarkButton.setClickable(true);
                    portraitBookmarkButton.setFocusable(true);
                    portraitBookmarkButton.setAlpha(1.0f);
                }
                if (portraitDownloadButton != null) {
                    portraitDownloadButton.setEnabled(false);
                    portraitDownloadButton.setClickable(false);
                    portraitDownloadButton.setFocusable(false);
                    portraitDownloadButton.setAlpha(0.35f);
                }
            }
            if (menuToggleFullscreenBtn != null) {
                menuToggleFullscreenBtn.setVisibility(View.VISIBLE);
                if (menuToggleFullscreenBtn instanceof ImageButton) {
                    ((ImageButton) menuToggleFullscreenBtn).setImageResource(isPortrait ? R.drawable.ic_fullscreen : R.drawable.ic_fullscreen_exit);
                }
            }
            View landscapeTargetView = controllerView.findViewById(R.id.landscapeEpisodesContainer);
            if (landscapeTargetView == null) {
                landscapeTargetView = episodesHorizontalRecyclerView;
            }

            if (landscapeTargetView != null) {
                if (isPortrait) {
                    landscapeTargetView.setVisibility(View.GONE);
                } else {
                    if (episodesManager != null && episodesManager.isEpisodesMenuVisible()) {
                        landscapeTargetView.setVisibility(View.VISIBLE);
                        if (episodesHorizontalRecyclerView != null) {
                            episodesHorizontalRecyclerView.setVisibility(View.VISIBLE);
                        }
                    } else {
                        landscapeTargetView.setVisibility(View.INVISIBLE);
                    }
                }
            }

            // 5. Adjust timebar position & margin
            android.widget.LinearLayout playersControlBarView = controllerView.findViewById(R.id.playersControlBar);
            View exoProgress = controllerView.findViewById(R.id.timeBarContainer);
            if (exoProgress == null) {
                exoProgress = controllerView.findViewById(R.id.exo_progress);
            }
            View timeAndControlsContainer = controllerView.findViewById(R.id.timeAndControlsContainer);
            View overlayBadgesContainer = controllerView.findViewById(R.id.overlayBadgesContainer);

            if (playersControlBarView != null && exoProgress != null && timeAndControlsContainer != null) {
                playersControlBarView.setClipChildren(false);
                playersControlBarView.setClipToPadding(false);

                if (overlayBadgesContainer != null && overlayBadgesContainer.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) overlayBadgesContainer.getParent()).removeView(overlayBadgesContainer);
                }
                if (exoProgress.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) exoProgress.getParent()).removeView(exoProgress);
                }
                if (timeAndControlsContainer.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) timeAndControlsContainer.getParent()).removeView(timeAndControlsContainer);
                }
                if (landscapeTargetView != null && landscapeTargetView.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) landscapeTargetView.getParent()).removeView(landscapeTargetView);
                }

                if (overlayBadgesContainer != null) {
                    android.view.ViewGroup.LayoutParams obLp = overlayBadgesContainer.getLayoutParams();
                    android.widget.LinearLayout.LayoutParams obParams = (obLp instanceof android.widget.LinearLayout.LayoutParams) ?
                            (android.widget.LinearLayout.LayoutParams) obLp :
                            new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    float d = getResources().getDisplayMetrics().density;
                    obParams.leftMargin = (int) ((isPortrait ? 16 : 24) * d);
                    obParams.rightMargin = (int) ((isPortrait ? 16 : 24) * d);
                    obParams.bottomMargin = (int) (6 * d);
                    overlayBadgesContainer.setLayoutParams(obParams);
                }

                android.view.ViewGroup.LayoutParams tcLp = timeAndControlsContainer.getLayoutParams();
                android.widget.LinearLayout.LayoutParams timeAndControlsParams = (tcLp instanceof android.widget.LinearLayout.LayoutParams) ?
                        (android.widget.LinearLayout.LayoutParams) tcLp :
                        new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

                android.view.ViewGroup.LayoutParams exLp = exoProgress.getLayoutParams();
                android.widget.LinearLayout.LayoutParams progressParams = (exLp instanceof android.widget.LinearLayout.LayoutParams) ?
                        (android.widget.LinearLayout.LayoutParams) exLp :
                        new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

                float density = getResources().getDisplayMetrics().density;

                if (isPortrait) {
                    // Reset translationY to 0 so timebar and timecode are fully visible
                    playersControlBarView.setTranslationY(0f);

                    // Align timeAndControls (16dp) with exoProgress (8dp container margin + 8dp internal padding = 16dp track)
                    timeAndControlsParams.topMargin = (int) (2 * density);
                    timeAndControlsParams.bottomMargin = (int) (2 * density);
                    timeAndControlsParams.leftMargin = (int) (16 * density);
                    timeAndControlsParams.rightMargin = (int) (16 * density);

                    progressParams.topMargin = (int) (2 * density);
                    progressParams.bottomMargin = (int) (12 * density);
                    progressParams.leftMargin = (int) (8 * density);
                    progressParams.rightMargin = (int) (8 * density);

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
                } else {
                    // Landscape: timeAndControlsContainer on top, exoProgress at bottom (same order as portrait)
                    if (episodesManager != null && episodesManager.isEpisodesMenuVisible()) {
                        playersControlBarView.setTranslationY(0f);
                    } else {
                        playersControlBarView.setTranslationY(60f * density);
                    }

                    // Align timeAndControls (24dp) with exoProgress (16dp container margin + 8dp internal padding = 24dp track)
                    timeAndControlsParams.topMargin = (int) (2 * density);
                    timeAndControlsParams.bottomMargin = (int) (2 * density);
                    timeAndControlsParams.leftMargin = (int) (24 * density);
                    timeAndControlsParams.rightMargin = (int) (24 * density);

                    progressParams.topMargin = (int) (2 * density);
                    progressParams.bottomMargin = (int) (14 * density);
                    progressParams.leftMargin = (int) (16 * density);
                    progressParams.rightMargin = (int) (16 * density);

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
                }

                playersControlBarView.setVisibility(View.VISIBLE);
                exoProgress.setVisibility(View.VISIBLE);
                timeAndControlsContainer.setVisibility(View.VISIBLE);

                View timeCodeContainer = controllerView.findViewById(R.id.timeCodeContainer);
                if (timeCodeContainer != null) timeCodeContainer.setVisibility(View.VISIBLE);
            }

            // 6. Block drag events on side panels, top curtain, episode carousel (Task 3)
            if (menuPanelContainer != null) menuPanelContainer.setDragEnabled(!isPortrait);
            if (commentsPanelContainer != null) commentsPanelContainer.setDragEnabled(!isPortrait);
            if (gesturesManager != null) gesturesManager.setPortraitMode(isPortrait);
            if (verticalGesturesManager != null) verticalGesturesManager.setPortraitMode(isPortrait);

            if (DownloadService.isRunning()) {
                showDownloadProgress(DownloadService.getCurrentTaskPercent());
            } else {
                resetDownloadUi();
            }
        } catch (Exception e) {
            Log.e("VideoPlayer", "Error in checkAndUpdateOrientation", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (playerOrientationController != null) {
            playerOrientationController.enable();
        }

        setupFullscreen();
        startBufferingMonitoring();

        // Восстанавливаем плашку прогресса при возврате в плеер
        if (DownloadService.isRunning()) {
            showDownloadProgress(DownloadService.getCurrentTaskPercent());
        } else {
            resetDownloadUi();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopViewProgressTracking();
        stopBufferingMonitoring();
        
        if (playerOrientationController != null) {
            playerOrientationController.cleanup();
            playerOrientationController = null;
        }
        if (playerNextEpisodeController != null) {
            playerNextEpisodeController.cleanup();
            playerNextEpisodeController = null;
        }

        // Автоматически сохраняем закладку при закрытии плеера
        autoSaveBookmark();
        
        // Clear screen keep flag
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (playerDialogsController != null) {
            playerDialogsController.dismissErrorDialog();
        }

        if (backgroundPlayer != null) {
            try {
                backgroundPlayer.stop();
                backgroundPlayer.release();
            } catch (Exception ignored) {}
            backgroundPlayer = null;
        }
        if (player != null) {
            player.release();
            player = null;
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

        if (playerDialogsController != null) {
            playerDialogsController.dismissErrorDialog();
            playerDialogsController.dismissSettingsBottomSheet();
        }
    }

    private String getAnimePageUrl() {
        if (isOfflineMode) {
            return null;
        }

        String rawUrl = animeUrl;
        if (rawUrl == null || rawUrl.isEmpty()) {
            if (getIntent() != null) {
                rawUrl = getIntent().getStringExtra(EXTRA_ANIME_URL);
                if (rawUrl == null || rawUrl.isEmpty()) {
                    rawUrl = getIntent().getStringExtra("anime_url");
                }
            }
        }

        String slug = null;
        if (rawUrl != null && !rawUrl.isEmpty()) {
            slug = ApiService.extractMediaSlugFromUrl(rawUrl);
            if (slug == null && apiService != null) {
                slug = apiService.extractAnimeSlug(rawUrl);
            }
        }

        if (slug == null && currentAnimeInfo != null && currentAnimeInfo.getData() != null) {
            String slugUrl = currentAnimeInfo.getData().getSlug_url();
            if (slugUrl != null && !slugUrl.isEmpty()) {
                slug = ApiService.extractMediaSlugFromUrl(slugUrl);
                if (slug == null && apiService != null) {
                    slug = apiService.extractAnimeSlug(slugUrl);
                }
                if (slug == null) {
                    slug = slugUrl;
                }
            }
        }

        if (slug == null && currentAnimeId != null && !currentAnimeId.isEmpty()) {
            slug = currentAnimeId;
        }

        if (slug == null) {
            if (rawUrl != null && !rawUrl.isEmpty() && !rawUrl.contains("api.cdnlibs.org")) {
                slug = rawUrl;
            } else {
                return null;
            }
        }

        if (slug.contains("?")) {
            slug = slug.substring(0, slug.indexOf("?"));
        }
        if (slug.contains("#")) {
            slug = slug.substring(0, slug.indexOf("#"));
        }
        if (slug.endsWith("/watch")) {
            slug = slug.substring(0, slug.length() - "/watch".length());
        } else if (slug.endsWith("/watch/")) {
            slug = slug.substring(0, slug.length() - "/watch/".length());
        }
        if (slug.endsWith("/")) {
            slug = slug.substring(0, slug.length() - 1);
        }

        if (slug.startsWith("http://") || slug.startsWith("https://")) {
            return slug;
        }

        String baseSiteUrl = databaseManager != null ? databaseManager.getSiteUrl() : null;
        if (baseSiteUrl == null || baseSiteUrl.isEmpty()) {
            baseSiteUrl = "https://animelib.org";
        }
        if (!baseSiteUrl.startsWith("http://") && !baseSiteUrl.startsWith("https://")) {
            baseSiteUrl = "https://" + baseSiteUrl;
        }
        if (baseSiteUrl.endsWith("/")) {
            baseSiteUrl = baseSiteUrl.substring(0, baseSiteUrl.length() - 1);
        }

        if (slug.startsWith("/")) {
            return baseSiteUrl + slug;
        }

        return baseSiteUrl + "/ru/anime/" + slug;
    }

    private void navigateToAnimePageInWebView() {
        if (isOfflineMode || isNavigatingToAnimePage) {
            return;
        }
        isNavigatingToAnimePage = true;
        try {
            String animePageUrl = getAnimePageUrl();
            if (animePageUrl != null && !animePageUrl.isEmpty()) {
                Log.d("VideoPlayer", "Navigating to anime page in WebView on player exit: " + animePageUrl);
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("EXTRA_OPEN_URL", animePageUrl);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }
        } catch (Exception e) {
            Log.e("VideoPlayer", "Error navigating to anime page in WebView on exit", e);
        }
    }

    @Override
    public void finish() {
        if (!isOfflineMode && !isNavigatingToAnimePage) {
            navigateToAnimePageInWebView();
        }
        super.finish();
    }

    @Override
    public void onBackPressed() {
        if (player != null && player.isPlaying() && playerPipController != null && !playerPipController.isInPictureInPictureMode()) {
            autoSaveBookmark();
            playerPipController.enterPictureInPictureMode();
            return;
        }
        autoSaveBookmark();

        super.onBackPressed();
    }
}


