package com.example.animelib.controllers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.media3.ui.PlayerView;

import com.example.animelib.R;
import com.example.animelib.managers.GesturesManager;
import com.example.animelib.util.CustomToast;

/**
 * Контроллер блокировки экрана в плеере (только для ландшафтного режима).
 * Скрывает все элементы управления, предотвращает поворот экрана
 * и предоставляет свайп-зону снизу для разблокировки.
 */
public class PlayerLockController {

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PlayerView playerView;
    private PlayerOrientationController orientationController;
    private GesturesManager gesturesManager;
    private ImageButton btnLockScreen;

    private View layoutLockedOverlay;
    private View lockedStatusBadge;
    private View swipeUnlockZone;
    private View swipeUnlockPill;
    private ImageView ivSwipeLockIcon;
    private ImageView ivSwipeChevron;
    private TextView tvSwipeUnlockText;

    private boolean isLocked = false;
    private float startTouchY = 0f;
    private boolean isDraggingPill = false;
    private final float unlockThresholdDp = 48f;
    private float unlockThresholdPx = 0f;

    private final Runnable autoDimRunnable = this::dimLockUI;

    public PlayerLockController(Activity activity) {
        this.activity = activity;
    }

    public void init(PlayerView playerView,
                     PlayerOrientationController orientationController,
                     GesturesManager gesturesManager,
                     ImageButton btnLockScreen,
                     View root) {
        this.playerView = playerView;
        this.orientationController = orientationController;
        this.gesturesManager = gesturesManager;
        this.btnLockScreen = btnLockScreen;

        if (root != null) {
            layoutLockedOverlay = root.findViewById(R.id.layoutLockedOverlay);
            lockedStatusBadge = root.findViewById(R.id.lockedStatusBadge);
            swipeUnlockZone = root.findViewById(R.id.swipeUnlockZone);
            swipeUnlockPill = root.findViewById(R.id.swipeUnlockPill);
            ivSwipeLockIcon = root.findViewById(R.id.ivSwipeLockIcon);
            ivSwipeChevron = root.findViewById(R.id.ivSwipeChevron);
            tvSwipeUnlockText = root.findViewById(R.id.tvSwipeUnlockText);
        }

        float density = activity.getResources().getDisplayMetrics().density;
        unlockThresholdPx = unlockThresholdDp * density;

        setupLockClick();
        setupSwipeUnlockGesture();
    }

    public void setLockScreenButton(ImageButton button) {
        this.btnLockScreen = button;
        setupLockClick();
    }

    private void setupLockClick() {
        if (btnLockScreen != null) {
            btnLockScreen.setOnClickListener(v -> {
                boolean isLandscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                if (isLandscape && !isLocked) {
                    lockScreen();
                }
            });
        }
    }

    /**
     * Блокирует экран плеера
     */
    public void lockScreen() {
        if (isLocked) return;
        isLocked = true;

        // 1. Предотвращаем поворот экрана
        if (orientationController != null) {
            orientationController.setLocked(true);
        }

        // 2. Блокируем жесты плеера
        if (gesturesManager != null) {
            gesturesManager.setScreenLocked(true);
        }

        // 3. Скрываем контроллер ExoPlayer
        if (playerView != null) {
            playerView.hideController();
            playerView.setUseController(false);
        }

        // 4. Показываем оверлей блокировки
        if (layoutLockedOverlay != null) {
            layoutLockedOverlay.setVisibility(View.VISIBLE);
            layoutLockedOverlay.setAlpha(1f);
        }

        if (swipeUnlockPill != null) {
            swipeUnlockPill.setTranslationY(0f);
            swipeUnlockPill.setAlpha(1f);
            swipeUnlockPill.setScaleX(1f);
            swipeUnlockPill.setScaleY(1f);
        }

        if (ivSwipeLockIcon != null) {
            ivSwipeLockIcon.setImageResource(R.drawable.ic_lock);
        }
        if (tvSwipeUnlockText != null) {
            tvSwipeUnlockText.setText("Смахните вверх для разблокировки");
        }

        // 5. Анимация появления плашки статуса
        if (lockedStatusBadge != null) {
            lockedStatusBadge.setVisibility(View.VISIBLE);
            lockedStatusBadge.setAlpha(1f);
            lockedStatusBadge.setScaleX(0.85f);
            lockedStatusBadge.setScaleY(0.85f);
            lockedStatusBadge.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.2f))
                    .withEndAction(() -> {
                        mainHandler.postDelayed(() -> {
                            if (isLocked && lockedStatusBadge != null) {
                                lockedStatusBadge.animate()
                                        .alpha(0f)
                                        .setDuration(300)
                                        .withEndAction(() -> lockedStatusBadge.setVisibility(View.GONE))
                                        .start();
                            }
                        }, 2200);
                    })
                    .start();
        }

        // Тактильный отклик
        try {
            if (layoutLockedOverlay != null) {
                layoutLockedOverlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        } catch (Exception ignored) {}

        // Запуск таймера автозатемнения свайп-зоны
        scheduleAutoDim();
    }

    /**
     * Разблокирует экран плеера
     */
    public void unlockScreen() {
        if (!isLocked) return;
        isLocked = false;

        mainHandler.removeCallbacks(autoDimRunnable);

        // 1. Скрываем оверлей блокировки
        if (layoutLockedOverlay != null) {
            layoutLockedOverlay.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction(() -> {
                        if (layoutLockedOverlay != null) {
                            layoutLockedOverlay.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }

        // 2. Разблокируем поворот экрана
        if (orientationController != null) {
            orientationController.setLocked(false);
        }

        // 3. Разблокируем жесты
        if (gesturesManager != null) {
            gesturesManager.setScreenLocked(false);
        }

        // 4. Включаем контроллер ExoPlayer
        if (playerView != null) {
            playerView.setUseController(true);
            playerView.showController();
        }

        // Тактильный отклик
        try {
            if (playerView != null) {
                playerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        } catch (Exception ignored) {}

        CustomToast.show(activity, "Экран разблокирован");
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupSwipeUnlockGesture() {
        if (layoutLockedOverlay == null) return;

        // Клик и касание по любой области экрана в заблокированном состоянии: убираем прозрачность
        layoutLockedOverlay.setOnClickListener(v -> {
            if (!isLocked) return;
            wakeUpLockUI();
        });

        layoutLockedOverlay.setOnTouchListener((v, event) -> {
            if (!isLocked) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                wakeUpLockUI();
            }
            return false;
        });

        // Обработка свайпа в зоне разблокировки снизу
        View touchTarget = swipeUnlockZone != null ? swipeUnlockZone : layoutLockedOverlay;
        touchTarget.setOnTouchListener((v, event) -> {
            if (!isLocked) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startTouchY = event.getRawY();
                    isDraggingPill = true;
                    wakeUpLockUI();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!isDraggingPill) {
                        startTouchY = event.getRawY();
                        isDraggingPill = true;
                    }
                    float currentY = event.getRawY();
                    float deltaY = startTouchY - currentY; // Положительное значение при движении вверх

                    if (deltaY > 0) {
                        float translationY = -Math.min(deltaY, unlockThresholdPx * 1.8f);
                        if (swipeUnlockPill != null) {
                            swipeUnlockPill.setTranslationY(translationY);
                        }

                        if (deltaY >= unlockThresholdPx) {
                            if (ivSwipeLockIcon != null) {
                                ivSwipeLockIcon.setImageResource(R.drawable.ic_lock_open);
                            }
                            if (tvSwipeUnlockText != null) {
                                tvSwipeUnlockText.setText("Отпустите для разблокировки");
                            }
                        } else {
                            if (ivSwipeLockIcon != null) {
                                ivSwipeLockIcon.setImageResource(R.drawable.ic_lock);
                            }
                            if (tvSwipeUnlockText != null) {
                                tvSwipeUnlockText.setText("Смахните вверх для разблокировки");
                            }
                        }
                    } else {
                        if (swipeUnlockPill != null) {
                            swipeUnlockPill.setTranslationY(0f);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isDraggingPill) {
                        isDraggingPill = false;
                        float endY = event.getRawY();
                        float totalDeltaY = startTouchY - endY;

                        if (totalDeltaY >= unlockThresholdPx) {
                            unlockScreen();
                        } else {
                            // Сброс положения с анимацией
                            if (swipeUnlockPill != null) {
                                swipeUnlockPill.animate()
                                        .translationY(0f)
                                        .setDuration(220)
                                        .setInterpolator(new DecelerateInterpolator())
                                        .start();
                            }
                            if (ivSwipeLockIcon != null) {
                                ivSwipeLockIcon.setImageResource(R.drawable.ic_lock);
                            }
                            if (tvSwipeUnlockText != null) {
                                tvSwipeUnlockText.setText("Смахните вверх для разблокировки");
                            }
                            scheduleAutoDim();
                        }
                    }
                    return true;
            }
            return false;
        });
    }

    private void wakeUpLockUI() {
        mainHandler.removeCallbacks(autoDimRunnable);
        if (swipeUnlockPill != null) {
            swipeUnlockPill.setVisibility(View.VISIBLE);
            swipeUnlockPill.animate()
                    .alpha(1f)
                    .setDuration(180)
                    .start();
        }
        if (lockedStatusBadge != null) {
            lockedStatusBadge.setVisibility(View.VISIBLE);
            lockedStatusBadge.animate()
                    .alpha(1f)
                    .setDuration(180)
                    .start();
        }
        scheduleAutoDim();
    }

    private void dimLockUI() {
        if (!isLocked) return;
        if (swipeUnlockPill != null) {
            swipeUnlockPill.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .start();
        }
        if (lockedStatusBadge != null) {
            lockedStatusBadge.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .withEndAction(() -> {
                        if (lockedStatusBadge != null) {
                            lockedStatusBadge.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private void scheduleAutoDim() {
        mainHandler.removeCallbacks(autoDimRunnable);
        mainHandler.postDelayed(autoDimRunnable, 3200);
    }

    public void updateVisibilityForOrientation(boolean isPortrait) {
        if (btnLockScreen != null) {
            btnLockScreen.setVisibility(isPortrait ? View.GONE : View.VISIBLE);
        }
        if (isPortrait && isLocked) {
            unlockScreen();
        }
    }

    public boolean isLocked() {
        return isLocked;
    }

    public void cleanup() {
        mainHandler.removeCallbacks(autoDimRunnable);
    }
}
