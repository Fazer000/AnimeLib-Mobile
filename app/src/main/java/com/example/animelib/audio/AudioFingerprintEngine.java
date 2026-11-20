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
        public final boolean isProvisional;

        public MatchResult(int fromSec, int toSec, float confidence, float zScore, int bestLagFrames, long templateId) {
            this(fromSec, toSec, confidence, zScore, bestLagFrames, templateId, false);
        }

        public MatchResult(int fromSec, int toSec, float confidence, float zScore, int bestLagFrames, long templateId,
                           boolean isProvisional) {
            this.fromSec = fromSec;
            this.toSec = toSec;
            this.confidence = confidence;
            this.zScore = zScore;
            this.bestLagFrames = bestLagFrames;
            this.templateId = templateId;
            this.isProvisional = isProvisional;
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
        if (!template.isValid() || targetFrames < 64 || targetData.length < targetFrames * NUM_BANDS) {
            return null;
        }

        final int qFrames = template.frameCount;
        final int coreStart = template.coreStartFrame;
        final int coreEnd = template.coreEndFrame;
        final int coreLen = coreEnd - coreStart + 1;
        if (coreLen < 32) {
            return null;
        }

        final int baseMinOverlap = Math.max(40, Math.min(coreLen / 2, 100));
        final int minOverlapFrames = (!isFinalPass && targetFrames < 180)
                ? Math.max(48, Math.min(baseMinOverlap, (int) (targetFrames * 0.60f)))
                : baseMinOverlap;
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

    /**
     * Сверхбыстрый поиск по короткому 16–20с аудио-зонду (Sparse Probe).
     * Прогоняет короткий фрагмент эпизода по всему шаблону опенинга с шагом 1 кадр (0.25с),
     * мгновенно вычисляя точный глобальный сдвиг lag и границы опенинга всего за ~0.5 мс CPU.
     */
    @Nullable
    public static MatchResult findProbeMatch(@NonNull Template template,
                                             @NonNull byte[] probeData,
                                             int probeFrames,
                                             double probeStartSec) {
        if (!template.isValid() || probeFrames < 44 || probeData.length < probeFrames * NUM_BANDS) {
            return null;
        }

        final int qFrames = template.frameCount;
        final boolean[] qActive = buildActiveMask(template.data, qFrames);
        final boolean[] pActive = buildActiveMask(probeData, probeFrames);

        int activeProbeCount = 0;
        for (int i = 0; i < probeFrames; i++) {
            if (pActive[i]) activeProbeCount++;
        }
        if (activeProbeCount < 24) {
            return null;
        }

        final int minOverlap = Math.max(32, Math.min(44, (int) (probeFrames * 0.55f)));
        final int minQOffset = -(probeFrames - minOverlap);
        final int maxQOffset = qFrames - minOverlap;
        if (maxQOffset < minQOffset) {
            return null;
        }

        final int offsetCount = maxQOffset - minQOffset + 1;
        final float[] scores = new float[offsetCount];
        int evaluated = 0;
        double sumScores = 0.0;
        double sumSqScores = 0.0;

        for (int idx = 0, qOff = minQOffset; qOff <= maxQOffset; idx++, qOff++) {
            float sum = 0f;
            int valid = 0;
            int pStart = Math.max(0, -qOff);
            int pEnd = Math.min(probeFrames, qFrames - qOff);
            for (int p = pStart; p < pEnd; p++) {
                int q = qOff + p;
                if (qActive[q] && pActive[p]) {
                    sum += frameDot(template.data, q, probeData, p);
                    valid++;
                }
            }
            if (valid >= Math.max(24, minOverlap - 10)) {
                float mean = sum / valid;
                scores[idx] = mean;
                sumScores += mean;
                sumSqScores += (double) mean * mean;
                evaluated++;
            } else {
                scores[idx] = -2f;
            }
        }

        if (evaluated < 10) {
            return null;
        }

        // Находим топ-4 разделённых пика (не ближе 16 кадров = 4 сек друг к другу)
        final int maxPeaks = 4;
        final int suppressRadius = 16;
        List<Integer> peakOffsets = new ArrayList<>(maxPeaks);
        boolean[] suppressed = new boolean[offsetCount];

        for (int pk = 0; pk < maxPeaks; pk++) {
            int bestIdx = -1;
            float bestVal = -2f;
            for (int i = 0; i < offsetCount; i++) {
                if (!suppressed[i] && scores[i] > bestVal) {
                    bestVal = scores[i];
                    bestIdx = i;
                }
            }
            if (bestIdx < 0 || bestVal < 0.25f) {
                break;
            }
            peakOffsets.add(minQOffset + bestIdx);
            int sFrom = Math.max(0, bestIdx - suppressRadius);
            int sTo = Math.min(offsetCount - 1, bestIdx + suppressRadius);
            for (int s = sFrom; s <= sTo; s++) {
                suppressed[s] = true;
            }
        }

        if (peakOffsets.isEmpty()) {
            return null;
        }

        int primaryOffset = peakOffsets.get(0);
        double bgSum = 0.0;
        double bgSq = 0.0;
        int bgCount = 0;
        for (int i = 0; i < offsetCount; i++) {
            int qOff = minQOffset + i;
            if (scores[i] > -1.5f && Math.abs(qOff - primaryOffset) > 20) {
                bgSum += scores[i];
                bgSq += (double) scores[i] * scores[i];
                bgCount++;
            }
        }
        float bgMean = bgCount > 5 ? (float) (bgSum / bgCount) : (float) (sumScores / evaluated);
        float bgVar = bgCount > 5
                ? (float) Math.max(0.0, (bgSq / bgCount) - (double) bgMean * bgMean)
                : (float) Math.max(0.0, (sumSqScores / evaluated) - (sumScores / evaluated) * (sumScores / evaluated));
        float bgStd = (float) Math.sqrt(bgVar);

        int probeStartFrame = (int) Math.round(probeStartSec * FRAMES_PER_SECOND);
        MatchResult bestResult = null;
        float bestComposite = -1f;

        for (int qOff : peakOffsets) {
            int offsetIdx = qOff - minQOffset;
            float peakScore = scores[offsetIdx];
            float peakMargin = peakScore - bgMean;
            float peakZ = (bgStd > 0.02f)
                    ? (peakMargin / bgStd)
                    : (peakMargin * 20f);

            // Контраст локального пика относительно «плеч» (±1.5..4.5с):
            // реальный опенинг даёт острый пик синхронизации, а схожий тембр голоса — плоское плато.
            float shoulderSum = 0f;
            int shoulderCnt = 0;
            for (int d = 6; d <= 18; d++) {
                int leftIdx = offsetIdx - d;
                if (leftIdx >= 0 && leftIdx < offsetCount && scores[leftIdx] > -1.5f) {
                    shoulderSum += scores[leftIdx];
                    shoulderCnt++;
                }
                int rightIdx = offsetIdx + d;
                if (rightIdx >= 0 && rightIdx < offsetCount && scores[rightIdx] > -1.5f) {
                    shoulderSum += scores[rightIdx];
                    shoulderCnt++;
                }
            }
            float shoulderMean = shoulderCnt >= 4 ? (shoulderSum / shoulderCnt) : bgMean;
            float localContrast = peakScore - shoulderMean;

            if (peakZ < 4.2f || peakMargin < 0.17f || localContrast < 0.15f) {
                continue;
            }

            float[] rawSim = new float[probeFrames];
            boolean[] validActive = new boolean[probeFrames];
            for (int p = 0; p < probeFrames; p++) {
                int q = qOff + p;
                if (q >= 0 && q < qFrames && qActive[q] && pActive[p]) {
                    rawSim[p] = frameDot(template.data, q, probeData, p);
                    validActive[p] = true;
                }
            }

            float[] smoothSim = new float[probeFrames];
            float peakSmooth = 0f;
            final int halfWin = 3; // ±0.75s
            for (int p = 0; p < probeFrames; p++) {
                int wStart = Math.max(0, p - halfWin);
                int wEnd = Math.min(probeFrames - 1, p + halfWin);
                float s = 0f;
                int c = 0;
                for (int k = wStart; k <= wEnd; k++) {
                    if (validActive[k]) {
                        s += rawSim[k];
                        c++;
                    }
                }
                if (c >= 2) {
                    float avg = s / c;
                    smoothSim[p] = avg;
                    if (avg > peakSmooth) {
                        peakSmooth = avg;
                    }
                }
            }

            if (peakSmooth < 0.58f) {
                continue;
            }

            float clampedBg = Math.max(0f, bgMean);
            float thresh = Math.max(0.40f, clampedBg + 0.52f * (peakSmooth - clampedBg));

            List<int[]> spans = new ArrayList<>();
            int curStart = -1;
            for (int p = 0; p < probeFrames; p++) {
                if (smoothSim[p] >= thresh) {
                    if (curStart < 0) curStart = p;
                } else if (curStart >= 0) {
                    spans.add(new int[]{curStart, p - 1});
                    curStart = -1;
                }
            }
            if (curStart >= 0) {
                spans.add(new int[]{curStart, probeFrames - 1});
            }
            if (spans.isEmpty()) {
                continue;
            }

            List<int[]> merged = new ArrayList<>();
            int[] current = spans.get(0);
            for (int s = 1; s < spans.size(); s++) {
                int[] next = spans.get(s);
                int curLen = current[1] - current[0] + 1;
                int nextLen = next[1] - next[0] + 1;
                if (next[0] - current[1] - 1 <= 14 && curLen >= 10 && nextLen >= 10) {
                    current[1] = next[1];
                } else {
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current);

            int[] bestSpan = null;
            int bestSpanLen = 0;
            for (int[] sp : merged) {
                int len = sp[1] - sp[0] + 1;
                if (len > bestSpanLen) {
                    bestSpanLen = len;
                    bestSpan = sp;
                }
            }
            if (bestSpan == null) {
                continue;
            }

            int spStart = bestSpan[0];
            int spEnd = bestSpan[1];
            float edgeThresh = Math.max(0.36f, thresh * 0.90f);
            while (spStart < spEnd && (!validActive[spStart] || rawSim[spStart] < edgeThresh)) spStart++;
            while (spEnd > spStart && (!validActive[spEnd] || rawSim[spEnd] < edgeThresh)) spEnd--;
            while (spStart > 0 && validActive[spStart - 1] && rawSim[spStart - 1] >= edgeThresh) spStart--;
            while (spEnd < probeFrames - 1 && validActive[spEnd + 1] && rawSim[spEnd + 1] >= edgeThresh) spEnd++;

            int spanFrames = spEnd - spStart + 1;
            float spanSec = spanFrames * (HOP_MS / 1000f);
            if (spanSec < 9.5f) {
                continue;
            }

            // Убеждаемся, что найденный фрагмент существенно пересекается с ядром опенинга (не только с диалоговым padding)
            int matchedQStart = qOff + spStart;
            int matchedQEnd = qOff + spEnd;
            int coreWinStart = Math.max(0, template.coreStartFrame - 12);
            int coreWinEnd = Math.min(qFrames - 1, template.coreEndFrame + 12);
            int coreOverlap = Math.min(matchedQEnd, coreWinEnd) - Math.max(matchedQStart, coreWinStart) + 1;
            if (coreOverlap < 24) {
                continue;
            }

            float sumSim = 0f;
            int activeSpan = 0;
            for (int p = spStart; p <= spEnd; p++) {
                if (validActive[p]) {
                    sumSim += rawSim[p];
                    activeSpan++;
                }
            }
            if (activeSpan < 26) {
                continue;
            }

            float meanSpanSim = sumSim / activeSpan;
            boolean strongAbsolute = meanSpanSim >= 0.62f && peakSmooth >= 0.70f
                    && (meanSpanSim - bgMean) >= 0.26f;
            boolean strongRelative = meanSpanSim >= 0.52f && peakSmooth >= 0.60f
                    && (meanSpanSim - bgMean) >= 0.30f && peakZ >= 5.0f;

            if (!strongAbsolute && !strongRelative) {
                continue;
            }

            int lag = probeStartFrame - qOff;
            int estFromSec = Math.max(0, Math.round((lag + template.coreStartFrame) * (HOP_MS / 1000f)));
            int alignedEndSec = Math.round((lag + template.coreEndFrame + 1) * (HOP_MS / 1000f));
            int minEstTo = estFromSec + Math.max(25, template.skipDurationSec - 15);
            int maxEstTo = estFromSec + 150;
            int estToSec = Math.max(minEstTo, Math.min(maxEstTo, alignedEndSec));

            float composite = meanSpanSim + Math.min(0.25f, peakZ * 0.02f);
            if (composite > bestComposite) {
                bestComposite = composite;
                bestResult = new MatchResult(estFromSec, estToSec, meanSpanSim, peakZ, lag, template.id, true);
            }
        }

        return bestResult;
    }

    /**
     * Точно уточняет левую и правую границы опенинга по двум коротким краевым окнам (старт и конец),
     * компенсируя ±1с возможный дрейф #EXTINF в HLS-сегментах и позднее/раннее нажатие ручного пропуска.
     *
     * @return массив из 2 элементов: {refinedFromSec, refinedToSec}
     */
    @NonNull
    public static int[] refineEdgesAtLag(@NonNull Template template,
                                         int initialLag,
                                         int fallbackFromSec,
                                         int fallbackToSec,
                                         @Nullable byte[] startData,
                                         int startOriginFrame,
                                         int startFrames,
                                         @Nullable byte[] endData,
                                         int endOriginFrame,
                                         int endFrames) {
        int refinedFrom = fallbackFromSec;
        int refinedTo = fallbackToSec;
        final int qFrames = template.frameCount;
        final boolean[] qActive = buildActiveMask(template.data, qFrames);

        // 1. Уточнение левой границы (начала опенинга)
        if (startData != null && startFrames >= 16 && startData.length >= startFrames * NUM_BANDS) {
            boolean[] sActive = buildActiveMask(startData, startFrames);
            int qLimit = Math.min(qFrames - 1, template.coreStartFrame + 96);
            int bestLag = refineMicroLag(template.data, qActive, 0, qLimit,
                    startData, sActive, startOriginFrame, startFrames, initialLag, 6);

            float[] raw = new float[qLimit + 1];
            boolean[] valid = new boolean[qLimit + 1];
            for (int q = 0; q <= qLimit; q++) {
                int localIdx = (bestLag + q) - startOriginFrame;
                if (localIdx >= 0 && localIdx < startFrames && qActive[q] && sActive[localIdx]) {
                    raw[q] = frameDot(template.data, q, startData, localIdx);
                    valid[q] = true;
                }
            }
            float[] smooth = smoothArray(raw, valid, 3);
            float peak = 0f;
            int anchorQ = -1;
            for (int q = qLimit; q >= 0; q--) {
                if (smooth[q] > peak) {
                    peak = smooth[q];
                }
                if (anchorQ < 0 && smooth[q] >= 0.50f) {
                    anchorQ = q;
                }
            }
            if (peak >= 0.54f && anchorQ >= 0) {
                float thresh = Math.max(0.36f, peak * 0.52f);
                // Идём влево от внутренней части опенинга к самому началу
                int startQ = anchorQ;
                int gap = 0;
                for (int q = anchorQ; q >= 0; q--) {
                    if (smooth[q] >= thresh) {
                        startQ = q;
                        gap = 0;
                    } else {
                        gap++;
                        if (gap > 12) break;
                    }
                }
                float edgeThresh = Math.max(0.32f, thresh * 0.90f);
                while (startQ < anchorQ && (!valid[startQ] || raw[startQ] < edgeThresh)) startQ++;
                while (startQ > 0 && valid[startQ - 1] && raw[startQ - 1] >= edgeThresh) startQ--;

                int candidateFrom = Math.max(0, Math.round((bestLag + startQ) * (HOP_MS / 1000f)));
                if (Math.abs(candidateFrom - fallbackFromSec) <= 18) {
                    refinedFrom = candidateFrom;
                }
            }
        }

        // 2. Уточнение правой границы (конца опенинга)
        if (endData != null && endFrames >= 16 && endData.length >= endFrames * NUM_BANDS) {
            boolean[] eActive = buildActiveMask(endData, endFrames);
            int qMin = Math.max(0, template.coreEndFrame - 96);
            int bestLag = refineMicroLag(template.data, qActive, qMin, qFrames - 1,
                    endData, eActive, endOriginFrame, endFrames, initialLag, 6);

            int len = qFrames - qMin;
            float[] raw = new float[len];
            boolean[] valid = new boolean[len];
            for (int i = 0; i < len; i++) {
                int q = qMin + i;
                int localIdx = (bestLag + q) - endOriginFrame;
                if (localIdx >= 0 && localIdx < endFrames && qActive[q] && eActive[localIdx]) {
                    raw[i] = frameDot(template.data, q, endData, localIdx);
                    valid[i] = true;
                }
            }
            float[] smooth = smoothArray(raw, valid, 3);
            float peak = 0f;
            int anchorIdx = -1;
            for (int i = 0; i < len; i++) {
                if (smooth[i] > peak) {
                    peak = smooth[i];
                }
                if (anchorIdx < 0 && smooth[i] >= 0.50f) {
                    anchorIdx = i;
                }
            }
            if (peak >= 0.54f && anchorIdx >= 0) {
                float thresh = Math.max(0.36f, peak * 0.52f);
                // Идём вправо от внутренней части опенинга до конца
                int endIdx = anchorIdx;
                int gap = 0;
                int recoveryRun = 0;
                for (int i = anchorIdx; i < len; i++) {
                    if (smooth[i] >= thresh) {
                        if (gap == 0) {
                            endIdx = i;
                        } else {
                            recoveryRun++;
                            if (recoveryRun >= 8) {
                                // Устойчивое продолжение (>= 2с) после короткого провала (<= 3с)
                                endIdx = i;
                                gap = 0;
                                recoveryRun = 0;
                            }
                        }
                    } else {
                        recoveryRun = 0;
                        gap++;
                        if (gap > 12) break;
                    }
                }
                float edgeThresh = Math.max(0.32f, thresh * 0.90f);
                while (endIdx > anchorIdx && (!valid[endIdx] || raw[endIdx] < edgeThresh)) endIdx--;
                while (endIdx < len - 1 && valid[endIdx + 1] && raw[endIdx + 1] >= edgeThresh) endIdx++;

                int candidateTo = Math.round((bestLag + qMin + endIdx + 1) * (HOP_MS / 1000f));
                if (Math.abs(candidateTo - fallbackToSec) <= 20) {
                    refinedTo = candidateTo;
                }
            }
        }

        if (refinedTo < refinedFrom + 20 || refinedTo > refinedFrom + 160) {
            return new int[]{fallbackFromSec, fallbackToSec};
        }
        return new int[]{refinedFrom, refinedTo};
    }

    private static int refineMicroLag(@NonNull byte[] qData,
                                      @NonNull boolean[] qActive,
                                      int qStart,
                                      int qEnd,
                                      @NonNull byte[] winData,
                                      @NonNull boolean[] winActive,
                                      int winOriginFrame,
                                      int winFrames,
                                      int initialLag,
                                      int radius) {
        int bestLag = initialLag;
        float bestScore = -2f;
        for (int d = -radius; d <= radius; d++) {
            int candLag = initialLag + d;
            float sum = 0f;
            int cnt = 0;
            for (int q = qStart; q <= qEnd; q++) {
                int localIdx = (candLag + q) - winOriginFrame;
                if (localIdx >= 0 && localIdx < winFrames && qActive[q] && winActive[localIdx]) {
                    sum += frameDot(qData, q, winData, localIdx);
                    cnt++;
                }
            }
            if (cnt >= 10) {
                float mean = sum / cnt;
                if (mean > bestScore) {
                    bestScore = mean;
                    bestLag = candLag;
                }
            }
        }
        return bestLag;
    }

    private static float[] smoothArray(@NonNull float[] raw, @NonNull boolean[] valid, int halfWin) {
        int n = raw.length;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - halfWin);
            int to = Math.min(n - 1, i + halfWin);
            float sum = 0f;
            int cnt = 0;
            for (int k = from; k <= to; k++) {
                if (valid[k]) {
                    sum += raw[k];
                    cnt++;
                }
            }
            out[i] = cnt >= 2 ? (sum / cnt) : 0f;
        }
        return out;
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

        int targetStartFrame = Math.max(0, lag + spanStart);
        int targetEndFrame = Math.min(targetFrames, lag + spanEnd + 1);
        boolean touchesRightEdge = !isFinalPass && targetEndFrame >= targetFrames - (8 * FRAMES_PER_SECOND);

        int finalSpanFrames = spanEnd - spanStart + 1;
        float spanDurationSec = finalSpanFrames * (HOP_MS / 1000f);

        // Вычисляем среднюю схожесть на активных кадрах найденного опенинга
        float sumSpanSim = 0f;
        int activeSpanFrames = 0;
        for (int i = spanStart; i <= spanEnd; i++) {
            if (validActive[i]) {
                sumSpanSim += rawSim[i];
                activeSpanFrames++;
            }
        }
        if (activeSpanFrames == 0) {
            return null;
        }
        float meanSpanSim = sumSpanSim / activeSpanFrames;

        float zScore = (bgStd > 0.015f)
                ? (Math.max(coreScore, meanSpanSim) - bgMean) / bgStd
                : (Math.max(coreScore, meanSpanSim) - bgMean) * 20f;

        if (touchesRightEdge) {
            // Этап 1 (мгновенное раннее обнаружение): опенинг уже уверенно идёт на правом краю
            // декодированного буфера (например, начинается сразу с 0:00). Не ждём загрузки всех 90с:
            // возвращаем предварительный результат с точным стартом и расчётным концом по шаблону,
            // а фоновый скан тихо уточнит точную секунду конца, когда догрузит остаток.
            boolean startsNearOpeningBegin = spanStart <= template.coreStartFrame + 28;
            if (!startsNearOpeningBegin || spanDurationSec < 13.5f || activeSpanFrames < 36) {
                return null;
            }
            boolean strongEarlyAbsolute = meanSpanSim >= 0.65f && peakSmooth >= 0.72f;
            boolean strongEarlyRelative = meanSpanSim >= 0.54f && peakSmooth >= 0.64f
                    && (meanSpanSim - bgMean) >= 0.25f && zScore >= 4.0f;
            if (!strongEarlyAbsolute && !strongEarlyRelative) {
                return null;
            }

            int fromSec = Math.max(0, Math.round(targetStartFrame * (HOP_MS / 1000f)));
            int alignedCoreEndSec = Math.round((lag + template.coreEndFrame + 1) * (HOP_MS / 1000f));
            int minEstToSec = fromSec + Math.max(30, template.skipDurationSec - 10);
            int maxEstToSec = fromSec + 150;
            int estToSec = Math.max(minEstToSec, Math.min(maxEstToSec, alignedCoreEndSec));
            return new MatchResult(fromSec, estToSec, meanSpanSim, zScore, lag, template.id, true);
        }

        // Этап 2 (полное точное совпадение): опенинг целиком поместился в буфер
        float minRequiredSec = Math.max(24f, Math.min(38f, template.skipDurationSec * 0.52f));
        if (spanDurationSec < minRequiredSec || spanDurationSec > 165f) {
            return null;
        }
        if (activeSpanFrames < minRequiredSec * 2.2f) {
            return null;
        }

        boolean strongAbsolute = meanSpanSim >= 0.62f && peakSmooth >= 0.68f;
        boolean strongRelative = meanSpanSim >= 0.48f && peakSmooth >= 0.58f
                && (meanSpanSim - bgMean) >= 0.24f && zScore >= 3.5f;

        if (!strongAbsolute && !strongRelative) {
            return null;
        }

        int fromSec = Math.max(0, Math.round(targetStartFrame * (HOP_MS / 1000f)));
        int toSec = Math.round(targetEndFrame * (HOP_MS / 1000f));
        if (toSec <= fromSec + 15) {
            return null;
        }

        return new MatchResult(fromSec, toSec, meanSpanSim, zScore, lag, template.id, false);
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
