package com.example.animelib.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Высококачественный оверлей органического рассеивания подсветки (Ambilight).
 *
 * Особенности:
 * 1. Неравномерный порог затухания (гармонические волны затухания света вместо плоской геометрической коробки).
 * 2. Хроматическая дисперсия (спектральное затухание: холодные короткие волны синего/фиолетового цвета
 *    затухают ближе к плееру, а теплые длинные волны красного/оранжевого проникают дальше в пустое пространство).
 * 3. Подсветка плавно сходит на нет к краям экрана в глубокий чистый черный цвет (#000000), не упираясь в рамки.
 * 4. Полная поддержка обычного ландшафта, ландшафта с открытыми боковыми панелями (меню/комментарии) и портретного режима.
 */
public class AmbientVignetteOverlayView extends View {

    // Спектральная таблица затухания: холодные оттенки поглощаются раньше, теплые распространяются дальше
    private static final int[] CHROMATIC_COLORS = new int[]{
            0x00000000, // 0.00: 100% прозрачно у края видео (полная яркость подсветки)
            0x15030714, // 0.25: Первичное мягкое поглощение коротких синих волн
            0x4803060E, // 0.50: Сине-фиолетовый диапазон приглушен, зеленый и теплый свет проходят свободно
            0x92000000, // 0.72: Зеленый диапазон плавно рассеивается, теплые тона проникают глубже
            0xE0000000, // 0.90: Мягкое угасание теплого спектра
            0xFF000000  // 1.00: Чистый 100% черный цвет (#000000) до достижения границы экрана
    };

    private static final float[] CHROMATIC_STOPS = new float[]{
            0.00f, 0.25f, 0.50f, 0.72f, 0.90f, 1.00f
    };

    private final Paint solidBlackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint leftGradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint rightGradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint topGradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint bottomGradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    private final Paint cornerTopLeftPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint cornerTopRightPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint cornerBottomLeftPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint cornerBottomRightPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    private final Paint screenEdgeVignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    private final Path leftWavyPath = new Path();
    private final Path rightWavyPath = new Path();
    private final Path topWavyPath = new Path();
    private final Path bottomWavyPath = new Path();

    private final RectF currentVideoRect = new RectF();
    private final RectF customBounds = new RectF();
    private boolean hasCustomBounds = false;
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
        hasCustomBounds = true;
        customBounds.set(left, top, right, bottom);
        rebuildShaders(getWidth(), getHeight());
        invalidate();
    }

    /**
     * Сбрасывает пользовательские границы (переходит к авто-вычислению по соотношению сторон)
     */
    public void clearCustomVideoBounds() {
        hasCustomBounds = false;
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

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    /**
     * Перестраивает шейдеры и органические волнообразные границы
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

        float maxGlowReach = dpToPx(140f);

        // 1. Левая пустая область
        leftWavyPath.reset();
        if (vLeft > 1f) {
            float marginW = vLeft;
            float glowDepth = Math.min(marginW * 0.82f, maxGlowReach);
            float outerReach = marginW - glowDepth; // Базовая точка 100% черного цвета
            float waveAmp = Math.min(dpToPx(16f), glowDepth * 0.16f);

            leftGradientPaint.setShader(new LinearGradient(
                    vLeft, 0, outerReach, 0,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));

            // Строим органический волнообразный контур границы черного цвета
            leftWavyPath.moveTo(0, 0);
            leftWavyPath.lineTo(0, h);

            int steps = 24;
            float stepY = h / (float) steps;
            for (int i = steps; i >= 0; i--) {
                float y = i * stepY;
                float wave = (float) (Math.sin(y * 0.012) * 0.6 + Math.cos(y * 0.024 + 1.2) * 0.4);
                float x = Math.max(0f, outerReach + wave * waveAmp);
                if (i == steps) {
                    leftWavyPath.lineTo(x, h);
                } else {
                    leftWavyPath.lineTo(x, y);
                }
            }
            leftWavyPath.close();
        }

        // 2. Правая пустая область
        rightWavyPath.reset();
        if (vRight < w - 1f) {
            float marginW = w - vRight;
            float glowDepth = Math.min(marginW * 0.82f, maxGlowReach);
            float outerReach = vRight + glowDepth;
            float waveAmp = Math.min(dpToPx(16f), glowDepth * 0.16f);

            rightGradientPaint.setShader(new LinearGradient(
                    vRight, 0, outerReach, 0,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));

            rightWavyPath.moveTo(w, 0);
            rightWavyPath.lineTo(w, h);

            int steps = 24;
            float stepY = h / (float) steps;
            for (int i = steps; i >= 0; i--) {
                float y = i * stepY;
                float wave = (float) (Math.sin(y * 0.014 + 0.8) * 0.6 + Math.cos(y * 0.022 + 2.1) * 0.4);
                float x = Math.min((float) w, outerReach - wave * waveAmp);
                if (i == steps) {
                    rightWavyPath.lineTo(x, h);
                } else {
                    rightWavyPath.lineTo(x, y);
                }
            }
            rightWavyPath.close();
        }

        // 3. Верхняя пустая область
        topWavyPath.reset();
        if (vTop > 1f) {
            float marginH = vTop;
            float glowDepth = Math.min(marginH * 0.82f, maxGlowReach);
            float outerReach = marginH - glowDepth;
            float waveAmp = Math.min(dpToPx(16f), glowDepth * 0.16f);

            topGradientPaint.setShader(new LinearGradient(
                    0, vTop, 0, outerReach,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));

            topWavyPath.moveTo(0, 0);
            topWavyPath.lineTo(w, 0);

            int steps = 24;
            float stepX = w / (float) steps;
            for (int i = steps; i >= 0; i--) {
                float x = i * stepX;
                float wave = (float) (Math.sin(x * 0.013 + 1.5) * 0.6 + Math.cos(x * 0.026 + 0.4) * 0.4);
                float y = Math.max(0f, outerReach + wave * waveAmp);
                if (i == steps) {
                    topWavyPath.lineTo(w, y);
                } else {
                    topWavyPath.lineTo(x, y);
                }
            }
            topWavyPath.close();
        }

        // 4. Нижняя пустая область
        bottomWavyPath.reset();
        if (vBottom < h - 1f) {
            float marginH = h - vBottom;
            float glowDepth = Math.min(marginH * 0.82f, maxGlowReach);
            float outerReach = vBottom + glowDepth;
            float waveAmp = Math.min(dpToPx(16f), glowDepth * 0.16f);

            bottomGradientPaint.setShader(new LinearGradient(
                    0, vBottom, 0, outerReach,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));

            bottomWavyPath.moveTo(0, h);
            bottomWavyPath.lineTo(w, h);

            int steps = 24;
            float stepX = w / (float) steps;
            for (int i = steps; i >= 0; i--) {
                float x = i * stepX;
                float wave = (float) (Math.sin(x * 0.015 + 0.3) * 0.6 + Math.cos(x * 0.023 + 1.8) * 0.4);
                float y = Math.min((float) h, outerReach - wave * waveAmp);
                if (i == steps) {
                    bottomWavyPath.lineTo(w, y);
                } else {
                    bottomWavyPath.lineTo(x, y);
                }
            }
            bottomWavyPath.close();
        }

        // 5. Углы (радиальное рассеивание от углов видеокарты)
        float cornerRadius = dpToPx(120f);
        if (vLeft > 1f && vTop > 1f) {
            cornerTopLeftPaint.setShader(new RadialGradient(
                    vLeft, vTop, cornerRadius,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }
        if (vRight < w - 1f && vTop > 1f) {
            cornerTopRightPaint.setShader(new RadialGradient(
                    vRight, vTop, cornerRadius,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }
        if (vLeft > 1f && vBottom < h - 1f) {
            cornerBottomLeftPaint.setShader(new RadialGradient(
                    vLeft, vBottom, cornerRadius,
                    CHROMATIC_COLORS, CHROMATIC_STOPS, Shader.TileMode.CLAMP
            ));
        }
        if (vRight < w - 1f && vBottom < h - 1f) {
            cornerBottomRightPaint.setShader(new RadialGradient(
                    vRight, vBottom, cornerRadius,
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

        // 1. Отрисовка левой области: градиент рассеивания + органическая черная отсечка
        if (vLeft > 1f) {
            canvas.drawRect(0, 0, vLeft, h, leftGradientPaint);
            if (!leftWavyPath.isEmpty()) {
                canvas.drawPath(leftWavyPath, solidBlackPaint);
            }
        }

        // 2. Отрисовка правой области
        if (vRight < w - 1f) {
            canvas.drawRect(vRight, 0, w, h, rightGradientPaint);
            if (!rightWavyPath.isEmpty()) {
                canvas.drawPath(rightWavyPath, solidBlackPaint);
            }
        }

        // 3. Отрисовка верхней области
        if (vTop > 1f) {
            canvas.drawRect(0, 0, w, vTop, topGradientPaint);
            if (!topWavyPath.isEmpty()) {
                canvas.drawPath(topWavyPath, solidBlackPaint);
            }
        }

        // 4. Отрисовка нижней области
        if (vBottom < h - 1f) {
            canvas.drawRect(0, vBottom, w, h, bottomGradientPaint);
            if (!bottomWavyPath.isEmpty()) {
                canvas.drawPath(bottomWavyPath, solidBlackPaint);
            }
        }

        // 5. Отрисовка углов (плавное соединение боковых и верхних/нижних градиентов)
        if (vLeft > 1f && vTop > 1f) {
            canvas.drawRect(0, 0, vLeft, vTop, cornerTopLeftPaint);
        }
        if (vRight < w - 1f && vTop > 1f) {
            canvas.drawRect(vRight, 0, w, vTop, cornerTopRightPaint);
        }
        if (vLeft > 1f && vBottom < h - 1f) {
            canvas.drawRect(0, vBottom, vLeft, h, cornerBottomLeftPaint);
        }
        if (vRight < w - 1f && vBottom < h - 1f) {
            canvas.drawRect(vRight, vBottom, w, h, cornerBottomRightPaint);
        }
    }
}
