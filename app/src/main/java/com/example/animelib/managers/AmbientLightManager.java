package com.example.animelib.managers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.example.animelib.ui.AmbientVignetteOverlayView;
import com.example.animelib.util.MediaCacheManager;

/**
 * Высокопроизводительный менеджер фоновой подсветки (Ambilight).
 *
 * Принципиальная архитектура:
 * 1. Direct GPU Surface Sampling: Считывает кадры напрямую с TextureView основного плеера
 *    в режиме реального времени (0мс задержки, 0 элементов рассинхрона, 0 доп. сетевого трафика).
 * 2. Аппаратный GPU-блюр (RenderEffect) с повышенной насыщенностью цветов (+40%).
 * 3. Fallback на второй легковесный ExoPlayer, если основной View не является TextureView.
 */
public class AmbientLightManager {
    private static final String TAG = "AmbientLightManager";

    private final Context context;
    private final PlayerView mainPlayerView;
    private final View ambientContainer;
    private final PlayerView ambientPlayerView;
    private final ImageView ambientImageView;
    private final AmbientVignetteOverlayView ambientVignetteOverlay;
    private final Handler mainHandler;

    private ExoPlayer mainPlayer;
    private ExoPlayer ambientPlayer;

    private DataSource.Factory cacheDataSourceFactory;
    private MediaItem currentMediaItem;
    private String currentVideoUrl;

    private volatile boolean isEnabled = false;
    private volatile boolean isPrepared = false;
    private boolean isErrorState = false;
    private boolean isSuspended = false;
    private boolean isFrozen = false;
    private boolean isDirectSamplerActive = false;

    private Bitmap sampleBitmap;
    private Player.Listener mainPlayerListener;
    private Player.Listener ambientPlayerListener;

    public AmbientLightManager(@NonNull Context context,
                               @NonNull PlayerView mainPlayerView,
                               @Nullable PlayerView ambientPlayerView) {
        this(context, mainPlayerView, null, ambientPlayerView, null);
    }

    public AmbientLightManager(@NonNull Context context,
                               @NonNull PlayerView mainPlayerView,
                               @Nullable View ambientContainer,
                               @Nullable PlayerView ambientPlayerView,
                               @Nullable AmbientVignetteOverlayView ambientVignetteOverlay) {
        this.context = context;
        this.mainPlayerView = mainPlayerView;
        this.ambientContainer = ambientContainer;
        this.ambientPlayerView = ambientPlayerView;
        this.ambientImageView = ambientContainer != null ? ambientContainer.findViewById(com.example.animelib.R.id.ambientImageView) : null;
        this.ambientVignetteOverlay = ambientVignetteOverlay;
        this.mainHandler = new Handler(Looper.getMainLooper());

        setupAmbientViewStyle();
    }

    /**
     * Стилизация и настройка отображения слоев подсветки
     */
    private void setupAmbientViewStyle() {
        if (ambientContainer != null) {
            ambientContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        if (ambientImageView != null) {
            ambientImageView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            ambientImageView.setScaleType(ImageView.ScaleType.FIT_XY);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    RenderEffect blurEffect = RenderEffect.createBlurEffect(120f, 120f, Shader.TileMode.CLAMP);
                    ColorMatrix colorMatrix = new ColorMatrix();
                    colorMatrix.setSaturation(1.4f);

                    ColorMatrix scaleMatrix = new ColorMatrix(new float[] {
                            1.1f, 0,    0,    0, 0,
                            0,    1.1f, 0,    0, 0,
                            0,    0,    1.1f, 0, 0,
                            0,    0,    0,    1, 0
                    });
                    colorMatrix.postConcat(scaleMatrix);

                    RenderEffect colorEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
                    RenderEffect combinedEffect = RenderEffect.createChainEffect(blurEffect, colorEffect);
                    ambientImageView.setRenderEffect(combinedEffect);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply RenderEffect to ambientImageView", e);
                }
            } else {
                ambientImageView.setAlpha(0.95f);
            }
        }

        if (ambientPlayerView != null) {
            ambientPlayerView.setUseController(false);
            ambientPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            ambientPlayerView.setAlpha(1.0f);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    RenderEffect blurEffect = RenderEffect.createBlurEffect(120f, 120f, Shader.TileMode.CLAMP);
                    ColorMatrix colorMatrix = new ColorMatrix();
                    colorMatrix.setSaturation(1.4f);
                    RenderEffect colorEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
                    RenderEffect combinedEffect = RenderEffect.createChainEffect(blurEffect, colorEffect);
                    ambientPlayerView.setRenderEffect(combinedEffect);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply RenderEffect blur to ambientPlayerView", e);
                }
            }
        }
    }

    private int textureAttachRetryCount = 0;
    private final Runnable retryAttachRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isDirectSamplerActive && mainPlayerView != null && textureAttachRetryCount < 10) {
                textureAttachRetryCount++;
                attachMainTextureViewListener();
            }
        }
    };

    private void attachMainTextureViewListener() {
        if (mainPlayerView == null) return;
        View surfaceView = mainPlayerView.getVideoSurfaceView();
        if (surfaceView instanceof TextureView) {
            TextureView mainTextureView = (TextureView) surfaceView;
            if (sampleBitmap == null || sampleBitmap.isRecycled()) {
                sampleBitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
            }

            TextureView.SurfaceTextureListener previousListener = mainTextureView.getSurfaceTextureListener();
            mainTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    if (previousListener != null) previousListener.onSurfaceTextureAvailable(surface, width, height);
                    sampleMainFrame(mainTextureView);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    if (previousListener != null) previousListener.onSurfaceTextureSizeChanged(surface, width, height);
                    sampleMainFrame(mainTextureView);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    if (previousListener != null) return previousListener.onSurfaceTextureDestroyed(surface);
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                    if (previousListener != null) previousListener.onSurfaceTextureUpdated(surface);
                    if (isEnabled && !isSuspended && !isFrozen) {
                        sampleMainFrame(mainTextureView);
                    }
                }
            });

            isDirectSamplerActive = true;
            if (ambientImageView != null) ambientImageView.setVisibility(View.VISIBLE);
            if (ambientPlayerView != null) ambientPlayerView.setVisibility(View.GONE);
            releaseAmbientPlayer();
            sampleMainFrame(mainTextureView);
        } else {
            isDirectSamplerActive = false;
            if (ambientImageView != null) ambientImageView.setVisibility(View.GONE);
            if (ambientPlayerView != null) ambientPlayerView.setVisibility(View.VISIBLE);
            mainHandler.removeCallbacks(retryAttachRunnable);
            if (textureAttachRetryCount < 10) {
                mainHandler.postDelayed(retryAttachRunnable, 200);
            }
        }
    }

    private long lastSampleTimeMs = 0;
    private static final long SAMPLE_INTERVAL_MS = 33; // ~30 FPS: плавный мгновенный акаратный синхрон с видео без рассинхрона

    private void sampleMainFrame(TextureView mainTextureView) {
        if (ambientImageView == null || mainTextureView == null || !mainTextureView.isAvailable()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSampleTimeMs < SAMPLE_INTERVAL_MS) {
            return;
        }
        lastSampleTimeMs = now;
        try {
            if (sampleBitmap == null || sampleBitmap.isRecycled()) {
                sampleBitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
            }
            mainTextureView.getBitmap(sampleBitmap);
            ambientImageView.setImageBitmap(sampleBitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error in sampleMainFrame", e);
        }
    }

    public void refreshAmbientFrame() {
        mainHandler.post(() -> {
            if (mainPlayerView != null) {
                View surfaceView = mainPlayerView.getVideoSurfaceView();
                if (surfaceView instanceof TextureView) {
                    lastSampleTimeMs = 0;
                    sampleMainFrame((TextureView) surfaceView);
                }
            }
        });
    }

    public void setDataSourceFactory(DataSource.Factory dataSourceFactory) {
        if (dataSourceFactory != null) {
            this.cacheDataSourceFactory = MediaCacheManager.createCacheDataSourceFactory(context, dataSourceFactory);
        }
    }

    public void setPlayer(@Nullable ExoPlayer mainPlayer) {
        setPlayer(mainPlayer, null, null);
    }

    public void setPlayer(@Nullable ExoPlayer mainPlayer, @Nullable MediaItem mediaItem, @Nullable String videoUrl) {
        if (this.mainPlayer != null && mainPlayerListener != null) {
            this.mainPlayer.removeListener(mainPlayerListener);
        }

        this.mainPlayer = mainPlayer;
        if (mediaItem != null) this.currentMediaItem = mediaItem;
        if (videoUrl != null) this.currentVideoUrl = videoUrl;

        this.isErrorState = false;
        this.isPrepared = false;

        if (mainPlayer == null) {
            releaseAmbientPlayer();
            return;
        }

        setupMainPlayerListener();
        attachMainTextureViewListener();

        if (isEnabled && !isSuspended && !isFrozen) {
            showAmbientContainer(true);
            if (!isDirectSamplerActive) {
                ensureAmbientPlayerInitialized();
            }
        }
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        mainHandler.post(() -> {
            if (enabled) {
                if (!isSuspended && !isFrozen) {
                    showAmbientContainer(true);
                    attachMainTextureViewListener();
                    if (!isDirectSamplerActive) {
                        ensureAmbientPlayerInitialized();
                    }
                    refreshAmbientFrame();
                }
            } else {
                hideAmbientContainer(true);
                releaseAmbientPlayer();
            }
        });
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void suspend() {
        this.isSuspended = true;
        mainHandler.post(() -> {
            hideAmbientContainer(false);
            pauseAmbientPlayer();
        });
    }

    public void freeze() {
        this.isFrozen = true;
        mainHandler.post(() -> {
            hideAmbientContainer(false);
            pauseAmbientPlayer();
        });
    }

    public void unfreeze() {
        this.isFrozen = false;
        mainHandler.post(() -> {
            if (isEnabled && !isErrorState && !isSuspended) {
                showAmbientContainer(true);
                attachMainTextureViewListener();
                if (!isDirectSamplerActive) {
                    ensureAmbientPlayerInitialized();
                }
                refreshAmbientFrame();
            }
        });
    }

    public void resume() {
        this.isSuspended = false;
        this.isFrozen = false;
        mainHandler.post(() -> {
            if (isEnabled && !isErrorState) {
                showAmbientContainer(true);
                attachMainTextureViewListener();
                if (!isDirectSamplerActive) {
                    ensureAmbientPlayerInitialized();
                }
                refreshAmbientFrame();
            }
        });
    }

    private void ensureAmbientPlayerInitialized() {
        if (!isEnabled || mainPlayer == null || isErrorState || isSuspended || ambientPlayerView == null || isDirectSamplerActive) return;

        if (ambientPlayer == null) {
            try {
                DefaultLoadControl ambientLoadControl = new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(1_000, 3_000, 200, 400)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build();

                ExoPlayer.Builder builder = new ExoPlayer.Builder(context)
                        .setLoadControl(ambientLoadControl);

                if (cacheDataSourceFactory != null) {
                    builder.setMediaSourceFactory(new DefaultMediaSourceFactory(cacheDataSourceFactory));
                }

                ambientPlayer = builder.build();
                ambientPlayerView.setPlayer(ambientPlayer);

                ambientPlayer.setVolume(0f);
                TrackSelectionParameters parameters = ambientPlayer.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setMaxVideoBitrate(1_000_000)
                        .build();
                ambientPlayer.setTrackSelectionParameters(parameters);

                setupAmbientPlayerListener();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create fallback ambient ExoPlayer", e);
                isErrorState = true;
                hideAmbientContainer(false);
                return;
            }
        }

        prepareAmbientMedia();
    }

    private void prepareAmbientMedia() {
        if (ambientPlayer == null || mainPlayer == null || isErrorState || isDirectSamplerActive) return;

        try {
            MediaItem mediaItemToUse = currentMediaItem;
            if (mediaItemToUse == null && currentVideoUrl != null && !currentVideoUrl.isEmpty()) {
                mediaItemToUse = MediaItem.fromUri(currentVideoUrl);
            }

            if (mediaItemToUse != null) {
                ambientPlayer.setMediaItem(mediaItemToUse);
                ambientPlayer.prepare();
                ambientPlayer.setPlaybackParameters(mainPlayer.getPlaybackParameters());
                ambientPlayer.seekTo(mainPlayer.getCurrentPosition());
                if (mainPlayer.isPlaying()) {
                    ambientPlayer.play();
                } else {
                    ambientPlayer.pause();
                }
                isPrepared = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error preparing ambient media", e);
        }
    }

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            if (isEnabled && !isSuspended && !isFrozen && mainPlayer != null && ambientPlayer != null && !isDirectSamplerActive) {
                try {
                    long mainPos = mainPlayer.getCurrentPosition();
                    long ambPos = ambientPlayer.getCurrentPosition();
                    long diff = Math.abs(mainPos - ambPos);

                    if (diff > 150) {
                        ambientPlayer.seekTo(mainPos);
                    }

                    if (ambientPlayer.getPlaybackParameters().speed != mainPlayer.getPlaybackParameters().speed) {
                        ambientPlayer.setPlaybackParameters(mainPlayer.getPlaybackParameters());
                    }

                    if (mainPlayer.isPlaying() && !ambientPlayer.isPlaying()) {
                        ambientPlayer.play();
                    } else if (!mainPlayer.isPlaying() && ambientPlayer.isPlaying()) {
                        ambientPlayer.pause();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in ambient sync loop", e);
                }
            }
            if (isEnabled && !isSuspended && !isFrozen && ambientPlayer != null && !isDirectSamplerActive) {
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    private void setupMainPlayerListener() {
        if (mainPlayer == null) return;

        mainPlayerListener = new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (!isEnabled || isSuspended || isFrozen) return;
                refreshAmbientFrame();
                if (ambientPlayer != null && !isDirectSamplerActive) {
                    if (isPlaying) {
                        ambientPlayer.play();
                        mainHandler.removeCallbacks(syncRunnable);
                        mainHandler.post(syncRunnable);
                    } else {
                        ambientPlayer.pause();
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (!isEnabled || isSuspended || isFrozen) return;
                refreshAmbientFrame();
                if (!isDirectSamplerActive) {
                    attachMainTextureViewListener();
                }
            }

            @Override
            public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
                if (!isEnabled || isSuspended || isFrozen) return;
                if (ambientPlayer != null && !isDirectSamplerActive) {
                    ambientPlayer.setPlaybackParameters(playbackParameters);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (!isEnabled || isSuspended || isFrozen) return;
                refreshAmbientFrame();
                if (ambientPlayer != null && !isDirectSamplerActive && mainPlayer != null) {
                    ambientPlayer.seekTo(mainPlayer.getCurrentPosition());
                    if (mainPlayer.isPlaying()) {
                        ambientPlayer.play();
                    }
                }
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                if (!isEnabled || isSuspended || isFrozen) return;
                refreshAmbientFrame();
                if (ambientPlayer != null && !isDirectSamplerActive) {
                    ambientPlayer.seekTo(mainPlayer.getCurrentPosition());
                }
            }
        };

        mainPlayer.addListener(mainPlayerListener);
    }

    private void setupAmbientPlayerListener() {
        if (ambientPlayer == null) return;

        ambientPlayerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Fallback ambient player error: " + error.getMessage());
                isErrorState = true;
                pauseAmbientPlayer();
            }
        };

        ambientPlayer.addListener(ambientPlayerListener);
    }

    private void showAmbientContainer(boolean animate) {
        View targetView = ambientContainer != null ? ambientContainer : (isDirectSamplerActive ? ambientImageView : ambientPlayerView);
        if (targetView == null) return;

        targetView.removeCallbacks(null);
        if (targetView.getVisibility() != View.VISIBLE) {
            targetView.setVisibility(View.VISIBLE);
            targetView.setAlpha(0f);
        }

        if (animate) {
            targetView.animate()
                    .alpha(1.0f)
                    .setDuration(250)
                    .start();
        } else {
            targetView.setAlpha(1.0f);
        }
    }

    private void hideAmbientContainer(boolean animate) {
        View targetView = ambientContainer != null ? ambientContainer : (isDirectSamplerActive ? ambientImageView : ambientPlayerView);
        if (targetView == null) return;

        if (animate) {
            targetView.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> targetView.setVisibility(View.GONE))
                    .start();
        } else {
            targetView.setAlpha(0f);
            targetView.setVisibility(View.GONE);
        }
    }

    private void pauseAmbientPlayer() {
        if (ambientPlayer != null) {
            try {
                ambientPlayer.pause();
            } catch (Exception ignored) {}
        }
    }

    public void releaseAmbientPlayer() {
        mainHandler.removeCallbacks(syncRunnable);
        mainHandler.removeCallbacks(retryAttachRunnable);

        if (mainPlayer != null && mainPlayerListener != null) {
            mainPlayer.removeListener(mainPlayerListener);
            mainPlayerListener = null;
        }

        if (ambientPlayer != null) {
            try {
                if (ambientPlayerListener != null) {
                    ambientPlayer.removeListener(ambientPlayerListener);
                    ambientPlayerListener = null;
                }
                ambientPlayer.stop();
                ambientPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing ambientPlayer", e);
            }
            ambientPlayer = null;
        }
        isPrepared = false;
        isErrorState = false;
    }

    public void cleanup() {
        releaseAmbientPlayer();
        if (sampleBitmap != null && !sampleBitmap.isRecycled()) {
            sampleBitmap.recycle();
            sampleBitmap = null;
        }
    }

    public void onConfigurationChanged() {
        mainHandler.postDelayed(() -> {
            if (isEnabled && !isSuspended && !isFrozen) {
                attachMainTextureViewListener();
                refreshAmbientFrame();
            }
        }, 150);
    }

    public void onPause() {
        suspend();
    }

    public void onResume() {
        resume();
    }

    public void onDestroy() {
        cleanup();
    }
}
