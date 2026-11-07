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
import android.os.SystemClock;
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
 * Высокопроизводительный менеджер фоновой подсветки (Ambilight 2.0) на базе второго ExoPlayer.
 *
 * Архитектурные принципы:
 * 1. Второй легковесный ExoPlayer:
 *    - Полностью отключены аудио и субтитры.
 *    - Принудительное ограничение разрешения до 360p/480p и битрейта до 800 kbps (минимальная нагрузка на CPU/GPU).
 *    - Кэширующий DataSourceFactory для повторного использования уже загруженных сегментов.
 * 2. Устранение зависаний (Zero-Freeze Dynamic Drift Pitch):
 *    - Запрещены циклические вызовы seekTo во время воспроизведения.
 *    - Плавная подстройка скорости воспроизведения (0.92x - 1.08x) незаметно выравнивает кадры без сброса буфера.
 *    - Жесткий seekTo выполняется только при явной перемотке пользователем либо при длительном рассинхроне (>2200 мс) с дебаунсом в 3.5 сек.
 * 3. Кинематографичный вид (RenderEffect DECAL):
 *    - Мягкий GPU-блюр 65px без CLAMP-артефактов на краях.
 *    - Сбалансированная насыщенность 1.22x без выжигания деталей.
 * 4. Бесшовная адаптация к боковым панелям и портретному режиму.
 */
public class AmbientLightManager {
    private static final String TAG = "AmbientLightManager";

    private static final long SYNC_INTERVAL_MS = 160L;
    private static final long SEEK_DEBOUNCE_MS = 3500L;

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

    private long lastSeekTimeMs = 0L;

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
     * Стилизация слоя подсветки (аппаратный GPU-блюр, контраст и кинематографичная насыщенность)
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
                    // Мягкий GPU-блюр без зажима краев в полосы
                    RenderEffect blurEffect = RenderEffect.createBlurEffect(65f, 65f, Shader.TileMode.DECAL);

                    // Кинематографичная насыщенность 1.22x и подъем контраста 1.05x
                    ColorMatrix colorMatrix = new ColorMatrix();
                    colorMatrix.setSaturation(1.22f);

                    ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                            1.05f, 0f,    0f,    0f, 0f,
                            0f,    1.05f, 0f,    0f, 0f,
                            0f,    0f,    1.05f, 0f, 0f,
                            0f,    0f,    0f,    1f, 0f
                    });
                    colorMatrix.postConcat(contrastMatrix);

                    RenderEffect colorEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
                    RenderEffect combinedEffect = RenderEffect.createChainEffect(blurEffect, colorEffect);
                    ambientPlayerView.setRenderEffect(combinedEffect);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply RenderEffect blur to ambientPlayerView", e);
                }
            } else {
                ambientPlayerView.setAlpha(0.88f);
            }
        }

        if (ambientVignetteOverlay != null) {
            ambientVignetteOverlay.setVisibility(View.VISIBLE);
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
        if (mediaItem != null) {
            this.currentMediaItem = mediaItem;
        }
        if (videoUrl != null && !videoUrl.isEmpty()) {
            this.currentVideoUrl = videoUrl;
        }

        if (mainPlayer == null) {
            pauseAmbientPlayer();
            stopSyncLoop();
            hideAmbientContainer(true);
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
                isErrorState = false;
                if (!isSuspended && !isFrozen) {
                    showAmbientContainer(true);
                    ensureAmbientPlayerInitialized();
                    updateBoundsFromPlayer();
                }
            } else {
                hideAmbientContainer(true);
                pauseAmbientPlayer();
                stopSyncLoop();
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
     * Создает и конфигурирует второй легковесный плеер подсветки
     */
    private void ensureAmbientPlayerInitialized() {
        if (!isEnabled || mainPlayer == null || isErrorState || isSuspended || ambientPlayerView == null) return;

        if (ambientPlayer == null) {
            try {
                // Стабильный комфортный LoadControl с достаточным буфером для исключения голодания HLS
                DefaultLoadControl ambientLoadControl = new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                                3_000,   // minBufferMs (удерживаем 3 сек)
                                10_000,  // maxBufferMs (до 10 сек)
                                500,     // bufferForPlaybackMs
                                1_000    // bufferForPlaybackAfterRebufferMs
                        )
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

                // Принудительно выбираем 360p/480p с битрейтом до 800 kbps (идеально для размытого Ambilight)
                TrackSelectionParameters parameters = ambientPlayer.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setMaxVideoSize(640, 360)
                        .setMaxVideoBitrate(800_000)
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
                lastSeekTimeMs = SystemClock.uptimeMillis();

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
     * Синхронизатор без зависаний (Zero-Freeze Dynamic Drift Pitch):
     * - При рассинхроне < 30 мс: идеальное совпадение, стандартная скорость.
     * - При рассинхроне 30-500 мс: микро-подгонка скорости на +/-5% без сброса буфера.
     * - При рассинхроне 500-2000 мс: ускоренная подгонка на +/-12% без заиканий декодера.
     * - При рассинхроне > 2200 мс: безопасный seek с дебаунсом не чаще 1 раза в 3.5 сек.
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
                long now = SystemClock.uptimeMillis();

                if (Math.abs(driftMs) > 2200) {
                    // Крупный рассинхрон: безопасный seek с ограничением частоты
                    if (now - lastSeekTimeMs > SEEK_DEBOUNCE_MS) {
                        ambientPlayer.seekTo(mainPos);
                        ambientPlayer.setPlaybackSpeed(baseSpeed);
                        lastSeekTimeMs = now;
                    }
                } else if (driftMs < -500) {
                    // Подсветка ощутимо отстает: плавно нагоняем на +12%
                    ambientPlayer.setPlaybackSpeed(baseSpeed * 1.12f);
                } else if (driftMs < -30) {
                    // Подсветка чуть отстает: плавно нагоняем на +5%
                    ambientPlayer.setPlaybackSpeed(baseSpeed * 1.05f);
                } else if (driftMs > 500) {
                    // Подсветка ощутимо опережает: плавно притормаживаем на -12%
                    ambientPlayer.setPlaybackSpeed(baseSpeed * 0.88f);
                } else if (driftMs > 30) {
                    // Подсветка чуть опережает: плавно притормаживаем на -5%
                    ambientPlayer.setPlaybackSpeed(baseSpeed * 0.95f);
                } else {
                    // Синхронизация точна (в пределах 30 мс)
                    if (ambientPlayer.getPlaybackParameters().speed != baseSpeed) {
                        ambientPlayer.setPlaybackSpeed(baseSpeed);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in ambient sync loop", e);
            }

            if (isEnabled && !isSuspended && !isFrozen && mainPlayer != null && mainPlayer.isPlaying()) {
                mainHandler.postDelayed(this, SYNC_INTERVAL_MS);
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
                        if (Math.abs(mainPos - ambPos) > 300) {
                            ambientPlayer.seekTo(mainPos);
                            lastSeekTimeMs = SystemClock.uptimeMillis();
                        }
                        ambientPlayer.setPlaybackSpeed(mainPlayer.getPlaybackParameters().speed);
                        ambientPlayer.play();
                        startSyncLoop();
                    } else {
                        ambientPlayer.pause();
                        long mainPos = mainPlayer.getCurrentPosition();
                        long ambPos = ambientPlayer.getCurrentPosition();
                        if (Math.abs(mainPos - ambPos) > 80) {
                            ambientPlayer.seekTo(mainPos);
                        }
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
                            if (Math.abs(mainPos - ambPos) > 300) {
                                ambientPlayer.seekTo(mainPos);
                                lastSeekTimeMs = SystemClock.uptimeMillis();
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
                    lastSeekTimeMs = SystemClock.uptimeMillis();
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
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        ambientPlayer.seekTo(newPosition.positionMs);
                        ambientPlayer.setPlaybackSpeed(mainPlayer.getPlaybackParameters().speed);
                        lastSeekTimeMs = SystemClock.uptimeMillis();
                    }
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
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.w(TAG, "Secondary ambient player error: " + error.getMessage());
                isErrorState = true;
                pauseAmbientPlayer();
                stopSyncLoop();

                // Мягкое восстановление фонового плеера через 2.5 сек, если основной плеер продолжает играть
                mainHandler.postDelayed(() -> {
                    if (isEnabled && !isSuspended && !isFrozen && mainPlayer != null && mainPlayer.isPlaying()) {
                        isErrorState = false;
                        prepareAmbientMedia();
                    }
                }, 2500);
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
                float left = (sw - fullVideoW) * 0.5f;
                float top = (sh - fullVideoH) * 0.5f;
                ambientVignetteOverlay.setVideoBounds(left, top, left + fullVideoW, top + fullVideoH);
            }
        }

        updateTransform(currentScale, currentTranslationX, currentTranslationY, isCurrentCropped);
    }

    /**
     * Обновляет трансформацию плеера подсветки:
     * Выступает на 18% за границы основного видео и строго следует за карточкой плеера
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
                float aspect = (float) mainPlayer.getVideoSize().width / (float) mainPlayer.getVideoSize().height;
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
            float extraX = (viewW * (ambientScale - 1f)) * 0.5f;
            float extraY = (targetH * (ambientScale - 1f)) * 0.5f;

            ambientPlayerView.setPivotX(0f);
            ambientPlayerView.setPivotY(0f);
            ambientPlayerView.setScaleX(ambientScale);
            ambientPlayerView.setScaleY(ambientScale);
            ambientPlayerView.setTranslationX(-extraX);
            ambientPlayerView.setTranslationY(-extraY);
        } else {
            // В ландшафтном режиме задаем размеры плеера подсветки равными точным размерам видеокадра
            float aspect = 16f / 9f;
            if (mainPlayer != null && mainPlayer.getVideoSize().width > 0 && mainPlayer.getVideoSize().height > 0) {
                aspect = (float) mainPlayer.getVideoSize().width / (float) mainPlayer.getVideoSize().height;
            }

            int sw = mainPlayerView.getWidth();
            int sh = mainPlayerView.getHeight();
            if (sw <= 0 || sh <= 0) {
                sw = context.getResources().getDisplayMetrics().widthPixels;
                sh = context.getResources().getDisplayMetrics().heightPixels;
            }

            float fullVideoW = Math.min((float) sw, (float) sh * aspect);
            float fullVideoH = Math.min((float) sh, (float) sw / aspect);

            int targetW = Math.max(1, Math.round(fullVideoW));
            int targetH = Math.max(1, Math.round(fullVideoH));

            ViewGroup.LayoutParams lp = ambientPlayerView.getLayoutParams();
            if (lp != null && (lp.width != targetW || lp.height != targetH)) {
                lp.width = targetW;
                lp.height = targetH;
                if (lp instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) lp).gravity = Gravity.TOP | Gravity.START;
                }
                ambientPlayerView.setLayoutParams(lp);
            }

            float videoLeft = (sw - fullVideoW) * 0.5f;
            float videoTop = (sh - fullVideoH) * 0.5f;

            // Масштаб с ровным выступом на +20% относительно основного видео
            float ambientScale = scale * 1.20f;
            float extraX = (targetW * (ambientScale - scale)) * 0.5f;
            float extraY = (targetH * (ambientScale - scale)) * 0.5f;

            ambientPlayerView.setPivotX(0f);
            ambientPlayerView.setPivotY(0f);
            ambientPlayerView.setScaleX(ambientScale);
            ambientPlayerView.setScaleY(ambientScale);

            // Точное центрирование плеера подсветки строго под карточкой видео
            ambientPlayerView.setTranslationX(translationX + videoLeft * scale - extraX);
            ambientPlayerView.setTranslationY(translationY + videoTop * scale - extraY);
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
                    .setDuration(280)
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
                    .setDuration(220)
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
            } catch (Exception ignored) {
            }
            ambientPlayer = null;
        }

        if (ambientPlayerView != null) {
            ambientPlayerView.setPlayer(null);
        }
        isPrepared = false;
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
