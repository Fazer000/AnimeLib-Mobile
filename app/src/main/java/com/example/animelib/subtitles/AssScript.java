package com.example.animelib.subtitles;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Complete parsed representation of an ASS/SSA subtitle script, including
 * [Script Info], [V4+ Styles], and pre-parsed [Events] with override tags intact.
 */
public final class AssScript {

    public static final int BORDER_STYLE_OUTLINE = 1;
    public static final int BORDER_STYLE_OPAQUE_BOX = 3;

    public final float playResX;
    public final float playResY;
    public final boolean scaledBorderAndShadow;
    public final boolean reverseCollisions;
    public final int defaultWrapStyle;
    public final Map<String, AssStyle> styles;
    public final List<AssEvent> events;
    private final long[] prefixMaxEndMs;

    public AssScript(float playResX,
                     float playResY,
                     boolean scaledBorderAndShadow,
                     boolean reverseCollisions,
                     int defaultWrapStyle,
                     @NonNull Map<String, AssStyle> styles,
                     @NonNull List<AssEvent> events) {
        this.playResX = playResX;
        this.playResY = playResY;
        this.scaledBorderAndShadow = scaledBorderAndShadow;
        this.reverseCollisions = reverseCollisions;
        this.defaultWrapStyle = defaultWrapStyle;
        this.styles = Collections.unmodifiableMap(new HashMap<>(styles));

        List<AssEvent> sorted = new ArrayList<>(events);
        sorted.sort((a, b) -> {
            int cmp = Long.compare(a.startMs, b.startMs);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.layer, b.layer);
            if (cmp != 0) return cmp;
            return Integer.compare(a.eventIndex, b.eventIndex);
        });
        this.events = Collections.unmodifiableList(sorted);
        this.prefixMaxEndMs = new long[this.events.size()];
        long runningMax = Long.MIN_VALUE;
        for (int i = 0; i < this.events.size(); i++) {
            long end = this.events.get(i).endMs;
            if (end > runningMax) {
                runningMax = end;
            }
            this.prefixMaxEndMs[i] = runningMax;
        }
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Resolves effective PlayResX, falling back cleanly when [Script Info] omits PlayResX.
     */
    public float resolvePlayResX(int videoWidth, int videoHeight) {
        if (playResX > 0f) {
            return playResX;
        }
        if (playResY > 0f && videoWidth > 0 && videoHeight > 0) {
            return playResY * ((float) videoWidth / (float) videoHeight);
        }
        if (playResY > 0f) {
            return playResY * (16f / 9f);
        }
        if (videoWidth > 0) {
            return (float) videoWidth;
        }
        return 384f;
    }

    /**
     * Resolves effective PlayResY, falling back cleanly when [Script Info] omits PlayResY.
     */
    public float resolvePlayResY(int videoWidth, int videoHeight) {
        if (playResY > 0f) {
            return playResY;
        }
        if (playResX > 0f && videoWidth > 0 && videoHeight > 0) {
            return playResX * ((float) videoHeight / (float) videoWidth);
        }
        if (playResX > 0f) {
            return playResX * (9f / 16f);
        }
        if (videoHeight > 0) {
            return (float) videoHeight;
        }
        return 288f;
    }

    /**
     * Finds all events active at {@code positionMs} in O(log N + K), sorted by (layer ASC, eventIndex ASC).
     */
    public void getActiveEvents(long positionMs, @NonNull List<AssEvent> out) {
        out.clear();
        int n = events.size();
        if (n == 0 || positionMs < 0) return;

        // Binary search for the rightmost index with startMs <= positionMs
        int lo = 0;
        int hi = n - 1;
        int upper = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).startMs <= positionMs) {
                upper = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (upper < 0) return;

        for (int i = upper; i >= 0; i--) {
            if (prefixMaxEndMs[i] <= positionMs) {
                break;
            }
            AssEvent ev = events.get(i);
            if (ev.startMs <= positionMs && positionMs < ev.endMs) {
                out.add(ev);
            }
        }

        if (out.size() > 1) {
            out.sort(LAYER_ORDER_COMPARATOR);
        }
    }

    public static final Comparator<AssEvent> LAYER_ORDER_COMPARATOR = (a, b) -> {
        int cmp = Integer.compare(a.layer, b.layer);
        if (cmp != 0) return cmp;
        return Integer.compare(a.eventIndex, b.eventIndex);
    };

    public static String normalizeStyleKey(@Nullable String name) {
        if (name == null) return "default";
        String trimmed = name.trim();
        while (trimmed.startsWith("*")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.isEmpty() ? "default" : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Represents a single [V4+ Styles] / [V4 Styles] definition.
     */
    public static final class AssStyle {
        public final String name;
        public final String fontName;
        public final float fontSize;
        public final int primaryColor;   // ARGB
        public final int secondaryColor; // ARGB
        public final int outlineColor;   // ARGB
        public final int backColor;      // ARGB
        public final boolean bold;
        public final boolean italic;
        public final boolean underline;
        public final boolean strikeOut;
        public final float scaleX;       // percent, default 100
        public final float scaleY;       // percent, default 100
        public final float spacing;      // script px
        public final float angle;        // degrees (frz)
        public final int borderStyle;    // 1 = outline+shadow, 3 = opaque box
        public final float outline;      // script px
        public final float shadow;       // script px
        public final int alignment;      // numpad 1..9
        public final int marginL;
        public final int marginR;
        public final int marginV;

        public AssStyle(String name,
                        String fontName,
                        float fontSize,
                        int primaryColor,
                        int secondaryColor,
                        int outlineColor,
                        int backColor,
                        boolean bold,
                        boolean italic,
                        boolean underline,
                        boolean strikeOut,
                        float scaleX,
                        float scaleY,
                        float spacing,
                        float angle,
                        int borderStyle,
                        float outline,
                        float shadow,
                        int alignment,
                        int marginL,
                        int marginR,
                        int marginV) {
            this.name = name;
            this.fontName = fontName != null && !fontName.trim().isEmpty() ? fontName.trim() : "Arial";
            this.fontSize = fontSize > 0f ? fontSize : 20f;
            this.primaryColor = primaryColor;
            this.secondaryColor = secondaryColor;
            this.outlineColor = outlineColor;
            this.backColor = backColor;
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
            this.strikeOut = strikeOut;
            this.scaleX = scaleX > 0f ? scaleX : 100f;
            this.scaleY = scaleY > 0f ? scaleY : 100f;
            this.spacing = spacing;
            this.angle = angle;
            this.borderStyle = (borderStyle == BORDER_STYLE_OPAQUE_BOX) ? BORDER_STYLE_OPAQUE_BOX : BORDER_STYLE_OUTLINE;
            this.outline = Math.max(0f, outline);
            this.shadow = Math.max(0f, shadow);
            this.alignment = (alignment >= 1 && alignment <= 9) ? alignment : 2;
            this.marginL = Math.max(0, marginL);
            this.marginR = Math.max(0, marginR);
            this.marginV = Math.max(0, marginV);
        }

        public static AssStyle createDefault() {
            return new AssStyle(
                    "Default",
                    "Arial",
                    22f,
                    0xFFFFFFFF,
                    0xFFFFFF00,
                    0xFF000000,
                    0x80000000,
                    false,
                    false,
                    false,
                    false,
                    100f,
                    100f,
                    0f,
                    0f,
                    BORDER_STYLE_OUTLINE,
                    2f,
                    1.5f,
                    2,
                    10,
                    10,
                    10
            );
        }
    }

    /**
     * Resolved style state for an individual text run or vector drawing segment.
     */
    public static final class SegmentStyle {
        public String fontName;
        public float fontSize;
        public int primaryColor;   // ARGB (1c + 1a)
        public int secondaryColor; // ARGB (2c + 2a)
        public int outlineColor;   // ARGB (3c + 3a)
        public int backColor;      // ARGB (4c + 4a)
        public boolean bold;
        public boolean italic;
        public boolean underline;
        public boolean strikeOut;
        public float scaleX;       // percent
        public float scaleY;       // percent
        public float spacing;      // script px
        public float frz;          // degrees
        public float fax;          // horizontal shear
        public float fay;          // vertical shear
        public int borderStyle;
        public float borderX;
        public float borderY;
        public float shadowX;
        public float shadowY;
        public float blur;
        public int pLevel;         // 0 = text, 1..9 = drawing scale
        public float pbo;          // baseline offset for drawings

        public SegmentStyle(@NonNull AssStyle base) {
            applyFrom(base);
        }

        public SegmentStyle(@NonNull SegmentStyle other) {
            this.fontName = other.fontName;
            this.fontSize = other.fontSize;
            this.primaryColor = other.primaryColor;
            this.secondaryColor = other.secondaryColor;
            this.outlineColor = other.outlineColor;
            this.backColor = other.backColor;
            this.bold = other.bold;
            this.italic = other.italic;
            this.underline = other.underline;
            this.strikeOut = other.strikeOut;
            this.scaleX = other.scaleX;
            this.scaleY = other.scaleY;
            this.spacing = other.spacing;
            this.frz = other.frz;
            this.fax = other.fax;
            this.fay = other.fay;
            this.borderStyle = other.borderStyle;
            this.borderX = other.borderX;
            this.borderY = other.borderY;
            this.shadowX = other.shadowX;
            this.shadowY = other.shadowY;
            this.blur = other.blur;
            this.pLevel = other.pLevel;
            this.pbo = other.pbo;
        }

        public void applyFrom(@NonNull AssStyle base) {
            this.fontName = base.fontName;
            this.fontSize = base.fontSize;
            this.primaryColor = base.primaryColor;
            this.secondaryColor = base.secondaryColor;
            this.outlineColor = base.outlineColor;
            this.backColor = base.backColor;
            this.bold = base.bold;
            this.italic = base.italic;
            this.underline = base.underline;
            this.strikeOut = base.strikeOut;
            this.scaleX = base.scaleX;
            this.scaleY = base.scaleY;
            this.spacing = base.spacing;
            this.frz = base.angle;
            this.fax = 0f;
            this.fay = 0f;
            this.borderStyle = base.borderStyle;
            this.borderX = base.outline;
            this.borderY = base.outline;
            this.shadowX = base.shadow;
            this.shadowY = base.shadow;
            this.blur = 0f;
            this.pLevel = 0;
            this.pbo = 0f;
        }

        public SegmentStyle copy() {
            return new SegmentStyle(this);
        }
    }

    /**
     * Represents an animated \t([t1, t2, [accel,]]\tags...) override applied over time.
     */
    public static final class AssTransform {
        public final long t1Ms;
        public final long t2Ms;
        public final float accel;
        @Nullable public final Integer primaryRgb;
        @Nullable public final Integer outlineRgb;
        @Nullable public final Integer backRgb;
        @Nullable public final Integer allAlpha;
        @Nullable public final Integer primaryAlpha;
        @Nullable public final Integer outlineAlpha;
        @Nullable public final Integer backAlpha;
        @Nullable public final Float fontSize;
        @Nullable public final Float scaleX;
        @Nullable public final Float scaleY;
        @Nullable public final Float frz;
        @Nullable public final Float borderX;
        @Nullable public final Float borderY;
        @Nullable public final Float shadowX;
        @Nullable public final Float shadowY;
        @Nullable public final Float blur;
        @Nullable public final Float spacing;

        public AssTransform(long t1Ms,
                            long t2Ms,
                            float accel,
                            @Nullable Integer primaryRgb,
                            @Nullable Integer outlineRgb,
                            @Nullable Integer backRgb,
                            @Nullable Integer allAlpha,
                            @Nullable Integer primaryAlpha,
                            @Nullable Integer outlineAlpha,
                            @Nullable Integer backAlpha,
                            @Nullable Float fontSize,
                            @Nullable Float scaleX,
                            @Nullable Float scaleY,
                            @Nullable Float frz,
                            @Nullable Float borderX,
                            @Nullable Float borderY,
                            @Nullable Float shadowX,
                            @Nullable Float shadowY,
                            @Nullable Float blur,
                            @Nullable Float spacing) {
            this.t1Ms = t1Ms;
            this.t2Ms = t2Ms;
            this.accel = accel > 0f ? accel : 1f;
            this.primaryRgb = primaryRgb;
            this.outlineRgb = outlineRgb;
            this.backRgb = backRgb;
            this.allAlpha = allAlpha;
            this.primaryAlpha = primaryAlpha;
            this.outlineAlpha = outlineAlpha;
            this.backAlpha = backAlpha;
            this.fontSize = fontSize;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.frz = frz;
            this.borderX = borderX;
            this.borderY = borderY;
            this.shadowX = shadowX;
            this.shadowY = shadowY;
            this.blur = blur;
            this.spacing = spacing;
        }

        public float computeProgress(long relTimeMs, long eventDurationMs) {
            long start = t1Ms >= 0 ? t1Ms : 0L;
            long end = t2Ms >= 0 ? t2Ms : Math.max(1L, eventDurationMs);
            if (relTimeMs <= start) return 0f;
            if (relTimeMs >= end || end <= start) return 1f;
            float linear = (float) (relTimeMs - start) / (float) (end - start);
            if (Math.abs(accel - 1f) < 0.0001f) return linear;
            return (float) Math.pow(linear, accel);
        }

        public void applyTo(@NonNull SegmentStyle target, float progress) {
            if (progress <= 0f) return;
            float p = Math.min(1f, progress);
            if (primaryRgb != null) {
                target.primaryColor = lerpRgbKeepAlpha(target.primaryColor, primaryRgb, p);
            }
            if (outlineRgb != null) {
                target.outlineColor = lerpRgbKeepAlpha(target.outlineColor, outlineRgb, p);
            }
            if (backRgb != null) {
                target.backColor = lerpRgbKeepAlpha(target.backColor, backRgb, p);
            }
            if (allAlpha != null) {
                target.primaryColor = lerpAlphaKeepRgb(target.primaryColor, allAlpha, p);
                target.outlineColor = lerpAlphaKeepRgb(target.outlineColor, allAlpha, p);
                target.backColor = lerpAlphaKeepRgb(target.backColor, allAlpha, p);
            }
            if (primaryAlpha != null) {
                target.primaryColor = lerpAlphaKeepRgb(target.primaryColor, primaryAlpha, p);
            }
            if (outlineAlpha != null) {
                target.outlineColor = lerpAlphaKeepRgb(target.outlineColor, outlineAlpha, p);
            }
            if (backAlpha != null) {
                target.backColor = lerpAlphaKeepRgb(target.backColor, backAlpha, p);
            }
            if (fontSize != null) {
                target.fontSize = lerp(target.fontSize, fontSize, p);
            }
            if (scaleX != null) {
                target.scaleX = lerp(target.scaleX, scaleX, p);
            }
            if (scaleY != null) {
                target.scaleY = lerp(target.scaleY, scaleY, p);
            }
            if (frz != null) {
                target.frz = lerp(target.frz, frz, p);
            }
            if (borderX != null) {
                target.borderX = lerp(target.borderX, borderX, p);
            }
            if (borderY != null) {
                target.borderY = lerp(target.borderY, borderY, p);
            }
            if (shadowX != null) {
                target.shadowX = lerp(target.shadowX, shadowX, p);
            }
            if (shadowY != null) {
                target.shadowY = lerp(target.shadowY, shadowY, p);
            }
            if (blur != null) {
                target.blur = lerp(target.blur, blur, p);
            }
            if (spacing != null) {
                target.spacing = lerp(target.spacing, spacing, p);
            }
        }
    }

    /**
     * Rectangular or vector \clip / \iclip region.
     */
    public static final class AssClip {
        public final boolean inverted;
        public final boolean isVector;
        public final float x1;
        public final float y1;
        public final float x2;
        public final float y2;
        public final int vectorScale;
        @Nullable public final String vectorCommands;

        public static AssClip rectangular(boolean inverted, float x1, float y1, float x2, float y2) {
            return new AssClip(inverted, false, x1, y1, x2, y2, 1, null);
        }

        public static AssClip vector(boolean inverted, int scale, @NonNull String commands) {
            return new AssClip(inverted, true, 0f, 0f, 0f, 0f, Math.max(1, scale), commands);
        }

        private AssClip(boolean inverted, boolean isVector,
                        float x1, float y1, float x2, float y2,
                        int vectorScale, @Nullable String vectorCommands) {
            this.inverted = inverted;
            this.isVector = isVector;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.vectorScale = vectorScale;
            this.vectorCommands = vectorCommands;
        }
    }

    /**
     * A contiguous styled text or vector drawing segment within a single Dialogue event.
     */
    public static final class AssSegment {
        public final boolean isDrawing;
        public final String content;
        public final SegmentStyle style;
        @Nullable public final List<AssTransform> transforms;

        public AssSegment(boolean isDrawing,
                          @NonNull String content,
                          @NonNull SegmentStyle style,
                          @Nullable List<AssTransform> transforms) {
            this.isDrawing = isDrawing;
            this.content = content;
            this.style = style;
            this.transforms = (transforms != null && !transforms.isEmpty())
                    ? Collections.unmodifiableList(new ArrayList<>(transforms))
                    : null;
        }

        public SegmentStyle resolveStyleAt(long relTimeMs, long eventDurationMs) {
            if (transforms == null || transforms.isEmpty()) {
                return style;
            }
            SegmentStyle resolved = style.copy();
            for (int i = 0; i < transforms.size(); i++) {
                AssTransform tr = transforms.get(i);
                float progress = tr.computeProgress(relTimeMs, eventDurationMs);
                tr.applyTo(resolved, progress);
            }
            return resolved;
        }
    }

    /**
     * A single parsed Dialogue event ready for layout and rendering.
     */
    public static final class AssEvent {
        public final int eventIndex;
        public final int layer;
        public final long startMs;
        public final long endMs;
        public final AssStyle style;
        public final int marginL;
        public final int marginR;
        public final int marginV;
        public final int alignment; // 1..9
        public final boolean hasPos;
        public final float posX;
        public final float posY;
        public final boolean hasMove;
        public final float moveX1;
        public final float moveY1;
        public final float moveX2;
        public final float moveY2;
        public final long moveT1Ms;
        public final long moveT2Ms;
        public final boolean hasOrg;
        public final float orgX;
        public final float orgY;
        public final long fadeInMs;
        public final long fadeOutMs;
        public final boolean hasComplexFade;
        public final int fadeA1;
        public final int fadeA2;
        public final int fadeA3;
        public final long fadeT1;
        public final long fadeT2;
        public final long fadeT3;
        public final long fadeT4;
        @Nullable public final AssClip clip;
        public final int wrapStyle;
        public final List<AssSegment> segments;
        public final boolean hasVectorDrawing;
        public final boolean hasAnimations;

        public AssEvent(int eventIndex,
                        int layer,
                        long startMs,
                        long endMs,
                        @NonNull AssStyle style,
                        int marginL,
                        int marginR,
                        int marginV,
                        int alignment,
                        boolean hasPos,
                        float posX,
                        float posY,
                        boolean hasMove,
                        float moveX1,
                        float moveY1,
                        float moveX2,
                        float moveY2,
                        long moveT1Ms,
                        long moveT2Ms,
                        boolean hasOrg,
                        float orgX,
                        float orgY,
                        long fadeInMs,
                        long fadeOutMs,
                        boolean hasComplexFade,
                        int fadeA1,
                        int fadeA2,
                        int fadeA3,
                        long fadeT1,
                        long fadeT2,
                        long fadeT3,
                        long fadeT4,
                        @Nullable AssClip clip,
                        int wrapStyle,
                        @NonNull List<AssSegment> segments) {
            this.eventIndex = eventIndex;
            this.layer = layer;
            this.startMs = startMs;
            this.endMs = endMs;
            this.style = style;
            this.marginL = marginL;
            this.marginR = marginR;
            this.marginV = marginV;
            this.alignment = (alignment >= 1 && alignment <= 9) ? alignment : style.alignment;
            this.hasPos = hasPos;
            this.posX = posX;
            this.posY = posY;
            this.hasMove = hasMove;
            this.moveX1 = moveX1;
            this.moveY1 = moveY1;
            this.moveX2 = moveX2;
            this.moveY2 = moveY2;
            this.moveT1Ms = moveT1Ms;
            this.moveT2Ms = moveT2Ms;
            this.hasOrg = hasOrg;
            this.orgX = orgX;
            this.orgY = orgY;
            this.fadeInMs = Math.max(0L, fadeInMs);
            this.fadeOutMs = Math.max(0L, fadeOutMs);
            this.hasComplexFade = hasComplexFade;
            this.fadeA1 = fadeA1;
            this.fadeA2 = fadeA2;
            this.fadeA3 = fadeA3;
            this.fadeT1 = fadeT1;
            this.fadeT2 = fadeT2;
            this.fadeT3 = fadeT3;
            this.fadeT4 = fadeT4;
            this.clip = clip;
            this.wrapStyle = wrapStyle;
            this.segments = Collections.unmodifiableList(segments);

            boolean vec = false;
            boolean anim = hasMove || fadeInMs > 0 || fadeOutMs > 0 || hasComplexFade;
            for (int i = 0; i < segments.size(); i++) {
                AssSegment seg = segments.get(i);
                if (seg.isDrawing) vec = true;
                if (seg.transforms != null && !seg.transforms.isEmpty()) anim = true;
            }
            this.hasVectorDrawing = vec;
            this.hasAnimations = anim;
        }

        public boolean isExplicitlyPositioned() {
            return hasPos || hasMove;
        }

        public long getDurationMs() {
            return Math.max(1L, endMs - startMs);
        }

        /**
         * Computes effective fade opacity multiplier in [0.0f, 1.0f] at {@code positionMs}.
         */
        public float computeFadeAlphaFactor(long positionMs) {
            long rel = Math.max(0L, positionMs - startMs);
            long dur = getDurationMs();
            if (hasComplexFade) {
                int assTransparency;
                if (rel <= fadeT1) {
                    assTransparency = fadeA1;
                } else if (rel < fadeT2 && fadeT2 > fadeT1) {
                    float t = (float) (rel - fadeT1) / (float) (fadeT2 - fadeT1);
                    assTransparency = Math.round(lerp(fadeA1, fadeA2, t));
                } else if (rel <= fadeT3) {
                    assTransparency = fadeA2;
                } else if (rel < fadeT4 && fadeT4 > fadeT3) {
                    float t = (float) (rel - fadeT3) / (float) (fadeT4 - fadeT3);
                    assTransparency = Math.round(lerp(fadeA2, fadeA3, t));
                } else {
                    assTransparency = fadeA3;
                }
                int opacity = Math.max(0, Math.min(255, 255 - assTransparency));
                return opacity / 255f;
            }
            float factor = 1f;
            if (fadeInMs > 0 && rel < fadeInMs) {
                factor = Math.min(factor, (float) rel / (float) fadeInMs);
            }
            if (fadeOutMs > 0 && rel > dur - fadeOutMs) {
                long remaining = Math.max(0L, dur - rel);
                factor = Math.min(factor, (float) remaining / (float) fadeOutMs);
            }
            return Math.max(0f, Math.min(1f, factor));
        }

        public float computePosXAt(long positionMs) {
            if (!hasMove) return posX;
            float t = computeMoveProgress(positionMs);
            return lerp(moveX1, moveX2, t);
        }

        public float computePosYAt(long positionMs) {
            if (!hasMove) return posY;
            float t = computeMoveProgress(positionMs);
            return lerp(moveY1, moveY2, t);
        }

        private float computeMoveProgress(long positionMs) {
            long rel = positionMs - startMs;
            long t1 = moveT1Ms >= 0 ? moveT1Ms : 0L;
            long t2 = moveT2Ms > 0 ? moveT2Ms : getDurationMs();
            if (rel <= t1) return 0f;
            if (rel >= t2 || t2 <= t1) return 1f;
            return (float) (rel - t1) / (float) (t2 - t1);
        }
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static int replaceRgbKeepAlpha(int argb, int newColor) {
        return (argb & 0xFF000000) | (newColor & 0x00FFFFFF);
    }

    public static int replaceAlphaKeepRgb(int argb, int opacity0To255) {
        int clamped = Math.max(0, Math.min(255, opacity0To255));
        return (clamped << 24) | (argb & 0x00FFFFFF);
    }

    public static int multiplyAlpha(int argb, float factor) {
        if (factor >= 0.999f) return argb;
        int origA = (argb >>> 24) & 0xFF;
        int newA = Math.max(0, Math.min(255, Math.round(origA * factor)));
        return (newA << 24) | (argb & 0x00FFFFFF);
    }

    private static int lerpRgbKeepAlpha(int fromArgb, int toRgb, float t) {
        int a = (fromArgb >>> 24) & 0xFF;
        int r1 = (fromArgb >>> 16) & 0xFF;
        int g1 = (fromArgb >>> 8) & 0xFF;
        int b1 = fromArgb & 0xFF;
        int r2 = (toRgb >>> 16) & 0xFF;
        int g2 = (toRgb >>> 8) & 0xFF;
        int b2 = toRgb & 0xFF;
        int r = Math.round(r1 + (r2 - r1) * t);
        int g = Math.round(g1 + (g2 - g1) * t);
        int b = Math.round(b1 + (b2 - b1) * t);
        return (a << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    private static int lerpAlphaKeepRgb(int fromArgb, int toOpacity, float t) {
        int a1 = (fromArgb >>> 24) & 0xFF;
        int a = Math.max(0, Math.min(255, Math.round(a1 + (toOpacity - a1) * t)));
        return (a << 24) | (fromArgb & 0x00FFFFFF);
    }
}
