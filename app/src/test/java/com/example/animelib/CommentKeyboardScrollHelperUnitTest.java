package com.example.animelib;

import static org.junit.Assert.assertEquals;

import com.example.animelib.util.CommentKeyboardScrollHelper;

import org.junit.Test;

public class CommentKeyboardScrollHelperUnitTest {

    @Test
    public void computeTargetScrollY_scrollsUpWhenInputIsCoveredByKeyboard() {
        // Screen 2400px, keyboard 900px -> imeTopScreenY = 1500px, margin = 30px (10dp @ 3x)
        // Input currently at bottom Y = 2050px with currentScrollY = 400px -> needs +580px scroll -> 980px
        int targetScrollY = CommentKeyboardScrollHelper.computeTargetScrollY(
                400,
                3000,
                2050,
                1500,
                30
        );
        assertEquals(980, targetScrollY);
    }

    @Test
    public void computeTargetScrollY_alignsInputAboveKeyboardWhenScrolledDeepInComments() {
        // User scrolled deep into comments (currentScrollY = 1400px), so input is higher up (Y = 1050px).
        // Tapping input aligns it right above keyboard at 1500 - 30 = 1470px -> 1400 + (1050 - 1470) = 980px.
        int targetScrollY = CommentKeyboardScrollHelper.computeTargetScrollY(
                1400,
                3000,
                1050,
                1500,
                30
        );
        assertEquals(980, targetScrollY);
    }

    @Test
    public void computeTargetScrollY_clampsWithinValidScrollBounds() {
        int clampedMin = CommentKeyboardScrollHelper.computeTargetScrollY(
                50,
                2000,
                900,
                1600,
                30
        );
        assertEquals(0, clampedMin);

        int clampedMax = CommentKeyboardScrollHelper.computeTargetScrollY(
                1800,
                2000,
                2100,
                1500,
                30
        );
        assertEquals(2000, clampedMax);
    }

    @Test
    public void computePortraitScrollBottomPadding_addsKeyboardAndRestoresNavBarOnClose() {
        int openPadding = CommentKeyboardScrollHelper.computePortraitScrollBottomPadding(
                12,
                880,
                48,
                72
        );
        assertEquals(12 + 880 + 72, openPadding);

        int closedPadding = CommentKeyboardScrollHelper.computePortraitScrollBottomPadding(
                12,
                0,
                48,
                72
        );
        assertEquals(12 + 48, closedPadding);
    }

    @Test
    public void computeEffectiveImeBottom_ignoresNavBarButUsesFallbackWhenFullscreenSuppressesInsets() {
        // Only navigation bar (126px out of 2400px < 16%) -> 0 keyboard height
        assertEquals(0, CommentKeyboardScrollHelper.computeEffectiveImeBottom(
                0,
                2400,
                2400,
                2274
        ));

        // Fallback visible frame detects 860px keyboard even if insets reported 0
        assertEquals(860, CommentKeyboardScrollHelper.computeEffectiveImeBottom(
                0,
                2400,
                2400,
                1540
        ));

        // WindowInsets immediately reports target 900px while visibleFrame is still animating at 2100px
        assertEquals(900, CommentKeyboardScrollHelper.computeEffectiveImeBottom(
                900,
                2400,
                2400,
                2100
        ));
    }
}
