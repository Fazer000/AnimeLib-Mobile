package com.example.animelib.ui;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

import com.example.animelib.subtitles.AssScript;
import com.example.animelib.subtitles.AssVectorGeometry;
import com.example.animelib.subtitles.AssVectorPathParser;
import com.example.animelib.util.FontResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native hardware-accelerated Canvas renderer for ASS/SSA subtitles.
 * Renders full [V4+ Styles], inline colors & alphas (\c, \1c, \3c, \4c, \alpha, \1a..4a),
 * accurate vector drawings (\p1..\p9 with B-splines, WINDING fill, and libass/VSFilter alignment),
 * \pos/\move/\org/\frz/\fscx/\fscy, \clip/\iclip, \fad/\fade, \t(...) transforms, Layer ordering,
 * and non-overlapping collision stacking.
 */
public class AssSubtitleOverlayView extends View {

    // Fallback ratio between ASS/GDI Fontsize (WinAscent+WinDescent) and Android em-size if metrics are unavailable
    private static final float FALLBACK_ASS_FONT_SIZE_RATIO = 0.86f;

    @Nullable private AssScript script;
    @Nullable private ExoPlayer player;
    private long currentPositionMs = 0L;
    private int videoWidth = 0;
    private int videoHeight = 0;
    private boolean isRunningLoop = false;

    private final List<AssScript.AssEvent> activeEvents = new ArrayList<>();
    private final List<AssScript.AssEvent> previousActiveEvents = new ArrayList<>();
    private final List<RectF> occupiedBoxes = new ArrayList<>();
    private final Map<Integer, RectF> placedRectsByEventIndex = new HashMap<>();
    private final LruCache<Typeface, Float> fontMetricRatioCache = new LruCache<>(32);

    // Reusable drawing primitives to avoid allocations in onDraw
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint metricMeasurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint vectorFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vectorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix tempMatrix = new Matrix();
    private final Path tempPath = new Path();
    private final RectF tempRect = new RectF();

    private final Runnable frameTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunningLoop) return;
            ExoPlayer p = player;
            if (p == null || script == null || getVisibility() != VISIBLE) {
                isRunningLoop = false;
                return;
            }
            updateForPosition(p.getCurrentPosition(), false);
            if (shouldAnimate(p) && isAttachedToWindow()) {
                postOnAnimation(this);
            } else {
                isRunningLoop = false;
            }
        }
    };

    private static boolean shouldAnimate(@Nullable ExoPlayer p) {
        if (p == null) return false;
        if (p.isPlaying()) return true;
        int state = p.getPlaybackState();
        return p.getPlayWhenReady() && (state == androidx.media3.common.Player.STATE_READY
                || state == androidx.media3.common.Player.STATE_BUFFERING);
    }

    public AssSubtitleOverlayView(Context context) {
        super(context);
        init();
    }

    public AssSubtitleOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AssSubtitleOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        vectorFillPaint.setStyle(Paint.Style.FILL);
        vectorStrokePaint.setStyle(Paint.Style.STROKE);
        vectorStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        vectorStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        boxPaint.setStyle(Paint.Style.FILL);
    }

    public void setPlayer(@Nullable ExoPlayer player) {
        this.player = player;
        if (player != null) {
            updateVideoSize(player.getVideoSize().width, player.getVideoSize().height);
            updateForPosition(player.getCurrentPosition(), true);
            if (shouldAnimate(player) && getVisibility() == VISIBLE) {
                startRenderLoop();
            }
        } else {
            stopRenderLoop();
        }
    }

    public void setAssScript(@Nullable AssScript script) {
        this.script = script;
        previousActiveEvents.clear();
        activeEvents.clear();
        if (script == null || script.isEmpty()) {
            stopRenderLoop();
            invalidate();
            return;
        }
        if (player != null) {
            currentPositionMs = player.getCurrentPosition();
            updateForPosition(currentPositionMs, true);
            if (shouldAnimate(player) && getVisibility() == VISIBLE) {
                startRenderLoop();
            }
        } else {
            invalidate();
        }
    }

    @Nullable
    public AssScript getAssScript() {
        return script;
    }

    public void updateVideoSize(int width, int height) {
        if (width > 0 && height > 0 && (this.videoWidth != width || this.videoHeight != height)) {
            this.videoWidth = width;
            this.videoHeight = height;
            invalidate();
        }
    }

    public void syncNow() {
        ExoPlayer p = player;
        if (p != null && script != null && getVisibility() == VISIBLE) {
            updateForPosition(p.getCurrentPosition(), true);
            if (shouldAnimate(p)) {
                startRenderLoop();
            }
        }
    }

    public void startRenderLoop() {
        if (script == null || script.isEmpty() || player == null || getVisibility() != VISIBLE) {
            return;
        }
        if (!isRunningLoop) {
            isRunningLoop = true;
            removeCallbacks(frameTickRunnable);
            postOnAnimation(frameTickRunnable);
        }
    }

    public void stopRenderLoop() {
        isRunningLoop = false;
        removeCallbacks(frameTickRunnable);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE && script != null && player != null) {
            syncNow();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopRenderLoop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            stopRenderLoop();
        } else if (player != null && script != null) {
            syncNow();
        }
    }

    private void updateForPosition(long positionMs, boolean forceInvalidate) {
        this.currentPositionMs = positionMs;
        AssScript s = this.script;
        if (s == null || s.isEmpty()) {
            if (!previousActiveEvents.isEmpty() || forceInvalidate) {
                previousActiveEvents.clear();
                activeEvents.clear();
                invalidate();
            }
            return;
        }

        s.getActiveEvents(positionMs, activeEvents);

        boolean hasAnimatedEvent = false;
        for (int i = 0; i < activeEvents.size(); i++) {
            if (activeEvents.get(i).hasAnimations) {
                hasAnimatedEvent = true;
                break;
            }
        }

        boolean eventsChanged = forceInvalidate || !sameEventList(activeEvents, previousActiveEvents);
        if (eventsChanged || hasAnimatedEvent) {
            previousActiveEvents.clear();
            previousActiveEvents.addAll(activeEvents);
            invalidate();
        }
    }

    private static boolean sameEventList(List<AssScript.AssEvent> a, List<AssScript.AssEvent> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        AssScript s = this.script;
        if (s == null || s.isEmpty() || activeEvents.isEmpty()) {
            return;
        }

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        float playResX = s.resolvePlayResX(videoWidth, videoHeight);
        float playResY = s.resolvePlayResY(videoWidth, videoHeight);
        if (playResX <= 0f || playResY <= 0f) return;

        float sx = viewW / playResX;
        float sy = viewH / playResY;
        float borderScaleX = s.scaledBorderAndShadow ? sx : (viewW / Math.max(1f, videoWidth > 0 ? videoWidth : playResX));
        float borderScaleY = s.scaledBorderAndShadow ? sy : (viewH / Math.max(1f, videoHeight > 0 ? videoHeight : playResY));

        long posMs = currentPositionMs;

        // Pre-layout text events and resolve non-overlapping positions for all unpositioned events
        Map<Integer, TextBlockLayout> textLayouts = new HashMap<>();
        List<AssScript.AssEvent> unpositionedTextEvents = new ArrayList<>();

        for (int i = 0; i < activeEvents.size(); i++) {
            AssScript.AssEvent ev = activeEvents.get(i);
            boolean isPureVector = ev.hasVectorDrawing && !ev.hasTextContent;
            if (!isPureVector) {
                TextBlockLayout layout = layoutTextEvent(ev, posMs, playResX, sx, sy);
                if (layout != null && !layout.lines.isEmpty()) {
                    textLayouts.put(ev.eventIndex, layout);
                    if (!ev.isExplicitlyPositioned()) {
                        unpositionedTextEvents.add(ev);
                    }
                }
            }
        }

        // Compute collision-free bounding boxes in ASS collision order (Normal: older stays put, newer stacks)
        occupiedBoxes.clear();
        placedRectsByEventIndex.clear();
        if (!unpositionedTextEvents.isEmpty()) {
            if (s.reverseCollisions) {
                unpositionedTextEvents.sort((a, b) -> {
                    int cmp = Long.compare(b.startMs, a.startMs);
                    return cmp != 0 ? cmp : Integer.compare(b.eventIndex, a.eventIndex);
                });
            } else {
                unpositionedTextEvents.sort(COLLISION_ORDER_COMPARATOR);
            }

            float vGap = Math.max(4f, 6f * sy);
            float hPad = Math.max(2f, 4f * sx);

            for (int i = 0; i < unpositionedTextEvents.size(); i++) {
                AssScript.AssEvent ev = unpositionedTextEvents.get(i);
                TextBlockLayout layout = textLayouts.get(ev.eventIndex);
                if (layout == null) continue;

                RectF box = computeUnpositionedBox(ev, layout, viewW, viewH, sx, sy);
                resolveCollision(box, ev.alignment, occupiedBoxes, viewH, vGap, hPad);
                occupiedBoxes.add(new RectF(box));
                placedRectsByEventIndex.put(ev.eventIndex, box);
            }
        }

        // Draw all active events in strict ASS Z-order: (layer ASC, eventIndex ASC)
        for (int i = 0; i < activeEvents.size(); i++) {
            AssScript.AssEvent ev = activeEvents.get(i);
            float fadeFactor = ev.computeFadeAlphaFactor(posMs);
            if (fadeFactor <= 0.004f) continue;

            int clipSave = canvas.save();
            applyEventClip(canvas, ev.clip, sx, sy);

            boolean isPureVector = ev.hasVectorDrawing && !ev.hasTextContent;
            if (isPureVector) {
                drawVectorEvent(canvas, ev, posMs, fadeFactor, playResX, playResY, sx, sy, borderScaleX, borderScaleY);
            } else {
                TextBlockLayout layout = textLayouts.get(ev.eventIndex);
                if (layout != null && !layout.lines.isEmpty()) {
                    drawTextEvent(canvas, ev, layout, posMs, fadeFactor, sx, sy, borderScaleX, borderScaleY);
                }
            }

            canvas.restoreToCount(clipSave);
        }
    }

    private static final Comparator<AssScript.AssEvent> COLLISION_ORDER_COMPARATOR = (a, b) -> {
        int cmp = Long.compare(a.startMs, b.startMs);
        if (cmp != 0) return cmp;
        return Integer.compare(a.eventIndex, b.eventIndex);
    };

    private void applyEventClip(Canvas canvas, @Nullable AssScript.AssClip clip, float sx, float sy) {
        if (clip == null) return;
        if (!clip.isVector) {
            float left = Math.min(clip.x1, clip.x2) * sx;
            float top = Math.min(clip.y1, clip.y2) * sy;
            float right = Math.max(clip.x1, clip.x2) * sx;
            float bottom = Math.max(clip.y1, clip.y2) * sy;
            if (clip.inverted) {
                canvas.clipOutRect(left, top, right, bottom);
            } else {
                canvas.clipRect(left, top, right, bottom);
            }
        } else if (clip.vectorCommands != null) {
            float pScale = 1f / (float) (1 << Math.max(0, clip.vectorScale - 1));
            AssVectorPathParser.ParsedVectorPath parsed = AssVectorPathParser.parseCached(clip.vectorCommands, pScale);
            if (parsed != null) {
                tempMatrix.reset();
                tempMatrix.setScale(sx, sy);
                tempPath.reset();
                parsed.path.transform(tempMatrix, tempPath);
                tempPath.setFillType(Path.FillType.WINDING);
                if (clip.inverted) {
                    canvas.clipOutPath(tempPath);
                } else {
                    canvas.clipPath(tempPath);
                }
            }
        }
    }

    // ==================== VECTOR DRAWING RENDERER ====================

    private static final class PreparedVectorSegment {
        final AssScript.SegmentStyle style;
        final AssVectorPathParser.ParsedVectorPath parsed;
        final float pScale;
        final float fscx;
        final float fscy;
        final float bWidth;
        final float bHeight;

        PreparedVectorSegment(AssScript.SegmentStyle style,
                              AssVectorPathParser.ParsedVectorPath parsed,
                              float pScale,
                              float fscx,
                              float fscy,
                              float bWidth,
                              float bHeight) {
            this.style = style;
            this.parsed = parsed;
            this.pScale = pScale;
            this.fscx = fscx;
            this.fscy = fscy;
            this.bWidth = bWidth;
            this.bHeight = bHeight;
        }
    }

    private void drawVectorEvent(Canvas canvas,
                                 AssScript.AssEvent ev,
                                 long posMs,
                                 float fadeFactor,
                                 float playResX,
                                 float playResY,
                                 float sx,
                                 float sy,
                                 float borderScaleX,
                                 float borderScaleY) {
        long relMs = Math.max(0L, posMs - ev.startMs);
        long durMs = ev.getDurationMs();

        int an = ev.alignment;
        float anchorX;
        float anchorY;
        if (ev.isExplicitlyPositioned()) {
            anchorX = ev.computePosXAt(posMs);
            anchorY = ev.computePosYAt(posMs);
        } else {
            anchorX = AssVectorGeometry.computeUnpositionedAnchorX(playResX, ev.marginL, ev.marginR, an);
            anchorY = AssVectorGeometry.computeUnpositionedAnchorY(playResY, ev.marginV, an);
        }

        // Prepare all drawing segments on this line to compute accurate combined libass/VSFilter line metrics
        List<PreparedVectorSegment> prepared = new ArrayList<>(ev.segments.size());
        float totalWidth = 0f;
        float maxHeight = 0f;

        for (int sIdx = 0; sIdx < ev.segments.size(); sIdx++) {
            AssScript.AssSegment seg = ev.segments.get(sIdx);
            if (!seg.isDrawing) continue;

            AssScript.SegmentStyle st = seg.resolveStyleAt(relMs, durMs);
            float fscx = st.scaleX / 100f;
            float fscy = st.scaleY / 100f;
            if (fscx <= 0.001f || fscy <= 0.001f) continue;

            float pScale = 1f / (float) (1 << Math.max(0, st.pLevel - 1));
            AssVectorPathParser.ParsedVectorPath parsed = AssVectorPathParser.parseCached(seg.content, pScale);
            if (parsed == null) continue;

            float bWidth = Math.max(0f, (parsed.bounds.right - parsed.bounds.left) * fscx);
            float bHeight = Math.max(0f, (parsed.bounds.bottom - parsed.bounds.top) * fscy);
            prepared.add(new PreparedVectorSegment(st, parsed, pScale, fscx, fscy, bWidth, bHeight));
            totalWidth += bWidth;
            if (bHeight > maxHeight) {
                maxHeight = bHeight;
            }
        }

        if (prepared.isEmpty()) return;

        float penX = AssVectorGeometry.computeLineOriginX(anchorX, totalWidth, an);
        float lineTopY = AssVectorGeometry.computeLineTopY(anchorY, maxHeight, an);

        for (int i = 0; i < prepared.size(); i++) {
            PreparedVectorSegment pv = prepared.get(i);
            AssScript.SegmentStyle st = pv.style;

            float originX = penX;
            float originY = lineTopY
                    + (maxHeight - pv.bHeight)
                    + AssVectorGeometry.computePboOffsetY(st.pbo, pv.pScale, pv.fscy);

            // Transform path into screen pixel coordinates
            tempMatrix.reset();
            tempMatrix.postScale(pv.fscx, pv.fscy);
            tempMatrix.postTranslate(originX, originY);
            tempMatrix.postScale(sx, sy);

            tempPath.reset();
            pv.parsed.path.transform(tempMatrix, tempPath);
            tempPath.setFillType(Path.FillType.WINDING);

            int saveCount = canvas.save();

            // Apply rotation / shear around \org or anchor point
            if (Math.abs(st.frz) > 0.01f || Math.abs(st.fax) > 0.001f || Math.abs(st.fay) > 0.001f) {
                float pivotX = (ev.hasOrg ? ev.orgX : anchorX) * sx;
                float pivotY = (ev.hasOrg ? ev.orgY : anchorY) * sy;
                canvas.translate(pivotX, pivotY);
                if (Math.abs(st.frz) > 0.01f) {
                    canvas.rotate(-st.frz);
                }
                if (Math.abs(st.fax) > 0.001f || Math.abs(st.fay) > 0.001f) {
                    canvas.skew(st.fax, st.fay);
                }
                canvas.translate(-pivotX, -pivotY);
            }

            drawTransformedVectorPath(canvas, tempPath, st, fadeFactor, borderScaleX, borderScaleY);
            canvas.restoreToCount(saveCount);

            penX += pv.bWidth;
        }
    }

    private void drawTransformedVectorPath(Canvas canvas,
                                           Path transformedPath,
                                           AssScript.SegmentStyle st,
                                           float fadeFactor,
                                           float borderScaleX,
                                           float borderScaleY) {
        float avgBorder = (st.borderX + st.borderY) * 0.5f;
        float strokeWidthPx = avgBorder * ((borderScaleX + borderScaleY) * 0.5f) * 2f;
        float blurPx = st.blur > 0.1f ? Math.min(24f, st.blur * ((borderScaleX + borderScaleY) * 0.5f)) : 0f;
        BlurMaskFilter blurFilter = blurPx > 0.2f ? new BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) : null;

        int fillColor = AssScript.multiplyAlpha(st.primaryColor, fadeFactor);
        int outlineColor = AssScript.multiplyAlpha(st.outlineColor, fadeFactor);
        int shadowColor = AssScript.multiplyAlpha(st.backColor, fadeFactor);

        // 1. Shadow (drawn first, behind border and fill)
        if ((Math.abs(st.shadowX) > 0.01f || Math.abs(st.shadowY) > 0.01f) && Color.alpha(shadowColor) > 0) {
            int shSave = canvas.save();
            canvas.translate(st.shadowX * borderScaleX, st.shadowY * borderScaleY);
            if (strokeWidthPx > 0.1f) {
                vectorStrokePaint.setColor(shadowColor);
                vectorStrokePaint.setStrokeWidth(strokeWidthPx);
                vectorStrokePaint.setMaskFilter(blurFilter);
                canvas.drawPath(transformedPath, vectorStrokePaint);
            }
            vectorFillPaint.setColor(shadowColor);
            vectorFillPaint.setMaskFilter(blurFilter);
            canvas.drawPath(transformedPath, vectorFillPaint);
            canvas.restoreToCount(shSave);
        }

        // 2. Border / Outline (drawn BEHIND fill at 2*bord width so outer half matches exact ASS bord)
        if (strokeWidthPx > 0.1f && Color.alpha(outlineColor) > 0) {
            vectorStrokePaint.setColor(outlineColor);
            vectorStrokePaint.setStrokeWidth(strokeWidthPx);
            vectorStrokePaint.setMaskFilter(blurFilter);
            canvas.drawPath(transformedPath, vectorStrokePaint);
        }

        // 3. Primary Fill (drawn ON TOP)
        if (Color.alpha(fillColor) > 0) {
            vectorFillPaint.setColor(fillColor);
            vectorFillPaint.setMaskFilter(blurFilter);
            canvas.drawPath(transformedPath, vectorFillPaint);
        }

        vectorFillPaint.setMaskFilter(null);
        vectorStrokePaint.setMaskFilter(null);
    }

    // ==================== RICH TEXT & INLINE VECTOR LAYOUT / RENDERER ====================

    /**
     * Resolves Android Skia TextPaint em-size so that (-ascent + descent) matches ASS/GDI
     * Fontsize * sy across every typeface (e.g. Open Sans, Roboto, Serif, Condensed).
     */
    private float resolveAssTextSizePx(Typeface tf, float assFontSize, float sy) {
        float ratio = getAssFontMetricRatio(tf);
        return Math.max(4f, assFontSize * sy * ratio);
    }

    private float getAssFontMetricRatio(Typeface tf) {
        if (tf == null) return FALLBACK_ASS_FONT_SIZE_RATIO;
        Float cached = fontMetricRatioCache.get(tf);
        if (cached != null) {
            return cached;
        }
        metricMeasurePaint.setTypeface(tf);
        metricMeasurePaint.setTextSize(100f);
        Paint.FontMetrics fm = metricMeasurePaint.getFontMetrics();
        float metricHeight = fm.descent - fm.ascent;
        float ratio = (metricHeight > 10f) ? (100f / metricHeight) : FALLBACK_ASS_FONT_SIZE_RATIO;
        ratio = Math.max(0.55f, Math.min(1.15f, ratio));
        fontMetricRatioCache.put(tf, ratio);
        return ratio;
    }

    private static final class MeasuredRun {
        final boolean isDrawing;
        final String text;
        @Nullable final AssVectorPathParser.ParsedVectorPath parsedVector;
        final AssScript.SegmentStyle style;
        @Nullable final Typeface typeface;
        final float textSizePx;
        final float spacingPx;
        final float scaleXRatio;
        final float scaleYRatio;
        final float pboPx;
        final float widthPx;
        final float ascentPx;  // positive magnitude above baseline
        final float descentPx; // positive magnitude below baseline

        MeasuredRun(String text,
                    AssScript.SegmentStyle style,
                    Typeface typeface,
                    float textSizePx,
                    float spacingPx,
                    float scaleXRatio,
                    float scaleYRatio,
                    float widthPx,
                    float ascentPx,
                    float descentPx) {
            this.isDrawing = false;
            this.text = text;
            this.parsedVector = null;
            this.style = style;
            this.typeface = typeface;
            this.textSizePx = textSizePx;
            this.spacingPx = spacingPx;
            this.scaleXRatio = scaleXRatio;
            this.scaleYRatio = scaleYRatio;
            this.pboPx = 0f;
            this.widthPx = widthPx;
            this.ascentPx = ascentPx;
            this.descentPx = descentPx;
        }

        MeasuredRun(AssVectorPathParser.ParsedVectorPath parsedVector,
                    AssScript.SegmentStyle style,
                    float scaleXToPx,
                    float scaleYToPx,
                    float pboPx,
                    float widthPx,
                    float ascentPx) {
            this.isDrawing = true;
            this.text = "";
            this.parsedVector = parsedVector;
            this.style = style;
            this.typeface = null;
            this.textSizePx = 0f;
            this.spacingPx = 0f;
            this.scaleXRatio = scaleXToPx;
            this.scaleYRatio = scaleYToPx;
            this.pboPx = pboPx;
            this.widthPx = widthPx;
            this.ascentPx = ascentPx;
            this.descentPx = 0f;
        }
    }

    private static final class MeasuredLine {
        final List<MeasuredRun> runs = new ArrayList<>();
        float widthPx = 0f;
        float maxAscentPx = 0f;
        float maxDescentPx = 0f;
        float maxBorderYPx = 0f;

        float getLineHeightPx() {
            return Math.max(1f, maxAscentPx + maxDescentPx);
        }
    }

    private static final class TextBlockLayout {
        final List<MeasuredLine> lines = new ArrayList<>();
        float blockWidthPx = 0f;
        float blockHeightPx = 0f;
        float maxBorderXPx = 0f;
        float maxBorderYPx = 0f;
    }

    @Nullable
    private TextBlockLayout layoutTextEvent(AssScript.AssEvent ev,
                                            long posMs,
                                            float playResX,
                                            float sx,
                                            float sy) {
        long relMs = Math.max(0L, posMs - ev.startMs);
        long durMs = ev.getDurationMs();

        boolean allowWrap = !ev.isExplicitlyPositioned() && ev.wrapStyle != 2;
        float maxLineWidthPx = Math.max(40f, (playResX - ev.marginL - ev.marginR) * sx);

        // Step 1: Split segments into hard-broken logical lines (\N, or \n when wrapStyle == 2)
        List<List<StyledPiece>> hardLines = new ArrayList<>();
        List<StyledPiece> currentHardLine = new ArrayList<>();

        for (int i = 0; i < ev.segments.size(); i++) {
            AssScript.AssSegment seg = ev.segments.get(i);
            AssScript.SegmentStyle st = seg.resolveStyleAt(relMs, durMs);
            if (seg.isDrawing) {
                currentHardLine.add(new StyledPiece(seg.content, st, true));
                continue;
            }
            String raw = seg.content;
            int len = raw.length();
            StringBuilder buf = new StringBuilder();

            for (int c = 0; c < len; c++) {
                char ch = raw.charAt(c);
                if (ch == '\\' && c + 1 < len) {
                    char next = raw.charAt(c + 1);
                    if (next == 'N' || (next == 'n' && ev.wrapStyle == 2)) {
                        if (buf.length() > 0) {
                            currentHardLine.add(new StyledPiece(buf.toString(), st, false));
                            buf.setLength(0);
                        }
                        hardLines.add(currentHardLine);
                        currentHardLine = new ArrayList<>();
                        c++;
                        continue;
                    } else if (next == 'n') {
                        buf.append(' ');
                        c++;
                        continue;
                    } else if (next == 'h') {
                        buf.append('\u00A0');
                        c++;
                        continue;
                    }
                } else if (ch == '\n') {
                    if (buf.length() > 0) {
                        currentHardLine.add(new StyledPiece(buf.toString(), st, false));
                        buf.setLength(0);
                    }
                    hardLines.add(currentHardLine);
                    currentHardLine = new ArrayList<>();
                    continue;
                }
                buf.append(ch);
            }
            if (buf.length() > 0) {
                currentHardLine.add(new StyledPiece(buf.toString(), st, false));
            }
        }
        if (!currentHardLine.isEmpty()) {
            hardLines.add(currentHardLine);
        }

        if (hardLines.isEmpty()) return null;

        // Step 2: Measure & word-wrap each logical line
        TextBlockLayout block = new TextBlockLayout();
        for (int h = 0; h < hardLines.size(); h++) {
            List<StyledPiece> pieces = hardLines.get(h);
            if (pieces.isEmpty()) {
                // Preserve intentional empty line spacing (ASS Fontsize == ascent + descent)
                MeasuredLine empty = new MeasuredLine();
                float fallbackH = Math.max(4f, ev.style.fontSize * sy);
                empty.maxAscentPx = fallbackH * 0.8f;
                empty.maxDescentPx = fallbackH * 0.2f;
                block.lines.add(empty);
                continue;
            }
            wrapAndMeasureLogicalLine(pieces, allowWrap, maxLineWidthPx, sx, sy, block.lines);
        }

        if (block.lines.isEmpty()) return null;

        float totalH = 0f;
        float maxW = 0f;
        float maxBx = 0f;
        float maxBy = 0f;
        for (int i = 0; i < block.lines.size(); i++) {
            MeasuredLine line = block.lines.get(i);
            if (line.widthPx > maxW) maxW = line.widthPx;
            totalH += line.getLineHeightPx();
            if (line.maxBorderYPx > maxBy) maxBy = line.maxBorderYPx;
            for (int r = 0; r < line.runs.size(); r++) {
                float bx = line.runs.get(r).style.borderX * sx;
                if (bx > maxBx) maxBx = bx;
            }
        }
        block.blockWidthPx = maxW;
        block.blockHeightPx = totalH;
        block.maxBorderXPx = maxBx;
        block.maxBorderYPx = maxBy;
        return block;
    }

    private static final class StyledPiece {
        final String text;
        final AssScript.SegmentStyle style;
        final boolean isDrawing;

        StyledPiece(String text, AssScript.SegmentStyle style, boolean isDrawing) {
            this.text = text;
            this.style = style;
            this.isDrawing = isDrawing;
        }
    }

    private void wrapAndMeasureLogicalLine(List<StyledPiece> pieces,
                                           boolean allowWrap,
                                           float maxLineWidthPx,
                                           float sx,
                                           float sy,
                                           List<MeasuredLine> outLines) {
        MeasuredLine currentLine = new MeasuredLine();

        for (int p = 0; p < pieces.size(); p++) {
            StyledPiece piece = pieces.get(p);
            AssScript.SegmentStyle st = piece.style;

            if (piece.isDrawing) {
                float fscx = st.scaleX / 100f;
                float fscy = st.scaleY / 100f;
                if (fscx <= 0.001f || fscy <= 0.001f) continue;
                float pScale = 1f / (float) (1 << Math.max(0, st.pLevel - 1));
                AssVectorPathParser.ParsedVectorPath parsed = AssVectorPathParser.parseCached(piece.text, pScale);
                if (parsed == null) continue;

                float drawW = Math.max(0f, (parsed.bounds.right - parsed.bounds.left) * fscx * sx);
                float drawH = Math.max(0f, (parsed.bounds.bottom - parsed.bounds.top) * fscy * sy);
                float pboPx = AssVectorGeometry.computePboOffsetY(st.pbo, pScale, fscy) * sy;

                if (allowWrap && !currentLine.runs.isEmpty() && currentLine.widthPx + drawW > maxLineWidthPx) {
                    trimTrailingSpace(currentLine);
                    outLines.add(currentLine);
                    currentLine = new MeasuredLine();
                }
                addRunToLine(currentLine, new MeasuredRun(parsed, st, fscx * sx, fscy * sy, pboPx, drawW, drawH), sy);
                continue;
            }

            if (st.scaleX <= 0.001f || st.scaleY <= 0.001f) continue;

            Typeface tf = FontResolver.resolveTypeface(getContext(), st.fontName, st.bold, st.italic);
            float textSizePx = resolveAssTextSizePx(tf, st.fontSize, sy);
            float scaleXRatio = Math.max(0.05f, (st.scaleX / 100f) * (sx / Math.max(0.0001f, sy)));
            float scaleYRatio = Math.max(0.05f, st.scaleY / 100f);
            float spacingPx = st.spacing * sx;

            configureTextPaint(textPaint, tf, textSizePx, spacingPx, scaleXRatio, st.underline, st.strikeOut);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float ascentPx = -fm.ascent * scaleYRatio;
            float descentPx = fm.descent * scaleYRatio;

            String text = piece.text;
            if (!allowWrap) {
                float w = textPaint.measureText(text);
                addRunToLine(currentLine, new MeasuredRun(text, st, tf, textSizePx, spacingPx, scaleXRatio, scaleYRatio, w, ascentPx, descentPx), sy);
                continue;
            }

            // Tokenize into words keeping trailing spaces for clean wrapping
            int idx = 0;
            int len = text.length();
            while (idx < len) {
                int nextBreak = findNextWordEnd(text, idx);
                String chunk = text.substring(idx, nextBreak);
                float chunkW = textPaint.measureText(chunk);

                if (!currentLine.runs.isEmpty() && currentLine.widthPx + chunkW > maxLineWidthPx) {
                    // Trim trailing spaces on completed line
                    trimTrailingSpace(currentLine);
                    outLines.add(currentLine);
                    currentLine = new MeasuredLine();
                    chunk = trimLeadingSpaces(chunk);
                    if (chunk.isEmpty()) {
                        idx = nextBreak;
                        continue;
                    }
                    chunkW = textPaint.measureText(chunk);
                }

                // If a single word is wider than maxLineWidthPx, break by characters
                if (chunkW > maxLineWidthPx && chunk.length() > 1) {
                    int subStart = 0;
                    while (subStart < chunk.length()) {
                        int count = textPaint.breakText(chunk, subStart, chunk.length(), true, maxLineWidthPx, null);
                        if (count <= 0) count = 1;
                        String part = chunk.substring(subStart, subStart + count);
                        float partW = textPaint.measureText(part);
                        if (!currentLine.runs.isEmpty() && currentLine.widthPx + partW > maxLineWidthPx) {
                            outLines.add(currentLine);
                            currentLine = new MeasuredLine();
                        }
                        addRunToLine(currentLine, new MeasuredRun(part, st, tf, textSizePx, spacingPx, scaleXRatio, scaleYRatio, partW, ascentPx, descentPx), sy);
                        subStart += count;
                    }
                } else {
                    addRunToLine(currentLine, new MeasuredRun(chunk, st, tf, textSizePx, spacingPx, scaleXRatio, scaleYRatio, chunkW, ascentPx, descentPx), sy);
                }
                idx = nextBreak;
            }
        }

        if (!currentLine.runs.isEmpty()) {
            trimTrailingSpace(currentLine);
            outLines.add(currentLine);
        }
    }

    private void addRunToLine(MeasuredLine line, MeasuredRun run, float sy) {
        line.runs.add(run);
        line.widthPx += run.widthPx;
        if (run.ascentPx > line.maxAscentPx) line.maxAscentPx = run.ascentPx;
        if (run.descentPx > line.maxDescentPx) line.maxDescentPx = run.descentPx;
        float by = run.style.borderY * sy;
        if (by > line.maxBorderYPx) line.maxBorderYPx = by;
    }

    private void trimTrailingSpace(MeasuredLine line) {
        if (line.runs.isEmpty()) return;
        MeasuredRun last = line.runs.get(line.runs.size() - 1);
        if (last.isDrawing) return;
        String trimmed = last.text;
        while (trimmed.endsWith(" ")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.length() == last.text.length()) return;
        if (trimmed.isEmpty()) {
            line.widthPx = Math.max(0f, line.widthPx - last.widthPx);
            line.runs.remove(line.runs.size() - 1);
            return;
        }
        configureTextPaint(textPaint, last.typeface, last.textSizePx, last.spacingPx, last.scaleXRatio, last.style.underline, last.style.strikeOut);
        float newW = textPaint.measureText(trimmed);
        line.widthPx = Math.max(0f, line.widthPx - last.widthPx + newW);
        line.runs.set(line.runs.size() - 1, new MeasuredRun(
                trimmed, last.style, last.typeface, last.textSizePx, last.spacingPx,
                last.scaleXRatio, last.scaleYRatio, newW, last.ascentPx, last.descentPx
        ));
    }

    private static int findNextWordEnd(String text, int start) {
        int len = text.length();
        int i = start;
        // Consume non-space characters
        while (i < len && text.charAt(i) != ' ') {
            i++;
        }
        // Consume trailing spaces together with the word
        while (i < len && text.charAt(i) == ' ') {
            i++;
        }
        return i > start ? i : start + 1;
    }

    private static String trimLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i == 0 ? s : s.substring(i);
    }

    private void configureTextPaint(TextPaint paint,
                                    Typeface tf,
                                    float textSizePx,
                                    float spacingPx,
                                    float scaleXRatio,
                                    boolean underline,
                                    boolean strikeOut) {
        paint.setTypeface(tf);
        paint.setTextSize(textSizePx);
        paint.setTextScaleX(scaleXRatio);
        paint.setLetterSpacing(textSizePx > 0.1f ? (spacingPx / textSizePx) : 0f);
        paint.setUnderlineText(underline);
        paint.setStrikeThruText(strikeOut);
    }

    private RectF computeUnpositionedBox(AssScript.AssEvent ev,
                                         TextBlockLayout layout,
                                         int viewW,
                                         int viewH,
                                         float sx,
                                         float sy) {
        int an = ev.alignment;
        int xAlign = (an - 1) % 3 + 1;
        int yAlign = (an - 1) / 3 + 1;

        float marginLPx = ev.marginL * sx;
        float marginRPx = ev.marginR * sx;
        float marginVPx = ev.marginV * sy;

        float w = layout.blockWidthPx;
        float h = layout.blockHeightPx;

        float left;
        if (xAlign == 1) {
            left = marginLPx;
        } else if (xAlign == 2) {
            left = marginLPx + Math.max(0f, (viewW - marginLPx - marginRPx - w) * 0.5f);
        } else {
            left = viewW - marginRPx - w;
        }

        float top;
        if (yAlign == 3) {
            top = marginVPx;
        } else if (yAlign == 2) {
            top = (viewH - h) * 0.5f;
        } else {
            top = viewH - marginVPx - h;
        }

        return new RectF(left, top, left + w, top + h);
    }

    /**
     * Pushes {@code box} vertically until it does not collide with any previously placed
     * unpositioned subtitle box, preventing text overlap for simultaneous lines.
     */
    private static void resolveCollision(RectF box,
                                         int alignment,
                                         List<RectF> occupied,
                                         int viewH,
                                         float vGap,
                                         float hPad) {
        if (occupied.isEmpty()) return;
        int yAlign = (alignment - 1) / 3 + 1; // 1=bottom, 2=middle, 3=top
        boolean pushUp = (yAlign != 3); // bottom & middle stack upwards; top stacks downwards

        int maxIterations = occupied.size() + 4;
        for (int iter = 0; iter < maxIterations; iter++) {
            RectF blocker = null;
            for (int i = 0; i < occupied.size(); i++) {
                RectF occ = occupied.get(i);
                boolean horizOverlap = box.left < occ.right + hPad && box.right > occ.left - hPad;
                boolean vertOverlap = box.top < occ.bottom + vGap && box.bottom > occ.top - vGap;
                if (horizOverlap && vertOverlap) {
                    if (blocker == null) {
                        blocker = occ;
                    } else if (pushUp && occ.top < blocker.top) {
                        blocker = occ;
                    } else if (!pushUp && occ.bottom > blocker.bottom) {
                        blocker = occ;
                    }
                }
            }
            if (blocker == null) break;

            if (pushUp) {
                float dy = (blocker.top - vGap) - box.bottom;
                box.offset(0f, dy);
                if (box.top < 0f && yAlign == 2) {
                    // Middle cue ran out of space above; switch direction downward
                    pushUp = false;
                }
            } else {
                float dy = (blocker.bottom + vGap) - box.top;
                box.offset(0f, dy);
            }
        }

        // Keep strictly within visible video bounds when possible
        float h = box.height();
        if (box.top < 0f) {
            box.top = 0f;
            box.bottom = h;
        } else if (box.bottom > viewH) {
            box.bottom = viewH;
            box.top = Math.max(0f, viewH - h);
        }
    }

    private void drawTextEvent(Canvas canvas,
                               AssScript.AssEvent ev,
                               TextBlockLayout layout,
                               long posMs,
                               float fadeFactor,
                               float sx,
                               float sy,
                               float borderScaleX,
                               float borderScaleY) {
        int an = ev.alignment;
        int xAlign = (an - 1) % 3 + 1; // 1=left, 2=center, 3=right
        int yAlign = (an - 1) / 3 + 1; // 1=bottom, 2=middle, 3=top

        float blockLeft;
        float blockTop;
        float anchorPxX;
        float anchorPxY;

        if (ev.isExplicitlyPositioned()) {
            anchorPxX = ev.computePosXAt(posMs) * sx;
            anchorPxY = ev.computePosYAt(posMs) * sy;

            if (xAlign == 1) blockLeft = anchorPxX;
            else if (xAlign == 2) blockLeft = anchorPxX - layout.blockWidthPx * 0.5f;
            else blockLeft = anchorPxX - layout.blockWidthPx;

            if (yAlign == 3) blockTop = anchorPxY;
            else if (yAlign == 2) blockTop = anchorPxY - layout.blockHeightPx * 0.5f;
            else blockTop = anchorPxY - layout.blockHeightPx;
        } else {
            RectF placed = placedRectsByEventIndex.get(ev.eventIndex);
            if (placed == null) return;
            blockLeft = placed.left;
            blockTop = placed.top;

            if (xAlign == 1) anchorPxX = blockLeft;
            else if (xAlign == 2) anchorPxX = blockLeft + layout.blockWidthPx * 0.5f;
            else anchorPxX = blockLeft + layout.blockWidthPx;

            if (yAlign == 3) anchorPxY = blockTop;
            else if (yAlign == 2) anchorPxY = blockTop + layout.blockHeightPx * 0.5f;
            else anchorPxY = blockTop + layout.blockHeightPx;
        }

        // Representative event-level rotation/shear from first run
        MeasuredRun firstRun = findFirstRun(layout);
        float frz = firstRun != null ? firstRun.style.frz : ev.style.angle;
        float fax = firstRun != null ? firstRun.style.fax : 0f;
        float fay = firstRun != null ? firstRun.style.fay : 0f;

        int saveCount = canvas.save();
        if (Math.abs(frz) > 0.01f || Math.abs(fax) > 0.001f || Math.abs(fay) > 0.001f) {
            float pivotX = ev.hasOrg ? ev.orgX * sx : anchorPxX;
            float pivotY = ev.hasOrg ? ev.orgY * sy : anchorPxY;
            canvas.translate(pivotX, pivotY);
            if (Math.abs(frz) > 0.01f) {
                canvas.rotate(-frz);
            }
            if (Math.abs(fax) > 0.001f || Math.abs(fay) > 0.001f) {
                canvas.skew(fax, fay);
            }
            canvas.translate(-pivotX, -pivotY);
        }

        float borderAvgScale = (borderScaleX + borderScaleY) * 0.5f;

        // Pass 0: Opaque box background (BorderStyle == 3) if applicable
        if (firstRun != null && firstRun.style.borderStyle == AssScript.BORDER_STYLE_OPAQUE_BOX) {
            float padX = Math.max(4f, firstRun.style.borderX * borderScaleX);
            float padY = Math.max(2f, firstRun.style.borderY * borderScaleY);
            int boxColor = AssScript.multiplyAlpha(firstRun.style.outlineColor, fadeFactor);
            int shadowColor = AssScript.multiplyAlpha(firstRun.style.backColor, fadeFactor);
            float lineY = blockTop;
            for (int l = 0; l < layout.lines.size(); l++) {
                MeasuredLine line = layout.lines.get(l);
                float lineLeft = computeLineLeft(blockLeft, layout.blockWidthPx, line.widthPx, xAlign);
                float lineH = line.getLineHeightPx();
                if ((Math.abs(firstRun.style.shadowX) > 0.01f || Math.abs(firstRun.style.shadowY) > 0.01f)
                        && Color.alpha(shadowColor) > 0) {
                    boxPaint.setColor(shadowColor);
                    float shx = firstRun.style.shadowX * borderScaleX;
                    float shy = firstRun.style.shadowY * borderScaleY;
                    tempRect.set(lineLeft - padX + shx, lineY - padY + shy,
                            lineLeft + line.widthPx + padX + shx, lineY + lineH + padY + shy);
                    canvas.drawRect(tempRect, boxPaint);
                }
                if (Color.alpha(boxColor) > 0) {
                    boxPaint.setColor(boxColor);
                    tempRect.set(lineLeft - padX, lineY - padY,
                            lineLeft + line.widthPx + padX, lineY + lineH + padY);
                    canvas.drawRect(tempRect, boxPaint);
                }
                lineY += lineH;
            }
        }

        // Pass 1: Shadows (for BorderStyle == 1)
        drawTextPass(canvas, layout, blockLeft, blockTop, xAlign, fadeFactor, borderScaleX, borderScaleY, borderAvgScale, PASS_SHADOW);
        // Pass 2: Outlines / Borders (drawn behind fill so glyphs remain sharp & exact)
        drawTextPass(canvas, layout, blockLeft, blockTop, xAlign, fadeFactor, borderScaleX, borderScaleY, borderAvgScale, PASS_OUTLINE);
        // Pass 3: Primary Text Fill (drawn on top with exact per-span colors)
        drawTextPass(canvas, layout, blockLeft, blockTop, xAlign, fadeFactor, borderScaleX, borderScaleY, borderAvgScale, PASS_FILL);

        textPaint.setMaskFilter(null);
        canvas.restoreToCount(saveCount);
    }

    private static final int PASS_SHADOW = 0;
    private static final int PASS_OUTLINE = 1;
    private static final int PASS_FILL = 2;

    private void drawTextPass(Canvas canvas,
                              TextBlockLayout layout,
                              float blockLeft,
                              float blockTop,
                              int xAlign,
                              float fadeFactor,
                              float borderScaleX,
                              float borderScaleY,
                              float borderAvgScale,
                              int pass) {
        float lineTop = blockTop;
        for (int l = 0; l < layout.lines.size(); l++) {
            MeasuredLine line = layout.lines.get(l);
            float cursorX = computeLineLeft(blockLeft, layout.blockWidthPx, line.widthPx, xAlign);
            float baselineY = lineTop + line.maxAscentPx;

            for (int r = 0; r < line.runs.size(); r++) {
                MeasuredRun run = line.runs.get(r);
                AssScript.SegmentStyle st = run.style;

                if (run.isDrawing && run.parsedVector != null) {
                    float drawOriginY = baselineY - run.ascentPx + run.pboPx;
                    tempMatrix.reset();
                    tempMatrix.postScale(run.scaleXRatio, run.scaleYRatio);
                    tempMatrix.postTranslate(cursorX, drawOriginY);
                    tempPath.reset();
                    run.parsedVector.path.transform(tempMatrix, tempPath);
                    tempPath.setFillType(Path.FillType.WINDING);

                    float avgBorder = (st.borderX + st.borderY) * 0.5f;
                    float strokeWidthPx = avgBorder * borderAvgScale * 2f;
                    float blurPx = st.blur > 0.1f ? Math.min(24f, st.blur * borderAvgScale) : 0f;
                    BlurMaskFilter blurFilter = blurPx > 0.2f ? new BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) : null;

                    if (pass == PASS_SHADOW) {
                        int shadowColor = AssScript.multiplyAlpha(st.backColor, fadeFactor);
                        if ((Math.abs(st.shadowX) > 0.01f || Math.abs(st.shadowY) > 0.01f) && Color.alpha(shadowColor) > 0) {
                            int shSave = canvas.save();
                            canvas.translate(st.shadowX * borderScaleX, st.shadowY * borderScaleY);
                            if (strokeWidthPx > 0.1f) {
                                vectorStrokePaint.setColor(shadowColor);
                                vectorStrokePaint.setStrokeWidth(strokeWidthPx);
                                vectorStrokePaint.setMaskFilter(blurFilter);
                                canvas.drawPath(tempPath, vectorStrokePaint);
                            }
                            vectorFillPaint.setColor(shadowColor);
                            vectorFillPaint.setMaskFilter(blurFilter);
                            canvas.drawPath(tempPath, vectorFillPaint);
                            canvas.restoreToCount(shSave);
                        }
                    } else if (pass == PASS_OUTLINE) {
                        int outlineColor = AssScript.multiplyAlpha(st.outlineColor, fadeFactor);
                        if (strokeWidthPx > 0.1f && Color.alpha(outlineColor) > 0) {
                            vectorStrokePaint.setColor(outlineColor);
                            vectorStrokePaint.setStrokeWidth(strokeWidthPx);
                            vectorStrokePaint.setMaskFilter(blurFilter);
                            canvas.drawPath(tempPath, vectorStrokePaint);
                        }
                    } else {
                        int fillColor = AssScript.multiplyAlpha(st.primaryColor, fadeFactor);
                        if (Color.alpha(fillColor) > 0) {
                            vectorFillPaint.setColor(fillColor);
                            vectorFillPaint.setMaskFilter(blurFilter);
                            canvas.drawPath(tempPath, vectorFillPaint);
                        }
                    }
                    vectorFillPaint.setMaskFilter(null);
                    vectorStrokePaint.setMaskFilter(null);
                    cursorX += run.widthPx;
                    continue;
                }

                configureTextPaint(textPaint, run.typeface, run.textSizePx, run.spacingPx,
                        run.scaleXRatio, st.underline, st.strikeOut);

                float avgBorder = (st.borderX + st.borderY) * 0.5f;
                float strokeWidthPx = avgBorder * borderAvgScale * 2f;
                float blurPx = st.blur > 0.1f ? Math.min(20f, st.blur * borderAvgScale) : 0f;
                BlurMaskFilter blurFilter = blurPx > 0.2f ? new BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) : null;

                boolean hasScaleY = Math.abs(run.scaleYRatio - 1f) > 0.01f;
                int runSave = hasScaleY ? canvas.save() : -1;
                if (hasScaleY) {
                    canvas.translate(cursorX, baselineY);
                    canvas.scale(1f, run.scaleYRatio);
                    canvas.translate(-cursorX, -baselineY);
                }

                if (pass == PASS_SHADOW) {
                    if (st.borderStyle == AssScript.BORDER_STYLE_OUTLINE
                            && (Math.abs(st.shadowX) > 0.01f || Math.abs(st.shadowY) > 0.01f)) {
                        int shadowColor = AssScript.multiplyAlpha(st.backColor, fadeFactor);
                        if (Color.alpha(shadowColor) > 0) {
                            float shx = st.shadowX * borderScaleX;
                            float shy = st.shadowY * borderScaleY;
                            textPaint.setColor(shadowColor);
                            textPaint.setMaskFilter(blurFilter);
                            if (strokeWidthPx > 0.1f) {
                                textPaint.setStyle(Paint.Style.STROKE);
                                textPaint.setStrokeJoin(Paint.Join.ROUND);
                                textPaint.setStrokeCap(Paint.Cap.ROUND);
                                textPaint.setStrokeWidth(strokeWidthPx);
                                canvas.drawText(run.text, cursorX + shx, baselineY + shy, textPaint);
                            }
                            textPaint.setStyle(Paint.Style.FILL);
                            canvas.drawText(run.text, cursorX + shx, baselineY + shy, textPaint);
                        }
                    }
                } else if (pass == PASS_OUTLINE) {
                    if (st.borderStyle == AssScript.BORDER_STYLE_OUTLINE && strokeWidthPx > 0.1f) {
                        int outlineColor = AssScript.multiplyAlpha(st.outlineColor, fadeFactor);
                        if (Color.alpha(outlineColor) > 0) {
                            textPaint.setStyle(Paint.Style.STROKE);
                            textPaint.setStrokeJoin(Paint.Join.ROUND);
                            textPaint.setStrokeCap(Paint.Cap.ROUND);
                            textPaint.setStrokeWidth(strokeWidthPx);
                            textPaint.setColor(outlineColor);
                            textPaint.setMaskFilter(blurFilter);
                            canvas.drawText(run.text, cursorX, baselineY, textPaint);
                        }
                    }
                } else {
                    int fillColor = AssScript.multiplyAlpha(st.primaryColor, fadeFactor);
                    if (Color.alpha(fillColor) > 0) {
                        textPaint.setStyle(Paint.Style.FILL);
                        textPaint.setColor(fillColor);
                        textPaint.setMaskFilter(blurFilter);
                        canvas.drawText(run.text, cursorX, baselineY, textPaint);
                    }
                }

                if (hasScaleY) {
                    canvas.restoreToCount(runSave);
                }
                cursorX += run.widthPx;
            }
            lineTop += line.getLineHeightPx();
        }
    }

    private static float computeLineLeft(float blockLeft, float blockWidth, float lineWidth, int xAlign) {
        if (xAlign == 1) return blockLeft;
        if (xAlign == 2) return blockLeft + (blockWidth - lineWidth) * 0.5f;
        return blockLeft + (blockWidth - lineWidth);
    }

    @Nullable
    private static MeasuredRun findFirstRun(TextBlockLayout layout) {
        for (int i = 0; i < layout.lines.size(); i++) {
            MeasuredLine line = layout.lines.get(i);
            if (!line.runs.isEmpty()) {
                return line.runs.get(0);
            }
        }
        return null;
    }
}
