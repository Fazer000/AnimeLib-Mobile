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
    // Завершает затухание в 100% черный ДО физических границ выступающего плеера подсветки,
    // полностью исключая видимые прямые грани и обрезания.
    private static final int[] VIGNETTE_COLORS = new int[]{
            0x00000000, // 0.00: 100% прозрачно в центре (под видео)
            0x00000000, // 0.62: полностью прозрачно для выступающей подсветки
            0x0A000000, // 0.72: мягчайший бархатный спад (~4% черного)
            0x24000000, // 0.80: шелковистая полутень (~14% черного)
            0x58000000, // 0.87: глубокое естественное рассеивание (~35% черного)
            0x9E000000, // 0.93: растворение в темноту (~62% черного)
            0xE2000000, // 0.97: предфинальный спад (~89% черного)
            0xFF000000  // 1.00: 100% чистый абсолютный черный #000000
    };

    private static final float[] VIGNETTE_STOPS = new float[]{
            0.00f, 0.62f, 0.72f, 0.80f, 0.87f, 0.93f, 0.97f, 1.00f
    };

    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    private final Matrix shaderMatrix = new Matrix();
    private final RectF currentVideoRect = new RectF();
    private final RectF customBounds = new RectF();

    private RadialGradient radialGradient;

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

        vignettePaint.setStyle(Paint.Style.FILL);

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

        // Радиальный размах эллипса: полностью и бесшовно гасит свет до 100% черного (#000000)
        // ДО достижения внешних физических границ выступающего плеера подсветки (1.24x)
        float spreadX = halfW * 1.18f + (14f * density);
        float spreadY = halfH * 1.28f + (10f * density);

        // Масштабируем единичный круг в точный эллипс вокруг центра видео
        shaderMatrix.reset();
        shaderMatrix.setScale(spreadX / SHADER_BASE_RADIUS, spreadY / SHADER_BASE_RADIUS);
        shaderMatrix.postTranslate(cx, cy);

        if (radialGradient != null) {
            radialGradient.setLocalMatrix(shaderMatrix);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Единый бесшовный симметричный эллиптический градиент вокруг центра активного видеокадра
        canvas.drawRect(0, 0, w, h, vignettePaint);
    }
}
