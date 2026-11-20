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
    private final float[] meshVerts = new float[32]; // 4x4 vertices * (x, y)

    private Bitmap displayBitmap;

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

        setBackgroundColor(Color.TRANSPARENT);
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
            invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Error updating sample pixels", e);
        }
    }

    public void setVideoAspectRatio(float aspect) {
        if (aspect > 0 && Math.abs(this.videoAspectRatio - aspect) > 0.01f) {
            this.videoAspectRatio = aspect;
            invalidate();
        }
    }

    public void setCustomVideoBounds(float left, float top, float right, float bottom) {
        if (customVideoBounds == null) {
            customVideoBounds = new RectF(left, top, right, bottom);
            invalidate();
        } else if (Math.abs(customVideoBounds.left - left) >= 0.5f
                || Math.abs(customVideoBounds.top - top) >= 0.5f
                || Math.abs(customVideoBounds.right - right) >= 0.5f
                || Math.abs(customVideoBounds.bottom - bottom) >= 0.5f) {
            customVideoBounds.set(left, top, right, bottom);
            invalidate();
        }
    }

    public void clearCustomVideoBounds() {
        if (customVideoBounds != null) {
            customVideoBounds = null;
            invalidate();
        }
    }

    public void setAvailableBounds(float left, float top, float right, float bottom) {
        if (availableBounds == null) {
            availableBounds = new RectF(left, top, right, bottom);
            invalidate();
        } else if (Math.abs(availableBounds.left - left) >= 0.5f
                || Math.abs(availableBounds.top - top) >= 0.5f
                || Math.abs(availableBounds.right - right) >= 0.5f
                || Math.abs(availableBounds.bottom - bottom) >= 0.5f) {
            availableBounds.set(left, top, right, bottom);
            invalidate();
        }
    }

    public void clearAvailableBounds() {
        if (availableBounds != null) {
            availableBounds = null;
            invalidate();
        }
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
                return;
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
            meshVerts[0]  = x0; meshVerts[1]  = y0;
            meshVerts[2]  = x1; meshVerts[3]  = y0;
            meshVerts[4]  = x2; meshVerts[5]  = y0;
            meshVerts[6]  = x3; meshVerts[7]  = y0;

            meshVerts[8]  = x0; meshVerts[9]  = y1;
            meshVerts[10] = x1; meshVerts[11] = y1;
            meshVerts[12] = x2; meshVerts[13] = y1;
            meshVerts[14] = x3; meshVerts[15] = y1;

            meshVerts[16] = x0; meshVerts[17] = y2;
            meshVerts[18] = x1; meshVerts[19] = y2;
            meshVerts[20] = x2; meshVerts[21] = y2;
            meshVerts[22] = x3; meshVerts[23] = y2;

            meshVerts[24] = x0; meshVerts[25] = y3;
            meshVerts[26] = x1; meshVerts[27] = y3;
            meshVerts[28] = x2; meshVerts[29] = y3;
            meshVerts[30] = x3; meshVerts[31] = y3;

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
            invalidate();
        });
        intensityAnimator.start();
    }

    public void suspend() {
        if (intensityAnimator != null && intensityAnimator.isRunning()) {
            intensityAnimator.cancel();
        }
    }

    public void resume() {
        setVisibility(VISIBLE);
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
