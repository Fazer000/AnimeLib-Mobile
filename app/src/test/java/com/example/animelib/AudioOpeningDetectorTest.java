package com.example.animelib;

import com.example.animelib.audio.AudioFingerprintEngine;
import com.example.animelib.util.HlsPlaylistParser;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Юнит-тесты для спектрального движка поиска опенингов по аудиодорожке
 * и парсинга длительности #EXTINF в HLS-плейлистах.
 */
public class AudioOpeningDetectorTest {

    private static final int SRC_RATE = 16000;

    /**
     * Генерирует реалистичный многогармонический «музыкальный» сигнал (аккорды + ритмика + мелодия),
     * детерминированный по seed.
     */
    private static float[] synthesizeMusicalTrack(int durationSec, long seed, float gain) {
        int totalSamples = durationSec * SRC_RATE;
        float[] pcm = new float[totalSamples];
        Random rng = new Random(seed);

        int chordsCount = Math.max(1, durationSec / 2);
        double[] rootFreqs = new double[chordsCount];
        double[] melodyFreqs = new double[chordsCount * 4];
        for (int i = 0; i < chordsCount; i++) {
            rootFreqs[i] = 160.0 + rng.nextInt(14) * 28.0;
        }
        for (int i = 0; i < melodyFreqs.length; i++) {
            melodyFreqs[i] = 420.0 + rng.nextInt(18) * 95.0;
        }

        for (int n = 0; n < totalSamples; n++) {
            double t = (double) n / SRC_RATE;
            int chordIdx = Math.min(chordsCount - 1, (int) (t / 2.0));
            int noteIdx = Math.min(melodyFreqs.length - 1, (int) (t * 2.0));
            double f1 = rootFreqs[chordIdx];
            double f2 = f1 * 1.25;
            double f3 = f1 * 1.5;
            double fm = melodyFreqs[noteIdx];
            double beatEnv = 0.55 + 0.45 * Math.sin(2.0 * Math.PI * 2.2 * t);

            double sample = 0.30 * Math.sin(2.0 * Math.PI * f1 * t)
                    + 0.22 * Math.sin(2.0 * Math.PI * f2 * t)
                    + 0.18 * Math.sin(2.0 * Math.PI * f3 * t)
                    + 0.25 * beatEnv * Math.sin(2.0 * Math.PI * fm * t)
                    + 0.10 * Math.sin(2.0 * Math.PI * (fm * 2.0) * t);
            pcm[n] = (float) (sample * gain);
        }
        return pcm;
    }

    /**
     * Склеивает эпизод: [до опенинга (диалог)] + [опенинг с шумом и другой громкостью] + [после опенинга].
     */
    private static float[] buildEpisodeAudio(int totalSec, int opStartSec, float[] openingPcm,
                                             long dialogueSeed, float opGain, float noiseLevel) {
        float[] ep = synthesizeMusicalTrack(totalSec, dialogueSeed, 0.5f);
        Random noiseRng = new Random(dialogueSeed ^ 0x55AA55AAL);
        if (openingPcm != null && opStartSec >= 0) {
            int startSample = opStartSec * SRC_RATE;
            for (int i = 0; i < openingPcm.length && (startSample + i) < ep.length; i++) {
                float noise = (float) ((noiseRng.nextDouble() * 2.0 - 1.0) * noiseLevel);
                ep[startSample + i] = Math.max(-1f, Math.min(1f, openingPcm[i] * opGain + noise));
            }
        }
        return ep;
    }

    private static byte[] fingerprintRange(float[] episodePcm, int fromSec, int toSec) {
        int start = Math.max(0, fromSec * SRC_RATE);
        int end = Math.min(episodePcm.length, toSec * SRC_RATE);
        AudioFingerprintEngine.StreamingFingerprintBuilder builder =
                new AudioFingerprintEngine.StreamingFingerprintBuilder(SRC_RATE, (toSec - fromSec) * 4 + 16);
        builder.feedMonoFloat(episodePcm, start, end - start);
        return builder.copyFramesData();
    }

    @Test
    public void testDetectOpeningWithLateSkipAndExactBoundaryTrimming() {
        // Опенинг 90 секунд
        float[] opening = synthesizeMusicalTrack(90, 1001L, 0.8f);

        // Эпизод 1: опенинг на 30..120с. Пользователь нажал +85с чуть поздно: на 34..119с.
        // Расширенное окно шаблона: [24..129с] (захватывает 6с диалога ДО и 9с диалога ПОСЛЕ)
        float[] ep1 = buildEpisodeAudio(200, 30, opening, 2001L, 1.0f, 0.01f);
        byte[] tplData = fingerprintRange(ep1, 24, 129);
        int tplFrames = tplData.length / AudioFingerprintEngine.NUM_BANDS;
        AudioFingerprintEngine.Template template = new AudioFingerprintEngine.Template(
                1L, tplData, tplFrames,
                (34 - 24) * AudioFingerprintEngine.FRAMES_PER_SECOND,
                (119 - 24) * AudioFingerprintEngine.FRAMES_PER_SECOND,
                85
        );

        // Эпизод 2: тот же опенинг на 100..190с, но с другой громкостью (0.45x) и добавочным шумом
        float[] ep2 = buildEpisodeAudio(260, 100, opening, 3001L, 0.45f, 0.06f);
        byte[] ep2Data = fingerprintRange(ep2, 0, 260);
        int ep2Frames = ep2Data.length / AudioFingerprintEngine.NUM_BANDS;

        AudioFingerprintEngine.MatchResult match =
                AudioFingerprintEngine.findMatch(template, ep2Data, ep2Frames, true);

        assertNotNull("Expected opening to be detected in Episode 2", match);
        assertTrue("Expected start near 100s, got " + match.fromSec, Math.abs(match.fromSec - 100) <= 2);
        assertTrue("Expected end near 190s, got " + match.toSec, Math.abs(match.toSec - 190) <= 2);
        assertTrue("Expected high confidence, got " + match.confidence, match.confidence >= 0.60f);
    }

    @Test
    public void testDetectOpeningAtZeroWithNegativeLag() {
        float[] opening = synthesizeMusicalTrack(90, 4001L, 0.75f);

        // Эпизод 1: опенинг после долгого пролога на 90..180с. Шаблон: [80..190с]
        float[] ep1 = buildEpisodeAudio(220, 90, opening, 5001L, 1.0f, 0.01f);
        byte[] tplData = fingerprintRange(ep1, 80, 190);
        int tplFrames = tplData.length / AudioFingerprintEngine.NUM_BANDS;
        AudioFingerprintEngine.Template template = new AudioFingerprintEngine.Template(
                2L, tplData, tplFrames,
                10 * AudioFingerprintEngine.FRAMES_PER_SECOND,
                95 * AudioFingerprintEngine.FRAMES_PER_SECOND,
                85
        );

        // Эпизод 2: опенинг начинается прямо с 0:00 (0..90с)! Требует отрицательного лага (-40 кадров)
        float[] ep2 = buildEpisodeAudio(200, 0, opening, 6001L, 0.7f, 0.05f);
        byte[] ep2Data = fingerprintRange(ep2, 0, 200);
        int ep2Frames = ep2Data.length / AudioFingerprintEngine.NUM_BANDS;

        AudioFingerprintEngine.MatchResult match =
                AudioFingerprintEngine.findMatch(template, ep2Data, ep2Frames, true);

        assertNotNull("Expected opening at 0:00 to be detected via negative lag", match);
        assertTrue("Expected start near 0s, got " + match.fromSec, match.fromSec <= 2);
        assertTrue("Expected end near 90s, got " + match.toSec, Math.abs(match.toSec - 90) <= 2);
    }

    @Test
    public void testProgressiveRightEdgeReturnsProvisionalThenRefinesExact() {
        float[] opening = synthesizeMusicalTrack(90, 7001L, 0.8f);

        float[] ep1 = buildEpisodeAudio(200, 20, opening, 7002L, 1.0f, 0.01f);
        byte[] tplData = fingerprintRange(ep1, 10, 120);
        int tplFrames = tplData.length / AudioFingerprintEngine.NUM_BANDS;
        AudioFingerprintEngine.Template template = new AudioFingerprintEngine.Template(
                3L, tplData, tplFrames,
                10 * AudioFingerprintEngine.FRAMES_PER_SECOND,
                95 * AudioFingerprintEngine.FRAMES_PER_SECOND,
                85
        );

        // В Эпизоде 2 опенинг на 75..165с.
        float[] ep2 = buildEpisodeAudio(220, 75, opening, 7003L, 0.8f, 0.03f);

        // Проход 1: декодировано 0..120с (опенинг идёт и упирается в правый край -> Stage 1 provisional, не обрезанный до 120с!)
        byte[] pass1Data = fingerprintRange(ep2, 0, 120);
        AudioFingerprintEngine.MatchResult pass1 = AudioFingerprintEngine.findMatch(
                template, pass1Data, pass1Data.length / AudioFingerprintEngine.NUM_BANDS, false);
        assertNotNull("Stage 1 should return immediate provisional opening match", pass1);
        assertTrue("Pass 1 must be marked provisional so background refinement continues", pass1.isProvisional);
        assertTrue("Expected provisional start near 75s, got " + pass1.fromSec, Math.abs(pass1.fromSec - 75) <= 2);
        assertTrue("Expected provisional end estimated near 160..165s (not truncated to 120s), got " + pass1.toSec,
                pass1.toSec >= 155 && pass1.toSec <= 168);

        // Проход 2: декодировано 0..190с (опенинг целиком внутри -> Stage 2 exact, isProvisional = false)
        byte[] pass2Data = fingerprintRange(ep2, 0, 190);
        AudioFingerprintEngine.MatchResult pass2 = AudioFingerprintEngine.findMatch(
                template, pass2Data, pass2Data.length / AudioFingerprintEngine.NUM_BANDS, false);
        assertNotNull("Progressive pass 2 should detect full opening and stop early", pass2);
        assertTrue("Pass 2 must be final (not provisional)", !pass2.isProvisional);
        assertTrue("Expected start near 75s, got " + pass2.fromSec, Math.abs(pass2.fromSec - 75) <= 2);
        assertTrue("Expected end near 165s, got " + pass2.toSec, Math.abs(pass2.toSec - 165) <= 2);
    }

    @Test
    public void testInstantEarlyDetectionAtEpisodeStart() {
        float[] opening = synthesizeMusicalTrack(90, 6101L, 0.8f);
        float[] otherOpening = synthesizeMusicalTrack(90, 6199L, 0.8f);

        // Эпизод 1: опенинг после пролога (60..150с), пользователь нажал +85с (60..145с)
        float[] ep1 = buildEpisodeAudio(210, 60, opening, 6102L, 1.0f, 0.01f);
        byte[] tplData = fingerprintRange(ep1, 50, 155);
        int tplFrames = tplData.length / AudioFingerprintEngine.NUM_BANDS;
        AudioFingerprintEngine.Template template = new AudioFingerprintEngine.Template(
                5L, tplData, tplFrames,
                10 * AudioFingerprintEngine.FRAMES_PER_SECOND,
                95 * AudioFingerprintEngine.FRAMES_PER_SECOND,
                85
        );

        // Эпизод 2: опенинг сразу с 0:00 (0..90с) — декодировано ТОЛЬКО 28 секунд!
        float[] ep2AtZero = buildEpisodeAudio(160, 0, opening, 6103L, 0.65f, 0.04f);
        byte[] first28sZero = fingerprintRange(ep2AtZero, 0, 28);
        AudioFingerprintEngine.MatchResult earlyZero = AudioFingerprintEngine.findMatch(
                template, first28sZero, first28sZero.length / AudioFingerprintEngine.NUM_BANDS, false);
        assertNotNull("Opening at 0:00 should be early-detected within first 28s", earlyZero);
        assertTrue("Early match at 28s must be provisional", earlyZero.isProvisional);
        assertTrue("Expected start near 0s, got " + earlyZero.fromSec, earlyZero.fromSec <= 2);
        assertTrue("Expected estimated end near 85..90s, got " + earlyZero.toSec,
                earlyZero.toSec >= 80 && earlyZero.toSec <= 92);

        // Эпизод 3: опенинг почти сразу на 0:06 (6..96с) — декодировано ТОЛЬКО 28 секунд!
        float[] ep3AtSix = buildEpisodeAudio(160, 6, opening, 6104L, 0.7f, 0.04f);
        byte[] first28sSix = fingerprintRange(ep3AtSix, 0, 28);
        AudioFingerprintEngine.MatchResult earlySix = AudioFingerprintEngine.findMatch(
                template, first28sSix, first28sSix.length / AudioFingerprintEngine.NUM_BANDS, false);
        assertNotNull("Opening at 0:06 should be early-detected within first 28s", earlySix);
        assertTrue("Early match at 28s must be provisional", earlySix.isProvisional);
        assertTrue("Expected start near 6s, got " + earlySix.fromSec, Math.abs(earlySix.fromSec - 6) <= 2);
        assertTrue("Expected estimated end near 91..96s, got " + earlySix.toSec,
                earlySix.toSec >= 86 && earlySix.toSec <= 98);

        // Эпизод 13: другой опенинг (OP2) сразу с 0:00 — НЕ должно быть ложного раннего срабатывания на 28с!
        float[] ep13OtherOp = buildEpisodeAudio(160, 0, otherOpening, 6105L, 0.8f, 0.02f);
        byte[] first28sOther = fingerprintRange(ep13OtherOp, 0, 28);
        AudioFingerprintEngine.MatchResult falseMatch = AudioFingerprintEngine.findMatch(
                template, first28sOther, first28sOther.length / AudioFingerprintEngine.NUM_BANDS, false);
        assertNull("Different OP2 at 0:00 must NOT trigger early provisional false positive", falseMatch);
    }

    @Test
    public void testNoFalsePositiveWhenOpeningChanged() {
        float[] opening1 = synthesizeMusicalTrack(90, 8001L, 0.8f);
        float[] opening2 = synthesizeMusicalTrack(90, 9999L, 0.8f);

        float[] ep1 = buildEpisodeAudio(200, 30, opening1, 8002L, 1.0f, 0.01f);
        byte[] tplData = fingerprintRange(ep1, 20, 130);
        int tplFrames = tplData.length / AudioFingerprintEngine.NUM_BANDS;
        AudioFingerprintEngine.Template template = new AudioFingerprintEngine.Template(
                4L, tplData, tplFrames,
                10 * AudioFingerprintEngine.FRAMES_PER_SECOND,
                95 * AudioFingerprintEngine.FRAMES_PER_SECOND,
                85
        );

        // Эпизод 13: совсем другой опенинг (OP2)
        float[] ep13 = buildEpisodeAudio(240, 40, opening2, 8003L, 0.8f, 0.02f);
        byte[] ep13Data = fingerprintRange(ep13, 0, 240);

        AudioFingerprintEngine.MatchResult match = AudioFingerprintEngine.findMatch(
                template, ep13Data, ep13Data.length / AudioFingerprintEngine.NUM_BANDS, true);
        assertNull("Different opening song must NOT produce a false positive", match);
    }

    @Test
    public void testSparseProbeAndDualEdgeRefinement() {
        float[] opening = synthesizeMusicalTrack(90, 5101L, 0.8f);
        float[] otherOpening = synthesizeMusicalTrack(90, 5199L, 0.8f);

        // Эпизод 1: опенинг на 40..130с. Пользователь нажал +85с поздно: на 44..129с.
        // Расширенный шаблон: [34..139с] (захватывает диалог до и после)
        float[] ep1 = buildEpisodeAudio(200, 40, opening, 5102L, 1.0f, 0.01f);
        byte[] tplData = fingerprintRange(ep1, 34, 139);
        int tplFrames = tplData.length / AudioFingerprintEngine.NUM_BANDS;
        AudioFingerprintEngine.Template template = new AudioFingerprintEngine.Template(
                10L, tplData, tplFrames,
                (44 - 34) * AudioFingerprintEngine.FRAMES_PER_SECOND,
                (129 - 34) * AudioFingerprintEngine.FRAMES_PER_SECOND,
                85
        );

        // 1) Эпизод 2: опенинг сразу с 0:00 (0..90с) -> должен пойматься НА ПЕРВОМ ЖЕ 18с ЗОНДЕ (0..18с)!
        float[] ep2AtZero = buildEpisodeAudio(200, 0, opening, 5103L, 0.6f, 0.05f);
        byte[] probe0 = fingerprintRange(ep2AtZero, 0, 18);
        AudioFingerprintEngine.MatchResult hit0 = AudioFingerprintEngine.findProbeMatch(
                template, probe0, probe0.length / AudioFingerprintEngine.NUM_BANDS, 0.0);
        assertNotNull("Opening at 0:00 must be found on Probe 0 (0..18s)", hit0);
        assertTrue("Expected estFrom near 0..4s, got " + hit0.fromSec, hit0.fromSec <= 6);
        assertTrue("Expected estTo near 85..92s, got " + hit0.toSec, hit0.toSec >= 82 && hit0.toSec <= 94);

        // Уточнение краёв: старт из Probe 0, конец из короткого окна [75..104с]
        byte[] endWin0 = fingerprintRange(ep2AtZero, 75, 104);
        int[] refined0 = AudioFingerprintEngine.refineEdgesAtLag(
                template, hit0.bestLagFrames, hit0.fromSec, hit0.toSec,
                probe0, 0, probe0.length / AudioFingerprintEngine.NUM_BANDS,
                endWin0, 75 * AudioFingerprintEngine.FRAMES_PER_SECOND, endWin0.length / AudioFingerprintEngine.NUM_BANDS
        );
        assertTrue("Refined start at 0:00 expected <= 2s, got " + refined0[0], refined0[0] <= 2);
        assertTrue("Refined end expected near 90s, got " + refined0[1], Math.abs(refined0[1] - 90) <= 2);

        // 2) Эпизод 3: опенинг после пролога на 135..225с -> зонды 0, 44, 88 промахиваются, зонд 132..150с ПОПАДАЕТ!
        float[] ep3ColdOpen = buildEpisodeAudio(300, 135, opening, 5104L, 0.5f, 0.05f);
        for (int missStart : new int[]{0, 44, 88}) {
            byte[] missProbe = fingerprintRange(ep3ColdOpen, missStart, missStart + 18);
            AudioFingerprintEngine.MatchResult miss = AudioFingerprintEngine.findProbeMatch(
                    template, missProbe, missProbe.length / AudioFingerprintEngine.NUM_BANDS, missStart);
            assertNull("Dialogue probe at " + missStart + "s must NOT match", miss);
        }

        byte[] hitProbe = fingerprintRange(ep3ColdOpen, 132, 150);
        AudioFingerprintEngine.MatchResult hit3 = AudioFingerprintEngine.findProbeMatch(
                template, hitProbe, hitProbe.length / AudioFingerprintEngine.NUM_BANDS, 132.0);
        assertNotNull("Sparse probe at 132..150s must detect opening at 135..225s", hit3);

        // Двустороннее уточнение краёв (убирает +4с опоздания из Эпизода 1 и даёт точные 135..225с!)
        int sFrom = Math.max(0, hit3.fromSec - 12);
        int sTo = hit3.fromSec + 12;
        byte[] startWin3 = fingerprintRange(ep3ColdOpen, sFrom, sTo);
        int eFrom = Math.max(0, hit3.toSec - 14);
        int eTo = hit3.toSec + 14;
        byte[] endWin3 = fingerprintRange(ep3ColdOpen, eFrom, eTo);

        int[] refined3 = AudioFingerprintEngine.refineEdgesAtLag(
                template, hit3.bestLagFrames, hit3.fromSec, hit3.toSec,
                startWin3, sFrom * AudioFingerprintEngine.FRAMES_PER_SECOND, startWin3.length / AudioFingerprintEngine.NUM_BANDS,
                endWin3, eFrom * AudioFingerprintEngine.FRAMES_PER_SECOND, endWin3.length / AudioFingerprintEngine.NUM_BANDS
        );
        assertTrue("Refined start expected near 135s, got " + refined3[0], Math.abs(refined3[0] - 135) <= 2);
        assertTrue("Refined end expected near 225s, got " + refined3[1], Math.abs(refined3[1] - 225) <= 2);

        // 3) Эпизод 13: другой опенинг (OP2) — ни один из 8 зондов не должен дать ложное срабатывание!
        float[] ep13 = buildEpisodeAudio(360, 20, otherOpening, 5105L, 0.8f, 0.02f);
        for (int pStart = 0; pStart < 340; pStart += 44) {
            byte[] pData = fingerprintRange(ep13, pStart, pStart + 18);
            AudioFingerprintEngine.MatchResult fp = AudioFingerprintEngine.findProbeMatch(
                    template, pData, pData.length / AudioFingerprintEngine.NUM_BANDS, pStart);
            assertNull("OP2 probe at " + pStart + "s must NOT produce a false positive", fp);
        }
    }

    @Test
    public void testHlsPlaylistParserExtinfDurationsAndLowestVariant() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXTINF:6.006,\n"
                + "seg0.ts\n"
                + "#EXTINF:4.5,title\n"
                + "seg1.ts\n";
        HlsPlaylistParser.ParsedMediaPlaylist parsed =
                HlsPlaylistParser.parseMediaPlaylist(playlist, "https://example.com/hls/index.m3u8");
        assertEquals(2, parsed.segments.size());
        assertEquals(6.006, parsed.segments.get(0).durationSec, 0.001);
        assertEquals(4.5, parsed.segments.get(1).durationSec, 0.001);

        String masterNoRes = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2400000\n"
                + "720/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=520000\n"
                + "360/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1200000\n"
                + "480/index.m3u8\n";
        String lowest = HlsPlaylistParser.selectLowestBandwidthVariantUrl(
                masterNoRes, "https://example.com/hls/master.m3u8");
        assertEquals("https://example.com/hls/360/index.m3u8", lowest);
    }
}
