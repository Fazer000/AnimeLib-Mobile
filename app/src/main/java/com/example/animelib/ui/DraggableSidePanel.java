package com.example.animelib.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/**
 * Кастомная боковая панель с плавным 1:1 drag-to-open / drag-to-dismiss без скачков в начале жеста.
 */
public class DraggableSidePanel extends FrameLayout {
    public static final float OPEN_DRAG_THRESHOLD = 0.20f;
    public static final float COLLAPSE_THRESHOLD = 0.30f;
    public static final float FLING_VELOCITY_THRESHOLD = 400f;
    private static final int ANIMATION_DURATION = 260;
    private static final Interpolator PANEL_INTERPOLATOR = new FastOutSlowInInterpolator();

    private View dragView;
    private View dragZone;
    private View animatedView;
    private boolean isOpen = false;
    private boolean dragEnabled = true;
    private OnPanelStateChangeListener listener;

    private float initialTouchRawX;
    private float initialTouchRawY;
    private float initialTranslationX;
    private boolean isDragging = false;
    private boolean isDragStarted = false;
    private boolean isExternalDragging = false;
    private boolean dragStartNotified = false;
    private int touchSlop = 0;
    private float dragStartOffset = 0f;
    private VelocityTracker velocityTracker;
    private ValueAnimator activeAnimator;

    public interface OnPanelStateChangeListener {
        default void onPanelDragStart() {}
        void onPanelOpened();
        void onPanelClosed();
        void onPanelSliding(float slideOffset);

        /**
         * Проверяет можно ли закрыть панель
         * @return true если можно закрыть, false если нельзя
         */
        default boolean canClosePanel() {
            return true;
        }
    }

    public DraggableSidePanel(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DraggableSidePanel(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DraggableSidePanel(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0) {
            animatedView = getChildAt(0);
            dragView = animatedView;

            if (animatedView instanceof ViewGroup) {
                dragZone = animatedView.findViewById(com.example.animelib.R.id.dragZone);
            }

            setupTouchListener();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchListener() {
        if (animatedView == null) {
            return;
        }
        if (dragZone != null) {
            dragZone.setOnTouchListener((v, event) -> {
                if (!isOpen || isExternalDragging) return false;
                return handleTouchEvent(event);
            });
        }
    }

    public void setDragEnabled(boolean enabled) {
        this.dragEnabled = enabled;
    }

    private void cancelActiveAnimator() {
        if (activeAnimator != null) {
            activeAnimator.removeAllUpdateListeners();
            activeAnimator.removeAllListeners();
            activeAnimator.cancel();
            activeAnimator = null;
        }
        if (animatedView != null) {
            animatedView.animate().setUpdateListener(null).withEndAction(null).cancel();
        }
    }

    private void clearPendingCallbacks() {
        cancelActiveAnimator();
        if (animatedView != null) {
            animatedView.removeCallbacks(null);
        }
        removeCallbacks(null);
    }

    public float getPanelWidth() {
        if (animatedView == null) {
            return TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 360f, getResources().getDisplayMetrics());
        }
        if (animatedView.getWidth() > 0) {
            return animatedView.getWidth();
        }
        if (animatedView.getMeasuredWidth() > 0) {
            return animatedView.getMeasuredWidth();
        }

        int parentW = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        int parentH = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        ViewGroup.LayoutParams lp = animatedView.getLayoutParams();
        int widthSpec;
        if (lp != null && lp.width > 0) {
            widthSpec = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY);
        } else {
            widthSpec = MeasureSpec.makeMeasureSpec(parentW, MeasureSpec.AT_MOST);
        }
        int heightSpec = MeasureSpec.makeMeasureSpec(parentH, MeasureSpec.EXACTLY);
        animatedView.measure(widthSpec, heightSpec);

        if (animatedView.getMeasuredWidth() > 0) {
            return animatedView.getMeasuredWidth();
        }

        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 360f, getResources().getDisplayMetrics());
    }

    private void notifyDragStartOnce() {
        if (!dragStartNotified) {
            dragStartNotified = true;
            if (listener != null) {
                listener.onPanelDragStart();
            }
        }
    }

    private boolean handleTouchEvent(MotionEvent event) {
        if (!dragEnabled || !isOpen || animatedView == null || getVisibility() != View.VISIBLE) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelActiveAnimator();
                initialTouchRawX = event.getRawX();
                initialTouchRawY = event.getRawY();
                initialTranslationX = animatedView.getTranslationX();
                isDragging = true;
                isDragStarted = false;
                dragStartOffset = 0f;

                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain();
                } else {
                    velocityTracker.clear();
                }
                addRawMovementToVelocityTracker(velocityTracker, event);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!isDragging) return false;
                addRawMovementToVelocityTracker(velocityTracker, event);

                float deltaX = event.getRawX() - initialTouchRawX;
                float deltaY = event.getRawY() - initialTouchRawY;

                if (!isDragStarted) {
                    float totalDelta = (float) Math.hypot(deltaX, deltaY);
                    if (totalDelta < touchSlop) {
                        return true;
                    }
                    if (Math.abs(deltaY) > Math.abs(deltaX) * 1.2f) {
                        isDragging = false;
                        if (velocityTracker != null) {
                            velocityTracker.recycle();
                            velocityTracker = null;
                        }
                        return false;
                    }
                    isDragStarted = true;
                    dragStartOffset = deltaX;
                    ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    notifyDragStartOnce();
                }

                float adjustedDeltaX = deltaX - dragStartOffset;
                float panelWidth = getPanelWidth();
                float newTranslationX = Math.max(0f, Math.min(initialTranslationX + adjustedDeltaX, panelWidth));
                animatedView.setTranslationX(newTranslationX);

                if (listener != null && panelWidth > 0f) {
                    listener.onPanelSliding(computeSlideOffset(newTranslationX, panelWidth));
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isDragging) return false;

                float xVel = 0f;
                if (velocityTracker != null) {
                    addRawMovementToVelocityTracker(velocityTracker, event);
                    velocityTracker.computeCurrentVelocity(1000);
                    xVel = velocityTracker.getXVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;
                }

                boolean dragWasStarted = isDragStarted;
                isDragging = false;
                isDragStarted = false;

                if (!dragWasStarted && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    dragStartNotified = false;
                    return false;
                }

                float currentTranslationX = animatedView.getTranslationX();
                float panelW = getPanelWidth();
                float closeOffset = computeSlideOffset(currentTranslationX, panelW);
                boolean shouldClose = shouldClosePanelOnRelease(closeOffset, xVel);

                dragStartNotified = false;
                if (shouldClose) {
                    if (listener != null && !listener.canClosePanel()) {
                        shakePanel();
                    } else {
                        animateClose();
                    }
                } else {
                    animateToOpen();
                }
                return true;
        }
        return false;
    }

    private static void addRawMovementToVelocityTracker(@Nullable VelocityTracker tracker, @NonNull MotionEvent event) {
        if (tracker == null) return;
        MotionEvent rawEvent = MotionEvent.obtain(event);
        rawEvent.setLocation(event.getRawX(), event.getRawY());
        tracker.addMovement(rawEvent);
        rawEvent.recycle();
    }

    public void setOnPanelStateChangeListener(OnPanelStateChangeListener listener) {
        this.listener = listener;
    }

    public void openPanel() {
        if (animatedView == null) return;

        clearPendingCallbacks();
        isExternalDragging = false;
        dragStartNotified = false;
        isOpen = true;

        float panelWidth = getPanelWidth();
        boolean alreadyVisibleAndOpen = getVisibility() == VISIBLE
                && animatedView.getVisibility() == VISIBLE
                && animatedView.getTranslationX() == 0f
                && animatedView.getWidth() > 0;
        if (alreadyVisibleAndOpen) {
            if (listener != null) {
                listener.onPanelOpened();
            }
            return;
        }

        if (getVisibility() != VISIBLE || animatedView.getVisibility() != VISIBLE || animatedView.getTranslationX() <= 0f) {
            animatedView.setTranslationX(panelWidth);
        }
        setVisibility(VISIBLE);
        animatedView.setVisibility(VISIBLE);

        animateToOpen();
    }

    private void animateToOpen() {
        if (animatedView == null) return;

        cancelActiveAnimator();
        isOpen = true;

        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        if (animatedView.getVisibility() != VISIBLE) animatedView.setVisibility(VISIBLE);

        notifyDragStartOnce();

        float panelWidth = getPanelWidth();
        float startX = animatedView.getTranslationX();
        if (startX <= 0.5f) {
            animatedView.setTranslationX(0f);
            dragStartNotified = false;
            if (listener != null) {
                listener.onPanelSliding(0f);
                listener.onPanelOpened();
            }
            return;
        }

        float remainingRatio = panelWidth > 0f ? Math.min(1f, Math.abs(startX) / panelWidth) : 1f;
        long duration = Math.max(120L, Math.min(ANIMATION_DURATION, (long) (ANIMATION_DURATION * remainingRatio)));

        ValueAnimator animator = ValueAnimator.ofFloat(startX, 0f);
        animator.setDuration(duration);
        animator.setInterpolator(PANEL_INTERPOLATOR);
        animator.addUpdateListener(animation -> {
            float curX = (float) animation.getAnimatedValue();
            animatedView.setTranslationX(curX);
            if (listener != null && panelWidth > 0f) {
                listener.onPanelSliding(computeSlideOffset(curX, panelWidth));
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (activeAnimator == animator) {
                    activeAnimator = null;
                }
                if (cancelled) return;
                dragStartNotified = false;
                isOpen = true;
                animatedView.setTranslationX(0f);
                if (listener != null) {
                    listener.onPanelOpened();
                }
            }
        });
        activeAnimator = animator;
        animator.start();
    }

    public void forceClose() {
        isOpen = false;
        isExternalDragging = false;
        isDragging = false;
        isDragStarted = false;
        dragStartNotified = false;
        clearPendingCallbacks();
        if (animatedView != null) {
            float targetX = getPanelWidth();
            animatedView.setTranslationX(targetX);
            animatedView.setVisibility(GONE);
        }
        setVisibility(GONE);
        if (listener != null) {
            listener.onPanelClosed();
        }
    }

    public void closePanel() {
        if (animatedView == null || !isOpen) return;

        if (listener != null && !listener.canClosePanel()) {
            shakePanel();
            return;
        }

        animateClose();
    }

    /**
     * Анимация "тряски" панели когда её нельзя закрыть
     */
    private void shakePanel() {
        if (animatedView == null) return;
        cancelActiveAnimator();

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 20f, -20f, 10f, 0f);
        animator.setDuration(160);
        animator.addUpdateListener(animation -> {
            float val = (float) animation.getAnimatedValue();
            animatedView.setTranslationX(val);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (activeAnimator == animator) {
                    activeAnimator = null;
                }
                animatedView.setTranslationX(0f);
            }
        });
        activeAnimator = animator;
        animator.start();
    }

    private void animateClose() {
        if (animatedView == null) return;

        cancelActiveAnimator();
        notifyDragStartOnce();

        float targetX = getPanelWidth();
        float startX = animatedView.getTranslationX();
        if (startX >= targetX - 0.5f) {
            dragStartNotified = false;
            isOpen = false;
            animatedView.setTranslationX(targetX);
            setVisibility(GONE);
            if (listener != null) {
                listener.onPanelSliding(1f);
                listener.onPanelClosed();
            }
            return;
        }

        float remainingRatio = targetX > 0f ? Math.min(1f, Math.abs(targetX - startX) / targetX) : 1f;
        long duration = Math.max(120L, Math.min(ANIMATION_DURATION, (long) (ANIMATION_DURATION * remainingRatio)));

        ValueAnimator animator = ValueAnimator.ofFloat(startX, targetX);
        animator.setDuration(duration);
        animator.setInterpolator(PANEL_INTERPOLATOR);
        animator.addUpdateListener(animation -> {
            float curX = (float) animation.getAnimatedValue();
            animatedView.setTranslationX(curX);
            if (listener != null && targetX > 0f) {
                listener.onPanelSliding(computeSlideOffset(curX, targetX));
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (activeAnimator == animator) {
                    activeAnimator = null;
                }
                if (cancelled) return;
                dragStartNotified = false;
                isOpen = false;
                animatedView.setTranslationX(targetX);
                setVisibility(GONE);
                if (listener != null) {
                    listener.onPanelClosed();
                }
            }
        });
        activeAnimator = animator;
        animator.start();
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void setPanelOpen(boolean open) {
        if (open) {
            openPanel();
        } else {
            closePanel();
        }
    }

    /**
     * Подготавливает панель перед началом интерактивного drag-жеста справа налево.
     * Выставляет начальный translationX = width ДО перевода контейнера в VISIBLE,
     * исключая однокадровое мигание в открытом положении и скачок в начале жеста.
     */
    public void beginDragFromRight() {
        if (animatedView == null) return;

        cancelActiveAnimator();
        isExternalDragging = true;
        isOpen = false;

        notifyDragStartOnce();

        float panelWidth = getPanelWidth();
        if (getVisibility() != VISIBLE) {
            animatedView.setTranslationX(panelWidth);
            setVisibility(VISIBLE);
        } else if (animatedView.getVisibility() != VISIBLE) {
            animatedView.setTranslationX(panelWidth);
        }
        if (animatedView.getVisibility() != VISIBLE) {
            animatedView.setVisibility(VISIBLE);
        }
    }

    /**
     * Сдвигает панель справа налево ровно на draggedLeftPx экранных пикселей (1:1 с пальцем).
     */
    public void updateDragByLeftPx(float draggedLeftPx) {
        if (animatedView == null) return;
        if (!isExternalDragging) {
            beginDragFromRight();
        }

        float panelWidth = getPanelWidth();
        float translationX = computeTranslationXForRightDrag(panelWidth, draggedLeftPx);
        animatedView.setTranslationX(translationX);

        if (listener != null && panelWidth > 0f) {
            listener.onPanelSliding(computeSlideOffset(translationX, panelWidth));
        }
    }

    /**
     * Устанавливает прогресс drag (0.0 = закрыто, 1.0 = открыто).
     */
    public void setDragProgress(float progress) {
        if (animatedView == null) return;
        if (!isExternalDragging) {
            beginDragFromRight();
        }
        float clamped = Math.max(0f, Math.min(1f, progress));
        float panelWidth = getPanelWidth();
        updateDragByLeftPx(clamped * panelWidth);
    }

    /**
     * Завершает внешний drag жест с учётом текущей позиции и горизонтальной скорости пальца.
     */
    public void completeDragWithVelocity(float xVelocityPxPerSec) {
        if (animatedView == null) return;
        float panelWidth = getPanelWidth();
        float currentTx = animatedView.getTranslationX();
        float openProgress = panelWidth > 0f ? Math.max(0f, Math.min(1f, 1f - (currentTx / panelWidth))) : 0f;
        boolean shouldOpen = shouldOpenPanelOnRelease(openProgress, xVelocityPxPerSec);
        completeDrag(shouldOpen);
    }

    /**
     * Завершает drag жест с решением открыть или закрыть панель.
     */
    public void completeDrag(boolean shouldOpen) {
        if (animatedView == null) return;

        isExternalDragging = false;
        dragStartNotified = false;
        if (shouldOpen) {
            animateToOpen();
        } else {
            animateClose();
        }
    }

    // ==================== Pure Testable Helpers ====================

    public static float computeTranslationXForRightDrag(float panelWidth, float draggedLeftPx) {
        if (panelWidth <= 0f) return 0f;
        float clampedLeft = Math.max(0f, Math.min(draggedLeftPx, panelWidth));
        return panelWidth - clampedLeft;
    }

    public static float computeSlideOffset(float translationX, float panelWidth) {
        if (panelWidth <= 0f) return 1f;
        return Math.max(0f, Math.min(1f, translationX / panelWidth));
    }

    public static boolean shouldOpenPanelOnRelease(float openProgress, float xVelocityPxPerSec) {
        if (xVelocityPxPerSec < -FLING_VELOCITY_THRESHOLD) {
            return true;
        }
        if (xVelocityPxPerSec > FLING_VELOCITY_THRESHOLD) {
            return false;
        }
        return openProgress >= OPEN_DRAG_THRESHOLD;
    }

    public static boolean shouldClosePanelOnRelease(float slideOffset, float xVelocityPxPerSec) {
        if (xVelocityPxPerSec > FLING_VELOCITY_THRESHOLD) {
            return true;
        }
        if (xVelocityPxPerSec < -FLING_VELOCITY_THRESHOLD) {
            return false;
        }
        return slideOffset > COLLAPSE_THRESHOLD;
    }
}
