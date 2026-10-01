package com.example.animelib.managers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

import java.util.Locale;

/**
 * Менеджер для управления жестами плеера.
 * Обеспечивает функциональность свайпа для перемотки и удержания для ускорения
 */
public class GesturesManager {
    private static final String TAG = "GesturesManager";
    private static final int DOUBLE_TAP_SKIP_SECONDS = 10;
    
    // Контекст и зависимости
    private final Context context;
    private PlayerView playerView;
    private Player player;
    
    // UI компоненты
    private View holdSpeedToast;
    private View seekPreviewText;
    private View skipIndicatorLeft;
    private View skipIndicatorRight;
    private android.widget.TextView skipTextLeft;
    private android.widget.TextView skipTextRight;
    
    // Состояние жестов
    private boolean isSwipingSeek = false;
    private boolean isHoldToSpeed = false;
    private boolean isGestureCooldown = false;
    private boolean isHorizontalGestureActive = false; // Флаг для блокировки вертикальных жестов
    private float swipeStartX = 0f;
    private float swipeStartY = 0f;
    private int swipeTouchSlopPx = 0;
    
    // Переменные для hold-to-speed с регулировкой
    private long holdStartTime = 0L;
    private Runnable holdToSpeedRunnable = null;
    private float currentSpeedMultiplier = 1.0f;
    private boolean isSpeedAdjustmentMode = false;
    private float speedAdjustmentStartX = 0f;
    private float speedAdjustmentSensitivity = 0.5f; // Чувствительность регулировки скорости
    
    // Переменные для свайпа (как в оригинале)
    private float swipeAccumulatedDx = 0f;
    private long basePositionMs = 0L;
    private Float lastSwipeX = null;
    
    // Переменные для edge swipes
    private boolean isEdgeSwipe = false;
    private EdgeSwipeType currentEdgeSwipeType = EdgeSwipeType.NONE;
    private float edgeDragStartX = 0f;
    private float edgeDragStartY = 0f;
    private static final float EDGE_DRAG_THRESHOLD = 0.1f; // Порог для открытия панели (10%)
    private android.view.VelocityTracker velocityTracker = null;
    private int screenWidth = 0;
    private int screenHeight = 0;
    private static final int EDGE_SWIPE_THRESHOLD = 100; // dp для определения края экрана (увеличено с 50dp)
    private static final int BOTTOM_ZONE_HEIGHT = 200; // dp для нижней зоны (над таймбаром)
    private static final int TOP_ZONE_HEIGHT = 200; // dp для верхней зоны (для закрытия эпизодов)
    private int edgeSwipeThresholdPx = 0;
    private int bottomZoneHeightPx = 0;
    private int topZoneHeightPx = 0;
    
    // GestureDetector для двойного нажатия
    private GestureDetector doubleTapDetector;
    
    // Callback интерфейсы (только для горизонтальных жестов и других не-вертикальных действий)
    public interface GestureCallback {
        void onSeekGesture(long seekPosition);
        void onSpeedChange(float speed);
        void updatePlayLoadingIndicator(int playbackState);
        void onCommentsSwipeFromRight(); // Свайп справа для комментариев
        void onPlayersSwipeFromRight(); // Свайп справа снизу для озвучек
        
        // Методы для drag-to-open панелей (только горизонтальные)
        void onCommentsDragProgress(float progress); // Прогресс вытягивания панели комментариев
        void onPlayersDragProgress(float progress); // Прогресс вытягивания панели озвучек
        void onPanelDragComplete(EdgeSwipeType type, boolean shouldOpen); // Завершение drag жеста
        
        // Метод для двойного нажатия (длинная перемотка)
        void onDoubleTapSkip(boolean isForward, int skipDurationSeconds); // Двойное нажатие для длинной перемотки
        
        // УСТАРЕВШИЕ методы - оставлены для совместимости, но не используются
        // Вертикальные жесты теперь в VerticalGesturesManager
        @Deprecated default void onEpisodesSwipeUp() {}
        @Deprecated default void onEpisodesSwipeDown() {}
        @Deprecated default void onEpisodesDragProgress(float progress) {}
        @Deprecated default void onRelatedTitlesDragProgress(float progress) {}
        @Deprecated default boolean isEpisodesMenuVisible() { return false; }
        @Deprecated default boolean isRelatedTitlesMenuVisible() { return false; }
    }
    
    private GestureCallback gestureCallback;
    private VerticalGesturesManager verticalGesturesManager;
    
    /**
     * Конструктор GesturesManager
     * @param context Контекст приложения
     */
    public GesturesManager(Context context) {
        this.context = context;
        
        // Получаем минимальное расстояние для распознавания свайпа
        ViewConfiguration config = ViewConfiguration.get(context);
        swipeTouchSlopPx = config.getScaledTouchSlop();
        
        // Конвертируем dp в пиксели для edge swipes
        float density = context.getResources().getDisplayMetrics().density;
        edgeSwipeThresholdPx = (int) (EDGE_SWIPE_THRESHOLD * density);
        bottomZoneHeightPx = (int) (BOTTOM_ZONE_HEIGHT * density);
        topZoneHeightPx = (int) (TOP_ZONE_HEIGHT * density);
        
        // Получаем размеры экрана
        screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        
        Log.d(TAG, "GesturesManager initialized - touch slop: " + swipeTouchSlopPx + 
                ", edge threshold: " + edgeSwipeThresholdPx + 
                ", bottom zone: " + bottomZoneHeightPx + ", top zone: " + topZoneHeightPx +
                ", screen: " + screenWidth + "x" + screenHeight);
    }
    
    /**
     * Инициализация с UI компонентами
     * @param playerView PlayerView для обработки жестов
     * @param player ExoPlayer для управления воспроизведением
     * @param holdSpeedToast Toast для отображения ускорения
     * @param seekPreviewText TextView для отображения превью перемотки
     * @param skipIndicatorLeft Индикатор перемотки назад
     * @param skipIndicatorRight Индикатор перемотки вперед
     */
    public void initializeViews(PlayerView playerView, Player player, View holdSpeedToast, View seekPreviewText,
                                View skipIndicatorLeft, View skipIndicatorRight) {
        this.playerView = playerView;
        this.player = player;
        this.holdSpeedToast = holdSpeedToast;
        this.seekPreviewText = seekPreviewText;
        this.skipIndicatorLeft = skipIndicatorLeft;
        this.skipIndicatorRight = skipIndicatorRight;
        
        // Получаем TextView из индикаторов
        if (skipIndicatorLeft != null) {
            skipTextLeft = skipIndicatorLeft.findViewById(com.example.animelib.R.id.skipTextLeft);
        }
        if (skipIndicatorRight != null) {
            skipTextRight = skipIndicatorRight.findViewById(com.example.animelib.R.id.skipTextRight);
        }

        updateSkipDurationText(DOUBLE_TAP_SKIP_SECONDS);
        updateIndicatorSizesForPortrait(isPortraitMode);
        setupGestures();
    }
    
    /**
     * Настройка всех жестов
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        if (playerView == null) {
            Log.w(TAG, "PlayerView is null, cannot setup gestures");
            return;
        }
        
        setupDoubleTapDetector();
        setupCombinedGestures();
        
        Log.d(TAG, "Gestures setup completed");
    }
    
    private int getEffectiveWidth() {
        if (playerView != null && playerView.getWidth() > 0) {
            return playerView.getWidth();
        }
        return context.getResources().getDisplayMetrics().widthPixels;
    }

    private int getEffectiveHeight() {
        if (playerView != null && playerView.getHeight() > 0) {
            return playerView.getHeight();
        }
        return context.getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * Настройка детектора двойного нажатия
     */
    private void setupDoubleTapDetector() {
        doubleTapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // Не обрабатываем двойное нажатие если активен другой жест
                if (isSwipingSeek || isHoldToSpeed || isEdgeSwipe) {
                    Log.d(TAG, "Double tap ignored - another gesture is active");
                    return false;
                }
                
                float tapX = e.getX();
                boolean isLeftSide = tapX < (getEffectiveWidth() / 2f);
                
                Log.d(TAG, "Double tap detected at x=" + tapX + ", isLeftSide=" + isLeftSide);
                
                // Показываем индикатор и вызываем callback
                if (isLeftSide) {
                    showSkipIndicator(false); // Назад
                } else {
                    showSkipIndicator(true); // Вперед
                }
                
                return true;
            }
            
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // Пропускаем - обработка кликов остается стандартной
                return false;
            }
        });
    }
    
    /**
     * Настройка объединенных жестов (swipe seek + hold to speed)
     */
    @SuppressLint({"ClickableViewAccessibility", "SetTextI18n"})
    private void setupCombinedGestures() {
        if (playerView == null) return;
        
        // Убираем OnLongClickListener - будем обрабатывать все через OnTouchListener
        playerView.setOnLongClickListener(null);
        
        // Setup combined touch listener for both swipe seek and hold-to-speed
        setupSwipeSeek();
    }
    
    /**
     * Настройка свайпа для перемотки (оригинальная реализация)
     */
    private boolean isScreenLocked = false;

    public void setScreenLocked(boolean locked) {
        this.isScreenLocked = locked;
        if (locked) {
            hideAllGesturesUI();
        }
    }

    public boolean isScreenLocked() {
        return isScreenLocked;
    }

    @OptIn(markerClass = UnstableApi.class)
    @SuppressLint({"ClickableViewAccessibility", "SetTextI18n"})
    private void setupSwipeSeek() {
        if (playerView == null) return;
        
        playerView.setOnTouchListener((v, event) -> {
            if (isScreenLocked) return false;
            if (player == null) return false;
            
            // ПРИОРИТЕТ 1: Вертикальные жесты (эпизоды и панель с инфо) обрабатываются ПЕРВЫМИ
            // НО: блокируем если уже активен горизонтальный жест
            if (!isHorizontalGestureActive && verticalGesturesManager != null && verticalGesturesManager.onTouchEvent(event)) {
                Log.d(TAG, "Event handled by VerticalGesturesManager");
                // Если вертикальный жест обработан, не продолжаем с другими жестами
                return true;
            }
            
            // Если активен вертикальный жест, блокируем горизонтальные
            if (verticalGesturesManager != null && verticalGesturesManager.isDragging()) {
                return false; // Пропускаем горизонтальную обработку
            }
            
            // ПРИОРИТЕТ 2: Пробуем обработать через GestureDetector для двойного нажатия
            if (doubleTapDetector != null && doubleTapDetector.onTouchEvent(event)) {
                Log.d(TAG, "Event handled by GestureDetector");
                // Если это двойное нажатие, не продолжаем обработку других жестов
                return true;
            }
            
            // Если активен cooldown после жестов, не перехватываем события
            if (isGestureCooldown) {
                return false;
            }
            
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (velocityTracker == null) {
                        velocityTracker = android.view.VelocityTracker.obtain();
                    } else {
                        velocityTracker.clear();
                    }
                    velocityTracker.addMovement(event);

                    // Принудительно сбрасываем все состояния жестов
                    if (isHoldToSpeed && player != null) {
                        Log.d(TAG, "Force reset hold-to-speed on new touch");
                        resetHoldToSpeed();
                    }
                    
                     // Инициализируем переменные для жестов
                     isSwipingSeek = false;
                     isEdgeSwipe = false;
                     isHorizontalGestureActive = false; // Сбрасываем флаг блокировки
                     currentEdgeSwipeType = EdgeSwipeType.NONE;
                     isSpeedAdjustmentMode = false;
                     swipeAccumulatedDx = 0f;
                     assert player != null;
                     basePositionMs = player.getCurrentPosition();
                     lastSwipeX = event.getX();
                     swipeStartX = event.getX();
                     swipeStartY = event.getY();
                     speedAdjustmentStartX = event.getX();
                     holdStartTime = System.currentTimeMillis();
                     currentSpeedMultiplier = 1.0f; // Сбрасываем скорость на 1.0x при новом касании
                    
                    // НЕ проверяем edge swipe в ACTION_DOWN - это блокирует обычные жесты
                    // Проверка будет в ACTION_MOVE
                    
                    // Запускаем таймер для hold-to-speed
                    if (holdToSpeedRunnable != null) {
                        v.removeCallbacks(holdToSpeedRunnable);
                    }
                    holdToSpeedRunnable = () -> {
                        if (!isSwipingSeek && !isHoldToSpeed && player != null) {
                            Log.d(TAG, "Hold to speed activated");
                            activateHoldToSpeed();
                        }
                    };
                    v.postDelayed(holdToSpeedRunnable, 500); // 500ms для активации hold-to-speed
                    
                    if (seekPreviewText != null) {
                        seekPreviewText.setVisibility(View.GONE);
                        if (seekPreviewText instanceof android.widget.TextView) {
                            ((android.widget.TextView) seekPreviewText).setText("0 c");
                        }
                    }
                    return false; // НЕ перехватываем событие, позволяем контролам работать
                    
                case MotionEvent.ACTION_MOVE:
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                    }
                    float currentX = event.getX();
                    float currentY = event.getY();
                    float totalDx = Math.abs(currentX - swipeStartX);
                    float totalDy = Math.abs(currentY - swipeStartY);
                    boolean passedDeadZone = totalDx > swipeTouchSlopPx && totalDx > totalDy * 1.5f;

                    // Проверяем edge swipe ТОЛЬКО если начало было с края И есть движение
                    if (!isEdgeSwipe && !isSwipingSeek && !isHoldToSpeed &&
                        (totalDx > swipeTouchSlopPx || totalDy > swipeTouchSlopPx)) {
                        // Проверяем, начался ли жест с края экрана
                        if (isEdgeSwipeStart(swipeStartX, swipeStartY)) {
                            EdgeSwipeType swipeType = detectEdgeSwipeType(swipeStartX, swipeStartY, currentX, currentY);
                            if (swipeType != EdgeSwipeType.NONE) {
                                isEdgeSwipe = true;
                                isHorizontalGestureActive = true; // Блокируем вертикальные жесты
                                currentEdgeSwipeType = swipeType;
                                edgeDragStartX = swipeStartX;
                                edgeDragStartY = swipeStartY;
                                
                                // ВАЖНО: Отменяем таймер hold-to-speed при начале edge swipe
                                if (holdToSpeedRunnable != null) {
                                    v.removeCallbacks(holdToSpeedRunnable);
                                    holdToSpeedRunnable = null;
                                }
                                
                                // Сбрасываем hold-to-speed если он был активен (на всякий случай)
                                if (isHoldToSpeed) {
                                    resetHoldToSpeed();
                                }
                                
                                Log.d(TAG, "Edge drag started: " + swipeType + " - blocking vertical gestures");
                                return true;
                            }
                        }
                    }
                    
                    // Обработка drag progress для edge swipe
                    if (isEdgeSwipe && currentEdgeSwipeType != EdgeSwipeType.NONE) {
                        float progress = calculateEdgeDragProgress(currentEdgeSwipeType, currentX, currentY);
                        updateEdgeDragProgress(currentEdgeSwipeType, progress);
                        return true;
                    }

                    // Если есть движение, отменяем hold-to-speed
                    if (passedDeadZone && holdToSpeedRunnable != null) {
                        v.removeCallbacks(holdToSpeedRunnable);
                        holdToSpeedRunnable = null;
                    }

                    // Обработка регулировки скорости при активном hold-to-speed
                    // НЕ обрабатываем если уже начался edge swipe
                    if (isHoldToSpeed && !isEdgeSwipe) {
                        handleSpeedAdjustment(currentX);
                        return true;
                    }

                    if (!isSwipingSeek && !isEdgeSwipe) {
                        if (passedDeadZone) {
                            isSwipingSeek = true; // Устанавливаем сразу без задержки
                            isHorizontalGestureActive = true; // Блокируем вертикальные жесты
                            // блокируем перехват родителями (ViewPager и т.п.)
                            android.view.ViewParent p = v.getParent();
                            if (p != null) p.requestDisallowInterceptTouchEvent(true);
                            // показать превью
                            if (seekPreviewText != null)
                                seekPreviewText.setVisibility(View.VISIBLE);
                            if (gestureCallback != null && player != null) {
                                gestureCallback.updatePlayLoadingIndicator(player.getPlaybackState());
                            }
                            
                            // Показываем контролы при начале свайпа
                            if (playerView != null) {
                                playerView.showController();
                            }
                            
                            Log.d(TAG, "Swipe seek started - blocking vertical gestures");
                            return true;
                        } else {
                            return false; // НЕ перехватываем событие для обычных касаний
                        }
                    }

                    // уже в режиме свайпа — накапливаем дельту
                    if (lastSwipeX == null) lastSwipeX = currentX;
                    float delta = currentX - lastSwipeX;
                    lastSwipeX = currentX;
                    swipeAccumulatedDx += delta;
                    long offsetSec = Math.round(swipeAccumulatedDx / 12f);
                    if (seekPreviewText != null && seekPreviewText instanceof android.widget.TextView) {
                        String sign = offsetSec >= 0 ? "+" : "";
                        ((android.widget.TextView) seekPreviewText).setText(sign + offsetSec + " c");
                    }
                    if (gestureCallback != null && player != null) {
                        gestureCallback.updatePlayLoadingIndicator(player.getPlaybackState());
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                        velocityTracker.computeCurrentVelocity(1000);
                    }
                    float xVel = velocityTracker != null ? velocityTracker.getXVelocity() : 0f;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }

                    // Отменяем таймер hold-to-speed
                    if (holdToSpeedRunnable != null) {
                        v.removeCallbacks(holdToSpeedRunnable);
                        holdToSpeedRunnable = null;
                    }
                    
                    // Принудительно скрываем seek preview если он видим
                    if (seekPreviewText != null && seekPreviewText.getVisibility() == View.VISIBLE) {
                        seekPreviewText.setVisibility(View.GONE);
                    }
                    
                    boolean handledGesture = false;
                    
                    // Handle swipe seek completion
                    if (isSwipingSeek) {
                        long finalOffsetMs = Math.round(swipeAccumulatedDx / 12f) * 1000L;
                        long newPos = Math.max(0, basePositionMs + finalOffsetMs);
                        long dur = player.getDuration();
                        if (dur > 0) newPos = Math.min(newPos, dur);
                        player.seekTo(newPos);
                        if (gestureCallback != null) {
                            gestureCallback.onSeekGesture(newPos);
                        }
                        Log.d(TAG, "Seek completed: " + finalOffsetMs + "ms");
                        
                        // Показываем контролы плеера после перемотки
                        if (playerView != null) {
                            playerView.showController();
                        }
                        
                        // Активируем cooldown для восстановления кликабельности
                        activateGestureCooldown();
                        
                        handledGesture = true;
                    }
                    
                    // Handle hold-to-speed release
                    if (isHoldToSpeed) {
                        Log.d(TAG, "Hold to speed deactivated");
                        resetHoldToSpeed();
                        
                        // Показываем контролы плеера после завершения ускорения
                        if (playerView != null) {
                            playerView.showController();
                        }
                        
                        // Активируем cooldown для восстановления кликабельности
                        activateGestureCooldown();
                        
                        handledGesture = true;
                    }
                    
                    // Handle edge drag completion (только для горизонтальных свайпов)
                    if (isEdgeSwipe && currentEdgeSwipeType != EdgeSwipeType.NONE) {
                        float finalProgress = calculateEdgeDragProgress(currentEdgeSwipeType, event.getX(), event.getY());
                        boolean shouldOpen;
                        if (xVel < -300f) {
                            // Быстрый свайп влево (открытие правой панели)
                            shouldOpen = true;
                        } else if (xVel > 300f) {
                            // Быстрый свайп вправо (закрытие панели)
                            shouldOpen = false;
                        } else {
                            shouldOpen = finalProgress >= EDGE_DRAG_THRESHOLD;
                        }
                        
                        Log.d(TAG, "Edge drag completed: " + currentEdgeSwipeType + 
                              ", progress=" + finalProgress + ", xVel=" + xVel + ", shouldOpen=" + shouldOpen);
                        
                        if (gestureCallback != null) {
                            gestureCallback.onPanelDragComplete(currentEdgeSwipeType, shouldOpen);
                        }
                        
                        handledGesture = true;
                    }
                    
                    // Сбрасываем состояние
                    isSwipingSeek = false;
                    isEdgeSwipe = false;
                    isHorizontalGestureActive = false; // Разблокируем вертикальные жесты
                    currentEdgeSwipeType = EdgeSwipeType.NONE;
                    swipeAccumulatedDx = 0f;
                    lastSwipeX = null;
                    
                    // Если был какой-то жест, перехватываем событие
                    // Если не было жеста, позволяем клику пройти для показа контролов
                    return handledGesture;
            }
            return false;
        });
    }
    
    /**
     * Типы edge swipes (только горизонтальные - вертикальные в VerticalGesturesManager)
     */
    public enum EdgeSwipeType {
        NONE,
        COMMENTS_RIGHT,     // Свайп справа сверху для комментариев
        PLAYERS_RIGHT       // Свайп справа снизу для озвучек
    }
    
    /**
     * Проверяет, начался ли жест с края экрана (только правый край для горизонтальных свайпов)
     */
    private boolean isEdgeSwipeStart(float x, float y) {
        // Проверяем только правый край (для комментариев и озвучек)
        // Вертикальные жесты обрабатываются в VerticalGesturesManager
        return x > (getEffectiveWidth() - edgeSwipeThresholdPx);
    }
    
    /**
     * Определяет тип edge swipe на основе начальной и конечной позиции
     */
    private EdgeSwipeType detectEdgeSwipeType(float startX, float startY, float endX, float endY) {
        if (isPortraitMode) {
            return EdgeSwipeType.NONE;
        }
        float deltaX = endX - startX;
        float deltaY = endY - startY;
        float absDeltaX = Math.abs(deltaX);
        float absDeltaY = Math.abs(deltaY);
        
        int effWidth = getEffectiveWidth();
        int effHeight = getEffectiveHeight();
        
        // ВЕРТИКАЛЬНЫЕ ЖЕСТЫ (эпизоды и панель с инфо) ОБРАБАТЫВАЮТСЯ В VerticalGesturesManager
        // Здесь остаются только горизонтальные свайпы справа (комментарии и озвучки)
        
        // Свайпы справа налево
        if (startX > (effWidth - edgeSwipeThresholdPx) && deltaX < -swipeTouchSlopPx && absDeltaX > absDeltaY) {
            // Определяем верх или низ экрана
            boolean isTopHalf = startY < (effHeight / 2f);
            
            if (isTopHalf) {
                Log.d(TAG, "Detected COMMENTS_RIGHT swipe (startY=" + startY + ", effHeight=" + effHeight + ")");
                return EdgeSwipeType.COMMENTS_RIGHT;
            } else {
                Log.d(TAG, "Detected PLAYERS_RIGHT swipe (startY=" + startY + ", effHeight=" + effHeight + ")");
                return EdgeSwipeType.PLAYERS_RIGHT;
            }
        }
        
        return EdgeSwipeType.NONE;
    }
    
    /**
     * Обрабатывает edge swipe и вызывает соответствующий callback
     * УСТАРЕВШИЙ МЕТОД - теперь используется drag-to-open
     */
    private void handleEdgeSwipe(EdgeSwipeType swipeType) {
        if (gestureCallback == null) {
            Log.w(TAG, "GestureCallback is null, cannot handle edge swipe");
            return;
        }
        
        switch (swipeType) {
            case COMMENTS_RIGHT:
                Log.d(TAG, "Opening comments panel");
                gestureCallback.onCommentsSwipeFromRight();
                break;
            case PLAYERS_RIGHT:
                Log.d(TAG, "Opening players panel");
                gestureCallback.onPlayersSwipeFromRight();
                break;
            case NONE:
                // Ничего не делаем
                break;
        }
    }
    
    /**
     * Вычисляет прогресс drag для edge swipe (0.0 - 1.0)
     * Только для горизонтальных свайпов (комментарии и озвучки)
     */
    private float calculateEdgeDragProgress(EdgeSwipeType swipeType, float currentX, float currentY) {
        float progress = 0f;
        
        switch (swipeType) {
            case COMMENTS_RIGHT:
            case PLAYERS_RIGHT:
                // Свайп справа налево - чем левее сдвинулся палец, тем больше прогресс
                float leftDistance = edgeDragStartX - currentX;
                // Рассчитываем относительно реальной ширины боковой панели (360dp), чтобы панель следовала 1:1 за пальцем
                float density = context.getResources().getDisplayMetrics().density;
                float panelWidthPx = 360f * density;
                progress = Math.max(0f, Math.min(1f, leftDistance / panelWidthPx));
                break;
                
            case NONE:
                progress = 0f;
                break;
        }
        
        return progress;
    }
    
    /**
     * Обновляет прогресс drag и вызывает соответствующий callback
     * Только для горизонтальных свайпов (комментарии и озвучки)
     */
    private void updateEdgeDragProgress(EdgeSwipeType swipeType, float progress) {
        if (gestureCallback == null) return;
        
        switch (swipeType) {
            case COMMENTS_RIGHT:
                gestureCallback.onCommentsDragProgress(progress);
                break;
                
            case PLAYERS_RIGHT:
                gestureCallback.onPlayersDragProgress(progress);
                break;
                
            case NONE:
                // Ничего не делаем
                break;
        }
    }
    
    /**
     * Активирует режим ускорения с начальной скоростью 2x
     */
    private void activateHoldToSpeed() {
        isHoldToSpeed = true;
        isHorizontalGestureActive = true; // Блокируем вертикальные жесты
        currentSpeedMultiplier = 2.0f;
        
        // Set playback speed to 2x
        if (player != null) {
            player.setPlaybackSpeed(currentSpeedMultiplier);
        }
        
        // Show speed toast
        if (holdSpeedToast != null) {
            holdSpeedToast.setVisibility(View.VISIBLE);
            holdSpeedToast.setAlpha(0f);
            holdSpeedToast.animate().alpha(1f).setDuration(120).start();
        }
        
        // Обновляем текст в toast
        updateSpeedToast();
        
        // Notify callback about speed change
        if (gestureCallback != null) {
            gestureCallback.onSpeedChange(currentSpeedMultiplier);
        }
        
        Log.d(TAG, "Hold to speed activated with speed: " + currentSpeedMultiplier + " - blocking vertical gestures");
    }
    
    /**
     * Сбрасывает режим ускорения
     */
    private void resetHoldToSpeed() {
        isHoldToSpeed = false;
        isSpeedAdjustmentMode = false;
        currentSpeedMultiplier = 1.0f;
        
        // Reset to normal speed
        if (player != null) {
            player.setPlaybackSpeed(1.0f);
        }
        
        // Hide speed toast
        if (holdSpeedToast != null) {
            holdSpeedToast.animate().alpha(0f).setDuration(120)
                    .withEndAction(() -> holdSpeedToast.setVisibility(View.GONE))
                    .start();
        }
        
        // Notify callback about speed reset
        if (gestureCallback != null) {
            gestureCallback.onSpeedChange(1.0f);
        }
        
        if (gestureCallback != null && player != null) {
            gestureCallback.updatePlayLoadingIndicator(player.getPlaybackState());
        }
        
        Log.d(TAG, "Hold to speed reset to 1.0x");
    }
    
    /**
     * Обрабатывает регулировку скорости движением влево/вправо
     */
    private void handleSpeedAdjustment(float currentX) {
        if (!isSpeedAdjustmentMode) {
            // Активируем режим регулировки скорости при первом движении
            isSpeedAdjustmentMode = true;
            speedAdjustmentStartX = currentX;
            Log.d(TAG, "Speed adjustment mode activated");
            return;
        }
        
        // Вычисляем изменение позиции
        float deltaX = currentX - speedAdjustmentStartX;
        
        // Вычисляем новую скорость на основе движения
        // Движение вправо = увеличение скорости, влево = уменьшение
        float speedChange = deltaX * speedAdjustmentSensitivity / 100f; // Чувствительность
        float newSpeed = Math.max(0.25f, Math.min(4.0f, 2.0f + speedChange)); // Ограничиваем от 0.25x до 4x
        
        // Обновляем скорость только если она изменилась значительно
        if (Math.abs(newSpeed - currentSpeedMultiplier) > 0.1f) {
            currentSpeedMultiplier = newSpeed;
            
            // Применяем новую скорость
            if (player != null) {
                player.setPlaybackSpeed(currentSpeedMultiplier);
            }
            
            // Обновляем toast с новой скоростью
            updateSpeedToast();
            
            // Notify callback about speed change
            if (gestureCallback != null) {
                gestureCallback.onSpeedChange(currentSpeedMultiplier);
            }
            
            Log.d(TAG, "Speed adjusted to: " + currentSpeedMultiplier + "x (deltaX: " + deltaX + ")");
        }
    }
    
    /**
     * Обновляет отображение скорости в toast
     */
    private void updateSpeedToast() {
        if (holdSpeedToast != null && holdSpeedToast instanceof android.widget.TextView) {
            @SuppressLint("DefaultLocale")
            String speedText = String.format(Locale.US, "%.1f", currentSpeedMultiplier) + "x";

            ((android.widget.TextView) holdSpeedToast).setText(speedText);
        }
    }
    
    /**
     * Показывает индикатор длинной перемотки с анимацией
     * @param isForward true - вперед, false - назад
     */
    private void showSkipIndicator(boolean isForward) {
        View indicator = isForward ? skipIndicatorRight : skipIndicatorLeft;
        
        if (indicator == null) {
            Log.w(TAG, "Skip indicator is null");
            return;
        }

        if (gestureCallback != null) {
            gestureCallback.onDoubleTapSkip(isForward, DOUBLE_TAP_SKIP_SECONDS);
        }

        indicator.setVisibility(View.VISIBLE);
        indicator.setAlpha(0f);
        indicator.setScaleX(0.8f);
        indicator.setScaleY(0.8f);

        indicator.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .withEndAction(() -> {
                    indicator.postDelayed(() -> {
                        indicator.animate()
                                .alpha(0f)
                                .scaleX(0.8f)
                                .scaleY(0.8f)
                                .setDuration(200)
                                .withEndAction(() -> indicator.setVisibility(View.GONE))
                                .start();
                    }, 500);
                })
                .start();
        
        Log.d(TAG, "Skip indicator shown: " + (isForward ? "forward" : "backward"));
    }
    
    /**
     * Обновляет текст в skip индикаторах
     * @param skipDurationSeconds Длительность перемотки в секундах
     */
    public void updateSkipDurationText(int skipDurationSeconds) {
        if (skipTextLeft != null) {
            skipTextLeft.setText("-" + skipDurationSeconds + " сек");
        }
        if (skipTextRight != null) {
            skipTextRight.setText("+" + skipDurationSeconds + " сек");
        }
        Log.d(TAG, "Skip duration text updated: " + skipDurationSeconds + " seconds");
    }
    
    // handleSeekSwipe logic is now integrated into setupSwipeSeek
    
    // setupHoldToSpeed logic is now integrated into setupCombinedGestures
    
    /**
     * Обновление ссылки на плеер
     * @param player Новый экземпляр плеера
     */
    public void updatePlayer(Player player) {
        // Принудительно сбрасываем hold-to-speed при смене плеера
        if (isHoldToSpeed && this.player != null) {
            Log.d(TAG, "Resetting hold-to-speed on player update");
            resetHoldToSpeed();
        }
        
        this.player = player;
        Log.d(TAG, "Player updated");
    }
    
    /**
     * Обновление ссылки на PlayerView
     * @param playerView Новый экземпляр PlayerView
     */
    public void updatePlayerView(PlayerView playerView) {
        // Принудительно сбрасываем hold-to-speed при смене PlayerView
        if (isHoldToSpeed && player != null) {
            Log.d(TAG, "Resetting hold-to-speed on PlayerView update");
            resetHoldToSpeed();
        }
        
        this.playerView = playerView;
        setupCombinedGestures(); // Re-setup gestures with new PlayerView
        Log.d(TAG, "PlayerView updated and gestures re-setup");
    }
    
    /**
     * Активирует cooldown для восстановления кликабельности контролов
     */
    private void activateGestureCooldown() {
        isGestureCooldown = true;
        Log.d(TAG, "Gesture cooldown activated");
        
        // Отключаем cooldown через 300мс
        if (playerView != null) {
            playerView.postDelayed(() -> {
                isGestureCooldown = false;
                Log.d(TAG, "Gesture cooldown deactivated");
            }, 300);
        }
    }
    
    /**
     * Проверка, выполняется ли свайп для перемотки
     * @return true если выполняется свайп
     */
    public boolean isSwipingSeek() {
        return isSwipingSeek;
    }
    
    /**
     * Проверка, активно ли ускорение удержанием
     * @return true если активно ускорение
     */
    public boolean isHoldToSpeed() {
        return isHoldToSpeed;
    }
    
    /**
     * Проверка, активен ли любой горизонтальный жест
     * @return true если активен горизонтальный жест (seek, edge swipe, hold-to-speed)
     */
    public boolean isHorizontalGestureActive() {
        return isHorizontalGestureActive || isSwipingSeek || isHoldToSpeed || isEdgeSwipe;
    }
    
    /**
     * Принудительная остановка всех жестов
     */
    public void stopAllGestures() {
        Log.d(TAG, "Stopping all gestures");
        
        isSwipingSeek = false;
        isEdgeSwipe = false;
        isHorizontalGestureActive = false; // Разблокируем вертикальные жесты
        isGestureCooldown = false; // Сбрасываем cooldown
        
        // Принудительно отменяем таймер
        if (holdToSpeedRunnable != null && playerView != null) {
            playerView.removeCallbacks(holdToSpeedRunnable);
            holdToSpeedRunnable = null;
        }
        
        // Принудительно скрываем seek preview
        if (seekPreviewText != null) {
            seekPreviewText.setVisibility(View.GONE);
        }
        
        // Принудительно сбрасываем hold-to-speed
        if (isHoldToSpeed && player != null) {
            Log.d(TAG, "Force stopping hold-to-speed");
            resetHoldToSpeed();
        }
        
        // Re-setup gestures to restore normal functionality
        setupCombinedGestures();
    }
    
    /**
     * Скрытие всех UI элементов жестов (для PiP режима)
     */
    public void hideAllGesturesUI() {
        // Принудительно сбрасываем hold-to-speed
        if (isHoldToSpeed && player != null) {
            Log.d(TAG, "Force resetting hold-to-speed on UI hide");
            resetHoldToSpeed();
        }
        
        if (holdSpeedToast != null) {
            holdSpeedToast.setVisibility(View.GONE);
        }
        
        // Принудительно скрываем seek preview
        if (seekPreviewText != null) {
            seekPreviewText.setVisibility(View.GONE);
        }
        
        // Скрываем skip индикаторы
        if (skipIndicatorLeft != null) {
            skipIndicatorLeft.setVisibility(View.GONE);
        }
        if (skipIndicatorRight != null) {
            skipIndicatorRight.setVisibility(View.GONE);
        }
        
        // Stop any active gestures
        stopAllGestures();
        
        Log.d(TAG, "All gestures UI hidden");
    }
    
    /**
     * Показ всех UI элементов жестов (выход из PiP режима)
     */
    public void showAllGesturesUI() {
        // Gestures are automatically restored when PlayerView is available
        setupCombinedGestures();
        
        Log.d(TAG, "All gestures UI shown");
    }
    
    // Getters and Setters
    public void setGestureCallback(GestureCallback callback) {
        this.gestureCallback = callback;
    }
    
    private boolean isPortraitMode = false;

    public void setVerticalGesturesManager(VerticalGesturesManager manager) {
        this.verticalGesturesManager = manager;
    }
    
    public void setPortraitMode(boolean isPortrait) {
        this.isPortraitMode = isPortrait;
        if (verticalGesturesManager != null) {
            verticalGesturesManager.setPortraitMode(isPortrait);
        }
        updateIndicatorSizesForPortrait(isPortrait);
    }

    private void updateIndicatorSizesForPortrait(boolean isPortrait) {
        if (context == null) return;
        float density = context.getResources().getDisplayMetrics().density;

        if (skipIndicatorLeft != null) {
            android.view.ViewGroup.LayoutParams lp = skipIndicatorLeft.getLayoutParams();
            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) lp;
                params.leftMargin = (int) ((isPortrait ? 32 : 130) * density);
                skipIndicatorLeft.setLayoutParams(params);
            }
            View iconLeft = skipIndicatorLeft.findViewById(com.example.animelib.R.id.skipIconLeft);
            if (iconLeft != null) {
                android.view.ViewGroup.LayoutParams iconParams = iconLeft.getLayoutParams();
                int iconSize = (int) ((isPortrait ? 38 : 64) * density);
                iconParams.width = iconSize;
                iconParams.height = iconSize;
                iconLeft.setLayoutParams(iconParams);
            }
            if (skipTextLeft != null) {
                skipTextLeft.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isPortrait ? 11f : 14f);
                int padH = (int) ((isPortrait ? 8 : 12) * density);
                int padV = (int) ((isPortrait ? 3 : 6) * density);
                skipTextLeft.setPadding(padH, padV, padH, padV);
            }
        }

        if (skipIndicatorRight != null) {
            android.view.ViewGroup.LayoutParams lp = skipIndicatorRight.getLayoutParams();
            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) lp;
                params.rightMargin = (int) ((isPortrait ? 32 : 130) * density);
                skipIndicatorRight.setLayoutParams(params);
            }
            View iconRight = skipIndicatorRight.findViewById(com.example.animelib.R.id.skipIconRight);
            if (iconRight != null) {
                android.view.ViewGroup.LayoutParams iconParams = iconRight.getLayoutParams();
                int iconSize = (int) ((isPortrait ? 38 : 64) * density);
                iconParams.width = iconSize;
                iconParams.height = iconSize;
                iconRight.setLayoutParams(iconParams);
            }
            if (skipTextRight != null) {
                skipTextRight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isPortrait ? 11f : 14f);
                int padH = (int) ((isPortrait ? 8 : 12) * density);
                int padV = (int) ((isPortrait ? 3 : 6) * density);
                skipTextRight.setPadding(padH, padV, padH, padV);
            }
        }

        if (seekPreviewText != null) {
            android.view.ViewGroup.LayoutParams lp = seekPreviewText.getLayoutParams();
            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) lp;
                params.topMargin = (int) ((isPortrait ? 20 : 80) * density);
                seekPreviewText.setLayoutParams(params);
            }
        }

        if (holdSpeedToast != null) {
            android.view.ViewGroup.LayoutParams lp = holdSpeedToast.getLayoutParams();
            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) lp;
                params.topMargin = (int) ((isPortrait ? 20 : 80) * density);
                holdSpeedToast.setLayoutParams(params);
            }
        }
    }
    
    /**
     * Отменяет таймер hold-to-speed (вызывается из VerticalGesturesManager)
     */
    public void cancelHoldToSpeedTimer() {
        if (holdToSpeedRunnable != null && playerView != null) {
            playerView.removeCallbacks(holdToSpeedRunnable);
            holdToSpeedRunnable = null;
            Log.d(TAG, "Hold-to-speed timer cancelled by VerticalGesturesManager");
        }
    }
    
    public void setHoldSpeedToast(View holdSpeedToast) {
        this.holdSpeedToast = holdSpeedToast;
    }
    
    public void setSeekPreviewText(View seekPreviewText) {
        this.seekPreviewText = seekPreviewText;
    }
    
    /**
     * Очистка ресурсов
     */
    public void cleanup() {
        stopAllGestures();
        
        if (playerView != null) {
            playerView.setOnTouchListener(null);
            playerView.setOnLongClickListener(null);
            if (holdToSpeedRunnable != null) {
                playerView.removeCallbacks(holdToSpeedRunnable);
                holdToSpeedRunnable = null;
            }
        }
        
        playerView = null;
        player = null;
        holdSpeedToast = null;
        skipIndicatorLeft = null;
        skipIndicatorRight = null;
        skipTextLeft = null;
        skipTextRight = null;
        doubleTapDetector = null;
        gestureCallback = null;
        
        Log.d(TAG, "GesturesManager cleaned up");
    }
}
