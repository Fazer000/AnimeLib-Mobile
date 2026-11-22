package com.example.animelib.controllers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.SingleSampleMediaSource;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.api.FastDns;
import com.example.animelib.api.KodikLinksExtractor;
import com.example.animelib.managers.AmbientLightManager;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.util.AutoQualityHelper;
import com.example.animelib.util.MediaCacheManager;
import com.example.animelib.util.SurroundRenderersFactory;
import com.example.animelib.util.VpnNetworkHelper;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;

@OptIn(markerClass = UnstableApi.class)
public class PlayerPlaybackController {

    private static final String TAG = "PlayerPlaybackController";
    private static final long BUFFERING_TIMEOUT_MS = 12_000L;
    private static final String STREAM_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36";

    private static final ConnectionPool SHARED_STREAM_POOL = new ConnectionPool(12, 3, TimeUnit.MINUTES);

    public interface PlaybackCallback {
        Context getPlayerContext();
        PlayerAudioController getPlayerAudioController();
        AmbientLightManager getAmbientLightManager();
        GesturesManager getGesturesManager();
        TimecodeManager getTimecodeManager();
        HttpDataSource.Factory getHttpDataSourceFactory();
        ApiService getApiService();
        void onFirstFrameRendered();
        void onPlaybackStateChanged(int state, boolean playWhenReady);
        void onPlayerError(PlaybackException error);
        void safeRunOnUiThread(Runnable runnable);
        default void onVideoSizeChanged(@NonNull VideoSize videoSize) {}
        default void onPositionDiscontinuity(int reason) {}
        default void onIsLoadingChanged(boolean isLoading) {}
        default void onIsPlayingChanged(boolean isPlaying) {}
        default void onEvents() {}
        default void onPlayerAttached(ExoPlayer player) {}
        default void onPrepareBackgroundPlayer(@NonNull ExoPlayer backgroundPlayer) {}
        default void onSeamlessSwitchTimedOutOrFailed() {}
        default void onSeamlessSwitchCompleted(String newQuality, String newVideoUrl) {}
        default boolean isActivityFinishingOrDestroyed() { return false; }
    }

    private final Context context;
    private final PlayerView playerView;
    private final HttpDataSource.Factory httpDataSourceFactory;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable bufferingWatchdogRunnable = null;
    private OkHttpClient streamingOkHttpClient;
    private OkHttpClient directNonVpnStreamingClient;
    private ExoPlayer player;
    private ExoPlayer backgroundPlayer;
    private boolean isSeamlessSwitching = false;
    private Handler seamlessTimeoutHandler = null;
    private Runnable seamlessTimeoutRunnable = null;
    private int currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    private float lastValidAspectRatio = 16f / 9f;
    private PlaybackCallback callback;
    private boolean isFirstFrameRendered = false;
    private long lastKnownValidPositionMs = 0L;
    private long pendingSeamlessStartPositionMs = 0L;

    public PlayerPlaybackController(Context context, PlayerView playerView, HttpDataSource.Factory httpDataSourceFactory) {
        this.context = context;
        this.playerView = playerView;
        this.httpDataSourceFactory = httpDataSourceFactory;
        MediaCacheManager.warmUpAsync(context);
    }

    public void setCallback(PlaybackCallback callback) {
        this.callback = callback;
    }

    public ExoPlayer getPlayer() {
        return player;
    }

    public boolean isSeamlessSwitching() {
        return isSeamlessSwitching;
    }

    public long getLastKnownValidPositionMs() {
        return lastKnownValidPositionMs;
    }

    public long resolveCurrentPlaybackPositionMs() {
        if (player != null) {
            long live = player.getCurrentPosition();
            if (live > 0) {
                lastKnownValidPositionMs = live;
                return live;
            }
        }
        return Math.max(pendingSeamlessStartPositionMs, lastKnownValidPositionMs);
    }

    public void recordValidPositionMs(long positionMs) {
        if (positionMs > 0) {
            lastKnownValidPositionMs = positionMs;
        }
    }

    public void resetLastKnownValidPosition() {
        lastKnownValidPositionMs = 0L;
        pendingSeamlessStartPositionMs = 0L;
    }

    private void captureValidPositionFromPlayer() {
        if (player != null) {
            long pos = player.getCurrentPosition();
            if (pos > 0) {
                lastKnownValidPositionMs = pos;
            }
        }
    }

    public int getCurrentResizeMode() {
        return currentResizeMode;
    }

    public void setVideoResizeMode(int resizeMode) {
        this.currentResizeMode = resizeMode;
        if (playerView != null) {
            playerView.setResizeMode(resizeMode);
        }
        if (player != null) {
            if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            } else {
                player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            }
        }
    }

    public void preservePlayerAspectRatio() {
        if (playerView != null) {
            AspectRatioFrameLayout contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
            if (contentFrame != null && lastValidAspectRatio > 0f) {
                contentFrame.setAspectRatio(lastValidAspectRatio);
            }
        }
    }

    public boolean isFirstFrameRendered() {
        return isFirstFrameRendered;
    }

    private synchronized OkHttpClient getOrCreateStreamingOkHttpClient() {
        if (streamingOkHttpClient == null) {
            streamingOkHttpClient = new OkHttpClient.Builder()
                    .connectionPool(SHARED_STREAM_POOL)
                    .dispatcher(FastDns.createDispatcher(64, 16))
                    .dns(FastDns.INSTANCE)
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .writeTimeout(8, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .addInterceptor(chain -> {
                        Request original = chain.request();
                        String urlStr = original.url().toString();
                        Request.Builder builder = original.newBuilder();

                        if (ApiService.isKodikStreamUrl(urlStr)) {
                            builder.removeHeader("Authorization");
                            for (Map.Entry<String, String> entry : ApiService.buildKodikStreamHeaders().entrySet()) {
                                builder.header(entry.getKey(), entry.getValue());
                            }
                        } else if (callback != null && callback.getApiService() != null) {
                            Map<String, String> animeLibHeaders = callback.getApiService().getVideoRequestHeaders();
                            if (animeLibHeaders != null) {
                                for (Map.Entry<String, String> entry : animeLibHeaders.entrySet()) {
                                    if (original.header(entry.getKey()) == null) {
                                        builder.header(entry.getKey(), entry.getValue());
                                    }
                                }
                            }
                        }
                        return chain.proceed(builder.build());
                    })
                    .build();
        }
        return streamingOkHttpClient;
    }

    private synchronized OkHttpClient getOrCreateDirectNonVpnStreamingClient() {
        if (directNonVpnStreamingClient == null) {
            directNonVpnStreamingClient = getOrCreateStreamingOkHttpClient().newBuilder()
                    .socketFactory(VpnNetworkHelper.createPhysicalNetworkSocketFactory())
                    .dns(VpnNetworkHelper.createPhysicalNetworkDns(FastDns.INSTANCE))
                    .build();
        }
        return directNonVpnStreamingClient;
    }

    public OkHttpClient getStreamingOkHttpClientForUrl(String targetUrl) {
        boolean isKodik = ApiService.isKodikStreamUrl(targetUrl);
        return (isKodik && KodikLinksExtractor.didLastExtractionUseDirectNetwork())
                ? getOrCreateDirectNonVpnStreamingClient()
                : getOrCreateStreamingOkHttpClient();
    }

    public HttpDataSource.Factory buildStreamHttpDataSourceFactory(String targetUrl) {
        boolean isKodik = ApiService.isKodikStreamUrl(targetUrl);
        OkHttpClient activeClient = getStreamingOkHttpClientForUrl(targetUrl);
        OkHttpDataSource.Factory okFactory = new OkHttpDataSource.Factory(activeClient)
                .setUserAgent(STREAM_USER_AGENT);
        if (isKodik) {
            okFactory.setDefaultRequestProperties(ApiService.buildKodikStreamHeaders());
        } else if (callback != null && callback.getApiService() != null) {
            Map<String, String> headers = callback.getApiService().getVideoRequestHeaders();
            if (headers != null) {
                okFactory.setDefaultRequestProperties(headers);
            }
        }
        return okFactory;
    }

    private LoadErrorHandlingPolicy createStreamErrorHandlingPolicy() {
        return new DefaultLoadErrorHandlingPolicy(4) {
            @Override
            public long getRetryDelayMsFor(@NonNull LoadErrorInfo loadErrorInfo) {
                Throwable ex = loadErrorInfo.exception;
                if (ex instanceof HttpDataSource.InvalidResponseCodeException) {
                    int code = ((HttpDataSource.InvalidResponseCodeException) ex).responseCode;
                    if (code == 403 || code == 404 || code == 410) {
                        return C.TIME_UNSET;
                    }
                }
                if (loadErrorInfo.errorCount > 4) {
                    return C.TIME_UNSET;
                }
                return Math.min(350L * loadErrorInfo.errorCount, 1500L);
            }

            @Override
            public int getMinimumLoadableRetryCount(int dataType) {
                return 4;
            }
        };
    }

    static boolean isHlsStreamUrl(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) return false;
        String lower = videoUrl.toLowerCase(Locale.ROOT);
        return lower.contains(".m3u8")
                || lower.contains(":hls:")
                || (ApiService.isKodikStreamUrl(videoUrl) && !lower.endsWith(".mp4"));
    }

    private static boolean isLocalOrContentUri(String videoUrl) {
        if (videoUrl == null) return false;
        return videoUrl.startsWith("file://")
                || videoUrl.startsWith("content://")
                || videoUrl.startsWith("/");
    }

    public MediaSource buildMediaSourceForUrl(Context playerContext, String videoUrl, MediaItem itemToPlay) {
        HttpDataSource.Factory httpFactory = buildStreamHttpDataSourceFactory(videoUrl);
        DataSource.Factory baseDsFactory = new DefaultDataSource.Factory(playerContext, httpFactory);
        LoadErrorHandlingPolicy errorPolicy = createStreamErrorHandlingPolicy();

        boolean isLocal = isLocalOrContentUri(videoUrl);
        boolean isHls = !isLocal && isHlsStreamUrl(videoUrl);

        List<MediaItem.SubtitleConfiguration> subtitleConfigs =
                (itemToPlay.localConfiguration != null && itemToPlay.localConfiguration.subtitleConfigurations != null)
                        ? itemToPlay.localConfiguration.subtitleConfigurations
                        : Collections.emptyList();
        boolean hasExternalSubtitles = !subtitleConfigs.isEmpty();

        if (isHls) {
            MediaItem.Builder videoItemBuilder = itemToPlay.buildUpon()
                    .setSubtitleConfigurations(Collections.emptyList());
            if (itemToPlay.localConfiguration == null || itemToPlay.localConfiguration.mimeType == null) {
                videoItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            MediaSource hlsSource = new HlsMediaSource.Factory(baseDsFactory)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(errorPolicy)
                    .createMediaSource(videoItemBuilder.build());

            if (!hasExternalSubtitles) {
                return hlsSource;
            }

            List<MediaSource> merged = new ArrayList<>(1 + subtitleConfigs.size());
            merged.add(hlsSource);
            SingleSampleMediaSource.Factory subFactory = new SingleSampleMediaSource.Factory(baseDsFactory)
                    .setTreatLoadErrorsAsEndOfStream(true)
                    .setLoadErrorHandlingPolicy(errorPolicy);
            for (MediaItem.SubtitleConfiguration subConfig : subtitleConfigs) {
                if (subConfig != null) {
                    merged.add(subFactory.createMediaSource(subConfig, C.TIME_UNSET));
                }
            }
            return new MergingMediaSource(merged.toArray(new MediaSource[0]));
        }

        DataSource.Factory effectiveDsFactory = isLocal
                ? baseDsFactory
                : MediaCacheManager.createNonBlockingCacheDataSourceFactory(playerContext, baseDsFactory);

        return new DefaultMediaSourceFactory(effectiveDsFactory)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .createMediaSource(itemToPlay);
    }

    public ExoPlayer initializePlayer(String videoUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady) {
        return initializePlayer(videoUrl, mediaItem, resizeMode, playWhenReady, 0);
    }

    public ExoPlayer initializePlayer(String videoUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady, long startPosition) {
        if (context == null || videoUrl == null) return player;

        this.currentResizeMode = resizeMode;
        Context playerContext = callback != null ? callback.getPlayerContext() : context;
        if (playerContext == null) playerContext = context;

        isFirstFrameRendered = false;

        PlayerAudioController audioController = callback != null ? callback.getPlayerAudioController() : null;

        if (player == null) {
            SurroundRenderersFactory rf = new SurroundRenderersFactory(
                    playerContext,
                    audioController != null ? audioController.getSurroundAudioProcessor() : null);

            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 500, 1_000)
                    .setBackBuffer(25_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();

            ExoPlayer.Builder builder = new ExoPlayer.Builder(playerContext, rf)
                    .setLoadControl(loadControl)
                    .setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(playerContext))
                    .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                    .setSeekBackIncrementMs(10000)
                    .setSeekForwardIncrementMs(10000);

            player = builder.build();
            if (playerView != null) {
                playerView.setPlayer(player);
            }
            setupPlayerListener();
        } else {
            player.stop();
            player.clearMediaItems();
        }
        setVideoResizeMode(resizeMode);

        MediaItem itemToPlay = mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl);
        MediaSource mediaSource = buildMediaSourceForUrl(playerContext, videoUrl, itemToPlay);

        long safeStart = Math.max(0L, startPosition);
        if (safeStart > 0) {
            lastKnownValidPositionMs = safeStart;
        }
        player.setMediaSource(mediaSource, safeStart);

        AmbientLightManager ambientLightManager = callback != null ? callback.getAmbientLightManager() : null;
        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(player, itemToPlay, videoUrl);
        }

        if (audioController != null) {
            audioController.attachPlayer(player);
        }

        GesturesManager gesturesManager = callback != null ? callback.getGesturesManager() : null;
        if (gesturesManager != null) {
            gesturesManager.updatePlayer(player);
        }

        TimecodeManager timecodeManager = callback != null ? callback.getTimecodeManager() : null;
        if (timecodeManager != null && playerView != null) {
            View controllerView = playerView.findViewById(R.id.exo_controller);
            MaterialButton skipButton = controllerView != null ? controllerView.findViewById(R.id.skipSegmentButton) : null;
            timecodeManager.initializeViews(player, playerView, skipButton);
        }

        if (playWhenReady) {
            startBufferingWatchdog();
        }
        player.setPlayWhenReady(playWhenReady);
        player.prepare();

        if (callback != null) {
            callback.onPlayerAttached(player);
        }

        return player;
    }

    public ExoPlayer initializeOfflinePlayer(String videoUrl, MediaItem mediaItem, boolean playWhenReady) {
        if (context == null || videoUrl == null) return player;

        Context playerContext = callback != null ? callback.getPlayerContext() : context;
        if (playerContext == null) playerContext = context;

        isFirstFrameRendered = false;
        PlayerAudioController audioController = callback != null ? callback.getPlayerAudioController() : null;

        if (player == null) {
            SurroundRenderersFactory rf = new SurroundRenderersFactory(
                    playerContext,
                    audioController != null ? audioController.getSurroundAudioProcessor() : null);
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 1_500, 2_500)
                    .setBackBuffer(10_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(15000)
                    .setAllowCrossProtocolRedirects(true);
            if (callback != null && callback.getApiService() != null) {
                httpFactory.setDefaultRequestProperties(callback.getApiService().getVideoRequestHeaders());
            }
            DataSource.Factory dsFactory = new DefaultDataSource.Factory(playerContext, httpFactory);
            player = new ExoPlayer.Builder(playerContext, rf)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory))
                    .setLoadControl(loadControl)
                    .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                    .setSeekBackIncrementMs(10000)
                    .setSeekForwardIncrementMs(10000)
                    .build();
            if (playerView != null) {
                playerView.setPlayer(player);
            }
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

        AmbientLightManager ambientLightManager = callback != null ? callback.getAmbientLightManager() : null;
        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(player);
        }

        if (audioController != null) {
            audioController.attachPlayer(player);
        }

        if (playerView != null) {
            playerView.setUseController(true);
        }

        GesturesManager gesturesManager = callback != null ? callback.getGesturesManager() : null;
        if (gesturesManager != null) {
            gesturesManager.updatePlayer(player);
        }

        TimecodeManager timecodeManager = callback != null ? callback.getTimecodeManager() : null;
        if (timecodeManager != null && playerView != null) {
            View controllerView = playerView.findViewById(R.id.exo_controller);
            MaterialButton skipButton = controllerView != null ? controllerView.findViewById(R.id.skipSegmentButton) : null;
            timecodeManager.initializeViews(player, playerView, skipButton);
        }

        MediaItem itemToPlay = mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl);
        player.setMediaItem(itemToPlay, 0L);
        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(player, itemToPlay, videoUrl);
        }
        player.prepare();

        if (callback != null) {
            callback.onPlayerAttached(player);
        }

        player.setPlayWhenReady(playWhenReady);
        return player;
    }

    public ExoPlayer switchMediaSource(String videoUrl, MediaItem mediaItem, long startPosition, boolean playWhenReady) {
        cleanupBackgroundPlayer();
        if (player == null) {
            return initializePlayer(videoUrl, mediaItem, currentResizeMode, playWhenReady, startPosition);
        }

        Context playerContext = callback != null ? callback.getPlayerContext() : context;
        if (playerContext == null) playerContext = context;

        isFirstFrameRendered = false;

        MediaItem itemToPlay = mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl);
        MediaSource mediaSource = buildMediaSourceForUrl(playerContext, videoUrl, itemToPlay);

        AmbientLightManager ambientLightManager = callback != null ? callback.getAmbientLightManager() : null;
        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(player, itemToPlay, videoUrl);
        }

        long safeStart = Math.max(0L, startPosition);
        if (safeStart > 0) {
            lastKnownValidPositionMs = safeStart;
        }
        player.setMediaSource(mediaSource, safeStart);

        if (playWhenReady) {
            startBufferingWatchdog();
        } else {
            cancelBufferingWatchdog();
        }

        player.setPlayWhenReady(playWhenReady);
        player.prepare();

        if (callback != null) {
            callback.onPlayerAttached(player);
        }

        return player;
    }

    public ExoPlayer initializeHlsPlayer(String hlsUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady) {
        return initializePlayer(hlsUrl, mediaItem, resizeMode, playWhenReady, 0);
    }

    public ExoPlayer initializeHlsPlayer(String hlsUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady, long startPosition) {
        return initializePlayer(hlsUrl, mediaItem, resizeMode, playWhenReady, startPosition);
    }

    public void cleanupBackgroundPlayer() {
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

    public void switchQualitySeamlessly(String newQuality, String oldQuality, String newVideoUrl, MediaItem mediaItem) {
        long currentPosition = resolveCurrentPlaybackPositionMs();
        float currentSpeed = player != null ? player.getPlaybackParameters().speed : 1.0f;
        boolean wasPlaying = player == null || player.getPlayWhenReady();

        cleanupBackgroundPlayer();
        isSeamlessSwitching = true;
        pendingSeamlessStartPositionMs = currentPosition;
        if (currentPosition > 0) {
            lastKnownValidPositionMs = currentPosition;
        }

        Context playerContext = callback != null ? callback.getPlayerContext() : context;
        if (playerContext == null) playerContext = context;

        DefaultRenderersFactory rf = new DefaultRenderersFactory(playerContext);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_500, 10_000, 500, 1_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        ExoPlayer.Builder builder = new ExoPlayer.Builder(playerContext, rf)
                .setLoadControl(loadControl)
                .setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(playerContext))
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000);

        backgroundPlayer = builder.build();
        backgroundPlayer.setVolume(0f);

        if (callback != null) {
            callback.onPrepareBackgroundPlayer(backgroundPlayer);
        }

        MediaItem itemToPlay = mediaItem != null ? mediaItem : MediaItem.fromUri(newVideoUrl);
        MediaSource mediaSource = buildMediaSourceForUrl(playerContext, newVideoUrl, itemToPlay);
        backgroundPlayer.setMediaSource(mediaSource, Math.max(0L, currentPosition));

        backgroundPlayer.setPlaybackSpeed(currentSpeed);
        backgroundPlayer.setPlayWhenReady(true);
        backgroundPlayer.prepare();

        seamlessTimeoutHandler = new Handler(Looper.getMainLooper());
        seamlessTimeoutRunnable = () -> {
            if (isSeamlessSwitching && backgroundPlayer != null) {
                Log.w(TAG, "Seamless quality switch timed out, falling back to restart");
                cleanupBackgroundPlayer();
                if (callback != null) {
                    callback.onSeamlessSwitchTimedOutOrFailed();
                }
            }
        };
        seamlessTimeoutHandler.postDelayed(seamlessTimeoutRunnable, 10000);

        backgroundPlayer.addListener(new Player.Listener() {
            private boolean switched = false;

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    lastValidAspectRatio = (float) (videoSize.width * videoSize.pixelWidthHeightRatio) / (float) videoSize.height;
                    preservePlayerAspectRatio();
                    if (callback != null) {
                        callback.onVideoSizeChanged(videoSize);
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && !switched) {
                    switched = true;
                    if (seamlessTimeoutHandler != null && seamlessTimeoutRunnable != null) {
                        seamlessTimeoutHandler.removeCallbacks(seamlessTimeoutRunnable);
                    }
                    Runnable performAction = () -> performSeamlessSwitch(newQuality, oldQuality, newVideoUrl, wasPlaying);
                    if (callback != null) {
                        callback.safeRunOnUiThread(performAction);
                    } else {
                        performAction.run();
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (seamlessTimeoutHandler != null && seamlessTimeoutRunnable != null) {
                    seamlessTimeoutHandler.removeCallbacks(seamlessTimeoutRunnable);
                }
                Runnable errorAction = () -> {
                    Log.e(TAG, "Failed background load for quality " + newQuality + ", falling back to restart", error);
                    cleanupBackgroundPlayer();
                    if (callback != null) {
                        callback.onSeamlessSwitchTimedOutOrFailed();
                    }
                };
                if (callback != null) {
                    callback.safeRunOnUiThread(errorAction);
                } else {
                    errorAction.run();
                }
            }
        });
    }

    private void performSeamlessSwitch(String newQuality, String oldQuality, String newVideoUrl, boolean wasPlaying) {
        if (backgroundPlayer == null) return;
        if (callback != null && callback.isActivityFinishingOrDestroyed()) return;

        Log.d(TAG, "Background player READY! Performing seamless switch to " + newQuality);

        ExoPlayer oldPlayer = player;
        long oldPos = oldPlayer != null ? oldPlayer.getCurrentPosition() : 0L;
        long bgPos = backgroundPlayer.getCurrentPosition();
        long fallbackPos = Math.max(pendingSeamlessStartPositionMs, lastKnownValidPositionMs);
        long livePosition = oldPos > 0 ? oldPos : (bgPos > 0 ? bgPos : fallbackPos);
        if (livePosition > 0) {
            lastKnownValidPositionMs = livePosition;
        }

        if (playerView != null) {
            playerView.setPlayer(backgroundPlayer);
            preservePlayerAspectRatio();
            setVideoResizeMode(currentResizeMode);
        }

        if (livePosition > 0 && Math.abs(livePosition - bgPos) > 1000) {
            backgroundPlayer.seekTo(livePosition);
        }
        backgroundPlayer.setVolume(1.0f);
        backgroundPlayer.setPlayWhenReady(wasPlaying);

        attachNewPlayer(backgroundPlayer);
        backgroundPlayer = null;
        isSeamlessSwitching = false;
        pendingSeamlessStartPositionMs = 0L;

        PlayerAudioController audioController = callback != null ? callback.getPlayerAudioController() : null;
        if (audioController != null) {
            audioController.attachPlayer(player);
        }
        GesturesManager gesturesManager = callback != null ? callback.getGesturesManager() : null;
        if (gesturesManager != null) {
            gesturesManager.updatePlayer(player);
        }
        AmbientLightManager ambientLightManager = callback != null ? callback.getAmbientLightManager() : null;
        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(player, MediaItem.fromUri(newVideoUrl), newVideoUrl);
        }

        if (oldPlayer != null && oldPlayer != player) {
            try {
                oldPlayer.stop();
                oldPlayer.release();
            } catch (Exception ignored) {}
        }

        if (callback != null) {
            callback.onSeamlessSwitchCompleted(newQuality, newVideoUrl);
        }
    }

    public void stopCurrentPlayback() {
        captureValidPositionFromPlayer();
        cancelBufferingWatchdog();
        cleanupBackgroundPlayer();
        if (player != null) {
            Log.d(TAG, "Stopping current playback");
            player.stop();
            player.clearMediaItems();
        }
    }

    private void startBufferingWatchdog() {
        cancelBufferingWatchdog();
        bufferingWatchdogRunnable = () -> {
            if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING && player.getPlayWhenReady()) {
                Log.w(TAG, "Playback stuck in buffering for " + BUFFERING_TIMEOUT_MS + "ms");
                if (callback != null) {
                    callback.onPlayerError(new PlaybackException(
                            "Превышено время ожидания видеопотока. Попробуйте сменить качество или озвучку.",
                            null,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT));
                }
            }
        };
        watchdogHandler.postDelayed(bufferingWatchdogRunnable, BUFFERING_TIMEOUT_MS);
    }

    private void cancelBufferingWatchdog() {
        if (bufferingWatchdogRunnable != null) {
            watchdogHandler.removeCallbacks(bufferingWatchdogRunnable);
            bufferingWatchdogRunnable = null;
        }
    }

    private Player.Listener playerListener;

    private void setupPlayerListener() {
        if (player == null) return;
        if (playerListener != null) {
            player.removeListener(playerListener);
        }
        playerListener = new Player.Listener() {
            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    lastValidAspectRatio = (float) (videoSize.width * videoSize.pixelWidthHeightRatio) / (float) videoSize.height;
                }
                preservePlayerAspectRatio();
                if (callback != null) {
                    callback.onVideoSizeChanged(videoSize);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                isFirstFrameRendered = true;
                cancelBufferingWatchdog();
                if (callback != null) {
                    callback.onFirstFrameRendered();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                captureValidPositionFromPlayer();
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    cancelBufferingWatchdog();
                } else if (playbackState == Player.STATE_BUFFERING && player != null && player.getPlayWhenReady()) {
                    startBufferingWatchdog();
                }
                if (callback != null) {
                    callback.onPlaybackStateChanged(playbackState, player != null && player.getPlayWhenReady());
                }
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                if (newPosition.positionMs > 0) {
                    lastKnownValidPositionMs = newPosition.positionMs;
                } else if (oldPosition.positionMs > 0 && reason != Player.DISCONTINUITY_REASON_SEEK) {
                    lastKnownValidPositionMs = oldPosition.positionMs;
                }
                if (callback != null) {
                    callback.onPositionDiscontinuity(reason);
                }
            }

            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                captureValidPositionFromPlayer();
                if (callback != null) {
                    callback.onIsLoadingChanged(isLoading);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                captureValidPositionFromPlayer();
                if (callback != null) {
                    callback.onIsPlayingChanged(isPlaying);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                captureValidPositionFromPlayer();
                Log.e(TAG, "ExoPlayer error", error);
                cancelBufferingWatchdog();
                if (callback != null) {
                    callback.onPlayerError(error);
                }
            }

            @Override
            public void onEvents(@NonNull Player p, @NonNull Player.Events events) {
                captureValidPositionFromPlayer();
                if (callback != null) {
                    callback.onEvents();
                }
            }
        };
        player.addListener(playerListener);
    }

    public void attachNewPlayer(ExoPlayer newPlayer) {
        cancelBufferingWatchdog();
        if (this.player != null && this.player != newPlayer) {
            try {
                if (playerListener != null) {
                    this.player.removeListener(playerListener);
                }
                this.player.stop();
                this.player.release();
            } catch (Exception ignored) {}
        }
        this.player = newPlayer;
        if (playerView != null) {
            playerView.setPlayer(newPlayer);
        }
        isFirstFrameRendered = true;
        setupPlayerListener();
        TimecodeManager timecodeManager = callback != null ? callback.getTimecodeManager() : null;
        if (timecodeManager != null) {
            timecodeManager.updatePlayer(newPlayer);
        }
        if (callback != null) {
            callback.onPlayerAttached(newPlayer);
        }
    }

    public void release() {
        cancelBufferingWatchdog();
        cleanupBackgroundPlayer();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
