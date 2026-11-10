package com.example.animelib.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
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
 * Кастомный векторный логотип сплешскрина с плавным аппаратным переливанием света:
 * - Скобочки всегда сохраняют 100% сочный брендовый цвет (не обесцвечиваются).
 * - Безупречно плавный, непрерывный перелив световой волны (без лагов, без микро-фризов, без saveLayer).
 * - Гармоничный отклик блика на центральной литере «A».
 */
public class PlayerSplashLogoView extends View {

    private static final float VIEWPORT_W = 103.8f;
    private static final float VIEWPORT_H = 104.2f;

    private static final long ENTRY_DURATION_MS = 380L;
    private static final long LOOP_BLEND_START_MS = 280L;
    private static final long LOOP_BLEND_DURATION_MS = 260L;
    private static final long CYCLE_DURATION_MS = 1400L;
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

    private final RectF letterABounds = new RectF();

    private final Matrix transformMatrix = new Matrix();
    private final Matrix sheenMatrix = new Matrix();

    private final float[] headPos = new float[2];

    private final Paint letterAPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterASheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPulseGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPulseCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketSparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private LinearGradient letterASheenShader;

    private int brandPurpleColor;
    private int brandGlowColor;
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

        // Осевые траектории вдоль скобок для естественного пробега светового импульса
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

        letterAPaint.setStyle(Paint.Style.FILL);
        letterASheenPaint.setStyle(Paint.Style.FILL);

        bracketBasePaint.setStyle(Paint.Style.FILL);

        bracketPulseGlowPaint.setStyle(Paint.Style.STROKE);
        bracketPulseGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        bracketPulseGlowPaint.setStrokeJoin(Paint.Join.ROUND);

        bracketPulseCorePaint.setStyle(Paint.Style.STROKE);
        bracketPulseCorePaint.setStrokeCap(Paint.Cap.ROUND);
        bracketPulseCorePaint.setStrokeJoin(Paint.Join.ROUND);

        bracketSparkPaint.setStyle(Paint.Style.FILL);

        refreshThemeColors(context);
    }

    private void refreshThemeColors(Context context) {
        if ("mangalib".equalsIgnoreCase(currentSiteKey)) {
            brandPurpleColor = 0xFFFF9800; // Orange
            brandGlowColor = 0xFFFFE082;   // Light amber
        } else if ("ranobelib".equalsIgnoreCase(currentSiteKey)) {
            brandPurpleColor = 0xFF2196F3; // Blue
            brandGlowColor = 0xFF80D8FF;   // Light blue
        } else if ("hentailib".equalsIgnoreCase(currentSiteKey)) {
            brandPurpleColor = 0xFFEC407A; // Pink
            brandGlowColor = 0xFFFF80AB;   // Light pink
        } else if ("slashlib".equalsIgnoreCase(currentSiteKey)) {
            brandPurpleColor = 0xFFE53935; // Red
            brandGlowColor = 0xFFFF8A80;   // Light red
        } else {
            brandPurpleColor = ContextCompat.getColor(context, R.color.purple_primary);
            brandGlowColor = 0xFFD1C4E9;   // Soft luminous lavender
        }

        letterAColor = ContextCompat.getColor(context, R.color.player_splash_logo_a);
        int uiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        isNightMode = (uiMode == Configuration.UI_MODE_NIGHT_YES);

        letterAPaint.setColor(letterAColor);
        bracketBasePaint.setColor(brandPurpleColor);
        bracketPulseGlowPaint.setColor(brandGlowColor);
        bracketPulseCorePaint.setColor(0xFFFFFFFF);
        bracketSparkPaint.setColor(0xFFFFFFFF);
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
        }
        if (rawBracketBR != null) {
            rawBracketBR.transform(transformMatrix, scaledBracketBR);
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

        float strokePx = Math.max(4f, 15f * viewportScale);
        bracketPulseGlowPaint.setStrokeWidth(strokePx * 1.35f);
        bracketPulseCorePaint.setStrokeWidth(strokePx * 0.70f);

        sheenHalfBand = Math.max(12f, letterABounds.width() * 0.40f);
        int sheenPeak = isNightMode ? 0x90FFFFFF : 0x75FFFFFF;
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

        // 1. Вход: плавное проявление и схождение скобок к центру
        float rawEntry = clamp01(elapsed / (float) ENTRY_DURATION_MS);
        float easedEntry = easeOutQuint(rawEntry);
        float masterAlpha = clamp01(elapsed / 160f);

        // 2. Плавное включение живой анимации без скачков
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

        // 3. Непрерывный 1400мс цикл: волна света по скобкам + отклик на «A»
        long cycleElapsed = Math.max(0L, elapsed - LOOP_BLEND_START_MS);
        float cycleT = (cycleElapsed % CYCLE_DURATION_MS) / (float) CYCLE_DURATION_MS;

        // Непрерывное гармоничное дыхание (синусоида без скачков на границах циклов)
        float breathT = (float) Math.sin(cycleT * 2f * Math.PI);
        float breathUnits = 1.35f * breathT * loopBlend;

        float entryOffsetUnits = (1f - easedEntry) * 18.5f;
        float exitOffsetUnits = exitEased * 6.8f;
        float totalDiagPx = (entryOffsetUnits + breathUnits + exitOffsetUnits) * viewportScale;

        // Живой контр-масштаб центральной литеры «A»
        float aScale = (0.90f + 0.10f * easedEntry)
                + (0.015f * breathT * loopBlend)
                + (0.042f * exitEased);

        // --- 1. Отрисовка центральной литеры «A» с алмазным бликом ---
        int aAlpha = Math.round(255f * masterAlpha);
        letterAPaint.setColor(applyAlpha(letterAColor, aAlpha));

        boolean hasAGlint = (loopBlend > 0.05f && cycleT >= 0.46f && cycleT <= 0.88f && letterASheenShader != null);
        canvas.save();
        canvas.scale(aScale, aScale, centerX, centerY);
        canvas.drawPath(scaledLetterA, letterAPaint);

        if (hasAGlint) {
            canvas.save();
            canvas.clipPath(scaledLetterA);

            float glintT = easeInOutSine((cycleT - 0.46f) / 0.42f);
            float startX = letterABounds.left - sheenHalfBand * 1.25f;
            float endX = letterABounds.right + sheenHalfBand * 1.25f;
            float curX = startX + (endX - startX) * glintT;

            sheenMatrix.reset();
            sheenMatrix.setTranslate(curX, letterABounds.centerY());
            letterASheenShader.setLocalMatrix(sheenMatrix);
            letterASheenPaint.setAlpha(Math.round(255f * loopBlend * masterAlpha));
            canvas.drawRect(letterABounds, letterASheenPaint);

            canvas.restore();
        }
        canvas.restore();

        // --- 2. Отрисовка скобок (100% сочный брендовый цвет, НИКАКОГО обесцвечивания!) ---
        bracketBasePaint.setColor(applyAlpha(brandPurpleColor, Math.round(255f * masterAlpha)));

        // Расчет непрерывного пробега световой волны (фаза 0.0 - 0.72)
        boolean hasActivePulse = (loopBlend > 0.01f && cycleT <= 0.72f && traceLengthTL > 0f && traceLengthBR > 0f);

        if (hasActivePulse) {
            float u = easeInOutQuad(cycleT / 0.72f);

            // Верхняя левая скобка (TL)
            float pulseLenTL = traceLengthTL * 0.44f;
            float travelDistTL = u * (traceLengthTL + pulseLenTL);
            float headDTL = Math.min(traceLengthTL, travelDistTL);
            float tailDTL = Math.max(0f, travelDistTL - pulseLenTL);

            float alphaFractionTL = 1.0f;
            if (travelDistTL < pulseLenTL) {
                alphaFractionTL = travelDistTL / pulseLenTL;
            } else if (travelDistTL > traceLengthTL) {
                alphaFractionTL = 1.0f - (travelDistTL - traceLengthTL) / pulseLenTL;
            }
            alphaFractionTL = clamp01(alphaFractionTL) * loopBlend * masterAlpha;

            drawAnimatedBracket(
                    canvas,
                    scaledBracketTL,
                    pathMeasureTL,
                    activeSegmentTL,
                    tailDTL,
                    headDTL,
                    alphaFractionTL,
                    -totalDiagPx,
                    -totalDiagPx
            );

            // Нижняя правая скобка (BR)
            float pulseLenBR = traceLengthBR * 0.44f;
            float travelDistBR = u * (traceLengthBR + pulseLenBR);
            float headDBR = Math.min(traceLengthBR, travelDistBR);
            float tailDBR = Math.max(0f, travelDistBR - pulseLenBR);

            float alphaFractionBR = 1.0f;
            if (travelDistBR < pulseLenBR) {
                alphaFractionBR = travelDistBR / pulseLenBR;
            } else if (travelDistBR > traceLengthBR) {
                alphaFractionBR = 1.0f - (travelDistBR - traceLengthBR) / pulseLenBR;
            }
            alphaFractionBR = clamp01(alphaFractionBR) * loopBlend * masterAlpha;

            drawAnimatedBracket(
                    canvas,
                    scaledBracketBR,
                    pathMeasureBR,
                    activeSegmentBR,
                    tailDBR,
                    headDBR,
                    alphaFractionBR,
                    totalDiagPx,
                    totalDiagPx
            );
        } else {
            // Статичная отрисовка 100% сочных скобок с легким дыханием
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

    /**
     * Отрисовывает скобку со 100% сочным базовым цветом и гладким световым переливом внутри.
     * Не использует saveLayer, работает напрямую в GPU буфере без микро-фризов.
     */
    private void drawAnimatedBracket(
            @NonNull Canvas canvas,
            @NonNull Path bracketOutline,
            @NonNull PathMeasure pm,
            @NonNull Path outSegment,
            float tailD,
            float headD,
            float alphaFraction,
            float dx,
            float dy
    ) {
        canvas.save();
        canvas.translate(dx, dy);

        // 1. Всегда рисуем базовую скобку в 100% сочном брендовом цвете
        canvas.drawPath(bracketOutline, bracketBasePaint);

        // 2. Внутри скобки отрисовываем переливающийся световой импульс
        if (headD > tailD && alphaFraction > 0.01f) {
            outSegment.reset();
            pm.getSegment(tailD, headD, outSegment, true);
            pm.getPosTan(headD, headPos, null);

            canvas.save();
            canvas.clipPath(bracketOutline);

            // Мягкий неоновый ореол вокруг импульса
            bracketPulseGlowPaint.setAlpha(Math.round(180f * alphaFraction));
            canvas.drawPath(outSegment, bracketPulseGlowPaint);

            // Яркое белое ядро импульса
            bracketPulseCorePaint.setAlpha(Math.round(230f * alphaFraction));
            canvas.drawPath(outSegment, bracketPulseCorePaint);

            // Головная светящаяся искра на пике волны
            float sparkRadius = 6.0f * viewportScale;
            bracketSparkPaint.setAlpha(Math.round(255f * alphaFraction));
            canvas.drawCircle(headPos[0], headPos[1], sparkRadius, bracketSparkPaint);

            canvas.restore();
        }

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

    private static float easeInOutQuad(float t) {
        t = clamp01(t);
        return t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
    }

    private static float easeInOutSine(float t) {
        t = clamp01(t);
        return -(float) (Math.cos(Math.PI * t) - 1.0) * 0.5f;
    }
}
