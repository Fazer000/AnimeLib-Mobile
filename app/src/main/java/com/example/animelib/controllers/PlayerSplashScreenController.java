package com.example.animelib.controllers;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import com.example.animelib.R;
import com.example.animelib.ui.PlayerSplashLogoView;

/**
 * Контроллер сплешскрина при входе в плеер.
 * Управляет показом и плавным раскрытием анимированного логотипа AnimeLib.
 */
public class PlayerSplashScreenController {

    private static final String TAG = "PlayerSplashCtrl";
    private static final long SAFETY_TIMEOUT_MS = 7000L;
    private static final long MIN_ENTRY_DISPLAY_MS = 340L;
    private static final long SLOW_NETWORK_HINT_DELAY_MS = 1800L;
    private static final long EXIT_ANIMATION_MS = 240L;

    private View splashOverlay;
    private View splashContent;
    private PlayerSplashLogoView splashLogoView;
    private TextView splashStatusText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PathInterpolator exitInterpolator = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private boolean isShowing = false;
    private boolean isDismissing = false;
    private boolean hasDismissed = false;
    private long shownAtUptimeMs = 0L;
    private Runnable pendingDismissRunnable;

    private final Runnable slowNetworkHintRunnable = () -> {
        if (isShowing && !isDismissing && !hasDismissed && splashStatusText != null) {
            splashStatusText.setVisibility(View.VISIBLE);
            splashStatusText.setAlpha(0f);
            splashStatusText.setTranslationY(6f);
            splashStatusText.animate()
                    .alpha(0.72f)
                    .translationY(0f)
                    .setDuration(260L)
                    .start();
        }
    };

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
        splashLogoView = rootView.findViewById(R.id.playerSplashLogoView);
        splashStatusText = rootView.findViewById(R.id.playerSplashStatusText);
    }

    /**
     * Показывает сплешскрин при первом входе в плеер и запускает хореографию логотипа.
     */
    public void showOnEntry() {
        if (hasDismissed || splashOverlay == null) return;

        isShowing = true;
        isDismissing = false;
        shownAtUptimeMs = SystemClock.uptimeMillis();

        splashOverlay.animate().cancel();
        splashOverlay.setVisibility(View.VISIBLE);
        splashOverlay.setAlpha(1.0f);
        splashOverlay.setScaleX(1.0f);
        splashOverlay.setScaleY(1.0f);

        if (splashContent != null) {
            splashContent.animate().cancel();
            splashContent.setAlpha(1.0f);
            splashContent.setScaleX(1.0f);
            splashContent.setScaleY(1.0f);
        }

        if (splashStatusText != null) {
            splashStatusText.animate().cancel();
            splashStatusText.setAlpha(0f);
            splashStatusText.setTranslationY(0f);
            splashStatusText.setVisibility(View.INVISIBLE);
        }

        if (splashLogoView != null) {
            splashLogoView.startAnimation();
        }

        handler.removeCallbacks(slowNetworkHintRunnable);
        handler.postDelayed(slowNetworkHintRunnable, SLOW_NETWORK_HINT_DELAY_MS);

        handler.removeCallbacks(safetyTimeoutRunnable);
        handler.postDelayed(safetyTimeoutRunnable, SAFETY_TIMEOUT_MS);
    }

    /**
     * Плавно раскрывает скобки логотипа и растворяет сплешскрин после завершения загрузки данных.
     */
    public void dismissWithAnimation(Runnable onDismissed) {
        if (hasDismissed || splashOverlay == null) {
            if (onDismissed != null) onDismissed.run();
            return;
        }
        if (isDismissing) {
            return;
        }

        isDismissing = true;
        handler.removeCallbacks(slowNetworkHintRunnable);
        handler.removeCallbacks(safetyTimeoutRunnable);

        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - shownAtUptimeMs);
        long waitMs = Math.max(0L, MIN_ENTRY_DISPLAY_MS - elapsed);

        pendingDismissRunnable = () -> performExitAnimation(onDismissed);
        if (waitMs > 0L) {
            handler.postDelayed(pendingDismissRunnable, waitMs);
        } else {
            pendingDismissRunnable.run();
        }
    }

    private void performExitAnimation(Runnable onDismissed) {
        if (hasDismissed || splashOverlay == null) {
            if (onDismissed != null) onDismissed.run();
            return;
        }

        hasDismissed = true;
        isShowing = false;
        isDismissing = false;
        pendingDismissRunnable = null;

        if (splashLogoView != null) {
            splashLogoView.startExitAnimation();
        }
        if (splashStatusText != null && splashStatusText.getVisibility() == View.VISIBLE) {
            splashStatusText.animate()
                    .alpha(0f)
                    .setDuration(140L)
                    .start();
        }

        splashOverlay.animate().cancel();
        splashOverlay.animate()
                .alpha(0f)
                .setDuration(EXIT_ANIMATION_MS)
                .setInterpolator(exitInterpolator)
                .withEndAction(() -> {
                    if (splashLogoView != null) {
                        splashLogoView.stopAnimation();
                    }
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
        isDismissing = false;
        handler.removeCallbacks(slowNetworkHintRunnable);
        handler.removeCallbacks(safetyTimeoutRunnable);
        if (pendingDismissRunnable != null) {
            handler.removeCallbacks(pendingDismissRunnable);
            pendingDismissRunnable = null;
        }

        if (splashLogoView != null) {
            splashLogoView.stopAnimation();
        }
        if (splashStatusText != null) {
            splashStatusText.animate().cancel();
            splashStatusText.setVisibility(View.INVISIBLE);
        }
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
