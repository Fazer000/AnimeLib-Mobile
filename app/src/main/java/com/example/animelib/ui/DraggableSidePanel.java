package com.example.animelib.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/**
 * Кастомная панель с возможностью drag-to-dismiss справа налево
 */
public class DraggableSidePanel extends FrameLayout {
    private static final float COLLAPSE_THRESHOLD = 0.3f;
    private static final int ANIMATION_DURATION = 300;
    private static final Interpolator BOTTOM_SHEET_INTERPOLATOR = new FastOutSlowInInterpolator();
    
    private View dragView; // Основная панель (slidingMenuPanel или commentsPanel)
    private View dragZone; // Зона для перетаскивания
    private View animatedView; // View который будет анимироваться (корневой контейнер из include)
    private boolean isOpen = false;
    private boolean dragEnabled = true;
    private OnPanelStateChangeListener listener;
    
    private float initialTouchX;
    private float initialTouchY;
    private float initialTranslationX;
    private boolean isDragging = false;
    private boolean isDragStarted = false; // Флаг что драг действительно начался (прошли порог)
    private int touchSlop = 0; // Минимальное расстояние для начала драга
    private float dragStartOffset = 0; // Смещение на момент начала драга
    private android.view.VelocityTracker velocityTracker;
    
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
        // Получаем touch slop из конфигурации системы
        android.view.ViewConfiguration config = android.view.ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        android.util.Log.d("DraggableSidePanel", "Touch slop initialized: " + touchSlop + "px");
    }
    
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0) {
            // Первый дочерний элемент - это корневой элемент из include (CardView или LinearLayout)
            animatedView = getChildAt(0);
            dragView = animatedView; // Для совместимости
            
            // Ищем dragZone внутри animatedView
            if (animatedView instanceof android.view.ViewGroup) {
                dragZone = animatedView.findViewById(com.example.animelib.R.id.dragZone);
            }
            
            android.util.Log.d("DraggableSidePanel", "onFinishInflate: animatedView=" + animatedView + 
                ", dragZone=" + dragZone);
            
            setupTouchListener();
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchListener() {
        if (animatedView == null) {
            android.util.Log.e("DraggableSidePanel", "animatedView is null!");
            return;
        }
        
        android.util.Log.d("DraggableSidePanel", "Setting up touch listeners. dragZone=" + (dragZone != null ? "found" : "null"));
        
        // Touch listener для drag zone
        if (dragZone != null) {
            dragZone.setOnTouchListener((v, event) -> {
                if (!isOpen) return false;
                return handleTouchEvent(event);
            });
        }
    }
    
    public void setDragEnabled(boolean enabled) {
        this.dragEnabled = enabled;
    }

    private void clearPendingCallbacks() {
        if (animatedView != null) {
            animatedView.removeCallbacks(null);
            animatedView.animate().cancel();
        }
        removeCallbacks(null);
    }

    private float getPanelWidth() {
        if (animatedView == null) {
            return android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 360f, getResources().getDisplayMetrics());
        }
        if (animatedView.getWidth() > 0) {
            return animatedView.getWidth();
        }
        if (animatedView.getMeasuredWidth() > 0) {
            return animatedView.getMeasuredWidth();
        }
        
        // Заставляем измериться если еще не был измерен
        int parentW = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        int parentH = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        animatedView.measure(
            MeasureSpec.makeMeasureSpec(parentW, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(parentH, MeasureSpec.EXACTLY)
        );
        if (animatedView.getMeasuredWidth() > 0) {
            return animatedView.getMeasuredWidth();
        }
        
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 360f, getResources().getDisplayMetrics());
    }

    private boolean handleTouchEvent(MotionEvent event) {
        if (!dragEnabled || !isOpen || animatedView == null || getVisibility() != View.VISIBLE) return false;
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                clearPendingCallbacks();
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                initialTranslationX = animatedView.getTranslationX();
                isDragging = true;
                isDragStarted = false; // Сбрасываем флаг начала драга
                dragStartOffset = 0;
                
                if (velocityTracker == null) {
                    velocityTracker = android.view.VelocityTracker.obtain();
                } else {
                    velocityTracker.clear();
                }
                velocityTracker.addMovement(event);
                return true;
                
            case MotionEvent.ACTION_MOVE:
                if (!isDragging) return false;
                
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                }
                
                float deltaX = event.getRawX() - initialTouchX;
                float deltaY = event.getRawY() - initialTouchY;
                
                // Проверяем прошли ли порог для начала драга
                if (!isDragStarted) {
                    float totalDelta = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                    
                    if (totalDelta < touchSlop) {
                        // Еще не прошли порог - просто ждем
                        return true;
                    }
                    
                    // Порог пройден - запоминаем текущее смещение
                    isDragStarted = true;
                    dragStartOffset = deltaX;
                    if (listener != null) {
                        listener.onPanelDragStart();
                    }
                    
                    android.util.Log.d("DraggableSidePanel", "Drag started. touchSlop=" + touchSlop + 
                                     ", totalDelta=" + totalDelta + ", dragStartOffset=" + dragStartOffset);
                }
                
                // Вычисляем новую позицию с учетом смещения на момент начала
                float adjustedDeltaX = deltaX - dragStartOffset;
                float newTranslationX = initialTranslationX + adjustedDeltaX;
                
                // Ограничиваем: от 0 (открыто) до width (закрыто)
                float pw = getPanelWidth();
                newTranslationX = Math.max(0, Math.min(newTranslationX, pw));
                animatedView.setTranslationX(newTranslationX);
                
                // Уведомляем о скольжении
                if (listener != null && pw > 0) {
                    float slideOffset = newTranslationX / pw;
                    listener.onPanelSliding(slideOffset);
                }
                return true;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isDragging) return false;
                
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                }
                float xVel = velocityTracker != null ? velocityTracker.getXVelocity() : 0f;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                
                // Сохраняем состояние до сброса
                boolean dragWasStarted = isDragStarted;
                
                isDragging = false;
                isDragStarted = false; // Сбрасываем флаг
                
                // Если драг не начался (не прошли touchSlop), не обрабатываем как drag gesture
                if (!dragWasStarted && event.getAction() == MotionEvent.ACTION_UP) {
                    android.util.Log.d("DraggableSidePanel", "Touch released without starting drag (touchSlop not passed)");
                    return false; // Позволяем обработать как обычное нажатие
                }
                
                float currentTranslationX = animatedView.getTranslationX();
                float panelWidth = getPanelWidth();
                
                boolean shouldClose;
                if (xVel > 400f) {
                    // Быстрый свайп вправо -> закрыть
                    shouldClose = true;
                } else if (xVel < -400f) {
                    // Быстрый свайп влево -> открыть
                    shouldClose = false;
                } else {
                    shouldClose = currentTranslationX > panelWidth * COLLAPSE_THRESHOLD;
                }
                
                if (shouldClose) {
                    // Проверяем можно ли закрыть
                    if (listener != null && !listener.canClosePanel()) {
                        // Нельзя закрыть - показываем "тряску" и возвращаем в открытое положение
                        shakePanel();
                    } else {
                        // Закрываем
                        animateClose();
                    }
                } else {
                    // Возвращаем в открытое положение
                    animateToOpen();
                }
                return true;
        }
        return false;
    }
    
    public void setOnPanelStateChangeListener(OnPanelStateChangeListener listener) {
        this.listener = listener;
    }
    
    public void openPanel() {
        if (animatedView == null) return;
        
        clearPendingCallbacks();
        isOpen = true;
        
        setVisibility(VISIBLE);
        animatedView.setVisibility(VISIBLE);
        
        float panelWidth = getPanelWidth();
        if (animatedView.getTranslationX() == 0f && animatedView.getWidth() > 0 && isOpen) {
            android.util.Log.d("DraggableSidePanel", "Panel already open, skipping");
            if (listener != null) {
                listener.onPanelOpened();
            }
            return;
        }
        
        if (animatedView.getTranslationX() == 0f) {
            animatedView.setTranslationX(panelWidth);
        }
        
        animateToOpen();
    }
    
    private void animateToOpen() {
        if (animatedView == null) return;
        
        clearPendingCallbacks();
        isOpen = true;
        
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        if (animatedView.getVisibility() != VISIBLE) animatedView.setVisibility(VISIBLE);
        
        if (listener != null) {
            listener.onPanelDragStart();
        }
        
        float panelWidth = getPanelWidth();
        float currentX = animatedView.getTranslationX();
        float remainingRatio = panelWidth > 0 ? Math.abs(currentX) / panelWidth : 1f;
        long duration = Math.max(150, Math.min(ANIMATION_DURATION, (long) (ANIMATION_DURATION * remainingRatio)));
        
        animatedView.animate()
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(BOTTOM_SHEET_INTERPOLATOR)
            .setUpdateListener(animation -> {
                if (listener != null && panelWidth > 0) {
                    float curX = animatedView.getTranslationX();
                    float slideOffset = curX / panelWidth;
                    listener.onPanelSliding(Math.max(0f, Math.min(1f, slideOffset)));
                }
            })
            .withEndAction(() -> {
                clearPendingCallbacks();
                isOpen = true;
                animatedView.setTranslationX(0f);
                if (listener != null) {
                    listener.onPanelOpened();
                }
            })
            .start();
    }

    public void forceClose() {
        isOpen = false;
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
        
        // Проверяем можно ли закрыть
        if (listener != null && !listener.canClosePanel()) {
            android.util.Log.d("DraggableSidePanel", "Panel close blocked by listener");
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
        clearPendingCallbacks();
        
        // Быстрая анимация влево-вправо
        animatedView.animate()
            .translationX(20)
            .setDuration(50)
            .setInterpolator(new android.view.animation.LinearInterpolator())
            .withEndAction(() -> {
                animatedView.animate()
                    .translationX(-20)
                    .setDuration(50)
                    .withEndAction(() -> {
                        animatedView.animate()
                            .translationX(0)
                            .setDuration(50)
                            .start();
                    })
                    .start();
            })
            .start();
    }
    
    private void animateClose() {
        if (animatedView == null) return;
        
        clearPendingCallbacks();
        
        if (listener != null) {
            listener.onPanelDragStart();
        }
        
        float targetX = getPanelWidth();
        float currentX = animatedView.getTranslationX();
        float remainingRatio = targetX > 0 ? Math.abs(targetX - currentX) / targetX : 1f;
        long duration = Math.max(150, Math.min(ANIMATION_DURATION, (long) (ANIMATION_DURATION * remainingRatio)));

        animatedView.animate()
            .translationX(targetX)
            .setDuration(duration)
            .setInterpolator(BOTTOM_SHEET_INTERPOLATOR)
            .setUpdateListener(animation -> {
                if (listener != null && targetX > 0) {
                    float curX = animatedView.getTranslationX();
                    float slideOffset = curX / targetX;
                    listener.onPanelSliding(Math.max(0f, Math.min(1f, slideOffset)));
                }
            })
            .withEndAction(() -> {
                clearPendingCallbacks();
                isOpen = false;
                animatedView.setTranslationX(targetX);
                setVisibility(GONE);
                
                if (listener != null) {
                    listener.onPanelClosed();
                }
            })
            .start();
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
     * Устанавливает прогресс drag (0.0 = закрыто, 1.0 = открыто)
     * Используется для плавного вытягивания панели во время жеста
     */
    public void setDragProgress(float progress) {
        if (animatedView == null) return;
        
        clearPendingCallbacks();
        
        progress = Math.max(0f, Math.min(1f, progress));
        
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        if (animatedView.getVisibility() != VISIBLE) {
            animatedView.setVisibility(VISIBLE);
        }
        
        float panelWidth = getPanelWidth();
        float translationX = panelWidth * (1f - progress);
        
        animatedView.setTranslationX(translationX);
        
        if (listener != null && panelWidth > 0) {
            listener.onPanelSliding(1f - progress);
        }
    }
    
    /**
     * Завершает drag жест с решением открыть или закрыть панель
     */
    public void completeDrag(boolean shouldOpen) {
        if (animatedView == null) return;
        
        clearPendingCallbacks();
        android.util.Log.d("DraggableSidePanel", "Complete drag: shouldOpen=" + shouldOpen);
        
        if (shouldOpen) {
            animateToOpen();
        } else {
            animateClose();
        }
    }
}
