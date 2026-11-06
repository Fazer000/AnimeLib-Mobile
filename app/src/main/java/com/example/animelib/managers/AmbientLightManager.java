package com.example.animelib.managers;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.example.animelib.ui.AmbientVignetteOverlayView;
import com.example.animelib.util.MediaCacheManager;

/**
 * Высокопроизводительный менеджер фоновой подсветки (Ambilight) на базе второго плеера.
 *
 * Архитектурные принципы:
 * 1. Второй легковесный ExoPlayer: декодирует видеопоток с отключенным аудио и субтитрами,
 *    с ограничением разрешения для максимальной плавности и экономии батареи.
 * 2. Идеальная суб-кадровая синхронизация (двухконтурный микро-контроль скорости без заиканий деселектора).
 * 3. Физический выступ за границы основного плеера (выступает на ~22% и плавно рассеивается).
 * 4. Аппаратный GPU-блюр RenderEffect с насыщенностью цвета +45% и контрастом.
 */
public class AmbientLightManager {
    private static final String TAG = "AmbientLightManager";

    private final Context context;
    private final PlayerView mainPlayerView;
    private final View ambientContainer;
    private final PlayerView ambientPlayerView;
    private final AmbientVignetteOverlayView ambientVignetteOverlay;
    private final Handler mainHandler;

    private ExoPlayer mainPlayer;
    private ExoPlayer ambientPlayer;

    private DataSource.Factory cacheDataSourceFactory;
    private MediaItem currentMediaItem;
    private String currentVideoUrl;

    private volatile boolean isEnabled = true;
    private volatile boolean isPrepared = false;
    private boolean isErrorState = false;
    private boolean isSuspended = false;
    private boolean isFrozen = false;

    private Player.Listener mainPlayerListener;
    private Player.Listener ambientPlayerListener;

    private float currentScale = 1.0f;
    private float currentTranslationX = 0f;
    private float currentTranslationY = 0f;
    private boolean isCurrentCropped = false;

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
        this.ambientVignetteOverlay = ambientVignetteOverlay;
        this.mainHandler = new Handler(Looper.getMainLooper());

        setupAmbientViewStyle();
    }

    /**
     * Стилизация слоя подсветки (аппаратный GPU-блюр, контраст и насыщенность)
     */
    private void setupAmbientViewStyle() {
        if (ambientContainer != null) {
            ambientContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        if (ambientPlayerView != null) {
            ambientPlayerView.setVisibility(View.VISIBLE);
            ambientPlayerView.setUseController(false);
            ambientPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            ambientPlayerView.setAlpha(1.0f);
            ambientPlayerView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    // Мягкий широкий GPU-блюр для бесшовного облачного рассеивания
                    RenderEffect blurEffect = RenderEffect.createBlurEffect(110f, 110f, Shader.TileMode.CLAMP);

                    // Богатая насыщенность и легкий подъем контраста для сочной подсветки
                    ColorMatrix colorMatrix = new ColorMatrix();
                    colorMatrix.setSaturation(1.45f);

                    ColorMatrix scaleMatrix = new ColorMatrix(new float[]{
                            1.15f, 0,     0,     0, 5f,
                            0,     1.15f, 0,     0, 5f,
                            0,     0,     1.15f, 0, 5f,
                            0,     0,     0,     1, 0
                    });
                    colorMatrix.postConcat(scaleMatrix);

                    RenderEffect colorEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
                    RenderEffect combinedEffect = RenderEffect.createChainEffect(blurEffect, colorEffect);
                    ambientPlayerView.setRenderEffect(combinedEffect);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply RenderEffect blur to ambientPlayerView", e);
                }
            } else {
                ambientPlayerView.setAlpha(0.92f);
            }
        }
    }

    /**
     * Устанавливает фабрику кэша для воспроизведения без повторной загрузки видео
     */
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

        if (isEnabled && !isSuspended && !isFrozen) {
            showAmbientContainer(true);
            ensureAmbientPlayerInitialized();
            updateBoundsFromPlayer();
        }
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        mainHandler.post(() -> {
            if (enabled) {
                if (!isSuspended && !isFrozen) {
                    showAmbientContainer(true);
                    ensureAmbientPlayerInitialized();
                    updateBoundsFromPlayer();
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
            stopSyncLoop();
        });
    }

    public void freeze() {
        this.isFrozen = true;
        mainHandler.post(() -> {
            hideAmbientContainer(false);
            pauseAmbientPlayer();
            stopSyncLoop();
        });
    }

    public void unfreeze() {
        this.isFrozen = false;
        mainHandler.post(() -> {
            if (isEnabled && !isErrorState && !isSuspended) {
                showAmbientContainer(true);
                ensureAmbientPlayerInitialized();
                updateBoundsFromPlayer();
            }
        });
    }

    public void resume() {
        this.isSuspended = false;
        this.isFrozen = false;
        mainHandler.post(() -> {
            if (isEnabled && !isErrorState) {
                showAmbientContainer(true);
                ensureAmbientPlayerInitialized();
                updateBoundsFromPlayer();
            }
        });
    }

    /**
     * Создает и конфигурирует второй плеер подсветки
     */
    private void ensureAmbientPlayerInitialized() {
        if (!isEnabled || mainPlayer == null || isErrorState || isSuspended || ambientPlayerView == null) return;

        if (ambientPlayer == null) {
            try {
                // Быстрый легковесный LoadControl с малым буфером для отзывчивости
                DefaultLoadControl ambientLoadControl = new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(1_000, 3_000, 250, 500)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build();

                ExoPlayer.Builder builder = new ExoPlayer.Builder(context)
                        .setLoadControl(ambientLoadControl);

                if (cacheDataSourceFactory != null) {
                    builder.setMediaSourceFactory(new DefaultMediaSourceFactory(cacheDataSourceFactory));
                }

                ambientPlayer = builder.build();
                ambientPlayerView.setPlayer(ambientPlayer);

                // Отключаем звук, аудио-декодер и субтитры для нулевой нагрузки на аудиосистему
                ambientPlayer.setVolume(0f);
                TrackSelectionParameters parameters = ambientPlayer.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setMaxVideoSize(960, 540) // Оптимально для размытой подсветки: четкие градиенты без лишней нагрузки
                        .setMaxVideoBitrate(1_500_000)
                        .build();
                ambientPlayer.setTrackSelectionParameters(parameters);

                setupAmbientPlayerListener();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create secondary ambient ExoPlayer", e);
                isErrorState = true;
                hideAmbientContainer(false);
                return;
            }
        }

        prepareAmbientMedia();
    }

    /**
     * Подготавливает медиапоток во втором плеере
     */
    private void prepareAmbientMedia() {
        if (ambientPlayer == null || mainPlayer == null || isErrorState) return;

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
                    startSyncLoop();
                } else {
                    ambientPlayer.pause();
                }
                isPrepared = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error preparing ambient media", e);
        }
    }

    private void startSyncLoop() {
        mainHandler.removeCallbacks(syncRunnable);
        mainHandler.post(syncRunnable);
    }

    private void stopSyncLoop() {
        mainHandler.removeCallbacks(syncRunnable);
    }

    /**
     * Высокоточный непрерывный синхронизатор плееров:
     * - При рассинхроне < 20мс: попиксельная кадровая точность, стандартная скорость.
     * - При рассинхроне 20-350мс: микро-подгонка скорости на +/-5% без прерываний и заиканий видеодекодера.
     * - При рассинхроне > 350мс: мгновенный seek.
     */
    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isEnabled || isSuspended || isFrozen || mainPlayer == null || ambientPlayer == null) {
                return;
            }

            try {
                if (!mainPlayer.isPlaying()) {
                    if (ambientPlayer.isPlaying()) {
                        ambientPlayer.pause();
                    }
                    return;
                }

                if (!ambientPlayer.isPlaying() && mainPlayer.getPlaybackState() == Player.STATE_READY) {
                    ambientPlayer.play();
                }

                long mainPos = mainPlayer.getCurrentPosition();
                long ambPos = ambientPlayer.getCurrentPosition();
                long driftMs = ambPos - mainPos; // >0 если подсветка опережает, <0 если отстает
                float baseSpeed = mainPlayer.getPlaybackParameters().speed;

                if (Math.abs(driftMs) > 350) {
                    // Крупный сдвиг (прыжок по таймлайну): прямой seek
                    ambientPlayer.seekTo(mainPos);
                    ambientPlayer.setPlaybackSpeed(baseSpeed);
                } else if (driftMs < -20) {
                    // Подсветка чуть отстает: плавно ускоряем на +5% для незаметного нагона
                    ambientPlayer.setPlaybackSpeed(baseSpeed * 1.05f);
                } else if (driftMs > 20) {
                    // Подсветка чуть опережает: плавно притормаживаем на -5%
                    ambientPlayer.setPlaybackSpeed(baseSpeed * 0.95f);
                } else {
                    // Кадровая синхронизация идеальна (в пределах 20мс)
                    if (ambientPlayer.getPlaybackParameters().speed != baseSpeed) {
                        ambientPlayer.setPlaybackSpeed(baseSpeed);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in ambient sync loop", e);
            }

            if (isEnabled && !isSuspended && !isFrozen && mainPlayer != null && mainPlayer.isPlaying()) {
                mainHandler.postDelayed(this, 140);
            }
        }
    };

    /**
     * Слушатель событий основного плеера для мгновенной синхронизации
     */
    private void setupMainPlayerListener() {
        if (mainPlayer == null) return;

        mainPlayerListener = new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (!isEnabled || isSuspended || isFrozen) return;
                if (ambientPlayer != null) {
                    if (isPlaying) {
                        long mainPos = mainPlayer.getCurrentPosition();
                        long ambPos = ambientPlayer.getCurrentPosition();
                        if (Math.abs(mainPos - ambPos) > 40) {
                            ambientPlayer.seekTo(mainPos);
                        }
                        ambientPlayer.setPlaybackSpeed(mainPlayer.getPlaybackParameters().speed);
                        ambientPlayer.play();
                        startSyncLoop();
                    } else {
                        ambientPlayer.pause();
                        ambientPlayer.seekTo(mainPlayer.getCurrentPosition());
                        stopSyncLoop();
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (!isEnabled || isSuspended || isFrozen) return;
                if (ambientPlayer != null) {
                    if (playbackState == Player.STATE_BUFFERING) {
                        ambientPlayer.pause();
                    } else if (playbackState == Player.STATE_READY) {
                        if (mainPlayer.getPlayWhenReady()) {
                            long mainPos = mainPlayer.getCurrentPosition();
                            long ambPos = ambientPlayer.getCurrentPosition();
                            if (Math.abs(mainPos - ambPos) > 40) {
                                ambientPlayer.seekTo(mainPos);
                            }
                            ambientPlayer.setPlaybackSpeed(mainPlayer.getPlaybackParameters().speed);
                            ambientPlayer.play();
                            startSyncLoop();
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        ambientPlayer.pause();
                        stopSyncLoop();
                    }
                }
            }

            @Override
            public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
                if (!isEnabled || isSuspended || isFrozen) return;
                if (ambientPlayer != null) {
                    ambientPlayer.setPlaybackParameters(playbackParameters);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (!isEnabled || isSuspended || isFrozen) return;
                if (ambientPlayer != null && mainPlayer != null) {
                    ambientPlayer.seekTo(mainPlayer.getCurrentPosition());
                    if (mainPlayer.isPlaying()) {
                        ambientPlayer.play();
                        startSyncLoop();
                    }
                }
                updateBoundsFromPlayer();
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                if (!isEnabled || isSuspended || isFrozen) return;
                if (ambientPlayer != null) {
                    ambientPlayer.seekTo(newPosition.positionMs);
                    ambientPlayer.setPlaybackSpeed(mainPlayer.getPlaybackParameters().speed);
                }
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    float aspect = (float) videoSize.width / (float) videoSize.height;
                    if (ambientVignetteOverlay != null) {
                        ambientVignetteOverlay.setVideoAspectRatio(aspect);
                    }
                    updateBoundsFromPlayer();
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
                Log.w(TAG, "Secondary ambient player error: " + error.getMessage());
                isErrorState = true;
                pauseAmbientPlayer();
                stopSyncLoop();
            }
        };

        ambientPlayer.addListener(ambientPlayerListener);
    }

    /**
     * Обновляет границы видеокарты и трансформацию подсветки на основе состояния плеера
     */
    public void updateBoundsFromPlayer() {
        if (ambientPlayerView == null || mainPlayerView == null) return;

        int sw = mainPlayerView.getWidth();
        int sh = mainPlayerView.getHeight();
        if (sw <= 0 || sh <= 0) {
            sw = context.getResources().getDisplayMetrics().widthPixels;
            sh = context.getResources().getDisplayMetrics().heightPixels;
        }

        float aspect = 16f / 9f;
        if (mainPlayer != null && mainPlayer.getVideoSize().width > 0 && mainPlayer.getVideoSize().height > 0) {
            aspect = (float) mainPlayer.getVideoSize().width / (float) mainPlayer.getVideoSize().height;
        }

        boolean isPortrait = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;

        if (ambientVignetteOverlay != null) {
            ambientVignetteOverlay.setVideoAspectRatio(aspect);
            if (!isPortrait) {
                float fullVideoW = Math.min((float) sw, (float) sh * aspect);
                float fullVideoH = Math.min((float) sh, (float) sw / aspect);
                float left = (sw - fullVideoW) / 2f;
                float top = (sh - fullVideoH) / 2f;
                ambientVignetteOverlay.setVideoBounds(left, top, left + fullVideoW, top + fullVideoH);
            }
        }

        updateTransform(currentScale, currentTranslationX, currentTranslationY, isCurrentCropped);
    }

    /**
     * Обновляет трансформацию плеера подсветки:
     * Выступает на 22% за границы основного видео и точно следует за карточкой плеера
     */
    public void updateTransform(float scale, float translationX, float translationY, boolean isCropped) {
        this.currentScale = scale;
        this.currentTranslationX = translationX;
        this.currentTranslationY = translationY;
        this.isCurrentCropped = isCropped;

        if (ambientPlayerView == null) return;

        boolean isPortrait = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;

        int viewW = ambientPlayerView.getWidth();
        int viewH = ambientPlayerView.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            viewW = context.getResources().getDisplayMetrics().widthPixels;
            viewH = context.getResources().getDisplayMetrics().heightPixels;
        }

        if (isPortrait) {
            // В портретном режиме видеоплеер расположен вверху экрана
            int targetH = viewW * 9 / 16;
            if (mainPlayer != null && mainPlayer.getVideoSize().height > 0) {
                float aspect = (float) mainPlayer.getVideoSize().width / mainPlayer.getVideoSize().height;
                targetH = (int) (viewW / aspect);
            }

            ViewGroup.LayoutParams lp = ambientPlayerView.getLayoutParams();
            if (lp != null && (lp.height != targetH || lp.width != ViewGroup.LayoutParams.MATCH_PARENT)) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = targetH;
                if (lp instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) lp).gravity = Gravity.TOP | Gravity.START;
                }
                ambientPlayerView.setLayoutParams(lp);
            }

            float ambientScale = 1.15f;
            float extraX = (viewW * (ambientScale - 1f)) / 2f;
            float extraY = (targetH * (ambientScale - 1f)) / 2f;

            ambientPlayerView.setPivotX(0f);
            ambientPlayerView.setPivotY(0f);
            ambientPlayerView.setScaleX(ambientScale);
            ambientPlayerView.setScaleY(ambientScale);
            ambientPlayerView.setTranslationX(-extraX);
            ambientPlayerView.setTranslationY(-extraY);
        } else {
            // В ландшафтном режиме плеер занимает контейнер и следует за анимациями боковых панелей
            ViewGroup.LayoutParams lp = ambientPlayerView.getLayoutParams();
            if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                if (lp instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) lp).gravity = Gravity.TOP | Gravity.START;
                }
                ambientPlayerView.setLayoutParams(lp);
            }

            // Масштаб с аккуратным выступом на 15% относительно основного плеера
            float ambientScale = scale * 1.15f;
            float extraX = (viewW * (ambientScale - scale)) / 2f;
            float extraY = (viewH * (ambientScale - scale)) / 2f;

            // Точка привязки (0, 0) строго синхронизирована с playerContainer
            ambientPlayerView.setPivotX(0f);
            ambientPlayerView.setPivotY(0f);
            ambientPlayerView.setScaleX(ambientScale);
            ambientPlayerView.setScaleY(ambientScale);

            // Смещение с компенсацией выступа: идеально ровно по центру под playerContainer
            ambientPlayerView.setTranslationX(translationX - extraX);
            ambientPlayerView.setTranslationY(translationY - extraY);
        }
    }

    public void refreshAmbientFrame() {
        mainHandler.post(() -> {
            if (ambientPlayer != null && mainPlayer != null) {
                ambientPlayer.seekTo(mainPlayer.getCurrentPosition());
            }
        });
    }

    private void showAmbientContainer(boolean animate) {
        if (ambientContainer == null) return;
        ambientContainer.removeCallbacks(null);
        if (ambientContainer.getVisibility() != View.VISIBLE) {
            ambientContainer.setVisibility(View.VISIBLE);
            ambientContainer.setAlpha(0f);
        }

        if (animate) {
            ambientContainer.animate()
                    .alpha(1.0f)
                    .setDuration(250)
                    .start();
        } else {
            ambientContainer.setAlpha(1.0f);
        }
    }

    private void hideAmbientContainer(boolean animate) {
        if (ambientContainer == null) return;
        if (animate) {
            ambientContainer.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> ambientContainer.setVisibility(View.GONE))
                    .start();
        } else {
            ambientContainer.setAlpha(0f);
            ambientContainer.setVisibility(View.GONE);
        }
    }

    private void pauseAmbientPlayer() {
        if (ambientPlayer != null) {
            try {
                ambientPlayer.pause();
            } catch (Exception ignored) {
            }
        }
    }

    public void releaseAmbientPlayer() {
        stopSyncLoop();

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
    }

    public void onConfigurationChanged() {
        mainHandler.postDelayed(() -> {
            if (isEnabled && !isSuspended && !isFrozen) {
                updateBoundsFromPlayer();
            }
        }, 120);
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
