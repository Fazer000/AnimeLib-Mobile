package com.example.animelib.subtitles;

/**
 * Shared pure-geometry helper matching libass (ass_drawing.c / ass_render.c) and VSFilter (CPolygon)
 * alignment & baseline-offset math for ASS vector drawings (\p1..\p9, \pbo, \an1..\an9).
 *
 * <p>In libass / VSFilter, a vector drawing with scaled bounding box [bLeft, bTop, bRight, bBottom]
 * behaves as an inline glyph with:
 * <ul>
 *   <li>{@code advance.x = bRight - bLeft} (drawing width)</li>
 *   <li>{@code asc = bBottom - bTop} (drawing height), {@code desc = 0}</li>
 *   <li>Drawing coordinate {@code (0, 0)} anchored at {@code (penX, baselineY - asc + pboOffset)}</li>
 *   <li>{@code pboOffset = pbo * pScale * fscy}, where positive {@code \pbo} moves DOWN (+Y)
 *       and negative {@code \pbo} moves UP (-Y)</li>
 * </ul>
 */
public final class AssVectorGeometry {

    private AssVectorGeometry() {}

    /**
     * Computes the horizontal origin (translation X in PlayRes coordinates) of a line of vector
     * drawings with total advance width {@code totalWidth} for alignment {@code alignment} (1..9).
     */
    public static float computeLineOriginX(float anchorX, float totalWidth, int alignment) {
        int xAlign = (clampAlignment(alignment) - 1) % 3 + 1; // 1=left, 2=center, 3=right
        if (xAlign == 1) {
            return anchorX;
        } else if (xAlign == 2) {
            return anchorX - totalWidth * 0.5f;
        } else {
            return anchorX - totalWidth;
        }
    }

    /**
     * Computes the vertical top origin (translation Y in PlayRes coordinates before per-segment
     * baseline alignment and \pbo) of a line of vector drawings with maximum ascent {@code maxHeight}
     * for alignment {@code alignment} (1..9).
     */
    public static float computeLineTopY(float anchorY, float maxHeight, int alignment) {
        int yAlign = (clampAlignment(alignment) - 1) / 3 + 1; // 1=bottom, 2=middle, 3=top
        if (yAlign == 3) {
            return anchorY;
        } else if (yAlign == 2) {
            return anchorY - maxHeight * 0.5f;
        } else {
            return anchorY - maxHeight;
        }
    }

    /**
     * Computes the horizontal origin X for a single vector drawing segment whose scaled bounding box
     * is {@code [bLeft, bRight]}.
     */
    public static float computeSingleOriginX(float anchorX, float bLeft, float bRight, int alignment) {
        float bWidth = Math.max(0f, bRight - bLeft);
        return computeLineOriginX(anchorX, bWidth, alignment);
    }

    /**
     * Computes the vertical origin Y for a single vector drawing segment whose scaled bounding box
     * is {@code [bTop, bBottom]}, including the ASS {@code \pbo} baseline offset.
     */
    public static float computeSingleOriginY(float anchorY,
                                             float bTop,
                                             float bBottom,
                                             int alignment,
                                             float pbo,
                                             float pScale,
                                             float fscy) {
        float bHeight = Math.max(0f, bBottom - bTop);
        return computeLineTopY(anchorY, bHeight, alignment) + computePboOffsetY(pbo, pScale, fscy);
    }

    /**
     * Computes the vertical offset produced by {@code \pbo<y>} in PlayRes Y units.
     * Positive {@code \pbo} shifts DOWN (+Y); negative {@code \pbo} shifts UP (-Y).
     */
    public static float computePboOffsetY(float pbo, float pScale, float fscy) {
        return pbo * pScale * fscy;
    }

    /**
     * Computes the unpositioned anchor X in PlayRes coordinates taking MarginL / MarginR into account.
     */
    public static float computeUnpositionedAnchorX(float playResX, int marginL, int marginR, int alignment) {
        int xAlign = (clampAlignment(alignment) - 1) % 3 + 1;
        if (xAlign == 1) {
            return marginL;
        } else if (xAlign == 2) {
            return marginL + (playResX - marginL - marginR) * 0.5f;
        } else {
            return playResX - marginR;
        }
    }

    /**
     * Computes the unpositioned anchor Y in PlayRes coordinates taking MarginV into account.
     */
    public static float computeUnpositionedAnchorY(float playResY, int marginV, int alignment) {
        int yAlign = (clampAlignment(alignment) - 1) / 3 + 1;
        if (yAlign == 3) {
            return marginV;
        } else if (yAlign == 2) {
            return playResY * 0.5f;
        } else {
            return playResY - marginV;
        }
    }

    private static int clampAlignment(int alignment) {
        return (alignment >= 1 && alignment <= 9) ? alignment : 2;
    }
}
