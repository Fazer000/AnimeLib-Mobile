package com.example.animelib.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Оверлей бесшовного органического рассеивания подсветки (Ambilight).
 *
 * Архитектурные принципы:
 * 1. 100% непрерывное затухание по плавной S-кривой без каких-либо видимых линий, стыков или ступеней.
 * 2. Хроматическая дисперсия: сине-фиолетовые короткие волны мягко затухают ближе к плееру,
 *    а теплые красные и оранжевые оттенки проникают дальше в темное пространство.
 * 3. К краям экрана и границе бокового окна свет плавно и незаметно растворяется в чистый глубокий черный цвет (#000000).
 * 4. Полная поддержка обычного ландшафта, интерактивного открытия боковых окон и портретного режима.
 */
public class AmbientVignetteOverlayView extends View {

    // Спектральная таблица плавного хроматического рассеивания:
    // Сине-фиолетовый диапазон мягко поглощается в первой половине расстояния,
    // в то время как теплый красный/оранжевый свет проникает дальше в темноту.
    // На границе экрана достигается 100% чистый черный цвет (#000000) без каких-либо видимых линий или полос.
    private static final int[] CHROMATIC_COLORS = new int[]{
            0x00000000, // 0.00: 100% прозрачно у края видео (полная яркость подсветки)
            0x05010308, // 0.15: Естественное начало мягкого рассеивания
            0x1C030612, // 0.35: Мягкое поглощение коротких синих волн
            0x4803060E, // 0.55: Сине-фиолетовый спектр приглушен, теплые тона продолжают светить
            0x88000000, // 0.75: Зеленый и нейтральный свет плавно угасают, глубокий теплый ореол идет дальше
            0xD5000000, // 0.90: Финальное мягкое растворение теплого спектра
            0xFF000000  // 1.00: Чистый 100% черный цвет (#000000) у внешней границы
    };

    private static final float[] CHROMATIC_STOPS = new float[]{
            0.00f, 0.15f, 0.35f, 0.55f, 0.75f, 0.90f, 1.00f
    };

    private final Paint solidBlackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint leftPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint rightPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint topPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint bottomPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    private final Paint cornerTopLeftPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint cornerTopRightPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint cornerBottomLeftPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint cornerBottomRightPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    private final RectF currentVideoRect = new RectF();
    private final RectF customBounds = new RectF();
    private boolean hasCustomBounds = false;
    private float customAvailRight = -1f;
    private float videoAspectRatio = 16f / 9f;

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
        solidBlackPaint.setColor(0xFF000000);
        solidBlackPaint.setStyle(Paint.Style.FILL);
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
        rebuildShaders(getWidth(), getHeight());
        invalidate();
    }

    /**
     * Сбрасывает пользовательские границы (переходит к авто-вычислению по соотношению сторон)
     */
    public void clearCustomVideoBounds() {
        hasCustomBounds = false;
        customAvailRight = -1f;
        rebuildShaders(getWidth(), getHeight());
        invalidate();
    }

    /**
     * Устанавливает соотношение сторон видео для автоматического центрирования
     */
    public void setVideoAspectRatio(float aspect) {
        if (aspect > 0 && Math.abs(this.videoAspectRatio - aspect) > 0.01f) {
            this.videoAspectRatio = aspect;
            rebuildShaders(getWidth(), getHeight());
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w;
            lastHeight = h;
            rebuildShaders(w, h);
        }
    }

    /**
     * Перестраивает гладкие спектральные градиенты
     */
    private void rebuildShaders(int w, int h) {
        if (w <= 0 || h <= 0) return;

        // Вычисляем прямоугольник основного видео
        if (hasCustomBounds && customBounds.width() > 0 && customBounds.height() > 0) {
            currentVideoRect.set(customBounds);
        } else {
            float aspect = videoAspectRatio > 0 ? videoAspectRatio : 16f / 9f;
            float fullVideoW = Math.min((float) w, (float) h * aspect);
            float fullVideoH = Math.min((float) h, (float) w / aspect);
            float left = (w - fullVideoW) / 2f;
            float top = (h - fullVideoH) / 2f;
            currentVideoRect.set(left, top, left + fullVideoW, top + fullVideoH);
        }

        float vLeft = Math.max(0f, currentVideoRect.left);
        float vTop = Math.max(0f, currentVideoRect.top);
        float vRight = Math.min((float) w, currentVideoRect.right);
        float vBottom = Math.min((float) h, currentVideoRect.bottom);

        float availLeft = 0f;
        float availRight = (customAvailRight > 0f) ? Math.min((float) w, customAvailRight) : (float) w;
        float availTop = 0f;
        float availBottom = (float) h;

        // 1. Левая пустая область: плавный спектральный спад от края видео (0% черного) к левому краю экрана (100% черного)
        if (vLeft > availLeft + 1f) {
            leftPaint.setShader(new LinearGradient(
                    vLeft, 0, availLeft, 0,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }

        // 2. Правая пустая область: спад от правого края видео к границе экрана или бокового окна
        if (vRight < availRight - 1f) {
            rightPaint.setShader(new LinearGradient(
                    vRight, 0, availRight, 0,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }

        // 3. Верхняя пустая область: спад от верхнего края видео к верхнему краю экрана
        if (vTop > availTop + 1f) {
            topPaint.setShader(new LinearGradient(
                    0, vTop, 0, availTop,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }

        // 4. Нижняя пустая область: спад от нижнего края видео к нижнему краю экрана
        if (vBottom < availBottom - 1f) {
            bottomPaint.setShader(new LinearGradient(
                    0, vBottom, 0, availBottom,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }

        // 5. Углы: радиальные градиенты со спектральным спадом из 4 углов видеокарты
        if (vLeft > availLeft + 1f && vTop > availTop + 1f) {
            float rx = vLeft - availLeft;
            float ry = vTop - availTop;
            float radius = (float) Math.hypot(rx, ry);
            cornerTopLeftPaint.setShader(new RadialGradient(
                    vLeft, vTop, radius,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }

        if (vRight < availRight - 1f && vTop > availTop + 1f) {
            float rx = availRight - vRight;
            float ry = vTop - availTop;
            float radius = (float) Math.hypot(rx, ry);
            cornerTopRightPaint.setShader(new RadialGradient(
                    vRight, vTop, radius,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }

        if (vLeft > availLeft + 1f && vBottom < availBottom - 1f) {
            float rx = vLeft - availLeft;
            float ry = availBottom - vBottom;
            float radius = (float) Math.hypot(rx, ry);
            cornerBottomLeftPaint.setShader(new RadialGradient(
                    vLeft, vBottom, radius,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }

        if (vRight < availRight - 1f && vBottom < availBottom - 1f) {
            float rx = availRight - vRight;
            float ry = availBottom - vBottom;
            float radius = (float) Math.hypot(rx, ry);
            cornerBottomRightPaint.setShader(new RadialGradient(
                    vRight, vBottom, radius,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float vLeft = Math.max(0f, currentVideoRect.left);
        float vTop = Math.max(0f, currentVideoRect.top);
        float vRight = Math.min((float) w, currentVideoRect.right);
        float vBottom = Math.min((float) h, currentVideoRect.bottom);

        float availLeft = 0f;
        float availRight = (customAvailRight > 0f) ? Math.min((float) w, customAvailRight) : (float) w;
        float availTop = 0f;
        float availBottom = (float) h;

        // Если открыто боковое окно справа: глушим область под окном сплошным черным
        if (availRight < w) {
            canvas.drawRect(availRight, 0, w, h, solidBlackPaint);
        }

        // 1. Левая область рассеивания
        if (vLeft > availLeft + 1f) {
            canvas.drawRect(availLeft, vTop, vLeft, vBottom, leftPaint);
        }

        // 2. Правая область рассеивания
        if (vRight < availRight - 1f) {
            canvas.drawRect(vRight, vTop, availRight, vBottom, rightPaint);
        }

        // 3. Верхняя область рассеивания
        if (vTop > availTop + 1f) {
            canvas.drawRect(vLeft, availTop, vRight, vTop, topPaint);
        }

        // 4. Нижняя область рассеивания
        if (vBottom < availBottom - 1f) {
            canvas.drawRect(vLeft, vBottom, vRight, availBottom, bottomPaint);
        }

        // 5. Угловые области (радиальное рассеивание из углов видеокарты)
        if (vLeft > availLeft + 1f && vTop > availTop + 1f) {
            canvas.drawRect(availLeft, availTop, vLeft, vTop, cornerTopLeftPaint);
        }
        if (vRight < availRight - 1f && vTop > availTop + 1f) {
            canvas.drawRect(vRight, availTop, availRight, vTop, cornerTopRightPaint);
        }
        if (vLeft > availLeft + 1f && vBottom < availBottom - 1f) {
            canvas.drawRect(availLeft, vBottom, vLeft, availBottom, cornerBottomLeftPaint);
        }
        if (vRight < availRight - 1f && vBottom < availBottom - 1f) {
            canvas.drawRect(vRight, vBottom, availRight, availBottom, cornerBottomRightPaint);
        }
    }
}
