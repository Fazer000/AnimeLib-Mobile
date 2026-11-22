package com.example.animelib.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Централизованный высокопроизводительный движок активного матового стекла
 * (зернистое размытие реального видеопотока в реальном времени) для HUD-элементов плеера.
 *
 * Архитектура оптимизации:
 * 1. Единый общий буфер захвата 112x63 (~28 КБ) на весь плеер — один TextureView.getBitmap()
 *    и один 2-проходный O(1) скользящий сепарабельный блюр (< 0.15 мс на кадр) для всех плашек сразу.
 * 2. Нулевая нагрузка в простое: если ни одна стеклянная плашка/чип не видна на экране,
 *    Choreographer-цикл мгновенно останавливается (0% CPU/GPU).
 * 3. Дедупликация по SurfaceTexture.getTimestamp() + лимит 30 FPS: на паузе и между
 *    24 FPS кадрами аниме захват и размытие полностью пропускаются.
 * 4. Плавный 60/120 FPS параллакс при скролле карусели серий или анимации плашек
 *    даже на паузе без повторного размытия кадра.
 */
public class PlayerBackdropBlurManager {

    public static final int BLUR_WIDTH = 128;
    public static final int BLUR_HEIGHT = 72;
    public static final int COMPOSITE_WIDTH = 256;
    public static final int COMPOSITE_HEIGHT = 128;

    private static final int BLUR_RADIUS = 1;
    private static final int BLUR_DIV = BLUR_RADIUS * 2 + 1; // 3
    private static final int BLUR_INV_DIV = 21846; // ceil(65536 / 3) -> exact [0..255]
    private static final int GRAIN_SIZE = 128;
    private static final long MIN_CAPTURE_INTERVAL_MS = 40L; // ~25 FPS (matches 24 FPS anime)
    private static final long SCROLL_CAPTURE_INTERVAL_MS = 100L; // Avoid GPU readback stalls during 120Hz scroll/drag

    private static volatile Bitmap sSharedGrainBitmap;

    private final Context context;
    private final PlayerView playerView;
    private final float density;

    @Nullable
    private AmbientLightView ambientLightView;

    private Bitmap sampleBitmap;
    private Bitmap blurredBitmap;
    private Bitmap compositeBitmap;
    private Canvas compositeCanvas;
    private final Paint compositeVideoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF tmpVideoDstRect = new RectF();
    private final int[] bufA;
    private final int[] bufB;

    private final List<WeakReference<FrostedGlassDrawable>> targets = new ArrayList<>();
    private final int[] tmpVideoLoc = new int[2];

    private TextureView cachedTextureView;
    private long lastSurfaceTimestamp = Long.MIN_VALUE;
    private long lastCaptureUptimeMs = 0L;
    private long frameGeneration = 0L;
    private boolean hasCapturedFrame = false;
    private boolean hasCompositeFrame = false;

    private float videoScreenX = 0f;
    private float videoScreenY = 0f;
    private float videoScreenW = 0f;
    private float videoScreenH = 0f;

    private float backdropScreenX = 0f;
    private float backdropScreenY = 0f;
    private float backdropScreenW = 0f;
    private float backdropScreenH = 0f;

    // Последнее скомпозированное состояние (для избежания лишних перерисовок буфера)
    private long lastComposedAmbientGen = -1L;
    private float lastComposedAmbientAlpha = -1f;
    private float lastComposedVideoX = Float.NaN;
    private float lastComposedVideoY = Float.NaN;
    private float lastComposedVideoW = Float.NaN;
    private float lastComposedVideoH = Float.NaN;
    private float lastComposedBackdropX = Float.NaN;
    private float lastComposedBackdropY = Float.NaN;
    private float lastComposedBackdropW = Float.NaN;
    private float lastComposedBackdropH = Float.NaN;

    private boolean isLoopRunning = false;
    private boolean isPaused = false;
    private boolean isReleased = false;
    private boolean forceNextCapture = true;
    private boolean isCarouselScrolling = false;
    private boolean isVerticalDragging = false;
    @Nullable
    private WeakReference<RecyclerView> attachedScrollRvRef;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!isLoopRunning || isPaused || isReleased) {
                isLoopRunning = false;
                return;
            }

            boolean anyVisible = false;
            Iterator<WeakReference<FrostedGlassDrawable>> it = targets.iterator();
            while (it.hasNext()) {
                FrostedGlassDrawable drawable = it.next().get();
                if (drawable == null) {
                    it.remove();
                    continue;
                }
                View host = drawable.getHostView();
                if (host == null) {
                    it.remove();
                    continue;
                }
                if (isViewEffectivelyVisible(host)) {
                    anyVisible = true;
                }
            }

            if (!anyVisible) {
                isLoopRunning = false;
                return;
            }

            updateVideoBounds();
            boolean videoUpdated = captureAndBlurIfNeeded(false);
            boolean compositeUpdated = composeUnifiedBackdropIfNeeded(videoUpdated);

            for (int i = 0, size = targets.size(); i < size; i++) {
                FrostedGlassDrawable drawable = targets.get(i).get();
                if (drawable == null) continue;
                View host = drawable.getHostView();
                if (host != null && host.getLayerType() != View.LAYER_TYPE_HARDWARE && isViewEffectivelyVisible(host)) {
                    if (compositeUpdated || drawable.hasGeometryChangedOnScreen(
                            backdropScreenX, backdropScreenY, backdropScreenW, backdropScreenH)) {
                        host.invalidate();
                    }
                }
            }

            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public PlayerBackdropBlurManager(@NonNull Context context, @NonNull PlayerView playerView) {
        this.context = context.getApplicationContext();
        this.playerView = playerView;
        this.density = context.getResources().getDisplayMetrics().density;

        int pixelCount = BLUR_WIDTH * BLUR_HEIGHT;
        this.bufA = new int[pixelCount];
        this.bufB = new int[pixelCount];
        this.sampleBitmap = Bitmap.createBitmap(BLUR_WIDTH, BLUR_HEIGHT, Bitmap.Config.ARGB_8888);
        this.blurredBitmap = Bitmap.createBitmap(BLUR_WIDTH, BLUR_HEIGHT, Bitmap.Config.ARGB_8888);
        this.compositeBitmap = Bitmap.createBitmap(COMPOSITE_WIDTH, COMPOSITE_HEIGHT, Bitmap.Config.ARGB_8888);
        this.compositeCanvas = new Canvas(this.compositeBitmap);
        ensureSharedGrainBitmap();
    }

    /**
     * Подключает View фоновой подсветки (AmbientLightView), чтобы матовое стекло
     * над областями подсветки (letterbox/pillarbox) брало точные цвета и затухание подсветки,
     * а не растягивало крайний пиксель видео.
     */
    public void setAmbientLightView(@Nullable AmbientLightView ambientView) {
        if (this.ambientLightView == ambientView) return;
        if (this.ambientLightView != null) {
            this.ambientLightView.setOnContentUpdatedListener(null);
        }
        this.ambientLightView = ambientView;
        this.lastComposedAmbientGen = -1L;
        this.lastComposedAmbientAlpha = -1f;
        if (ambientView != null && !isReleased) {
            ambientView.setOnContentUpdatedListener(this::onAmbientContentUpdated);
        }
        if (!isPaused && !isReleased) {
            invalidateVisibleTargets();
        }
    }

    private void onAmbientContentUpdated() {
        if (isPaused || isReleased) return;
        if (isCarouselScrolling || isVerticalDragging) {
            return;
        }
        if (hasAnyVisibleTarget()) {
            invalidateVisibleTargets();
            ensureLoopStarted();
        }
    }

    public void setVerticalDragActive(boolean active) {
        if (this.isVerticalDragging == active) return;
        this.isVerticalDragging = active;
        if (!active && !isPaused && !isReleased) {
            invalidateVisibleTargets();
            ensureLoopStarted();
        }
    }

    private boolean hasAnyVisibleTarget() {
        for (int i = targets.size() - 1; i >= 0; i--) {
            FrostedGlassDrawable drawable = targets.get(i).get();
            if (drawable == null) {
                targets.remove(i);
                continue;
            }
            View host = drawable.getHostView();
            if (host != null && isViewEffectivelyVisible(host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Применяет активное матовое зернистое стекло в форме капсулы (pill, radius = h/2),
     * аккуратно сохраняя текущие padding вьюшки.
     */
    public void applyPillGlass(@Nullable View view) {
        applyStaticGlass(view, 0f, true);
    }

    /**
     * Применяет активное матовое зернистое стекло с заданным скруглением углов в dp.
     */
    public void applyRoundedGlass(@Nullable View view, float cornerRadiusDp) {
        applyStaticGlass(view, cornerRadiusDp, false);
    }

    private void applyStaticGlass(@Nullable View view, float cornerRadiusDp, boolean isPill) {
        if (view == null || isReleased) return;
        Object existing = view.getTag(R.id.tag_frosted_glass_drawable);
        if (existing instanceof FrostedGlassDrawable) {
            return;
        }
        int padL = view.getPaddingLeft();
        int padT = view.getPaddingTop();
        int padR = view.getPaddingRight();
        int padB = view.getPaddingBottom();

        FrostedGlassDrawable drawable = new FrostedGlassDrawable(
                view, this, dpToPx(cornerRadiusDp), isPill, false);
        registerTarget(drawable);
        view.setTag(R.id.tag_frosted_glass_drawable, drawable);
        view.setBackground(drawable);
        view.setPadding(padL, padT, padR, padB);
    }

    /**
     * Применяет или обновляет состояние (normal / selected) интерактивного матового стекла
     * с Material Ripple (для горизонтальных чипов серий и кнопки поиска в ландшафте).
     * При повторном вызове на той же View не создаёт новых объектов (0 аллокаций при скролле).
     */
    public void applyOrUpdateInteractiveGlass(@Nullable View view, float cornerRadiusDp, boolean selected) {
        if (view == null || isReleased) return;

        Object existingGlass = view.getTag(R.id.tag_frosted_glass_drawable);
        Object existingRipple = view.getTag(R.id.tag_frosted_glass_ripple);
        if (existingGlass instanceof FrostedGlassDrawable && existingRipple instanceof RippleDrawable) {
            FrostedGlassDrawable glass = (FrostedGlassDrawable) existingGlass;
            RippleDrawable ripple = (RippleDrawable) existingRipple;
            glass.setSelectedState(selected);
            ripple.setColor(ColorStateList.valueOf(getRippleColor(selected)));
            if (view.getBackground() != ripple) {
                int padL = view.getPaddingLeft();
                int padT = view.getPaddingTop();
                int padR = view.getPaddingRight();
                int padB = view.getPaddingBottom();
                view.setBackground(ripple);
                view.setPadding(padL, padT, padR, padB);
            }
            return;
        }

        int padL = view.getPaddingLeft();
        int padT = view.getPaddingTop();
        int padR = view.getPaddingRight();
        int padB = view.getPaddingBottom();

        float radiusPx = dpToPx(cornerRadiusDp);
        FrostedGlassDrawable glass = new FrostedGlassDrawable(view, this, radiusPx, false, selected);
        registerTarget(glass);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(radiusPx);
        mask.setColor(Color.WHITE);

        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(getRippleColor(selected)),
                glass,
                mask
        );

        view.setTag(R.id.tag_frosted_glass_drawable, glass);
        view.setTag(R.id.tag_frosted_glass_ripple, ripple);
        view.setBackground(ripple);
        view.setPadding(padL, padT, padR, padB);
    }

    /**
     * Подключает RecyclerView с матовыми чипами, чтобы при скролле даже на паузе
     * фон стекла каждого чипа плавно скользил по размытому видеокадру.
     */
    public void attachScrollableGlassContainer(@Nullable RecyclerView recyclerView) {
        if (recyclerView == null) return;
        if (attachedScrollRvRef != null && attachedScrollRvRef.get() == recyclerView) {
            return;
        }
        attachedScrollRvRef = new WeakReference<>(recyclerView);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                boolean scrolling = (newState != RecyclerView.SCROLL_STATE_IDLE);
                if (isCarouselScrolling != scrolling) {
                    isCarouselScrolling = scrolling;
                    if (!scrolling && !isPaused && !isReleased) {
                        invalidateVisibleTargets();
                        ensureLoopStarted();
                    }
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dx == 0 && dy == 0) return;
                int childCount = rv.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    View child = rv.getChildAt(i);
                    if (child != null && child.getVisibility() == View.VISIBLE) {
                        child.invalidate();
                    }
                }
            }
        });
    }

    private int getRippleColor(boolean selected) {
        if (selected) {
            return ContextCompat.getColor(context, R.color.purple_alpha_25);
        }
        return 0x33FFFFFF;
    }

    private void registerTarget(@NonNull FrostedGlassDrawable drawable) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            FrostedGlassDrawable existing = targets.get(i).get();
            if (existing == null) {
                targets.remove(i);
            } else if (existing == drawable) {
                return;
            }
        }
        targets.add(new WeakReference<>(drawable));
    }

    /**
     * Вызывается из FrostedGlassDrawable.draw(), чтобы мгновенно разбудить цикл
     * и синхронно докомпозитить подсветку, если AmbientLightManager обновил её в этом же VSYNC.
     */
    void notifyDrawableDrawn() {
        if (isPaused || isReleased) return;
        if (!hasCapturedFrame) {
            updateVideoBounds();
            boolean captured = captureAndBlurIfNeeded(true);
            composeUnifiedBackdropIfNeeded(captured);
        } else {
            if (backdropScreenW <= 0f || backdropScreenH <= 0f || videoScreenW <= 0f || videoScreenH <= 0f) {
                updateVideoBounds();
            }
            // Быстрая O(1) проверка: перекомпозитим только если подсветка реально обновилась в этом же VSYNC
            if (!hasCompositeFrame
                    || (ambientLightView != null && ambientLightView.getContentGeneration() != lastComposedAmbientGen)) {
                composeUnifiedBackdropIfNeeded(false);
            }
        }
        ensureLoopStarted();
    }

    private void ensureLoopStarted() {
        if (isLoopRunning || isPaused || isReleased) return;
        isLoopRunning = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    public void requestImmediateRefresh() {
        if (isReleased) return;
        forceNextCapture = true;
        lastComposedAmbientGen = -1L;
        updateVideoBounds();
        if (!isPaused) {
            boolean captured = captureAndBlurIfNeeded(true);
            composeUnifiedBackdropIfNeeded(captured);
            invalidateVisibleTargets();
            ensureLoopStarted();
        }
    }

    public void onConfigurationChanged() {
        if (isReleased) return;
        cachedTextureView = null;
        forceNextCapture = true;
        lastComposedAmbientGen = -1L;
        videoScreenW = 0f;
        videoScreenH = 0f;
        backdropScreenW = 0f;
        backdropScreenH = 0f;
        invalidateVisibleTargets();
    }

    public void onPause() {
        isPaused = true;
        if (isLoopRunning) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            isLoopRunning = false;
        }
    }

    public void onResume() {
        if (isReleased) return;
        isPaused = false;
        forceNextCapture = true;
        invalidateVisibleTargets();
    }

    public void cleanup() {
        isReleased = true;
        isPaused = true;
        if (isLoopRunning) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            isLoopRunning = false;
        }
        if (ambientLightView != null) {
            ambientLightView.setOnContentUpdatedListener(null);
            ambientLightView = null;
        }
        targets.clear();
        cachedTextureView = null;
        if (sampleBitmap != null && !sampleBitmap.isRecycled()) {
            sampleBitmap.recycle();
        }
        sampleBitmap = null;
        if (blurredBitmap != null && !blurredBitmap.isRecycled()) {
            blurredBitmap.recycle();
        }
        blurredBitmap = null;
        if (compositeBitmap != null && !compositeBitmap.isRecycled()) {
            compositeBitmap.recycle();
        }
        compositeBitmap = null;
        compositeCanvas = null;
    }

    @Nullable
    Bitmap getCompositeBackdropBitmap() {
        return (hasCompositeFrame && compositeBitmap != null && !compositeBitmap.isRecycled())
                ? compositeBitmap
                : null;
    }

    long getFrameGeneration() {
        return frameGeneration;
    }

    float getBackdropScreenX() {
        return backdropScreenX;
    }

    float getBackdropScreenY() {
        return backdropScreenY;
    }

    float getBackdropScreenW() {
        return backdropScreenW;
    }

    float getBackdropScreenH() {
        return backdropScreenH;
    }

    float getDensity() {
        return density;
    }

    @NonNull
    static Bitmap getSharedGrainBitmap() {
        return ensureSharedGrainBitmap();
    }

    private void invalidateVisibleTargets() {
        for (int i = targets.size() - 1; i >= 0; i--) {
            FrostedGlassDrawable drawable = targets.get(i).get();
            if (drawable == null) {
                targets.remove(i);
                continue;
            }
            View host = drawable.getHostView();
            if (host != null && host.getLayerType() != View.LAYER_TYPE_HARDWARE && isViewEffectivelyVisible(host)) {
                host.invalidate();
            }
        }
    }

    void updateVideoBounds() {
        if (playerView != null && playerView.getWidth() > 0 && playerView.getHeight() > 0) {
            playerView.getLocationOnScreen(tmpVideoLoc);
            float pvScaleX = 1f;
            float pvScaleY = 1f;
            View pvCurrent = playerView;
            while (pvCurrent != null) {
                pvScaleX *= pvCurrent.getScaleX();
                pvScaleY *= pvCurrent.getScaleY();
                ViewParent parent = pvCurrent.getParent();
                pvCurrent = (parent instanceof View) ? (View) parent : null;
            }
            backdropScreenX = tmpVideoLoc[0];
            backdropScreenY = tmpVideoLoc[1];
            backdropScreenW = playerView.getWidth() * pvScaleX;
            backdropScreenH = playerView.getHeight() * pvScaleY;
        }

        TextureView tv = getTextureView();
        if (tv == null || tv.getWidth() <= 0 || tv.getHeight() <= 0) {
            return;
        }
        tv.getLocationOnScreen(tmpVideoLoc);
        float totalScaleX = 1f;
        float totalScaleY = 1f;
        View current = tv;
        while (current != null) {
            totalScaleX *= current.getScaleX();
            totalScaleY *= current.getScaleY();
            ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
        }
        videoScreenX = tmpVideoLoc[0];
        videoScreenY = tmpVideoLoc[1];
        videoScreenW = tv.getWidth() * totalScaleX;
        videoScreenH = tv.getHeight() * totalScaleY;

        // Если playerView ещё не получил размеры, используем границы видео как fallback
        if (backdropScreenW <= 1f || backdropScreenH <= 1f) {
            backdropScreenX = videoScreenX;
            backdropScreenY = videoScreenY;
            backdropScreenW = videoScreenW;
            backdropScreenH = videoScreenH;
        }
    }

    private boolean composeUnifiedBackdropIfNeeded(boolean videoUpdated) {
        if (isReleased || compositeBitmap == null || compositeBitmap.isRecycled() || compositeCanvas == null) {
            return false;
        }
        if (backdropScreenW <= 1f || backdropScreenH <= 1f) {
            return false;
        }

        long ambGen = (ambientLightView != null) ? ambientLightView.getContentGeneration() : 0L;
        float ambAlpha = (ambientLightView != null) ? ambientLightView.getEffectiveAlpha() : 0f;

        boolean ambientChanged = ambGen != lastComposedAmbientGen
                || Math.abs(ambAlpha - lastComposedAmbientAlpha) >= 0.005f;
        boolean geometryChanged = Float.isNaN(lastComposedVideoX)
                || Math.abs(videoScreenX - lastComposedVideoX) >= 0.5f
                || Math.abs(videoScreenY - lastComposedVideoY) >= 0.5f
                || Math.abs(videoScreenW - lastComposedVideoW) >= 0.5f
                || Math.abs(videoScreenH - lastComposedVideoH) >= 0.5f
                || Math.abs(backdropScreenX - lastComposedBackdropX) >= 0.5f
                || Math.abs(backdropScreenY - lastComposedBackdropY) >= 0.5f
                || Math.abs(backdropScreenW - lastComposedBackdropW) >= 0.5f
                || Math.abs(backdropScreenH - lastComposedBackdropH) >= 0.5f;

        if (!videoUpdated && !ambientChanged && !geometryChanged && hasCompositeFrame) {
            return false;
        }
        if (!hasCapturedFrame && ambAlpha <= 0.005f) {
            return false;
        }

        // 1. Чёрная подложка плеера (как фон activity_video_player)
        compositeCanvas.drawColor(Color.BLACK);

        // 2. Реальная краевая подсветка AmbientLightView (1:1 цвета, диффузия и затухание)
        boolean ambientDrawn = false;
        if (ambientLightView != null && ambAlpha > 0.005f) {
            ambientDrawn = ambientLightView.drawIntoBackdrop(
                    compositeCanvas,
                    backdropScreenX,
                    backdropScreenY,
                    backdropScreenW,
                    backdropScreenH,
                    COMPOSITE_WIDTH,
                    COMPOSITE_HEIGHT
            );
        }

        // 3. Размытый видеокадр строго в пределах реального прямоугольника видео
        if (hasCapturedFrame && blurredBitmap != null && !blurredBitmap.isRecycled()
                && videoScreenW > 1f && videoScreenH > 1f) {
            float scaleX = COMPOSITE_WIDTH / backdropScreenW;
            float scaleY = COMPOSITE_HEIGHT / backdropScreenH;
            float dstL = (videoScreenX - backdropScreenX) * scaleX;
            float dstT = (videoScreenY - backdropScreenY) * scaleY;
            float dstR = dstL + videoScreenW * scaleX;
            float dstB = dstT + videoScreenH * scaleY;
            tmpVideoDstRect.set(dstL, dstT, dstR, dstB);
            compositeCanvas.drawBitmap(blurredBitmap, null, tmpVideoDstRect, compositeVideoPaint);
        }

        if (ambAlpha <= 0.005f || ambientDrawn) {
            lastComposedAmbientGen = ambGen;
            lastComposedAmbientAlpha = ambAlpha;
        } else {
            lastComposedAmbientGen = -1L;
            lastComposedAmbientAlpha = -1f;
        }
        lastComposedVideoX = videoScreenX;
        lastComposedVideoY = videoScreenY;
        lastComposedVideoW = videoScreenW;
        lastComposedVideoH = videoScreenH;
        lastComposedBackdropX = backdropScreenX;
        lastComposedBackdropY = backdropScreenY;
        lastComposedBackdropW = backdropScreenW;
        lastComposedBackdropH = backdropScreenH;

        hasCompositeFrame = true;
        frameGeneration++;
        return true;
    }

    private boolean captureAndBlurIfNeeded(boolean force) {
        if (isReleased || sampleBitmap == null || blurredBitmap == null) {
            return false;
        }
        TextureView tv = getTextureView();
        if (tv == null || !tv.isAvailable() || tv.getWidth() <= 0 || tv.getHeight() <= 0) {
            return false;
        }

        long nowMs = SystemClock.uptimeMillis();
        boolean forced = force || forceNextCapture;
        long captureInterval = (isCarouselScrolling || isVerticalDragging)
                ? SCROLL_CAPTURE_INTERVAL_MS
                : MIN_CAPTURE_INTERVAL_MS;
        if (!forced && (nowMs - lastCaptureUptimeMs) < captureInterval) {
            return false;
        }

        SurfaceTexture st = tv.getSurfaceTexture();
        long ts = (st != null) ? st.getTimestamp() : Long.MIN_VALUE;
        if (!forced && hasCapturedFrame && ts != Long.MIN_VALUE && ts == lastSurfaceTimestamp) {
            return false;
        }

        try {
            Bitmap captured = tv.getBitmap(sampleBitmap);
            if (captured == null) {
                return false;
            }
            captured.getPixels(bufA, 0, BLUR_WIDTH, 0, 0, BLUR_WIDTH, BLUR_HEIGHT);

            // 1-проходный O(1) сепарабельный box-блюр (192x108, r=2 -> более чёткое, менее мыльное стекло)
            boxBlurHorizontal(bufA, bufB, BLUR_WIDTH, BLUR_HEIGHT);
            boxBlurVertical(bufB, bufA, BLUR_WIDTH, BLUR_HEIGHT, true);

            blurredBitmap.setPixels(bufA, 0, BLUR_WIDTH, 0, 0, BLUR_WIDTH, BLUR_HEIGHT);
            lastSurfaceTimestamp = ts;
            lastCaptureUptimeMs = nowMs;
            forceNextCapture = false;
            hasCapturedFrame = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Скользящий горизонтальный проход O(1) на пиксель без аллокаций.
     */
    private static void boxBlurHorizontal(int[] src, int[] dst, int w, int h) {
        final int r = BLUR_RADIUS;
        final int wMinus1 = w - 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int first = src[row];
            int sumR = ((first >>> 16) & 0xFF) * (r + 1);
            int sumG = ((first >>> 8) & 0xFF) * (r + 1);
            int sumB = (first & 0xFF) * (r + 1);
            for (int i = 1; i <= r; i++) {
                int p = src[row + Math.min(i, wMinus1)];
                sumR += (p >>> 16) & 0xFF;
                sumG += (p >>> 8) & 0xFF;
                sumB += p & 0xFF;
            }

            for (int x = 0; x < w; x++) {
                int outR = (sumR * BLUR_INV_DIV) >>> 16;
                int outG = (sumG * BLUR_INV_DIV) >>> 16;
                int outB = (sumB * BLUR_INV_DIV) >>> 16;
                dst[row + x] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;

                int addX = x + r + 1;
                if (addX > wMinus1) addX = wMinus1;
                int remX = x - r;
                if (remX < 0) remX = 0;

                int pAdd = src[row + addX];
                int pRem = src[row + remX];
                sumR += ((pAdd >>> 16) & 0xFF) - ((pRem >>> 16) & 0xFF);
                sumG += ((pAdd >>> 8) & 0xFF) - ((pRem >>> 8) & 0xFF);
                sumB += (pAdd & 0xFF) - (pRem & 0xFF);
            }
        }
    }

    /**
     * Скользящий вертикальный проход O(1) на пиксель.
     * На финальном проходе добавляет лёгкое усиление насыщенности (+20%), чтобы цвета
     * аниме мягко и живо светились сквозь тёмное матовое стекло (как в iOS/macOS Vibrancy).
     */
    private static void boxBlurVertical(int[] src, int[] dst, int w, int h, boolean applyVibrancy) {
        final int r = BLUR_RADIUS;
        final int hMinus1 = h - 1;
        for (int x = 0; x < w; x++) {
            int first = src[x];
            int sumR = ((first >>> 16) & 0xFF) * (r + 1);
            int sumG = ((first >>> 8) & 0xFF) * (r + 1);
            int sumB = (first & 0xFF) * (r + 1);
            for (int i = 1; i <= r; i++) {
                int p = src[Math.min(i, hMinus1) * w + x];
                sumR += (p >>> 16) & 0xFF;
                sumG += (p >>> 8) & 0xFF;
                sumB += p & 0xFF;
            }

            for (int y = 0; y < h; y++) {
                int outR = (sumR * BLUR_INV_DIV) >>> 16;
                int outG = (sumG * BLUR_INV_DIV) >>> 16;
                int outB = (sumB * BLUR_INV_DIV) >>> 16;

                if (applyVibrancy) {
                    int luma = (77 * outR + 150 * outG + 29 * outB) >> 8;
                    outR = clamp255(luma + (((outR - luma) * 274) >> 8));
                    outG = clamp255(luma + (((outG - luma) * 274) >> 8));
                    outB = clamp255(luma + (((outB - luma) * 274) >> 8));
                }

                dst[y * w + x] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;

                int addY = y + r + 1;
                if (addY > hMinus1) addY = hMinus1;
                int remY = y - r;
                if (remY < 0) remY = 0;

                int pAdd = src[addY * w + x];
                int pRem = src[remY * w + x];
                sumR += ((pAdd >>> 16) & 0xFF) - ((pRem >>> 16) & 0xFF);
                sumG += ((pAdd >>> 8) & 0xFF) - ((pRem >>> 8) & 0xFF);
                sumB += (pAdd & 0xFF) - (pRem & 0xFF);
            }
        }
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (Math.min(v, 255));
    }

    private static boolean isViewEffectivelyVisible(@NonNull View view) {
        if (!view.isAttachedToWindow() || !view.isShown()
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        View current = view;
        int depth = 0;
        while (current != null && depth < 12) {
            if (current.getAlpha() <= 0.01f) {
                return false;
            }
            ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
            depth++;
        }
        return true;
    }

    @Nullable
    private TextureView getTextureView() {
        if (cachedTextureView != null && cachedTextureView.isAvailable()) {
            return cachedTextureView;
        }
        if (playerView == null) return null;
        View surface = playerView.getVideoSurfaceView();
        if (surface instanceof TextureView) {
            cachedTextureView = (TextureView) surface;
            return cachedTextureView;
        }
        cachedTextureView = findTextureView(playerView);
        return cachedTextureView;
    }

    @Nullable
    private static TextureView findTextureView(@Nullable ViewGroup group) {
        if (group == null) return null;
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextureView) {
                return (TextureView) child;
            } else if (child instanceof ViewGroup) {
                TextureView found = findTextureView((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private float dpToPx(float dp) {
        return dp * density;
    }

    @NonNull
    private static Bitmap ensureSharedGrainBitmap() {
        Bitmap existing = sSharedGrainBitmap;
        if (existing != null && !existing.isRecycled()) {
            return existing;
        }
        synchronized (PlayerBackdropBlurManager.class) {
            if (sSharedGrainBitmap != null && !sSharedGrainBitmap.isRecycled()) {
                return sSharedGrainBitmap;
            }
            Bitmap bmp = Bitmap.createBitmap(GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[GRAIN_SIZE * GRAIN_SIZE];
            int seed = 0x5F3759DF;
            for (int i = 0; i < pixels.length; i++) {
                seed ^= (seed << 13);
                seed ^= (seed >>> 17);
                seed ^= (seed << 5);
                int r1 = seed & 0xFF;

                seed ^= (seed << 13);
                seed ^= (seed >>> 17);
                seed ^= (seed << 5);
                int r2 = seed & 0xFF;

                // Треугольное распределение (деликатное мелкое матовое зерно без лишней яркости)
                int delta = (r1 + r2) - 255; // [-255..+255]
                if (delta >= 0) {
                    int alpha = (delta * 30) / 255;
                    pixels[i] = (alpha << 24) | 0x00FFFFFF;
                } else {
                    int alpha = ((-delta) * 30) / 255;
                    pixels[i] = (alpha << 24) | 0x0006080D;
                }
            }
            bmp.setPixels(pixels, 0, GRAIN_SIZE, 0, 0, GRAIN_SIZE, GRAIN_SIZE);
            sSharedGrainBitmap = bmp;
            return bmp;
        }
    }
}
