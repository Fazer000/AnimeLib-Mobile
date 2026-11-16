package com.example.animelib.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Оверлей нативного разрешения для бесшовного финального затухания краевой подсветки в #000000.
 *
 * Работает индивидуально с каждой из 4 пустых областей вокруг видео
 * (левая, правая, верхняя, нижняя), обеспечивая плавный переход к абсолютному черному
 * ровно по ширине/высоте соответствующей пустой области без ступеней и угловых швов.
 */
public class AmbientVignetteOverlayView extends View {

    // Вдоль градиента: 0.0 = внешняя граница пустой области (#FF000000), 1.0 = край видео (#00000000)
    // Внутренние 75% области (0.25..1.0) остаются без затемнения, чтобы не создавать двойного порога,
    // а внешние 25% (0.0..0.25) мягко доводят край до чистого #000000 по C2-кривой из 16 точек.
    private static final float FADE_START_STOP = 0.25f;
    private static final int FADE_STOP_COUNT = 16;
    private static final int[] FADE_COLORS = new int[FADE_STOP_COUNT + 1];
    private static final float[] FADE_STOPS = new float[FADE_STOP_COUNT + 1];

    static {
        for (int i = 0; i < FADE_STOP_COUNT; i++) {
            float u = (float) i / (float) (FADE_STOP_COUNT - 1); // 0.0 (внешний край) -> 1.0 (начало затухания)
            float stopPos = u * FADE_START_STOP;
            float smoother = u * u * u * (u * (u * 6.0f - 15.0f) + 10.0f);
            float blackAlpha = 1.0f - smoother;
            int alphaByte = Math.max(0, Math.min(255, Math.round(blackAlpha * 255.0f)));
            FADE_STOPS[i] = stopPos;
            FADE_COLORS[i] = alphaByte << 24;
        }
        FADE_STOPS[FADE_STOP_COUNT] = 1.0f;
        FADE_COLORS[FADE_STOP_COUNT] = 0x00000000;
    }

    private final Paint leftPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint rightPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint topPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint bottomPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint blackPaint = new Paint();

    private final RectF customBounds = new RectF();
    private final RectF availableBounds = new RectF();
    private final RectF currentVideoRect = new RectF();

    private boolean hasCustomBounds = false;
    private boolean hasCustomAvailBounds = false;
    private float legacyAvailRight = -1f;
    private float videoAspectRatio = 16f / 9f;

    private int lastWidth = 0;
    private int lastHeight = 0;
    private float cachedAL, cachedAT, cachedAR, cachedAB;
    private float cachedVL, cachedVT, cachedVR, cachedVB;
    private boolean shadersDirty = true;

    public AmbientVignetteOverlayView(Context context) {
        this(context, null);
    }

    public AmbientVignetteOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AmbientVignetteOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        blackPaint.setColor(Color.BLACK);
        blackPaint.setStyle(Paint.Style.FILL);
    }

    public void setVideoBounds(float left, float top, float right, float bottom) {
        setVideoBounds(left, top, right, bottom, -1f);
    }

    public void setVideoBounds(float left, float top, float right, float bottom, float availRight) {
        hasCustomBounds = true;
        customBounds.set(left, top, right, bottom);
        this.legacyAvailRight = availRight;
        shadersDirty = true;
        invalidate();
    }

    public void setBounds(float vLeft, float vTop, float vRight, float vBottom,
                          float aLeft, float aTop, float aRight, float aBottom) {
        hasCustomBounds = true;
        customBounds.set(vLeft, vTop, vRight, vBottom);
        hasCustomAvailBounds = true;
        availableBounds.set(aLeft, aTop, aRight, aBottom);
        legacyAvailRight = aRight;
        shadersDirty = true;
        invalidate();
    }

    public void clearCustomVideoBounds() {
        hasCustomBounds = false;
        hasCustomAvailBounds = false;
        legacyAvailRight = -1f;
        shadersDirty = true;
        invalidate();
    }

    public void setVideoAspectRatio(float aspect) {
        if (aspect > 0 && Math.abs(this.videoAspectRatio - aspect) > 0.01f) {
            this.videoAspectRatio = aspect;
            shadersDirty = true;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w;
            lastHeight = h;
            shadersDirty = true;
        }
    }

    private void rebuildShadersIfNeeded(int w, int h) {
        float aL = 0f;
        float aT = 0f;
        float aR = w;
        float aB = h;

        if (hasCustomAvailBounds && availableBounds.width() > 0 && availableBounds.height() > 0) {
            aL = Math.max(0f, availableBounds.left);
            aT = Math.max(0f, availableBounds.top);
            aR = Math.min((float) w, availableBounds.right);
            aB = Math.min((float) h, availableBounds.bottom);
        } else if (legacyAvailRight > 0f && legacyAvailRight < w) {
            aR = legacyAvailRight;
        }

        if (hasCustomBounds && customBounds.width() > 0 && customBounds.height() > 0) {
            currentVideoRect.set(customBounds);
        } else {
            float aspect = videoAspectRatio > 0 ? videoAspectRatio : 16f / 9f;
            float fullVideoW = Math.min((float) w, (float) h * aspect);
            float fullVideoH = Math.min((float) h, (float) w / aspect);
            float left = (w - fullVideoW) * 0.5f;
            float top = (h - fullVideoH) * 0.5f;
            currentVideoRect.set(left, top, left + fullVideoW, top + fullVideoH);
        }

        float vL = Math.max(aL, Math.min(aR, currentVideoRect.left));
        float vT = Math.max(aT, Math.min(aB, currentVideoRect.top));
        float vR = Math.max(vL, Math.min(aR, currentVideoRect.right));
        float vB = Math.max(vT, Math.min(aB, currentVideoRect.bottom));

        if (!shadersDirty
                && Math.abs(aL - cachedAL) < 0.5f && Math.abs(aT - cachedAT) < 0.5f
                && Math.abs(aR - cachedAR) < 0.5f && Math.abs(aB - cachedAB) < 0.5f
                && Math.abs(vL - cachedVL) < 0.5f && Math.abs(vT - cachedVT) < 0.5f
                && Math.abs(vR - cachedVR) < 0.5f && Math.abs(vB - cachedVB) < 0.5f) {
            return;
        }

        cachedAL = aL; cachedAT = aT; cachedAR = aR; cachedAB = aB;
        cachedVL = vL; cachedVT = vT; cachedVR = vR; cachedVB = vB;
        shadersDirty = false;

        if (vL - aL > 1.5f) {
            leftPaint.setShader(new LinearGradient(aL, 0f, vL, 0f, FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP));
        } else {
            leftPaint.setShader(null);
        }

        if (aR - vR > 1.5f) {
            rightPaint.setShader(new LinearGradient(aR, 0f, vR, 0f, FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP));
        } else {
            rightPaint.setShader(null);
        }

        if (vT - aT > 1.5f) {
            topPaint.setShader(new LinearGradient(0f, aT, 0f, vT, FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP));
        } else {
            topPaint.setShader(null);
        }

        if (aB - vB > 1.5f) {
            bottomPaint.setShader(new LinearGradient(0f, aB, 0f, vB, FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP));
        } else {
            bottomPaint.setShader(null);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        rebuildShadersIfNeeded(w, h);

        // Полосы затухания вдоль каждой пустой области (пересечение в углах C1-гладкое)
        if (leftPaint.getShader() != null) {
            canvas.drawRect(cachedAL, cachedAT, cachedVL, cachedAB, leftPaint);
        }
        if (rightPaint.getShader() != null) {
            canvas.drawRect(cachedVR, cachedAT, cachedAR, cachedAB, rightPaint);
        }
        if (topPaint.getShader() != null) {
            canvas.drawRect(cachedAL, cachedAT, cachedAR, cachedVT, topPaint);
        }
        if (bottomPaint.getShader() != null) {
            canvas.drawRect(cachedAL, cachedVB, cachedAR, cachedAB, bottomPaint);
        }

        // Закрываем область за открытой шторкой или ниже портретного плеера чистым черным
        if (cachedAR < w - 0.5f) {
            canvas.drawRect(cachedAR, 0f, w, h, blackPaint);
        }
        if (cachedAB < h - 0.5f) {
            canvas.drawRect(0f, cachedAB, w, h, blackPaint);
        }
    }
}
