package com.example.animelib.managers;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.example.animelib.R;
import com.example.animelib.ui.AmbientLightView;
import com.example.animelib.ui.AmbientVignetteOverlayView;
import com.example.animelib.ui.RoundedCornerFrameLayout;
import com.example.animelib.util.FastBlurUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Высокопроизводительный менеджер фоновой подсветки (Edge-Diffused Ambilight).
 *
 * Архитектурные принципы:
 * 1. Идеальная синхронизация без задержек (Direct Texture Sampling):
 *    - Кадры берутся напрямую из аппаратного TextureView основного плеера (~0.2 мс).
 * 2. Отсутствие дублирования видеоконтента:
 *    - FastBlurUtils извлекает только периметральные полосы краёв и строит 9-зонное
 *      поле рассеивания (60x48), растягивающееся ровно на ширину пустых областей плеера.
 * 3. Единый источник границ (AmbientLightView + AmbientVignetteOverlayView):
 *    - Точно учитывает resizeMode (FIT / FILL / ZOOM / FIXED_*), портретный статус-бар,
 *      боковую панель (availRight) и автоматическое обновление при изменении layout.
 */
public class AmbientLightManager {
    private static final String TAG = "AmbientLightManager";

    private static final int SAMPLE_WIDTH = 36;
    private static final int SAMPLE_HEIGHT = 20;
    private static final int TOTAL_PIXELS = SAMPLE_WIDTH * SAMPLE_HEIGHT;
    private static final int GLOW_TOTAL_PIXELS = FastBlurUtils.GLOW_WIDTH * FastBlurUtils.GLOW_HEIGHT;
    private static final long SAMPLING_INTERVAL_MS = 42L; // ~24 FPS (оптимальный баланс плавности и нагрузки)
    private static final long TRANSITION_SAFETY_TIMEOUT_MS = 450L;

    private final Context context;
    private final PlayerView mainPlayerView;
    private final View ambientContainer;
    private final AmbientLightView ambientLightView;
    private final AmbientVignetteOverlayView ambientVignetteOverlay;
    private final PlayerView ambientPlayerViewStub; // Сохранен для совместимости сигнатур
    private final Handler mainHandler;

    private final FastBlurUtils fastBlurUtils;
    private final ExecutorService backgroundExecutor;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final Object glowBufferLock = new Object();

    private Bitmap sampleBitmap;
    private final int[] rawPixels;
    private final int[] workerPixels;
    private final int[] workerGlowPixels;
    private final int[] uiGlowPixels;

    private ExoPlayer mainPlayer;
    private Player.Listener mainPlayerListener;
    private TextureView cachedTextureView;
    private final View.OnLayoutChangeListener playerLayoutChangeListener;

    private volatile boolean isEnabled = true;
    private boolean isSuspended = false;
    private boolean isFrozen = false;
    private boolean isSamplingRunning = false;
    private boolean isPanelTransitioning = false;
    private final Runnable resetTransitionSafetyRunnable = () -> setPanelTransitioning(false);

    private float currentScale = 1.0f;
    private float currentTranslationX = 0f;
    private float currentTranslationY = 0f;
    private boolean isCurrentCropped = false;
    private float currentAvailRight = -1f;

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
        this.ambientPlayerViewStub = ambientPlayerView;
        this.mainHandler = new Handler(Looper.getMainLooper());

        if (ambientContainer != null) {
            this.ambientLightView = ambientContainer.findViewById(R.id.ambientLightView);
            this.ambientVignetteOverlay = (ambientVignetteOverlay != null)
                    ? ambientVignetteOverlay
                    : ambientContainer.findViewById(R.id.ambientVignetteOverlay);
        } else {
            this.ambientLightView = null;
            this.ambientVignetteOverlay = ambientVignetteOverlay;
        }

        this.fastBlurUtils = new FastBlurUtils(SAMPLE_WIDTH, SAMPLE_HEIGHT, 4);
        this.rawPixels = new int[TOTAL_PIXELS];
        this.workerPixels = new int[TOTAL_PIXELS];
        this.workerGlowPixels = new int[GLOW_TOTAL_PIXELS];
        this.uiGlowPixels = new int[GLOW_TOTAL_PIXELS];
        this.sampleBitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888);

        this.backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "AmbientLight-Worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });

        this.playerLayoutChangeListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                updateBoundsFromPlayer();
            }
        };
        if (this.mainPlayerView != null) {
            this.mainPlayerView.addOnLayoutChangeListener(playerLayoutChangeListener);
        }

        setupAmbientViewStyle();
    }

    private void setupAmbientViewStyle() {
        if (ambientLightView != null) {
            ambientLightView.setVisibility(View.VISIBLE);
        }

        // Кривая затухания встроена в сетку AmbientLightView (1 GPU-проход без овердрава и пересборки шейдеров)
        if (ambientVignetteOverlay != null) {
            ambientVignetteOverlay.setVisibility(View.GONE);
        }

        // Заглушка второго плеера отключается — декодирование теперь на 100% единое
        if (ambientPlayerViewStub != null) {
            ambientPlayerViewStub.setVisibility(View.GONE);
            ambientPlayerViewStub.setPlayer(null);
        }
    }

    public void setDataSourceFactory(DataSource.Factory dataSourceFactory) {
        // Поддерживается для обратной совместимости вызовов из VideoPlayerActivity
    }

    public void setPlayer(@Nullable ExoPlayer mainPlayer) {
        setPlayer(mainPlayer, null, null);
    }

    public void setPlayer(@Nullable ExoPlayer mainPlayer, @Nullable MediaItem mediaItem, @Nullable String videoUrl) {
        if (this.mainPlayer != null && mainPlayerListener != null) {
            this.mainPlayer.removeListener(mainPlayerListener);
        }

        this.mainPlayer = mainPlayer;
        this.cachedTextureView = null;

        if (mainPlayer == null) {
            stopSamplingLoop();
            hideAmbientContainer(true);
            return;
        }

        setupMainPlayerListener();
        updateBoundsFromPlayer();

        if (isEnabled && !isSuspended && !isFrozen) {
            showAmbientContainer(true);

            if (mainPlayer.isPlaying()) {
                startSamplingLoop();
            } else {
                captureAndProcessFrame();
            }
        }
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        mainHandler.post(() -> {
            if (enabled) {
                if (!isSuspended && !isFrozen) {
                    showAmbientContainer(true);
                    updateBoundsFromPlayer();
                    if (mainPlayer != null && mainPlayer.isPlaying()) {
                        startSamplingLoop();
                    } else {
                        captureAndProcessFrame();
                    }
                }
            } else {
                stopSamplingLoop();
                hideAmbientContainer(true);
            }
        });
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void suspend() {
        this.isSuspended = true;
        mainHandler.post(() -> {
            stopSamplingLoop();
            hideAmbientContainer(false);
            if (ambientLightView != null) {
                ambientLightView.suspend();
            }
        });
    }

    public void freeze() {
        this.isFrozen = true;
        mainHandler.post(() -> {
            stopSamplingLoop();
            hideAmbientContainer(false);
        });
    }

    public void unfreeze() {
        this.isFrozen = false;
        mainHandler.post(() -> {
            if (isEnabled && !isSuspended) {
                showAmbientContainer(true);
                updateBoundsFromPlayer();
                if (ambientLightView != null) {
                    ambientLightView.resume();
                }
                if (mainPlayer != null && mainPlayer.isPlaying()) {
                    startSamplingLoop();
                } else {
                    captureAndProcessFrame();
                }
            }
        });
    }

    public void resume() {
        this.isSuspended = false;
        this.isFrozen = false;
        mainHandler.post(() -> {
            if (isEnabled) {
                showAmbientContainer(true);
                updateBoundsFromPlayer();
                if (ambientLightView != null) {
                    ambientLightView.resume();
                }
                if (mainPlayer != null && mainPlayer.isPlaying()) {
                    startSamplingLoop();
                } else {
                    captureAndProcessFrame();
                }
            }
        });
    }

    private void startSamplingLoop() {
        if (isSamplingRunning) return;
        isSamplingRunning = true;
        mainHandler.removeCallbacks(sampleRunnable);
        mainHandler.post(sampleRunnable);
    }

    private void stopSamplingLoop() {
        isSamplingRunning = false;
        mainHandler.removeCallbacks(sampleRunnable);
    }

    public void setPanelTransitioning(boolean transitioning) {
        if (this.isPanelTransitioning == transitioning) return;
        this.isPanelTransitioning = transitioning;
        mainHandler.removeCallbacks(resetTransitionSafetyRunnable);
        if (transitioning) {
            // Страховочный таймер на случай прерванной анимации
            mainHandler.postDelayed(resetTransitionSafetyRunnable, TRANSITION_SAFETY_TIMEOUT_MS);
        } else if (isEnabled && !isSuspended && !isFrozen && mainPlayer != null && mainPlayer.isPlaying()) {
            captureAndProcessFrame();
            startSamplingLoop();
        }
    }

    private final Runnable sampleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isEnabled || isSuspended || isFrozen || mainPlayer == null) {
                isSamplingRunning = false;
                return;
            }

            if (!mainPlayer.isPlaying()) {
                isSamplingRunning = false;
                return;
            }

            if (isPanelTransitioning) {
                // Во время анимации шторки не читаем кадры с GPU, чтобы не ронять FPS интерфейса
                mainHandler.postDelayed(this, SAMPLING_INTERVAL_MS);
                return;
            }

            TextureView tv = getTextureView();
            if (tv == null || !tv.isAvailable()) {
                // Surface еще создается или не готов к выборке, повторяем через 45 мс
                mainHandler.postDelayed(this, 45L);
                return;
            }

            captureAndProcessFrame();

            if (isEnabled && !isSuspended && !isFrozen && mainPlayer != null && mainPlayer.isPlaying()) {
                mainHandler.postDelayed(this, SAMPLING_INTERVAL_MS);
            } else {
                isSamplingRunning = false;
            }
        }
    };

    /**
     * Захватывает текущий кадр из TextureView и строит краевое поле рассеивания на фоне.
     */
    private void captureAndProcessFrame() {
        if (!isEnabled || isSuspended || isFrozen) return;

        TextureView tv = getTextureView();
        if (tv == null || !tv.isAvailable()) {
            return;
        }

        try {
            if (sampleBitmap == null || sampleBitmap.isRecycled()) {
                sampleBitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888);
            }

            // Аппаратное быстрое чтение текущего видеокадра прямо в микро-битмап (~0.2 мс)
            tv.getBitmap(sampleBitmap);

            if (sampleBitmap != null) {
                sampleBitmap.getPixels(rawPixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);

                if (isProcessing.compareAndSet(false, true)) {
                    System.arraycopy(rawPixels, 0, workerPixels, 0, TOTAL_PIXELS);
                    backgroundExecutor.execute(() -> {
                        try {
                            fastBlurUtils.buildEdgeGlowField(workerPixels, workerGlowPixels);
                            synchronized (glowBufferLock) {
                                System.arraycopy(workerGlowPixels, 0, uiGlowPixels, 0, GLOW_TOTAL_PIXELS);
                            }
                            mainHandler.post(() -> {
                                if (ambientLightView != null && isEnabled && !isSuspended && !isFrozen) {
                                    synchronized (glowBufferLock) {
                                        ambientLightView.updateSamplePixels(
                                                uiGlowPixels,
                                                FastBlurUtils.GLOW_WIDTH,
                                                FastBlurUtils.GLOW_HEIGHT
                                        );
                                    }
                                }
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing ambient frame in worker", e);
                        } finally {
                            isProcessing.set(false);
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sampling frame from TextureView", e);
        }
    }

    @Nullable
    private TextureView getTextureView() {
        if (cachedTextureView != null && cachedTextureView.isAvailable()) {
            return cachedTextureView;
        }
        if (mainPlayerView != null) {
            View surfaceView = mainPlayerView.getVideoSurfaceView();
            if (surfaceView instanceof TextureView) {
                cachedTextureView = (TextureView) surfaceView;
                return cachedTextureView;
            }
            cachedTextureView = findTextureView(mainPlayerView);
            return cachedTextureView;
        }
        return null;
    }

    @Nullable
    private TextureView findTextureView(@Nullable ViewGroup root) {
        if (root == null) return null;
        int count = root.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextureView) {
                return (TextureView) child;
            } else if (child instanceof ViewGroup) {
                TextureView found = findTextureView((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void setupMainPlayerListener() {
        if (mainPlayer == null) return;

        mainPlayerListener = new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (!isEnabled || isSuspended || isFrozen) return;
                if (isPlaying) {
                    showAmbientContainer(true);
                    startSamplingLoop();
                } else {
                    // При паузе захватываем кадр один раз и останавливаем цикл (0% нагрузки при паузе)
                    captureAndProcessFrame();
                    stopSamplingLoop();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (!isEnabled || isSuspended || isFrozen) return;
                if (playbackState == Player.STATE_READY) {
                    if (mainPlayer.getPlayWhenReady()) {
                        showAmbientContainer(true);
                        startSamplingLoop();
                    } else {
                        captureAndProcessFrame();
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    stopSamplingLoop();
                    hideAmbientContainer(true);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                updateBoundsFromPlayer();
                if (!isEnabled || isSuspended || isFrozen) return;
                if (fastBlurUtils != null) {
                    fastBlurUtils.resetHistory();
                }
                captureAndProcessFrame();
                if (mainPlayer != null && mainPlayer.isPlaying()) {
                    startSamplingLoop();
                }
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                                @NonNull Player.PositionInfo newPosition,
                                                int reason) {
                if (!isEnabled || isSuspended || isFrozen) return;
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    if (fastBlurUtils != null) {
                        fastBlurUtils.resetHistory();
                    }
                    mainHandler.postDelayed(AmbientLightManager.this::captureAndProcessFrame, 35L);
                }
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    updateBoundsFromPlayer();
                }
            }
        };

        mainPlayer.addListener(mainPlayerListener);
    }

    public void updateBoundsFromPlayer() {
        updateTransform(currentScale, currentTranslationX, currentTranslationY, isCurrentCropped, currentAvailRight);
    }

    public void updateTransform(float scale, float translationX, float translationY, boolean isCropped) {
        updateTransform(scale, translationX, translationY, isCropped, -1f);
    }

    public void updateTransform(float scale, float translationX, float translationY, boolean isCropped, float availRight) {
        this.currentScale = scale;
        this.currentTranslationX = translationX;
        this.currentTranslationY = translationY;
        this.isCurrentCropped = isCropped;
        this.currentAvailRight = availRight;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int rootW = (ambientContainer != null && ambientContainer.getWidth() > 0)
                ? ambientContainer.getWidth()
                : dm.widthPixels;
        int rootH = (ambientContainer != null && ambientContainer.getHeight() > 0)
                ? ambientContainer.getHeight()
                : dm.heightPixels;

        if (rootW <= 0 || rootH <= 0) return;

        boolean isPortrait = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT
                || rootH > rootW;

        float aspect = 16f / 9f;
        if (mainPlayer != null) {
            VideoSize vs = mainPlayer.getVideoSize();
            if (vs.width > 0 && vs.height > 0) {
                float pixelRatio = vs.pixelWidthHeightRatio > 0f ? vs.pixelWidthHeightRatio : 1f;
                aspect = ((float) vs.width * pixelRatio) / (float) vs.height;
            }
        }

        int resizeMode = (mainPlayerView != null)
                ? mainPlayerView.getResizeMode()
                : AspectRatioFrameLayout.RESIZE_MODE_FIT;

        float vL, vT, vR, vB;
        float aL, aT, aR, aB;

        if (isPortrait) {
            View playerContainer = (mainPlayerView != null && mainPlayerView.getParent() instanceof View)
                    ? (View) mainPlayerView.getParent()
                    : mainPlayerView;

            float actualLeft = 0f;
            float actualTop = getStatusBarHeightFallback();
            float containerW = (float) rootW;
            float containerH = rootW * 9f / 16f;

            if (playerContainer != null) {
                if (playerContainer.getWidth() > 0) {
                    containerW = playerContainer.getWidth();
                }
                if (playerContainer.getHeight() > 0) {
                    containerH = playerContainer.getHeight();
                }
                if (ambientContainer != null) {
                    int[] pLoc = new int[2];
                    int[] aLoc = new int[2];
                    playerContainer.getLocationOnScreen(pLoc);
                    ambientContainer.getLocationOnScreen(aLoc);
                    if (pLoc[1] > 0 || aLoc[1] > 0) {
                        actualLeft = pLoc[0] - aLoc[0];
                        actualTop = pLoc[1] - aLoc[1];
                    }
                }
            }

            float fitW = computeFittedWidth(containerW, containerH, aspect, resizeMode);
            float fitH = computeFittedHeight(containerW, containerH, aspect, resizeMode);

            float localVL = clamp((containerW - fitW) * 0.5f, 0f, containerW);
            float localVT = clamp((containerH - fitH) * 0.5f, 0f, containerH);
            float localVR = clamp((containerW + fitW) * 0.5f, localVL, containerW);
            float localVB = clamp((containerH + fitH) * 0.5f, localVT, containerH);

            if (playerContainer instanceof RoundedCornerFrameLayout) {
                ((RoundedCornerFrameLayout) playerContainer).setVideoShadowBounds(localVL, localVT, localVR, localVB);
            }

            vL = actualLeft + localVL;
            vT = actualTop + localVT;
            vR = actualLeft + localVR;
            vB = actualTop + localVB;

            aL = 0f;
            aT = 0f;
            aR = (float) rootW;
            aB = Math.min((float) rootH, actualTop + containerH);
        } else {
            if (mainPlayerView != null && mainPlayerView.getParent() instanceof RoundedCornerFrameLayout) {
                ((RoundedCornerFrameLayout) mainPlayerView.getParent()).clearVideoShadowBounds();
            }

            float viewW = (mainPlayerView != null && mainPlayerView.getWidth() > 0)
                    ? mainPlayerView.getWidth()
                    : rootW;
            float viewH = (mainPlayerView != null && mainPlayerView.getHeight() > 0)
                    ? mainPlayerView.getHeight()
                    : rootH;

            float fitW = computeFittedWidth(viewW, viewH, aspect, resizeMode);
            float fitH = computeFittedHeight(viewW, viewH, aspect, resizeMode);

            // Ограничиваем границами контейнера плеера (важно для RESIZE_MODE_ZOOM со шторкой)
            float unscaledL = clamp((viewW - fitW) * 0.5f, 0f, viewW);
            float unscaledT = clamp((viewH - fitH) * 0.5f, 0f, viewH);
            float unscaledR = clamp((viewW + fitW) * 0.5f, unscaledL, viewW);
            float unscaledB = clamp((viewH + fitH) * 0.5f, unscaledT, viewH);

            vL = translationX + unscaledL * scale;
            vT = translationY + unscaledT * scale;
            vR = translationX + unscaledR * scale;
            vB = translationY + unscaledB * scale;

            // Не обрезаем правый край по левой границе шторки (dragZone), чтобы подсветка
            // свободно уходила под скруглённую карточку панели без вертикального шва.
            aL = 0f;
            aT = 0f;
            aR = (float) rootW;
            aB = (float) rootH;
        }

        if (ambientLightView != null) {
            ambientLightView.setVideoAspectRatio(aspect);
            ambientLightView.setAvailableBounds(aL, aT, aR, aB);
            ambientLightView.setCustomVideoBounds(vL, vT, vR, vB);
        }
    }

    private static float computeFittedWidth(float boxW, float boxH, float aspect, int resizeMode) {
        switch (resizeMode) {
            case AspectRatioFrameLayout.RESIZE_MODE_FILL:
            case AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH:
                return boxW;
            case AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT:
                return boxH * aspect;
            case AspectRatioFrameLayout.RESIZE_MODE_ZOOM:
                return Math.max(boxW, boxH * aspect);
            case AspectRatioFrameLayout.RESIZE_MODE_FIT:
            default:
                return Math.min(boxW, boxH * aspect);
        }
    }

    private static float computeFittedHeight(float boxW, float boxH, float aspect, int resizeMode) {
        switch (resizeMode) {
            case AspectRatioFrameLayout.RESIZE_MODE_FILL:
            case AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT:
                return boxH;
            case AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH:
                return boxW / aspect;
            case AspectRatioFrameLayout.RESIZE_MODE_ZOOM:
                return Math.max(boxH, boxW / aspect);
            case AspectRatioFrameLayout.RESIZE_MODE_FIT:
            default:
                return Math.min(boxH, boxW / aspect);
        }
    }

    private int getStatusBarHeightFallback() {
        int sbHeight = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ambientContainer != null) {
            WindowInsets insets = ambientContainer.getRootWindowInsets();
            if (insets != null) {
                sbHeight = insets.getInsets(WindowInsets.Type.statusBars()).top;
            }
        }
        if (sbHeight <= 0) {
            int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                sbHeight = context.getResources().getDimensionPixelSize(resourceId);
            }
        }
        return sbHeight;
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    public void refreshAmbientFrame() {
        mainHandler.post(() -> {
            if (isEnabled && !isSuspended && !isFrozen) {
                captureAndProcessFrame();
            }
        });
    }

    private void showAmbientContainer(boolean animate) {
        if (ambientContainer == null) return;
        ambientContainer.animate().cancel();
        if (ambientContainer.getVisibility() != View.VISIBLE) {
            ambientContainer.setVisibility(View.VISIBLE);
            ambientContainer.setAlpha(0f);
        }

        if (animate) {
            ambientContainer.animate()
                    .alpha(1.0f)
                    .setDuration(280)
                    .withLayer()
                    .start();
        } else {
            ambientContainer.setAlpha(1.0f);
        }
    }

    private void hideAmbientContainer(boolean animate) {
        if (ambientContainer == null) return;
        ambientContainer.animate().cancel();
        if (animate) {
            ambientContainer.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withLayer()
                    .withEndAction(() -> ambientContainer.setVisibility(View.GONE))
                    .start();
        } else {
            ambientContainer.setAlpha(0f);
            ambientContainer.setVisibility(View.GONE);
        }
    }

    public void releaseAmbientPlayer() {
        stopSamplingLoop();
        if (mainPlayer != null && mainPlayerListener != null) {
            mainPlayer.removeListener(mainPlayerListener);
            mainPlayerListener = null;
        }
        cachedTextureView = null;
        if (fastBlurUtils != null) {
            fastBlurUtils.resetHistory();
        }
    }

    public void cleanup() {
        mainHandler.removeCallbacks(resetTransitionSafetyRunnable);
        releaseAmbientPlayer();
        if (mainPlayerView != null && playerLayoutChangeListener != null) {
            mainPlayerView.removeOnLayoutChangeListener(playerLayoutChangeListener);
        }
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
        if (sampleBitmap != null && !sampleBitmap.isRecycled()) {
            sampleBitmap.recycle();
            sampleBitmap = null;
        }
    }

    public void onConfigurationChanged() {
        mainHandler.postDelayed(() -> {
            updateBoundsFromPlayer();
            if (isEnabled && !isSuspended && !isFrozen) {
                captureAndProcessFrame();
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
