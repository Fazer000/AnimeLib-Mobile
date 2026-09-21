package com.example.animelib.managers;

import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.adapters.HorizontalRelatedTitlesAdapter;
import com.example.animelib.models.RelatedTitlesResponse;
import com.google.android.material.chip.Chip;

import java.util.List;

/**
 * Менеджер для управления связанными тайтлами в контроллере плеера
 */
public class RelatedTitlesManager {
    private static final String TAG = "RelatedTitlesManager";
    
    // UI компоненты
    private FrameLayout relatedTitlesOverlay;
    private View relatedTitlesDimOverlay;
    private RecyclerView relatedTitlesRecyclerView;
    private TextView relatedTitlesHeader;
    private LinearLayout animeInfoContainer;
    private ImageView animeCover;
    private TextView animeTitle;
    private TextView animeEngTitle;
    private Chip animeTypeChip;
    private Chip animeStatusChip;
    private Chip animeYearChip;
    private Chip animeAgeChip;
    private TextView animeRating;
    private TextView animeVotes;
    private TextView animeEpisodes;
    private HorizontalRelatedTitlesAdapter relatedTitlesAdapter;
    
    // Callback для управления интерфейсом плеера
    private OnPlayerInterfaceControlListener playerInterfaceControlListener;
    
    // Состояние
    private boolean isRelatedTitlesVisible = false;
    private float overlayHeightPx = 0f; // Высота overlay для анимации
    
    /**
     * Интерфейс для управления видимостью интерфейса плеера
     */
    public interface OnPlayerInterfaceControlListener {
        void onHidePlayerInterface();
        void onShowPlayerInterface();
        void onPlayerInterfaceAlpha(float alpha); // Для плавного скрытия
    }
    
    public interface RelatedTitlesVisibilityCallback {
        void onRelatedTitlesVisibilityChanged(boolean isVisible);
    }
    
    private RelatedTitlesVisibilityCallback visibilityCallback;
    
    public void setVisibilityCallback(RelatedTitlesVisibilityCallback callback) {
        this.visibilityCallback = callback;
    }
    
    /**
     * Инициализация менеджера
     */
    public void initialize(FrameLayout relatedTitlesOverlay, View relatedTitlesDimOverlay, 
                          RecyclerView relatedTitlesRecyclerView, TextView relatedTitlesHeader,
                          LinearLayout relatedAnimeInfoContainer, ImageView relatedAnimeCover,
                          TextView relatedAnimeTitle, TextView relatedAnimeEngTitle,
                          Chip relatedAnimeTypeChip, Chip relatedAnimeStatusChip,
                          Chip relatedAnimeYearChip, Chip relatedAnimeAgeChip,
                          TextView relatedAnimeRating, TextView relatedAnimeVotes, 
                          TextView relatedAnimeEpisodes) {
        this.relatedTitlesOverlay = relatedTitlesOverlay;
        this.relatedTitlesDimOverlay = relatedTitlesDimOverlay;
        this.relatedTitlesRecyclerView = relatedTitlesRecyclerView;
        this.relatedTitlesHeader = relatedTitlesHeader;
        this.animeInfoContainer = relatedAnimeInfoContainer;
        this.animeCover = relatedAnimeCover;
        this.animeTitle = relatedAnimeTitle;
        this.animeEngTitle = relatedAnimeEngTitle;
        this.animeTypeChip = relatedAnimeTypeChip;
        this.animeStatusChip = relatedAnimeStatusChip;
        this.animeYearChip = relatedAnimeYearChip;
        this.animeAgeChip = relatedAnimeAgeChip;
        this.animeRating = relatedAnimeRating;
        this.animeVotes = relatedAnimeVotes;
        this.animeEpisodes = relatedAnimeEpisodes;
        
        Log.d(TAG, "RelatedTitlesManager initialized");
    }
    
    /**
     * Устанавливает адаптер для связанных тайтлов
     */
    public void setAdapter(HorizontalRelatedTitlesAdapter adapter) {
        this.relatedTitlesAdapter = adapter;
        if (relatedTitlesRecyclerView != null) {
            relatedTitlesRecyclerView.setAdapter(adapter);
        }
    }
    
    /**
     * Устанавливает listener для управления интерфейсом плеера
     */
    public void setPlayerInterfaceControlListener(OnPlayerInterfaceControlListener listener) {
        this.playerInterfaceControlListener = listener;
    }
    
    /**
     * Устанавливает информацию об аниме
     */
    public void setAnimeInfo(String coverUrl, String title, String engTitle, 
                            String type, String status, String year, String ageRating,
                            String rating, String votes, String episodes) {
        // Загружаем обложку
        if (animeCover != null && coverUrl != null && !coverUrl.isEmpty()) {
            com.example.animelib.util.ImageLoader.getInstance()
                .loadInto(animeCover, coverUrl, 0);
        }
        
        // Название
        if (animeTitle != null) {
            animeTitle.setText(title != null ? title : "");
        }
        
        // Английское название
        if (animeEngTitle != null) {
            if (engTitle != null && !engTitle.isEmpty()) {
                animeEngTitle.setText(engTitle);
                animeEngTitle.setVisibility(View.VISIBLE);
            } else {
                animeEngTitle.setVisibility(View.GONE);
            }
        }
        
        // Тип (ТВ Сериал, Фильм и т.д.)
        if (animeTypeChip != null) {
            if (type != null && !type.isEmpty()) {
                animeTypeChip.setText(type);
                animeTypeChip.setVisibility(View.VISIBLE);
            } else {
                animeTypeChip.setVisibility(View.GONE);
            }
        }
        
        // Статус (Онгоинг, Вышло и т.д.)
        if (animeStatusChip != null) {
            if (status != null && !status.isEmpty()) {
                animeStatusChip.setText(status);
                animeStatusChip.setVisibility(View.VISIBLE);
            } else {
                animeStatusChip.setVisibility(View.GONE);
        }
        }
        
        // Год выхода
        if (animeYearChip != null) {
            if (year != null && !year.isEmpty()) {
                animeYearChip.setText(year);
                animeYearChip.setVisibility(View.VISIBLE);
            } else {
                animeYearChip.setVisibility(View.GONE);
            }
        }
        
        // Возрастной рейтинг
        if (animeAgeChip != null) {
            if (ageRating != null && !ageRating.isEmpty()) {
                animeAgeChip.setText(ageRating);
                animeAgeChip.setVisibility(View.VISIBLE);
            } else {
                animeAgeChip.setVisibility(View.GONE);
            }
        }
        
        // Рейтинг
        if (animeRating != null) {
            animeRating.setText(rating != null ? rating : "");
        }
        
        // Голоса
        if (animeVotes != null) {
            animeVotes.setText(votes != null ? votes : "");
        }
        
        // Количество эпизодов
        if (animeEpisodes != null) {
            if (episodes != null && !episodes.isEmpty()) {
                animeEpisodes.setText(episodes);
                animeEpisodes.setVisibility(View.VISIBLE);
            } else {
                animeEpisodes.setVisibility(View.GONE);
            }
        }
    }
    
    
    /**
     * Показывает связанные тайтлы с 🔥 ПРЕМИУМ анимацией
     */
    private List<RelatedTitlesResponse.RelatedTitle> pendingRelatedTitles;

    public void showRelatedTitles() {
        if (relatedTitlesOverlay == null || relatedTitlesRecyclerView == null) return;
        
        // Если уже видима, не перезапускаем анимацию
        if (isRelatedTitlesVisible) {
            Log.d(TAG, "Related titles already visible, skipping animation");
            return;
        }

        if (pendingRelatedTitles != null && relatedTitlesAdapter != null) {
            relatedTitlesAdapter.updateData(pendingRelatedTitles);
        }
        
        Log.d(TAG, "Showing related titles with premium animation 🔥");
        
        // Получаем высоту экрана для full-screen панели
        if (overlayHeightPx == 0f) {
            overlayHeightPx = relatedTitlesOverlay.getResources().getDisplayMetrics().heightPixels;
            Log.d(TAG, "Screen height: " + overlayHeightPx + "px");
        }
        
        // Останавливаем любые текущие анимации
        if (relatedTitlesDimOverlay != null) {
            relatedTitlesDimOverlay.animate().cancel();
        }
        relatedTitlesOverlay.animate().cancel();
        if (relatedTitlesHeader != null) {
            relatedTitlesHeader.animate().cancel();
        }
        if (animeInfoContainer != null) {
            animeInfoContainer.animate().cancel();
        }
        if (relatedTitlesRecyclerView != null) {
            relatedTitlesRecyclerView.animate().cancel();
        }
        
        // Получаем текущую позицию overlay (может быть частично открыт после drag)
        float currentTranslationY = relatedTitlesOverlay.getTranslationY();
        float currentScale = relatedTitlesOverlay.getScaleX();
        
        // Показываем затемняющий overlay (анимируем от текущей прозрачности)
        if (relatedTitlesDimOverlay != null) {
            relatedTitlesDimOverlay.setVisibility(View.VISIBLE);
            relatedTitlesDimOverlay.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                    .start();
        }
        
        // Показываем overlay и контент
        relatedTitlesOverlay.setVisibility(View.VISIBLE);
        relatedTitlesOverlay.setAlpha(1f);
        if (animeInfoContainer != null) {
            animeInfoContainer.setVisibility(View.VISIBLE);
        }
        if (relatedTitlesRecyclerView != null) {
            relatedTitlesRecyclerView.setAlpha(0f); // Начинаем с невидимого
        }
        
        // 🎬 ГЛАВНАЯ АНИМАЦИЯ: Scale + TranslationY с overshoot
        relatedTitlesOverlay.setPivotX(relatedTitlesOverlay.getWidth() / 2f);
        relatedTitlesOverlay.setPivotY(0f);
        relatedTitlesOverlay.animate()
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f))
                .setUpdateListener(animation -> {
                    // Вычисляем текущий progress анимации
                    float currentTranslation = relatedTitlesOverlay.getTranslationY();
                    float currentProgress = 1f + (currentTranslation / overlayHeightPx);
                    currentProgress = Math.max(0f, Math.min(1f, currentProgress));
                    
                    // Плавно скрываем интерфейс плеера
                    if (playerInterfaceControlListener != null) {
                        float interfaceAlpha = 1f - currentProgress;
                        playerInterfaceControlListener.onPlayerInterfaceAlpha(interfaceAlpha);
                    }
                    
                    // 🎬 Anime info с параллакс эффектом (0.2 → 0.8)
                    if (animeInfoContainer != null) {
                        if (currentProgress > 0.2f && currentProgress < 0.8f) {
                            float localProgress = (currentProgress - 0.2f) / 0.6f;
                            float easedProgress = 1f - (float)Math.pow(1f - localProgress, 2);
                            
                            animeInfoContainer.setAlpha(easedProgress);
                            animeInfoContainer.setTranslationY(40f * (1f - easedProgress));
                            
                            float infoScale = 0.95f + (0.05f * easedProgress);
                            animeInfoContainer.setScaleX(infoScale);
                            animeInfoContainer.setScaleY(infoScale);
                            animeInfoContainer.setPivotX(animeInfoContainer.getWidth() / 2f);
                            animeInfoContainer.setPivotY(animeInfoContainer.getHeight() / 2f);
                            
                        } else if (currentProgress >= 0.8f) {
                            animeInfoContainer.setAlpha(1f);
                            animeInfoContainer.setTranslationY(0f);
                            animeInfoContainer.setScaleX(1f);
                            animeInfoContainer.setScaleY(1f);
                        }
                    }
                    
                    // 🎬 Header с bounce эффектом (0.5 → 1.0)
                    if (relatedTitlesHeader != null && currentProgress > 0.5f) {
                        float localProgress = (currentProgress - 0.5f) / 0.5f;
                        localProgress = Math.min(1f, localProgress);
                        
                        // Overshoot для bounce
                        float tension = 1.5f;
                        float overshootProgress;
                        if (localProgress < 1f) {
                            overshootProgress = localProgress * localProgress * ((tension + 1f) * localProgress - tension);
                        } else {
                            float adjusted = localProgress - 1f;
                            overshootProgress = adjusted * adjusted * ((tension + 1f) * adjusted + tension) + 1f;
                        }
                        
                        float easedAlpha = 1f - (float)Math.pow(1f - localProgress, 2);
                        relatedTitlesHeader.setAlpha(Math.max(0f, Math.min(1f, easedAlpha)));
                        relatedTitlesHeader.setTranslationY(-30f * (1f - overshootProgress));
                        relatedTitlesHeader.setRotation(-2f * (1f - localProgress));
                    }
                    
                    // 🎬 RecyclerView fade-in (0.6 → 1.0)
                    if (relatedTitlesRecyclerView != null && currentProgress > 0.6f) {
                        float localProgress = (currentProgress - 0.6f) / 0.4f;
                        localProgress = Math.min(1f, localProgress);
                        float recyclerAlpha = 1f - (float)Math.pow(1f - localProgress, 2);
                        relatedTitlesRecyclerView.setAlpha(recyclerAlpha);
                    }
                })
                .withEndAction(() -> {
                    // Гарантируем финальное состояние
                    relatedTitlesOverlay.setScaleX(1f);
                    relatedTitlesOverlay.setScaleY(1f);
                    if (animeInfoContainer != null) {
                        animeInfoContainer.setAlpha(1f);
                        animeInfoContainer.setTranslationY(0f);
                        animeInfoContainer.setScaleX(1f);
                        animeInfoContainer.setScaleY(1f);
                    }
                    if (relatedTitlesHeader != null) {
                        relatedTitlesHeader.setAlpha(1f);
                        relatedTitlesHeader.setTranslationY(0f);
                        relatedTitlesHeader.setRotation(0f);
                    }
                    if (relatedTitlesRecyclerView != null) {
                        relatedTitlesRecyclerView.setAlpha(1f);
                    }
                    if (visibilityCallback != null) {
                        visibilityCallback.onRelatedTitlesVisibilityChanged(true);
                    }
                })
                .start();
        
        isRelatedTitlesVisible = true;
    }
    
    /**
     * Скрывает связанные тайтлы с 🔥 ПРЕМИУМ анимацией (в обратном порядке)
     */
    public void hideRelatedTitles() {
        if (relatedTitlesOverlay == null) return;
        
        // Проверяем видимость overlay вместо флага, чтобы работать и с частично открытой панелью
        if (relatedTitlesOverlay.getVisibility() != View.VISIBLE) return;
        
        Log.d(TAG, "Hiding related titles with premium animation 🔥");
        
        // Получаем высоту экрана если еще не вычислена
        if (overlayHeightPx == 0f) {
            overlayHeightPx = relatedTitlesOverlay.getResources().getDisplayMetrics().heightPixels;
        }
        
        // Останавливаем текущие анимации
        relatedTitlesOverlay.animate().cancel();
        if (relatedTitlesDimOverlay != null) {
            relatedTitlesDimOverlay.animate().cancel();
        }
        if (relatedTitlesHeader != null) {
            relatedTitlesHeader.animate().cancel();
        }
        if (animeInfoContainer != null) {
            animeInfoContainer.animate().cancel();
        }
        if (relatedTitlesRecyclerView != null) {
            relatedTitlesRecyclerView.animate().cancel();
        }
        
        // БЫСТРО скрываем RecyclerView и Header (первыми исчезают)
        if (relatedTitlesRecyclerView != null) {
            relatedTitlesRecyclerView.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .start();
        }
        if (relatedTitlesHeader != null) {
            relatedTitlesHeader.animate()
                    .alpha(0f)
                    .translationY(-20f) // Уходит вверх
                    .rotation(2f) // Легкая ротация в обратную сторону
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .start();
        }
        
        // Anime info исчезает чуть медленнее с параллакс эффектом вниз
        if (animeInfoContainer != null) {
            animeInfoContainer.animate()
                    .alpha(0f)
                    .translationY(30f) // Уходит вниз
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(250)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                    .start();
        }
        
        // Затемнение исчезает быстро
        if (relatedTitlesDimOverlay != null) {
            relatedTitlesDimOverlay.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        relatedTitlesDimOverlay.setVisibility(View.GONE);
                    })
                    .start();
        }
        
        // 🎬 ГЛАВНАЯ АНИМАЦИЯ: Overlay уезжает вверх с легким scale (становится чуть меньше)
        relatedTitlesOverlay.setPivotX(relatedTitlesOverlay.getWidth() / 2f);
        relatedTitlesOverlay.setPivotY(0f);
        relatedTitlesOverlay.animate()
                .translationY(-overlayHeightPx)
                .scaleX(0.92f) // Уменьшается при закрытии
                .scaleY(0.92f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                .setUpdateListener(animation -> {
                    // Вычисляем прогресс закрытия
                    float currentTranslation = relatedTitlesOverlay.getTranslationY();
                    float closeProgress = Math.abs(currentTranslation / overlayHeightPx);
                    closeProgress = Math.max(0f, Math.min(1f, closeProgress));
                    
                    // Показываем интерфейс плеера по мере закрытия
                    if (playerInterfaceControlListener != null) {
                        float interfaceAlpha = closeProgress;
                        playerInterfaceControlListener.onPlayerInterfaceAlpha(interfaceAlpha);
                    }
                })
                .withEndAction(() -> {
                    // Скрываем overlay
                    relatedTitlesOverlay.setVisibility(View.GONE);
                    
                    // Сбрасываем все трансформации к начальному состоянию
                    relatedTitlesOverlay.setScaleX(1f);
                    relatedTitlesOverlay.setScaleY(1f);
                    relatedTitlesOverlay.setTranslationY(0f);
                    
                    if (animeInfoContainer != null) {
                        animeInfoContainer.setScaleX(1f);
                        animeInfoContainer.setScaleY(1f);
                        animeInfoContainer.setTranslationY(0f);
                        animeInfoContainer.setAlpha(1f);
                    }
                    if (relatedTitlesHeader != null) {
                        relatedTitlesHeader.setTranslationY(0f);
                        relatedTitlesHeader.setRotation(0f);
                        relatedTitlesHeader.setAlpha(1f);
                    }
                    if (relatedTitlesRecyclerView != null) {
                        relatedTitlesRecyclerView.setAlpha(1f);
                    }
                    
                    // Показываем интерфейс плеера после полного закрытия
                    if (playerInterfaceControlListener != null) {
                        playerInterfaceControlListener.onShowPlayerInterface();
                    }
                    if (visibilityCallback != null) {
                        visibilityCallback.onRelatedTitlesVisibilityChanged(false);
                    }
                })
                .start();
        
        isRelatedTitlesVisible = false;
    }
    
    /**
     * Устанавливает прогресс drag (0.0 = закрыто, 1.0 = открыто)
     * Используется для плавного вытягивания панели во время жеста
     * 
     * 🔥 PREMIUM АНИМАЦИЯ:
     * - Scale эффект для основной панели (0.92 → 1.0)
     * - Параллакс для anime info (двигается медленнее + fade)
     * - Упругий эффект для header (bounce + fade + rotate)
     * - Прогрессивное затемнение с ease-out кривой
     */
    public void setDragProgress(float progress) {
        if (relatedTitlesOverlay == null) return;
        
        // НЕ ограничиваем progress - позволяем панели двигаться дальше за пределы экрана
        // Это предотвращает "застывание" при продолжении drag
        progress = Math.max(0f, progress); // Только минимум 0
        
        // Получаем высоту экрана если еще не вычислена
        if (overlayHeightPx == 0f) {
            overlayHeightPx = relatedTitlesOverlay.getResources().getDisplayMetrics().heightPixels;
        }
        
        // Останавливаем текущие анимации
        relatedTitlesOverlay.animate().cancel();
        if (relatedTitlesDimOverlay != null) {
            relatedTitlesDimOverlay.animate().cancel();
        }
        if (relatedTitlesHeader != null) {
            relatedTitlesHeader.animate().cancel();
        }
        if (animeInfoContainer != null) {
            animeInfoContainer.animate().cancel();
        }
        
        // Показываем overlay если progress > 0
        if (progress > 0f) {
            if (pendingRelatedTitles != null && relatedTitlesAdapter != null && relatedTitlesAdapter.getItemCount() == 0) {
                relatedTitlesAdapter.updateData(pendingRelatedTitles);
            }
            relatedTitlesOverlay.setVisibility(View.VISIBLE);
            if (relatedTitlesDimOverlay != null) {
                relatedTitlesDimOverlay.setVisibility(View.VISIBLE);
            }
            if (animeInfoContainer != null) {
                animeInfoContainer.setVisibility(View.VISIBLE);
            }
        }
        
        // 🎬 ЭФФЕКТ 1: Scale + TranslationY для основной панели
        // Панель начинается чуть меньше (92%) и растет до 100%
        // progress = 0 (закрыто): translationY = -overlayHeightPx, scale = 0.92
        // progress = 1 (открыто): translationY = 0, scale = 1.0
        // progress > 1 (overscroll): translationY > 0, scale = 1.0
        float translationY = -overlayHeightPx * (1f - progress);
        
        // Scale с ease-out эффектом: медленнее в начале, быстрее в конце
        float scaleProgress = Math.min(1f, progress); // Ограничиваем до 1.0 для scale
        float easeOutProgress = 1f - (float)Math.pow(1f - scaleProgress, 3); // cubic ease-out
        float scale = 0.92f + (0.08f * easeOutProgress); // 0.92 → 1.0
        
        relatedTitlesOverlay.setTranslationY(translationY);
        relatedTitlesOverlay.setScaleX(scale);
        relatedTitlesOverlay.setScaleY(scale);
        relatedTitlesOverlay.setPivotX(relatedTitlesOverlay.getWidth() / 2f);
        relatedTitlesOverlay.setPivotY(0f); // Масштабируем от верхнего края
        relatedTitlesOverlay.setAlpha(1f);
        
        // 🎬 ЭФФЕКТ 2: Прогрессивное затемнение с ease-out кривой
        if (relatedTitlesDimOverlay != null) {
            float dimProgress = Math.min(1f, progress);
            // Ease-out quad для плавного затемнения
            float easedDimProgress = 1f - (float)Math.pow(1f - dimProgress, 2);
            float dimAlpha = easedDimProgress;
            relatedTitlesDimOverlay.setAlpha(dimAlpha);
        }
        
        // Плавно скрываем интерфейс плеера (от 1.0 к 0.0, ограничено до 0.0)
        if (playerInterfaceControlListener != null) {
            float interfaceAlpha = Math.max(0f, 1f - progress);
            playerInterfaceControlListener.onPlayerInterfaceAlpha(interfaceAlpha);
        }
        
        // 🎬 ЭФФЕКТ 3: Параллакс для anime info (двигается медленнее + fade + subtle scale)
        // Появляется с 0.2 до 0.8 с эффектом "всплытия"
        if (animeInfoContainer != null) {
            float animeInfoAlpha = 0f;
            float animeInfoTranslationY = 0f;
            float animeInfoScale = 0.95f;
            
            if (progress > 0.2f && progress < 0.8f) {
                // Маппируем progress от 0.2-0.8 к alpha от 0.0-1.0
                float localProgress = (progress - 0.2f) / 0.6f;
                // Ease-out для плавного появления
                float easedProgress = 1f - (float)Math.pow(1f - localProgress, 2);
                animeInfoAlpha = easedProgress;
                
                // Параллакс: двигается вверх медленнее чем основная панель
                animeInfoTranslationY = 40f * (1f - easedProgress); // 40dp → 0
                
                // Легкий scale эффект
                animeInfoScale = 0.95f + (0.05f * easedProgress); // 0.95 → 1.0
                
            } else if (progress >= 0.8f) {
                animeInfoAlpha = 1f;
                animeInfoTranslationY = 0f;
                animeInfoScale = 1.0f;
            } else {
                // progress <= 0.2
                animeInfoAlpha = 0f;
                animeInfoTranslationY = 40f;
                animeInfoScale = 0.95f;
            }
            
            animeInfoContainer.setAlpha(animeInfoAlpha);
            animeInfoContainer.setTranslationY(animeInfoTranslationY);
            animeInfoContainer.setScaleX(animeInfoScale);
            animeInfoContainer.setScaleY(animeInfoScale);
            animeInfoContainer.setPivotX(animeInfoContainer.getWidth() / 2f);
            animeInfoContainer.setPivotY(animeInfoContainer.getHeight() / 2f);
        }
        
        // 🎬 ЭФФЕКТ 4: Bounce эффект для header (fade + translateY + subtle rotation)
        // Появляется с 0.5 до 1.0 с легким "подпрыгиванием"
        if (relatedTitlesHeader != null) {
            float headerAlpha = 0f;
            float headerTranslationY = 0f;
            float headerRotation = 0f;
            
            if (progress > 0.5f) {
                // Маппируем progress от 0.5-1.0 к alpha от 0.0-1.0
                float localProgress = (progress - 0.5f) / 0.5f;
                localProgress = Math.min(1f, localProgress);
                
                // Overshoot интерполятор для bounce эффекта
                float tension = 1.5f;
                float overshootProgress;
                if (localProgress < 1f) {
                    overshootProgress = localProgress * localProgress * ((tension + 1f) * localProgress - tension);
                } else {
                    localProgress -= 1f;
                    overshootProgress = localProgress * localProgress * ((tension + 1f) * localProgress + tension) + 1f;
                }
                
                // Alpha с ease-out
                float easedAlpha = 1f - (float)Math.pow(1f - localProgress, 2);
                headerAlpha = Math.max(0f, Math.min(1f, easedAlpha));
                
                // TranslationY с bounce (может уйти чуть ниже 0)
                headerTranslationY = -30f * (1f - overshootProgress); // -30dp → 0 (с bounce)
                
                // Легкая ротация для динамики
                headerRotation = -2f * (1f - localProgress); // -2° → 0°
                
            } else {
                headerAlpha = 0f;
                headerTranslationY = -30f;
                headerRotation = -2f;
            }
            
            relatedTitlesHeader.setAlpha(headerAlpha);
            relatedTitlesHeader.setTranslationY(headerTranslationY);
            relatedTitlesHeader.setRotation(headerRotation);
        }
        
        // 🎬 ЭФФЕКТ 5: RecyclerView с легким fade-in (появляется последним)
        if (relatedTitlesRecyclerView != null) {
            float recyclerAlpha = 0f;
            if (progress > 0.6f) {
                float localProgress = (progress - 0.6f) / 0.4f; // 0.6 → 1.0
                localProgress = Math.min(1f, localProgress);
                recyclerAlpha = 1f - (float)Math.pow(1f - localProgress, 2); // ease-out
            }
            relatedTitlesRecyclerView.setAlpha(recyclerAlpha);
        }
        
        // НЕ обновляем флаг isRelatedTitlesVisible здесь!
        // Это вызывает проблему: при progress >= 0.9 флаг становится true,
        // и потом showRelatedTitles() делает ранний return, не завершая анимацию
        // Флаг должен обновляться только в showRelatedTitles() и hideRelatedTitles()
        
        // Только сбрасываем флаг если полностью закрыто
        if (progress <= 0.0f) {
            isRelatedTitlesVisible = false;
        }
        
        // Скрываем если progress = 0
        if (progress == 0f) {
            relatedTitlesOverlay.setVisibility(View.GONE);
            if (relatedTitlesDimOverlay != null) {
                relatedTitlesDimOverlay.setVisibility(View.GONE);
            }
            
            // Сбрасываем все трансформации
            relatedTitlesOverlay.setScaleX(1f);
            relatedTitlesOverlay.setScaleY(1f);
            if (animeInfoContainer != null) {
                animeInfoContainer.setScaleX(1f);
                animeInfoContainer.setScaleY(1f);
                animeInfoContainer.setTranslationY(0f);
            }
            if (relatedTitlesHeader != null) {
                relatedTitlesHeader.setTranslationY(0f);
                relatedTitlesHeader.setRotation(0f);
            }
            if (relatedTitlesRecyclerView != null) {
                relatedTitlesRecyclerView.setAlpha(1f);
            }
        }
        
        Log.d(TAG, "Related titles drag progress: " + progress + ", translationY=" + translationY + 
              ", scale=" + scale + ", overlayHeight=" + overlayHeightPx);
    }
    
    /**
     * Завершает drag жест с решением открыть или закрыть панель связанных тайтлов
     */
    public void completeDrag(boolean shouldOpen) {
        Log.d(TAG, "Complete drag: shouldOpen=" + shouldOpen);
        if (shouldOpen) {
            showRelatedTitles();
        } else {
            hideRelatedTitles();
        }
    }
    
    /**
     * Обновляет состояние панели без анимации (используется при конфликтах)
     */
    public void updateDragState(boolean shouldOpen) {
        Log.d(TAG, "Update drag state: shouldOpen=" + shouldOpen);
        if (shouldOpen) {
            showRelatedTitles();
        } else {
            hideRelatedTitles();
        }
    }
    
    /**
     * Возвращает true если панель связанных тайтлов видима
     */
    public boolean isRelatedTitlesVisible() {
        return isRelatedTitlesVisible;
    }
    
    /**
     * Обновляет данные связанных тайтлов
     */
    public void updateRelatedTitles(List<RelatedTitlesResponse.RelatedTitle> relatedTitles) {
        this.pendingRelatedTitles = relatedTitles;
        if (isRelatedTitlesVisible && relatedTitlesAdapter != null) {
            relatedTitlesAdapter.updateData(relatedTitles);
        }
    }
    
    /**
     * Сбрасывает позицию overlay (используется при конфликтах с эпизодами)
     */
    public void resetControllerPosition() {
        if (relatedTitlesOverlay != null) {
            relatedTitlesOverlay.animate().cancel();
            relatedTitlesOverlay.setVisibility(View.GONE);
        }
        if (relatedTitlesDimOverlay != null) {
            relatedTitlesDimOverlay.animate().cancel();
            relatedTitlesDimOverlay.setVisibility(View.GONE);
        }
        isRelatedTitlesVisible = false;
    }
}