package com.example.animelib.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * Drawable активного матового стекла (зернистое оптическое размытие) для HUD-элементов плеера.
 *
 * - На Android 13+ (API 33+): единый аппаратный AGSL RuntimeShader выполняет оптическое
 *   микро-рассеивание (преломление живого размытого видеокадра по мелкозернистой матрице стекла),
 *   добавляет тактильную кристаллическую зернистость, тёмную тонировку и мягкий верхний блик
 *   за один проход GPU без saveLayer и clipPath.
 * - На Android 10–12 (API 29–32): многослойный аппаратный Canvas с тем же живым
 *   размытым видеокадром, бесшовным тайлингом матовой зернистости, верхним бликом и тонкой рамкой.
 */
public class FrostedGlassDrawable extends Drawable {

    private static final int FALLBACK_BG_NORMAL = 0xBD0A0C12;
    private static final int FALLBACK_BG_SELECTED = 0xC8160E28;
    private static final int TINT_OVERLAY_NORMAL = 0x9E090B11;
    private static final int TINT_OVERLAY_SELECTED = 0xA8160E28;
    private static final int STROKE_SELECTED_COLOR = 0xFF8852DE; // @color/purple_primary

    // AGSL-шейдер оптического матового зернистого стекла (API 33+)
    private static final String FROSTED_GLASS_AGSL =
            "uniform shader uBackdrop;\n" +
            "uniform float2 uSize;\n" +
            "uniform float uDensity;\n" +
            "uniform float uScatterPx;\n" +
            "uniform float4 uTintColor;\n" +
            "uniform float uGrainStrength;\n" +
            "uniform float uSheenStrength;\n" +
            "\n" +
            "float2 hash22(float2 p) {\n" +
            "    float3 p3 = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));\n" +
            "    p3 += dot(p3, p3.yzx + 33.33);\n" +
            "    return fract((p3.xx + p3.yz) * p3.zy);\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 fragCoord) {\n" +
            "    float cell = max(1.0, floor(uDensity * 0.58 + 0.5));\n" +
            "    float2 grainCell = floor(fragCoord / cell);\n" +
            "    float2 rnd = hash22(grainCell);\n" +
            "    float2 rnd2 = hash22(grainCell + float2(71.3, 19.7));\n" +
            "\n" +
            "    float2 jitter = ((rnd + rnd2) * 0.5 - 0.5) * uScatterPx;\n" +
            "    half3 bg = uBackdrop.eval(fragCoord + jitter).rgb;\n" +
            "\n" +
            "    half3 color = mix(bg, half3(uTintColor.rgb), half(uTintColor.a));\n" +
            "\n" +
            "    float grain = (rnd.x + rnd2.y) - 1.0;\n" +
            "    color += half3(grain * uGrainStrength);\n" +
            "\n" +
            "    float v = clamp(fragCoord.y / max(uSize.y, 1.0), 0.0, 1.0);\n" +
            "    float inv = 1.0 - v;\n" +
            "    float topSheen = inv * inv * inv * uSheenStrength;\n" +
            "    color += half3(topSheen);\n" +
            "\n" +
            "    return half4(clamp(color, half3(0.0), half3(1.0)), 1.0);\n" +
            "}\n";

    private static volatile boolean sAgslUnavailable = false;

    private final WeakReference<View> hostViewRef;
    private final PlayerBackdropBlurManager manager;
    private final float cornerRadiusPx;
    private final boolean isPill;
    private final float density;

    private boolean isSelected;
    private int drawableAlpha = 255;

    private final RectF boundsRect = new RectF();
    private final RectF strokeRect = new RectF();
    private final Matrix backdropMatrix = new Matrix();
    private final Matrix grainMatrix = new Matrix();
    private final int[] tmpViewLoc = new int[2];

    private final Paint agslPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable
    private BitmapShader localBackdropShader;
    private long cachedFrameGeneration = -1L;

    @Nullable
    private BitmapShader grainShader;
    @Nullable
    private Object runtimeShaderObj; // android.graphics.RuntimeShader на API 33+

    private float lastSheenHeight = -1f;
    private float lastBorderHeight = -1f;
    private boolean lastBorderSelected = false;

    // Последняя отрисованная геометрия (для детекции движения при скролле/анимации)
    private int lastDrawnViewX = Integer.MIN_VALUE;
    private int lastDrawnViewY = Integer.MIN_VALUE;
    private float lastDrawnScaleX = 1f;
    private float lastDrawnScaleY = 1f;
    private float lastDrawnVideoX = Float.NaN;
    private float lastDrawnVideoY = Float.NaN;
    private float lastDrawnVideoW = Float.NaN;
    private float lastDrawnVideoH = Float.NaN;

    public FrostedGlassDrawable(@NonNull View hostView,
                                @NonNull PlayerBackdropBlurManager manager,
                                float cornerRadiusPx,
                                boolean isPill,
                                boolean selected) {
        this.hostViewRef = new WeakReference<>(hostView);
        this.manager = manager;
        this.cornerRadiusPx = cornerRadiusPx;
        this.isPill = isPill;
        this.density = manager.getDensity();
        this.isSelected = selected;

        strokePaint.setStyle(Paint.Style.STROKE);
        updateStrokeStyle(0f);
    }

    @Nullable
    View getHostView() {
        return hostViewRef.get();
    }

    public void setSelectedState(boolean selected) {
        if (this.isSelected != selected) {
            this.isSelected = selected;
            lastBorderHeight = -1f;
            invalidateSelf();
        }
    }

    boolean hasGeometryChangedOnScreen(float videoX, float videoY, float videoW, float videoH) {
        View host = hostViewRef.get();
        if (host == null) return false;
        host.getLocationOnScreen(tmpViewLoc);
        float scaleX = computeTotalScaleX(host);
        float scaleY = computeTotalScaleY(host);
        return Math.abs(tmpViewLoc[0] - lastDrawnViewX) >= 1
                || Math.abs(tmpViewLoc[1] - lastDrawnViewY) >= 1
                || Math.abs(scaleX - lastDrawnScaleX) > 0.005f
                || Math.abs(scaleY - lastDrawnScaleY) > 0.005f
                || Math.abs(videoX - lastDrawnVideoX) >= 1f
                || Math.abs(videoY - lastDrawnVideoY) >= 1f
                || Math.abs(videoW - lastDrawnVideoW) >= 1f
                || Math.abs(videoH - lastDrawnVideoH) >= 1f;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        boundsRect.set(bounds);
        lastSheenHeight = -1f;
        lastBorderHeight = -1f;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int width = bounds.width();
        int height = bounds.height();
        if (width <= 0 || height <= 0) return;

        manager.notifyDrawableDrawn();

        boundsRect.set(bounds);
        float radius = isPill ? (Math.min(width, height) * 0.5f) : cornerRadiusPx;

        View host = hostViewRef.get();
        Bitmap blurred = manager.getBlurredBitmap();
        float videoW = manager.getVideoScreenW();
        float videoH = manager.getVideoScreenH();

        boolean drewLiveBackdrop = false;

        if (host != null && blurred != null && videoW > 1f && videoH > 1f) {
            long gen = manager.getFrameGeneration();
            if (localBackdropShader == null || cachedFrameGeneration != gen) {
                localBackdropShader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                cachedFrameGeneration = gen;
            }

            host.getLocationOnScreen(tmpViewLoc);
            float viewX = tmpViewLoc[0];
            float viewY = tmpViewLoc[1];
            float totalScaleX = computeTotalScaleX(host);
            float totalScaleY = computeTotalScaleY(host);
            float videoX = manager.getVideoScreenX();
            float videoY = manager.getVideoScreenY();

            lastDrawnViewX = tmpViewLoc[0];
            lastDrawnViewY = tmpViewLoc[1];
            lastDrawnScaleX = totalScaleX;
            lastDrawnScaleY = totalScaleY;
            lastDrawnVideoX = videoX;
            lastDrawnVideoY = videoY;
            lastDrawnVideoW = videoW;
            lastDrawnVideoH = videoH;

            float invScaleX = totalScaleX > 0.01f ? (1f / totalScaleX) : 1f;
            float invScaleY = totalScaleY > 0.01f ? (1f / totalScaleY) : 1f;

            float sx = (videoW / (float) PlayerBackdropBlurManager.BLUR_WIDTH) * invScaleX;
            float sy = (videoH / (float) PlayerBackdropBlurManager.BLUR_HEIGHT) * invScaleY;
            float tx = (videoX - viewX) * invScaleX + bounds.left;
            float ty = (videoY - viewY) * invScaleY + bounds.top;

            backdropMatrix.reset();
            backdropMatrix.setScale(sx, sy);
            backdropMatrix.postTranslate(tx, ty);
            localBackdropShader.setLocalMatrix(backdropMatrix);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !sAgslUnavailable) {
                drewLiveBackdrop = drawWithAgslShader(canvas, width, height, radius, localBackdropShader);
            }

            if (!drewLiveBackdrop) {
                backdropPaint.setShader(localBackdropShader);
                backdropPaint.setAlpha(drawableAlpha);
                canvas.drawRoundRect(boundsRect, radius, radius, backdropPaint);

                tintPaint.setColor(isSelected ? TINT_OVERLAY_SELECTED : TINT_OVERLAY_NORMAL);
                tintPaint.setAlpha(scaleAlpha(tintPaint.getAlpha()));
                canvas.drawRoundRect(boundsRect, radius, radius, tintPaint);

                drawSheenAndGrainFallback(canvas, height, radius);
                drewLiveBackdrop = true;
            }
        }

        if (!drewLiveBackdrop) {
            tintPaint.setColor(isSelected ? FALLBACK_BG_SELECTED : FALLBACK_BG_NORMAL);
            tintPaint.setAlpha(scaleAlpha(tintPaint.getAlpha()));
            canvas.drawRoundRect(boundsRect, radius, radius, tintPaint);
            drawSheenAndGrainFallback(canvas, height, radius);
        }

        // Тонкая стеклянная окантовка (верхний край чуть светлее для объёма матового стекла)
        updateStrokeStyle(height);
        float halfStroke = strokePaint.getStrokeWidth() * 0.5f;
        strokeRect.set(
                boundsRect.left + halfStroke,
                boundsRect.top + halfStroke,
                boundsRect.right - halfStroke,
                boundsRect.bottom - halfStroke
        );
        float strokeRadius = Math.max(0f, radius - halfStroke);
        canvas.drawRoundRect(strokeRect, strokeRadius, strokeRadius, strokePaint);
    }

    private boolean drawWithAgslShader(@NonNull Canvas canvas,
                                       int width,
                                       int height,
                                       float radius,
                                       @NonNull BitmapShader backdropShader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        try {
            RuntimeShader shader;
            if (runtimeShaderObj instanceof RuntimeShader) {
                shader = (RuntimeShader) runtimeShaderObj;
            } else {
                shader = new RuntimeShader(FROSTED_GLASS_AGSL);
                runtimeShaderObj = shader;
            }

            shader.setInputShader("uBackdrop", backdropShader);
            shader.setFloatUniform("uSize", (float) width, (float) height);
            shader.setFloatUniform("uDensity", density);
            shader.setFloatUniform("uScatterPx", 4.5f * density);

            if (isSelected) {
                // Приглушённое тёмное матовое стекло с аккуратным фиолетовым акцентом
                shader.setFloatUniform("uTintColor", 0.082f, 0.052f, 0.152f, 0.66f);
                shader.setFloatUniform("uGrainStrength", 0.040f);
                shader.setFloatUniform("uSheenStrength", 0.034f);
            } else {
                // Тёмное дымчатое матовое стекло в тон интерфейса плеера
                shader.setFloatUniform("uTintColor", 0.034f, 0.041f, 0.062f, 0.62f);
                shader.setFloatUniform("uGrainStrength", 0.042f);
                shader.setFloatUniform("uSheenStrength", 0.026f);
            }

            agslPaint.setShader(shader);
            agslPaint.setAlpha(drawableAlpha);
            canvas.drawRoundRect(boundsRect, radius, radius, agslPaint);
            return true;
        } catch (Throwable ignored) {
            sAgslUnavailable = true;
            runtimeShaderObj = null;
            return false;
        }
    }

    private void drawSheenAndGrainFallback(@NonNull Canvas canvas, float height, float radius) {
        if (Math.abs(height - lastSheenHeight) > 0.5f) {
            lastSheenHeight = height;
            sheenPaint.setShader(new LinearGradient(
                    0f, boundsRect.top,
                    0f, boundsRect.top + height * 0.45f,
                    0x0CFFFFFF,
                    0x00FFFFFF,
                    Shader.TileMode.CLAMP
            ));
        }
        sheenPaint.setAlpha(drawableAlpha);
        canvas.drawRoundRect(boundsRect, radius, radius, sheenPaint);

        if (grainShader == null) {
            Bitmap grainBmp = PlayerBackdropBlurManager.getSharedGrainBitmap();
            grainShader = new BitmapShader(grainBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            float grainScale = Math.max(1.0f, (float) Math.floor(density * 0.58f + 0.5f));
            grainMatrix.reset();
            grainMatrix.setScale(grainScale, grainScale);
            grainShader.setLocalMatrix(grainMatrix);
            grainPaint.setShader(grainShader);
        }
        grainPaint.setAlpha(drawableAlpha);
        canvas.drawRoundRect(boundsRect, radius, radius, grainPaint);
    }

    private void updateStrokeStyle(float height) {
        if (Math.abs(height - lastBorderHeight) <= 0.5f && isSelected == lastBorderSelected) {
            strokePaint.setAlpha(drawableAlpha);
            return;
        }
        lastBorderHeight = height;
        lastBorderSelected = isSelected;

        if (isSelected) {
            strokePaint.setShader(null);
            strokePaint.setStrokeWidth(1.0f * density);
            strokePaint.setColor(STROKE_SELECTED_COLOR);
            strokePaint.setAlpha(drawableAlpha);
        } else {
            strokePaint.setStrokeWidth(0.55f * density);
            strokePaint.setColor(0xFFFFFFFF);
            if (height > 1f) {
                strokePaint.setShader(new LinearGradient(
                        0f, boundsRect.top,
                        0f, boundsRect.bottom,
                        0x38FFFFFF,
                        0x1CFFFFFF,
                        Shader.TileMode.CLAMP
                ));
            } else {
                strokePaint.setShader(null);
                strokePaint.setColor(0x2EFFFFFF);
            }
            strokePaint.setAlpha(drawableAlpha);
        }
    }

    private int scaleAlpha(int baseAlpha) {
        return (baseAlpha * drawableAlpha) / 255;
    }

    private static float computeTotalScaleX(@NonNull View view) {
        float scale = 1f;
        View current = view;
        int depth = 0;
        while (current != null && depth < 12) {
            scale *= current.getScaleX();
            ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
            depth++;
        }
        return scale;
    }

    private static float computeTotalScaleY(@NonNull View view) {
        float scale = 1f;
        View current = view;
        int depth = 0;
        while (current != null && depth < 12) {
            scale *= current.getScaleY();
            ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
            depth++;
        }
        return scale;
    }

    @Override
    public void setAlpha(int alpha) {
        this.drawableAlpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        agslPaint.setColorFilter(colorFilter);
        backdropPaint.setColorFilter(colorFilter);
        tintPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
