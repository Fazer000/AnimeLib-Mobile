package com.example.animelib;

import org.junit.Test;

import static org.junit.Assert.*;

public class FranchiseMapScaleUnitTest {

    private static final float MIN_SCALE = 0.30f;
    private static final float MAX_SCALE = 2.20f;

    private float applyScale(float currentScale, float newScale) {
        if (Float.isNaN(newScale) || Float.isInfinite(newScale) || newScale <= 0f) {
            return currentScale; // Ignored, keeps current scale
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
    }

    @Test
    public void testScaleBounds() {
        assertEquals(0.70f, applyScale(0.70f, 0.70f), 0.001f);
        assertEquals(MIN_SCALE, applyScale(0.70f, 0.10f), 0.001f);
        assertEquals(MAX_SCALE, applyScale(0.70f, 3.50f), 0.001f);
        assertEquals(0.70f, applyScale(0.70f, -1.0f), 0.001f);
    }

    @Test
    public void testScaleNaNAndInfinityProtection() {
        assertEquals(0.70f, applyScale(0.70f, Float.NaN), 0.001f);
        assertEquals(0.70f, applyScale(0.70f, Float.POSITIVE_INFINITY), 0.001f);
        assertEquals(0.70f, applyScale(0.70f, Float.NEGATIVE_INFINITY), 0.001f);
    }

    @Test
    public void testZoomPercentageFormatting() {
        float scale1 = 0.70f;
        assertEquals("70%", Math.round(scale1 * 100) + "%");

        float scale2 = 1.00f;
        assertEquals("100%", Math.round(scale2 * 100) + "%");

        float scale3 = 0.30f;
        assertEquals("30%", Math.round(scale3 * 100) + "%");

        float scale4 = 2.20f;
        assertEquals("220%", Math.round(scale4 * 100) + "%");
    }

    @Test
    public void testZoomStepCalculations() {
        float currentScale = 0.70f;
        float zoomInScale = applyScale(currentScale, currentScale + 0.15f);
        assertEquals(0.85f, zoomInScale, 0.001f);

        float zoomOutScale = applyScale(currentScale, currentScale - 0.15f);
        assertEquals(0.55f, zoomOutScale, 0.001f);
    }
}
