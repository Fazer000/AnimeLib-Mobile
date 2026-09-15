package com.example.animelib.managers;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * Менеджер для обработки вертикальных жестов (эпизоды, яркость и громкость)
 * Работает независимо от горизонтальных жестов
 */
public class VerticalGesturesManager {
    private static final String TAG = "VerticalGestures";
    
    // Типы вертикальных панелей и жестов
    public enum PanelType {
        NONE,
        EPISODES,
        RELATED_INFO,
        BRIGHTNESS,
        VOLUME
    }
    
    // Callback интерфейс
    public interface VerticalGestureCallback {
        void onEpisodesDragProgress(float progress);
        void onRelatedInfoDragProgress(float progress);
        void onEpisodesDragComplete(boolean shouldOpen);
        void onRelatedInfoDragComplete(boolean shouldOpen);
        boolean isEpisodesOpen();
        boolean isRelatedInfoOpen();

        default void onBrightnessChanged(float brightness) {}
        default void onVolumeChanged(int currentVolume, int maxVolume) {}
        default void onGestureCompleted() {}
    }
    
    private final Context context;
    private VerticalGestureCallback callback;
    private GesturesManager gesturesManager; // Для отмены hold-to-speed таймера
    
    // Размеры экрана
    private int screenHeight;
    private int screenWidth;
    private int bottomZoneHeight; // Зона для эпизодов снизу (уменьшена)
    
    // Состояние drag
    private boolean isDragging = false;
    private PanelType currentPanel = PanelType.NONE;
    private float dragStartY;
    private float dragStartX;

    // Начальные параметры для регулировок
    private float initialBrightness = -1f;
    private int initialVolume = -1;
    private int maxVolume = 15;
    
    // Пороги
    private static final float OPEN_THRESHOLD = 0.25f; // 25% движения достаточно для открытия
    private static final float DRAG_SENSITIVITY = 0.30f; // 30% высоты экрана - чувствительность для эпизодов
    private static final float RELATED_INFO_DRAG_SENSITIVITY = 0.6f;
    private static final float MIN_DRAG_DISTANCE = 10f; // Мгновенный подхват драга
    
    private boolean isPortraitMode = false;
    
    public VerticalGesturesManager(Context context) {
        this.context = context;
        initializeScreenDimensions();
    }
    
    public void setPortraitMode(boolean isPortrait) {
        this.isPortraitMode = isPortrait;
    }
    
    public void setCallback(VerticalGestureCallback callback) {
        this.callback = callback;
    }
    
    public void setGesturesManager(GesturesManager gesturesManager) {
        this.gesturesManager = gesturesManager;
    }
    
    private void initializeScreenDimensions() {
        screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        bottomZoneHeight = (int) (screenHeight * 0.45f); // 45% высоты экрана снизу для легкого открытия эпизодов
        
        Log.d(TAG, "Screen: " + screenWidth + "x" + screenHeight + ", bottomZone: " + bottomZoneHeight + "px");
    }
    
    /**
     * Обрабатывает touch событие
     * @return true если событие было обработано
     */
    public boolean onTouchEvent(MotionEvent event) {
        if (isPortraitMode || callback == null) return false;
        
        if (callback.isRelatedInfoOpen()) {
            return false;
        }
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                return handleDown(event);
                
            case MotionEvent.ACTION_MOVE:
                return handleMove(event);
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return handleUp(event);
        }
        
        return false;
    }
    
    private boolean handleDown(MotionEvent event) {
        initializeScreenDimensions();
        dragStartY = event.getRawY();
        dragStartX = event.getRawX();
        isDragging = false;
        currentPanel = PanelType.NONE;
        return false;
    }
    
    private boolean handleMove(MotionEvent event) {
        float currentY = event.getRawY();
        float currentX = event.getRawX();
        float deltaY = currentY - dragStartY;
        float deltaX = currentX - dragStartX;
        float absDeltaY = Math.abs(deltaY);
        float absDeltaX = Math.abs(deltaX);
        
        if (!isDragging && absDeltaY < MIN_DRAG_DISTANCE) {
            return false;
        }
        
        if (!isDragging) {
            if (gesturesManager != null && gesturesManager.isHorizontalGestureActive()) {
                Log.d(TAG, "Horizontal gesture is active, blocking vertical gesture");
                return false;
            }
            
            if (absDeltaX > absDeltaY * 1.2f) {
                Log.d(TAG, "Too much horizontal movement, ignoring: deltaX=" + deltaX + ", deltaY=" + deltaY);
                return false;
            }
            
            currentPanel = detectPanelType(deltaY);
            if (currentPanel == PanelType.NONE) {
                return false;
            }
            isDragging = true;
            
            if (gesturesManager != null) {
                gesturesManager.cancelHoldToSpeedTimer();
            }
            
            Log.d(TAG, "Started dragging: " + currentPanel + ", deltaY=" + deltaY);
        }
        
        if (currentPanel == PanelType.EPISODES) {
            float progress = calculateProgress(currentPanel, currentY);
            callback.onEpisodesDragProgress(progress);
        } else if (currentPanel == PanelType.BRIGHTNESS) {
            updateBrightness(currentY);
        } else if (currentPanel == PanelType.VOLUME) {
            updateVolume(currentY);
        } else if (currentPanel == PanelType.RELATED_INFO) {
            float progress = calculateProgress(currentPanel, currentY);
            callback.onRelatedInfoDragProgress(progress);
        }
        
        return true;
    }
    
    private boolean handleUp(MotionEvent event) {
        if (!isDragging || currentPanel == PanelType.NONE) {
            isDragging = false;
            currentPanel = PanelType.NONE;
            return false;
        }
        
        if (currentPanel == PanelType.EPISODES) {
            float finalProgress = calculateProgress(currentPanel, event.getRawY());
            boolean shouldOpen = finalProgress > OPEN_THRESHOLD;
            Log.d(TAG, "Episodes drag completed: progress=" + finalProgress + ", shouldOpen=" + shouldOpen);
            callback.onEpisodesDragComplete(shouldOpen);
        } else if (currentPanel == PanelType.RELATED_INFO) {
            float finalProgress = calculateProgress(currentPanel, event.getRawY());
            boolean shouldOpen = finalProgress > OPEN_THRESHOLD;
            callback.onRelatedInfoDragComplete(shouldOpen);
        } else if (currentPanel == PanelType.BRIGHTNESS || currentPanel == PanelType.VOLUME) {
            if (callback != null) {
                callback.onGestureCompleted();
            }
        }
        
        isDragging = false;
        currentPanel = PanelType.NONE;
        return true;
    }
    
    /**
     * Определяет тип панели/жеста на основе координат
     * Левая зона 25% — Яркость (на всю высоту)
     * Правая зона 25% — Громкость (на всю высоту)
     * Центральная зона 50% — Эпизоды (на всю высоту)
     */
    private PanelType detectPanelType(float deltaY) {
        // Отступ сверху (15% высоты экрана или минимум 60dp), чтобы не перехватывать свайп системной шторки
        float topSafetyMargin = Math.max(screenHeight * 0.15f, 60 * context.getResources().getDisplayMetrics().density);
        if (dragStartY < topSafetyMargin) {
            Log.d(TAG, "Touch started in top safety margin (" + dragStartY + " < " + topSafetyMargin + "), ignoring gesture for system status bar");
            return PanelType.NONE;
        }

        // Левая зона: 0%..25% ширины экрана — Яркость
        if (dragStartX <= screenWidth * 0.25f) {
            Log.d(TAG, "→ BRIGHTNESS (left 25% zone)");
            initBrightnessGesture();
            return PanelType.BRIGHTNESS;
        }

        // Правая зона: 75%..100% ширины экрана — Громкость
        if (dragStartX >= screenWidth * 0.75f) {
            Log.d(TAG, "→ VOLUME (right 25% zone)");
            initVolumeGesture();
            return PanelType.VOLUME;
        }

        // Центральная зона: 25%..75% ширины экрана (50% по центру) — Эпизоды
        Log.d(TAG, "→ EPISODES (center 50% zone)");
        return PanelType.EPISODES;
    }

    private void initBrightnessGesture() {
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            if (lp.screenBrightness >= 0f) {
                initialBrightness = lp.screenBrightness;
            } else {
                try {
                    int sysBrightness = Settings.System.getInt(
                            context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                    initialBrightness = sysBrightness / 255f;
                } catch (Exception e) {
                    initialBrightness = 0.5f;
                }
            }
        } else {
            initialBrightness = 0.5f;
        }
    }

    private void initVolumeGesture() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }
    }

    private void updateBrightness(float currentY) {
        float deltaY = dragStartY - currentY; // Свайп вверх увеличивает
        float deltaBrightness = deltaY / (screenHeight * 0.5f);
        float targetBrightness = Math.max(0.01f, Math.min(1.0f, initialBrightness + deltaBrightness));

        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            lp.screenBrightness = targetBrightness;
            activity.getWindow().setAttributes(lp);
        }

        if (callback != null) {
            callback.onBrightnessChanged(targetBrightness);
        }
    }

    private void updateVolume(float currentY) {
        float deltaY = dragStartY - currentY; // Свайп вверх увеличивает
        float deltaVolumePercent = deltaY / (screenHeight * 0.5f);
        int targetVolume = Math.max(0, Math.min(maxVolume, Math.round(initialVolume + deltaVolumePercent * maxVolume)));

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0);
        }

        if (callback != null) {
            callback.onVolumeChanged(targetVolume, maxVolume);
        }
    }
    
    /**
     * Вычисляет прогресс (0.0 - 1.0) для панели эпизодов
     */
    private float calculateProgress(PanelType panel, float currentY) {
        if (panel == PanelType.EPISODES) {
            float maxDistance = screenHeight * DRAG_SENSITIVITY;
            boolean isOpen = callback.isEpisodesOpen();
            float distance = dragStartY - currentY; // Вверх положительное
            
            if (isOpen) {
                float rawProgress = distance / maxDistance;
                return Math.max(0f, Math.min(1f, 1.0f + rawProgress));
            } else {
                float rawProgress = distance / maxDistance;
                return Math.max(0f, rawProgress);
            }
        } else if (panel == PanelType.RELATED_INFO) {
            float maxDistance = screenHeight * RELATED_INFO_DRAG_SENSITIVITY;
            boolean isOpen = callback.isRelatedInfoOpen();
            float distance = currentY - dragStartY;
            
            if (isOpen) {
                float rawProgress = Math.abs(distance) / maxDistance;
                return Math.max(0f, Math.min(1f, 1.0f - rawProgress));
            } else {
                float rawProgress = distance / maxDistance;
                return Math.max(0f, rawProgress);
            }
        }
        
        return 0f;
    }
    
    public boolean isDragging() {
        return isDragging;
    }
    
    public void reset() {
        isDragging = false;
        currentPanel = PanelType.NONE;
    }
}
