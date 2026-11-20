package com.example.animelib.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Компактный спектральный движок аудио-фингерпринтинга для поиска опенингов.
 *
 * Алгоритм:
 * - Ресемплинг моно-PCM к 8 кГц с антиалиасингом (дробное усреднение).
 * - Окно Ханна 2048 сэмплов (~256 мс), шаг 2000 сэмплов (ровно 250 мс = 4 кадра/сек).
 * - 16 логарифмически распределённых частотных полос (100 Гц – 3800 Гц),
 *   логарифм энергии, центрирование и L2-нормализация каждого кадра (инвариантность к громкости),
 *   квантование в signed byte [-127..127] (всего ~6.4 КБ на 100 секунд опенинга).
 * - Двухэтапный поиск (грубый сдвиг -> точная доводка -> обрезка границ по локальной корреляции),
 *   устойчивый к позднему/раннему нажатию кнопки ручного пропуска и к холодным вступлениям.
 */
public final class AudioFingerprintEngine {

    public static final int TARGET_SAMPLE_RATE = 8000;
    public static final int FFT_SIZE = 2048;
    public static final int HOP_SAMPLES = 2000; // 250ms at 8kHz
    public static final int HOP_MS = 250;
    public static final int FRAMES_PER_SECOND = 1000 / HOP_MS; // 4
    public static final int NUM_BANDS = 16;

    private static final float SILENCE_RMS_THRESHOLD = 0.0035f;
    private static final float QUANT_SCALE = 127f;
    private static final float INV_QUANT_SQ = 1f / (QUANT_SCALE * QUANT_SCALE);

    private static final float[] HANN_WINDOW = new float[FFT_SIZE];
    private static final int[] BAND_START_BIN = new int[NUM_BANDS];
    private static final int[] BAND_END_BIN = new int[NUM_BANDS];
    private static final int DCT_COUNT = 10; // C[0..9], используем C[2..9] и delta C[1..8]
    private static final float[][] DCT_COS = new float[DCT_COUNT][NUM_BANDS];

    static {
        for (int i = 0; i < FFT_SIZE; i++) {
            HANN_WINDOW[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1))));
        }

        double minFreq = 100.0;
        double maxFreq = 3800.0;
        double ratio = maxFreq / minFreq;
        double binHz = (double) TARGET_SAMPLE_RATE / FFT_SIZE;

        int prevEnd = Math.max(1, (int) Math.round(minFreq / binHz));
        for (int b = 0; b < NUM_BANDS; b++) {
            double fHigh = minFreq * Math.pow(ratio, (double) (b + 1) / NUM_BANDS);
            int endBin = Math.min(FFT_SIZE / 2 - 1, Math.max(prevEnd + 1, (int) Math.round(fHigh / binHz)));
            BAND_START_BIN[b] = prevEnd;
            BAND_END_BIN[b] = endBin;
            prevEnd = endBin;
        }

        for (int c = 0; c < DCT_COUNT; c++) {
            for (int b = 0; b < NUM_BANDS; b++) {
                DCT_COS[c][b] = (float) Math.cos(Math.PI * c * (b + 0.5) / NUM_BANDS);
            }
        }
    }

    private AudioFingerprintEngine() {
    }

    /**
     * Шаблон опенинга, извлечённый из расширенного окна вокруг ручного пропуска.
     */
    public static final class Template {
        public final long id;
        @NonNull public final byte[] data; // frameCount * NUM_BANDS
        public final int frameCount;
        public final int coreStartFrame;
        public final int coreEndFrame;
        public final int skipDurationSec;

        public Template(long id, @NonNull byte[] data, int frameCount,
                        int coreStartFrame, int coreEndFrame, int skipDurationSec) {
            this.id = id;
            this.data = data;
            this.frameCount = frameCount;
            this.coreStartFrame = Math.max(0, Math.min(coreStartFrame, Math.max(0, frameCount - 1)));
            this.coreEndFrame = Math.max(this.coreStartFrame, Math.min(coreEndFrame, Math.max(0, frameCount - 1)));
            this.skipDurationSec = Math.max(10, skipDurationSec);
        }

        public boolean isValid() {
            return frameCount >= 40 && data.length >= frameCount * NUM_BANDS;
        }
    }

    /**
     * Результат найденного опенинга в целевой серии.
     */
    public static final class MatchResult {
        public final int fromSec;
        public final int toSec;
        public final float confidence;
        public final float zScore;
        public final int bestLagFrames;
        public final long templateId;

        public MatchResult(int fromSec, int toSec, float confidence, float zScore, int bestLagFrames, long templateId) {
            this.fromSec = fromSec;
            this.toSec = toSec;
            this.confidence = confidence;
            this.zScore = zScore;
            this.bestLagFrames = bestLagFrames;
            this.templateId = templateId;
        }
    }

    /**
     * Потоковый сборщик аудио-отпечатка из произвольного PCM (любой sample rate и число каналов).
     */
    public static final class StreamingFingerprintBuilder {
        private final double resampleRatio;
        private double accum = 0.0;
        private double weightSum = 0.0;
        private double remaining;

        private final float[] ringBuffer = new float[FFT_SIZE];
        private int ringFilled = 0;

        private final float[] fftReal = new float[FFT_SIZE];
        private final float[] fftImag = new float[FFT_SIZE];
        private final float[] bandEnergies = new float[NUM_BANDS];
        private final float[] cepstra = new float[DCT_COUNT];
        private final float[] prevCepstra = new float[DCT_COUNT];
        private final float[] featureVec = new float[NUM_BANDS];
        private boolean hasPrevCepstra = false;

        private byte[] framesBuffer;
        private int frameCount = 0;

        public StreamingFingerprintBuilder(int sourceSampleRate, int initialCapacityFrames) {
            int safeRate = sourceSampleRate > 0 ? sourceSampleRate : 48000;
            this.resampleRatio = (double) safeRate / TARGET_SAMPLE_RATE;
            this.remaining = this.resampleRatio;
            int cap = Math.max(64, initialCapacityFrames);
            this.framesBuffer = new byte[cap * NUM_BANDS];
        }

        /**
         * Подаёт буфер 16-bit PCM сэмплов (interleaved для multi-channel).
         */
        public void feedPcm16(@NonNull byte[] pcmBytes, int offset, int lengthBytes, int channelCount) {
            int ch = Math.max(1, channelCount);
            int frameStrideBytes = ch * 2;
            int end = offset + lengthBytes - (lengthBytes % frameStrideBytes);
            float invScale = 1f / (32768f * ch);

            for (int pos = offset; pos < end; pos += frameStrideBytes) {
                int sum = 0;
                for (int c = 0; c < ch; c++) {
                    int bPos = pos + c * 2;
                    short sample = (short) ((pcmBytes[bPos] & 0xFF) | (pcmBytes[bPos + 1] << 8));
                    sum += sample;
                }
                feedMonoSample(sum * invScale);
            }
        }

        /**
         * Подаёт массив моно float сэмплов в диапазоне [-1.0, 1.0].
         */
        public void feedMonoFloat(@NonNull float[] samples, int offset, int count) {
            int end = Math.min(samples.length, offset + count);
            for (int i = offset; i < end; i++) {
                feedMonoSample(samples[i]);
            }
        }

        private void feedMonoSample(float sample) {
            if (resampleRatio <= 1.0001) {
                pushTargetSample(sample);
                return;
            }
            if (remaining > 1.0) {
                accum += sample;
                weightSum += 1.0;
                remaining -= 1.0;
            } else {
                double frac = remaining;
                accum += sample * frac;
                weightSum += frac;
                if (weightSum > 0.0) {
                    pushTargetSample((float) (accum / weightSum));
                }
                double left = 1.0 - frac;
                accum = sample * left;
                weightSum = left;
                remaining = resampleRatio - left;
                if (remaining <= 0.0) {
                    remaining = resampleRatio;
                }
            }
        }

        private void pushTargetSample(float sample8k) {
            ringBuffer[ringFilled++] = sample8k;
            if (ringFilled == FFT_SIZE) {
                emitCurrentWindowFrame();
                int overlap = FFT_SIZE - HOP_SAMPLES;
                if (overlap > 0) {
                    System.arraycopy(ringBuffer, HOP_SAMPLES, ringBuffer, 0, overlap);
                }
                ringFilled = overlap;
            }
        }

        private void emitCurrentWindowFrame() {
            ensureCapacity(frameCount + 1);
            int destOffset = frameCount * NUM_BANDS;

            double sumSq = 0.0;
            for (int i = 0; i < FFT_SIZE; i++) {
                float s = ringBuffer[i];
                sumSq += s * s;
                fftReal[i] = s * HANN_WINDOW[i];
                fftImag[i] = 0f;
            }
            float rms = (float) Math.sqrt(sumSq / FFT_SIZE);
            if (rms < SILENCE_RMS_THRESHOLD) {
                Arrays.fill(framesBuffer, destOffset, destOffset + NUM_BANDS, (byte) 0);
                hasPrevCepstra = false;
                frameCount++;
                return;
            }

            fftRadix2(fftReal, fftImag);

            for (int b = 0; b < NUM_BANDS; b++) {
                double power = 0.0;
                int start = BAND_START_BIN[b];
                int end = BAND_END_BIN[b];
                for (int k = start; k < end; k++) {
                    float re = fftReal[k];
                    float im = fftImag[k];
                    power += (double) re * re + (double) im * im;
                }
                bandEnergies[b] = (float) Math.log1p(power * 100.0 / Math.max(1, end - start));
            }

            // Вычисляем DCT-II (MFCC): C[0] — громкость, C[1] — глобальный наклон спектра (1/f).
            // Пропускаем C[0] и C[1] в статике, чтобы несвязанная речь/музыка давала корреляцию ~0.0.
            for (int c = 1; c < DCT_COUNT; c++) {
                float sum = 0f;
                float[] cosRow = DCT_COS[c];
                for (int b = 0; b < NUM_BANDS; b++) {
                    sum += bandEnergies[b] * cosRow[b];
                }
                cepstra[c] = sum;
            }

            // 8 статических MFCC C[2..9] + 8 временных дельт ΔC[1..8] (переходы нот и ритмика)
            for (int i = 0; i < 8; i++) {
                featureVec[i] = cepstra[i + 2];
                featureVec[8 + i] = hasPrevCepstra ? (cepstra[i + 1] - prevCepstra[i + 1]) * 1.25f : 0f;
            }
            System.arraycopy(cepstra, 0, prevCepstra, 0, DCT_COUNT);
            hasPrevCepstra = true;

            float normSq = 0f;
            for (int i = 0; i < NUM_BANDS; i++) {
                normSq += featureVec[i] * featureVec[i];
            }

            if (normSq < 1e-6f) {
                Arrays.fill(framesBuffer, destOffset, destOffset + NUM_BANDS, (byte) 0);
                frameCount++;
                return;
            }

            float invNorm = QUANT_SCALE / (float) Math.sqrt(normSq);
            for (int i = 0; i < NUM_BANDS; i++) {
                int q = Math.round(featureVec[i] * invNorm);
                if (q > 127) q = 127;
                else if (q < -127) q = -127;
                framesBuffer[destOffset + i] = (byte) q;
            }
            frameCount++;
        }

        private void ensureCapacity(int requiredFrames) {
            int requiredBytes = requiredFrames * NUM_BANDS;
            if (framesBuffer.length < requiredBytes) {
                int newLen = Math.max(requiredBytes, framesBuffer.length * 2);
                framesBuffer = Arrays.copyOf(framesBuffer, newLen);
            }
        }

        public int getFrameCount() {
            return frameCount;
        }

        @NonNull
        public byte[] copyFramesData() {
            return Arrays.copyOf(framesBuffer, frameCount * NUM_BANDS);
        }
    }

    /**
     * Буфер для сборки целевого аудио-отпечатка с привязкой батчей к точным временным позициям (для HLS).
     */
    public static final class PositionedFingerprintBuffer {
        private byte[] data;
        private int frameCount = 0;

        public PositionedFingerprintBuffer(int initialCapacityFrames) {
            this.data = new byte[Math.max(256, initialCapacityFrames) * NUM_BANDS];
        }

        public void putFramesAt(int startFrame, @NonNull byte[] srcData, int srcFrameCount) {
            if (srcFrameCount <= 0 || startFrame < 0) return;
            int endFrame = startFrame + srcFrameCount;
            int requiredBytes = endFrame * NUM_BANDS;
            if (data.length < requiredBytes) {
                int newLen = Math.max(requiredBytes, data.length * 2);
                data = Arrays.copyOf(data, newLen);
            }
            System.arraycopy(srcData, 0, data, startFrame * NUM_BANDS, srcFrameCount * NUM_BANDS);
            if (endFrame > frameCount) {
                frameCount = endFrame;
            }
        }

        public int getFrameCount() {
            return frameCount;
        }

        @NonNull
        public byte[] getBackingArray() {
            return data;
        }
    }

    /**
     * Ищет совпадение шаблона опенинга в целевом аудио-отпечатке.
     *
     * @param template    шаблон опенинга (с полями padding вокруг ручного пропуска)
     * @param targetData  массив байтов целевого эпизода (targetFrames * NUM_BANDS)
     * @param targetFrames число кадров в targetData (1 кадр = 250 мс)
     * @param isFinalPass true на финальном проходе (до конца окна поиска), false во время прогрессивного сканирования
     * @return найденные точные границы опенинга или null, если уверенного совпадения нет
     */
    @Nullable
    public static MatchResult findMatch(@NonNull Template template,
                                        @NonNull byte[] targetData,
                                        int targetFrames,
                                        boolean isFinalPass) {
        if (!template.isValid() || targetFrames < 80 || targetData.length < targetFrames * NUM_BANDS) {
            return null;
        }

        final int qFrames = template.frameCount;
        final int coreStart = template.coreStartFrame;
        final int coreEnd = template.coreEndFrame;
        final int coreLen = coreEnd - coreStart + 1;
        if (coreLen < 32) {
            return null;
        }

        final int minOverlapFrames = Math.max(40, Math.min(coreLen / 2, 100));
        final int minLag = -(coreEnd - minOverlapFrames);
        final int maxLag = targetFrames - coreStart - minOverlapFrames;
        if (maxLag < minLag) {
            return null;
        }

        // Предвычисляем маски тишины
        boolean[] qActive = buildActiveMask(template.data, qFrames);
        boolean[] tActive = buildActiveMask(targetData, targetFrames);

        int activeCoreCount = 0;
        for (int i = coreStart; i <= coreEnd; i++) {
            if (qActive[i]) activeCoreCount++;
        }
        if (activeCoreCount < 28) {
            return null;
        }

        // === Этап 1: Грубый проход (шаг по лагу = 2 кадра = 0.5с, шаг по шаблону = 2 кадра) ===
        final int coarseStep = 2;
        int lagCount = ((maxLag - minLag) / coarseStep) + 1;
        float[] coarseScores = new float[lagCount];
        int[] coarseLags = new int[lagCount];

        int evaluated = 0;
        double sumScores = 0.0;
        double sumSqScores = 0.0;

        for (int idx = 0, lag = minLag; lag <= maxLag; idx++, lag += coarseStep) {
            coarseLags[idx] = lag;
            float score = computeCoreMeanSim(template.data, qActive, targetData, tActive,
                    targetFrames, coreStart, coreEnd, lag, 2, minOverlapFrames / 2);
            coarseScores[idx] = score;
            if (score > -1.5f) {
                sumScores += score;
                sumSqScores += (double) score * score;
                evaluated++;
            }
        }

        if (evaluated == 0) {
            return null;
        }

        // Находим топ-8 разделённых локальных пиков (не ближе 20 кадров = 5 сек друг к другу)
        final int maxPeaks = 8;
        final int minPeakSeparationLags = 20;
        List<Integer> peakLags = new ArrayList<>(maxPeaks);
        boolean[] suppressed = new boolean[lagCount];

        for (int p = 0; p < maxPeaks; p++) {
            int bestIdx = -1;
            float bestVal = -2f;
            for (int i = 0; i < lagCount; i++) {
                if (!suppressed[i] && coarseScores[i] > bestVal) {
                    bestVal = coarseScores[i];
                    bestIdx = i;
                }
            }
            if (bestIdx < 0 || bestVal < 0.12f) {
                break;
            }
            peakLags.add(coarseLags[bestIdx]);
            int suppressRadius = minPeakSeparationLags / coarseStep;
            int sFrom = Math.max(0, bestIdx - suppressRadius);
            int sTo = Math.min(lagCount - 1, bestIdx + suppressRadius);
            for (int s = sFrom; s <= sTo; s++) {
                suppressed[s] = true;
            }
        }

        if (peakLags.isEmpty()) {
            return null;
        }

        // Фоновая статистика без учёта окрестности лучшего пика
        int primaryPeakLag = peakLags.get(0);
        double bgSum = 0.0;
        double bgSq = 0.0;
        int bgCount = 0;
        for (int i = 0; i < lagCount; i++) {
            if (coarseScores[i] > -1.5f && Math.abs(coarseLags[i] - primaryPeakLag) > 24) {
                bgSum += coarseScores[i];
                bgSq += (double) coarseScores[i] * coarseScores[i];
                bgCount++;
            }
        }
        float bgMean = bgCount > 5 ? (float) (bgSum / bgCount) : (float) (sumScores / evaluated);
        float bgVar = bgCount > 5
                ? (float) Math.max(0.0, (bgSq / bgCount) - (double) bgMean * bgMean)
                : (float) Math.max(0.0, (sumSqScores / evaluated) - (sumScores / evaluated) * (sumScores / evaluated));
        float bgStd = (float) Math.sqrt(bgVar);

        // === Этап 2 + 3: Точная доводка лага и выделение точных границ опенинга ===
        MatchResult bestResult = null;
        float bestCompositeScore = -1f;

        for (int coarseLag : peakLags) {
            int refinedLag = coarseLag;
            float refinedScore = -2f;
            for (int d = -3; d <= 3; d++) {
                int candidateLag = coarseLag + d;
                float s = computeCoreMeanSim(template.data, qActive, targetData, tActive,
                        targetFrames, coreStart, coreEnd, candidateLag, 1, minOverlapFrames);
                if (s > refinedScore) {
                    refinedScore = s;
                    refinedLag = candidateLag;
                }
            }
            if (refinedScore < 0.22f) {
                continue;
            }

            MatchResult candidate = refineBoundariesAndValidate(
                    template, qActive, targetData, tActive, targetFrames,
                    refinedLag, refinedScore, bgMean, bgStd, isFinalPass);

            if (candidate != null) {
                float composite = candidate.confidence + Math.min(0.25f, candidate.zScore * 0.02f);
                if (composite > bestCompositeScore) {
                    bestCompositeScore = composite;
                    bestResult = candidate;
                }
            }
        }

        return bestResult;
    }

    /**
     * Проверяет, совпадают ли два шаблона опенинга (для дедупликации в БД).
     */
    public static float computeTemplateSimilarity(@NonNull Template a, @NonNull Template b) {
        MatchResult result = findMatch(a, b.data, b.frameCount, true);
        return result != null ? result.confidence : 0f;
    }

    @Nullable
    private static MatchResult refineBoundariesAndValidate(@NonNull Template template,
                                                           @NonNull boolean[] qActive,
                                                           @NonNull byte[] targetData,
                                                           @NonNull boolean[] tActive,
                                                           int targetFrames,
                                                           int lag,
                                                           float coreScore,
                                                           float bgMean,
                                                           float bgStd,
                                                           boolean isFinalPass) {
        final int qFrames = template.frameCount;
        float[] rawSim = new float[qFrames];
        boolean[] validActive = new boolean[qFrames];

        for (int i = 0; i < qFrames; i++) {
            int tIdx = lag + i;
            if (tIdx >= 0 && tIdx < targetFrames && qActive[i] && tActive[tIdx]) {
                rawSim[i] = frameDot(template.data, i, targetData, tIdx);
                validActive[i] = true;
            } else {
                rawSim[i] = 0f;
                validActive[i] = false;
            }
        }

        // Скользящее среднее ±4 кадра (±1 секунда) только по активным кадрам
        float[] smoothSim = new float[qFrames];
        float peakSmooth = 0f;
        final int halfWin = 4;

        for (int i = 0; i < qFrames; i++) {
            int wStart = Math.max(0, i - halfWin);
            int wEnd = Math.min(qFrames - 1, i + halfWin);
            float sum = 0f;
            int cnt = 0;
            for (int k = wStart; k <= wEnd; k++) {
                if (validActive[k]) {
                    sum += rawSim[k];
                    cnt++;
                }
            }
            if (cnt >= 2) {
                float avg = sum / cnt;
                smoothSim[i] = avg;
                if (avg > peakSmooth) {
                    peakSmooth = avg;
                }
            } else {
                smoothSim[i] = 0f;
            }
        }

        if (peakSmooth < 0.56f) {
            return null;
        }

        // Адаптивный порог входа в сегмент опенинга: середина между фоном (bgMean) и пиком (peakSmooth)
        float clampedBg = Math.max(0f, bgMean);
        float thresh = Math.max(0.40f, clampedBg + 0.52f * (peakSmooth - clampedBg));

        // Находим все интервалы >= thresh и объединяем разрывы <= 14 кадров (3.5 сек, напр. озвучка названия серии)
        List<int[]> spans = new ArrayList<>();
        int curStart = -1;
        for (int i = 0; i < qFrames; i++) {
            if (smoothSim[i] >= thresh) {
                if (curStart < 0) curStart = i;
            } else if (curStart >= 0) {
                spans.add(new int[]{curStart, i - 1});
                curStart = -1;
            }
        }
        if (curStart >= 0) {
            spans.add(new int[]{curStart, qFrames - 1});
        }
        if (spans.isEmpty()) {
            return null;
        }

        List<int[]> merged = new ArrayList<>();
        int[] current = spans.get(0);
        final int maxBridgeGapFrames = 14; // 3.5 sec
        final int minBridgeableSpanFrames = 12; // 3.0 sec — не приклеиваем случайные короткие всплески диалога
        for (int s = 1; s < spans.size(); s++) {
            int[] next = spans.get(s);
            int nextLen = next[1] - next[0] + 1;
            int curLen = current[1] - current[0] + 1;
            if (next[0] - current[1] - 1 <= maxBridgeGapFrames
                    && curLen >= minBridgeableSpanFrames
                    && nextLen >= minBridgeableSpanFrames) {
                current[1] = next[1];
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        // Выбираем самый длинный сегмент, пересекающийся с основным пропущенным окном
        int[] bestSpan = null;
        int bestSpanLen = 0;
        for (int[] sp : merged) {
            boolean overlapsCore = sp[1] >= template.coreStartFrame && sp[0] <= template.coreEndFrame;
            int len = sp[1] - sp[0] + 1;
            if (overlapsCore && len > bestSpanLen) {
                bestSpanLen = len;
                bestSpan = sp;
            }
        }
        if (bestSpan == null) {
            return null;
        }

        // Точная подстройка границ по rawSim (убираем размытие от ±1с окна сглаживания)
        int spanStart = bestSpan[0];
        int spanEnd = bestSpan[1];
        float edgeThresh = Math.max(0.36f, thresh * 0.92f);

        while (spanStart < spanEnd && (!validActive[spanStart] || rawSim[spanStart] < edgeThresh)) {
            spanStart++;
        }
        while (spanEnd > spanStart && (!validActive[spanEnd] || rawSim[spanEnd] < edgeThresh)) {
            spanEnd--;
        }
        // Также проверяем возможность расширить на 1–3 кадра наружу, если rawSim там всё ещё сильный
        while (spanStart > 0 && validActive[spanStart - 1] && rawSim[spanStart - 1] >= edgeThresh) {
            spanStart--;
        }
        while (spanEnd < qFrames - 1 && validActive[spanEnd + 1] && rawSim[spanEnd + 1] >= edgeThresh) {
            spanEnd++;
        }

        int finalSpanFrames = spanEnd - spanStart + 1;
        float spanDurationSec = finalSpanFrames * (HOP_MS / 1000f);
        float minRequiredSec = Math.max(24f, Math.min(38f, template.skipDurationSec * 0.52f));
        if (spanDurationSec < minRequiredSec || spanDurationSec > 165f) {
            return null;
        }

        // Вычисляем среднюю схожесть на активных кадрах найденного опенинга
        float sumSpanSim = 0f;
        int activeSpanFrames = 0;
        for (int i = spanStart; i <= spanEnd; i++) {
            if (validActive[i]) {
                sumSpanSim += rawSim[i];
                activeSpanFrames++;
            }
        }
        if (activeSpanFrames < minRequiredSec * 2.2f) {
            return null;
        }
        float meanSpanSim = sumSpanSim / activeSpanFrames;

        float zScore = (bgStd > 0.015f)
                ? (Math.max(coreScore, meanSpanSim) - bgMean) / bgStd
                : (Math.max(coreScore, meanSpanSim) - bgMean) * 20f;

        boolean strongAbsolute = meanSpanSim >= 0.62f && peakSmooth >= 0.68f;
        boolean strongRelative = meanSpanSim >= 0.48f && peakSmooth >= 0.58f
                && (meanSpanSim - bgMean) >= 0.24f && zScore >= 3.5f;

        if (!strongAbsolute && !strongRelative) {
            return null;
        }

        int targetStartFrame = Math.max(0, lag + spanStart);
        int targetEndFrame = Math.min(targetFrames, lag + spanEnd + 1);

        // Защита при прогрессивном сканировании: если найденный опенинг упирается в правый край
        // ещё не полностью декодированного буфера, не обрезаем его раньше времени — ждём следующий чанк!
        if (!isFinalPass && targetEndFrame >= targetFrames - (8 * FRAMES_PER_SECOND)) {
            return null;
        }

        int fromSec = Math.max(0, Math.round(targetStartFrame * (HOP_MS / 1000f)));
        int toSec = Math.round(targetEndFrame * (HOP_MS / 1000f));
        if (toSec <= fromSec + 15) {
            return null;
        }

        return new MatchResult(fromSec, toSec, meanSpanSim, zScore, lag, template.id);
    }

    private static float computeCoreMeanSim(@NonNull byte[] qData,
                                            @NonNull boolean[] qActive,
                                            @NonNull byte[] tData,
                                            @NonNull boolean[] tActive,
                                            int targetFrames,
                                            int coreStart,
                                            int coreEnd,
                                            int lag,
                                            int stride,
                                            int minValidFrames) {
        float sum = 0f;
        int valid = 0;

        for (int i = coreStart; i <= coreEnd; i += stride) {
            int tIdx = lag + i;
            if (tIdx < 0) continue;
            if (tIdx >= targetFrames) break;
            if (qActive[i] && tActive[tIdx]) {
                sum += frameDot(qData, i, tData, tIdx);
                valid++;
            }
        }
        if (valid < minValidFrames) {
            return -2f;
        }
        return sum / valid;
    }

    private static boolean[] buildActiveMask(@NonNull byte[] data, int frameCount) {
        boolean[] active = new boolean[frameCount];
        for (int f = 0; f < frameCount; f++) {
            int base = f * NUM_BANDS;
            boolean nonZero = false;
            for (int b = 0; b < NUM_BANDS; b++) {
                if (data[base + b] != 0) {
                    nonZero = true;
                    break;
                }
            }
            active[f] = nonZero;
        }
        return active;
    }

    private static float frameDot(@NonNull byte[] a, int frameA, @NonNull byte[] b, int frameB) {
        int offA = frameA * NUM_BANDS;
        int offB = frameB * NUM_BANDS;
        int dot = 0;
        for (int k = 0; k < NUM_BANDS; k++) {
            dot += a[offA + k] * b[offB + k];
        }
        return dot * INV_QUANT_SQ;
    }

    /**
     * Быстрое in-place Radix-2 Cooley-Tukey FFT.
     */
    private static void fftRadix2(float[] re, float[] im) {
        final int n = re.length;
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wLenRe = (float) Math.cos(ang);
            float wLenIm = (float) Math.sin(ang);
            int halfLen = len >> 1;

            for (int i = 0; i < n; i += len) {
                float wRe = 1f;
                float wIm = 0f;
                for (int j = 0; j < halfLen; j++) {
                    int u = i + j;
                    int v = u + halfLen;
                    float vr = re[v] * wRe - im[v] * wIm;
                    float vi = re[v] * wIm + im[v] * wRe;

                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;

                    float nextWRe = wRe * wLenRe - wIm * wLenIm;
                    wIm = wRe * wLenIm + wIm * wLenRe;
                    wRe = nextWRe;
                }
            }
        }
    }
}
