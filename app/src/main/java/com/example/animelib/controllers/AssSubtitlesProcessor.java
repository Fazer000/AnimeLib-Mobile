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

import com.example.animelib.util.CustomTypefaceSpan;
import com.example.animelib.util.FontResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Процессор ASS/SSA-субтитров: парсинг тегов форматирования, перенос векторной графики (\p1..\p9),
 * эвристика PlayRes и разрешение коллизий одновременных реплик.
 */
public final class AssSubtitlesProcessor {

    private static final Pattern PATH_TOKEN_PATTERN = Pattern.compile("([a-zA-Z])|(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern P_TAG_OPEN_PATTERN = Pattern.compile("(?i)\\{\\\\p[1-9][^\\}]*\\}");
    private static final Pattern P_TAG_BARE_PATTERN = Pattern.compile("(?i)\\\\p[1-9]\\b");
    private static final Pattern ALL_TAGS_PATTERN = Pattern.compile("\\{([^\\}]+)\\}");
    private static final Pattern DRAWING_LINE_START_PATTERN = Pattern.compile("(?i)^[mn]\\s+-?\\d+.*");
    private static final Pattern WHITESPACE_SPLIT_PATTERN = Pattern.compile("\\s+");
    private static final Pattern DRAWING_CMD_TOKEN_PATTERN = Pattern.compile("(?i)^[mlbspcn]$");
    private static final Pattern DRAWING_ONLY_CHARS_PATTERN = Pattern.compile("(?i)^[mlbspcn0-9\\.\\-\\s,]+$");
    private static final Pattern DRAWING_TOKENS_SPLIT_PATTERN = Pattern.compile("[\\s,]+");

    private static final Pattern POS_VECTOR_PATTERN = Pattern.compile("(?i)\\\\pos\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)");
    private static final Pattern POS_OR_MOVE_PATTERN = Pattern.compile("(?i)\\\\(?:pos|move)\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)[^\\)]*\\)");
    private static final Pattern AN_TAG_PATTERN = Pattern.compile("(?i)\\\\an([1-9])");
    private static final Pattern LEGACY_A_TAG_PATTERN = Pattern.compile("(?i)\\\\a([1-9]|10|11)");
    private static final Pattern COLOR_1C_PATTERN = Pattern.compile("(?i)\\\\(?:1c|c)[&H#]*([0-9a-fA-F]{1,8})&?");
    private static final Pattern COLOR_3C_PATTERN = Pattern.compile("(?i)\\\\3c[&H#]*([0-9a-fA-F]{1,8})&?");
    private static final Pattern BORD_PATTERN = Pattern.compile("(?i)\\\\bord(\\d+(?:\\.\\d+)?)");
    private static final Pattern P_LEVEL_PATTERN = Pattern.compile("(?i)\\\\p([1-9])");
    private static final Pattern P_BLOCK_PATTERN = Pattern.compile("(?i)\\{\\\\p[1-9]\\}(.*?)(?:\\{\\\\p0\\}|$)", Pattern.DOTALL);
    private static final Pattern P_STRIP_PATTERN = Pattern.compile("(?i)\\{\\\\p[1-9]\\}[^\\{]*(\\{\\\\p0\\})?");

    private static final Pattern FONT_NAME_PATTERN = Pattern.compile("(?i)\\\\fn([^\\\\}]+)");
    private static final Pattern FONT_SIZE_PATTERN = Pattern.compile("(?i)\\\\fs(\\d+)");
    private static final Pattern BOLD_PATTERN = Pattern.compile("(?i)\\\\b([01]|\\d{3})");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?i)\\\\i([01])");
    private static final Pattern UNDERLINE_PATTERN = Pattern.compile("(?i)\\\\u([01])");
    private static final Pattern STRIKE_PATTERN = Pattern.compile("(?i)\\\\s([01])");
    private static final Pattern RESET_TAG_PATTERN = Pattern.compile("(?i).*\\\\r.*");

    private static final Pattern CLEAN_COLOR_PREFIX_PATTERN = Pattern.compile("(?i)[&H#]");
    private static final Pattern INTEGER_ONLY_PATTERN = Pattern.compile("^-?\\d+$");

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
        Integer outlineColor = null;
        String font = null;
        Integer fontSize = null;
        Boolean bold = null;
        Boolean italic = null;
        Boolean underline = null;
        Boolean strikethrough = null;

        void reset() {
            color = null;
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
        if (drawingCommands == null || drawingCommands.trim().isEmpty()) return null;
        try {
            Matcher matcher = PATH_TOKEN_PATTERN.matcher(drawingCommands);
            List<String> tokens = new ArrayList<>();
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
            if (tokens.isEmpty()) return null;

            Path path = new Path();
            path.setFillType(Path.FillType.EVEN_ODD);
            char currentCmd = 'm';
            int i = 0;
            boolean hasPoints = false;

            while (i < tokens.size()) {
                String token = tokens.get(i);
                char firstChar = Character.toLowerCase(token.charAt(0));

                if (Character.isLetter(firstChar)) {
                    currentCmd = firstChar;
                    i++;
                    if (currentCmd == 'c') {
                        path.close();
                        continue;
                    }
                    if (i >= tokens.size()) break;
                }

                if (currentCmd == 'm' || currentCmd == 'n') {
                    if (i + 1 < tokens.size() && isNumeric(tokens.get(i)) && isNumeric(tokens.get(i + 1))) {
                        float x = Float.parseFloat(tokens.get(i)) * scale;
                        float y = Float.parseFloat(tokens.get(i + 1)) * scale;
                        path.moveTo(x, y);
                        hasPoints = true;
                        i += 2;
                        currentCmd = 'l';
                    } else {
                        i++;
                    }
                } else if (currentCmd == 'l' || currentCmd == 's' || currentCmd == 'p') {
                    if (i + 1 < tokens.size() && isNumeric(tokens.get(i)) && isNumeric(tokens.get(i + 1))) {
                        float x = Float.parseFloat(tokens.get(i)) * scale;
                        float y = Float.parseFloat(tokens.get(i + 1)) * scale;
                        path.lineTo(x, y);
                        hasPoints = true;
                        i += 2;
                    } else {
                        i++;
                    }
                } else if (currentCmd == 'b') {
                    if (i + 5 < tokens.size() && isNumeric(tokens.get(i)) && isNumeric(tokens.get(i + 1))
                            && isNumeric(tokens.get(i + 2)) && isNumeric(tokens.get(i + 3))
                            && isNumeric(tokens.get(i + 4)) && isNumeric(tokens.get(i + 5))) {
                        float x1 = Float.parseFloat(tokens.get(i)) * scale;
                        float y1 = Float.parseFloat(tokens.get(i + 1)) * scale;
                        float x2 = Float.parseFloat(tokens.get(i + 2)) * scale;
                        float y2 = Float.parseFloat(tokens.get(i + 3)) * scale;
                        float x3 = Float.parseFloat(tokens.get(i + 4)) * scale;
                        float y3 = Float.parseFloat(tokens.get(i + 5)) * scale;
                        path.cubicTo(x1, y1, x2, y2, x3, y3);
                        hasPoints = true;
                        i += 6;
                    } else {
                        i++;
                    }
                } else {
                    i++;
                }
            }

            if (!hasPoints) return null;

            RectF bounds = new RectF();
            path.computeBounds(bounds, true);
            if (bounds.width() <= 0 || bounds.height() <= 0) return null;

            return new AssDrawingResult(path, bounds);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        char c = str.charAt(0);
        return Character.isDigit(c) || c == '-' || c == '+' || c == '.';
    }

    @Nullable
    private static Cue renderAssVectorCue(Path rawPath, RectF bounds, int fillColor, int strokeColor, float strokeWidth,
                                          float posX, float posY, int anVal, float playResX, float playResY) {
        if (rawPath == null || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return null;
        try {
            float padding = Math.max(4.0f, strokeWidth * 2.0f);
            float boundsW = bounds.width() + padding * 2;
            float boundsH = bounds.height() + padding * 2;

            float renderScale = Math.max(1.0f, Math.min(3.0f, 1080.0f / Math.max(1.0f, playResY)));
            int bmpWidth = Math.max(1, Math.min(2048, Math.round(boundsW * renderScale)));
            int bmpHeight = Math.max(1, Math.min(2048, Math.round(boundsH * renderScale)));

            Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Matrix matrix = new Matrix();
            matrix.postTranslate(-bounds.left + padding, -bounds.top + padding);
            matrix.postScale(renderScale, renderScale);

            Path drawPath = new Path();
            rawPath.transform(matrix, drawPath);
            drawPath.setFillType(Path.FillType.EVEN_ODD);

            if (Color.alpha(fillColor) > 0) {
                Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(fillColor);
                canvas.drawPath(drawPath, fillPaint);
            }

            if (strokeWidth > 0 && Color.alpha(strokeColor) > 0) {
                Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setColor(strokeColor);
                strokePaint.setStrokeWidth(Math.max(1.0f, strokeWidth * renderScale));
                strokePaint.setStrokeJoin(Paint.Join.ROUND);
                strokePaint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawPath(drawPath, strokePaint);
            }

            float scriptX;
            float scriptY;

            if (posX >= 0 && posY >= 0) {
                float anchorOffsetX = 0f;
                float anchorOffsetY = 0f;
                int xAlign = (anVal - 1) % 3 + 1;
                int yAlign = (anVal - 1) / 3 + 1;
                if (xAlign == 2) {
                    anchorOffsetX = boundsW / 2.0f;
                } else if (xAlign == 3) {
                    anchorOffsetX = boundsW;
                }
                if (yAlign == 2) {
                    anchorOffsetY = boundsH / 2.0f;
                } else if (yAlign == 1) {
                    anchorOffsetY = boundsH;
                }
                scriptX = posX + bounds.left - anchorOffsetX;
                scriptY = posY + bounds.top - anchorOffsetY;
            } else {
                scriptX = bounds.left - padding;
                scriptY = bounds.top - padding;

                if (scriptX < 0 || scriptY < 0) {
                    if (anVal == 7 || anVal == 8 || anVal == 9) {
                        scriptX = (anVal == 8) ? ((playResX - boundsW) / 2.0f) : ((anVal == 9) ? (playResX - boundsW - 20) : 20);
                        scriptY = 0.05f * playResY;
                    } else if (anVal == 4 || anVal == 5 || anVal == 6) {
                        scriptX = (anVal == 5) ? ((playResX - boundsW) / 2.0f) : ((anVal == 6) ? (playResX - boundsW - 20) : 20);
                        scriptY = (playResY - boundsH) / 2.0f;
                    } else {
                        scriptX = (anVal == 2) ? ((playResX - boundsW) / 2.0f) : ((anVal == 3) ? (playResX - boundsW - 20) : 20);
                        scriptY = playResY - boundsH - (0.06f * playResY);
                    }
                }
            }

            float normX = Math.max(0.0f, Math.min(1.0f, scriptX / playResX));
            float normY = Math.max(0.0f, Math.min(1.0f, scriptY / playResY));
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
        switch (a) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            case 5: return 7;
            case 6: return 8;
            case 7: return 9;
            case 9: return 4;
            case 10: return 5;
            case 11: return 6;
            default: return a;
        }
    }

    @Nullable
    public static Integer parseAssColor(String rawHex) {
        if (rawHex == null) return null;
        String trimmedRaw = rawHex.trim();
        boolean hasHexPrefix = trimmedRaw.indexOf('&') >= 0
                || trimmedRaw.indexOf('#') >= 0
                || trimmedRaw.indexOf('H') >= 0
                || trimmedRaw.indexOf('h') >= 0;
        String clean = CLEAN_COLOR_PREFIX_PATTERN.matcher(trimmedRaw).replaceAll("").trim();
        if (clean.isEmpty()) return null;

        try {
            if (!hasHexPrefix && INTEGER_ONLY_PATTERN.matcher(clean).matches()) {
                long val = Long.parseLong(clean);
                if (val < 0) val = val & 0xFFFFFFFFL;
                int b = (int) ((val >> 16) & 0xFF);
                int g = (int) ((val >> 8) & 0xFF);
                int r = (int) (val & 0xFF);
                int a = (int) ((val >> 24) & 0xFF);
                int alpha = Math.max(0, Math.min(255, 255 - a));
                return ((alpha & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            }

            StringBuilder sb = new StringBuilder(clean);
            while (sb.length() < 6) {
                sb.insert(0, '0');
            }
            clean = sb.toString();

            if (clean.length() == 6) {
                int b = Integer.parseInt(clean.substring(0, 2), 16);
                int g = Integer.parseInt(clean.substring(2, 4), 16);
                int r = Integer.parseInt(clean.substring(4, 6), 16);
                return (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            } else if (clean.length() >= 8) {
                String hex8 = clean.substring(clean.length() - 8);
                int assAlpha = Integer.parseInt(hex8.substring(0, 2), 16);
                int alpha = Math.max(0, Math.min(255, 255 - assAlpha));
                int b = Integer.parseInt(hex8.substring(2, 4), 16);
                int g = Integer.parseInt(hex8.substring(4, 6), 16);
                int r = Integer.parseInt(hex8.substring(6, 8), 16);
                return ((alpha & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            }
        } catch (Exception ignored) {}
        return null;
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

        if (isAssDrawingPath(raw)) {
            float posX = -1.0f;
            float posY = -1.0f;
            Matcher posMatcher = POS_VECTOR_PATTERN.matcher(raw);
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

            int fillColor = Color.WHITE;
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

            float strokeWidth = 2.0f;
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
                Cue vectorCue = renderAssVectorCue(drawingResult.path, drawingResult.bounds, fillColor, strokeColor, strokeWidth,
                        posX, posY, anVal, playResX, playResY);
                if (vectorCue != null) {
                    return vectorCue;
                }
            }
            return null;
        }

        Integer baseStyleColor = null;
        if (cue.text instanceof Spanned) {
            Spanned spanned = (Spanned) cue.text;
            ForegroundColorSpan[] colorSpans = spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class);
            if (colorSpans != null && colorSpans.length > 0) {
                baseStyleColor = colorSpans[0].getForegroundColor();
            }
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

            Matcher anMatcher = AN_TAG_PATTERN.matcher(tagBlock);
            if (anMatcher.find()) {
                anVal = Integer.parseInt(anMatcher.group(1));
                applyAnAlignment(builder, anVal);
            } else {
                Matcher legacyAMatcher = LEGACY_A_TAG_PATTERN.matcher(tagBlock);
                if (legacyAMatcher.find()) {
                    anVal = convertLegacyAlignment(Integer.parseInt(legacyAMatcher.group(1)));
                    applyAnAlignment(builder, anVal);
                }
            }

            Matcher posMatcher = POS_OR_MOVE_PATTERN.matcher(tagBlock);
            if (posMatcher.find()) {
                try {
                    float px = Float.parseFloat(posMatcher.group(1));
                    float py = Float.parseFloat(posMatcher.group(2));
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

            Matcher colorMatcher = COLOR_1C_PATTERN.matcher(tagBlock);
            if (colorMatcher.find()) {
                styleState.color = parseAssColor(colorMatcher.group(1));
            }

            Matcher outlineColorMatcher = COLOR_3C_PATTERN.matcher(tagBlock);
            if (outlineColorMatcher.find()) {
                styleState.outlineColor = parseAssColor(outlineColorMatcher.group(1));
            }

            Matcher fontMatcher = FONT_NAME_PATTERN.matcher(tagBlock);
            if (fontMatcher.find()) {
                String fn = fontMatcher.group(1).trim();
                styleState.font = fn.isEmpty() ? null : fn;
            }

            Matcher sizeMatcher = FONT_SIZE_PATTERN.matcher(tagBlock);
            if (sizeMatcher.find()) {
                try {
                    styleState.fontSize = Integer.parseInt(sizeMatcher.group(1));
                } catch (Exception ignored) {}
            }

            Matcher boldMatcher = BOLD_PATTERN.matcher(tagBlock);
            if (boldMatcher.find()) {
                String val = boldMatcher.group(1);
                styleState.bold = "1".equals(val) || (val.length() == 3 && !val.equals("000"));
            }

            Matcher italicMatcher = ITALIC_PATTERN.matcher(tagBlock);
            if (italicMatcher.find()) {
                styleState.italic = "1".equals(italicMatcher.group(1));
            }

            Matcher underlineMatcher = UNDERLINE_PATTERN.matcher(tagBlock);
            if (underlineMatcher.find()) {
                styleState.underline = "1".equals(underlineMatcher.group(1));
            }

            Matcher strikeMatcher = STRIKE_PATTERN.matcher(tagBlock);
            if (strikeMatcher.find()) {
                styleState.strikethrough = "1".equals(strikeMatcher.group(1));
            }

            if (RESET_TAG_PATTERN.matcher(tagBlock).matches()) {
                styleState.reset();
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

        List<Cue> uniqueCues = new ArrayList<>();
        for (Cue cue : validCues) {
            boolean isDuplicate = false;
            if (cue.text != null) {
                String str1 = cue.text.toString().trim();
                for (Cue existing : uniqueCues) {
                    if (existing != null && existing.text != null) {
                        String str2 = existing.text.toString().trim();
                        if (str1.equals(str2)) {
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

        List<Cue> unpositionedBottomCues = new ArrayList<>();
        List<Cue> otherCues = new ArrayList<>();

        for (Cue cue : uniqueCues) {
            if (cue.bitmap == null && (cue.line == Cue.DIMEN_UNSET || cue.lineType == Cue.TYPE_UNSET)) {
                unpositionedBottomCues.add(cue);
            } else {
                otherCues.add(cue);
            }
        }

        if (unpositionedBottomCues.size() <= 1) {
            return uniqueCues;
        }

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

        List<Cue> result = new ArrayList<>(otherCues);
        result.add(mergedBottomCue);
        return result;
    }
}
