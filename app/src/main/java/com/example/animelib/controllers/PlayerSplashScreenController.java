package com.example.animelib.controllers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import com.example.animelib.R;

/**
 * Контроллер сплешскрина загрузки при первом входе в плеер.
 * Отображает стилизованный экран без фонов и анимаций логотипа,
 * удерживая его до момента загрузки информации об аниме, эпизоде и озвучках с API.
 */
public class PlayerSplashScreenController {

    private static final String TAG = "PlayerSplashCtrl";
    private static final long SAFETY_TIMEOUT_MS = 7000;

    private View splashOverlay;
    private View splashContent;

    private final Handler handler = new Handler(Looper.getMainLooper());

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
    }

    /**
     * Показывает сплешскрин только при первом входе в плеер.
     * Логотип и контент статичны (без анимации и без фона).
     */
    public void showOnEntry() {
        if (hasDismissed || splashOverlay == null) return;

        isShowing = true;
        splashOverlay.setVisibility(View.VISIBLE);
        splashOverlay.setAlpha(1.0f);
        splashOverlay.setScaleX(1.0f);
        splashOverlay.setScaleY(1.0f);

        if (splashContent != null) {
            splashContent.setAlpha(1.0f);
            splashContent.setScaleX(1.0f);
            splashContent.setScaleY(1.0f);
        }

        // Страховочный таймаут на случай медленного интернета или ошибки сети
        handler.removeCallbacks(safetyTimeoutRunnable);
        handler.postDelayed(safetyTimeoutRunnable, SAFETY_TIMEOUT_MS);
    }

    /**
     * Плавно растворяет сплешскрин после завершения загрузки данных.
     */
    public void dismissWithAnimation(Runnable onDismissed) {
        if (hasDismissed || splashOverlay == null) {
            if (onDismissed != null) onDismissed.run();
            return;
        }

        hasDismissed = true;
        isShowing = false;
        handler.removeCallbacks(safetyTimeoutRunnable);

        splashOverlay.animate().cancel();
        splashOverlay.animate()
                .alpha(0f)
                .setDuration(260)
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
