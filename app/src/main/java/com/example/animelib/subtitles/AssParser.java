package com.example.animelib.subtitles;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-featured ASS/SSA script parser supporting [Script Info], [V4+ Styles], [V4 Styles],
 * [Events], and left-to-right override tag evaluation (colors, alphas, fonts, scales, rotations,
 * shear, borders, shadows, blur, \pos, \move, \org, \fad, \fade, \clip, \iclip, \t, \r, \p1..\p9, \pbo).
 */
public final class AssParser {

    private static final int SECTION_NONE = 0;
    private static final int SECTION_SCRIPT_INFO = 1;
    private static final int SECTION_STYLES_V4_PLUS = 2;
    private static final int SECTION_STYLES_V4 = 3;
    private static final int SECTION_EVENTS = 4;

    private AssParser() {}

    @NonNull
    public static AssScript parse(@Nullable String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) {
            Map<String, AssScript.AssStyle> defaults = new HashMap<>();
            AssScript.AssStyle def = AssScript.AssStyle.createDefault();
            defaults.put(AssScript.normalizeStyleKey(def.name), def);
            return new AssScript(0f, 0f, true, false, 0, defaults, new ArrayList<>());
        }

        // Strip UTF-8 BOM if present
        String content = rawContent;
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }

        float playResX = 0f;
        float playResY = 0f;
        boolean scaledBorderAndShadow = true;
        boolean reverseCollisions = false;
        int defaultWrapStyle = 0;

        Map<String, AssScript.AssStyle> styles = new HashMap<>();
        AssScript.AssStyle fallbackStyle = AssScript.AssStyle.createDefault();
        styles.put(AssScript.normalizeStyleKey(fallbackStyle.name), fallbackStyle);

        List<AssScript.AssEvent> events = new ArrayList<>();

        Map<String, Integer> styleFormatMap = null;
        Map<String, Integer> eventFormatMap = null;
        int eventFormatColumnCount = 0;

        int section = SECTION_NONE;
        int eventIndex = 0;

        int len = content.length();
        int lineStart = 0;
        while (lineStart <= len) {
            int lineEnd = content.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = len;

            int actualEnd = lineEnd;
            if (actualEnd > lineStart && content.charAt(actualEnd - 1) == '\r') {
                actualEnd--;
            }

            String line = content.substring(lineStart, actualEnd).trim();
            lineStart = lineEnd + 1;

            if (line.isEmpty() || line.startsWith(";") || line.startsWith("!:")) {
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                String header = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                if ("script info".equals(header)) {
                    section = SECTION_SCRIPT_INFO;
                } else if ("v4+ styles".equals(header)) {
                    section = SECTION_STYLES_V4_PLUS;
                    styleFormatMap = null;
                } else if ("v4 styles".equals(header)) {
                    section = SECTION_STYLES_V4;
                    styleFormatMap = null;
                } else if ("events".equals(header)) {
                    section = SECTION_EVENTS;
                    eventFormatMap = null;
                    eventFormatColumnCount = 0;
                } else {
                    section = SECTION_NONE;
                }
                continue;
            }

            if (section == SECTION_SCRIPT_INFO) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = line.substring(colon + 1).trim();
                switch (key) {
                    case "playresx":
                        playResX = parseFloatSafe(val, playResX);
                        break;
                    case "playresy":
                        playResY = parseFloatSafe(val, playResY);
                        break;
                    case "scaledborderandshadow":
                        scaledBorderAndShadow = !("no".equalsIgnoreCase(val) || "0".equals(val) || "false".equalsIgnoreCase(val));
                        break;
                    case "collisions":
                        reverseCollisions = "reverse".equalsIgnoreCase(val);
                        break;
                    case "wrapstyle":
                        defaultWrapStyle = parseIntSafe(val, defaultWrapStyle);
                        break;
                    default:
                        break;
                }
            } else if (section == SECTION_STYLES_V4_PLUS || section == SECTION_STYLES_V4) {
                boolean isLegacyV4 = (section == SECTION_STYLES_V4);
                if (startsWithIgnoreCase(line, "format:")) {
                    styleFormatMap = parseFormatLine(line.substring(7));
                } else if (startsWithIgnoreCase(line, "style:")) {
                    AssScript.AssStyle parsed = parseStyleLine(line.substring(6), styleFormatMap, isLegacyV4);
                    if (parsed != null) {
                        styles.put(AssScript.normalizeStyleKey(parsed.name), parsed);
                    }
                }
            } else if (section == SECTION_EVENTS) {
                if (startsWithIgnoreCase(line, "format:")) {
                    eventFormatMap = parseFormatLine(line.substring(7));
                    eventFormatColumnCount = eventFormatMap.size();
                } else if (startsWithIgnoreCase(line, "dialogue:")) {
                    AssScript.AssEvent ev = parseDialogueLine(
                            line.substring(9),
                            eventFormatMap,
                            eventFormatColumnCount,
                            styles,
                            defaultWrapStyle,
                            eventIndex++
                    );
                    if (ev != null) {
                        events.add(ev);
                    }
                }
            }
        }

        return new AssScript(
                playResX,
                playResY,
                scaledBorderAndShadow,
                reverseCollisions,
                defaultWrapStyle,
                styles,
                events
        );
    }

    private static Map<String, Integer> parseFormatLine(String fieldsRaw) {
        Map<String, Integer> map = new HashMap<>();
        String[] parts = fieldsRaw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String key = parts[i].trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                map.put(key, i);
            }
        }
        return map;
    }

    @Nullable
    private static AssScript.AssStyle parseStyleLine(String rawValues,
                                                     @Nullable Map<String, Integer> fmt,
                                                     boolean isLegacyV4) {
        String[] cols = rawValues.split(",", -1);
        if (cols.length < 3) return null;

        String name = getCol(cols, fmt, "name", 0, "Default").trim();
        String fontName = getCol(cols, fmt, "fontname", 1, "Arial").trim();
        float fontSize = parseFloatSafe(getCol(cols, fmt, "fontsize", 2, "20"), 20f);

        int primaryColor = parseAssStyleColor(getCol(cols, fmt, "primarycolour", 3, "&H00FFFFFF"), 0xFFFFFFFF);
        int secondaryColor = parseAssStyleColor(getCol(cols, fmt, "secondarycolour", 4, "&H0000FFFF"), 0xFFFFFF00);

        String outlineKey = (fmt != null && fmt.containsKey("outlinecolour")) ? "outlinecolour" : "tertiarycolour";
        int outlineColor = parseAssStyleColor(getCol(cols, fmt, outlineKey, 5, "&H00000000"), 0xFF000000);
        int backColor = parseAssStyleColor(getCol(cols, fmt, "backcolour", 6, "&H80000000"), 0x80000000);

        boolean bold = parseAssBoolOrWeight(getCol(cols, fmt, "bold", 7, "0"));
        boolean italic = parseAssBool(getCol(cols, fmt, "italic", 8, "0"));

        boolean underline;
        boolean strikeOut;
        float scaleX;
        float scaleY;
        float spacing;
        float angle;
        int borderStyle;
        float outline;
        float shadow;
        int alignment;
        int marginL;
        int marginR;
        int marginV;

        if (isLegacyV4 && fmt == null) {
            underline = false;
            strikeOut = false;
            scaleX = 100f;
            scaleY = 100f;
            spacing = 0f;
            angle = 0f;
            borderStyle = parseIntSafe(getCol(cols, null, "borderstyle", 9, "1"), 1);
            outline = parseFloatSafe(getCol(cols, null, "outline", 10, "2"), 2f);
            shadow = parseFloatSafe(getCol(cols, null, "shadow", 11, "2"), 2f);
            alignment = convertLegacyAlignment(parseIntSafe(getCol(cols, null, "alignment", 12, "2"), 2));
            marginL = parseIntSafe(getCol(cols, null, "marginl", 13, "10"), 10);
            marginR = parseIntSafe(getCol(cols, null, "marginr", 14, "10"), 10);
            marginV = parseIntSafe(getCol(cols, null, "marginv", 15, "10"), 10);
        } else {
            underline = parseAssBool(getCol(cols, fmt, "underline", 9, "0"));
            strikeOut = parseAssBool(getCol(cols, fmt, "strikeout", 10, "0"));
            scaleX = parseFloatSafe(getCol(cols, fmt, "scalex", 11, "100"), 100f);
            scaleY = parseFloatSafe(getCol(cols, fmt, "scaley", 12, "100"), 100f);
            spacing = parseFloatSafe(getCol(cols, fmt, "spacing", 13, "0"), 0f);
            angle = parseFloatSafe(getCol(cols, fmt, "angle", 14, "0"), 0f);
            borderStyle = parseIntSafe(getCol(cols, fmt, "borderstyle", 15, "1"), 1);
            outline = parseFloatSafe(getCol(cols, fmt, "outline", 16, "2"), 2f);
            shadow = parseFloatSafe(getCol(cols, fmt, "shadow", 17, "2"), 2f);
            int rawAlign = parseIntSafe(getCol(cols, fmt, "alignment", 18, "2"), 2);
            alignment = isLegacyV4 ? convertLegacyAlignment(rawAlign) : rawAlign;
            marginL = parseIntSafe(getCol(cols, fmt, "marginl", 19, "10"), 10);
            marginR = parseIntSafe(getCol(cols, fmt, "marginr", 20, "10"), 10);
            marginV = parseIntSafe(getCol(cols, fmt, "marginv", 21, "10"), 10);
        }

        return new AssScript.AssStyle(
                name, fontName, fontSize,
                primaryColor, secondaryColor, outlineColor, backColor,
                bold, italic, underline, strikeOut,
                scaleX, scaleY, spacing, angle,
                borderStyle, outline, shadow, alignment,
                marginL, marginR, marginV
        );
    }

    @Nullable
    private static AssScript.AssEvent parseDialogueLine(String rawValues,
                                                        @Nullable Map<String, Integer> fmt,
                                                        int fmtCols,
                                                        @NonNull Map<String, AssScript.AssStyle> styles,
                                                        int defaultWrapStyle,
                                                        int eventIndex) {
        int maxSplit = fmtCols > 0 ? fmtCols : 10;
        String[] cols = rawValues.split(",", maxSplit);
        if (cols.length < 3) return null;

        int layerIdx = getColIndex(fmt, "layer", 0);
        int startIdx = getColIndex(fmt, "start", 1);
        int endIdx = getColIndex(fmt, "end", 2);
        int styleIdx = getColIndex(fmt, "style", 3);
        int marginLIdx = getColIndex(fmt, "marginl", 5);
        int marginRIdx = getColIndex(fmt, "marginr", 6);
        int marginVIdx = getColIndex(fmt, "marginv", 7);
        int textIdx = getColIndex(fmt, "text", maxSplit - 1);

        if (startIdx >= cols.length || endIdx >= cols.length || textIdx >= cols.length) {
            return null;
        }

        long startMs = parseTimestampMs(cols[startIdx]);
        long endMs = parseTimestampMs(cols[endIdx]);
        if (startMs < 0 || endMs <= startMs) {
            return null;
        }

        int layer = (layerIdx >= 0 && layerIdx < cols.length) ? parseIntSafe(cols[layerIdx], 0) : 0;
        String styleName = (styleIdx >= 0 && styleIdx < cols.length) ? cols[styleIdx].trim() : "Default";
        AssScript.AssStyle baseStyle = resolveStyle(styles, styleName);

        int rawMarginL = (marginLIdx >= 0 && marginLIdx < cols.length) ? parseIntSafe(cols[marginLIdx], 0) : 0;
        int rawMarginR = (marginRIdx >= 0 && marginRIdx < cols.length) ? parseIntSafe(cols[marginRIdx], 0) : 0;
        int rawMarginV = (marginVIdx >= 0 && marginVIdx < cols.length) ? parseIntSafe(cols[marginVIdx], 0) : 0;

        int effMarginL = rawMarginL > 0 ? rawMarginL : baseStyle.marginL;
        int effMarginR = rawMarginR > 0 ? rawMarginR : baseStyle.marginR;
        int effMarginV = rawMarginV > 0 ? rawMarginV : baseStyle.marginV;

        String rawText = cols[textIdx];
        if (rawText == null || rawText.isEmpty()) {
            return null;
        }

        return parseDialogueText(
                eventIndex, layer, startMs, endMs, baseStyle,
                effMarginL, effMarginR, effMarginV, defaultWrapStyle,
                rawText, styles
        );
    }

    @Nullable
    public static AssScript.AssEvent parseDialogueText(int eventIndex,
                                                       int layer,
                                                       long startMs,
                                                       long endMs,
                                                       @NonNull AssScript.AssStyle baseStyle,
                                                       int marginL,
                                                       int marginR,
                                                       int marginV,
                                                       int defaultWrapStyle,
                                                       @NonNull String rawText,
                                                       @NonNull Map<String, AssScript.AssStyle> styles) {
        AssScript.SegmentStyle currentStyle = new AssScript.SegmentStyle(baseStyle);
        List<AssScript.AssTransform> activeTransforms = new ArrayList<>();
        List<AssScript.AssSegment> segments = new ArrayList<>();

        EventParseContext ctx = new EventParseContext();
        ctx.alignment = baseStyle.alignment;
        ctx.wrapStyle = defaultWrapStyle;

        int i = 0;
        int len = rawText.length();
        StringBuilder textBuf = new StringBuilder();

        while (i < len) {
            char c = rawText.charAt(i);
            if (c == '{') {
                int close = findClosingBrace(rawText, i);
                if (close > i) {
                    if (textBuf.length() > 0) {
                        flushSegment(segments, textBuf, currentStyle, activeTransforms);
                    }
                    String tagBlock = rawText.substring(i + 1, close);
                    applyOverrideBlock(tagBlock, currentStyle, baseStyle, styles, ctx, activeTransforms);
                    i = close + 1;
                    continue;
                }
            }
            textBuf.append(c);
            i++;
        }

        if (textBuf.length() > 0) {
            flushSegment(segments, textBuf, currentStyle, activeTransforms);
        }

        if (segments.isEmpty()) {
            return null;
        }

        return new AssScript.AssEvent(
                eventIndex,
                layer,
                startMs,
                endMs,
                baseStyle,
                marginL,
                marginR,
                marginV,
                ctx.alignment,
                ctx.hasPos,
                ctx.posX,
                ctx.posY,
                ctx.hasMove,
                ctx.moveX1,
                ctx.moveY1,
                ctx.moveX2,
                ctx.moveY2,
                ctx.moveT1Ms,
                ctx.moveT2Ms,
                ctx.hasOrg,
                ctx.orgX,
                ctx.orgY,
                ctx.fadeInMs,
                ctx.fadeOutMs,
                ctx.hasComplexFade,
                ctx.fadeA1,
                ctx.fadeA2,
                ctx.fadeA3,
                ctx.fadeT1,
                ctx.fadeT2,
                ctx.fadeT3,
                ctx.fadeT4,
                ctx.clip,
                ctx.wrapStyle,
                segments
        );
    }

    private static void flushSegment(List<AssScript.AssSegment> out,
                                     StringBuilder buf,
                                     AssScript.SegmentStyle style,
                                     List<AssScript.AssTransform> activeTransforms) {
        String raw = buf.toString();
        buf.setLength(0);
        if (raw.isEmpty()) return;

        if (style.pLevel > 0) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                out.add(new AssScript.AssSegment(true, trimmed, style.copy(), activeTransforms));
            }
        } else {
            out.add(new AssScript.AssSegment(false, raw, style.copy(), activeTransforms));
        }
    }

    private static int findClosingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                if (depth > 0) depth--;
            } else if (ch == '}' && depth == 0) {
                return i;
            }
        }
        return s.indexOf('}', openIdx + 1);
    }

    private static final class EventParseContext {
        int alignment = 2;
        boolean alignmentSet = false;
        boolean hasPos = false;
        float posX = 0f;
        float posY = 0f;
        boolean hasMove = false;
        float moveX1 = 0f;
        float moveY1 = 0f;
        float moveX2 = 0f;
        float moveY2 = 0f;
        long moveT1Ms = -1L;
        long moveT2Ms = -1L;
        boolean hasOrg = false;
        float orgX = 0f;
        float orgY = 0f;
        long fadeInMs = 0L;
        long fadeOutMs = 0L;
        boolean hasComplexFade = false;
        int fadeA1 = 0;
        int fadeA2 = 0;
        int fadeA3 = 0;
        long fadeT1 = 0;
        long fadeT2 = 0;
        long fadeT3 = 0;
        long fadeT4 = 0;
        @Nullable AssScript.AssClip clip = null;
        int wrapStyle = 0;
    }

    /**
     * Evaluates a single `{...}` override block strictly left-to-right so tags like
     * `{\r\c&H00FF00&\1a&H80&\p1}` compose accurately.
     */
    private static void applyOverrideBlock(String block,
                                           AssScript.SegmentStyle style,
                                           AssScript.AssStyle baseStyle,
                                           Map<String, AssScript.AssStyle> styles,
                                           EventParseContext ctx,
                                           List<AssScript.AssTransform> activeTransforms) {
        if (block == null || block.isEmpty() || block.indexOf('\\') < 0) {
            return;
        }
        List<String> tags = splitOverrideTags(block);
        for (int i = 0; i < tags.size(); i++) {
            applySingleTag(tags.get(i), style, baseStyle, styles, ctx, activeTransforms);
        }
    }

    /**
     * Splits `{...}` content into individual `\`-prefixed tags while keeping parenthesized
     * expressions (such as `\t(0,500,\c&H00FF00&\fscx120)`) intact as a single tag.
     */
    public static List<String> splitOverrideTags(String block) {
        List<String> tags = new ArrayList<>();
        int len = block.length();
        int i = 0;
        while (i < len && block.charAt(i) != '\\') {
            i++;
        }
        while (i < len) {
            int start = i;
            i++; // skip '\'
            int parenDepth = 0;
            while (i < len) {
                char ch = block.charAt(i);
                if (ch == '(') {
                    parenDepth++;
                } else if (ch == ')') {
                    if (parenDepth > 0) parenDepth--;
                } else if (ch == '\\' && parenDepth == 0) {
                    break;
                }
                i++;
            }
            String tag = block.substring(start + 1, i).trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private static void applySingleTag(String tag,
                                       AssScript.SegmentStyle style,
                                       AssScript.AssStyle baseStyle,
                                       Map<String, AssScript.AssStyle> styles,
                                       EventParseContext ctx,
                                       List<AssScript.AssTransform> activeTransforms) {
        if (tag.isEmpty()) return;
        String lower = tag.toLowerCase(Locale.ROOT);

        // --- Parenthesized function tags (check longest prefixes first) ---
        if (lower.startsWith("pos(")) {
            float[] args = parseParenFloats(tag, 3);
            if (args != null && args.length >= 2 && !ctx.hasPos && !ctx.hasMove) {
                ctx.hasPos = true;
                ctx.posX = args[0];
                ctx.posY = args[1];
            }
            return;
        }
        if (lower.startsWith("move(")) {
            float[] args = parseParenFloats(tag, 4);
            if (args != null && args.length >= 4 && !ctx.hasPos && !ctx.hasMove) {
                ctx.hasMove = true;
                ctx.moveX1 = args[0];
                ctx.moveY1 = args[1];
                ctx.moveX2 = args[2];
                ctx.moveY2 = args[3];
                if (args.length >= 6) {
                    ctx.moveT1Ms = Math.round(args[4]);
                    ctx.moveT2Ms = Math.round(args[5]);
                }
            }
            return;
        }
        if (lower.startsWith("org(")) {
            float[] args = parseParenFloats(tag, 3);
            if (args != null && args.length >= 2 && !ctx.hasOrg) {
                ctx.hasOrg = true;
                ctx.orgX = args[0];
                ctx.orgY = args[1];
            }
            return;
        }
        if (lower.startsWith("fade(")) {
            float[] args = parseParenFloats(tag, 4);
            if (args != null && args.length >= 7) {
                ctx.hasComplexFade = true;
                ctx.fadeA1 = Math.round(args[0]);
                ctx.fadeA2 = Math.round(args[1]);
                ctx.fadeA3 = Math.round(args[2]);
                ctx.fadeT1 = Math.round(args[3]);
                ctx.fadeT2 = Math.round(args[4]);
                ctx.fadeT3 = Math.round(args[5]);
                ctx.fadeT4 = Math.round(args[6]);
            }
            return;
        }
        if (lower.startsWith("fad(")) {
            float[] args = parseParenFloats(tag, 3);
            if (args != null && args.length >= 2) {
                ctx.fadeInMs = Math.max(0L, Math.round(args[0]));
                ctx.fadeOutMs = Math.max(0L, Math.round(args[1]));
            }
            return;
        }
        if (lower.startsWith("iclip(") || lower.startsWith("clip(")) {
            boolean inverted = lower.startsWith("iclip(");
            int openIdx = tag.indexOf('(');
            int closeIdx = tag.lastIndexOf(')');
            if (openIdx >= 0 && closeIdx > openIdx) {
                String inner = tag.substring(openIdx + 1, closeIdx).trim();
                AssScript.AssClip parsedClip = parseClipContent(inner, inverted);
                if (parsedClip != null) {
                    ctx.clip = parsedClip;
                }
            }
            return;
        }
        if (lower.startsWith("t(")) {
            AssScript.AssTransform tr = parseTransformTag(tag);
            if (tr != null) {
                activeTransforms.add(tr);
            }
            return;
        }

        // --- Multi-character prefix tags (order longest-first to avoid collisions!) ---
        if (lower.startsWith("alpha")) {
            Integer alpha = parseAssAlpha(tag.substring(5));
            if (alpha != null) {
                style.primaryColor = AssScript.replaceAlphaKeepRgb(style.primaryColor, alpha);
                style.secondaryColor = AssScript.replaceAlphaKeepRgb(style.secondaryColor, alpha);
                style.outlineColor = AssScript.replaceAlphaKeepRgb(style.outlineColor, alpha);
                style.backColor = AssScript.replaceAlphaKeepRgb(style.backColor, alpha);
            } else if (tag.length() == 5) {
                style.primaryColor = AssScript.replaceAlphaKeepRgb(style.primaryColor, (baseStyle.primaryColor >>> 24) & 0xFF);
                style.outlineColor = AssScript.replaceAlphaKeepRgb(style.outlineColor, (baseStyle.outlineColor >>> 24) & 0xFF);
                style.backColor = AssScript.replaceAlphaKeepRgb(style.backColor, (baseStyle.backColor >>> 24) & 0xFF);
            }
            return;
        }
        if (lower.startsWith("xbord")) {
            style.borderX = Math.max(0f, parseFloatOrDefault(tag.substring(5), baseStyle.outline));
            return;
        }
        if (lower.startsWith("ybord")) {
            style.borderY = Math.max(0f, parseFloatOrDefault(tag.substring(5), baseStyle.outline));
            return;
        }
        if (lower.startsWith("xshad")) {
            style.shadowX = parseFloatOrDefault(tag.substring(5), baseStyle.shadow);
            return;
        }
        if (lower.startsWith("yshad")) {
            style.shadowY = parseFloatOrDefault(tag.substring(5), baseStyle.shadow);
            return;
        }
        if (lower.startsWith("bord")) {
            float v = Math.max(0f, parseFloatOrDefault(tag.substring(4), baseStyle.outline));
            style.borderX = v;
            style.borderY = v;
            return;
        }
        if (lower.startsWith("shad")) {
            float v = Math.max(0f, parseFloatOrDefault(tag.substring(4), baseStyle.shadow));
            style.shadowX = v;
            style.shadowY = v;
            return;
        }
        if (lower.startsWith("blur")) {
            style.blur = Math.max(0f, parseFloatOrDefault(tag.substring(4), 0f));
            return;
        }
        if (lower.startsWith("fscx")) {
            style.scaleX = Math.max(0f, parseFloatOrDefault(tag.substring(4), baseStyle.scaleX));
            return;
        }
        if (lower.startsWith("fscy")) {
            style.scaleY = Math.max(0f, parseFloatOrDefault(tag.substring(4), baseStyle.scaleY));
            return;
        }
        if (lower.startsWith("fsp")) {
            style.spacing = parseFloatOrDefault(tag.substring(3), baseStyle.spacing);
            return;
        }
        if (lower.startsWith("frz")) {
            style.frz = parseFloatOrDefault(tag.substring(3), baseStyle.angle);
            return;
        }
        if (lower.startsWith("frx") || lower.startsWith("fry")) {
            // 3D X/Y rotation not natively projected in 2D Canvas; consume cleanly
            return;
        }
        if (lower.startsWith("fax")) {
            style.fax = parseFloatOrDefault(tag.substring(3), 0f);
            return;
        }
        if (lower.startsWith("fay")) {
            style.fay = parseFloatOrDefault(tag.substring(3), 0f);
            return;
        }
        if (lower.startsWith("pbo")) {
            style.pbo = parseFloatOrDefault(tag.substring(3), 0f);
            return;
        }
        if (lower.startsWith("an")) {
            int an = parseLeadingInt(tag.substring(2), -1);
            if (an >= 1 && an <= 9 && !ctx.alignmentSet) {
                ctx.alignment = an;
                ctx.alignmentSet = true;
            }
            return;
        }
        if (lower.startsWith("fn")) {
            String fn = tag.substring(2).trim();
            style.fontName = fn.isEmpty() ? baseStyle.fontName : fn;
            return;
        }
        if (lower.startsWith("fs")) {
            float sz = parseFloatOrDefault(tag.substring(2), baseStyle.fontSize);
            if (sz > 0f) style.fontSize = sz;
            return;
        }
        if (lower.startsWith("fr")) {
            style.frz = parseFloatOrDefault(tag.substring(2), baseStyle.angle);
            return;
        }
        if (lower.startsWith("fe")) {
            // Font encoding tag; ignore cleanly
            return;
        }
        if (lower.startsWith("be")) {
            float edgeBlur = Math.max(0f, parseFloatOrDefault(tag.substring(2), 0f));
            if (edgeBlur > 0f) {
                style.blur = Math.max(style.blur, edgeBlur * 0.65f);
            } else {
                style.blur = 0f;
            }
            return;
        }
        if (lower.startsWith("1c")) {
            applyInlineColor(tag.substring(2), 1, style, baseStyle);
            return;
        }
        if (lower.startsWith("2c")) {
            applyInlineColor(tag.substring(2), 2, style, baseStyle);
            return;
        }
        if (lower.startsWith("3c")) {
            applyInlineColor(tag.substring(2), 3, style, baseStyle);
            return;
        }
        if (lower.startsWith("4c")) {
            applyInlineColor(tag.substring(2), 4, style, baseStyle);
            return;
        }
        if (lower.startsWith("1a")) {
            applyInlineAlpha(tag.substring(2), 1, style, baseStyle);
            return;
        }
        if (lower.startsWith("2a")) {
            applyInlineAlpha(tag.substring(2), 2, style, baseStyle);
            return;
        }
        if (lower.startsWith("3a")) {
            applyInlineAlpha(tag.substring(2), 3, style, baseStyle);
            return;
        }
        if (lower.startsWith("4a")) {
            applyInlineAlpha(tag.substring(2), 4, style, baseStyle);
            return;
        }
        if (lower.startsWith("kf") || lower.startsWith("ko") || lower.startsWith("kt")) {
            return;
        }

        // --- Single-character tags ---
        char ch0 = lower.charAt(0);
        String arg = tag.substring(1).trim();
        switch (ch0) {
            case 'c':
                applyInlineColor(arg, 1, style, baseStyle);
                break;
            case 'b':
                if (arg.isEmpty()) {
                    style.bold = baseStyle.bold;
                } else {
                    int bVal = parseIntSafe(arg, -1);
                    if (bVal == 0) style.bold = false;
                    else if (bVal == 1 || bVal >= 600) style.bold = true;
                    else if (bVal > 1) style.bold = false;
                }
                break;
            case 'i':
                if (arg.isEmpty()) style.italic = baseStyle.italic;
                else style.italic = "1".equals(arg);
                break;
            case 'u':
                if (arg.isEmpty()) style.underline = baseStyle.underline;
                else style.underline = "1".equals(arg);
                break;
            case 's':
                if (arg.isEmpty()) style.strikeOut = baseStyle.strikeOut;
                else style.strikeOut = "1".equals(arg);
                break;
            case 'p': {
                int pVal = parseLeadingInt(arg, -1);
                if (pVal >= 0) {
                    style.pLevel = Math.min(9, pVal);
                }
                break;
            }
            case 'a': {
                if (!ctx.alignmentSet) {
                    int legacyA = parseLeadingInt(arg, -1);
                    if (legacyA >= 1 && legacyA <= 11) {
                        ctx.alignment = convertLegacyAlignment(legacyA);
                        ctx.alignmentSet = true;
                    }
                }
                break;
            }
            case 'q': {
                int qVal = parseLeadingInt(arg, -1);
                if (qVal >= 0 && qVal <= 3) {
                    ctx.wrapStyle = qVal;
                }
                break;
            }
            case 'r': {
                AssScript.AssStyle resetTarget = arg.isEmpty() ? baseStyle : resolveStyle(styles, arg);
                style.applyFrom(resetTarget);
                activeTransforms.clear();
                break;
            }
            case 'k':
            default:
                break;
        }
    }

    private static void applyInlineColor(String rawVal,
                                         int channel,
                                         AssScript.SegmentStyle style,
                                         AssScript.AssStyle baseStyle) {
        String trimmed = rawVal.trim();
        if (trimmed.isEmpty()) {
            // Empty \c or \1c resets that channel's RGB to style default
            switch (channel) {
                case 1:
                    style.primaryColor = AssScript.replaceRgbKeepAlpha(style.primaryColor, baseStyle.primaryColor);
                    break;
                case 2:
                    style.secondaryColor = AssScript.replaceRgbKeepAlpha(style.secondaryColor, baseStyle.secondaryColor);
                    break;
                case 3:
                    style.outlineColor = AssScript.replaceRgbKeepAlpha(style.outlineColor, baseStyle.outlineColor);
                    break;
                case 4:
                    style.backColor = AssScript.replaceRgbKeepAlpha(style.backColor, baseStyle.backColor);
                    break;
                default:
                    break;
            }
            return;
        }
        String cleanHex = stripColorDelimiters(trimmed);
        Integer parsed = parseAssColor(trimmed);
        if (parsed == null) return;
        boolean hasExplicitAlpha = cleanHex.length() >= 8;
        switch (channel) {
            case 1:
                style.primaryColor = hasExplicitAlpha ? parsed : AssScript.replaceRgbKeepAlpha(style.primaryColor, parsed);
                break;
            case 2:
                style.secondaryColor = hasExplicitAlpha ? parsed : AssScript.replaceRgbKeepAlpha(style.secondaryColor, parsed);
                break;
            case 3:
                style.outlineColor = hasExplicitAlpha ? parsed : AssScript.replaceRgbKeepAlpha(style.outlineColor, parsed);
                break;
            case 4:
                style.backColor = hasExplicitAlpha ? parsed : AssScript.replaceRgbKeepAlpha(style.backColor, parsed);
                break;
            default:
                break;
        }
    }

    private static void applyInlineAlpha(String rawVal,
                                         int channel,
                                         AssScript.SegmentStyle style,
                                         AssScript.AssStyle baseStyle) {
        String trimmed = rawVal.trim();
        Integer alpha = trimmed.isEmpty() ? null : parseAssAlpha(trimmed);
        switch (channel) {
            case 1: {
                int a = alpha != null ? alpha : ((baseStyle.primaryColor >>> 24) & 0xFF);
                style.primaryColor = AssScript.replaceAlphaKeepRgb(style.primaryColor, a);
                break;
            }
            case 2: {
                int a = alpha != null ? alpha : ((baseStyle.secondaryColor >>> 24) & 0xFF);
                style.secondaryColor = AssScript.replaceAlphaKeepRgb(style.secondaryColor, a);
                break;
            }
            case 3: {
                int a = alpha != null ? alpha : ((baseStyle.outlineColor >>> 24) & 0xFF);
                style.outlineColor = AssScript.replaceAlphaKeepRgb(style.outlineColor, a);
                break;
            }
            case 4: {
                int a = alpha != null ? alpha : ((baseStyle.backColor >>> 24) & 0xFF);
                style.backColor = AssScript.replaceAlphaKeepRgb(style.backColor, a);
                break;
            }
            default:
                break;
        }
    }

    @Nullable
    private static AssScript.AssClip parseClipContent(String inner, boolean inverted) {
        if (inner == null || inner.isEmpty()) return null;
        // Check for 4 numeric arguments: \clip(x1, y1, x2, y2)
        String[] parts = inner.split(",");
        if (parts.length == 4) {
            try {
                float x1 = Float.parseFloat(parts[0].trim());
                float y1 = Float.parseFloat(parts[1].trim());
                float x2 = Float.parseFloat(parts[2].trim());
                float y2 = Float.parseFloat(parts[3].trim());
                return AssScript.AssClip.rectangular(inverted, x1, y1, x2, y2);
            } catch (NumberFormatException ignored) {}
        }
        // Vector clip: \clip(m 0 0 l ...) or \clip(scale, m 0 0 l ...)
        if (parts.length == 2) {
            int scale = parseIntSafe(parts[0].trim(), 1);
            String cmds = parts[1].trim();
            if (!cmds.isEmpty()) {
                return AssScript.AssClip.vector(inverted, scale, cmds);
            }
        } else if (parts.length == 1) {
            String cmds = parts[0].trim();
            if (!cmds.isEmpty()) {
                return AssScript.AssClip.vector(inverted, 1, cmds);
            }
        }
        return null;
    }

    @Nullable
    private static AssScript.AssTransform parseTransformTag(String tag) {
        int openIdx = tag.indexOf('(');
        int closeIdx = tag.lastIndexOf(')');
        if (openIdx < 0 || closeIdx <= openIdx) return null;
        String inner = tag.substring(openIdx + 1, closeIdx).trim();
        if (inner.isEmpty()) return null;

        int backslashIdx = inner.indexOf('\\');
        if (backslashIdx < 0) return null;

        String prefix = inner.substring(0, backslashIdx).trim();
        String mods = inner.substring(backslashIdx).trim();

        long t1 = -1L;
        long t2 = -1L;
        float accel = 1f;

        if (!prefix.isEmpty()) {
            if (prefix.endsWith(",")) {
                prefix = prefix.substring(0, prefix.length() - 1).trim();
            }
            if (!prefix.isEmpty()) {
                String[] parts = prefix.split(",");
                if (parts.length == 1) {
                    accel = parseFloatSafe(parts[0].trim(), 1f);
                } else if (parts.length == 2) {
                    t1 = Math.round(parseFloatSafe(parts[0].trim(), 0f));
                    t2 = Math.round(parseFloatSafe(parts[1].trim(), -1f));
                } else if (parts.length >= 3) {
                    t1 = Math.round(parseFloatSafe(parts[0].trim(), 0f));
                    t2 = Math.round(parseFloatSafe(parts[1].trim(), -1f));
                    accel = parseFloatSafe(parts[2].trim(), 1f);
                }
            }
        }

        Integer primaryRgb = null;
        Integer outlineRgb = null;
        Integer backRgb = null;
        Integer allAlpha = null;
        Integer primaryAlpha = null;
        Integer outlineAlpha = null;
        Integer backAlpha = null;
        Float fontSize = null;
        Float scaleX = null;
        Float scaleY = null;
        Float frz = null;
        Float borderX = null;
        Float borderY = null;
        Float shadowX = null;
        Float shadowY = null;
        Float blur = null;
        Float spacing = null;

        List<String> innerTags = splitOverrideTags(mods);
        for (int i = 0; i < innerTags.size(); i++) {
            String t = innerTags.get(i);
            String tl = t.toLowerCase(Locale.ROOT);
            if (tl.startsWith("alpha")) {
                allAlpha = parseAssAlpha(t.substring(5));
            } else if (tl.startsWith("1a")) {
                primaryAlpha = parseAssAlpha(t.substring(2));
            } else if (tl.startsWith("3a")) {
                outlineAlpha = parseAssAlpha(t.substring(2));
            } else if (tl.startsWith("4a")) {
                backAlpha = parseAssAlpha(t.substring(2));
            } else if (tl.startsWith("1c")) {
                primaryRgb = parseAssColor(t.substring(2));
            } else if (tl.startsWith("3c")) {
                outlineRgb = parseAssColor(t.substring(2));
            } else if (tl.startsWith("4c")) {
                backRgb = parseAssColor(t.substring(2));
            } else if (tl.startsWith("c")) {
                primaryRgb = parseAssColor(t.substring(1));
            } else if (tl.startsWith("fscx")) {
                scaleX = parseFloatBoxed(t.substring(4));
            } else if (tl.startsWith("fscy")) {
                scaleY = parseFloatBoxed(t.substring(4));
            } else if (tl.startsWith("fsp")) {
                spacing = parseFloatBoxed(t.substring(3));
            } else if (tl.startsWith("frz")) {
                frz = parseFloatBoxed(t.substring(3));
            } else if (tl.startsWith("fr")) {
                frz = parseFloatBoxed(t.substring(2));
            } else if (tl.startsWith("fs")) {
                fontSize = parseFloatBoxed(t.substring(2));
            } else if (tl.startsWith("xbord")) {
                borderX = parseFloatBoxed(t.substring(5));
            } else if (tl.startsWith("ybord")) {
                borderY = parseFloatBoxed(t.substring(5));
            } else if (tl.startsWith("bord")) {
                Float b = parseFloatBoxed(t.substring(4));
                borderX = b;
                borderY = b;
            } else if (tl.startsWith("xshad")) {
                shadowX = parseFloatBoxed(t.substring(5));
            } else if (tl.startsWith("yshad")) {
                shadowY = parseFloatBoxed(t.substring(5));
            } else if (tl.startsWith("shad")) {
                Float sh = parseFloatBoxed(t.substring(4));
                shadowX = sh;
                shadowY = sh;
            } else if (tl.startsWith("blur")) {
                blur = parseFloatBoxed(t.substring(4));
            }
        }

        if (primaryRgb == null && outlineRgb == null && backRgb == null
                && allAlpha == null && primaryAlpha == null && outlineAlpha == null && backAlpha == null
                && fontSize == null && scaleX == null && scaleY == null && frz == null
                && borderX == null && borderY == null && shadowX == null && shadowY == null
                && blur == null && spacing == null) {
            return null;
        }

        return new AssScript.AssTransform(
                t1, t2, accel,
                primaryRgb, outlineRgb, backRgb,
                allAlpha, primaryAlpha, outlineAlpha, backAlpha,
                fontSize, scaleX, scaleY, frz,
                borderX, borderY, shadowX, shadowY,
                blur, spacing
        );
    }

    /**
     * Decodes an ASS/SSA color string into Android ARGB int.
     * Supports &HAABBGGRR&, &HBBGGRR&, HBBGGRR, #BBGGRR, short hex (&HFF&), and decimal ABGR.
     */
    @Nullable
    public static Integer parseAssColor(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;

        boolean hasHexMarker = trimmed.indexOf('&') >= 0
                || trimmed.indexOf('#') >= 0
                || trimmed.indexOf('H') >= 0
                || trimmed.indexOf('h') >= 0;
        String clean = stripColorDelimiters(trimmed);
        if (clean.isEmpty()) return null;

        try {
            if (!hasHexMarker && isSignedDecimalInteger(clean) && (clean.startsWith("-") || clean.length() > 6)) {
                return decodeDecimalAbgr(Long.parseLong(clean));
            }
            if (!hasHexMarker && isSignedDecimalInteger(clean) && clean.length() <= 5) {
                // Short plain integer without hex marker (e.g., SSA decimal color like 65535)
                // Wait: 6-digit all-numeric strings like "00FF00" or "000000" are parsed as hex BBGGRR!
                return decodeDecimalAbgr(Long.parseLong(clean));
            }

            if (!isHexString(clean)) return null;

            StringBuilder sb = new StringBuilder(clean);
            while (sb.length() < 6) {
                sb.insert(0, '0');
            }
            String hex = sb.toString();

            if (hex.length() == 6) {
                int b = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int r = Integer.parseInt(hex.substring(4, 6), 16);
                return (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            } else if (hex.length() == 7) {
                hex = "0" + hex;
            }
            if (hex.length() >= 8) {
                String hex8 = hex.substring(hex.length() - 8);
                int assTransparency = Integer.parseInt(hex8.substring(0, 2), 16);
                int opacity = Math.max(0, Math.min(255, 255 - assTransparency));
                int b = Integer.parseInt(hex8.substring(2, 4), 16);
                int g = Integer.parseInt(hex8.substring(4, 6), 16);
                int r = Integer.parseInt(hex8.substring(6, 8), 16);
                return ((opacity & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int parseAssStyleColor(@Nullable String raw, int fallback) {
        if (raw == null) return fallback;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return fallback;
        boolean hasHexMarker = trimmed.indexOf('&') >= 0
                || trimmed.indexOf('#') >= 0
                || trimmed.indexOf('H') >= 0
                || trimmed.indexOf('h') >= 0;
        if (!hasHexMarker && isSignedDecimalInteger(trimmed)) {
            try {
                return decodeDecimalAbgr(Long.parseLong(trimmed));
            } catch (Exception ignored) {}
        }
        Integer parsed = parseAssColor(trimmed);
        return parsed != null ? parsed : fallback;
    }

    private static int decodeDecimalAbgr(long val) {
        long u = val & 0xFFFFFFFFL;
        int assTransparency = (int) ((u >>> 24) & 0xFF);
        int b = (int) ((u >>> 16) & 0xFF);
        int g = (int) ((u >>> 8) & 0xFF);
        int r = (int) (u & 0xFF);
        int opacity = Math.max(0, Math.min(255, 255 - assTransparency));
        return ((opacity & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /**
     * Decodes an ASS alpha tag value (e.g. "&H80&", "FF", "00") into Android opacity [0..255].
     * In ASS: 0x00 = fully opaque (255), 0xFF = fully transparent (0).
     */
    @Nullable
    public static Integer parseAssAlpha(@Nullable String raw) {
        if (raw == null) return null;
        String clean = stripColorDelimiters(raw.trim());
        if (clean.isEmpty() || !isHexString(clean)) return null;
        try {
            int assTransparency = Integer.parseInt(clean, 16) & 0xFF;
            return Math.max(0, Math.min(255, 255 - assTransparency));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Parses an ASS timestamp like "0:01:23.45" into milliseconds.
     */
    public static long parseTimestampMs(@Nullable String raw) {
        if (raw == null) return -1L;
        String s = raw.trim();
        int c1 = s.indexOf(':');
        if (c1 <= 0) return -1L;
        int c2 = s.indexOf(':', c1 + 1);
        if (c2 <= c1 + 1) return -1L;
        try {
            long hours = Long.parseLong(s.substring(0, c1).trim());
            long mins = Long.parseLong(s.substring(c1 + 1, c2).trim());
            String secPart = s.substring(c2 + 1).trim();
            int dot = secPart.indexOf('.');
            if (dot < 0) dot = secPart.indexOf(',');
            long secs;
            long ms = 0L;
            if (dot >= 0) {
                secs = Long.parseLong(secPart.substring(0, dot));
                String frac = secPart.substring(dot + 1);
                if (frac.length() == 1) ms = Long.parseLong(frac) * 100L;
                else if (frac.length() == 2) ms = Long.parseLong(frac) * 10L;
                else if (frac.length() >= 3) ms = Long.parseLong(frac.substring(0, 3));
            } else {
                secs = Long.parseLong(secPart);
            }
            return (((hours * 60L) + mins) * 60L + secs) * 1000L + ms;
        } catch (Exception e) {
            return -1L;
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
            default: return (a >= 1 && a <= 9) ? a : 2;
        }
    }

    private static AssScript.AssStyle resolveStyle(Map<String, AssScript.AssStyle> styles, String name) {
        String key = AssScript.normalizeStyleKey(name);
        AssScript.AssStyle found = styles.get(key);
        if (found != null) return found;
        found = styles.get("default");
        if (found != null) return found;
        if (!styles.isEmpty()) {
            return styles.values().iterator().next();
        }
        return AssScript.AssStyle.createDefault();
    }

    private static String stripColorDelimiters(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&' && c != 'H' && c != 'h' && c != '#' && !Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isHexString(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static boolean isSignedDecimalInteger(String s) {
        if (s.isEmpty()) return false;
        int start = (s.charAt(0) == '-' || s.charAt(0) == '+') ? 1 : 0;
        if (start >= s.length()) return false;
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static int parseLeadingInt(String raw, int fallback) {
        if (raw == null) return fallback;
        int len = raw.length();
        int i = 0;
        while (i < len && Character.isWhitespace(raw.charAt(i))) {
            i++;
        }
        int start = i;
        if (i < len && (raw.charAt(i) == '+' || raw.charAt(i) == '-')) {
            i++;
        }
        int digitStart = i;
        while (i < len && Character.isDigit(raw.charAt(i))) {
            i++;
        }
        if (i == digitStart) return fallback;
        try {
            return Integer.parseInt(raw.substring(start, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Nullable
    private static float[] parseParenFloats(String tag, int openOffset) {
        int open = tag.indexOf('(', openOffset);
        if (open < 0) return null;
        int close = tag.indexOf(')', open + 1);
        if (close <= open) return null;
        String inner = tag.substring(open + 1, close).trim();
        if (inner.isEmpty()) return null;
        String[] parts = inner.split(",");
        float[] res = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                res[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return res;
    }

    private static String getCol(String[] cols, @Nullable Map<String, Integer> fmt, String key, int defaultIdx, String fallback) {
        int idx = getColIndex(fmt, key, defaultIdx);
        if (idx >= 0 && idx < cols.length) {
            return cols[idx];
        }
        return fallback;
    }

    private static int getColIndex(@Nullable Map<String, Integer> fmt, String key, int defaultIdx) {
        if (fmt != null) {
            Integer mapped = fmt.get(key);
            if (mapped != null) return mapped;
        }
        return defaultIdx;
    }

    private static boolean startsWithIgnoreCase(String str, String prefix) {
        return str.length() >= prefix.length()
                && str.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static int parseIntSafe(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloatSafe(String raw, float fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (s.isEmpty()) return fallback;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            // Fallback to leading float prefix (e.g. if trailing inline comment exists in tag)
            int end = extractLeadingFloatEnd(s);
            if (end > 0) {
                try {
                    return Float.parseFloat(s.substring(0, end));
                } catch (NumberFormatException ignored) {}
            }
            return fallback;
        }
    }

    private static int extractLeadingFloatEnd(String s) {
        int i = 0;
        int len = s.length();
        if (i < len && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        boolean sawDigit = false;
        boolean sawDot = false;
        while (i < len) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                sawDigit = true;
                i++;
            } else if (c == '.' && !sawDot) {
                sawDot = true;
                i++;
            } else {
                break;
            }
        }
        return sawDigit ? i : 0;
    }

    private static float parseFloatOrDefault(String raw, float fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        return parseFloatSafe(raw, fallback);
    }

    @Nullable
    private static Float parseFloatBoxed(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.trim();
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            int end = extractLeadingFloatEnd(s);
            if (end > 0) {
                try {
                    return Float.parseFloat(s.substring(0, end));
                } catch (NumberFormatException ignored) {}
            }
            return null;
        }
    }

    private static boolean parseAssBool(String raw) {
        int v = parseIntSafe(raw, 0);
        return v != 0;
    }

    private static boolean parseAssBoolOrWeight(String raw) {
        int v = parseIntSafe(raw, 0);
        return v == -1 || v == 1 || v >= 600;
    }
}
