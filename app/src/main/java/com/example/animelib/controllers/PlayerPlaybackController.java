package com.example.animelib.controllers;

import android.content.Context;
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
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
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .setAllowCrossProtocolRedirects(true);
            if (headers != null) {
                defaultFactory.setDefaultRequestProperties(headers);
            }
            return defaultFactory;
        }

        DefaultHttpDataSource.Factory newFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        if (headers != null) {
            newFactory.setDefaultRequestProperties(headers);
        }
        return newFactory;
    }

    public ExoPlayer initializePlayer(String videoUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady) {
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

        player.setMediaSource(mediaSource);

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

        player.setPlayWhenReady(playWhenReady);
        player.prepare();

        return player;
    }

    public ExoPlayer initializeHlsPlayer(String hlsUrl, MediaItem mediaItem, int resizeMode, boolean playWhenReady) {
        return initializePlayer(hlsUrl, mediaItem, resizeMode, playWhenReady);
    }

    private void setupPlayerListener() {
        if (player == null) return;
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                isFirstFrameRendered = true;
                if (callback != null) {
                    callback.onFirstFrameRendered();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (callback != null) {
                    callback.onPlaybackStateChanged(playbackState, player != null && player.getPlayWhenReady());
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "ExoPlayer error", error);
                if (callback != null) {
                    callback.onPlayerError(error);
                }
            }
        });
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
