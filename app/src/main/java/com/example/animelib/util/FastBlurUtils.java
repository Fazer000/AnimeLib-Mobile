package com.example.animelib.util;

/**
 * Высокооптимизированный алгоритм StackBlur с идеальным сохранением цветового тона (Pure Hue Ambilight)
 * и аппаратным временным сглаживанием (Temporal EMA Filtering) в фоновом потоке.
 *
 * - 0 аллокаций памяти в рантайме;
 * - Математически точное сохранение оригинального тона (0% сдвига в посторонние цвета);
 * - Устранение дрожания и ступенчатости кадров в фоне (UI-поток полностью свободен).
 */
public class FastBlurUtils {

    private final int width;
    private final int height;
    private final int radius;

    private final int[] r;
    private final int[] g;
    private final int[] b;
    private final int[] vmin;
    private final int[] dv;
    private final int maxDvIndex;
    private final int[][] stack;
    private final float[] edgeAlphaTable;

    // Временное сглаживание в фоновом потоке (Temporal EMA Filter)
    private final int[] historyPixels;
    private boolean hasHistory = false;
    // Коэффициент экспоненциального сглаживания (0.20 = идеальная мягкость и отсутствие мерцания)
    private static final float EMA_ALPHA = 0.20f;
    private static final int EMA_ALPHA_INT = (int) (EMA_ALPHA * 256.0f); // 51 / 256
    private static final int EMA_INV_ALPHA_INT = 256 - EMA_ALPHA_INT;    // 205 / 256

    public FastBlurUtils(int width, int height, int radius) {
        this.width = width;
        this.height = height;
        this.radius = radius;

        int wh = width * height;
        int div = radius + radius + 1;

        this.r = new int[wh];
        this.g = new int[wh];
        this.b = new int[wh];
        this.vmin = new int[Math.max(width, height)];
        this.historyPixels = new int[wh];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        this.dv = new int[256 * divsum];
        this.maxDvIndex = this.dv.length - 1;
        for (int i = 0; i < 256 * divsum; i++) {
            this.dv[i] = (i / divsum);
        }

        this.stack = new int[div][3];

        // Плавное спадающее затемнение боковых краев с сохранением легкой полупрозрачности (1.0 у видеокадра до 0.07 у внешнего края)
        this.edgeAlphaTable = new float[wh];
        float fadeMarginX = Math.max(1f, width * 0.35f);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float distX = Math.min(x, width - 1 - x) / fadeMarginX;
                distX = Math.max(0.0f, Math.min(1.0f, distX));

                float sinVal = (float) Math.sin(distX * Math.PI * 0.5);
                float alphaFactor = 0.07f + 0.93f * (sinVal * sinVal);
                this.edgeAlphaTable[y * width + x] = Math.max(0.0f, Math.min(1.0f, alphaFactor));
            }
        }
    }

    public void resetHistory() {
        hasHistory = false;
    }

    /**
     * Выполняет быстрое размытие по Гауссу (StackBlur), точную калибровку цветов,
     * краевое затухание и фоновое темпоральное сглаживание.
     */
    public void blurAndSmooth(int[] pix) {
        if (pix == null || pix.length < width * height) {
            return;
        }

        int wm = width - 1;
        int hm = height - 1;
        int div = radius + radius + 1;

        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        yw = yi = 0;

        int stackpointer;
        int stackstart;
        int[] sir;
        int rbs;
        int r1 = radius + 1;
        int routsum, goutsum, boutsum;
        int rinsum, ginsum, binsum;

        // 1. Горизонтальный проход StackBlur
        for (y = 0; y < height; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            stackpointer = radius;

            for (x = 0; x < width; x++) {
                int safeR = rsum < 0 ? 0 : (rsum > maxDvIndex ? maxDvIndex : rsum);
                int safeG = gsum < 0 ? 0 : (gsum > maxDvIndex ? maxDvIndex : gsum);
                int safeB = bsum < 0 ? 0 : (bsum > maxDvIndex ? maxDvIndex : bsum);

                r[yi] = dv[safeR];
                g[yi] = dv[safeG];
                b[yi] = dv[safeB];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (y == 0) {
                    vmin[x] = Math.min(x + radius + 1, wm);
                }
                p = pix[yw + vmin[x]];

                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[(stackpointer) % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi++;
            }
            yw += width;
        }

        // 2. Вертикальный проход StackBlur + калибровка цветов с сохранением Hue
        for (x = 0; x < width; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * width;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;

                sir = stack[i + radius];

                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];

                rbs = r1 - Math.abs(i);

                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;

                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }

                if (i < hm) {
                    yp += width;
                }
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < height; y++) {
                int safeR = rsum < 0 ? 0 : (rsum > maxDvIndex ? maxDvIndex : rsum);
                int safeG = gsum < 0 ? 0 : (gsum > maxDvIndex ? maxDvIndex : gsum);
                int safeB = bsum < 0 ? 0 : (bsum > maxDvIndex ? maxDvIndex : bsum);

                int rawR = dv[safeR];
                int rawG = dv[safeG];
                int rawB = dv[safeB];

                // === Pure Hue Ambilight Color Calibration ===
                int max = Math.max(rawR, Math.max(rawG, rawB));
                int min = Math.min(rawR, Math.min(rawG, rawB));
                int chroma = max - min;

                int finalR, finalG, finalB;

                if (max < 8) {
                    finalR = 0;
                    finalG = 0;
                    finalB = 0;
                } else if (chroma == 0) {
                    float vNorm = max / 255.0f;
                    float vBoost = (float) Math.pow(vNorm, 0.88) * 1.12f;
                    int targetVal = Math.min(255, (int) (vBoost * 255.0f + 0.5f));
                    finalR = targetVal;
                    finalG = targetVal;
                    finalB = targetVal;
                } else {
                    float s = (float) chroma / (float) max;
                    float sBoost = Math.min(1.0f, s * 1.22f);

                    float vNorm = max / 255.0f;
                    float vBoost = Math.min(1.0f, (float) Math.pow(vNorm, 0.88) * 1.15f);

                    float cBoost = sBoost * vBoost * 255.0f;
                    float mBoost = vBoost * 255.0f - cBoost;
                    float ratio = cBoost / (float) chroma;

                    finalR = Math.max(0, Math.min(255, (int) (mBoost + (rawR - min) * ratio + 0.5f)));
                    finalG = Math.max(0, Math.min(255, (int) (mBoost + (rawG - min) * ratio + 0.5f)));
                    finalB = Math.max(0, Math.min(255, (int) (mBoost + (rawB - min) * ratio + 0.5f)));
                }

                int alpha = (int) (255 * edgeAlphaTable[yi]);
                int newPixel = (alpha << 24) | (finalR << 16) | (finalG << 8) | finalB;

                // Адаптивное фоновое сглаживание: мгновенный отклик на смену сцены + фильтрация шума
                if (!hasHistory) {
                    historyPixels[yi] = newPixel;
                    pix[yi] = newPixel;
                } else {
                    int prev = historyPixels[yi];
                    int pa = (prev >>> 24) & 0xff;
                    int pr = (prev >>> 16) & 0xff;
                    int pg = (prev >>> 8) & 0xff;
                    int pb = prev & 0xff;

                    int diffR = Math.abs(finalR - pr);
                    int diffG = Math.abs(finalG - pg);
                    int diffB = Math.abs(finalB - pb);
                    int maxDiff = Math.max(diffR, Math.max(diffG, diffB));

                    int alphaInt;
                    if (maxDiff > 35) {
                        alphaInt = 230; // ~90% новый цвет -> мгновенный отклик при смене кадров (0 задержки)
                    } else if (maxDiff > 15) {
                        alphaInt = 180; // ~70% новый цвет
                    } else {
                        alphaInt = 140; // ~55% новый цвет -> фильтрация микрошума
                    }
                    int invAlphaInt = 256 - alphaInt;

                    int smoothedA = (alpha * alphaInt + pa * invAlphaInt) >> 8;
                    int smoothedR = (finalR * alphaInt + pr * invAlphaInt) >> 8;
                    int smoothedG = (finalG * alphaInt + pg * invAlphaInt) >> 8;
                    int smoothedB = (finalB * alphaInt + pb * invAlphaInt) >> 8;

                    int smoothedPixel = (smoothedA << 24) | (smoothedR << 16) | (smoothedG << 8) | smoothedB;
                    historyPixels[yi] = smoothedPixel;
                    pix[yi] = smoothedPixel;
                }

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (x == 0) {
                    vmin[y] = Math.min(y + r1, hm) * width;
                }
                p = x + vmin[y];

                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi += width;
            }
        }

        hasHistory = true;
    }
}
