package com.example.animelib.controllers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import androidx.annotation.Nullable;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;

import com.example.animelib.subtitles.AssParser;
import com.example.animelib.subtitles.AssScript;
import com.example.animelib.subtitles.AssVectorGeometry;
import com.example.animelib.subtitles.AssVectorPathParser;
import com.example.animelib.util.CustomTypefaceSpan;
import com.example.animelib.util.FontResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback processor for ASS/SSA cues arriving via Media3 onCues (e.g. embedded subtitle tracks
 * or when external .ass download is unavailable). Handles vector paths (\p1..\p9),
 * left-to-right override tag parsing, color/alpha decoding, PlayRes heuristics,
 * and collision avoidance for overlapping simultaneous cues.
 */
public final class AssSubtitlesProcessor {

    private static final Pattern P_TAG_OPEN_PATTERN = Pattern.compile("(?i)\\{[^\\}]*\\\\p[1-9](?![0-9a-zA-Z])[^\\}]*\\}");
    private static final Pattern P_TAG_BARE_PATTERN = Pattern.compile("(?i)\\\\p[1-9]\\b");
    private static final Pattern ALL_TAGS_PATTERN = Pattern.compile("\\{([^\\}]+)\\}");
    private static final Pattern DRAWING_LINE_START_PATTERN = Pattern.compile("(?i)^[mn]\\s+-?\\d+.*");
    private static final Pattern WHITESPACE_SPLIT_PATTERN = Pattern.compile("\\s+");
    private static final Pattern DRAWING_CMD_TOKEN_PATTERN = Pattern.compile("(?i)^[mlbspcn]$");
    private static final Pattern DRAWING_ONLY_CHARS_PATTERN = Pattern.compile("(?i)^[mlbspcn0-9\\.\\-\\s,]+$");
    private static final Pattern DRAWING_TOKENS_SPLIT_PATTERN = Pattern.compile("[\\s,]+");

    private static final Pattern POS_OR_MOVE_PATTERN = Pattern.compile("(?i)\\\\(?:pos|move)\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)[^\\)]*\\)");
    private static final Pattern AN_TAG_PATTERN = Pattern.compile("(?i)\\\\an([1-9])");
    private static final Pattern LEGACY_A_TAG_PATTERN = Pattern.compile("(?i)\\\\a([1-9]|10|11)(?![0-9a-zA-Z])");
    private static final Pattern COLOR_1C_PATTERN = Pattern.compile("(?i)\\\\(?:1c|c)[&H#]*([0-9a-fA-F]{1,8})&?");
    private static final Pattern COLOR_3C_PATTERN = Pattern.compile("(?i)\\\\3c[&H#]*([0-9a-fA-F]{1,8})&?");
    private static final Pattern ALPHA_ALL_PATTERN = Pattern.compile("(?i)\\\\alpha[&H#]*([0-9a-fA-F]{1,2})&?");
    private static final Pattern ALPHA_1A_PATTERN = Pattern.compile("(?i)\\\\1a[&H#]*([0-9a-fA-F]{1,2})&?");
    private static final Pattern ALPHA_3A_PATTERN = Pattern.compile("(?i)\\\\3a[&H#]*([0-9a-fA-F]{1,2})&?");
    private static final Pattern BORD_PATTERN = Pattern.compile("(?i)\\\\bord(\\d+(?:\\.\\d+)?)");
    private static final Pattern P_LEVEL_PATTERN = Pattern.compile("(?i)\\\\p([1-9])(?![0-9a-zA-Z])");
    private static final Pattern PBO_PATTERN = Pattern.compile("(?i)\\\\pbo([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern FSCX_PATTERN = Pattern.compile("(?i)\\\\fscx(\\d+(?:\\.\\d+)?)");
    private static final Pattern FSCY_PATTERN = Pattern.compile("(?i)\\\\fscy(\\d+(?:\\.\\d+)?)");
    private static final Pattern P_BLOCK_PATTERN = Pattern.compile("(?i)\\{[^\\}]*\\\\p[1-9](?![0-9a-zA-Z])[^\\}]*\\}(.*?)(?:\\{[^\\}]*\\\\p0(?![0-9a-zA-Z])[^\\}]*\\}|$)", Pattern.DOTALL);
    private static final Pattern P_STRIP_PATTERN = Pattern.compile("(?i)\\{[^\\}]*\\\\p[1-9](?![0-9a-zA-Z])[^\\}]*\\}[^\\{]*(\\{[^\\}]*\\\\p0(?![0-9a-zA-Z])[^\\}]*\\})?");

    private AssSubtitlesProcessor() {}

    public static class AssDrawingResult {
        public final Path path;
        public final RectF bounds;

        public AssDrawingResult(Path path, RectF bounds) {
            this.path = path;
            this.bounds = bounds;
        }
    }

    private static class AssStyleState {
        Integer color = null;
        Integer alpha1 = null;
        Integer outlineColor = null;
        String font = null;
        Integer fontSize = null;
        Boolean bold = null;
        Boolean italic = null;
        Boolean underline = null;
        Boolean strikethrough = null;

        void reset() {
            color = null;
            alpha1 = null;
            outlineColor = null;
            font = null;
            fontSize = null;
            bold = null;
            italic = null;
            underline = null;
            strikethrough = null;
        }
    }

    public static AssDrawingResult parseAssPath(String drawingCommands, float scale) {
        AssVectorPathParser.ParsedVectorPath parsed = AssVectorPathParser.parse(drawingCommands, scale);
        if (parsed == null) return null;
        return new AssDrawingResult(parsed.path, parsed.bounds);
    }

    static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        char c = str.charAt(0);
        return Character.isDigit(c) || c == '-' || c == '+' || c == '.';
    }

    @Nullable
    private static Cue renderAssVectorCue(Path rawPath, RectF bounds, int fillColor, int strokeColor, float strokeWidth,
                                          float posX, float posY, int anVal, float pbo, float pScale,
                                          float fscx, float fscy, float playResX, float playResY,
                                          @Nullable Cue sourceCue) {
        if (rawPath == null || bounds == null || (bounds.width() <= 0f && bounds.height() <= 0f)) return null;
        if (fscx <= 0.001f || fscy <= 0.001f) return null;
        try {
            float bLeft = bounds.left * fscx;
            float bRight = bounds.right * fscx;
            float bTop = bounds.top * fscy;
            float bBottom = bounds.bottom * fscy;

            float padding = Math.max(4.0f, strokeWidth * 2.0f + 2.0f);
            float boundsW = Math.max(1f, bRight - bLeft) + padding * 2f;
            float boundsH = Math.max(1f, bBottom - bTop) + padding * 2f;

            float renderScale = Math.max(1.0f, Math.min(3.0f, 1080.0f / Math.max(1.0f, playResY)));
            int bmpWidth = Math.max(1, Math.min(2048, Math.round(boundsW * renderScale)));
            int bmpHeight = Math.max(1, Math.min(2048, Math.round(boundsH * renderScale)));

            Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Matrix matrix = new Matrix();
            matrix.postScale(fscx, fscy);
            matrix.postTranslate(-bLeft + padding, -bTop + padding);
            matrix.postScale(renderScale, renderScale);

            Path drawPath = new Path();
            rawPath.transform(matrix, drawPath);
            drawPath.setFillType(Path.FillType.WINDING);

            // In ASS, border/outline is drawn BEHIND fill at 2 * bord width
            if (strokeWidth > 0.05f && Color.alpha(strokeColor) > 0) {
                Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setColor(strokeColor);
                strokePaint.setStrokeWidth(Math.max(1.0f, strokeWidth * 2.0f * renderScale));
                strokePaint.setStrokeJoin(Paint.Join.ROUND);
                strokePaint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawPath(drawPath, strokePaint);
            }

            if (Color.alpha(fillColor) > 0) {
                Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(fillColor);
                canvas.drawPath(drawPath, fillPaint);
            }

            // If tags were stripped by Media3 SsaParser, recover position & alignment from sourceCue
            boolean hasExplicitPos = (posX >= 0f && posY >= 0f);
            float effectivePosX = posX;
            float effectivePosY = posY;
            int effectiveAn = anVal;

            if (!hasExplicitPos && sourceCue != null
                    && sourceCue.position != Cue.DIMEN_UNSET
                    && sourceCue.line != Cue.DIMEN_UNSET
                    && sourceCue.lineType == Cue.LINE_TYPE_FRACTION) {
                effectivePosX = sourceCue.position * playResX;
                effectivePosY = sourceCue.line * playResY;
                hasExplicitPos = true;
                int xPart = (sourceCue.positionAnchor == Cue.ANCHOR_TYPE_START) ? 1
                        : (sourceCue.positionAnchor == Cue.ANCHOR_TYPE_END ? 3 : 2);
                int yRow = (sourceCue.lineAnchor == Cue.ANCHOR_TYPE_START) ? 2
                        : (sourceCue.lineAnchor == Cue.ANCHOR_TYPE_MIDDLE ? 1 : 0);
                effectiveAn = yRow * 3 + xPart;
            }

            float anchorX = hasExplicitPos
                    ? effectivePosX
                    : AssVectorGeometry.computeUnpositionedAnchorX(playResX, 0, 0, effectiveAn);
            float anchorY = hasExplicitPos
                    ? effectivePosY
                    : AssVectorGeometry.computeUnpositionedAnchorY(playResY, 0, effectiveAn);

            float originX = AssVectorGeometry.computeSingleOriginX(anchorX, bLeft, bRight, effectiveAn);
            float originY = AssVectorGeometry.computeSingleOriginY(anchorY, bTop, bBottom, effectiveAn, pbo, pScale, fscy);

            float scriptLeft = originX + bLeft - padding;
            float scriptTop = originY + bTop - padding;

            float normX = Math.max(0.0f, Math.min(1.0f, scriptLeft / playResX));
            float normY = Math.max(0.0f, Math.min(1.0f, scriptTop / playResY));
            float normW = Math.max(0.001f, Math.min(1.0f, boundsW / playResX));
            float normH = Math.max(0.001f, Math.min(1.0f, boundsH / playResY));

            return new Cue.Builder()
                    .setBitmap(bitmap)
                    .setPosition(normX)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                    .setLine(normY, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_START)
                    .setSize(normW)
                    .setBitmapHeight(normH)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private static void applyAnAlignment(Cue.Builder builder, int an) {
        switch (an) {
            case 7:
                builder.setPosition(0.05f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                        .setLine(0.05f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                        .setTextAlignment(Layout.Alignment.ALIGN_NORMAL);
                break;
            case 8:
                builder.setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                        .setLine(0.05f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                        .setTextAlignment(Layout.Alignment.ALIGN_CENTER);
                break;
            case 9:
                builder.setPosition(0.95f).setPositionAnchor(Cue.ANCHOR_TYPE_END)
                        .setLine(0.05f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                        .setTextAlignment(Layout.Alignment.ALIGN_OPPOSITE);
                break;
            case 4:
                builder.setPosition(0.05f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                        .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                        .setTextAlignment(Layout.Alignment.ALIGN_NORMAL);
                break;
            case 5:
                builder.setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                        .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                        .setTextAlignment(Layout.Alignment.ALIGN_CENTER);
                break;
            case 6:
                builder.setPosition(0.95f).setPositionAnchor(Cue.ANCHOR_TYPE_END)
                        .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                        .setTextAlignment(Layout.Alignment.ALIGN_OPPOSITE);
                break;
            case 1:
                builder.setPosition(0.05f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                        .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.ANCHOR_TYPE_END)
                        .setTextAlignment(Layout.Alignment.ALIGN_NORMAL);
                break;
            case 3:
                builder.setPosition(0.95f).setPositionAnchor(Cue.ANCHOR_TYPE_END)
                        .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.ANCHOR_TYPE_END)
                        .setTextAlignment(Layout.Alignment.ALIGN_OPPOSITE);
                break;
            case 2:
            default:
                builder.setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                        .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.ANCHOR_TYPE_END)
                        .setTextAlignment(Layout.Alignment.ALIGN_CENTER);
                break;
        }
    }

    public static int convertLegacyAlignment(int a) {
        return AssParser.convertLegacyAlignment(a);
    }

    @Nullable
    public static Integer parseAssColor(String rawHex) {
        return AssParser.parseAssColor(rawHex);
    }

    public static boolean isAssDrawingPath(String str) {
        if (str == null || str.trim().isEmpty()) return false;
        String trimmed = str.trim();

        if (P_TAG_OPEN_PATTERN.matcher(trimmed).find() || P_TAG_BARE_PATTERN.matcher(trimmed).find()) {
            return true;
        }

        String cleanText = ALL_TAGS_PATTERN.matcher(trimmed).replaceAll("").trim();
        if (cleanText.isEmpty()) return false;

        if (DRAWING_LINE_START_PATTERN.matcher(cleanText).matches()) {
            String[] tokens = WHITESPACE_SPLIT_PATTERN.split(cleanText);
            if (tokens.length >= 3) {
                int drawingTokens = 0;
                for (String token : tokens) {
                    if (DRAWING_CMD_TOKEN_PATTERN.matcher(token).matches() || isNumeric(token)) {
                        drawingTokens++;
                    }
                }
                if ((float) drawingTokens / tokens.length >= 0.70f) {
                    return true;
                }
            }
        }

        if (DRAWING_ONLY_CHARS_PATTERN.matcher(cleanText).matches()) {
            String[] tokens = DRAWING_TOKENS_SPLIT_PATTERN.split(cleanText);
            if (tokens.length >= 3) {
                int numCount = 0;
                for (String t : tokens) {
                    if (isNumeric(t)) numCount++;
                }
                if ((float) numCount / tokens.length >= 0.45f) {
                    return true;
                }
            }
        }

        return false;
    }

    public static float getScriptResX(float posX, float posY, String raw, int cachedPlayResX, @Nullable VideoSize videoSize) {
        if (cachedPlayResX > 0) {
            return (float) cachedPlayResX;
        }
        if (videoSize != null && videoSize.width > 0) {
            int vw = videoSize.width;
            if (posX > 1280 || posY > 720) {
                return 1920.0f;
            }
            if (vw >= 1920) {
                if (posX <= 1280 && posY <= 720 && (posX > 848 || posY > 480)) {
                    return 1280.0f;
                }
                return 1920.0f;
            }
            if (vw == 1280) {
                return 1280.0f;
            }
            if (vw > 0) {
                return (float) vw;
            }
        }
        if (posX > 1280 || posY > 720 || (raw != null && (raw.contains("1920") || raw.contains("1080")))) {
            return 1920.0f;
        }
        if (posX > 848 || posY > 480) {
            return 1280.0f;
        }
        if (posX > 640) {
            return 848.0f;
        }
        return 1280.0f;
    }

    public static float getScriptResY(float posX, float posY, String raw, int cachedPlayResY, @Nullable VideoSize videoSize) {
        if (cachedPlayResY > 0) {
            return (float) cachedPlayResY;
        }
        if (videoSize != null && videoSize.height > 0) {
            int vh = videoSize.height;
            if (posX > 1280 || posY > 720) {
                return 1080.0f;
            }
            if (vh >= 1080) {
                if (posX <= 1280 && posY <= 720 && (posX > 848 || posY > 480)) {
                    return 720.0f;
                }
                return 1080.0f;
            }
            if (vh == 720) {
                return 720.0f;
            }
            if (vh > 0) {
                return (float) vh;
            }
        }
        if (posX > 1280 || posY > 720 || (raw != null && (raw.contains("1920") || raw.contains("1080")))) {
            return 1080.0f;
        }
        if (posX > 848 || posY > 480) {
            return 720.0f;
        }
        if (posX > 640 || posY > 288) {
            return 480.0f;
        }
        return 720.0f;
    }

    @Nullable
    public static Cue processAssCue(Cue cue, @Nullable Context ctx, int subtitleTextColor, float subtitleTextSize,
                                    int cachedPlayResX, int cachedPlayResY, @Nullable VideoSize videoSize) {
        if (cue == null || cue.text == null) return null;
        CharSequence text = cue.text;
        if (text.length() == 0) return null;

        String raw = text.toString();
        if (raw.trim().isEmpty()) return null;

        Integer baseStyleColor = null;
        if (cue.text instanceof Spanned) {
            Spanned spanned = (Spanned) cue.text;
            ForegroundColorSpan[] colorSpans = spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class);
            if (colorSpans != null && colorSpans.length > 0) {
                baseStyleColor = colorSpans[0].getForegroundColor();
            }
        }

        if (isAssDrawingPath(raw)) {
            float posX = -1.0f;
            float posY = -1.0f;
            Matcher posMatcher = POS_OR_MOVE_PATTERN.matcher(raw);
            if (posMatcher.find()) {
                try {
                    posX = Float.parseFloat(posMatcher.group(1));
                    posY = Float.parseFloat(posMatcher.group(2));
                } catch (Exception ignored) {}
            }

            int anVal = 7;
            Matcher anMatcher = AN_TAG_PATTERN.matcher(raw);
            if (anMatcher.find()) {
                try {
                    anVal = Integer.parseInt(anMatcher.group(1));
                } catch (Exception ignored) {}
            } else {
                Matcher legacyAMatcher = LEGACY_A_TAG_PATTERN.matcher(raw);
                if (legacyAMatcher.find()) {
                    try {
                        anVal = convertLegacyAlignment(Integer.parseInt(legacyAMatcher.group(1)));
                    } catch (Exception ignored) {}
                }
            }

            int fillColor = baseStyleColor != null ? baseStyleColor : Color.WHITE;
            Matcher colorMatcher = COLOR_1C_PATTERN.matcher(raw);
            if (colorMatcher.find()) {
                Integer c = parseAssColor(colorMatcher.group(1));
                if (c != null) fillColor = c;
            }

            int strokeColor = Color.BLACK;
            Matcher outlineColorMatcher = COLOR_3C_PATTERN.matcher(raw);
            if (outlineColorMatcher.find()) {
                Integer c = parseAssColor(outlineColorMatcher.group(1));
                if (c != null) strokeColor = c;
            }

            Matcher alphaAllMatcher = ALPHA_ALL_PATTERN.matcher(raw);
            if (alphaAllMatcher.find()) {
                Integer a = AssParser.parseAssAlpha(alphaAllMatcher.group(1));
                if (a != null) {
                    fillColor = AssScript.replaceAlphaKeepRgb(fillColor, a);
                    strokeColor = AssScript.replaceAlphaKeepRgb(strokeColor, a);
                }
            }
            Matcher alpha1Matcher = ALPHA_1A_PATTERN.matcher(raw);
            if (alpha1Matcher.find()) {
                Integer a = AssParser.parseAssAlpha(alpha1Matcher.group(1));
                if (a != null) fillColor = AssScript.replaceAlphaKeepRgb(fillColor, a);
            }
            Matcher alpha3Matcher = ALPHA_3A_PATTERN.matcher(raw);
            if (alpha3Matcher.find()) {
                Integer a = AssParser.parseAssAlpha(alpha3Matcher.group(1));
                if (a != null) strokeColor = AssScript.replaceAlphaKeepRgb(strokeColor, a);
            }

            float strokeWidth = 1.5f;
            Matcher bordMatcher = BORD_PATTERN.matcher(raw);
            if (bordMatcher.find()) {
                try {
                    strokeWidth = Float.parseFloat(bordMatcher.group(1));
                } catch (Exception ignored) {}
            }

            float pScale = 1.0f;
            Matcher pMatcher = P_LEVEL_PATTERN.matcher(raw);
            if (pMatcher.find()) {
                int pLevel = Integer.parseInt(pMatcher.group(1));
                pScale = 1.0f / (float) (1 << (pLevel - 1));
            }

            float pbo = 0.0f;
            Matcher pboMatcher = PBO_PATTERN.matcher(raw);
            if (pboMatcher.find()) {
                try {
                    pbo = Float.parseFloat(pboMatcher.group(1));
                } catch (Exception ignored) {}
            }

            float fscx = 1.0f;
            Matcher fscxMatcher = FSCX_PATTERN.matcher(raw);
            if (fscxMatcher.find()) {
                try {
                    fscx = Math.max(0f, Float.parseFloat(fscxMatcher.group(1)) / 100.0f);
                } catch (Exception ignored) {}
            }

            float fscy = 1.0f;
            Matcher fscyMatcher = FSCY_PATTERN.matcher(raw);
            if (fscyMatcher.find()) {
                try {
                    fscy = Math.max(0f, Float.parseFloat(fscyMatcher.group(1)) / 100.0f);
                } catch (Exception ignored) {}
            }

            String drawingCommands;
            Matcher pBlockMatcher = P_BLOCK_PATTERN.matcher(raw);
            if (pBlockMatcher.find()) {
                drawingCommands = ALL_TAGS_PATTERN.matcher(pBlockMatcher.group(1)).replaceAll("").trim();
            } else {
                drawingCommands = ALL_TAGS_PATTERN.matcher(raw).replaceAll("").trim();
            }

            float playResX = getScriptResX(posX, posY, raw, cachedPlayResX, videoSize);
            float playResY = getScriptResY(posX, posY, raw, cachedPlayResY, videoSize);

            AssDrawingResult drawingResult = parseAssPath(drawingCommands, pScale);
            if (drawingResult != null) {
                Cue vectorCue = renderAssVectorCue(
                        drawingResult.path, drawingResult.bounds,
                        fillColor, strokeColor, strokeWidth,
                        posX, posY, anVal, pbo, pScale, fscx, fscy, playResX, playResY, cue
                );
                if (vectorCue != null) {
                    return vectorCue;
                }
            }
            return null;
        }

        if (!raw.contains("{") && !raw.contains("\\N") && !raw.contains("\\n") && !raw.contains("\\h")) {
            return cue;
        }

        Cue.Builder builder = cue.buildUpon();
        String rawCleaned = P_STRIP_PATTERN.matcher(raw).replaceAll("");

        if (ALL_TAGS_PATTERN.matcher(rawCleaned).replaceAll("").trim().isEmpty()) {
            return null;
        }

        SpannableStringBuilder cleanSsb = new SpannableStringBuilder();
        AssStyleState styleState = new AssStyleState();
        int anVal = -1;
        int index = 0;
        int len = rawCleaned.length();

        while (index < len) {
            int tagOpen = rawCleaned.indexOf('{', index);
            if (tagOpen == -1) {
                String textSegment = rawCleaned.substring(index);
                appendCleanSegment(cleanSsb, textSegment, styleState, baseStyleColor, ctx, subtitleTextColor, subtitleTextSize);
                break;
            }

            if (tagOpen > index) {
                String textSegment = rawCleaned.substring(index, tagOpen);
                appendCleanSegment(cleanSsb, textSegment, styleState, baseStyleColor, ctx, subtitleTextColor, subtitleTextSize);
            }

            int tagClose = rawCleaned.indexOf('}', tagOpen);
            if (tagClose == -1) {
                String textSegment = rawCleaned.substring(tagOpen);
                appendCleanSegment(cleanSsb, textSegment, styleState, baseStyleColor, ctx, subtitleTextColor, subtitleTextSize);
                break;
            }

            String tagBlock = rawCleaned.substring(tagOpen + 1, tagClose);
            index = tagClose + 1;

            // Process override tags strictly left-to-right so \r followed by \c works properly
            List<String> tags = AssParser.splitOverrideTags(tagBlock);
            for (int t = 0; t < tags.size(); t++) {
                String tag = tags.get(t);
                String tl = tag.toLowerCase(Locale.ROOT);
                if (tl.startsWith("an") && tag.length() == 3 && Character.isDigit(tag.charAt(2))) {
                    anVal = tag.charAt(2) - '0';
                    applyAnAlignment(builder, anVal);
                } else if (tl.startsWith("pos(") || tl.startsWith("move(")) {
                    Matcher pm = POS_OR_MOVE_PATTERN.matcher("\\" + tag);
                    if (pm.find()) {
                        try {
                            float px = Float.parseFloat(pm.group(1));
                            float py = Float.parseFloat(pm.group(2));
                            float playResX = getScriptResX(px, py, rawCleaned, cachedPlayResX, videoSize);
                            float playResY = getScriptResY(px, py, rawCleaned, cachedPlayResY, videoSize);

                            float normX = Math.max(0.0f, Math.min(1.0f, px / playResX));
                            float normY = Math.max(0.0f, Math.min(1.0f, py / playResY));

                            int targetAn = anVal > 0 ? anVal : 2;
                            int xAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                            int yAnchor = Cue.ANCHOR_TYPE_END;
                            if (targetAn == 1 || targetAn == 4 || targetAn == 7) xAnchor = Cue.ANCHOR_TYPE_START;
                            else if (targetAn == 3 || targetAn == 6 || targetAn == 9) xAnchor = Cue.ANCHOR_TYPE_END;

                            if (targetAn >= 7) yAnchor = Cue.ANCHOR_TYPE_START;
                            else if (targetAn >= 4) yAnchor = Cue.ANCHOR_TYPE_MIDDLE;

                            builder.setPosition(normX).setPositionAnchor(xAnchor)
                                    .setLine(normY, Cue.LINE_TYPE_FRACTION).setLineAnchor(yAnchor);
                        } catch (Exception ignored) {}
                    }
                } else if (tl.startsWith("alpha")) {
                    Integer a = AssParser.parseAssAlpha(tag.substring(5));
                    if (a != null) styleState.alpha1 = a;
                } else if (tl.startsWith("1a")) {
                    Integer a = AssParser.parseAssAlpha(tag.substring(2));
                    if (a != null) styleState.alpha1 = a;
                } else if (tl.startsWith("1c")) {
                    Integer c = parseAssColor(tag.substring(2));
                    if (c != null) styleState.color = c;
                } else if (tl.startsWith("3c")) {
                    Integer c = parseAssColor(tag.substring(2));
                    if (c != null) styleState.outlineColor = c;
                } else if (tl.startsWith("c") && (tag.length() == 1 || tag.charAt(1) == '&' || tag.charAt(1) == 'H' || tag.charAt(1) == 'h' || tag.charAt(1) == '#')) {
                    if (tag.length() == 1) {
                        styleState.color = null;
                    } else {
                        Integer c = parseAssColor(tag.substring(1));
                        if (c != null) styleState.color = c;
                    }
                } else if (tl.startsWith("fn")) {
                    String fn = tag.substring(2).trim();
                    styleState.font = fn.isEmpty() ? null : fn;
                } else if (tl.startsWith("fs") && !tl.startsWith("fsc") && !tl.startsWith("fsp")) {
                    try {
                        styleState.fontSize = Math.round(Float.parseFloat(tag.substring(2).trim()));
                    } catch (Exception ignored) {}
                } else if (tl.startsWith("b") && !tl.startsWith("bord") && !tl.startsWith("blur") && !tl.startsWith("be")) {
                    String val = tag.substring(1).trim();
                    if (val.isEmpty()) styleState.bold = null;
                    else styleState.bold = "1".equals(val) || (val.length() == 3 && !val.equals("000") && Integer.parseInt(val) >= 600);
                } else if (tl.startsWith("i") && !tl.startsWith("iclip")) {
                    String val = tag.substring(1).trim();
                    styleState.italic = val.isEmpty() ? null : "1".equals(val);
                } else if (tl.startsWith("u")) {
                    String val = tag.substring(1).trim();
                    styleState.underline = val.isEmpty() ? null : "1".equals(val);
                } else if (tl.startsWith("s") && !tl.startsWith("shad")) {
                    String val = tag.substring(1).trim();
                    styleState.strikethrough = val.isEmpty() ? null : "1".equals(val);
                } else if (tl.startsWith("a") && tag.length() >= 2 && Character.isDigit(tag.charAt(1))) {
                    try {
                        anVal = convertLegacyAlignment(Integer.parseInt(tag.substring(1).trim()));
                        applyAnAlignment(builder, anVal);
                    } catch (Exception ignored) {}
                } else if (tl.startsWith("r")) {
                    styleState.reset();
                }
            }
        }

        if (cleanSsb.toString().trim().isEmpty()) {
            return null;
        }

        return builder.setText(cleanSsb).build();
    }

    private static void appendCleanSegment(SpannableStringBuilder ssb, String segment, AssStyleState style,
                                           Integer defaultColor, @Nullable Context ctx,
                                           int subtitleTextColor, float subtitleTextSize) {
        if (segment == null || segment.isEmpty()) return;

        String converted = segment.replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", "\u00A0");

        int start = ssb.length();
        ssb.append(converted);
        int end = ssb.length();

        if (end > start) {
            int targetColor = style.color != null ? style.color : (defaultColor != null ? defaultColor : subtitleTextColor);
            if (style.alpha1 != null) {
                targetColor = AssScript.replaceAlphaKeepRgb(targetColor, style.alpha1);
            }
            ssb.setSpan(new ForegroundColorSpan(targetColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            if (style.font != null && ctx != null) {
                Typeface tf = FontResolver.resolveTypeface(ctx, style.font,
                        style.bold != null && style.bold,
                        style.italic != null && style.italic);
                ssb.setSpan(new CustomTypefaceSpan(tf), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (style.fontSize != null && style.fontSize > 0) {
                float scaledSp = (style.fontSize / 34.0f) * subtitleTextSize;
                scaledSp = Math.max(11f, Math.min(32f, scaledSp));
                ssb.setSpan(new AbsoluteSizeSpan(Math.round(scaledSp), true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (style.bold != null || style.italic != null) {
                boolean b = (style.bold != null && style.bold);
                boolean it = (style.italic != null && style.italic);
                if (b && it) {
                    ssb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (b) {
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (it) {
                    ssb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (style.underline != null && style.underline) {
                ssb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (style.strikethrough != null && style.strikethrough) {
                ssb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /**
     * Prevents simultaneous fallback cues from overlapping on Media3's SubtitleView,
     * handling both unset lines and colliding fractional lines (e.g. Media3 SsaParser's 0.95f/0.05f/0.5f).
     */
    public static List<Cue> resolveCueCollisions(List<Cue> cues) {
        if (cues == null || cues.isEmpty()) return Collections.emptyList();
        if (cues.size() == 1) return cues;

        List<Cue> validCues = new ArrayList<>();
        for (Cue cue : cues) {
            if (cue == null) continue;
            if (cue.text != null) {
                String str = cue.text.toString().trim();
                if (str.isEmpty() || isAssDrawingPath(str)) {
                    continue;
                }
            }
            validCues.add(cue);
        }

        if (validCues.isEmpty()) return Collections.emptyList();
        if (validCues.size() == 1) return validCues;

        // Deduplicate ONLY cues with identical text AND virtually identical position/line
        List<Cue> uniqueCues = new ArrayList<>();
        for (Cue cue : validCues) {
            boolean isDuplicate = false;
            if (cue.text != null && cue.bitmap == null) {
                String str1 = cue.text.toString().trim();
                for (Cue existing : uniqueCues) {
                    if (existing != null && existing.text != null && existing.bitmap == null) {
                        if (str1.equals(existing.text.toString().trim()) && isSamePosition(cue, existing)) {
                            isDuplicate = true;
                            break;
                        }
                    }
                }
            }
            if (!isDuplicate) {
                uniqueCues.add(cue);
            }
        }

        if (uniqueCues.size() <= 1) return uniqueCues;

        // Separate bitmap cues (never shift) from text cues
        List<Cue> result = new ArrayList<>();
        List<Cue> unpositionedBottomCues = new ArrayList<>();
        List<Cue> positionedTextCues = new ArrayList<>();

        for (Cue cue : uniqueCues) {
            if (cue.bitmap != null) {
                result.add(cue);
            } else if (cue.line == Cue.DIMEN_UNSET || cue.lineType == Cue.TYPE_UNSET) {
                unpositionedBottomCues.add(cue);
            } else {
                positionedTextCues.add(cue);
            }
        }

        // Merge unpositioned bottom cues vertically in one clean block
        if (unpositionedBottomCues.size() == 1) {
            result.add(unpositionedBottomCues.get(0));
        } else if (unpositionedBottomCues.size() > 1) {
            SpannableStringBuilder mergedText = new SpannableStringBuilder();
            for (int i = 0; i < unpositionedBottomCues.size(); i++) {
                Cue c = unpositionedBottomCues.get(i);
                if (c.text != null && c.text.length() > 0) {
                    if (mergedText.length() > 0) {
                        mergedText.append("\n");
                    }
                    mergedText.append(c.text);
                }
            }
            Cue mergedBottomCue = unpositionedBottomCues.get(0).buildUpon()
                    .setText(mergedText)
                    .setPosition(0.5f)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                    .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
                    .setLineAnchor(Cue.ANCHOR_TYPE_END)
                    .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build();
            result.add(mergedBottomCue);
        }

        // Resolve colliding fractional-line text cues (e.g., multiple cues at line=0.95f or line=0.05f)
        List<Float> usedFractionalLines = new ArrayList<>();
        List<Float> usedPositions = new ArrayList<>();
        for (int i = 0; i < positionedTextCues.size(); i++) {
            Cue cue = positionedTextCues.get(i);
            if (cue.lineType != Cue.LINE_TYPE_FRACTION || cue.line == Cue.DIMEN_UNSET) {
                result.add(cue);
                continue;
            }

            float line = cue.line;
            float pos = cue.position != Cue.DIMEN_UNSET ? cue.position : 0.5f;
            int lineCount = countLines(cue.text);
            float step = Math.max(0.075f, 0.06f * lineCount);

            boolean pushDown = (cue.lineAnchor == Cue.ANCHOR_TYPE_START) || (line < 0.35f);
            for (int attempt = 0; attempt < 10; attempt++) {
                boolean collides = false;
                for (int j = 0; j < usedFractionalLines.size(); j++) {
                    float otherLine = usedFractionalLines.get(j);
                    float otherPos = usedPositions.get(j);
                    if (Math.abs(line - otherLine) < step && Math.abs(pos - otherPos) < 0.35f) {
                        collides = true;
                        break;
                    }
                }
                if (!collides) break;
                line = pushDown ? Math.min(0.95f, line + step) : Math.max(0.05f, line - step);
            }

            usedFractionalLines.add(line);
            usedPositions.add(pos);
            if (Math.abs(line - cue.line) > 0.0001f) {
                result.add(cue.buildUpon().setLine(line, Cue.LINE_TYPE_FRACTION).build());
            } else {
                result.add(cue);
            }
        }

        return result;
    }

    private static boolean isSamePosition(Cue a, Cue b) {
        if (a.lineType != b.lineType) return false;
        boolean sameLine = (a.line == Cue.DIMEN_UNSET && b.line == Cue.DIMEN_UNSET)
                || Math.abs(a.line - b.line) < 0.01f;
        boolean samePos = (a.position == Cue.DIMEN_UNSET && b.position == Cue.DIMEN_UNSET)
                || Math.abs(a.position - b.position) < 0.01f;
        return sameLine && samePos;
    }

    private static int countLines(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return 1;
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }
}
