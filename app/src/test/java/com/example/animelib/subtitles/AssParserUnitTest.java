package com.example.animelib.subtitles;

import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.settings.SubtitlesBottomSheet;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AssParserUnitTest {

    @Test
    public void parse_scriptInfoStylesColorsAndLayers() {
        String ass = "\uFEFF[Script Info]\n"
                + "PlayResX: 1920\n"
                + "PlayResY: 1080\n"
                + "ScaledBorderAndShadow: yes\n"
                + "\n"
                + "[V4+ Styles]\n"
                + "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
                + "Style: Default,Open Sans,62,&H00FFFFFF,&H0000FFFF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,1.5,2,40,40,35,1\n"
                + "Style: Sign,Arial,48,&H000080FF,&H000000FF,&H00201000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,7,10,10,10,1\n"
                + "\n"
                + "[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 1,0:00:01.00,0:00:05.00,Default,,0,0,0,,Обычный {\\1c&H0000FF&}красный{\\r\\c&H00FF00&} зеленый\n"
                + "Dialogue: 0,0:00:00.50,0:00:10.00,Sign,,0,0,0,,{\\an7\\pos(100,200)\\1a&H80&\\1c&HFF0000&\\bord0\\p1}m 0 0 l 200 0 200 80 0 80{\\p0}\n";

        AssScript script = AssParser.parse(ass);
        assertEquals(1920f, script.playResX, 0.01f);
        assertEquals(1080f, script.playResY, 0.01f);
        assertTrue(script.scaledBorderAndShadow);
        assertEquals(2, script.events.size());

        // Verify Sign style color (&H000080FF -> BGR 00 80 FF -> RGB FF 80 00, alpha FF -> 0xFFFF8000)
        AssScript.AssStyle signStyle = script.styles.get("sign");
        assertNotNull(signStyle);
        assertEquals(0xFFFF8000, signStyle.primaryColor);
        assertEquals(7, signStyle.alignment);

        // Query active events at 2.00s -> Layer 0 vector MUST come before Layer 1 text
        List<AssScript.AssEvent> active = new ArrayList<>();
        script.getActiveEvents(2000L, active);
        assertEquals(2, active.size());

        AssScript.AssEvent vecEvent = active.get(0);
        assertEquals(0, vecEvent.layer);
        assertTrue(vecEvent.hasVectorDrawing);
        assertTrue(vecEvent.hasPos);
        assertEquals(100f, vecEvent.posX, 0.01f);
        assertEquals(200f, vecEvent.posY, 0.01f);
        assertEquals(1, vecEvent.segments.size());
        AssScript.AssSegment vecSeg = vecEvent.segments.get(0);
        assertTrue(vecSeg.isDrawing);
        assertEquals(1, vecSeg.style.pLevel);
        // \1a&H80& (opacity 0x7F) + \1c&HFF0000& (BGR FF 00 00 -> pure blue 0000FF) => 0x7F0000FF
        assertEquals(0x7F0000FF, vecSeg.style.primaryColor);
        assertEquals(0f, vecSeg.style.borderX, 0.01f);

        AssScript.AssEvent textEvent = active.get(1);
        assertEquals(1, textEvent.layer);
        assertFalse(textEvent.hasVectorDrawing);
        assertEquals(3, textEvent.segments.size());
        // Segment 0: Default white
        assertEquals("Обычный ", textEvent.segments.get(0).content);
        assertEquals(0xFFFFFFFF, textEvent.segments.get(0).style.primaryColor);
        // Segment 1: \1c&H0000FF& -> pure red 0xFFFF0000
        assertEquals("красный", textEvent.segments.get(1).content);
        assertEquals(0xFFFF0000, textEvent.segments.get(1).style.primaryColor);
        // Segment 2: \r followed by \c&H00FF00& -> pure green 0xFF00FF00 (NOT wiped by \r!)
        assertEquals(" зеленый", textEvent.segments.get(2).content);
        assertEquals(0xFF00FF00, textEvent.segments.get(2).style.primaryColor);
    }

    @Test
    public void parse_longRunningBackgroundEventRemainsVisibleAtEnd() {
        String ass = "[Script Info]\nPlayResX: 1280\nPlayResY: 720\n"
                + "[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:01.00,0:01:00.00,Default,,0,0,0,,Long Sign\n"
                + "Dialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,Short 1\n"
                + "Dialogue: 0,0:00:30.00,0:00:32.00,Default,,0,0,0,,Short 2\n";

        AssScript script = AssParser.parse(ass);
        List<AssScript.AssEvent> active = new ArrayList<>();
        script.getActiveEvents(45_000L, active);
        assertEquals(1, active.size());
        assertEquals("Long Sign", active.get(0).segments.get(0).content);
    }

    @Test
    public void parse_moveFadeClipAndTransformTags() {
        String ass = "[Script Info]\nPlayResX: 1920\nPlayResY: 1080\n"
                + "[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:00.00,0:00:04.00,Default,,0,0,0,,"
                + "{\\move(100,200,500,600,0,2000)\\fad(500,500)\\clip(10,20,300,400)\\t(0,2000,\\1c&H0000FF&\\fscx200)}Animated\n";

        AssScript script = AssParser.parse(ass);
        assertEquals(1, script.events.size());
        AssScript.AssEvent ev = script.events.get(0);
        assertTrue(ev.hasMove);
        assertTrue(ev.hasAnimations);
        assertEquals(300f, ev.computePosXAt(1000L), 0.1f);
        assertEquals(400f, ev.computePosYAt(1000L), 0.1f);
        assertEquals(0.5f, ev.computeFadeAlphaFactor(250L), 0.01f);
        assertEquals(1.0f, ev.computeFadeAlphaFactor(1500L), 0.01f);
        assertEquals(0.5f, ev.computeFadeAlphaFactor(3750L), 0.01f);
        assertNotNull(ev.clip);
        assertFalse(ev.clip.isVector);
        assertFalse(ev.clip.inverted);
        assertEquals(10f, ev.clip.x1, 0.01f);
        assertEquals(400f, ev.clip.y2, 0.01f);

        AssScript.SegmentStyle midStyle = ev.segments.get(0).resolveStyleAt(1000L, 4000L);
        assertEquals(150f, midStyle.scaleX, 0.5f);
    }

    @Test
    public void subtitlesBottomSheet_locksCustomizationForAssAndUnlocksForVtt() {
        EpisodeResponse.SubtitleData assSub = new EpisodeResponse.SubtitleData();
        assSub.setFormat("ass");
        assSub.setSrc("https://example.com/sub.ass");

        EpisodeResponse.SubtitleData vttSub = new EpisodeResponse.SubtitleData();
        vttSub.setFormat("vtt");
        vttSub.setSrc("https://example.com/sub.vtt");

        assertTrue(SubtitlesBottomSheet.isAssCustomizationLocked("ass", Collections.singletonList(vttSub)));
        assertTrue(SubtitlesBottomSheet.isAssCustomizationLocked("ssa", Collections.singletonList(vttSub)));
        assertFalse(SubtitlesBottomSheet.isAssCustomizationLocked("vtt", Arrays.asList(assSub, vttSub)));
        assertFalse(SubtitlesBottomSheet.isAssCustomizationLocked("srt", Arrays.asList(assSub, vttSub)));
        assertTrue(SubtitlesBottomSheet.isAssCustomizationLocked("auto", Arrays.asList(assSub, vttSub)));
        assertFalse(SubtitlesBottomSheet.isAssCustomizationLocked("auto", Collections.singletonList(vttSub)));
    }

    @Test
    public void vectorGeometry_alignmentAndNegativeCoordinatesMatchLibass() {
        // Zero-origin box [0..100, 0..80]: bWidth=100, bHeight=80
        assertEquals(500f, AssVectorGeometry.computeSingleOriginX(500f, 0f, 100f, 7), 0.01f);
        assertEquals(450f, AssVectorGeometry.computeSingleOriginX(500f, 0f, 100f, 8), 0.01f);
        assertEquals(400f, AssVectorGeometry.computeSingleOriginX(500f, 0f, 100f, 9), 0.01f);
        assertEquals(300f, AssVectorGeometry.computeSingleOriginY(300f, 0f, 80f, 7, 0f, 1f, 1f), 0.01f);
        assertEquals(260f, AssVectorGeometry.computeSingleOriginY(300f, 0f, 80f, 5, 0f, 1f, 1f), 0.01f);
        assertEquals(220f, AssVectorGeometry.computeSingleOriginY(300f, 0f, 80f, 3, 0f, 1f, 1f), 0.01f);

        // Negative-coordinate drawing [-50..50, -80..20]: bWidth=100, bHeight=100
        // libass/VSFilter keeps (0,0) anchored by advance=bWidth, asc=bHeight (NOT bRight/bBottom)
        assertEquals(500f, AssVectorGeometry.computeSingleOriginX(500f, -50f, 50f, 7), 0.01f);
        assertEquals(450f, AssVectorGeometry.computeSingleOriginX(500f, -50f, 50f, 5), 0.01f);
        assertEquals(400f, AssVectorGeometry.computeSingleOriginX(500f, -50f, 50f, 3), 0.01f);
        assertEquals(700f, AssVectorGeometry.computeSingleOriginY(700f, -80f, 20f, 7, 0f, 1f, 1f), 0.01f);
        assertEquals(650f, AssVectorGeometry.computeSingleOriginY(700f, -80f, 20f, 5, 0f, 1f, 1f), 0.01f);
        assertEquals(600f, AssVectorGeometry.computeSingleOriginY(700f, -80f, 20f, 3, 0f, 1f, 1f), 0.01f);

        // Positive-offset drawing [100..200, 50..150]: bWidth=100, bHeight=100
        assertEquals(0f, AssVectorGeometry.computeSingleOriginX(0f, 100f, 200f, 7), 0.01f);
        assertEquals(0f, AssVectorGeometry.computeSingleOriginY(0f, 50f, 150f, 7, 0f, 1f, 1f), 0.01f);
    }

    @Test
    public void vectorGeometry_pboSignAndMargins() {
        // Negative \pbo shifts UP (-Y), positive \pbo shifts DOWN (+Y)
        assertEquals(-30f, AssVectorGeometry.computePboOffsetY(-30f, 1f, 1f), 0.01f);
        assertEquals(40f, AssVectorGeometry.computePboOffsetY(40f, 1f, 1f), 0.01f);
        // With \p2 (scale 0.5) and \fscy200 (2.0): -30 * 0.5 * 2.0 = -30
        assertEquals(-30f, AssVectorGeometry.computePboOffsetY(-30f, 0.5f, 2f), 0.01f);
        // Combined with \an3 at anchorY=700, bHeight=100, \pbo-25 -> 700 - 100 - 25 = 575
        assertEquals(575f, AssVectorGeometry.computeSingleOriginY(700f, 0f, 100f, 3, -25f, 1f, 1f), 0.01f);

        // Asymmetric MarginL=100, MarginR=20 on 1280px width -> centered at 100 + (1280 - 120) / 2 = 680
        assertEquals(100f, AssVectorGeometry.computeUnpositionedAnchorX(1280f, 100, 20, 1), 0.01f);
        assertEquals(680f, AssVectorGeometry.computeUnpositionedAnchorX(1280f, 100, 20, 2), 0.01f);
        assertEquals(1260f, AssVectorGeometry.computeUnpositionedAnchorX(1280f, 100, 20, 3), 0.01f);
        assertEquals(30f, AssVectorGeometry.computeUnpositionedAnchorY(720f, 30, 8), 0.01f);
        assertEquals(360f, AssVectorGeometry.computeUnpositionedAnchorY(720f, 30, 5), 0.01f);
        assertEquals(690f, AssVectorGeometry.computeUnpositionedAnchorY(720f, 30, 2), 0.01f);
    }

    @Test
    public void parse_mixedVectorAndText_pboZeroScaleAndDefaultPlayRes() {
        String ass = "[Script Info]\n"
                + "[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,"
                + "{\\an3 comment\\pbo-24\\p1}m 0 0 l 20 0 20 20 0 20{\\p0} Ждём тебя летом\n"
                + "Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,{\\fscx0\\fscy50}Hidden\n";

        AssScript script = AssParser.parse(ass);
        // Official ASS fallback when both PlayResX and PlayResY are omitted
        assertEquals(384f, script.resolvePlayResX(1920, 1080), 0.01f);
        assertEquals(288f, script.resolvePlayResY(1920, 1080), 0.01f);
        assertEquals(2, script.events.size());

        AssScript.AssEvent mixed = script.events.get(0);
        assertTrue(mixed.hasVectorDrawing);
        assertTrue(mixed.hasTextContent);
        assertEquals(3, mixed.alignment);
        assertEquals(2, mixed.segments.size());
        assertTrue(mixed.segments.get(0).isDrawing);
        assertEquals(-24f, mixed.segments.get(0).style.pbo, 0.01f);
        assertFalse(mixed.segments.get(1).isDrawing);
        assertEquals(" Ждём тебя летом", mixed.segments.get(1).content);

        AssScript.AssEvent zeroScale = script.events.get(1);
        assertEquals(0f, zeroScale.segments.get(0).style.scaleX, 0.01f);
        assertEquals(50f, zeroScale.segments.get(0).style.scaleY, 0.01f);
    }
}
