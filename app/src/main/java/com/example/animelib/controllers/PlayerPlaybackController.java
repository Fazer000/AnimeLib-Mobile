package com.example.animelib.controllers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import com.google.android.material.button.MaterialButton;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.managers.AmbientLightManager;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.managers.TimecodeManager;

public class PlayerPlaybackController {

    private static final String TAG = "PlayerPlaybackController";
    private static final long BUFFERING_TIMEOUT_MS = 15_000L;

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
    }

    private final Context context;
    private final PlayerView playerView;
    private final HttpDataSource.Factory httpDataSourceFactory;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable bufferingWatchdogRunnable = null;
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

    private HttpDataSource.Factory getEffectiveHttpDataSourceFactory() {
        HttpDataSource.Factory factory = null;
        if (httpDataSourceFactory != null) {
            factory = httpDataSourceFactory;
        } else if (callback != null && callback.getHttpDataSourceFactory() != null) {
            factory = callback.getHttpDataSourceFactory();
        }

        java.util.Map<String, String> headers = null;
        if (callback != null && callback.getApiService() != null) {
            headers = callback.getApiService().getVideoRequestHeaders();
        }

        if (factory instanceof DefaultHttpDataSource.Factory) {
            DefaultHttpDataSource.Factory defaultFactory = (DefaultHttpDataSource.Factory) factory;
            defaultFactory
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(15000)
                    .setAllowCrossProtocolRedirects(true);
            if (headers != null) {
                defaultFactory.setDefaultRequestProperties(headers);
            }
            return defaultFactory;
        }

        DefaultHttpDataSource.Factory newFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        if (headers != null) {
            newFactory.setDefaultRequestProperties(headers);
        }
        return newFactory;
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
                            1_500,  // bufferForPlaybackMs
                            2_500   // bufferForPlaybackAfterRebufferMs
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

        HttpDataSource.Factory httpFactory = getEffectiveHttpDataSourceFactory();
        DataSource.Factory dsFactory = new DefaultDataSource.Factory(playerContext, httpFactory);

        MediaSource mediaSource;
        if (videoUrl.contains(".m3u8") || videoUrl.contains("hls")) {
            mediaSource = new HlsMediaSource.Factory(dsFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl));
        } else {
            androidx.media3.extractor.DefaultExtractorsFactory extractorsFactory =
                    new androidx.media3.extractor.DefaultExtractorsFactory()
                            .setConstantBitrateSeekingEnabled(true);

            mediaSource = new ProgressiveMediaSource.Factory(dsFactory, extractorsFactory)
                    .createMediaSource(mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl));
        }

        if (startPosition > 0) {
            player.setMediaSource(mediaSource, startPosition);
        } else {
            player.setMediaSource(mediaSource);
        }

        AmbientLightManager ambientLightManager = callback != null ? callback.getAmbientLightManager() : null;
        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(player, mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl), videoUrl);
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

        HttpDataSource.Factory httpFactory = getEffectiveHttpDataSourceFactory();
        DataSource.Factory dsFactory = new DefaultDataSource.Factory(playerContext, httpFactory);

        MediaSource mediaSource;
        if (videoUrl.contains(".m3u8") || videoUrl.contains("hls")) {
            mediaSource = new HlsMediaSource.Factory(dsFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl));
        } else {
            androidx.media3.extractor.DefaultExtractorsFactory extractorsFactory =
                    new androidx.media3.extractor.DefaultExtractorsFactory()
                            .setConstantBitrateSeekingEnabled(true);

            mediaSource = new ProgressiveMediaSource.Factory(dsFactory, extractorsFactory)
                    .createMediaSource(mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl));
        }

        AmbientLightManager ambientLightManager = callback != null ? callback.getAmbientLightManager() : null;
        if (ambientLightManager != null) {
            ambientLightManager.setPlayer(player, mediaItem != null ? mediaItem : MediaItem.fromUri(videoUrl), videoUrl);
        }

        if (startPosition > 0) {
            player.setMediaSource(mediaSource, startPosition);
        } else {
            player.setMediaSource(mediaSource);
        }

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
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "ExoPlayer error", error);
                cancelBufferingWatchdog();
                if (callback != null) {
                    callback.onPlayerError(error);
                }
            }
        });
    }

    public void release() {
        cancelBufferingWatchdog();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
