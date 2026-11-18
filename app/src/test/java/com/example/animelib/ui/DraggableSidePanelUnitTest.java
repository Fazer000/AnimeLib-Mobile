package com.example.animelib.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DraggableSidePanelUnitTest {

    private static final float EPS = 0.0001f;

    @Test
    public void rightDragTranslationStartsAtFullWidthWithZeroOffset() {
        float panelWidth = 1080f;
        assertEquals(panelWidth, DraggableSidePanel.computeTranslationXForRightDrag(panelWidth, 0f), EPS);
        assertEquals(panelWidth, DraggableSidePanel.computeTranslationXForRightDrag(panelWidth, -45f), EPS);
    }

    @Test
    public void rightDragTranslationTracksFingerOneToOne() {
        float panelWidth = 960f;
        float tx1 = DraggableSidePanel.computeTranslationXForRightDrag(panelWidth, 12f);
        float tx2 = DraggableSidePanel.computeTranslationXForRightDrag(panelWidth, 13f);
        assertEquals(1f, tx1 - tx2, EPS);
        assertEquals(660f, DraggableSidePanel.computeTranslationXForRightDrag(panelWidth, 300f), EPS);
    }

    @Test
    public void rightDragTranslationClampsAtFullyOpenZero() {
        float panelWidth = 960f;
        assertEquals(0f, DraggableSidePanel.computeTranslationXForRightDrag(panelWidth, 960f), EPS);
        assertEquals(0f, DraggableSidePanel.computeTranslationXForRightDrag(panelWidth, 1500f), EPS);
        assertEquals(0f, DraggableSidePanel.computeTranslationXForRightDrag(0f, 100f), EPS);
    }

    @Test
    public void slideOffsetMapsMonotonicallyFromClosedToOpen() {
        float panelWidth = 1000f;
        assertEquals(1.0f, DraggableSidePanel.computeSlideOffset(1000f, panelWidth), EPS);
        assertEquals(0.75f, DraggableSidePanel.computeSlideOffset(750f, panelWidth), EPS);
        assertEquals(0.0f, DraggableSidePanel.computeSlideOffset(0f, panelWidth), EPS);
        assertEquals(0.0f, DraggableSidePanel.computeSlideOffset(-50f, panelWidth), EPS);
        assertEquals(1.0f, DraggableSidePanel.computeSlideOffset(1200f, panelWidth), EPS);
        assertEquals(1.0f, DraggableSidePanel.computeSlideOffset(50f, 0f), EPS);
    }

    @Test
    public void openReleaseRespectsFlingVelocityAndProgressThreshold() {
        assertTrue(DraggableSidePanel.shouldOpenPanelOnRelease(0.02f, -450f));
        assertFalse(DraggableSidePanel.shouldOpenPanelOnRelease(0.80f, 450f));
        assertFalse(DraggableSidePanel.shouldOpenPanelOnRelease(0.19f, 0f));
        assertTrue(DraggableSidePanel.shouldOpenPanelOnRelease(0.20f, 0f));
        assertTrue(DraggableSidePanel.shouldOpenPanelOnRelease(0.50f, -100f));
    }

    @Test
    public void closeReleaseRespectsFlingVelocityAndCollapseThreshold() {
        assertTrue(DraggableSidePanel.shouldClosePanelOnRelease(0.05f, 450f));
        assertFalse(DraggableSidePanel.shouldClosePanelOnRelease(0.85f, -450f));
        assertFalse(DraggableSidePanel.shouldClosePanelOnRelease(0.30f, 0f));
        assertTrue(DraggableSidePanel.shouldClosePanelOnRelease(0.31f, 0f));
    }
}
