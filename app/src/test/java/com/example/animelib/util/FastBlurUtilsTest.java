package com.example.animelib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FastBlurUtilsTest {

    private static final int SAMPLE_W = 36;
    private static final int SAMPLE_H = 20;

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    private static int red(int argb) {
        return (argb >>> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >>> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    @Test
    public void buildEdgeGlowField_ignoresInteriorCenterContent() {
        FastBlurUtils utils = new FastBlurUtils(SAMPLE_W, SAMPLE_H, 4);
        int[] sample = new int[SAMPLE_W * SAMPLE_H];
        int[] glow = new int[FastBlurUtils.GLOW_WIDTH * FastBlurUtils.GLOW_HEIGHT];

        // Pure dark perimeter, intense bright green/red block strictly in interior center
        for (int y = 0; y < SAMPLE_H; y++) {
            for (int x = 0; x < SAMPLE_W; x++) {
                boolean isCenterBlock = x >= 6 && x < SAMPLE_W - 6 && y >= 6 && y < SAMPLE_H - 6;
                sample[y * SAMPLE_W + x] = isCenterBlock ? 0xFF00FF00 : 0xFF0000A0;
            }
        }

        utils.buildEdgeGlowField(sample, glow);

        // Left diffusion zone mid-row (gx = 18 of 0..31, gy = 36 of 0..71) must reflect blue edge, NOT interior green block
        int midY = FastBlurUtils.GLOW_HEIGHT / 2;
        int leftGlow = glow[midY * FastBlurUtils.GLOW_WIDTH + 18];
        assertTrue("Edge glow must preserve perimeter blue", blue(leftGlow) > green(leftGlow) + 80);
        assertTrue("Interior green must not leak into outer glow", green(leftGlow) < 30);

        // Center area behind player must also interpolate perimeter blue without leaking center green
        int centerGlow = glow[midY * FastBlurUtils.GLOW_WIDTH + (FastBlurUtils.GLOW_WIDTH / 2)];
        assertTrue("Center glow must only interpolate perimeter edges", blue(centerGlow) > green(centerGlow) + 80);
    }

    @Test
    public void buildEdgeGlowField_diffusesEachEdgeIndependentlyAndBlendsCorners() {
        FastBlurUtils utils = new FastBlurUtils(SAMPLE_W, SAMPLE_H, 4);
        int[] sample = new int[SAMPLE_W * SAMPLE_H];
        int[] glow = new int[FastBlurUtils.GLOW_WIDTH * FastBlurUtils.GLOW_HEIGHT];

        // Left strip red, right strip blue, top/bottom dark grey
        for (int y = 0; y < SAMPLE_H; y++) {
            for (int x = 0; x < SAMPLE_W; x++) {
                if (x < 5) {
                    sample[y * SAMPLE_W + x] = 0xFFE02020;
                } else if (x >= SAMPLE_W - 5) {
                    sample[y * SAMPLE_W + x] = 0xFF2040E0;
                } else {
                    sample[y * SAMPLE_W + x] = 0xFF181818;
                }
            }
        }

        utils.buildEdgeGlowField(sample, glow);

        int midY = FastBlurUtils.GLOW_HEIGHT / 2;
        int thirdW = FastBlurUtils.GLOW_WIDTH / 3;
        int nearLeft = glow[midY * FastBlurUtils.GLOW_WIDTH + (thirdW - 4)];
        int nearRight = glow[midY * FastBlurUtils.GLOW_WIDTH + (thirdW * 2 + 4)];

        assertTrue("Left diffusion should be red-dominant", red(nearLeft) > blue(nearLeft) + 80);
        assertTrue("Right diffusion should be blue-dominant", blue(nearRight) > red(nearRight) + 80);
    }

    @Test
    public void buildEdgeGlowField_preservesDistinctColorZonesAlongSameEdge() {
        FastBlurUtils utils = new FastBlurUtils(SAMPLE_W, SAMPLE_H, 4);
        int[] sample = new int[SAMPLE_W * SAMPLE_H];
        int[] glow = new int[FastBlurUtils.GLOW_WIDTH * FastBlurUtils.GLOW_HEIGHT];

        // Top edge split cleanly: left half vivid red, right half vivid blue
        for (int y = 0; y < SAMPLE_H; y++) {
            for (int x = 0; x < SAMPLE_W; x++) {
                sample[y * SAMPLE_W + x] = (x < SAMPLE_W / 2) ? 0xFFE52020 : 0xFF2050E5;
            }
        }

        utils.buildEdgeGlowField(sample, glow);

        int thirdW = FastBlurUtils.GLOW_WIDTH / 3;
        int topRowNearVideo = (FastBlurUtils.GLOW_HEIGHT / 3) - 3;
        int topLeftZone = glow[topRowNearVideo * FastBlurUtils.GLOW_WIDTH + thirdW + 4];
        int topRightZone = glow[topRowNearVideo * FastBlurUtils.GLOW_WIDTH + (thirdW * 2 - 4)];

        assertTrue("Top-left zone must stay strongly red without mixing into blue", red(topLeftZone) > blue(topLeftZone) + 120);
        assertTrue("Top-right zone must stay strongly blue without mixing into red", blue(topRightZone) > red(topRightZone) + 120);
    }

    @Test
    public void buildEdgeGlowField_fadesMonotonicallyToZeroAtOuterBorder() {
        FastBlurUtils utils = new FastBlurUtils(SAMPLE_W, SAMPLE_H, 4);
        int[] sample = new int[SAMPLE_W * SAMPLE_H];
        int[] glow = new int[FastBlurUtils.GLOW_WIDTH * FastBlurUtils.GLOW_HEIGHT];

        for (int i = 0; i < sample.length; i++) {
            sample[i] = 0xFFFF6622;
        }

        utils.buildEdgeGlowField(sample, glow);

        int midY = FastBlurUtils.GLOW_HEIGHT / 2;
        int thirdW = FastBlurUtils.GLOW_WIDTH / 3;
        int prevAlpha = 255;
        // Left empty strip goes from gx = thirdW - 1 (video border) outward to gx = 0 (screen border)
        for (int gx = thirdW - 1; gx >= 0; gx--) {
            int a = alpha(glow[midY * FastBlurUtils.GLOW_WIDTH + gx]);
            assertTrue("Alpha must decrease monotonically outward at gx=" + gx, a <= prevAlpha);
            prevAlpha = a;
        }
        int outerLeftAlpha = alpha(glow[midY * FastBlurUtils.GLOW_WIDTH]);
        int outerRightAlpha = alpha(glow[midY * FastBlurUtils.GLOW_WIDTH + (FastBlurUtils.GLOW_WIDTH - 1)]);
        assertTrue("Outer left border should remain visibly semi-transparent", outerLeftAlpha >= 65 && outerLeftAlpha <= 100);
        assertTrue("Outer right border should remain visibly semi-transparent", outerRightAlpha >= 65 && outerRightAlpha <= 100);

        int midX = FastBlurUtils.GLOW_WIDTH / 2;
        int outerTopAlpha = alpha(glow[midX]);
        int outerBottomAlpha = alpha(glow[(FastBlurUtils.GLOW_HEIGHT - 1) * FastBlurUtils.GLOW_WIDTH + midX]);
        assertTrue("Outer top border should remain visibly semi-transparent", outerTopAlpha >= 65 && outerTopAlpha <= 100);
        assertTrue("Outer bottom border should remain visibly semi-transparent", outerBottomAlpha >= 65 && outerBottomAlpha <= 100);
    }

    @Test
    public void cinemaFalloff_respectsBoundsAndMonotonicity() {
        assertEquals(1.0f, FastBlurUtils.cinemaFalloff(0f), 0.0001f);
        assertTrue("Expanded zone should retain >65% alpha at midpoint", FastBlurUtils.cinemaFalloff(0.50f) > 0.65f);
        float endFalloff = FastBlurUtils.cinemaFalloff(1.0f);
        assertTrue("Outer falloff should end at a visibly semi-transparent ~31% level", endFalloff >= 0.28f && endFalloff <= 0.35f);

        float prev = 1.0f;
        for (int i = 1; i <= 20; i++) {
            float d = i / 20.0f;
            float val = FastBlurUtils.cinemaFalloff(d);
            assertTrue("cinemaFalloff must be monotonically non-increasing", val <= prev);
            prev = val;
        }
    }

    @Test
    public void temporalSmoothingAndResetHistory_behaveSmoothly() {
        FastBlurUtils utils = new FastBlurUtils(SAMPLE_W, SAMPLE_H, 4);
        int[] sample = new int[SAMPLE_W * SAMPLE_H];
        int[] glow1 = new int[FastBlurUtils.GLOW_WIDTH * FastBlurUtils.GLOW_HEIGHT];
        int[] glow2 = new int[FastBlurUtils.GLOW_WIDTH * FastBlurUtils.GLOW_HEIGHT];

        for (int i = 0; i < sample.length; i++) {
            sample[i] = 0xFF102030;
        }
        utils.buildEdgeGlowField(sample, glow1);

        // Small step change should be damped by slow EMA alpha
        for (int i = 0; i < sample.length; i++) {
            sample[i] = 0xFF202030;
        }
        utils.buildEdgeGlowField(sample, glow2);

        int sampleIdx = (FastBlurUtils.GLOW_HEIGHT / 2) * FastBlurUtils.GLOW_WIDTH + 18;
        int r1 = red(glow1[sampleIdx]);
        int r2 = red(glow2[sampleIdx]);
        assertTrue("Small luminance shift should be smoothed rather than jumping completely", r2 >= r1);

        // Reset history on black frame drops cleanly to zero
        utils.resetHistory();
        for (int i = 0; i < sample.length; i++) {
            sample[i] = 0xFF000000;
        }
        utils.buildEdgeGlowField(sample, glow2);
        assertEquals(0, red(glow2[sampleIdx]));
        assertEquals(0, green(glow2[sampleIdx]));
        assertEquals(0, blue(glow2[sampleIdx]));
    }
}
