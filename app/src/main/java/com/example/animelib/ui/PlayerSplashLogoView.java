package com.example.animelib.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.PathParser;

import com.example.animelib.R;

/**
 * Кастомный векторный логотип сплешскрина плеера с раздельной хореографией элементов:
 * верхней левой скобки (┏), нижней правой скобки (┛) и центральной литеры «A».
 */
public class PlayerSplashLogoView extends View {

    private static final float VIEWPORT_W = 103.8f;
    private static final float VIEWPORT_H = 104.2f;

    private static final long ENTRY_DURATION_MS = 380L;
    private static final long LOOP_BLEND_START_MS = 300L;
    private static final long LOOP_BLEND_DURATION_MS = 300L;
    private static final long CYCLE_DURATION_MS = 1460L;
    private static final long EXIT_DURATION_MS = 240L;

    private static final String SVG_BRACKET_TL =
            "M41.8,2.3v8.4c0,1.2 -1,2.2 -2.2,2.2H15.6c-1.5,0 -2.8,1.3 -2.8,2.8v24"
                    + "c0,1.2 -1,2.2 -2.2,2.2H2.2c-1.2,0 -2.2,-1 -2.2,-2.2V3.4"
                    + "C0,1.5 1.5,0 3.4,0h36.3c1.2,0 2.2,1 2.2,2.2l-0.1,0.1Z";

    private static final String SVG_BRACKET_BR =
            "M62,101.9v-8.4c0,-1.2 1,-2.2 2.2,-2.2h24c1.5,0 2.8,-1.3 2.8,-2.8v-24"
                    + "c0,-1.2 1,-2.2 2.2,-2.2h8.4c1.2,0 2.2,1 2.2,2.2v36.3"
                    + "c0,1.9 -1.5,3.4 -3.4,3.4h-36.3c-1.2,0 -2.2,-1 -2.2,-2.2l0.1,-0.1Z";

    private static final String SVG_LETTER_A =
            "M26.2,79.5c-0.4,0 -0.8,-0.2 -1.1,-0.5s-0.5,-0.7 -0.5,-1.1v-0.7l18.4,-50.3"
                    + "c0.2,-0.6 0.5,-1.1 1,-1.5s1.2,-0.7 2.1,-0.7h11.6c0.9,0 1.6,0.2 2.1,0.7 "
                    + "0.5,0.4 0.8,1 1,1.5l18.3,50.3c0.1,0.2 0.2,0.4 0.2,0.7 0,0.4 -0.2,0.8 -0.5,1.1"
                    + "s-0.7,0.5 -1.2,0.5h-9.6c-0.8,0 -1.4,-0.2 -1.8,-0.6s-0.6,-0.7 -0.7,-1.1"
                    + "l-3.1,-8h-20.9l-3,8c-0.1,0.3 -0.3,0.7 -0.7,1.1 -0.4,0.4 -1,0.6 -1.9,0.6h-9.7Z"
                    + "M44.6,58.6h14.6l-7.3,-20.7 -7.3,20.7h0Z";

    private static final String SVG_LETTER_M =
            "M25.2,79.5c-0.9,0 -1.6,-0.7 -1.6,-1.6v-50c0,-0.9 0.7,-1.6 1.6,-1.6h9.5"
                    + "c0.8,0 1.5,0.4 1.8,1.1l15.3,32.5 15.3,-32.5c0.3,-0.7 1,-1.1 1.8,-1.1h9.5"
                    + "c0.9,0 1.6,0.7 1.6,1.6v50c0,0.9 -0.7,1.6 -1.6,1.6h-8.2"
                    + "c-0.9,0 -1.6,-0.7 -1.6,-1.6v-34l-13.4,28.2c-0.4,0.8 -1.2,1.3 -2.1,1.3h-2.8"
                    + "c-0.9,0 -1.7,-0.5 -2.1,-1.3l-13.4,-28.2v34c0,0.9 -0.7,1.6 -1.6,1.6h-8.2Z";

    private static final String SVG_LETTER_R =
            "M26,79.5c-0.9,0 -1.6,-0.7 -1.6,-1.6v-50c0,-0.9 0.7,-1.6 1.6,-1.6h24.5"
                    + "c11.5,0 19,6.5 19,16.5 0,7.2 -4.2,12.8 -10.8,15.2l12.5,20"
                    + "c0.4,0.6 0.3,1.5 -0.3,2 -0.3,0.3 -0.7,0.5 -1.2,0.5h-10.5"
                    + "c-0.7,0 -1.4,-0.4 -1.8,-1l-11.8,-19.2h-10.8v18.6c0,0.9 -0.7,1.6 -1.6,1.6H26Z"
                    + "M36.8,49.2h12.5c5.2,0 8.8,-2.8 8.8,-7.5 0,-4.7 -3.6,-7.5 -8.8,-7.5H36.8v15Z";

    private static final String SVG_LETTER_H =
            "M26,79.5c-0.9,0 -1.6,-0.7 -1.6,-1.6v-50c0,-0.9 0.7,-1.6 1.6,-1.6h8.2"
                    + "c0.9,0 1.6,0.7 1.6,1.6v20h22.2v-20c0,-0.9 0.7,-1.6 1.6,-1.6h8.2"
                    + "c0.9,0 1.6,0.7 1.6,1.6v50c0,0.9 -0.7,1.6 -1.6,1.6h-8.2"
                    + "c-0.9,0 -1.6,-0.7 -1.6,-1.6v-20.2H37.4v20.2c0,0.9 -0.7,1.6 -1.6,1.6H26Z";

    private static final String SVG_LETTER_S =
            "M35.2,79.5c-6.8,0 -12,-4.8 -12,-11.5 0,-0.9 0.7,-1.6 1.6,-1.6h7.8"
                    + "c0.8,0 1.5,0.6 1.6,1.4 0.4,3 2.8,4.7 5.8,4.7 3.2,0 5.6,-1.6 5.6,-3.9"
                    + "c0,-2.1 -1.6,-3.3 -6.6,-4.4l-3.8,-0.8c-8.2,-1.8 -12.4,-5.6 -12.4,-12"
                    + "0,-7.2 5.8,-12.2 13.8,-12.2 6.5,0 11.4,4.2 11.7,10.6 0.1,0.9 -0.6,1.6 -1.5,1.6h-7.6"
                    + "c-0.8,0 -1.5,-0.6 -1.6,-1.4 -0.3,-2.6 -2.4,-4.1 -5.2,-4.1 -2.8,0 -5,1.5 -5,3.6"
                    + "0,2 1.6,3.1 6.2,4.1l3.8,0.8c8.5,1.9 12.8,5.8 12.8,12.2 0,7.6 -6.1,12.9 -14.6,12.9Z";

    private String currentSiteKey = "animelib";

    private Path rawBracketTL;
    private Path rawBracketBR;
    private Path rawLetterA;
    private Path rawTraceTL;
    private Path rawTraceBR;

    private final Path scaledBracketTL = new Path();
    private final Path scaledBracketBR = new Path();
    private final Path scaledLetterA = new Path();
    private final Path scaledTraceTL = new Path();
    private final Path scaledTraceBR = new Path();

    private final Path activeSegmentTL = new Path();
    private final Path activeSegmentBR = new Path();

    private final PathMeasure pathMeasureTL = new PathMeasure();
    private final PathMeasure pathMeasureBR = new PathMeasure();
    private float traceLengthTL = 0f;
    private float traceLengthBR = 0f;

    private final RectF bracketTLBounds = new RectF();
    private final RectF bracketBRBounds = new RectF();
    private final RectF letterABounds = new RectF();

    private final Matrix transformMatrix = new Matrix();
    private final Matrix crestMatrix = new Matrix();
    private final Matrix sheenMatrix = new Matrix();

    private final float[] headPos = new float[2];

    private final Paint letterAPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterASheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketCrestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private RadialGradient bracketCrestShader;
    private LinearGradient letterASheenShader;

    private int brandPurpleColor;
    private int letterAColor;
    private boolean isNightMode;

    private float viewportScale = 1f;
    private float centerX = 0f;
    private float centerY = 0f;
    private float sheenHalfBand = 24f;

    private boolean isRunning = false;
    private boolean isExiting = false;
    private long startTimeUptimeMs = 0L;
    private long exitStartUptimeMs = 0L;
    private float frozenLoopBlendOnExit = 0f;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!isRunning) return;
            invalidate();
            if (isRunning && getVisibility() == VISIBLE && isAttachedToWindow()) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public PlayerSplashLogoView(Context context) {
        super(context);
        init(context);
    }

    public PlayerSplashLogoView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public PlayerSplashLogoView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        rawBracketTL = PathParser.createPathFromPathData(SVG_BRACKET_TL);
        rawBracketBR = PathParser.createPathFromPathData(SVG_BRACKET_BR);
        rawLetterA = PathParser.createPathFromPathData(SVG_LETTER_A);
        if (rawLetterA != null) {
            rawLetterA.setFillType(Path.FillType.EVEN_ODD);
        }

        // Осевые траектории вдоль скобок для плавного пробега светового импульса
        rawTraceTL = new Path();
        rawTraceTL.moveTo(6.4f, 43.2f);
        rawTraceTL.lineTo(6.4f, 9.4f);
        rawTraceTL.quadTo(6.4f, 6.45f, 9.4f, 6.45f);
        rawTraceTL.lineTo(43.2f, 6.45f);

        rawTraceBR = new Path();
        rawTraceBR.moveTo(97.4f, 61.0f);
        rawTraceBR.lineTo(97.4f, 94.8f);
        rawTraceBR.quadTo(97.4f, 97.75f, 94.4f, 97.75f);
        rawTraceBR.lineTo(60.6f, 97.75f);

        PorterDuffXfermode srcAtop = new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);

        letterAPaint.setStyle(Paint.Style.FILL);
        letterASheenPaint.setStyle(Paint.Style.FILL);
        letterASheenPaint.setXfermode(srcAtop);

        bracketBasePaint.setStyle(Paint.Style.FILL);

        bracketPulsePaint.setStyle(Paint.Style.STROKE);
        bracketPulsePaint.setStrokeCap(Paint.Cap.ROUND);
        bracketPulsePaint.setStrokeJoin(Paint.Join.ROUND);
        bracketPulsePaint.setXfermode(srcAtop);

        bracketCrestPaint.setStyle(Paint.Style.STROKE);
        bracketCrestPaint.setStrokeCap(Paint.Cap.ROUND);
        bracketCrestPaint.setStrokeJoin(Paint.Join.ROUND);
        bracketCrestPaint.setXfermode(srcAtop);

        refreshThemeColors(context);
    }

    private void refreshThemeColors(Context context) {
        if ("mangalib".equalsIgnoreCase(currentSiteKey)) {
            brandPurpleColor = 0xFFFF9800;
        } else if ("ranobelib".equalsIgnoreCase(currentSiteKey)) {
            brandPurpleColor = 0xFF2196F3;
        } else if ("hentailib".equalsIgnoreCase(currentSiteKey)) {
            brandPurpleColor = 0xFFEC407A;
        } else if ("slashlib".equalsIgnoreCase(currentSiteKey)) {
            brandPurpleColor = 0xFFE53935;
        } else {
            brandPurpleColor = ContextCompat.getColor(context, R.color.purple_primary);
        }

        letterAColor = ContextCompat.getColor(context, R.color.player_splash_logo_a);
        int uiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        isNightMode = (uiMode == Configuration.UI_MODE_NIGHT_YES);

        letterAPaint.setColor(letterAColor);
        bracketBasePaint.setColor(brandPurpleColor);
        bracketPulsePaint.setColor(brandPurpleColor);
    }

    public void setSiteKey(String siteKey) {
        if (siteKey == null) siteKey = "animelib";
        String newKey = siteKey.toLowerCase();
        boolean keyChanged = !newKey.equalsIgnoreCase(this.currentSiteKey);
        this.currentSiteKey = newKey;

        String pathData;
        switch (currentSiteKey) {
            case "mangalib":
                pathData = SVG_LETTER_M;
                break;
            case "ranobelib":
                pathData = SVG_LETTER_R;
                break;
            case "hentailib":
                pathData = SVG_LETTER_H;
                break;
            case "slashlib":
                pathData = SVG_LETTER_S;
                break;
            case "animelib":
            default:
                pathData = SVG_LETTER_A;
                break;
        }

        rawLetterA = PathParser.createPathFromPathData(pathData);
        if (rawLetterA != null) {
            rawLetterA.setFillType(Path.FillType.EVEN_ODD);
        }

        refreshThemeColors(getContext());

        if (getWidth() > 0 && getHeight() > 0) {
            onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        }
        if (keyChanged) {
            startAnimation(true);
        } else {
            invalidate();
        }
    }

    public void setSiteLogoRes(int logoRes) {
        if (logoRes == R.drawable.ic_site_mangalib) {
            setSiteKey("mangalib");
        } else if (logoRes == R.drawable.ic_site_ranobelib) {
            setSiteKey("ranobelib");
        } else if (logoRes == R.drawable.ic_site_hentailib) {
            setSiteKey("hentailib");
        } else if (logoRes == R.drawable.ic_site_slashlib) {
            setSiteKey("slashlib");
        } else {
            setSiteKey("animelib");
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        refreshThemeColors(getContext());

        float minDim = Math.min(w, h);
        // Оставляем запас по краям под диагональное схождение/дыхание скобок без обрезки
        float inset = minDim * 0.145f;
        float availW = Math.max(1f, w - 2f * inset);
        float availH = Math.max(1f, h - 2f * inset);

        viewportScale = Math.min(availW / VIEWPORT_W, availH / VIEWPORT_H);
        float dx = (w - VIEWPORT_W * viewportScale) * 0.5f;
        float dy = (h - VIEWPORT_H * viewportScale) * 0.5f;
        centerX = w * 0.5f;
        centerY = h * 0.5f;

        transformMatrix.reset();
        transformMatrix.postScale(viewportScale, viewportScale);
        transformMatrix.postTranslate(dx, dy);

        if (rawBracketTL != null) {
            rawBracketTL.transform(transformMatrix, scaledBracketTL);
            scaledBracketTL.computeBounds(bracketTLBounds, true);
            bracketTLBounds.inset(-4f, -4f);
        }
        if (rawBracketBR != null) {
            rawBracketBR.transform(transformMatrix, scaledBracketBR);
            scaledBracketBR.computeBounds(bracketBRBounds, true);
            bracketBRBounds.inset(-4f, -4f);
        }
        if (rawLetterA != null) {
            rawLetterA.transform(transformMatrix, scaledLetterA);
            scaledLetterA.setFillType(Path.FillType.EVEN_ODD);
            scaledLetterA.computeBounds(letterABounds, true);
            letterABounds.inset(-4f, -4f);
        }
        if (rawTraceTL != null) {
            rawTraceTL.transform(transformMatrix, scaledTraceTL);
            pathMeasureTL.setPath(scaledTraceTL, false);
            traceLengthTL = pathMeasureTL.getLength();
        }
        if (rawTraceBR != null) {
            rawTraceBR.transform(transformMatrix, scaledTraceBR);
            pathMeasureBR.setPath(scaledTraceBR, false);
            traceLengthBR = pathMeasureBR.getLength();
        }

        float strokePx = Math.max(4f, 17.5f * viewportScale);
        bracketPulsePaint.setStrokeWidth(strokePx);
        bracketCrestPaint.setStrokeWidth(strokePx);

        float crestRadius = Math.max(14f, 26f * viewportScale);
        int crestPeak = isNightMode ? 0x99D2B8FF : 0x80B794F6;
        bracketCrestShader = new RadialGradient(
                0f, 0f, crestRadius,
                crestPeak, 0x00B794F6,
                Shader.TileMode.CLAMP
        );
        bracketCrestPaint.setShader(bracketCrestShader);

        sheenHalfBand = Math.max(12f, letterABounds.width() * 0.36f);
        int sheenPeak = isNightMode ? 0x4AD8B4FE : 0x529F67FF;
        letterASheenShader = new LinearGradient(
                -sheenHalfBand, -sheenHalfBand * 0.42f,
                sheenHalfBand, sheenHalfBand * 0.42f,
                new int[]{0x00FFFFFF, sheenPeak, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        );
        letterASheenPaint.setShader(letterASheenShader);
    }

    public void startAnimation() {
        startAnimation(false);
    }

    public void startAnimation(boolean forceReset) {
        refreshThemeColors(getContext());
        if (getWidth() > 0 && getHeight() > 0) {
            onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        }
        long now = SystemClock.uptimeMillis();
        if (forceReset || !isRunning || isExiting || (now - startTimeUptimeMs > 2500L)) {
            startTimeUptimeMs = now;
            exitStartUptimeMs = 0L;
            isExiting = false;
            frozenLoopBlendOnExit = 0f;
        }
        if (!isRunning) {
            isRunning = true;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
        invalidate();
    }

    public void startExitAnimation() {
        if (!isRunning) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long elapsed = Math.max(0L, now - startTimeUptimeMs);
        frozenLoopBlendOnExit = clamp01((elapsed - LOOP_BLEND_START_MS) / (float) LOOP_BLEND_DURATION_MS);
        exitStartUptimeMs = now;
        isExiting = true;
        invalidate();
    }

    public void stopAnimation() {
        isRunning = false;
        isExiting = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isRunning && getVisibility() == VISIBLE) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        } else if (isRunning && isAttachedToWindow()) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (scaledLetterA.isEmpty()) return;

        long now = isRunning ? SystemClock.uptimeMillis() : (startTimeUptimeMs + ENTRY_DURATION_MS);
        long elapsed = Math.max(0L, now - startTimeUptimeMs);

        // 1. Вход: схождение скобок к центру и проявление литеры «A»
        float rawEntry = clamp01(elapsed / (float) ENTRY_DURATION_MS);
        float easedEntry = easeOutQuint(rawEntry);
        float masterAlpha = clamp01(elapsed / 170f);

        // 2. Переход в режим живого индикатора на скобках
        float loopBlend;
        float exitEased = 0f;
        if (isExiting) {
            float rawExit = clamp01((now - exitStartUptimeMs) / (float) EXIT_DURATION_MS);
            exitEased = easeOutCubic(rawExit);
            loopBlend = frozenLoopBlendOnExit * (1f - exitEased);
        } else {
            loopBlend = smoothStep(
                    LOOP_BLEND_START_MS,
                    LOOP_BLEND_START_MS + LOOP_BLEND_DURATION_MS,
                    elapsed
            );
        }

        // 3. Цикл пробега импульсов по скобкам и отклика на «A»
        long cycleElapsed = Math.max(0L, elapsed - LOOP_BLEND_START_MS);
        float cycleT = (cycleElapsed % CYCLE_DURATION_MS) / (float) CYCLE_DURATION_MS;

        float headT = easeInOutCubic(clamp01(cycleT / 0.65f));
        float tailT = easeInOutCubic(clamp01((cycleT - 0.17f) / 0.63f));

        // Мягкое оптическое дыхание скобок на повороте угла (без «резиновости»)
        float breathUnits = 0f;
        if (cycleT > 0.10f && cycleT < 0.68f) {
            float bt = (cycleT - 0.10f) / 0.58f;
            float s = (float) Math.sin(Math.PI * bt);
            breathUnits = 2.1f * s * s * loopBlend;
        }

        float entryOffsetUnits = (1f - easedEntry) * 18.5f;
        float exitOffsetUnits = exitEased * 6.8f;
        float totalDiagPx = (entryOffsetUnits + breathUnits + exitOffsetUnits) * viewportScale;

        // Легкий контр-масштаб центральной «A» для ощущения глубины слоев
        float aScale = (0.90f + 0.10f * easedEntry)
                + (0.016f * (breathUnits / 2.1f))
                + (0.042f * exitEased);

        // --- Отрисовка центральной литеры «A» ---
        int aAlpha = Math.round(255f * masterAlpha);
        letterAPaint.setColor(applyAlpha(letterAColor, aAlpha));

        boolean hasAGlint = (loopBlend > 0.15f && cycleT > 0.52f && cycleT < 0.90f && letterASheenShader != null);
        canvas.save();
        canvas.scale(aScale, aScale, centerX, centerY);
        if (hasAGlint) {
            int saveA = canvas.saveLayer(letterABounds, layerPaint);
            canvas.drawPath(scaledLetterA, letterAPaint);

            float glintT = easeInOutSine((cycleT - 0.52f) / 0.38f);
            float startX = letterABounds.left - sheenHalfBand * 1.25f;
            float endX = letterABounds.right + sheenHalfBand * 1.25f;
            float curX = startX + (endX - startX) * glintT;
            sheenMatrix.reset();
            sheenMatrix.setTranslate(curX, letterABounds.centerY());
            letterASheenShader.setLocalMatrix(sheenMatrix);
            letterASheenPaint.setAlpha(Math.round(255f * loopBlend * masterAlpha));
            canvas.drawRect(letterABounds, letterASheenPaint);

            canvas.restoreToCount(saveA);
        } else {
            canvas.drawPath(scaledLetterA, letterAPaint);
        }
        canvas.restore();

        // --- Отрисовка скобок (┏ и ┛) ---
        float targetTrackAlpha = isNightMode ? 0.36f : 0.30f;
        float baseAlphaFraction = (1f - (1f - targetTrackAlpha) * loopBlend) * masterAlpha;
        bracketBasePaint.setColor(applyAlpha(brandPurpleColor, Math.round(255f * baseAlphaFraction)));

        boolean drawActivePulse = (loopBlend > 0.01f) && ((headT - tailT) > 0.004f)
                && traceLengthTL > 0f && traceLengthBR > 0f;

        if (drawActivePulse) {
            int pulseAlpha = Math.round(255f * loopBlend * masterAlpha);
            bracketPulsePaint.setColor(applyAlpha(brandPurpleColor, pulseAlpha));
            bracketCrestPaint.setAlpha(pulseAlpha);

            // Верхняя левая скобка (┏)
            drawAnimatedBracket(
                    canvas,
                    scaledBracketTL,
                    bracketTLBounds,
                    pathMeasureTL,
                    traceLengthTL,
                    activeSegmentTL,
                    tailT,
                    headT,
                    -totalDiagPx,
                    -totalDiagPx
            );

            // Нижняя правая скобка (┛)
            drawAnimatedBracket(
                    canvas,
                    scaledBracketBR,
                    bracketBRBounds,
                    pathMeasureBR,
                    traceLengthBR,
                    activeSegmentBR,
                    tailT,
                    headT,
                    totalDiagPx,
                    totalDiagPx
            );
        } else {
            canvas.save();
            canvas.translate(-totalDiagPx, -totalDiagPx);
            canvas.drawPath(scaledBracketTL, bracketBasePaint);
            canvas.restore();

            canvas.save();
            canvas.translate(totalDiagPx, totalDiagPx);
            canvas.drawPath(scaledBracketBR, bracketBasePaint);
            canvas.restore();
        }
    }

    private void drawAnimatedBracket(
            @NonNull Canvas canvas,
            @NonNull Path bracketOutline,
            @NonNull RectF localBounds,
            @NonNull PathMeasure pm,
            float totalLen,
            @NonNull Path outSegment,
            float tailT,
            float headT,
            float dx,
            float dy
    ) {
        outSegment.reset();
        float startD = tailT * totalLen;
        float stopD = headT * totalLen;
        pm.getSegment(startD, stopD, outSegment, true);
        pm.getPosTan(stopD, headPos, null);

        canvas.save();
        canvas.translate(dx, dy);
        int sc = canvas.saveLayer(localBounds, layerPaint);

        canvas.drawPath(bracketOutline, bracketBasePaint);
        canvas.drawPath(outSegment, bracketPulsePaint);

        if (bracketCrestShader != null) {
            crestMatrix.reset();
            crestMatrix.setTranslate(headPos[0], headPos[1]);
            bracketCrestShader.setLocalMatrix(crestMatrix);
            canvas.drawPath(outSegment, bracketCrestPaint);
        }

        canvas.restoreToCount(sc);
        canvas.restore();
    }

    private static int applyAlpha(int color, int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        return (color & 0x00FFFFFF) | (clamped << 24);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (Math.min(v, 1f));
    }

    private static float smoothStep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) return x >= edge1 ? 1f : 0f;
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float easeOutQuint(float t) {
        float inv = 1f - clamp01(t);
        return 1f - inv * inv * inv * inv * inv;
    }

    private static float easeOutCubic(float t) {
        float inv = 1f - clamp01(t);
        return 1f - inv * inv * inv;
    }

    private static float easeInOutCubic(float t) {
        t = clamp01(t);
        return t < 0.5f
                ? 4f * t * t * t
                : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    private static float easeInOutSine(float t) {
        t = clamp01(t);
        return -(float) (Math.cos(Math.PI * t) - 1.0) * 0.5f;
    }
}
