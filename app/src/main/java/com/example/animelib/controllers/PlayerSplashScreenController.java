package com.example.animelib.controllers;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.example.animelib.R;

/**
 * Контроллер сплешскрина загрузки при первом входе в плеер.
 * Отображает стилизованный экран с анимированным логотипом приложения,
 * удерживая его до момента загрузки информации об аниме с API.
 */
public class PlayerSplashScreenController {

    private static final String TAG = "PlayerSplashCtrl";
    private static final long SAFETY_TIMEOUT_MS = 6000;

    private View splashOverlay;
    private View splashContent;
    private View logoCard;
    private View logoGlow;
    private TextView tvStatus;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AnimatorSet pulseAnimatorSet;

    private boolean isShowing = false;
    private boolean hasDismissed = false;

    private final Runnable safetyTimeoutRunnable = () -> {
        if (isShowing && !hasDismissed) {
            Log.d(TAG, "Safety timeout reached, dismissing splash screen");
            dismissWithAnimation(null);
        }
    };

    public void initViews(View rootView) {
        if (rootView == null) return;

        splashOverlay = rootView.findViewById(R.id.playerSplashScreenOverlay);
        splashContent = rootView.findViewById(R.id.playerSplashContent);
        logoCard = rootView.findViewById(R.id.playerSplashLogoCard);
        logoGlow = rootView.findViewById(R.id.playerSplashLogoGlow);
        tvStatus = rootView.findViewById(R.id.playerSplashStatusText);
    }

    /**
     * Показывает сплешскрин только при первом входе в плеер с плавной анимацией появления и пульсацией логотипа.
     */
    public void showOnEntry() {
        if (hasDismissed || splashOverlay == null) return;

        isShowing = true;
        splashOverlay.setVisibility(View.VISIBLE);
        splashOverlay.setAlpha(1.0f);
        splashOverlay.setScaleX(1.0f);
        splashOverlay.setScaleY(1.0f);

        if (splashContent != null) {
            splashContent.setAlpha(0f);
            splashContent.setScaleX(0.86f);
            splashContent.setScaleY(0.86f);

            splashContent.animate()
                    .alpha(1.0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(280)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        startPulseAnimation();

        // Страховочный таймаут на случай медленного интернета или ошибки сети
        handler.removeCallbacks(safetyTimeoutRunnable);
        handler.postDelayed(safetyTimeoutRunnable, SAFETY_TIMEOUT_MS);
    }

    private void startPulseAnimation() {
        stopPulseAnimation();

        if (logoCard == null) return;

        ObjectAnimator cardPulseX = ObjectAnimator.ofFloat(logoCard, "scaleX", 1.0f, 1.06f, 1.0f);
        ObjectAnimator cardPulseY = ObjectAnimator.ofFloat(logoCard, "scaleY", 1.0f, 1.06f, 1.0f);
        cardPulseX.setRepeatCount(ValueAnimator.INFINITE);
        cardPulseY.setRepeatCount(ValueAnimator.INFINITE);
        cardPulseX.setDuration(1500);
        cardPulseY.setDuration(1500);
        cardPulseX.setInterpolator(new AccelerateDecelerateInterpolator());
        cardPulseY.setInterpolator(new AccelerateDecelerateInterpolator());

        pulseAnimatorSet = new AnimatorSet();

        if (logoGlow != null) {
            ObjectAnimator glowAlpha = ObjectAnimator.ofFloat(logoGlow, "alpha", 0.35f, 0.75f, 0.35f);
            ObjectAnimator glowScaleX = ObjectAnimator.ofFloat(logoGlow, "scaleX", 1.0f, 1.14f, 1.0f);
            ObjectAnimator glowScaleY = ObjectAnimator.ofFloat(logoGlow, "scaleY", 1.0f, 1.14f, 1.0f);

            glowAlpha.setRepeatCount(ValueAnimator.INFINITE);
            glowScaleX.setRepeatCount(ValueAnimator.INFINITE);
            glowScaleY.setRepeatCount(ValueAnimator.INFINITE);
            glowAlpha.setDuration(1500);
            glowScaleX.setDuration(1500);
            glowScaleY.setDuration(1500);
            glowAlpha.setInterpolator(new AccelerateDecelerateInterpolator());
            glowScaleX.setInterpolator(new AccelerateDecelerateInterpolator());
            glowScaleY.setInterpolator(new AccelerateDecelerateInterpolator());

            pulseAnimatorSet.playTogether(cardPulseX, cardPulseY, glowAlpha, glowScaleX, glowScaleY);
        } else {
            pulseAnimatorSet.playTogether(cardPulseX, cardPulseY);
        }

        pulseAnimatorSet.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnimatorSet != null) {
            pulseAnimatorSet.cancel();
            pulseAnimatorSet = null;
        }
        if (logoCard != null) {
            logoCard.setScaleX(1.0f);
            logoCard.setScaleY(1.0f);
        }
        if (logoGlow != null) {
            logoGlow.setScaleX(1.0f);
            logoGlow.setScaleY(1.0f);
            logoGlow.setAlpha(0.4f);
        }
    }

    /**
     * Плавно растворяет сплешскрин с легким увеличением масштаба (cinematic dissolve).
     */
    public void dismissWithAnimation(Runnable onDismissed) {
        if (hasDismissed || splashOverlay == null) {
            if (onDismissed != null) onDismissed.run();
            return;
        }

        hasDismissed = true;
        isShowing = false;
        handler.removeCallbacks(safetyTimeoutRunnable);

        stopPulseAnimation();

        splashOverlay.animate().cancel();
        splashOverlay.animate()
                .alpha(0f)
                .scaleX(1.05f)
                .scaleY(1.05f)
                .setDuration(360)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (splashOverlay != null) {
                        splashOverlay.setVisibility(View.GONE);
                    }
                    if (onDismissed != null) {
                        onDismissed.run();
                    }
                })
                .start();
    }

    public void dismissImmediately() {
        if (hasDismissed) return;

        hasDismissed = true;
        isShowing = false;
        handler.removeCallbacks(safetyTimeoutRunnable);

        stopPulseAnimation();

        if (splashOverlay != null) {
            splashOverlay.animate().cancel();
            splashOverlay.setVisibility(View.GONE);
        }
    }

    public boolean isShowing() {
        return isShowing;
    }

    public boolean hasDismissed() {
        return hasDismissed;
    }
}
