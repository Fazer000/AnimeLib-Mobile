package com.example.animelib.subtitles;

import android.graphics.Path;
import android.graphics.RectF;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accurate ASS vector drawing (\p1..\p9) path parser implementing:
 * - m (move & close previous contour)
 * - n (move without closing previous contour)
 * - l (line segments, multi-pair)
 * - b (cubic Bezier curves, multi-tuple)
 * - s / p / c (uniform cubic B-splines converted accurately to cubic Bezier curves)
 * - Path.FillType.WINDING (non-zero winding rule matching libass / VSFilter)
 */
public final class AssVectorPathParser {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("([a-zA-Z])|([+-]?(?:\\d+\\.?\\d*|\\.\\d+))");
    private static final LruCache<String, ParsedVectorPath> PATH_CACHE = new LruCache<>(256);

    private AssVectorPathParser() {}

    public static final class ParsedVectorPath {
        public final Path path;
        public final RectF bounds;

        public ParsedVectorPath(Path path, RectF bounds) {
            this.path = path;
            this.bounds = bounds;
        }
    }

    @Nullable
    public static ParsedVectorPath parseCached(@Nullable String commands, float scale) {
        if (commands == null || commands.trim().isEmpty()) return null;
        String key = scale + "|" + commands;
        ParsedVectorPath cached = PATH_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        ParsedVectorPath parsed = parse(commands, scale);
        if (parsed != null) {
            PATH_CACHE.put(key, parsed);
        }
        return parsed;
    }

    @Nullable
    public static ParsedVectorPath parse(@Nullable String commands, float scale) {
        if (commands == null || commands.trim().isEmpty()) return null;
        try {
            Matcher matcher = TOKEN_PATTERN.matcher(commands);
            List<String> tokens = new ArrayList<>();
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
            if (tokens.isEmpty()) return null;

            Path path = new Path();
            path.setFillType(Path.FillType.WINDING);

            char currentCmd = 'm';
            int i = 0;
            boolean hasPoints = false;
            boolean contourHasSegments = false;
            float curX = 0f;
            float curY = 0f;

            // Active B-spline control point buffer
            List<Float> splineX = null;
            List<Float> splineY = null;

            while (i < tokens.size()) {
                String token = tokens.get(i);
                char first = Character.toLowerCase(token.charAt(0));

                if (Character.isLetter(first)) {
                    if (first == 'c') {
                        if (splineX != null) {
                            flushBSpline(path, splineX, splineY, true);
                            splineX = null;
                            splineY = null;
                            contourHasSegments = true;
                        } else if (contourHasSegments) {
                            path.close();
                            contourHasSegments = false;
                        }
                        i++;
                        continue;
                    }

                    // Flush any in-progress B-spline when switching to a non-'p' command
                    if (first != 'p' && splineX != null) {
                        flushBSpline(path, splineX, splineY, false);
                        curX = splineX.get(splineX.size() - 1);
                        curY = splineY.get(splineY.size() - 1);
                        splineX = null;
                        splineY = null;
                        contourHasSegments = true;
                    }

                    currentCmd = first;
                    i++;
                    if (i >= tokens.size()) break;
                }

                if (currentCmd == 'm' || currentCmd == 'n') {
                    if (i + 1 < tokens.size() && isNumberToken(tokens.get(i)) && isNumberToken(tokens.get(i + 1))) {
                        float x = Float.parseFloat(tokens.get(i)) * scale;
                        float y = Float.parseFloat(tokens.get(i + 1)) * scale;
                        if (currentCmd == 'm' && contourHasSegments) {
                            path.close();
                        }
                        path.moveTo(x, y);
                        curX = x;
                        curY = y;
                        hasPoints = true;
                        contourHasSegments = false;
                        i += 2;
                        // Subsequent bare coordinate pairs after 'm'/'n' act as line segments
                        currentCmd = 'l';
                    } else {
                        i++;
                    }
                } else if (currentCmd == 'l') {
                    if (i + 1 < tokens.size() && isNumberToken(tokens.get(i)) && isNumberToken(tokens.get(i + 1))) {
                        float x = Float.parseFloat(tokens.get(i)) * scale;
                        float y = Float.parseFloat(tokens.get(i + 1)) * scale;
                        if (!hasPoints) {
                            path.moveTo(x, y);
                        } else {
                            path.lineTo(x, y);
                            contourHasSegments = true;
                        }
                        curX = x;
                        curY = y;
                        hasPoints = true;
                        i += 2;
                    } else {
                        i++;
                    }
                } else if (currentCmd == 'b') {
                    if (i + 5 < tokens.size()
                            && isNumberToken(tokens.get(i)) && isNumberToken(tokens.get(i + 1))
                            && isNumberToken(tokens.get(i + 2)) && isNumberToken(tokens.get(i + 3))
                            && isNumberToken(tokens.get(i + 4)) && isNumberToken(tokens.get(i + 5))) {
                        float x1 = Float.parseFloat(tokens.get(i)) * scale;
                        float y1 = Float.parseFloat(tokens.get(i + 1)) * scale;
                        float x2 = Float.parseFloat(tokens.get(i + 2)) * scale;
                        float y2 = Float.parseFloat(tokens.get(i + 3)) * scale;
                        float x3 = Float.parseFloat(tokens.get(i + 4)) * scale;
                        float y3 = Float.parseFloat(tokens.get(i + 5)) * scale;
                        if (!hasPoints) {
                            path.moveTo(curX, curY);
                        }
                        path.cubicTo(x1, y1, x2, y2, x3, y3);
                        curX = x3;
                        curY = y3;
                        hasPoints = true;
                        contourHasSegments = true;
                        i += 6;
                    } else {
                        i++;
                    }
                } else if (currentCmd == 's' || currentCmd == 'p') {
                    if (i + 1 < tokens.size() && isNumberToken(tokens.get(i)) && isNumberToken(tokens.get(i + 1))) {
                        float x = Float.parseFloat(tokens.get(i)) * scale;
                        float y = Float.parseFloat(tokens.get(i + 1)) * scale;
                        if (splineX == null) {
                            splineX = new ArrayList<>();
                            splineY = new ArrayList<>();
                            if (hasPoints) {
                                splineX.add(curX);
                                splineY.add(curY);
                            }
                        }
                        splineX.add(x);
                        splineY.add(y);
                        hasPoints = true;
                        i += 2;
                    } else {
                        i++;
                    }
                } else {
                    i++;
                }
            }

            if (splineX != null) {
                flushBSpline(path, splineX, splineY, false);
                contourHasSegments = true;
            }

            if (!hasPoints) return null;

            RectF bounds = new RectF();
            path.computeBounds(bounds, true);
            if (bounds.width() <= 0f && bounds.height() <= 0f) {
                return null;
            }

            return new ParsedVectorPath(path, bounds);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts a list of uniform cubic B-spline control points into exact cubic Bezier curves
     * matching libass/VSFilter's ASS 's'/'p'/'c' drawing commands.
     */
    private static void flushBSpline(Path path, List<Float> xs, List<Float> ys, boolean closeSpline) {
        int n = xs.size();
        if (n < 2) return;
        if (n == 2) {
            path.lineTo(xs.get(1), ys.get(1));
            if (closeSpline) path.close();
            return;
        }
        if (n == 3) {
            path.quadTo(xs.get(1), ys.get(1), xs.get(2), ys.get(2));
            if (closeSpline) path.close();
            return;
        }

        List<Float> ptsX = new ArrayList<>(xs);
        List<Float> ptsY = new ArrayList<>(ys);

        if (closeSpline) {
            // Wrap first 3 control points for a C2-continuous closed B-spline loop
            ptsX.add(xs.get(0));
            ptsY.add(ys.get(0));
            ptsX.add(xs.get(1));
            ptsY.add(ys.get(1));
            ptsX.add(xs.get(2));
            ptsY.add(ys.get(2));
        }

        int total = ptsX.size();
        for (int i = 0; i <= total - 4; i++) {
            float x0 = ptsX.get(i);
            float y0 = ptsY.get(i);
            float x1 = ptsX.get(i + 1);
            float y1 = ptsY.get(i + 1);
            float x2 = ptsX.get(i + 2);
            float y2 = ptsY.get(i + 2);
            float x3 = ptsX.get(i + 3);
            float y3 = ptsY.get(i + 3);

            float sx = (x0 + 4f * x1 + x2) / 6f;
            float sy = (y0 + 4f * y1 + y2) / 6f;
            float c1x = (2f * x1 + x2) / 3f;
            float c1y = (2f * y1 + y2) / 3f;
            float c2x = (x1 + 2f * x2) / 3f;
            float c2y = (y1 + 2f * y2) / 3f;
            float ex = (x1 + 4f * x2 + x3) / 6f;
            float ey = (y1 + 4f * y2 + y3) / 6f;

            if (i == 0) {
                if (closeSpline) {
                    path.moveTo(sx, sy);
                } else {
                    path.lineTo(sx, sy);
                }
            }
            path.cubicTo(c1x, c1y, c2x, c2y, ex, ey);
        }

        if (closeSpline) {
            path.close();
        } else {
            path.lineTo(ptsX.get(total - 1), ptsY.get(total - 1));
        }
    }

    private static boolean isNumberToken(String token) {
        if (token == null || token.isEmpty()) return false;
        char c = token.charAt(0);
        return Character.isDigit(c) || c == '-' || c == '+' || c == '.';
    }
}
