package com.example.animelib.util;

/**
 * Высокоскоростной генератор кинематографичной краевой фоновой подсветки (Edge-Diffused Ambilight).
 *
 * Особенности v2 (Zero-Lag VSYNC Sync):
 * 1. Все геометрические коэффициенты 9-зонного поля (96x72), альфа-кривая затухания,
 *    веса углов/центра и кривая яркости предрассчитаны в LUT-таблицах при инициализации
 *    (0 вызовов Math.sqrt / Math.cos / Math.pow на пиксель в рантайме -> < 0.1 мс на кадр).
 * 2. Сценочувствительное темпоральное сглаживание (Scene-Cut-Aware EMA):
 *    - При резкой смене кадра / вспышке / переходе в чёрный — мгновенное переключение (alpha = 1.0)
 *      без «шлейфа» предыдущей сцены.
 *    - На динамичных сценах — быстрая непрерывная кривая отклика, нормированная по реальному dtMs.
 *    - На статичных кадрах — мягкое подавление мелкого кодекового шума без мерцания.
 */
public class FastBlurUtils {

    public static final int GLOW_WIDTH = 96;
    public static final int GLOW_HEIGHT = 72;

    private static final int THIRD_W = GLOW_WIDTH / 3;   // 32
    private static final int TWO_THIRD_W = THIRD_W * 2;  // 64
    private static final int THIRD_H = GLOW_HEIGHT / 3;  // 24
    private static final int TWO_THIRD_H = THIRD_H * 2;  // 48

    private static final int MAX_EDGE_ALPHA = 248;
    private static final int MAX_EDGE_ALPHA_SHIFTED = MAX_EDGE_ALPHA << 24;
    private static final float MIN_OUTER_FALLOFF = 0.31f;
    private static final float REFERENCE_FRAME_MS = 41.67f; // 24 FPS (стандарт аниме)

    // Порог среднего изменения по всему периметру для мгновенной реакции на смену сцены
    private static final float SCENE_CUT_MEAN_DIFF = 26.0f;

    private final int sampleWidth;
    private final int sampleHeight;

    // Извлеченные и сглаженные профили краёв (RGB в float 0..255 без аллокаций в рантайме)
    private final float[] topNearR, topNearG, topNearB;
    private final float[] topFarR, topFarG, topFarB;
    private final float[] bottomNearR, bottomNearG, bottomNearB;
    private final float[] bottomFarR, bottomFarG, bottomFarB;
    private final float[] leftNearR, leftNearG, leftNearB;
    private final float[] leftFarR, leftFarG, leftFarB;
    private final float[] rightNearR, rightNearG, rightNearB;
    private final float[] rightFarR, rightFarG, rightFarB;

    // Временные буферы для 1D размытия
    private final float[] tempR, tempG, tempB;

    // История для темпорального сглаживания профилей
    private final float[] histTopNearR, histTopNearG, histTopNearB;
    private final float[] histTopFarR, histTopFarG, histTopFarB;
    private final float[] histBottomNearR, histBottomNearG, histBottomNearB;
    private final float[] histBottomFarR, histBottomFarG, histBottomFarB;
    private final float[] histLeftNearR, histLeftNearG, histLeftNearB;
    private final float[] histLeftFarR, histLeftFarG, histLeftFarB;
    private final float[] histRightNearR, histRightNearG, histRightNearB;
    private final float[] histRightFarR, histRightFarG, histRightFarB;

    // Ресэмплированные под разрешение сетки (THIRD_W / THIRD_H) профили — 1 раз за кадр вместо 6912
    private final float[] resTopNearR = new float[THIRD_W];
    private final float[] resTopNearG = new float[THIRD_W];
    private final float[] resTopNearB = new float[THIRD_W];
    private final float[] resTopFarR = new float[THIRD_W];
    private final float[] resTopFarG = new float[THIRD_W];
    private final float[] resTopFarB = new float[THIRD_W];

    private final float[] resBottomNearR = new float[THIRD_W];
    private final float[] resBottomNearG = new float[THIRD_W];
    private final float[] resBottomNearB = new float[THIRD_W];
    private final float[] resBottomFarR = new float[THIRD_W];
    private final float[] resBottomFarG = new float[THIRD_W];
    private final float[] resBottomFarB = new float[THIRD_W];

    private final float[] resLeftNearR = new float[THIRD_H];
    private final float[] resLeftNearG = new float[THIRD_H];
    private final float[] resLeftNearB = new float[THIRD_H];
    private final float[] resLeftFarR = new float[THIRD_H];
    private final float[] resLeftFarG = new float[THIRD_H];
    private final float[] resLeftFarB = new float[THIRD_H];

    private final float[] resRightNearR = new float[THIRD_H];
    private final float[] resRightNearG = new float[THIRD_H];
    private final float[] resRightNearB = new float[THIRD_H];
    private final float[] resRightFarR = new float[THIRD_H];
    private final float[] resRightFarG = new float[THIRD_H];
    private final float[] resRightFarB = new float[THIRD_H];

    // Предрасчитанные LUT-таблицы геометрии и калибровки
    private final float[] vBoostLut = new float[256];
    private final int[] gridAlphaShifted = new int[GLOW_WIDTH * GLOW_HEIGHT];
    private final float[] gridDiffuseMix = new float[GLOW_WIDTH * GLOW_HEIGHT];
    private final float[] gridCornerWx = new float[GLOW_WIDTH * GLOW_HEIGHT];
    private final float[] centerWL = new float[THIRD_W * THIRD_H];
    private final float[] centerWR = new float[THIRD_W * THIRD_H];
    private final float[] centerWT = new float[THIRD_W * THIRD_H];
    private final float[] centerWB = new float[THIRD_W * THIRD_H];

    private boolean hasHistory = false;

    public FastBlurUtils(int sampleWidth, int sampleHeight, int unusedRadius) {
        this.sampleWidth = Math.max(8, sampleWidth);
        this.sampleHeight = Math.max(8, sampleHeight);

        int w = this.sampleWidth;
        int h = this.sampleHeight;
        int maxDim = Math.max(w, h);

        topNearR = new float[w]; topNearG = new float[w]; topNearB = new float[w];
        topFarR = new float[w]; topFarG = new float[w]; topFarB = new float[w];
        bottomNearR = new float[w]; bottomNearG = new float[w]; bottomNearB = new float[w];
        bottomFarR = new float[w]; bottomFarG = new float[w]; bottomFarB = new float[w];

        leftNearR = new float[h]; leftNearG = new float[h]; leftNearB = new float[h];
        leftFarR = new float[h]; leftFarG = new float[h]; leftFarB = new float[h];
        rightNearR = new float[h]; rightNearG = new float[h]; rightNearB = new float[h];
        rightFarR = new float[h]; rightFarG = new float[h]; rightFarB = new float[h];

        tempR = new float[maxDim];
        tempG = new float[maxDim];
        tempB = new float[maxDim];

        histTopNearR = new float[w]; histTopNearG = new float[w]; histTopNearB = new float[w];
        histTopFarR = new float[w]; histTopFarG = new float[w]; histTopFarB = new float[w];
        histBottomNearR = new float[w]; histBottomNearG = new float[w]; histBottomNearB = new float[w];
        histBottomFarR = new float[w]; histBottomFarG = new float[w]; histBottomFarB = new float[w];

        histLeftNearR = new float[h]; histLeftNearG = new float[h]; histLeftNearB = new float[h];
        histLeftFarR = new float[h]; histLeftFarG = new float[h]; histLeftFarB = new float[h];
        histRightNearR = new float[h]; histRightNearG = new float[h]; histRightNearB = new float[h];
        histRightFarR = new float[h]; histRightFarG = new float[h]; histRightFarB = new float[h];

        // Предрасчет LUT калибровки яркости (убирает Math.pow из каждого кадра)
        for (int i = 0; i < 256; i++) {
            if (i <= 4) {
                vBoostLut[i] = 0f;
            } else {
                float darkToe = i < 14 ? smoothstep(4f, 14f, (float) i) : 1f;
                float vNorm = i / 255f;
                float vBoost = (float) Math.pow(vNorm, 0.85) * 1.06f;
                if (vBoost > 0.90f) {
                    vBoost = 0.90f + (vBoost - 0.90f) * 0.25f;
                }
                vBoostLut[i] = Math.min(0.95f, vBoost) * darkToe * 255f;
            }
        }

        // Предрасчет статической 2D геометрии сетки 96x72
        float[] txArr = new float[GLOW_WIDTH];
        float[] tyArr = new float[GLOW_HEIGHT];
        for (int gx = 0; gx < GLOW_WIDTH; gx++) {
            float cx = gx + 0.5f;
            if (cx < THIRD_W) {
                txArr[gx] = (THIRD_W - cx) / (float) THIRD_W;
            } else if (cx > TWO_THIRD_W) {
                txArr[gx] = (cx - TWO_THIRD_W) / (float) THIRD_W;
            } else {
                txArr[gx] = 0f;
            }
        }
        for (int gy = 0; gy < GLOW_HEIGHT; gy++) {
            float cy = gy + 0.5f;
            if (cy < THIRD_H) {
                tyArr[gy] = (THIRD_H - cy) / (float) THIRD_H;
            } else if (cy > TWO_THIRD_H) {
                tyArr[gy] = (cy - TWO_THIRD_H) / (float) THIRD_H;
            } else {
                tyArr[gy] = 0f;
            }
        }

        for (int gy = 0; gy < GLOW_HEIGHT; gy++) {
            float ty = tyArr[gy];
            int rowOff = gy * GLOW_WIDTH;
            for (int gx = 0; gx < GLOW_WIDTH; gx++) {
                float tx = txArr[gx];
                int idx = rowOff + gx;
                if (tx == 0f && ty == 0f) {
                    gridAlphaShifted[idx] = MAX_EDGE_ALPHA_SHIFTED;
                    gridDiffuseMix[idx] = 0f;
                    gridCornerWx[idx] = 0.5f;
                } else {
                    float dist = (float) Math.sqrt(tx * tx + ty * ty);
                    gridDiffuseMix[idx] = smoothstep(0.05f, 0.95f, Math.min(1f, dist));
                    int alpha = Math.round(MAX_EDGE_ALPHA * cinemaFalloff(dist));
                    gridAlphaShifted[idx] = alpha << 24;
                    float tx2 = tx * tx;
                    float ty2 = ty * ty;
                    float sum2 = tx2 + ty2;
                    gridCornerWx[idx] = sum2 > 0f ? (tx2 / sum2) : 0.5f;
                }
            }
        }

        // Предрасчет нормализованных весов для центральной области [THIRD_W..TWO_THIRD_W, THIRD_H..TWO_THIRD_H]
        for (int cy = 0; cy < THIRD_H; cy++) {
            float v = (cy + 0.5f) / (float) THIRD_H;
            float dT = v + 0.001f;
            float dB = (1f - v) + 0.001f;
            float wT = 1f / (dT * dT);
            float wB = 1f / (dB * dB);
            int rowOff = cy * THIRD_W;
            for (int cx = 0; cx < THIRD_W; cx++) {
                float u = (cx + 0.5f) / (float) THIRD_W;
                float dL = u + 0.001f;
                float dR = (1f - u) + 0.001f;
                float wL = 1f / (dL * dL);
                float wR = 1f / (dR * dR);
                float invSum = 1f / (wL + wR + wT + wB);
                int cIdx = rowOff + cx;
                centerWL[cIdx] = wL * invSum;
                centerWR[cIdx] = wR * invSum;
                centerWT[cIdx] = wT * invSum;
                centerWB[cIdx] = wB * invSum;
            }
        }
    }

    public void resetHistory() {
        hasHistory = false;
    }

    /**
     * Строит поле краевого рассеивания (GLOW_WIDTH x GLOW_HEIGHT) с эталонным шагом 24 FPS.
     */
    public void buildEdgeGlowField(int[] samplePixels, int[] outGlowPixels) {
        buildEdgeGlowField(samplePixels, outGlowPixels, REFERENCE_FRAME_MS);
    }

    /**
     * Строит поле краевого рассеивания (GLOW_WIDTH x GLOW_HEIGHT) на основе сэмпла кадра
     * с учётом фактического времени между кадрами deltaMs.
     *
     * @param samplePixels  входной сэмпл размером sampleWidth * sampleHeight
     * @param outGlowPixels выходной массив размером GLOW_WIDTH * GLOW_HEIGHT (ARGB_8888)
     * @param deltaMs       интервал времени с предыдущего обновлённого видеокадра (в мс)
     */
    public void buildEdgeGlowField(int[] samplePixels, int[] outGlowPixels, float deltaMs) {
        if (samplePixels == null || samplePixels.length < sampleWidth * sampleHeight) {
            return;
        }
        if (outGlowPixels == null || outGlowPixels.length < GLOW_WIDTH * GLOW_HEIGHT) {
            return;
        }

        extractPerimeterStrips(samplePixels);

        int horizNearRadius = Math.max(1, Math.round(sampleWidth * 0.055f)); // ~2
        int horizFarRadius = Math.max(2, Math.round(sampleWidth * 0.14f));   // ~5
        int vertNearRadius = Math.max(1, Math.round(sampleHeight * 0.06f));  // ~1
        int vertFarRadius = Math.max(2, Math.round(sampleHeight * 0.16f));   // ~3

        smoothStrip1D(topNearR, topNearG, topNearB, sampleWidth, horizNearRadius, 1);
        smoothStrip1D(topFarR, topFarG, topFarB, sampleWidth, horizFarRadius, 2);
        smoothStrip1D(bottomNearR, bottomNearG, bottomNearB, sampleWidth, horizNearRadius, 1);
        smoothStrip1D(bottomFarR, bottomFarG, bottomFarB, sampleWidth, horizFarRadius, 2);

        smoothStrip1D(leftNearR, leftNearG, leftNearB, sampleHeight, vertNearRadius, 1);
        smoothStrip1D(leftFarR, leftFarG, leftFarB, sampleHeight, vertFarRadius, 2);
        smoothStrip1D(rightNearR, rightNearG, rightNearB, sampleHeight, vertNearRadius, 1);
        smoothStrip1D(rightFarR, rightFarG, rightFarB, sampleHeight, vertFarRadius, 2);

        calibrateStrip(topNearR, topNearG, topNearB, sampleWidth);
        calibrateStrip(topFarR, topFarG, topFarB, sampleWidth);
        calibrateStrip(bottomNearR, bottomNearG, bottomNearB, sampleWidth);
        calibrateStrip(bottomFarR, bottomFarG, bottomFarB, sampleWidth);

        calibrateStrip(leftNearR, leftNearG, leftNearB, sampleHeight);
        calibrateStrip(leftFarR, leftFarG, leftFarB, sampleHeight);
        calibrateStrip(rightNearR, rightNearG, rightNearB, sampleHeight);
        calibrateStrip(rightFarR, rightFarG, rightFarB, sampleHeight);

        // Детекция смены сцены (scene cut): если периметр резко изменился целиком — мгновенный snap без лага!
        boolean isSceneCut = !hasHistory || detectSceneCut();
        float dtScale = Math.max(8f, Math.min(120f, deltaMs)) / REFERENCE_FRAME_MS;

        temporalSmoothStrip(topNearR, topNearG, topNearB, histTopNearR, histTopNearG, histTopNearB, sampleWidth, isSceneCut, dtScale);
        temporalSmoothStrip(topFarR, topFarG, topFarB, histTopFarR, histTopFarG, histTopFarB, sampleWidth, isSceneCut, dtScale);
        temporalSmoothStrip(bottomNearR, bottomNearG, bottomNearB, histBottomNearR, histBottomNearG, histBottomNearB, sampleWidth, isSceneCut, dtScale);
        temporalSmoothStrip(bottomFarR, bottomFarG, bottomFarB, histBottomFarR, histBottomFarG, histBottomFarB, sampleWidth, isSceneCut, dtScale);

        temporalSmoothStrip(leftNearR, leftNearG, leftNearB, histLeftNearR, histLeftNearG, histLeftNearB, sampleHeight, isSceneCut, dtScale);
        temporalSmoothStrip(leftFarR, leftFarG, leftFarB, histLeftFarR, histLeftFarG, histLeftFarB, sampleHeight, isSceneCut, dtScale);
        temporalSmoothStrip(rightNearR, rightNearG, rightNearB, histRightNearR, histRightNearG, histRightNearB, sampleHeight, isSceneCut, dtScale);
        temporalSmoothStrip(rightFarR, rightFarG, rightFarB, histRightFarR, histRightFarG, histRightFarB, sampleHeight, isSceneCut, dtScale);

        hasHistory = true;

        // Ресэмплим профили 1 раз под разрешение сетки и заполняем 96x72 без тяжёлой математики
        resampleStripsToGrid();
        synthesizeGlowGridFast(outGlowPixels);
    }

    private boolean detectSceneCut() {
        float totalDiff = 0f;
        int count = (sampleWidth + sampleHeight) * 2;
        for (int i = 0; i < sampleWidth; i++) {
            float dTop = Math.max(Math.abs(topNearR[i] - histTopNearR[i]),
                    Math.max(Math.abs(topNearG[i] - histTopNearG[i]), Math.abs(topNearB[i] - histTopNearB[i])));
            float dBot = Math.max(Math.abs(bottomNearR[i] - histBottomNearR[i]),
                    Math.max(Math.abs(bottomNearG[i] - histBottomNearG[i]), Math.abs(bottomNearB[i] - histBottomNearB[i])));
            totalDiff += dTop + dBot;
        }
        for (int i = 0; i < sampleHeight; i++) {
            float dLeft = Math.max(Math.abs(leftNearR[i] - histLeftNearR[i]),
                    Math.max(Math.abs(leftNearG[i] - histLeftNearG[i]), Math.abs(leftNearB[i] - histLeftNearB[i])));
            float dRight = Math.max(Math.abs(rightNearR[i] - histRightNearR[i]),
                    Math.max(Math.abs(rightNearG[i] - histRightNearG[i]), Math.abs(rightNearB[i] - histRightNearB[i])));
            totalDiff += dLeft + dRight;
        }
        return (totalDiff / Math.max(1, count)) >= SCENE_CUT_MEAN_DIFF;
    }

    private void extractPerimeterStrips(int[] pix) {
        int w = sampleWidth;
        int h = sampleHeight;

        float[] vWeights = {0.22f, 0.38f, 0.26f, 0.14f};
        float[] hWeights = {0.22f, 0.38f, 0.26f, 0.14f};

        int maxRowDepth = Math.min(vWeights.length, h / 3);
        int maxColDepth = Math.min(hWeights.length, w / 3);

        for (int x = 0; x < w; x++) {
            float tr = 0f, tg = 0f, tb = 0f, tw = 0f;
            float br = 0f, bg = 0f, bb = 0f, bw = 0f;

            for (int d = 0; d < maxRowDepth; d++) {
                float weight = vWeights[d];
                int topP = pix[d * w + x];
                tr += ((topP >>> 16) & 0xFF) * weight;
                tg += ((topP >>> 8) & 0xFF) * weight;
                tb += (topP & 0xFF) * weight;
                tw += weight;

                int botP = pix[(h - 1 - d) * w + x];
                br += ((botP >>> 16) & 0xFF) * weight;
                bg += ((botP >>> 8) & 0xFF) * weight;
                bb += (botP & 0xFF) * weight;
                bw += weight;
            }

            if (tw > 0f) {
                float invTw = 1f / tw;
                topNearR[x] = topFarR[x] = tr * invTw;
                topNearG[x] = topFarG[x] = tg * invTw;
                topNearB[x] = topFarB[x] = tb * invTw;
            }
            if (bw > 0f) {
                float invBw = 1f / bw;
                bottomNearR[x] = bottomFarR[x] = br * invBw;
                bottomNearG[x] = bottomFarG[x] = bg * invBw;
                bottomNearB[x] = bottomFarB[x] = bb * invBw;
            }
        }

        for (int y = 0; y < h; y++) {
            float lr = 0f, lg = 0f, lb = 0f, lw = 0f;
            float rr = 0f, rg = 0f, rb = 0f, rw = 0f;
            int rowOff = y * w;

            for (int d = 0; d < maxColDepth; d++) {
                float weight = hWeights[d];
                int leftP = pix[rowOff + d];
                lr += ((leftP >>> 16) & 0xFF) * weight;
                lg += ((leftP >>> 8) & 0xFF) * weight;
                lb += (leftP & 0xFF) * weight;
                lw += weight;

                int rightP = pix[rowOff + (w - 1 - d)];
                rr += ((rightP >>> 16) & 0xFF) * weight;
                rg += ((rightP >>> 8) & 0xFF) * weight;
                rb += (rightP & 0xFF) * weight;
                rw += weight;
            }

            if (lw > 0f) {
                float invLw = 1f / lw;
                leftNearR[y] = leftFarR[y] = lr * invLw;
                leftNearG[y] = leftFarG[y] = lg * invLw;
                leftNearB[y] = leftFarB[y] = lb * invLw;
            }
            if (rw > 0f) {
                float invRw = 1f / rw;
                rightNearR[y] = rightFarR[y] = rr * invRw;
                rightNearG[y] = rightFarG[y] = rg * invRw;
                rightNearB[y] = rightFarB[y] = rb * invRw;
            }
        }
    }

    private void smoothStrip1D(float[] sr, float[] sg, float[] sb, int len, int radius, int passes) {
        if (len <= 1 || radius <= 0) return;
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 0; i < len; i++) {
                float sumR = 0f, sumG = 0f, sumB = 0f, wSum = 0f;
                int start = Math.max(0, i - radius);
                int end = Math.min(len - 1, i + radius);
                for (int j = start; j <= end; j++) {
                    float w = (radius + 1) - Math.abs(i - j);
                    sumR += sr[j] * w;
                    sumG += sg[j] * w;
                    sumB += sb[j] * w;
                    wSum += w;
                }
                if (wSum > 0f) {
                    float invW = 1f / wSum;
                    tempR[i] = sumR * invW;
                    tempG[i] = sumG * invW;
                    tempB[i] = sumB * invW;
                }
            }
            System.arraycopy(tempR, 0, sr, 0, len);
            System.arraycopy(tempG, 0, sg, 0, len);
            System.arraycopy(tempB, 0, sb, 0, len);
        }
    }

    private void calibrateStrip(float[] sr, float[] sg, float[] sb, int len) {
        for (int i = 0; i < len; i++) {
            float r = sr[i];
            float g = sg[i];
            float b = sb[i];

            float max = Math.max(r, Math.max(g, b));
            float min = Math.min(r, Math.min(g, b));
            float chroma = max - min;

            if (max <= 4f) {
                sr[i] = 0f;
                sg[i] = 0f;
                sb[i] = 0f;
                continue;
            }

            int maxIdx = Math.min(255, Math.max(0, Math.round(max)));
            float vBoost255 = vBoostLut[maxIdx];

            if (chroma < 0.5f) {
                sr[i] = vBoost255;
                sg[i] = vBoost255;
                sb[i] = vBoost255;
            } else {
                float s = chroma / max;
                float sBoost = Math.min(1.0f, s * 1.24f);
                float cBoost = sBoost * vBoost255;
                float mBoost = vBoost255 - cBoost;
                float ratio = cBoost / chroma;

                sr[i] = clamp255(mBoost + (r - min) * ratio);
                sg[i] = clamp255(mBoost + (g - min) * ratio);
                sb[i] = clamp255(mBoost + (b - min) * ratio);
            }
        }
    }

    private void temporalSmoothStrip(float[] sr, float[] sg, float[] sb,
                                     float[] hr, float[] hg, float[] hb,
                                     int len, boolean snapImmediate, float dtScale) {
        if (snapImmediate) {
            System.arraycopy(sr, 0, hr, 0, len);
            System.arraycopy(sg, 0, hg, 0, len);
            System.arraycopy(sb, 0, hb, 0, len);
            return;
        }

        for (int i = 0; i < len; i++) {
            float diffR = Math.abs(sr[i] - hr[i]);
            float diffG = Math.abs(sg[i] - hg[i]);
            float diffB = Math.abs(sb[i] - hb[i]);
            float maxDiff = Math.max(diffR, Math.max(diffG, diffB));

            float alpha;
            if (maxDiff >= 52f) {
                alpha = 0.96f;
            } else {
                float t = smoothstep(8f, 52f, maxDiff);
                float baseAlpha = 0.34f + 0.62f * t;
                // Фреймрейт-независимый отклик (одинаково быстрый на 24/30/60 FPS)
                alpha = Math.abs(dtScale - 1f) < 0.08f
                        ? baseAlpha
                        : (1f - (float) Math.pow(1f - baseAlpha, dtScale));
            }

            float inv = 1.0f - alpha;
            float nr = sr[i] * alpha + hr[i] * inv;
            float ng = sg[i] * alpha + hg[i] * inv;
            float nb = sb[i] * alpha + hb[i] * inv;

            hr[i] = nr; hg[i] = ng; hb[i] = nb;
            sr[i] = nr; sg[i] = ng; sb[i] = nb;
        }
    }

    private void resampleStripsToGrid() {
        for (int i = 0; i < THIRD_W; i++) {
            float u = (i + 0.5f) / (float) THIRD_W;
            resTopNearR[i] = sampleChannel(topNearR, sampleWidth, u);
            resTopNearG[i] = sampleChannel(topNearG, sampleWidth, u);
            resTopNearB[i] = sampleChannel(topNearB, sampleWidth, u);
            resTopFarR[i] = sampleChannel(topFarR, sampleWidth, u);
            resTopFarG[i] = sampleChannel(topFarG, sampleWidth, u);
            resTopFarB[i] = sampleChannel(topFarB, sampleWidth, u);

            resBottomNearR[i] = sampleChannel(bottomNearR, sampleWidth, u);
            resBottomNearG[i] = sampleChannel(bottomNearG, sampleWidth, u);
            resBottomNearB[i] = sampleChannel(bottomNearB, sampleWidth, u);
            resBottomFarR[i] = sampleChannel(bottomFarR, sampleWidth, u);
            resBottomFarG[i] = sampleChannel(bottomFarG, sampleWidth, u);
            resBottomFarB[i] = sampleChannel(bottomFarB, sampleWidth, u);
        }

        for (int i = 0; i < THIRD_H; i++) {
            float v = (i + 0.5f) / (float) THIRD_H;
            resLeftNearR[i] = sampleChannel(leftNearR, sampleHeight, v);
            resLeftNearG[i] = sampleChannel(leftNearG, sampleHeight, v);
            resLeftNearB[i] = sampleChannel(leftNearB, sampleHeight, v);
            resLeftFarR[i] = sampleChannel(leftFarR, sampleHeight, v);
            resLeftFarG[i] = sampleChannel(leftFarG, sampleHeight, v);
            resLeftFarB[i] = sampleChannel(leftFarB, sampleHeight, v);

            resRightNearR[i] = sampleChannel(rightNearR, sampleHeight, v);
            resRightNearG[i] = sampleChannel(rightNearG, sampleHeight, v);
            resRightNearB[i] = sampleChannel(rightNearB, sampleHeight, v);
            resRightFarR[i] = sampleChannel(rightFarR, sampleHeight, v);
            resRightFarG[i] = sampleChannel(rightFarG, sampleHeight, v);
            resRightFarB[i] = sampleChannel(rightFarB, sampleHeight, v);
        }
    }

    private void synthesizeGlowGridFast(int[] out) {
        // 1. Верхняя зона (y: 0..THIRD_H-1)
        for (int gy = 0; gy < THIRD_H; gy++) {
            int rowOff = gy * GLOW_WIDTH;
            // Верхний-левый угол
            fillCornerRow(out, rowOff, 0, THIRD_W,
                    leftNearR[0], leftNearG[0], leftNearB[0], leftFarR[0], leftFarG[0], leftFarB[0],
                    topNearR[0], topNearG[0], topNearB[0], topFarR[0], topFarG[0], topFarB[0]);
            // Верхняя полоса
            for (int cx = 0; cx < THIRD_W; cx++) {
                int idx = rowOff + THIRD_W + cx;
                float m = gridDiffuseMix[idx];
                float r = resTopNearR[cx] + (resTopFarR[cx] - resTopNearR[cx]) * m;
                float g = resTopNearG[cx] + (resTopFarG[cx] - resTopNearG[cx]) * m;
                float b = resTopNearB[cx] + (resTopFarB[cx] - resTopNearB[cx]) * m;
                out[idx] = gridAlphaShifted[idx] | (clampInt(r) << 16) | (clampInt(g) << 8) | clampInt(b);
            }
            // Верхний-правый угол
            int lastX = sampleWidth - 1;
            fillCornerRow(out, rowOff, TWO_THIRD_W, GLOW_WIDTH,
                    rightNearR[0], rightNearG[0], rightNearB[0], rightFarR[0], rightFarG[0], rightFarB[0],
                    topNearR[lastX], topNearG[lastX], topNearB[lastX], topFarR[lastX], topFarG[lastX], topFarB[lastX]);
        }

        // 2. Средняя зона (y: THIRD_H..TWO_THIRD_H-1)
        for (int cy = 0; cy < THIRD_H; cy++) {
            int gy = THIRD_H + cy;
            int rowOff = gy * GLOW_WIDTH;
            int cRowOff = cy * THIRD_W;

            float lnR = resLeftNearR[cy], lnG = resLeftNearG[cy], lnB = resLeftNearB[cy];
            float ldR = resLeftFarR[cy] - lnR, ldG = resLeftFarG[cy] - lnG, ldB = resLeftFarB[cy] - lnB;
            // Левая полоса
            for (int gx = 0; gx < THIRD_W; gx++) {
                int idx = rowOff + gx;
                float m = gridDiffuseMix[idx];
                out[idx] = gridAlphaShifted[idx]
                        | (clampInt(lnR + ldR * m) << 16)
                        | (clampInt(lnG + ldG * m) << 8)
                        | clampInt(lnB + ldB * m);
            }

            float rnR = resRightNearR[cy], rnG = resRightNearG[cy], rnB = resRightNearB[cy];
            // Центральная зона (за плеером — только для скруглённых углов при шторке)
            for (int cx = 0; cx < THIRD_W; cx++) {
                int cIdx = cRowOff + cx;
                float wL = centerWL[cIdx];
                float wR = centerWR[cIdx];
                float wT = centerWT[cIdx];
                float wB = centerWB[cIdx];
                float r = lnR * wL + rnR * wR + resTopNearR[cx] * wT + resBottomNearR[cx] * wB;
                float g = lnG * wL + rnG * wR + resTopNearG[cx] * wT + resBottomNearG[cx] * wB;
                float b = lnB * wL + rnB * wR + resTopNearB[cx] * wT + resBottomNearB[cx] * wB;
                out[rowOff + THIRD_W + cx] = MAX_EDGE_ALPHA_SHIFTED
                        | (clampInt(r) << 16) | (clampInt(g) << 8) | clampInt(b);
            }

            float rdR = resRightFarR[cy] - rnR, rdG = resRightFarG[cy] - rnG, rdB = resRightFarB[cy] - rnB;
            // Правая полоса
            for (int gx = TWO_THIRD_W; gx < GLOW_WIDTH; gx++) {
                int idx = rowOff + gx;
                float m = gridDiffuseMix[idx];
                out[idx] = gridAlphaShifted[idx]
                        | (clampInt(rnR + rdR * m) << 16)
                        | (clampInt(rnG + rdG * m) << 8)
                        | clampInt(rnB + rdB * m);
            }
        }

        // 3. Нижняя зона (y: TWO_THIRD_H..GLOW_HEIGHT-1)
        int lastY = sampleHeight - 1;
        int lastX = sampleWidth - 1;
        for (int gy = TWO_THIRD_H; gy < GLOW_HEIGHT; gy++) {
            int rowOff = gy * GLOW_WIDTH;
            // Нижний-левый угол
            fillCornerRow(out, rowOff, 0, THIRD_W,
                    leftNearR[lastY], leftNearG[lastY], leftNearB[lastY], leftFarR[lastY], leftFarG[lastY], leftFarB[lastY],
                    bottomNearR[0], bottomNearG[0], bottomNearB[0], bottomFarR[0], bottomFarG[0], bottomFarB[0]);
            // Нижняя полоса
            for (int cx = 0; cx < THIRD_W; cx++) {
                int idx = rowOff + THIRD_W + cx;
                float m = gridDiffuseMix[idx];
                float r = resBottomNearR[cx] + (resBottomFarR[cx] - resBottomNearR[cx]) * m;
                float g = resBottomNearG[cx] + (resBottomFarG[cx] - resBottomNearG[cx]) * m;
                float b = resBottomNearB[cx] + (resBottomFarB[cx] - resBottomNearB[cx]) * m;
                out[idx] = gridAlphaShifted[idx] | (clampInt(r) << 16) | (clampInt(g) << 8) | clampInt(b);
            }
            // Нижний-правый угол
            fillCornerRow(out, rowOff, TWO_THIRD_W, GLOW_WIDTH,
                    rightNearR[lastY], rightNearG[lastY], rightNearB[lastY], rightFarR[lastY], rightFarG[lastY], rightFarB[lastY],
                    bottomNearR[lastX], bottomNearG[lastX], bottomNearB[lastX], bottomFarR[lastX], bottomFarG[lastX], bottomFarB[lastX]);
        }
    }

    private void fillCornerRow(int[] out, int rowOff, int startX, int endX,
                               float hnR, float hnG, float hnB, float hfR, float hfG, float hfB,
                               float vnR, float vnG, float vnB, float vfR, float vfG, float vfB) {
        float hdR = hfR - hnR, hdG = hfG - hnG, hdB = hfB - hnB;
        float vdR = vfR - vnR, vdG = vfG - vnG, vdB = vfB - vnB;
        for (int gx = startX; gx < endX; gx++) {
            int idx = rowOff + gx;
            float m = gridDiffuseMix[idx];
            float wx = gridCornerWx[idx];
            float wy = 1f - wx;

            float hr = hnR + hdR * m;
            float hg = hnG + hdG * m;
            float hb = hnB + hdB * m;

            float vr = vnR + vdR * m;
            float vg = vnG + vdG * m;
            float vb = vnB + vdB * m;

            out[idx] = gridAlphaShifted[idx]
                    | (clampInt(hr * wx + vr * wy) << 16)
                    | (clampInt(hg * wx + vg * wy) << 8)
                    | clampInt(hb * wx + vb * wy);
        }
    }

    /**
     * Единая сверхплавная кривая затухания по всей ширине пустой области.
     */
    public static float cinemaFalloff(float distNorm) {
        if (distNorm <= 0f) return 1f;
        if (distNorm >= 1f) return MIN_OUTER_FALLOFF;
        double cosHalf = 0.5 * (1.0 + Math.cos(Math.PI * distNorm));
        float eased = (float) Math.pow(cosHalf, 0.78);
        return MIN_OUTER_FALLOFF + (1.0f - MIN_OUTER_FALLOFF) * eased;
    }

    private static float sampleChannel(float[] strip, int len, float t) {
        if (len <= 0) return 0f;
        if (len == 1) return strip[0];
        float pos = clamp01(t) * (len - 1);
        int i0 = (int) pos;
        int i1 = Math.min(len - 1, i0 + 1);
        float frac = pos - i0;
        return strip[i0] + (strip[i1] - strip[i0]) * frac;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp255(float v) {
        return v < 0f ? 0f : (v > 255f ? 255f : v);
    }

    private static int clampInt(float v) {
        int iv = (int) (v + 0.5f);
        return iv < 0 ? 0 : (iv > 255 ? 255 : iv);
    }
}
