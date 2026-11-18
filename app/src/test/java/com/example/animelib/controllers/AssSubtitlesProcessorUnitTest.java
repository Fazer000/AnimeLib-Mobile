package com.example.animelib.controllers;

import androidx.media3.common.VideoSize;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AssSubtitlesProcessorUnitTest {

    @Test
    public void isAssDrawingPath_detectsExplicitTagsAndRawVectorPaths() {
        assertFalse(AssSubtitlesProcessor.isAssDrawingPath(null));
        assertFalse(AssSubtitlesProcessor.isAssDrawingPath("   "));
        assertFalse(AssSubtitlesProcessor.isAssDrawingPath("Привет, как дела?"));
        assertFalse(AssSubtitlesProcessor.isAssDrawingPath("{\\an8\\pos(640,100)}Надпись на экране"));

        assertTrue(AssSubtitlesProcessor.isAssDrawingPath("{\\p1}m 0 0 l 100 0 100 100 0 100{\\p0}"));
        assertTrue(AssSubtitlesProcessor.isAssDrawingPath("{\\pos(100,200)\\p2}m 10 20 b 30 40 50 60 70 80"));
        assertTrue(AssSubtitlesProcessor.isAssDrawingPath("m 0 0 l 120 0 120 80 0 80 c"));
        assertTrue(AssSubtitlesProcessor.isAssDrawingPath("m -10 -20 l 50 -20 50 40"));
    }

    @Test
    public void parseAssColor_decodesBgrAndAlphaCorrectly() {
        // ASS BGR FF0000 -> RGB (R=00, G=00, B=FF, Alpha=255) = 0xFF0000FF
        assertEquals(Integer.valueOf(0xFF0000FF), AssSubtitlesProcessor.parseAssColor("&HFF0000&"));
        // Pure green 00FF00 -> 0xFF00FF00
        assertEquals(Integer.valueOf(0xFF00FF00), AssSubtitlesProcessor.parseAssColor("00FF00"));
        // Alpha AA=80 (ASS transparency 128 -> opacity 127 = 0x7F), BGR=112233 -> RGB=332211
        assertEquals(Integer.valueOf(0x7F332211), AssSubtitlesProcessor.parseAssColor("&H80112233&"));
        // White &HFFFFFF -> 0xFFFFFFFF
        assertEquals(Integer.valueOf(0xFFFFFFFF), AssSubtitlesProcessor.parseAssColor("FFFFFF"));

        assertNull(AssSubtitlesProcessor.parseAssColor(null));
        assertNull(AssSubtitlesProcessor.parseAssColor(""));
        assertNull(AssSubtitlesProcessor.parseAssColor("not_hex"));
    }

    @Test
    public void convertLegacyAlignment_mapsSsaValuesToNumpad() {
        assertEquals(1, AssSubtitlesProcessor.convertLegacyAlignment(1));
        assertEquals(2, AssSubtitlesProcessor.convertLegacyAlignment(2));
        assertEquals(3, AssSubtitlesProcessor.convertLegacyAlignment(3));
        assertEquals(7, AssSubtitlesProcessor.convertLegacyAlignment(5));
        assertEquals(8, AssSubtitlesProcessor.convertLegacyAlignment(6));
        assertEquals(9, AssSubtitlesProcessor.convertLegacyAlignment(7));
        assertEquals(4, AssSubtitlesProcessor.convertLegacyAlignment(9));
        assertEquals(5, AssSubtitlesProcessor.convertLegacyAlignment(10));
        assertEquals(6, AssSubtitlesProcessor.convertLegacyAlignment(11));
    }

    @Test
    public void isNumeric_identifiesNumbersAndRejectsCommands() {
        assertTrue(AssSubtitlesProcessor.isNumeric("0"));
        assertTrue(AssSubtitlesProcessor.isNumeric("-42.5"));
        assertTrue(AssSubtitlesProcessor.isNumeric(".75"));
        assertFalse(AssSubtitlesProcessor.isNumeric(null));
        assertFalse(AssSubtitlesProcessor.isNumeric(""));
        assertFalse(AssSubtitlesProcessor.isNumeric("m"));
        assertFalse(AssSubtitlesProcessor.isNumeric("l"));
    }

    @Test
    public void getScriptResolution_respectsPlayResAndCoordinateHeuristics() {
        assertEquals(1920.0f, AssSubtitlesProcessor.getScriptResX(300f, 200f, "", 1920, null), 0.01f);
        assertEquals(1080.0f, AssSubtitlesProcessor.getScriptResY(300f, 200f, "", 1080, null), 0.01f);

        // When PlayRes is unset and video is 1080p, coordinates <= 1280x720 but > 848x480 infer 1280x720 authoring
        VideoSize fhdVideo = new VideoSize(1920, 1080);
        assertEquals(1280.0f, AssSubtitlesProcessor.getScriptResX(1000f, 600f, "", 0, fhdVideo), 0.01f);
        assertEquals(720.0f, AssSubtitlesProcessor.getScriptResY(1000f, 600f, "", 0, fhdVideo), 0.01f);

        // Coordinates > 1280 infer 1920x1080
        assertEquals(1920.0f, AssSubtitlesProcessor.getScriptResX(1500f, 900f, "", 0, null), 0.01f);
        assertEquals(1080.0f, AssSubtitlesProcessor.getScriptResY(1500f, 900f, "", 0, null), 0.01f);
    }
}
