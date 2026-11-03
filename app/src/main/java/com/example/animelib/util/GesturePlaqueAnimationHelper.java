package com.example.animelib.util;

import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Единый хелпер для анимации появления и исчезновения плашек жестов плеера
 * (громкость, яркость, скорость, перемотка).
 */
public final class GesturePlaqueAnimationHelper {

    private static final int SHOW_DURATION_MS = 180;
    private static final int HIDE_DURATION_MS = 180;
    private static final float INITIAL_SCALE = 0.85f;
    private static final float TARGET_SCALE = 1.0f;

    private static final int TAG_IS_HIDING = 0x7F0B99A1;

    private GesturePlaqueAnimationHelper() {
    }

    /**
     * Плавно показывает плашку жеста с единой анимацией (fade + scale + легкий overshoot).
     */
    public static void show(View plaque) {
        if (plaque == null) return;

        Boolean isHiding = (Boolean) plaque.getTag(TAG_IS_HIDING);
        boolean hiding = isHiding != null && isHiding;

        if (plaque.getVisibility() != View.VISIBLE || hiding) {
            plaque.animate().cancel();
            plaque.setTag(TAG_IS_HIDING, Boolean.FALSE);

            float startAlpha = plaque.getVisibility() == View.VISIBLE ? plaque.getAlpha() : 0f;
            float startScaleX = plaque.getVisibility() == View.VISIBLE ? plaque.getScaleX() : INITIAL_SCALE;
            float startScaleY = plaque.getVisibility() == View.VISIBLE ? plaque.getScaleY() : INITIAL_SCALE;

            plaque.setVisibility(View.VISIBLE);
            plaque.setAlpha(startAlpha);
            plaque.setScaleX(startScaleX);
            plaque.setScaleY(startScaleY);

            plaque.animate()
                    .alpha(1.0f)
                    .scaleX(TARGET_SCALE)
                    .scaleY(TARGET_SCALE)
                    .setDuration(SHOW_DURATION_MS)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .withEndAction(null)
                    .start();
        }
    }

    /**
     * Плавно скрывает плашку жеста с единой анимацией (fade + scale down).
     */
    public static void hide(View plaque) {
        if (plaque == null || plaque.getVisibility() != View.VISIBLE) return;

        Boolean isHiding = (Boolean) plaque.getTag(TAG_IS_HIDING);
        if (isHiding != null && isHiding) {
            return;
        }

        plaque.animate().cancel();
        plaque.setTag(TAG_IS_HIDING, Boolean.TRUE);

        plaque.animate()
                .alpha(0f)
                .scaleX(INITIAL_SCALE)
                .scaleY(INITIAL_SCALE)
                .setDuration(HIDE_DURATION_MS)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    plaque.setTag(TAG_IS_HIDING, Boolean.FALSE);
                    plaque.setVisibility(View.GONE);
                    plaque.setAlpha(1.0f);
                    plaque.setScaleX(TARGET_SCALE);
                    plaque.setScaleY(TARGET_SCALE);
                })
                .start();
    }

    /**
     * Мгновенно скрывает плашку без анимации (например, при входе в PiP или блокировке экрана).
     */
    public static void hideImmediate(View plaque) {
        if (plaque == null) return;
        plaque.animate().cancel();
        plaque.setTag(TAG_IS_HIDING, Boolean.FALSE);
        plaque.setVisibility(View.GONE);
        plaque.setAlpha(1.0f);
        plaque.setScaleX(TARGET_SCALE);
        plaque.setScaleY(TARGET_SCALE);
    }
}
