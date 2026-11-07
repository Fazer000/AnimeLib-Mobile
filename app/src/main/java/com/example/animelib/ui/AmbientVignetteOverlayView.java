package com.example.animelib.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Оверлей бесшовного органического рассеивания подсветки (Ambilight 2.0).
 *
 * Архитектурные принципы:
 * 1. Единый непрерывный эллиптический градиент (Smooth Hermite S-Curve) без каких-либо
 *    прямоугольных швов, прямых граней и ступеней затухания.
 * 2. Центр и пропорции эллипса строго соответствуют активному видеокадру, естественно
 *    растворяя свечение в глубокий черный цвет (#000000) к краям экрана.
 * 3. При открытии боковых панелей свечение смещается вместе с плеером и мягко угасает
 *    перед шторкой через плавный переходный градиент без резкого вертикального среза.
 */
public class AmbientVignetteOverlayView extends View {

    private static final float SHADER_BASE_RADIUS = 1000f;

    // S-кривая плавного органического затухания в чистый черный (#000000)
    private static final int[] VIGNETTE_COLORS = new int[]{
            0x00000000, // 0.00: 100% прозрачно в центре
            0x00000000, // 0.58: прозрачно в пределах видеокадра
            0x0E000000, // 0.66: неощутимое начало рассеивания (~5% черного)
            0x2E000000, // 0.74: мягкий полутеневой переход (~18% черного)
            0x66000000, // 0.82: естественный спад (~40% черного)
            0xAA000000, // 0.90: глубокое приглушение (~67% черного)
            0xEB000000, // 0.96: предфинальное растворение (~92% черного)
            0xFF000000  // 1.00: чистый 100% черный (#000000)
    };

    private static final float[] VIGNETTE_STOPS = new float[]{
            0.00f, 0.58f, 0.66f, 0.74f, 0.82f, 0.90f, 0.96f, 1.00f
    };

    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint solidBlackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    private final Matrix shaderMatrix = new Matrix();
    private final RectF currentVideoRect = new RectF();
    private final RectF customBounds = new RectF();

    private RadialGradient radialGradient;
    private LinearGradient panelFadeGradient;

    private boolean hasCustomBounds = false;
    private float customAvailRight = -1f;
    private float videoAspectRatio = 16f / 9f;
    private float density = 1f;

    private int lastWidth = 0;
    private int lastHeight = 0;

    public AmbientVignetteOverlayView(Context context) {
        this(context, null);
    }

    public AmbientVignetteOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AmbientVignetteOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;

        solidBlackPaint.setColor(0xFF000000);
        solidBlackPaint.setStyle(Paint.Style.FILL);

        vignettePaint.setStyle(Paint.Style.FILL);
        panelFadePaint.setStyle(Paint.Style.FILL);

        // Базовый радиальный градиент с единичным радиусом 1000px, масштабируемый через матрицу в эллипс
        radialGradient = new RadialGradient(
                0f, 0f, SHADER_BASE_RADIUS,
                VIGNETTE_COLORS, VIGNETTE_STOPS,
                Shader.TileMode.CLAMP
        );
        vignettePaint.setShader(radialGradient);
    }

    /**
     * Устанавливает точные экранные границы видео
     */
    public void setVideoBounds(float left, float top, float right, float bottom) {
        setVideoBounds(left, top, right, bottom, -1f);
    }

    /**
     * Устанавливает экранные границы видео и границу доступной области (до начала бокового окна)
     */
    public void setVideoBounds(float left, float top, float right, float bottom, float availRight) {
        hasCustomBounds = true;
        customBounds.set(left, top, right, bottom);
        this.customAvailRight = availRight;
        updateShaderMatrix(getWidth(), getHeight());
        invalidate();
    }

    /**
     * Сбрасывает пользовательские границы (переходит к авто-вычислению по соотношению сторон)
     */
    public void clearCustomVideoBounds() {
        hasCustomBounds = false;
        customAvailRight = -1f;
        updateShaderMatrix(getWidth(), getHeight());
        invalidate();
    }

    /**
     * Устанавливает соотношение сторон видео для автоматического центрирования
     */
    public void setVideoAspectRatio(float aspect) {
        if (aspect > 0 && Math.abs(this.videoAspectRatio - aspect) > 0.01f) {
            this.videoAspectRatio = aspect;
            updateShaderMatrix(getWidth(), getHeight());
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w;
            lastHeight = h;
            updateShaderMatrix(w, h);
        }
    }

    /**
     * Обновляет матрицу эллиптического шейдера под геометрию видеокадра
     */
    private void updateShaderMatrix(int w, int h) {
        if (w <= 0 || h <= 0) return;

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

        float cx = currentVideoRect.centerX();
        float cy = currentVideoRect.centerY();
        float halfW = currentVideoRect.width() * 0.5f;
        float halfH = currentVideoRect.height() * 0.5f;

        if (halfW <= 0f) halfW = w * 0.5f;
        if (halfH <= 0f) halfH = h * 0.5f;

        // Радиальный размах эллипса: органичное рассеивание за пределы видеокарты
        float spreadX = halfW * 1.34f + (36f * density);
        float spreadY = halfH * 1.48f + (24f * density);

        // Масштабируем единичный круг в точный эллипс вокруг центра видео
        shaderMatrix.reset();
        shaderMatrix.setScale(spreadX / SHADER_BASE_RADIUS, spreadY / SHADER_BASE_RADIUS);
        shaderMatrix.postTranslate(cx, cy);

        if (radialGradient != null) {
            radialGradient.setLocalMatrix(shaderMatrix);
        }

        // Плавный переходный градиент к шторке бокового окна
        float availRight = (customAvailRight > 0f) ? Math.min((float) w, customAvailRight) : (float) w;
        if (availRight < w) {
            float fadeWidth = Math.max(28f * density, 48f);
            float fadeStart = Math.max(0f, availRight - fadeWidth);
            panelFadeGradient = new LinearGradient(
                    fadeStart, 0f, availRight, 0f,
                    new int[]{0x00000000, 0x88000000, 0xFF000000},
                    new float[]{0f, 0.6f, 1f},
                    Shader.TileMode.CLAMP
            );
            panelFadePaint.setShader(panelFadeGradient);
        } else {
            panelFadeGradient = null;
            panelFadePaint.setShader(null);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 1. Рисуем единый бесшовный эллиптический виньеточный градиент на весь экран
        canvas.drawRect(0, 0, w, h, vignettePaint);

        // 2. Если открыто боковое окно — рисуем мягкий переходный градиент и заливаем область под окном
        float availRight = (customAvailRight > 0f) ? Math.min((float) w, customAvailRight) : (float) w;
        if (availRight < w) {
            float fadeWidth = Math.max(28f * density, 48f);
            float fadeStart = Math.max(0f, availRight - fadeWidth);

            // Мягкое угасание света перед панелью без жесткой вертикальной линии
            if (panelFadeGradient != null) {
                canvas.drawRect(fadeStart, 0, availRight, h, panelFadePaint);
            }

            // Глубокий черный под самой боковой панелью
            canvas.drawRect(availRight, 0, w, h, solidBlackPaint);
        }
    }
}
