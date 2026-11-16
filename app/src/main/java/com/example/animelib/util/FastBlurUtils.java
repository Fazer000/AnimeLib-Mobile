package com.example.animelib.util;

/**
 * Генератор кинематографичной краевой фоновой подсветки (Edge-Diffused Ambilight).
 *
 * Принцип работы:
 * 1. Из сэмпла видеокадра (36x20) извлекаются ТОЛЬКО периметральные полосы краёв
 *    (левый, правый, верхний, нижний) — центр кадра полностью исключается,
 *    благодаря чему подсветка никогда не дублирует объекты и силуэты видео.
 * 2. Для каждого края строятся два уровня пространственного размытия:
 *    - Near (мягкий локальный цветовой контур у границы кадра)
 *    - Far (широкое атмосферное рассеивание по мере удаления от края видео)
 * 3. Цвета калибруются с сохранением чистого тона (Pure Hue), мягким ограничением
 *    ослепляющего белого и плавным спадом в темноте без мерцания.
 * 4. Адаптивное темпоральное сглаживание (EMA) по профилям краёв убирает шум и скачки.
 * 5. Итоговое поле подсветки (60x48, сетка 3x3 по 20x16 текселей на зону) содержит
 *    бесшовную диффузию краёв с плавной S-кривой затухания для растяжения ровно
 *    на ширину пустых областей вокруг плеера.
 */
public class FastBlurUtils {

    public static final int GLOW_WIDTH = 96;
    public static final int GLOW_HEIGHT = 72;

    private static final int THIRD_W = GLOW_WIDTH / 3;   // 32
    private static final int TWO_THIRD_W = THIRD_W * 2;  // 64
    private static final int THIRD_H = GLOW_HEIGHT / 3;  // 24
    private static final int TWO_THIRD_H = THIRD_H * 2;  // 48

    private static final int MAX_EDGE_ALPHA = 248;

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

    // Предрасчитанные 1D координаты и кривая затухания для таблицы GLOW_WIDTH x GLOW_HEIGHT
    private final float[] gridU = new float[GLOW_WIDTH];
    private final float[] gridV = new float[GLOW_HEIGHT];
    private final float[] gridTx = new float[GLOW_WIDTH];
    private final float[] gridTy = new float[GLOW_HEIGHT];

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

        for (int gx = 0; gx < GLOW_WIDTH; gx++) {
            float cx = gx + 0.5f;
            gridU[gx] = clamp01((cx - THIRD_W) / (float) THIRD_W);
            if (cx < THIRD_W) {
                gridTx[gx] = (THIRD_W - cx) / (float) THIRD_W;
            } else if (cx > TWO_THIRD_W) {
                gridTx[gx] = (cx - TWO_THIRD_W) / (float) THIRD_W;
            } else {
                gridTx[gx] = 0f;
            }
        }

        for (int gy = 0; gy < GLOW_HEIGHT; gy++) {
            float cy = gy + 0.5f;
            gridV[gy] = clamp01((cy - THIRD_H) / (float) THIRD_H);
            if (cy < THIRD_H) {
                gridTy[gy] = (THIRD_H - cy) / (float) THIRD_H;
            } else if (cy > TWO_THIRD_H) {
                gridTy[gy] = (cy - TWO_THIRD_H) / (float) THIRD_H;
            } else {
                gridTy[gy] = 0f;
            }
        }
    }

    public void resetHistory() {
        hasHistory = false;
    }

    /**
     * Строит поле краевого рассеивания (GLOW_WIDTH x GLOW_HEIGHT) на основе сэмпла кадра.
     *
     * @param samplePixels входной сэмпл размером sampleWidth * sampleHeight
     * @param outGlowPixels выходной массив размером GLOW_WIDTH * GLOW_HEIGHT (ARGB_8888, non-premultiplied)
     */
    public void buildEdgeGlowField(int[] samplePixels, int[] outGlowPixels) {
        if (samplePixels == null || samplePixels.length < sampleWidth * sampleHeight) {
            return;
        }
        if (outGlowPixels == null || outGlowPixels.length < GLOW_WIDTH * GLOW_HEIGHT) {
            return;
        }

        extractPerimeterStrips(samplePixels);

        int horizNearRadius = Math.max(3, Math.round(sampleWidth * 0.18f)); // ~6
        int horizFarRadius = Math.max(6, Math.round(sampleWidth * 0.42f));  // ~15
        int vertNearRadius = Math.max(2, Math.round(sampleHeight * 0.20f)); // ~4
        int vertFarRadius = Math.max(5, Math.round(sampleHeight * 0.45f));  // ~9

        // Многопроходное сглаживание (аппроксимация Гаусса) для Near и Far полос
        smoothStrip1D(topNearR, topNearG, topNearB, sampleWidth, horizNearRadius, 2);
        smoothStrip1D(topFarR, topFarG, topFarB, sampleWidth, horizFarRadius, 3);
        smoothStrip1D(bottomNearR, bottomNearG, bottomNearB, sampleWidth, horizNearRadius, 2);
        smoothStrip1D(bottomFarR, bottomFarG, bottomFarB, sampleWidth, horizFarRadius, 3);

        smoothStrip1D(leftNearR, leftNearG, leftNearB, sampleHeight, vertNearRadius, 2);
        smoothStrip1D(leftFarR, leftFarG, leftFarB, sampleHeight, vertFarRadius, 3);
        smoothStrip1D(rightNearR, rightNearG, rightNearB, sampleHeight, vertNearRadius, 2);
        smoothStrip1D(rightFarR, rightFarG, rightFarB, sampleHeight, vertFarRadius, 3);

        // Кинематографичная калибровка цвета (Pure Hue, мягкие блики, глубокий черный)
        calibrateStrip(topNearR, topNearG, topNearB, sampleWidth);
        calibrateStrip(topFarR, topFarG, topFarB, sampleWidth);
        calibrateStrip(bottomNearR, bottomNearG, bottomNearB, sampleWidth);
        calibrateStrip(bottomFarR, bottomFarG, bottomFarB, sampleWidth);

        calibrateStrip(leftNearR, leftNearG, leftNearB, sampleHeight);
        calibrateStrip(leftFarR, leftFarG, leftFarB, sampleHeight);
        calibrateStrip(rightNearR, rightNearG, rightNearB, sampleHeight);
        calibrateStrip(rightFarR, rightFarG, rightFarB, sampleHeight);

        // Адаптивное темпоральное сглаживание (EMA)
        temporalSmoothStrip(topNearR, topNearG, topNearB, histTopNearR, histTopNearG, histTopNearB, sampleWidth);
        temporalSmoothStrip(topFarR, topFarG, topFarB, histTopFarR, histTopFarG, histTopFarB, sampleWidth);
        temporalSmoothStrip(bottomNearR, bottomNearG, bottomNearB, histBottomNearR, histBottomNearG, histBottomNearB, sampleWidth);
        temporalSmoothStrip(bottomFarR, bottomFarG, bottomFarB, histBottomFarR, histBottomFarG, histBottomFarB, sampleWidth);

        temporalSmoothStrip(leftNearR, leftNearG, leftNearB, histLeftNearR, histLeftNearG, histLeftNearB, sampleHeight);
        temporalSmoothStrip(leftFarR, leftFarG, leftFarB, histLeftFarR, histLeftFarG, histLeftFarB, sampleHeight);
        temporalSmoothStrip(rightNearR, rightNearG, rightNearB, histRightNearR, histRightNearG, histRightNearB, sampleHeight);
        temporalSmoothStrip(rightFarR, rightFarG, rightFarB, histRightFarR, histRightFarG, histRightFarB, sampleHeight);

        hasHistory = true;

        // Генерация бесшовного 9-зонного поля рассеивания 60x48
        synthesizeGlowGrid(outGlowPixels);
    }

    /**
     * Извлекает взвешенные периметральные полосы (игнорируя внутреннее содержимое кадра).
     */
    private void extractPerimeterStrips(int[] pix) {
        int w = sampleWidth;
        int h = sampleHeight;

        // Веса от самого края внутрь (до ~14% глубины рамки)
        float[] vWeights = {0.16f, 0.34f, 0.26f, 0.15f, 0.09f};
        float[] hWeights = {0.16f, 0.34f, 0.26f, 0.15f, 0.09f};

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
                topNearR[x] = topFarR[x] = tr / tw;
                topNearG[x] = topFarG[x] = tg / tw;
                topNearB[x] = topFarB[x] = tb / tw;
            }
            if (bw > 0f) {
                bottomNearR[x] = bottomFarR[x] = br / bw;
                bottomNearG[x] = bottomFarG[x] = bg / bw;
                bottomNearB[x] = bottomFarB[x] = bb / bw;
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
                leftNearR[y] = leftFarR[y] = lr / lw;
                leftNearG[y] = leftFarG[y] = lg / lw;
                leftNearB[y] = leftFarB[y] = lb / lw;
            }
            if (rw > 0f) {
                rightNearR[y] = rightFarR[y] = rr / rw;
                rightNearG[y] = rightFarG[y] = rg / rw;
                rightNearB[y] = rightFarB[y] = rb / rw;
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
                    tempR[i] = sumR / wSum;
                    tempG[i] = sumG / wSum;
                    tempB[i] = sumB / wSum;
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

            // Плавный порог на самых темных сценах, исключающий резкие вспышки
            float darkToe = max < 14f ? smoothstep(4f, 14f, max) : 1f;
            float vNorm = max / 255f;
            // Подъем средних оттенков с мягким потолком (без ослепляющего белого)
            float vBoost = (float) Math.pow(vNorm, 0.85) * 1.06f;
            if (vBoost > 0.90f) {
                vBoost = 0.90f + (vBoost - 0.90f) * 0.25f;
            }
            vBoost = Math.min(0.95f, vBoost) * darkToe;

            if (chroma < 0.5f) {
                float val = Math.min(255f, vBoost * 255f);
                sr[i] = val;
                sg[i] = val;
                sb[i] = val;
            } else {
                float s = chroma / max;
                float sBoost = Math.min(1.0f, s * 1.24f);
                float cBoost = sBoost * vBoost * 255f;
                float mBoost = vBoost * 255f - cBoost;
                float ratio = cBoost / chroma;

                sr[i] = clamp255(mBoost + (r - min) * ratio);
                sg[i] = clamp255(mBoost + (g - min) * ratio);
                sb[i] = clamp255(mBoost + (b - min) * ratio);
            }
        }
    }

    private void temporalSmoothStrip(float[] sr, float[] sg, float[] sb,
                                     float[] hr, float[] hg, float[] hb, int len) {
        if (!hasHistory) {
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
            if (maxDiff > 42f) {
                alpha = 0.72f;
            } else if (maxDiff > 16f) {
                alpha = 0.40f;
            } else {
                alpha = 0.18f;
            }
            float inv = 1.0f - alpha;

            float nr = sr[i] * alpha + hr[i] * inv;
            float ng = sg[i] * alpha + hg[i] * inv;
            float nb = sb[i] * alpha + hb[i] * inv;

            hr[i] = nr; hg[i] = ng; hb[i] = nb;
            sr[i] = nr; sg[i] = ng; sb[i] = nb;
        }
    }

    private void synthesizeGlowGrid(int[] out) {
        for (int gy = 0; gy < GLOW_HEIGHT; gy++) {
            float v = gridV[gy];
            float ty = gridTy[gy];
            int rowOff = gy * GLOW_WIDTH;

            for (int gx = 0; gx < GLOW_WIDTH; gx++) {
                float u = gridU[gx];
                float tx = gridTx[gx];

                float outR, outG, outB;
                int alpha;

                if (tx == 0f && ty == 0f) {
                    // Внутренняя область за плеером (видна только на скругленных углах при шторке):
                    // плавно интерполируем периметральные цвета без какого-либо дублирования центра кадра
                    float dL = u + 0.001f;
                    float dR = (1f - u) + 0.001f;
                    float dT = v + 0.001f;
                    float dB = (1f - v) + 0.001f;

                    float wL = 1f / (dL * dL);
                    float wR = 1f / (dR * dR);
                    float wT = 1f / (dT * dT);
                    float wB = 1f / (dB * dB);
                    float wSum = wL + wR + wT + wB;

                    float lr = sampleChannel(leftNearR, sampleHeight, v);
                    float lg = sampleChannel(leftNearG, sampleHeight, v);
                    float lb = sampleChannel(leftNearB, sampleHeight, v);

                    float rr = sampleChannel(rightNearR, sampleHeight, v);
                    float rg = sampleChannel(rightNearG, sampleHeight, v);
                    float rb = sampleChannel(rightNearB, sampleHeight, v);

                    float tr = sampleChannel(topNearR, sampleWidth, u);
                    float tg = sampleChannel(topNearG, sampleWidth, u);
                    float tb = sampleChannel(topNearB, sampleWidth, u);

                    float br = sampleChannel(bottomNearR, sampleWidth, u);
                    float bg = sampleChannel(bottomNearG, sampleWidth, u);
                    float bb = sampleChannel(bottomNearB, sampleWidth, u);

                    outR = (lr * wL + rr * wR + tr * wT + br * wB) / wSum;
                    outG = (lg * wL + rg * wR + tg * wT + bg * wB) / wSum;
                    outB = (lb * wL + rb * wR + tb * wT + bb * wB) / wSum;
                    alpha = MAX_EDGE_ALPHA;
                } else {
                    float dist = (float) Math.sqrt(tx * tx + ty * ty);
                    float diffuseMix = smoothstep(0.05f, 0.95f, Math.min(1f, dist));

                    if (ty == 0f) {
                        // Левая или правая пустая область
                        boolean isLeft = gx < THIRD_W;
                        float[] nearR = isLeft ? leftNearR : rightNearR;
                        float[] nearG = isLeft ? leftNearG : rightNearG;
                        float[] nearB = isLeft ? leftNearB : rightNearB;
                        float[] farR = isLeft ? leftFarR : rightFarR;
                        float[] farG = isLeft ? leftFarG : rightFarG;
                        float[] farB = isLeft ? leftFarB : rightFarB;

                        outR = lerp(sampleChannel(nearR, sampleHeight, v), sampleChannel(farR, sampleHeight, v), diffuseMix);
                        outG = lerp(sampleChannel(nearG, sampleHeight, v), sampleChannel(farG, sampleHeight, v), diffuseMix);
                        outB = lerp(sampleChannel(nearB, sampleHeight, v), sampleChannel(farB, sampleHeight, v), diffuseMix);
                    } else if (tx == 0f) {
                        // Верхняя или нижняя пустая область
                        boolean isTop = gy < THIRD_H;
                        float[] nearR = isTop ? topNearR : bottomNearR;
                        float[] nearG = isTop ? topNearG : bottomNearG;
                        float[] nearB = isTop ? topNearB : bottomNearB;
                        float[] farR = isTop ? topFarR : bottomFarR;
                        float[] farG = isTop ? topFarG : bottomFarG;
                        float[] farB = isTop ? topFarB : bottomFarB;

                        outR = lerp(sampleChannel(nearR, sampleWidth, u), sampleChannel(farR, sampleWidth, u), diffuseMix);
                        outG = lerp(sampleChannel(nearG, sampleWidth, u), sampleChannel(farG, sampleWidth, u), diffuseMix);
                        outB = lerp(sampleChannel(nearB, sampleWidth, u), sampleChannel(farB, sampleWidth, u), diffuseMix);
                    } else {
                        // Угловая область — бесшовное смешивание горизонтального и вертикального краёв
                        boolean isLeft = gx < THIRD_W;
                        boolean isTop = gy < THIRD_H;

                        float[] hNearR = isLeft ? leftNearR : rightNearR;
                        float[] hNearG = isLeft ? leftNearG : rightNearG;
                        float[] hNearB = isLeft ? leftNearB : rightNearB;
                        float[] hFarR = isLeft ? leftFarR : rightFarR;
                        float[] hFarG = isLeft ? leftFarG : rightFarG;
                        float[] hFarB = isLeft ? leftFarB : rightFarB;

                        float[] vNearR = isTop ? topNearR : bottomNearR;
                        float[] vNearG = isTop ? topNearG : bottomNearG;
                        float[] vNearB = isTop ? topNearB : bottomNearB;
                        float[] vFarR = isTop ? topFarR : bottomFarR;
                        float[] vFarG = isTop ? topFarG : bottomFarG;
                        float[] vFarB = isTop ? topFarB : bottomFarB;

                        float hr = lerp(sampleChannel(hNearR, sampleHeight, v), sampleChannel(hFarR, sampleHeight, v), diffuseMix);
                        float hg = lerp(sampleChannel(hNearG, sampleHeight, v), sampleChannel(hFarG, sampleHeight, v), diffuseMix);
                        float hb = lerp(sampleChannel(hNearB, sampleHeight, v), sampleChannel(hFarB, sampleHeight, v), diffuseMix);

                        float vr = lerp(sampleChannel(vNearR, sampleWidth, u), sampleChannel(vFarR, sampleWidth, u), diffuseMix);
                        float vg = lerp(sampleChannel(vNearG, sampleWidth, u), sampleChannel(vFarG, sampleWidth, u), diffuseMix);
                        float vb = lerp(sampleChannel(vNearB, sampleWidth, u), sampleChannel(vFarB, sampleWidth, u), diffuseMix);

                        float tx2 = tx * tx;
                        float ty2 = ty * ty;
                        float sum2 = tx2 + ty2;
                        float wx = tx2 / sum2;
                        float wy = ty2 / sum2;

                        outR = hr * wx + vr * wy;
                        outG = hg * wx + vg * wy;
                        outB = hb * wx + vb * wy;
                    }

                    alpha = Math.round(MAX_EDGE_ALPHA * cinemaFalloff(dist));
                }

                int ri = clampInt(outR);
                int gi = clampInt(outG);
                int bi = clampInt(outB);
                out[rowOff + gx] = (alpha << 24) | (ri << 16) | (gi << 8) | bi;
            }
        }
    }

    /**
     * Перцептивно линейное (гамма-компенсированное, 1/2.2) косинусное затухание:
     * растягивает переход от яркого цвета к #000000 равномерно на всю ширину пустой области
     * без резкого порога в середине.
     */
    public static float cinemaFalloff(float distNorm) {
        if (distNorm <= 0f) return 1f;
        if (distNorm >= 1f) return 0f;
        double cosHalf = 0.5 * (1.0 + Math.cos(Math.PI * distNorm));
        return (float) Math.pow(cosHalf, 0.65);
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

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp255(float v) {
        return v < 0f ? 0f : (v > 255f ? 255f : v);
    }

    private static int clampInt(float v) {
        int iv = Math.round(v);
        return iv < 0 ? 0 : (iv > 255 ? 255 : iv);
    }
}
