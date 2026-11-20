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
    public void testProgressiveRightEdgeGuardPreventsTruncatedDetection() {
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

        // Проход 1: декодировано только 0..120с (опенинг обрезан на правом краю!)
        byte[] pass1Data = fingerprintRange(ep2, 0, 120);
        AudioFingerprintEngine.MatchResult pass1 = AudioFingerprintEngine.findMatch(
                template, pass1Data, pass1Data.length / AudioFingerprintEngine.NUM_BANDS, false);
        assertNull("Progressive pass should NOT stop early while opening touches right edge", pass1);

        // Проход 2: декодировано 0..190с (опенинг целиком внутри с запасом > 8с)
        byte[] pass2Data = fingerprintRange(ep2, 0, 190);
        AudioFingerprintEngine.MatchResult pass2 = AudioFingerprintEngine.findMatch(
                template, pass2Data, pass2Data.length / AudioFingerprintEngine.NUM_BANDS, false);
        assertNotNull("Progressive pass 2 should detect full opening and stop early", pass2);
        assertTrue("Expected start near 75s, got " + pass2.fromSec, Math.abs(pass2.fromSec - 75) <= 2);
        assertTrue("Expected end near 165s, got " + pass2.toSec, Math.abs(pass2.toSec - 165) <= 2);
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
    public void testHlsPlaylistParserExtinfDurations() {
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
    }
}
