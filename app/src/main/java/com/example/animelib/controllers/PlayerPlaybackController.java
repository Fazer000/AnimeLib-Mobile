package com.example.animelib.controllers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.managers.AmbientLightManager;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.managers.TimecodeManager;
import com.google.android.material.button.MaterialButton;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class PlayerPlaybackController {

    private static final String TAG = "PlayerPlaybackController";
    private static final long BUFFERING_TIMEOUT_MS = 12_000L;
    private static final String STREAM_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36";

    private static final ConnectionPool SHARED_STREAM_POOL = new ConnectionPool(8, 2, TimeUnit.MINUTES);

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
        default void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize videoSize) {}
    }

    private final Context context;
    private final PlayerView playerView;
    private final HttpDataSource.Factory httpDataSourceFactory;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable bufferingWatchdogRunnable = null;
    private OkHttpClient streamingOkHttpClient;
    private ExoPlayer player;
    private PlaybackCallback callback;
    private boolean isFirstFrameRendered = false;

    public PlayerPlaybackController(Context context, PlayerView playerView, HttpDataSource.Factory httpDataSourceFactory) {
        this.context = context;
        this.playerView = playerView;
        this.httpDataSourceFactory = httpDataSourceFactory;
    }

    public void setCallback(PlaybackCallback callback) {
        this.callback = callback;
    }

    public ExoPlayer getPlayer() {
        return player;
    }

    private synchronized OkHttpClient getOrCreateStreamingOkHttpClient() {
        if (streamingOkHttpClient == null) {
            streamingOkHttpClient = new OkHttpClient.Builder()
                    .connectionPool(SHARED_STREAM_POOL)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .addInterceptor(chain -> {
                        Request original = chain.request();
                        String urlStr = original.url().toString();
                        Request.Builder builder = original.newBuilder();

                        if (ApiService.isKodikStreamUrl(urlStr)) {
                            // Важно: не отправляем огромный JWT-токен AnimeLib и чужой Origin на CDN Kodik
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

    public HttpDataSource.Factory buildStreamHttpDataSourceFactory(String targetUrl) {
        OkHttpDataSource.Factory okFactory = new OkHttpDataSource.Factory(getOrCreateStreamingOkHttpClient())
                .setUserAgent(STREAM_USER_AGENT);
        if (ApiService.isKodikStreamUrl(targetUrl)) {
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
                    // При 403/404/410 ссылка или CDN-нода устарела — не ждём лишних ретраев, сразу отдаём управление авто-восстановлению
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

    private MediaSource buildMediaSourceForUrl(Context playerContext, String videoUrl, MediaItem itemToPlay) {
        HttpDataSource.Factory httpFactory = buildStreamHttpDataSourceFactory(videoUrl);
        DataSource.Factory dsFactory = new DefaultDataSource.Factory(playerContext, httpFactory);
        LoadErrorHandlingPolicy errorPolicy = createStreamErrorHandlingPolicy();

        boolean isHls = videoUrl != null && (
                videoUrl.contains(".m3u8")
                        || videoUrl.contains(":hls:")
                        || ApiService.isKodikStreamUrl(videoUrl) && !videoUrl.endsWith(".mp4")
        );

        boolean hasExternalSubtitles = itemToPlay.localConfiguration != null
                && !itemToPlay.localConfiguration.subtitleConfigurations.isEmpty();

        if (isHls && !hasExternalSubtitles) {
            return new HlsMediaSource.Factory(dsFactory)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(errorPolicy)
                    .createMediaSource(itemToPlay);
        }

        return new DefaultMediaSourceFactory(dsFactory)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .createMediaSource(itemToPlay);
    }

    public ExoPlayer initializePlayer(String videoUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady) {
        return initializePlayer(videoUrl, mediaItem, resizeMode, playWhenReady, 0);
    }

    public ExoPlayer initializePlayer(String videoUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady, long startPosition) {
        if (context == null || videoUrl == null) return player;

        Context playerContext = callback != null ? callback.getPlayerContext() : context;
        if (playerContext == null) playerContext = context;

        isFirstFrameRendered = false;

        PlayerAudioController audioController = callback != null ? callback.getPlayerAudioController() : null;

        if (player == null) {
            com.example.animelib.util.SurroundRenderersFactory rf = new com.example.animelib.util.SurroundRenderersFactory(
                    playerContext,
                    audioController != null ? audioController.getSurroundAudioProcessor() : null);

            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            15_000, // minBufferMs
                            50_000, // maxBufferMs
                            1_000,  // bufferForPlaybackMs — ускоренный старт воспроизведения
                            2_000   // bufferForPlaybackAfterRebufferMs
                    )
                    .setBackBuffer(10_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();

            ExoPlayer.Builder builder = new ExoPlayer.Builder(playerContext, rf)
                    .setLoadControl(loadControl)
                    .setSeekBackIncrementMs(10000)
                    .setSeekForwardIncrementMs(10000);

            player = builder.build();
            if (playerView != null) {
                playerView.setPlayer(player);
                playerView.setResizeMode(resizeMode);
            }
            setupPlayerListener();
        } else {
            player.stop();
            player.clearMediaItems();
        }

        MediaItem itemToPlay = mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl);
        MediaSource mediaSource = buildMediaSourceForUrl(playerContext, videoUrl, itemToPlay);

        player.setMediaSource(mediaSource, Math.max(0L, startPosition));

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

        return player;
    }

    public ExoPlayer switchMediaSource(String videoUrl, MediaItem mediaItem, long startPosition, boolean playWhenReady) {
        if (player == null) {
            int resizeMode = playerView != null ? playerView.getResizeMode() : androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
            return initializePlayer(videoUrl, mediaItem, resizeMode, playWhenReady, startPosition);
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

        player.setMediaSource(mediaSource, Math.max(0L, startPosition));

        if (playWhenReady) {
            startBufferingWatchdog();
        } else {
            cancelBufferingWatchdog();
        }

        player.setPlayWhenReady(playWhenReady);
        player.prepare();

        return player;
    }

    public ExoPlayer initializeHlsPlayer(String hlsUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady) {
        return initializePlayer(hlsUrl, mediaItem, resizeMode, playWhenReady, 0);
    }

    public ExoPlayer initializeHlsPlayer(String hlsUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady, long startPosition) {
        return initializePlayer(hlsUrl, mediaItem, resizeMode, playWhenReady, startPosition);
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

    private void setupPlayerListener() {
        if (player == null) return;
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize videoSize) {
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
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "ExoPlayer error", error);
                cancelBufferingWatchdog();
                if (callback != null) {
                    callback.onPlayerError(error);
                }
            }
        });
    }

    public void attachNewPlayer(ExoPlayer newPlayer) {
        cancelBufferingWatchdog();
        if (this.player != null && this.player != newPlayer) {
            try {
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
    }

    public void release() {
        cancelBufferingWatchdog();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
