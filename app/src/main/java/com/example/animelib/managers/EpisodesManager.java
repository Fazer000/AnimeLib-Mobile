package com.example.animelib.managers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.adapters.HorizontalEpisodesAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.DensityUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Менеджер для управления эпизодами и связанным UI
 */
public class EpisodesManager {
    private static final String TAG = "EpisodesManager";

    // Контекст и зависимости
    private final Context context;
    private final ApiService apiService;
    private final BookmarkManager bookmarkManager;
    private DensityUtils densityUtils;

    // UI компоненты
    private View episodesMenuPanel;
    private ImageButton episodesMenuButton;
    private RecyclerView episodesRecyclerView;
    private View landscapeEpisodesContainer;
    private TextView episodesCountText;
    private ImageButton prevEpisodeButton;
    private ImageButton nextEpisodeButton;
    private View menuOverlay;
    private View playersControlBar;

    // Состояние
    private boolean isEpisodesMenuVisible = false;
    private final List<EpisodesListResponse.EpisodeItem> episodes = new ArrayList<>();
    private EpisodesListResponse.EpisodeItem currentEpisode;
    private com.example.animelib.models.AnimeBookmarkResponse.BookmarkData animeBookmark;
    private HorizontalEpisodesAdapter episodesAdapter;
    private RecyclerView portraitEpisodesRecyclerView;
    private HorizontalEpisodesAdapter portraitEpisodesAdapter;
    private View portraitSearchContainer;
    private boolean hasInitialScrolledLandscape = false;
    private boolean hasInitialScrolledPortrait = false;

    // Смещение в dp (понятное значение)
    private final float totalOffsetDp = 60f;
    private int totalOffsetPx; // Будет вычислено при инициализации

    // Callback интерфейсы
    public interface EpisodeSelectionCallback {
        void onEpisodeSelected(EpisodesListResponse.EpisodeItem episode, boolean autoPlay);
    }

    public interface EpisodesVisibilityCallback {
        void onEpisodesVisibilityChanged(boolean isVisible);
    }

    public interface EpisodesDataCallback {
        void onEpisodesLoaded(List<EpisodesListResponse.EpisodeItem> episodes);

        void onEpisodesError(String error);
    }

    public interface PlayerControlsCallback {
        void onPlayerControlsAutoHideChanged(boolean shouldAutoHide);
    }

    private EpisodeSelectionCallback episodeSelectionCallback;
    private EpisodesVisibilityCallback visibilityCallback;
    private EpisodesDataCallback dataCallback;
    private PlayerControlsCallback playerControlsCallback;

    public EpisodesManager(Context context, ApiService apiService) {
        this.context = context;
        this.apiService = apiService;
        this.bookmarkManager = new BookmarkManager(context, apiService);
    }

    /**
     * Инициализация UI компонентов
     */
    public void initializeViews(View episodesMenuPanel, ImageButton episodesMenuButton,
                                RecyclerView episodesRecyclerView, TextView episodesCountText,
                                ImageButton prevEpisodeButton, ImageButton nextEpisodeButton,
                                View menuOverlay, View playersControlBar) {
        this.episodesMenuPanel = episodesMenuPanel;
        this.episodesMenuButton = episodesMenuButton;
        this.episodesRecyclerView = episodesRecyclerView;
        this.episodesCountText = episodesCountText;
        this.prevEpisodeButton = prevEpisodeButton;
        this.nextEpisodeButton = nextEpisodeButton;
        this.menuOverlay = menuOverlay;
        this.playersControlBar = playersControlBar;

        // Преобразуем dp в px один раз при инициализации
        totalOffsetPx = dpToPx(totalOffsetDp);

        setupEpisodesViews();
        initializeControlBarPosition();
    }

    public void setPortraitEpisodesRecyclerView(RecyclerView portraitRv) {
        this.portraitEpisodesRecyclerView = portraitRv;
        if (this.portraitEpisodesRecyclerView != null) {
            this.portraitEpisodesRecyclerView.setHasFixedSize(true);
            this.portraitEpisodesRecyclerView.setItemAnimator(null);
            this.portraitEpisodesRecyclerView.setItemViewCacheSize(20);
            this.portraitEpisodesRecyclerView.setHorizontalFadingEdgeEnabled(true);
            this.portraitEpisodesRecyclerView.setFadingEdgeLength(dpToPx(40));
            this.portraitEpisodesRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            setupTouchInterception(this.portraitEpisodesRecyclerView);
            updateEpisodesRecyclerView();
        }
    }

    public void setLandscapeEpisodesContainer(View container) {
        this.landscapeEpisodesContainer = container;
    }

    private View getLandscapeEpisodesView() {
        if (landscapeEpisodesContainer != null) {
            return landscapeEpisodesContainer;
        }
        return episodesRecyclerView;
    }

    /**
     * Преобразование dp в px
     */
    private int dpToPx(float dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f); // +0.5f для правильного округления
    }

    private boolean isPortrait() {
        return context != null && context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    private float getClosedTranslationY() {
        if (isPortrait()) {
            return 0f;
        }
        return totalOffsetPx;
    }

    /**
     * Инициализация позиции playersControlBar
     */
    private void initializeControlBarPosition() {
        if (playersControlBar == null) return;

        // Устанавливаем начальное смещение вниз (закрытое состояние)
        playersControlBar.setTranslationY(getClosedTranslationY());
        
        Log.d(TAG, "Initialized playersControlBar translationY=" + getClosedTranslationY() + "px (closed state)");
    }

    /**
     * Настройка UI компонентов эпизодов
     */
    private void setupEpisodesViews() {
        if (episodesMenuButton != null) {
            episodesMenuButton.setOnClickListener(v -> toggleEpisodesMenu());
        }

        setupEpisodesRecyclerView();
        setupEpisodeNavigationButtons();
    }

    /**
     * Настройка RecyclerView для эпизодов (горизонтальный список)
     */
    private void setupEpisodesRecyclerView() {
        if (episodesRecyclerView == null) return;

        episodesRecyclerView.setHasFixedSize(true);
        episodesRecyclerView.setItemAnimator(null);
        episodesRecyclerView.setItemViewCacheSize(20);
        episodesRecyclerView.setHorizontalFadingEdgeEnabled(true);
        episodesRecyclerView.setFadingEdgeLength(dpToPx(40));

        episodesAdapter = new HorizontalEpisodesAdapter(episodes, currentEpisode, true, episode -> {
            if (episodeSelectionCallback != null) {
                episodeSelectionCallback.onEpisodeSelected(episode, true);
            }
            hideEpisodesMenu();
        });

        LinearLayoutManager layoutManager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
        episodesRecyclerView.setLayoutManager(layoutManager);
        episodesRecyclerView.setAdapter(episodesAdapter);
        setupTouchInterception(episodesRecyclerView);
    }

    /**
     * Предотвращает перехват касаний родительскими контейнерами (например, NestedScrollView) во время перетаскивания карусели.
     */
    private void setupTouchInterception(RecyclerView rv) {
        if (rv == null) return;
        rv.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        if (rv.getParent() != null) {
                            rv.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (rv.getParent() != null) {
                            rv.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        break;
                }
                return false;
            }
        });
    }

    /**
     * Настройка кнопок навигации по эпизодам
     */
    private void setupEpisodeNavigationButtons() {
        if (prevEpisodeButton != null) {
            prevEpisodeButton.setOnClickListener(v -> navigateToPreviousEpisode());
        }

        if (nextEpisodeButton != null) {
            nextEpisodeButton.setOnClickListener(v -> navigateToNextEpisode());
        }

        updateEpisodeNavigationButtonsVisibility();
    }

    /**
     * Настройка поиска серии для портретного режима
     */
    public void setupPortraitSearchViews(View searchBtn, EditText inputEt) {
        if (searchBtn != null && searchBtn.getParent() instanceof View) {
            this.portraitSearchContainer = (View) searchBtn.getParent();
            updatePortraitSearchVisibility();
        }
        setupSearchUI(searchBtn, inputEt);
    }

    private void updatePortraitSearchVisibility() {
        if (portraitSearchContainer != null) {
            portraitSearchContainer.setVisibility((episodes != null && !episodes.isEmpty()) ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Настройка поиска серии для альбомного режима
     */
    public void setupLandscapeSearchViews(View searchBtn, EditText inputEt) {
        setupSearchUI(searchBtn, inputEt);
    }

    /**
     * Настройка функционала поиска серии по номеру (кнопка 38x38 заменяется на инпут)
     */
    public void setupSearchUI(View searchBtn, EditText inputEt) {
        if (searchBtn == null || inputEt == null) return;

        inputEt.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);

        searchBtn.setOnClickListener(v -> {
            searchBtn.setVisibility(View.GONE);
            inputEt.setVisibility(View.VISIBLE);
            inputEt.setText("");
            inputEt.requestFocus();
            showKeyboard(inputEt);
        });

        inputEt.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                inputEt.setVisibility(View.GONE);
                searchBtn.setVisibility(View.VISIBLE);
                hideKeyboard(inputEt);
            }
        });

        inputEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s != null && s.length() > 0) {
                    scrollToEpisodeNumber(s.toString().trim(), false);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        inputEt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String query = inputEt.getText().toString().trim();
                if (!query.isEmpty()) {
                    boolean found = scrollToEpisodeNumber(query, true);
                    if (found) {
                        inputEt.clearFocus();
                        inputEt.setVisibility(View.GONE);
                        searchBtn.setVisibility(View.VISIBLE);
                        hideKeyboard(inputEt);
                    } else {
                        CustomToast.showWarning(context, "Серия №" + query + " не найдена");
                    }
                } else {
                    inputEt.clearFocus();
                    inputEt.setVisibility(View.GONE);
                    searchBtn.setVisibility(View.VISIBLE);
                    hideKeyboard(inputEt);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Прокрутка и выбор серии по введённому номеру
     */
    public boolean scrollToEpisodeNumber(String queryNumber, boolean autoSelect) {
        if (episodes == null || episodes.isEmpty() || queryNumber == null || queryNumber.isEmpty()) return false;

        int foundIndex = -1;
        // 1. Точное совпадение по номеру
        for (int i = 0; i < episodes.size(); i++) {
            EpisodesListResponse.EpisodeItem item = episodes.get(i);
            if (item != null && item.getNumber() != null) {
                String num = item.getNumber().trim();
                if (num.equalsIgnoreCase(queryNumber)) {
                    foundIndex = i;
                    break;
                }
            }
        }

        // 2. Если не найдено, ищем по числовому значению
        if (foundIndex == -1) {
            try {
                int parsedTarget = Integer.parseInt(queryNumber);
                for (int i = 0; i < episodes.size(); i++) {
                    EpisodesListResponse.EpisodeItem item = episodes.get(i);
                    if (item != null && item.getNumber() != null) {
                        try {
                            int num = Integer.parseInt(item.getNumber().trim());
                            if (num == parsedTarget) {
                                foundIndex = i;
                                break;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

        // 3. Если не найдено, ищем по совпадению с начала
        if (foundIndex == -1) {
            for (int i = 0; i < episodes.size(); i++) {
                EpisodesListResponse.EpisodeItem item = episodes.get(i);
                if (item != null && item.getNumber() != null && item.getNumber().trim().startsWith(queryNumber)) {
                    foundIndex = i;
                    break;
                }
            }
        }

        if (foundIndex != -1) {
            final int indexToScroll = foundIndex;
            EpisodesListResponse.EpisodeItem foundEpisode = episodes.get(indexToScroll);

            // Прокручиваем активные RecyclerView
            if (portraitEpisodesRecyclerView != null && portraitEpisodesRecyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                ((LinearLayoutManager) portraitEpisodesRecyclerView.getLayoutManager()).scrollToPositionWithOffset(indexToScroll, 0);
            }
            if (episodesRecyclerView != null && episodesRecyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                ((LinearLayoutManager) episodesRecyclerView.getLayoutManager()).scrollToPositionWithOffset(indexToScroll, 0);
            }

            if (autoSelect && foundEpisode != null) {
                if (episodeSelectionCallback != null) {
                    episodeSelectionCallback.onEpisodeSelected(foundEpisode, true);
                }
            }
            return true;
        }

        return false;
    }

    private void showKeyboard(View view) {
        if (view == null || context == null) return;
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard(View view) {
        if (view == null || context == null) return;
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Переключение видимости меню эпизодов
     */
    public void toggleEpisodesMenu() {
        if (isEpisodesMenuVisible) {
            hideEpisodesMenu();
        } else {
            showEpisodesMenu();
        }
    }

    /**
     * Показать меню эпизодов (поднять весь playersControlBar)
     */
    public void showEpisodesMenu() {
        if (isPortrait() || episodesRecyclerView == null || playersControlBar == null) return;

        Log.d(TAG, "Showing episodes horizontal list - lifting playersControlBar");
        isEpisodesMenuVisible = true;

        // Отключаем автоматическое скрытие интерфейса плеера
        if (playerControlsCallback != null) {
            playerControlsCallback.onPlayerControlsAutoHideChanged(false);
        }

        View targetView = getLandscapeEpisodesView();

        // Сначала показываем View с анимацией появления
        if (targetView != null) {
            targetView.setVisibility(View.VISIBLE);
            targetView.setAlpha(0f);
            targetView.setScaleX(0.95f);
            targetView.setScaleY(0.95f);
        }
        if (episodesRecyclerView != null) {
            episodesRecyclerView.setVisibility(View.VISIBLE);
        }

        // Анимация для playersControlBar - подъем с "пружинным" эффектом
        Log.d(TAG, "EpisodesManager: Animating playersControlBar translationY to: 0px (opened)");
        playersControlBar.animate()
                .translationY(0)
                .setDuration(300)
                .setInterpolator(new OvershootInterpolator(0.6f)) // Пружинный эффект
                .withStartAction(() -> {
                    // Параллельная анимация появления списка эпизодов
                    if (targetView != null) {
                        targetView.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(250)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                    }
                })
                .withEndAction(() -> {
                    if (visibilityCallback != null) {
                        visibilityCallback.onEpisodesVisibilityChanged(true);
                    }
                })
                .start();

        updateEpisodeNavigationButtonsVisibility();
    }

    /**
     * Скрыть меню эпизодов (опустить весь playersControlBar)
     */
    public void hideEpisodesMenu() {
        if (episodesRecyclerView == null || playersControlBar == null) return;

        Log.d(TAG, "Hiding episodes horizontal list - lowering playersControlBar");
        isEpisodesMenuVisible = false;

        View targetView = getLandscapeEpisodesView();

        if (isPortrait()) {
            if (targetView != null) targetView.setVisibility(View.GONE);
            playersControlBar.animate().cancel();
            playersControlBar.setTranslationY(0f);
            updateEpisodeNavigationButtonsVisibility();
            if (visibilityCallback != null) {
                visibilityCallback.onEpisodesVisibilityChanged(false);
            }
            return;
        }

        // Включаем автоматическое скрытие интерфейса плеера
        if (playerControlsCallback != null) {
            playerControlsCallback.onPlayerControlsAutoHideChanged(true);
        }

        // Анимация исчезновения списка эпизодов
        if (targetView != null) {
            targetView.animate()
                    .alpha(0f)
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(150)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        // Устанавливаем INVISIBLE чтобы сохранить место в layout
                        targetView.setVisibility(View.INVISIBLE);
                    })
                    .start();
        }

        // Анимация для playersControlBar - опускание с "антиципацией"
        Log.d(TAG, "EpisodesManager: Animating playersControlBar translationY to: " + getClosedTranslationY() + "px (closed)");
        playersControlBar.animate()
                .translationY(getClosedTranslationY())
                .setDuration(250)
                .setInterpolator(new AnticipateInterpolator(1f)) // Эффект anticipation
                .withEndAction(() -> {
                    if (visibilityCallback != null) {
                        visibilityCallback.onEpisodesVisibilityChanged(false);
                    }
                })
                .start();

        updateEpisodeNavigationButtonsVisibility();
    }

    /**
     * Навигация к предыдущему эпизоду
     */
    public void navigateToPreviousEpisode() {
        Log.d(TAG, "navigateToPreviousEpisode called");

        if (episodes.isEmpty()) {
            Log.d(TAG, "No episodes available");
            return;
        }

        if (currentEpisode == null) {
            Log.d(TAG, "No current episode, cannot navigate to previous");
            return;
        }

        int currentIndex = -1;
        for (int i = 0; i < episodes.size(); i++) {
            if (Objects.equals(episodes.get(i).getNumber(), currentEpisode.getNumber())) {
                currentIndex = i;
                break;
            }
        }

        Log.d(TAG, "Current index: " + currentIndex + ", episodes count: " + episodes.size());

        if (currentIndex > 0) {
            EpisodesListResponse.EpisodeItem previousEpisode = episodes.get(currentIndex - 1);
            Log.d(TAG, "Navigating to previous episode: " + previousEpisode.getNumber());

            if (episodeSelectionCallback != null) {
                episodeSelectionCallback.onEpisodeSelected(previousEpisode, true);
            }
        } else {
            Log.d(TAG, "No previous episode available");
        }
    }

    /**
     * Навигация к следующему эпизоду
     */
    public void navigateToNextEpisode() {
        Log.d(TAG, "navigateToNextEpisode called");

        if (episodes.isEmpty()) {
            Log.d(TAG, "No episodes available");
            return;
        }

        if (currentEpisode == null) {
            Log.d(TAG, "No current episode, cannot navigate to next");
            return;
        }

        int currentIndex = -1;
        for (int i = 0; i < episodes.size(); i++) {
            if (Objects.equals(episodes.get(i).getNumber(), currentEpisode.getNumber())) {
                currentIndex = i;
                break;
            }
        }

        Log.d(TAG, "Current index: " + currentIndex + ", episodes count: " + episodes.size());

        if (currentIndex >= 0 && currentIndex < episodes.size() - 1) {
            EpisodesListResponse.EpisodeItem nextEpisode = episodes.get(currentIndex + 1);
            Log.d(TAG, "Navigating to next episode: " + nextEpisode.getNumber());

            if (episodeSelectionCallback != null) {
                episodeSelectionCallback.onEpisodeSelected(nextEpisode, true);
            }
        } else {
            Log.d(TAG, "No next episode available");
        }
    }

    /**
     * Обновление видимости кнопок навигации по эпизодам
     */
    public void updateEpisodeNavigationButtonsVisibility() {
        Log.d(TAG, "updateEpisodeNavigationButtonsVisibility called");

        if (prevEpisodeButton == null || nextEpisodeButton == null) {
            Log.d(TAG, "Navigation buttons not initialized");
            return;
        }

        // Показываем кнопки только если меню эпизодов скрыто и есть эпизоды
        boolean shouldShow = !episodes.isEmpty();
        int visibility = shouldShow ? View.VISIBLE : View.GONE;

        // Проверяем, есть ли текущий эпизод
        boolean hasCurrentEpisode = currentEpisode != null && currentEpisode.getNumber() != null;

        boolean isFirstEpisode = false;
        boolean isLastEpisode = false;

        if (hasCurrentEpisode && !episodes.isEmpty()) {
            try {
                int currentEpisodeNumber = Integer.parseInt(currentEpisode.getNumber());
                int totalEpisodes = episodes.size();

                isFirstEpisode = (currentEpisodeNumber == 1);
                isLastEpisode = (currentEpisodeNumber == totalEpisodes);

            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parsing episode number: " + currentEpisode.getNumber(), e);
            }
        }

        // Устанавливаем видимость
        prevEpisodeButton.setVisibility(visibility);
        nextEpisodeButton.setVisibility(visibility);

        // Активируем/деактивируем кнопки в зависимости от позиции
        prevEpisodeButton.setEnabled(!isFirstEpisode && shouldShow);
        nextEpisodeButton.setEnabled(!isLastEpisode && shouldShow);

        // Меняем прозрачность для неактивных кнопок
        float activeAlpha = 1.0f;
        float inactiveAlpha = 0.5f;

        prevEpisodeButton.setAlpha(isFirstEpisode || !shouldShow ? inactiveAlpha : activeAlpha);
        nextEpisodeButton.setAlpha(isLastEpisode || !shouldShow ? inactiveAlpha : activeAlpha);
    }

    /**
     * Установка списка эпизодов вручную (например, для офлайн режима)
     */
    public void setEpisodes(List<EpisodesListResponse.EpisodeItem> newEpisodes) {
        hasInitialScrolledLandscape = false;
        hasInitialScrolledPortrait = false;
        episodes.clear();
        if (newEpisodes != null) {
            episodes.addAll(newEpisodes);
        }
        updateEpisodesRecyclerView();
        updateEpisodesCount();
        updateEpisodeNavigationButtonsVisibility();
        if (episodesMenuButton != null) {
            boolean hasEpisodes = !episodes.isEmpty();
            episodesMenuButton.setEnabled(hasEpisodes);
            episodesMenuButton.setAlpha(hasEpisodes ? 1.0f : 0.3f);
        }
    }

    /**
     * Загрузка эпизодов
     */
    public void loadEpisodes(String animeId) {
        hasInitialScrolledLandscape = false;
        hasInitialScrolledPortrait = false;
        Log.d(TAG, "Loading episodes for anime ID: " + animeId);

        apiService.fetchEpisodesList(animeId, new ApiService.EpisodesCallback() {
            @Override
            public void onEpisodesReceived(EpisodesListResponse response) {
                safeRunOnUiThread(() -> {
                    if (response != null && response.getData() != null) {
                        episodes.clear();
                        episodes.addAll(response.getData());

                        Log.d(TAG, "Episodes loaded: " + episodes.size());

                        updateEpisodesRecyclerView();
                        updateEpisodesCount();
                        updateEpisodeNavigationButtonsVisibility();

                        if (dataCallback != null) {
                            dataCallback.onEpisodesLoaded(episodes);
                        }
                    }
                });
            }

            @Override
            public void onError(String error) {
                safeRunOnUiThread(() -> {
                    Log.e(TAG, "Error loading episodes: " + error);

                    if (dataCallback != null) {
                        dataCallback.onEpisodesError(error);
                    }
                });
            }
        });
    }
    
    /**
     * Загружает эпизоды и закладку аниме
     * @param animeId ID аниме
     * @param mediaSlug Слаг медиа для получения закладки
     */
    public void loadEpisodesWithBookmark(String animeId, String mediaSlug) {
        Log.d(TAG, "Loading episodes and bookmark for anime ID: " + animeId + ", mediaSlug: " + mediaSlug);
        
        // Загружаем эпизоды
        loadEpisodes(animeId);
        
        // Загружаем закладку
        if (mediaSlug != null && !mediaSlug.isEmpty()) {
            bookmarkManager.fetchAnimeBookmark(mediaSlug, new BookmarkManager.AnimeBookmarkCallback() {
                @Override
                public void onBookmarkReceived(com.example.animelib.models.AnimeBookmarkResponse response) {
                    safeRunOnUiThread(() -> {
                        if (response != null && response.getData() != null) {
                            animeBookmark = response.getData();
                            Log.d(TAG, "Anime bookmark loaded: episode " + animeBookmark.getItemId() + 
                                       ", progress: " + animeBookmark.getProgress());
                            
                            // Обновляем RecyclerView с информацией о закладке
                            updateEpisodesRecyclerView();
                            if (bookmarkLoadedListener != null) {
                                bookmarkLoadedListener.onBookmarkLoaded(animeBookmark);
                            }
                        }
                    });
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "Error loading anime bookmark: " + error);
                    // Не критично, продолжаем без закладки
                }
            });
        }
    }

    /**
     * Обновление RecyclerView эпизодов
     */
    public void updateEpisodesRecyclerView() {
        boolean isPortrait = isPortrait();

        if (!isPortrait) {
            if (episodesRecyclerView != null) {
                Log.d(TAG, "Updating landscape episodes RecyclerView with current episode: " + 
                    (currentEpisode != null ? currentEpisode.getNumber() + " (ID: " + currentEpisode.getId() + ")" : "null"));
                
                if (episodesAdapter == null) {
                    episodesAdapter = new HorizontalEpisodesAdapter(episodes, currentEpisode, true, episode -> {
                        if (episodeSelectionCallback != null) {
                            episodeSelectionCallback.onEpisodeSelected(episode, true);
                        }
                        hideEpisodesMenu();
                    });
                    episodesRecyclerView.setAdapter(episodesAdapter);
                } else {
                    episodesAdapter.setCurrentEpisode(currentEpisode);
                }
                
                if (animeBookmark != null) {
                    episodesAdapter.setAnimeBookmark(animeBookmark);
                }
                
                if (isEpisodesMenuVisible && currentEpisode != null) {
                    scrollToCurrentEpisode();
                }
            }
        } else {
            if (portraitEpisodesRecyclerView != null) {
                if (portraitEpisodesAdapter == null) {
                    portraitEpisodesAdapter = new HorizontalEpisodesAdapter(episodes, currentEpisode, false, episode -> {
                        if (episodeSelectionCallback != null) {
                            episodeSelectionCallback.onEpisodeSelected(episode, true);
                        }
                    });
                    portraitEpisodesRecyclerView.setAdapter(portraitEpisodesAdapter);
                } else {
                    portraitEpisodesAdapter.setCurrentEpisode(currentEpisode);
                }

                if (animeBookmark != null) {
                    portraitEpisodesAdapter.setAnimeBookmark(animeBookmark);
                }

                if (currentEpisode != null) {
                    scrollToCurrentEpisode();
                }
            }
        }

        updatePortraitSearchVisibility();
    }
    
    /**
     * Прокрутка к текущему эпизоду в списке (только при первоначальном входе в плеер/загрузке)
     */
    private void scrollToCurrentEpisode() {
        if ((episodesRecyclerView == null && portraitEpisodesRecyclerView == null) || currentEpisode == null || episodes.isEmpty()) {
            return;
        }
        
        int currentIndex = -1;
        for (int i = 0; i < episodes.size(); i++) {
            EpisodesListResponse.EpisodeItem episode = episodes.get(i);
            if (episode.getId() == currentEpisode.getId() || 
                (episode.getNumber() != null && episode.getNumber().equals(currentEpisode.getNumber()))) {
                currentIndex = i;
                break;
            }
        }
        
        if (currentIndex >= 0) {
            final int targetIndex = currentIndex;
            
            if (episodesRecyclerView != null && !hasInitialScrolledLandscape) {
                hasInitialScrolledLandscape = true;
                Log.d(TAG, "Initial scroll to current episode (landscape) at position: " + targetIndex);
                episodesRecyclerView.post(() -> {
                    if (episodesRecyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                        ((LinearLayoutManager) episodesRecyclerView.getLayoutManager()).scrollToPositionWithOffset(targetIndex, 0);
                    } else {
                        episodesRecyclerView.scrollToPosition(targetIndex);
                    }
                });
            }

            if (portraitEpisodesRecyclerView != null && !hasInitialScrolledPortrait) {
                hasInitialScrolledPortrait = true;
                Log.d(TAG, "Initial scroll to current episode (portrait) at position: " + targetIndex);
                portraitEpisodesRecyclerView.post(() -> {
                    if (portraitEpisodesRecyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                        ((LinearLayoutManager) portraitEpisodesRecyclerView.getLayoutManager()).scrollToPositionWithOffset(targetIndex, 0);
                    } else {
                        portraitEpisodesRecyclerView.scrollToPosition(targetIndex);
                    }
                });
            }
        }
    }

    /**
     * Обновление счетчика эпизодов
     */
    @SuppressLint("SetTextI18n")
    private void updateEpisodesCount() {
        if (episodesCountText != null) {
            episodesCountText.setText(episodes.size() + " эпизодов");
        }
    }

    /**
     * Установка текущего эпизода
     */
    public void setCurrentEpisode(EpisodesListResponse.EpisodeItem episode) {
        Log.d(TAG, "Setting current episode: " + (episode != null ? episode.getNumber() + " (ID: " + episode.getId() + ")" : "null"));
        this.currentEpisode = episode;
        
        // Обновляем UI на главном потоке
        safeRunOnUiThread(() -> {
                updateEpisodesRecyclerView();
                updateEpisodeNavigationButtonsVisibility();
            });
    }

    /**
     * Поиск и установка текущего эпизода по URL
     */
    public void findAndSetCurrentEpisodeFromUrl(String url) {
        if (url == null || episodes.isEmpty()) {
            Log.d(TAG, "URL is null or episodes list is empty");
            return;
        }

        Log.d(TAG, "Finding current episode from URL: " + url);

        // Извлекаем episode ID из URL (например, ?episode=133642)
        String episodeIdStr = null;
        if (url.contains("episode=")) {
            try {
                episodeIdStr = url.substring(url.indexOf("episode=") + 8);
                if (episodeIdStr.contains("&")) {
                    episodeIdStr = episodeIdStr.substring(0, episodeIdStr.indexOf("&"));
                }
                if (episodeIdStr.contains("#")) {
                    episodeIdStr = episodeIdStr.substring(0, episodeIdStr.indexOf("#"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error extracting episode ID from URL", e);
            }
        }

        if (episodeIdStr != null) {
            try {
                int episodeId = Integer.parseInt(episodeIdStr);
                Log.d(TAG, "Looking for episode with ID: " + episodeId);

                // Ищем эпизод по ID
                for (EpisodesListResponse.EpisodeItem episode : episodes) {
                    if (episode.getId() == episodeId) {
                        Log.d(TAG, "Found episode by ID: " + episode.getNumber());
                        setCurrentEpisode(episode);
                        return;
                    }
                }

                Log.d(TAG, "Episode with ID " + episodeId + " not found");
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid episode ID format: " + episodeIdStr, e);
            }
        }

        // Если не нашли по ID, берем первый эпизод
        if (!episodes.isEmpty()) {
            Log.d(TAG, "Using first episode as fallback");
            setCurrentEpisode(episodes.get(0));
        }
    }

    /**
     * Получение текущего эпизода
     */
    public EpisodesListResponse.EpisodeItem getCurrentEpisode() {
        return currentEpisode;
    }
    
    /**
     * Получить следующий эпизод (без переключения)
     */
    public EpisodesListResponse.EpisodeItem getNextEpisode() {
        if (episodes == null || episodes.isEmpty() || currentEpisode == null) {
            return null;
        }
        
        int currentIndex = -1;
        for (int i = 0; i < episodes.size(); i++) {
            if (episodes.get(i).equals(currentEpisode)) {
                currentIndex = i;
                break;
            }
        }
        
        if (currentIndex >= 0 && currentIndex < episodes.size() - 1) {
            return episodes.get(currentIndex + 1);
        }
        
        return null;
    }
    
    /**
     * Получает BookmarkManager
     */
    public BookmarkManager getBookmarkManager() {
        return bookmarkManager;
    }
    
    public interface OnBookmarkLoadedListener {
        void onBookmarkLoaded(com.example.animelib.models.AnimeBookmarkResponse.BookmarkData bookmarkData);
    }

    private OnBookmarkLoadedListener bookmarkLoadedListener;

    public void setOnBookmarkLoadedListener(OnBookmarkLoadedListener listener) {
        this.bookmarkLoadedListener = listener;
    }

    public com.example.animelib.models.AnimeBookmarkResponse.BookmarkData getAnimeBookmark() {
        return animeBookmark;
    }

    /**
     * Обновляет закладку в адаптере
     */
    public void updateBookmarkInAdapter(com.example.animelib.models.AnimeBookmarkResponse.BookmarkData bookmark) {
        animeBookmark = bookmark;
        if (episodesAdapter != null) {
            episodesAdapter.setAnimeBookmark(bookmark);
        }
        if (portraitEpisodesAdapter != null) {
            portraitEpisodesAdapter.setAnimeBookmark(bookmark);
        }
        if (bookmarkLoadedListener != null) {
            bookmarkLoadedListener.onBookmarkLoaded(bookmark);
        }
    }

    /**
     * Получение списка эпизодов
     */
    public List<EpisodesListResponse.EpisodeItem> getEpisodes() {
        return episodes;
    }

    /**
     * Проверка видимости меню эпизодов
     */
    public boolean isEpisodesMenuVisible() {
        return isEpisodesMenuVisible;
    }
    
    /**
     * Сбрасывает позицию контроллера (используется при конфликтах со связанными тайтлами)
     */
    public void resetControllerPosition() {
        if (playersControlBar != null) {
            playersControlBar.animate().cancel();
            playersControlBar.setTranslationY(0f);
        }
        if (episodesRecyclerView != null) {
            episodesRecyclerView.setVisibility(View.GONE);
        }
        isEpisodesMenuVisible = false;
    }

    /**
     * Скрытие всех UI элементов эпизодов (для PiP режима)
     */
    public void hideAllEpisodesUI() {
        if (episodesRecyclerView != null) {
            episodesRecyclerView.setVisibility(View.GONE);
        }
        if (prevEpisodeButton != null) {
            prevEpisodeButton.setVisibility(View.GONE);
        }
        if (nextEpisodeButton != null) {
            nextEpisodeButton.setVisibility(View.GONE);
        }
    }

    /**
     * Скрытие UI элементов эпизодов для PiP режима (НЕ трогаем playersControlBar)
     */
    public void hideEpisodesUIForPiP() {
        Log.d(TAG, "EpisodesManager: Hiding episodes UI for PiP (keeping playersControlBar)");
        if (prevEpisodeButton != null) {
            prevEpisodeButton.setVisibility(View.GONE);
        }
        if (nextEpisodeButton != null) {
            nextEpisodeButton.setVisibility(View.GONE);
        }
        // НЕ трогаем playersControlBar - оставляем его в текущей позиции
    }

    /**
     * Показ всех UI элементов эпизодов (выход из PiP режима)
     */
    public void showAllEpisodesUI() {
        updateEpisodeNavigationButtonsVisibility();
        // Меню эпизодов показывается только по запросу пользователя
    }

    /**
     * Установка callback для выбора эпизода
     */
    public void setEpisodeSelectionCallback(EpisodeSelectionCallback callback) {
        this.episodeSelectionCallback = callback;
    }

    /**
     * Установка callback для изменения видимости
     */
    public void setVisibilityCallback(EpisodesVisibilityCallback callback) {
        this.visibilityCallback = callback;
    }

    /**
     * Установка callback для данных
     */
    public void setDataCallback(EpisodesDataCallback callback) {
        this.dataCallback = callback;
    }

    /**
     * Установка callback для управления автоматическим скрытием плеера
     */
    public void setPlayerControlsCallback(PlayerControlsCallback callback) {
        this.playerControlsCallback = callback;
    }

    /**
     * Безопасно вызывает код в главном потоке
     */
    private void safeRunOnUiThread(Runnable runnable) {
        try {
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).runOnUiThread(runnable);
            } else {
                runnable.run();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calling UI thread", e);
            // Fallback - вызываем в текущем потоке
            try {
                runnable.run();
            } catch (Exception ex) {
                Log.e(TAG, "Error in fallback callback", ex);
            }
        }
    }

    /**
     * Устанавливает прогресс drag (0.0 = закрыто, 1.0 = открыто)
     * Используется для плавного вытягивания панели во время жеста
     */
    public void setDragProgress(float progress) {
        if (isPortrait() || playersControlBar == null || episodesRecyclerView == null) return;
        
        // Ограничиваем progress от 0.0 до 1.0
        progress = Math.max(0f, Math.min(1f, progress));
        
        View targetView = getLandscapeEpisodesView();
        if (targetView == null) targetView = episodesRecyclerView;

        // Показываем элементы если progress > 0
        if (progress > 0f) {
            targetView.setVisibility(View.VISIBLE);
            if (episodesRecyclerView != null) episodesRecyclerView.setVisibility(View.VISIBLE);
        }
        
        // Останавливаем текущие анимации
        playersControlBar.animate().cancel();
        targetView.animate().cancel();
        
        // Вычисляем смещение: 
        // progress = 0 (закрыто): translationY = totalOffsetPx (опущено вниз)
        // progress = 1 (открыто): translationY = 0 (на своей позиции)
        // Строго ограничиваем: от 0 до totalOffsetPx (60dp)
        float translationY = Math.max(0f, Math.min(totalOffsetPx, totalOffsetPx * (1f - progress)));
        playersControlBar.setTranslationY(translationY);
        
        // Обновляем прозрачность и масштаб списка эпизодов
        targetView.setAlpha(progress);
        targetView.setScaleX(0.95f + 0.05f * progress);
        targetView.setScaleY(0.95f + 0.05f * progress);
        
        // НЕ используем GONE - это ломает layout!
        // Используем INVISIBLE чтобы сохранить место в layout
        if (progress == 0f && targetView.getVisibility() == View.VISIBLE) {
            targetView.setVisibility(View.INVISIBLE);
        } else if (progress > 0f && targetView.getVisibility() == View.INVISIBLE) {
            targetView.setVisibility(View.VISIBLE);
            if (episodesRecyclerView != null) episodesRecyclerView.setVisibility(View.VISIBLE);
        }
        
        Log.d(TAG, "Episodes drag progress: " + progress + ", translationY: " + translationY + 
              ", isVisible=" + isEpisodesMenuVisible);
    }
    
    /**
     * Завершает drag жест с решением открыть или закрыть панель эпизодов
     */
    public void completeDrag(boolean shouldOpen) {
        if (isPortrait() || playersControlBar == null || episodesRecyclerView == null) return;
        
        Log.d(TAG, "Complete episodes drag: shouldOpen=" + shouldOpen + ", current isVisible=" + isEpisodesMenuVisible);
        
        View targetView = getLandscapeEpisodesView();
        if (targetView == null) targetView = episodesRecyclerView;

        // Отменяем текущие анимации
        playersControlBar.animate().cancel();
        targetView.animate().cancel();
        
        if (shouldOpen) {
            // Открываем панель
            isEpisodesMenuVisible = true;
            
            // Отключаем автоматическое скрытие интерфейса плеера
            if (playerControlsCallback != null) {
                playerControlsCallback.onPlayerControlsAutoHideChanged(false);
            }
            
            // Показываем RecyclerView если скрыт
            targetView.setVisibility(View.VISIBLE);
            if (episodesRecyclerView != null) {
                episodesRecyclerView.setVisibility(View.VISIBLE);
            }
            
            // Анимация к открытому состоянию
            playersControlBar.animate()
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(new OvershootInterpolator(0.6f))
                    .withEndAction(() -> {
                        if (visibilityCallback != null) {
                            visibilityCallback.onEpisodesVisibilityChanged(true);
                        }
                    })
                    .start();
            
            targetView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(250)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            
            updateEpisodeNavigationButtonsVisibility();
        } else {
            // Закрываем панель
            isEpisodesMenuVisible = false;
            
            // Включаем автоматическое скрытие интерфейса плеера
            if (playerControlsCallback != null) {
                playerControlsCallback.onPlayerControlsAutoHideChanged(true);
            }
            
            View finalTarget = targetView;
            // Анимация к закрытому состоянию
            targetView.animate()
                    .alpha(0f)
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(150)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        finalTarget.setVisibility(View.INVISIBLE);
                    })
                    .start();
            
            playersControlBar.animate()
                    .translationY(totalOffsetPx)
                    .setDuration(250)
                    .setInterpolator(new AnticipateInterpolator(1f))
                    .withEndAction(() -> {
                        if (visibilityCallback != null) {
                            visibilityCallback.onEpisodesVisibilityChanged(false);
                        }
                    })
                    .start();
            
            updateEpisodeNavigationButtonsVisibility();
        }
    }
    
    /**
     * Обновляет состояние после drag без повторной анимации
     */
    public void updateDragState(boolean shouldOpen) {
        if (isPortrait()) return;
        Log.d(TAG, "Update episodes drag state: shouldOpen=" + shouldOpen);
        
        if (shouldOpen && !isEpisodesMenuVisible) {
            // Обновляем флаг и завершаем анимацию БЕЗ повторного запуска
            isEpisodesMenuVisible = true;
            
            // Отключаем автоскрытие контролов
            if (playerControlsCallback != null) {
                playerControlsCallback.onPlayerControlsAutoHideChanged(false);
            }
            
            // Уведомляем
            if (visibilityCallback != null) {
                visibilityCallback.onEpisodesVisibilityChanged(true);
            }
            
            updateEpisodeNavigationButtonsVisibility();
            
            View targetView = getLandscapeEpisodesView();
            if (targetView == null) targetView = episodesRecyclerView;

            // Завершаем анимацию до конечного состояния С АНИМАЦИЕЙ
            if (playersControlBar != null && targetView != null) {
                playersControlBar.animate().cancel();
                targetView.animate().cancel();
                
                // Анимируем к конечным значениям
                playersControlBar.animate()
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
                
                targetView.setVisibility(View.VISIBLE);
                if (episodesRecyclerView != null) {
                    episodesRecyclerView.setVisibility(View.VISIBLE);
                }
                targetView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            }
        } else if (!shouldOpen && isEpisodesMenuVisible) {
            hideEpisodesMenu();
        }
    }
    
    /**
     * Очистка ресурсов
     */
    public void cleanup() {
        episodes.clear();
        currentEpisode = null;
        episodeSelectionCallback = null;
        visibilityCallback = null;
        dataCallback = null;
        playerControlsCallback = null;
        
        // Очищаем BookmarkManager
        if (bookmarkManager != null) {
            bookmarkManager.cleanup();
        }
    }
}
