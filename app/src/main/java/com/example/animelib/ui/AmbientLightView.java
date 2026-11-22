package com.example.animelib.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Аппаратно-ускоренный View краевой фоновой подсветки (Edge-Diffused Ambilight).
 *
 * Рисует предрассчитанное поле рассеивания краёв (96x72, сетка 3x3) через Canvas.drawBitmapMesh:
 * - Нулевые аллокации и отсутствие тяжелых полноэкранных RenderEffect/FBO-слоёв во время анимаций;
 * - Центральная зона [1/3..2/3] выравнивается по границам активного видео;
 * - 4 внешние полосы и 4 угла равномерно растягиваются ровно на ширину/высоту
 *   соответствующих пустых областей вокруг плеера с аппаратной билинейной фильтрацией.
 */
public class AmbientLightView extends View {
    private static final String TAG = "AmbientLightView";

    private final Paint drawPaint;
    private final Paint backdropDrawPaint;
    private final float[] meshVerts = new float[32]; // 4x4 vertices * (x, y)
    private final float[] backdropMeshVerts = new float[32];
    private final int[] tmpScreenLoc = new int[2];

    private Bitmap displayBitmap;
    private long contentGeneration = 0L;
    @Nullable
    private Runnable onContentUpdatedListener;

    private float currentIntensity = 1.0f;
    private ValueAnimator intensityAnimator;

    private RectF customVideoBounds = null;
    private RectF availableBounds = null;
    private float videoAspectRatio = 16f / 9f;

    public AmbientLightView(Context context) {
        this(context, null);
    }

    public AmbientLightView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AmbientLightView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        drawPaint.setDither(true);

        backdropDrawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        backdropDrawPaint.setDither(true);

        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setOnContentUpdatedListener(@Nullable Runnable listener) {
        this.onContentUpdatedListener = listener;
    }

    public long getContentGeneration() {
        return contentGeneration;
    }

    /**
     * Возвращает итоговую видимую прозрачность подсветки на экране [0..1]
     * с учётом currentIntensity и alpha/visibility родительских контейнеров.
     */
    public float getEffectiveAlpha() {
        if (!isAttachedToWindow() || !isShown() || getVisibility() != VISIBLE
                || displayBitmap == null || displayBitmap.isRecycled()) {
            return 0f;
        }
        float alpha = Math.max(0f, Math.min(1f, currentIntensity));
        View current = this;
        int depth = 0;
        while (current != null && depth < 12) {
            if (current.getVisibility() != VISIBLE) {
                return 0f;
            }
            alpha *= current.getAlpha();
            if (alpha <= 0.005f) {
                return 0f;
            }
            android.view.ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
            depth++;
        }
        return Math.max(0f, Math.min(1f, alpha));
    }

    private void notifyContentUpdated() {
        contentGeneration++;
        Runnable listener = onContentUpdatedListener;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Обновляет предрассчитанное поле краевого рассеивания (GLOW_WIDTH x GLOW_HEIGHT).
     */
    public void updateSamplePixels(int[] pixels, int width, int height) {
        if (pixels == null || pixels.length < width * height || getVisibility() != VISIBLE) {
            return;
        }

        try {
            if (displayBitmap == null || displayBitmap.isRecycled()
                    || displayBitmap.getWidth() != width || displayBitmap.getHeight() != height) {
                if (displayBitmap != null && !displayBitmap.isRecycled()) {
                    displayBitmap.recycle();
                }
                displayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }

            displayBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            notifyContentUpdated();
            invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Error updating sample pixels", e);
        }
    }

    public void setVideoAspectRatio(float aspect) {
        if (aspect > 0 && Math.abs(this.videoAspectRatio - aspect) > 0.01f) {
            this.videoAspectRatio = aspect;
            notifyContentUpdated();
            invalidate();
        }
    }

    public void setCustomVideoBounds(float left, float top, float right, float bottom) {
        if (customVideoBounds == null) {
            customVideoBounds = new RectF(left, top, right, bottom);
            notifyContentUpdated();
            invalidate();
        } else if (Math.abs(customVideoBounds.left - left) >= 0.5f
                || Math.abs(customVideoBounds.top - top) >= 0.5f
                || Math.abs(customVideoBounds.right - right) >= 0.5f
                || Math.abs(customVideoBounds.bottom - bottom) >= 0.5f) {
            customVideoBounds.set(left, top, right, bottom);
            notifyContentUpdated();
            invalidate();
        }
    }

    public void clearCustomVideoBounds() {
        if (customVideoBounds != null) {
            customVideoBounds = null;
            notifyContentUpdated();
            invalidate();
        }
    }

    public void setAvailableBounds(float left, float top, float right, float bottom) {
        if (availableBounds == null) {
            availableBounds = new RectF(left, top, right, bottom);
            notifyContentUpdated();
            invalidate();
        } else if (Math.abs(availableBounds.left - left) >= 0.5f
                || Math.abs(availableBounds.top - top) >= 0.5f
                || Math.abs(availableBounds.right - right) >= 0.5f
                || Math.abs(availableBounds.bottom - bottom) >= 0.5f) {
            availableBounds.set(left, top, right, bottom);
            notifyContentUpdated();
            invalidate();
        }
    }

    public void clearAvailableBounds() {
        if (availableBounds != null) {
            availableBounds = null;
            notifyContentUpdated();
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            notifyContentUpdated();
        }
    }

    private boolean computeMeshVerts(int viewW, int viewH, float[] outVerts) {
        float availL = 0f;
        float availT = 0f;
        float availR = viewW;
        float availB = viewH;

        if (availableBounds != null && availableBounds.width() > 0 && availableBounds.height() > 0) {
            availL = Math.max(0f, availableBounds.left);
            availT = Math.max(0f, availableBounds.top);
            availR = Math.min((float) viewW, availableBounds.right);
            availB = Math.min((float) viewH, availableBounds.bottom);
        }

        if (availR <= availL || availB <= availT) {
            return false;
        }

        float vL, vT, vR, vB;
        if (customVideoBounds != null && customVideoBounds.width() > 0 && customVideoBounds.height() > 0) {
            vL = customVideoBounds.left;
            vT = customVideoBounds.top;
            vR = customVideoBounds.right;
            vB = customVideoBounds.bottom;
        } else {
            float aspect = videoAspectRatio > 0 ? videoAspectRatio : 16f / 9f;
            float fullVideoW = Math.min((float) viewW, (float) viewH * aspect);
            float fullVideoH = Math.min((float) viewH, (float) viewW / aspect);
            vL = (viewW - fullVideoW) * 0.5f;
            vT = (viewH - fullVideoH) * 0.5f;
            vR = vL + fullVideoW;
            vB = vT + fullVideoH;
        }

        float x0 = availL;
        float x1 = Math.max(availL, Math.min(availR, vL));
        float x2 = Math.max(x1, Math.min(availR, vR));
        float x3 = availR;

        float y0 = availT;
        float y1 = Math.max(availT, Math.min(availB, vT));
        float y2 = Math.max(y1, Math.min(availB, vB));
        float y3 = availB;

        // Заполняем 4x4 вершины 3x3 сетки: края ровно по [avail .. video], центр [video]
        outVerts[0]  = x0; outVerts[1]  = y0;
        outVerts[2]  = x1; outVerts[3]  = y0;
        outVerts[4]  = x2; outVerts[5]  = y0;
        outVerts[6]  = x3; outVerts[7]  = y0;

        outVerts[8]  = x0; outVerts[9]  = y1;
        outVerts[10] = x1; outVerts[11] = y1;
        outVerts[12] = x2; outVerts[13] = y1;
        outVerts[14] = x3; outVerts[15] = y1;

        outVerts[16] = x0; outVerts[17] = y2;
        outVerts[18] = x1; outVerts[19] = y2;
        outVerts[20] = x2; outVerts[21] = y2;
        outVerts[22] = x3; outVerts[23] = y2;

        outVerts[24] = x0; outVerts[25] = y3;
        outVerts[26] = x1; outVerts[27] = y3;
        outVerts[28] = x2; outVerts[29] = y3;
        outVerts[30] = x3; outVerts[31] = y3;
        return true;
    }

    /**
     * Отрисовывает текущую 3x3-сетку подсветки в единый буфер заднего фона матового стекла
     * с точным сохранением экранных координат, затухания cinemaFalloff и текущей прозрачности.
     */
    public boolean drawIntoBackdrop(@NonNull Canvas dstCanvas,
                                    float backdropScreenX,
                                    float backdropScreenY,
                                    float backdropScreenW,
                                    float backdropScreenH,
                                    int dstW,
                                    int dstH) {
        if (backdropScreenW <= 1f || backdropScreenH <= 1f || dstW <= 0 || dstH <= 0) {
            return false;
        }
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) {
            return false;
        }
        float effectiveAlpha = getEffectiveAlpha();
        if (effectiveAlpha <= 0.005f || displayBitmap == null || displayBitmap.isRecycled()) {
            return false;
        }
        if (!computeMeshVerts(viewW, viewH, backdropMeshVerts)) {
            return false;
        }

        getLocationOnScreen(tmpScreenLoc);
        float ambientScreenX = tmpScreenLoc[0];
        float ambientScreenY = tmpScreenLoc[1];
        float scaleToDstX = dstW / backdropScreenW;
        float scaleToDstY = dstH / backdropScreenH;
        float offsetX = ambientScreenX - backdropScreenX;
        float offsetY = ambientScreenY - backdropScreenY;

        for (int i = 0; i < 32; i += 2) {
            backdropMeshVerts[i] = (backdropMeshVerts[i] + offsetX) * scaleToDstX;
            backdropMeshVerts[i + 1] = (backdropMeshVerts[i + 1] + offsetY) * scaleToDstY;
        }

        backdropDrawPaint.setAlpha(Math.round(effectiveAlpha * 255f));
        dstCanvas.drawBitmapMesh(displayBitmap, 3, 3, backdropMeshVerts, 0, null, 0, backdropDrawPaint);
        return true;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0 || getVisibility() != VISIBLE) {
            return;
        }

        if (displayBitmap == null || displayBitmap.isRecycled()) {
            return;
        }

        try {
            if (!computeMeshVerts(viewW, viewH, meshVerts)) {
                return;
            }
            drawPaint.setAlpha((int) (currentIntensity * 255));
            canvas.drawBitmapMesh(displayBitmap, 3, 3, meshVerts, 0, null, 0, drawPaint);
        } catch (Exception e) {
            Log.e(TAG, "Error in onDraw", e);
        }
    }

    public void dimToIntensity(float targetIntensity) {
        if (intensityAnimator != null && intensityAnimator.isRunning()) {
            intensityAnimator.cancel();
        }

        final float startIntensity = currentIntensity;
        intensityAnimator = ValueAnimator.ofFloat(startIntensity, targetIntensity);
        intensityAnimator.setDuration(250);
        intensityAnimator.setInterpolator(new DecelerateInterpolator());
        intensityAnimator.addUpdateListener(animation -> {
            currentIntensity = (float) animation.getAnimatedValue();
            notifyContentUpdated();
            invalidate();
        });
        intensityAnimator.start();
    }

    public void suspend() {
        if (intensityAnimator != null && intensityAnimator.isRunning()) {
            intensityAnimator.cancel();
        }
        notifyContentUpdated();
    }

    public void resume() {
        setVisibility(VISIBLE);
        notifyContentUpdated();
        invalidate();
    }

    public void pauseAnimations() {
        suspend();
    }

    public void resumeAnimations() {
        resume();
    }

    public void setColors(int[] left, int[] top, int[] right, int[] bottom) {
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (intensityAnimator != null) {
            intensityAnimator.cancel();
            intensityAnimator = null;
        }
        if (displayBitmap != null && !displayBitmap.isRecycled()) {
            displayBitmap.recycle();
            displayBitmap = null;
        }
    }
}
