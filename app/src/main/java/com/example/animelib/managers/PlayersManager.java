package com.example.animelib.managers;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.adapters.PlayerOptionsAdapter;
import com.example.animelib.adapters.PlayerTabsAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.EpisodeResponse;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Менеджер для управления плеерами и озвучками
 */
public class PlayersManager {
    private static final String TAG = "PlayersManager";
    
    // Контекст и зависимости
    private final Context context;
    private final ApiService apiService;
    
    // UI компоненты
    private LinearLayout slidingMenuPanel;
    private ImageButton closeMenuButton;
    private View menuOverlay;
    private View menuLoadingOverlay;
    private View menuLoadingIndicator;
    private com.example.animelib.ui.VoiceoverBottomSheet voiceoverBottomSheet;
    private boolean isLoading = false;

    // Side panel UI
    private TabLayout tabLayout;
    private ViewPager2 sidePanelViewPager;
    private PlayerTabsAdapter sidePanelTabsAdapter;
    private String sidePanelActiveTab = "animelib";
    
    // Состояние меню
    private boolean isMenuVisible = false;
    private float menuWidth;
    
    // Данные плееров
    private List<EpisodeResponse.PlayerData> allPlayers = new ArrayList<>();
    private List<EpisodeResponse.PlayerData> animelibPlayers = new ArrayList<>();
    private List<EpisodeResponse.PlayerData> kodikPlayers = new ArrayList<>();
    private EpisodeResponse.PlayerData currentPlayerData;
    private com.example.animelib.models.KodikResponse currentKodikResponse;
    
    // Предпочтения пользователя
    private String preferredPlayerType; // "animelib" or "kodik"
    private EpisodeResponse.PlayerData preferredAnimelibPlayer;
    private EpisodeResponse.PlayerData preferredKodikPlayer;
    private boolean enable4K = false; // Настройка 4K
    
    // Callback интерфейсы
    public interface PlayerSelectionCallback {
        void onPlayerSelected(EpisodeResponse.PlayerData playerData);
    }
    
    public interface PlayersVisibilityCallback {
        void onPlayersVisibilityChanged(boolean isVisible);
    }
    
    public interface PlayersDataCallback {
        void onPlayersLoaded(List<EpisodeResponse.PlayerData> players);
        void onPlayersError(String error);
    }
    
    private PlayerSelectionCallback playerSelectionCallback;
    private PlayersVisibilityCallback visibilityCallback;
    private PlayersDataCallback dataCallback;
    
    public PlayersManager(Context context, ApiService apiService) {
        this.context = context;
        this.apiService = apiService;
    }
    
    /**
     * Инициализация UI компонентов
     */
    public void initializeViews(LinearLayout slidingMenuPanel, ImageButton closeMenuButton,
                                View dummy1, View dummy2,
                                View menuOverlay, View menuLoadingOverlay, View menuLoadingIndicator) {
        this.slidingMenuPanel = slidingMenuPanel;
        this.closeMenuButton = closeMenuButton;
        this.menuOverlay = menuOverlay;
        this.menuLoadingOverlay = menuLoadingOverlay;
        this.menuLoadingIndicator = menuLoadingIndicator;
        
        setupPlayersViews();
    }
    
    /**
     * Настройка UI компонентов плееров
     */
    private void setupPlayersViews() {
        if (closeMenuButton != null) {
            closeMenuButton.setOnClickListener(v -> {
                if (currentPlayerData != null) {
                    hideMenu();
                }
            });
        }
        
        if (slidingMenuPanel != null) {
            tabLayout = slidingMenuPanel.findViewById(R.id.tabLayout);
            sidePanelViewPager = slidingMenuPanel.findViewById(R.id.viewPager);

            EditText etSearch = slidingMenuPanel.findViewById(R.id.etSearchVoiceovers);
            ImageButton btnClear = slidingMenuPanel.findViewById(R.id.btnClearVoiceoversSearch);
            View btnSort = slidingMenuPanel.findViewById(R.id.btnSortVoiceovers);
            TextView tvSortText = slidingMenuPanel.findViewById(R.id.tvSortVoiceovers);

            if (sidePanelViewPager != null) {
                // Изначально отключаем свайпы по ViewPager2, чтобы drag панели не сдвигал табы
                sidePanelViewPager.setUserInputEnabled(false);

                sidePanelTabsAdapter = new PlayerTabsAdapter(
                        animelibPlayers,
                        kodikPlayers,
                        currentPlayerData,
                        this::onPlayerSelected
                );
                sidePanelViewPager.setAdapter(sidePanelTabsAdapter);

                if (tvSortText != null) {
                    tvSortText.setText(com.example.animelib.util.VoiceoverSortHelper.getSortLabel(sidePanelTabsAdapter.getSortType()));
                }

                if (tabLayout != null) {
                    new TabLayoutMediator(tabLayout, sidePanelViewPager, (tab, position) -> {
                        if (position == 0) {
                            tab.setText("AnimeLib");
                        } else if (position == 1) {
                            tab.setText("Kodik");
                        }
                    }).attach();

                    for (int i = 0; i < tabLayout.getTabCount(); i++) {
                        TabLayout.Tab tab = tabLayout.getTabAt(i);
                        if (tab != null && tab.view != null) {
                            tab.view.setClipToOutline(true);
                        }
                    }
                }

                if (etSearch != null) {
                    etSearch.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                        @Override
                        public void onTextChanged(CharSequence s, int start, int before, int count) {
                            String q = s.toString();
                            if (btnClear != null) {
                                btnClear.setVisibility(q.trim().isEmpty() ? View.GONE : View.VISIBLE);
                            }
                            if (sidePanelTabsAdapter != null) {
                                sidePanelTabsAdapter.setFilterQuery(q);
                            }
                        }

                        @Override
                        public void afterTextChanged(Editable s) {}
                    });
                }

                if (btnClear != null && etSearch != null) {
                    btnClear.setOnClickListener(v -> etSearch.setText(""));
                }

                if (btnSort != null) {
                    btnSort.setOnClickListener(v -> {
                        if (sidePanelTabsAdapter != null && context != null) {
                            com.example.animelib.util.VoiceoverSortHelper.showSortPopup(btnSort, context, sidePanelTabsAdapter);
                        }
                    });
                }

                sidePanelViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        super.onPageSelected(position);
                        sidePanelActiveTab = (position == 1) ? "kodik" : "animelib";
                    }
                });
            }
        }
    }
    
    /**
     * Обработка выбора плеера
     */
    private void onPlayerSelected(EpisodeResponse.PlayerData playerData) {
        Log.d(TAG, "Player selected: " + playerData.getPlayer() + 
              ", Team: " + (playerData.getTeam() != null ? playerData.getTeam().getName() : "null"));
        
        // Update current player data
        currentPlayerData = playerData;
        
        // Обновляем меню с новым currentPlayerData для правильной подсветки
        updateMenuWithData();
        
        // Save user preference
        if (playerData.getPlayer() != null) {
            preferredPlayerType = playerData.getPlayer().toLowerCase();
            if ("animelib".equalsIgnoreCase(playerData.getPlayer())) {
                preferredAnimelibPlayer = playerData;
            } else if ("kodik".equalsIgnoreCase(playerData.getPlayer())) {
                preferredKodikPlayer = playerData;
            }
        }
        
        // Hide menu
        hideMenu();
        
        // Notify callback
        if (playerSelectionCallback != null) {
            playerSelectionCallback.onPlayerSelected(playerData);
        }
    }
    
    /**
     * Показать меню плееров
     */
    public void showMenu() {
        boolean isPortrait = context != null && context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        if (isPortrait) {
            showVoiceoverBottomSheet();
            return;
        }

        if (!isMenuVisible) {
            Log.d(TAG, "Showing players menu");
            isMenuVisible = true;
            
            // Обновляем данные в меню перед показом
            updateMenuWithData();
            
            // Use VideoPlayerActivity's method to open draggable panel
            if (context instanceof com.example.animelib.VideoPlayerActivity) {
                ((com.example.animelib.VideoPlayerActivity) context).openMenuPanel();
            }
            
            if (visibilityCallback != null) {
                visibilityCallback.onPlayersVisibilityChanged(true);
            }
        }
    }

    /**
     * Показать BottomSheet с озвучками для портретного режима
     */
    public void showVoiceoverBottomSheet() {
        if (voiceoverBottomSheet != null && voiceoverBottomSheet.isShowing()) {
            voiceoverBottomSheet.dismiss();
        }

        voiceoverBottomSheet = new com.example.animelib.ui.VoiceoverBottomSheet(
                context,
                animelibPlayers,
                kodikPlayers,
                currentPlayerData,
                isLoading,
                this::onPlayerSelected,
                () -> {
                    if (context instanceof com.example.animelib.VideoPlayerActivity) {
                        com.example.animelib.VideoPlayerActivity activity = (com.example.animelib.VideoPlayerActivity) context;
                        if (com.example.animelib.services.DownloadService.isRunning()) {
                            activity.showDownloadProgressBottomSheet();
                        } else {
                            activity.showDownloadBottomSheet();
                        }
                    }
                }
        );

        voiceoverBottomSheet.setOnDismissListener(dialog -> {
            isMenuVisible = false;
            if (visibilityCallback != null) {
                visibilityCallback.onPlayersVisibilityChanged(false);
            }
        });

        isMenuVisible = true;
        voiceoverBottomSheet.show();

        if (visibilityCallback != null) {
            visibilityCallback.onPlayersVisibilityChanged(true);
        }
    }

    public void updateDownloadButtonState(boolean isDownloading) {
        if (voiceoverBottomSheet != null && voiceoverBottomSheet.isShowing()) {
            voiceoverBottomSheet.updateDownloadButtonState(isDownloading);
        }
    }
    
    /**
     * Скрыть меню плееров
     */
    @OptIn(markerClass = UnstableApi.class)
    public void forceHideMenu() {
        if (voiceoverBottomSheet != null && voiceoverBottomSheet.isShowing()) {
            try {
                voiceoverBottomSheet.dismiss();
            } catch (Exception ignored) {}
        }
        isMenuVisible = false;
        if (visibilityCallback != null) {
            visibilityCallback.onPlayersVisibilityChanged(false);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    public void hideMenu() {
        if (voiceoverBottomSheet != null && voiceoverBottomSheet.isShowing()) {
            voiceoverBottomSheet.dismiss();
        }

        // Block closing if no episode selected
        if (currentPlayerData == null) {
            return;
        }
        
        if (isMenuVisible) {
            Log.d(TAG, "Hiding players menu");
            isMenuVisible = false;
            
            // Use VideoPlayerActivity's method to close draggable panel
            if (context instanceof com.example.animelib.VideoPlayerActivity) {
                ((com.example.animelib.VideoPlayerActivity) context).closeMenuPanel();
            }
            
            if (visibilityCallback != null) {
                visibilityCallback.onPlayersVisibilityChanged(false);
            }
        }
    }
    
    /**
     * Переключение видимости меню
     */
    public void toggleMenu() {
        if (isMenuVisible) {
            hideMenu();
        } else {
            showMenu();
        }
    }
    
    /**
     * Загрузка плееров для эпизода
     */
    public void loadPlayersForEpisode(int episodeId) {
        Log.d(TAG, "Loading players for episode ID: " + episodeId);
        
        // Show loading
        showLoading();
        
        apiService.fetchEpisodeData(episodeId, new ApiService.EpisodeDataCallback() {
            @Override
            public void onEpisodeDataReceived(EpisodeResponse response) {
                // ВАЖНО: onEpisodeDataReceived вызывается в фоновом потоке (от OkHttp callback)
                // Поэтому loadPlayerPreferences вызывается БЕЗ проблем здесь
                if (response.getData() != null && response.getData().getPlayers() != null) {
                    List<EpisodeResponse.PlayerData> players = response.getData().getPlayers();
                    
                    // Попытка автоматического выбора плеера на основе сохраненных предпочтений
                    com.example.animelib.data.entity.PlayerPreferences prefs = apiService.loadPlayerPreferences();
                    
                    Log.d(TAG, "Loaded preferences from DB: " + (prefs != null ? 
                          ("player=" + prefs.getPlayer() + ", teamId=" + prefs.getTeamId() + ", quality=" + prefs.getPreferredQuality()) : 
                          "null"));
                    
                    EpisodeResponse.PlayerData matchingPlayer = null;
                    
                    if (prefs != null && prefs.getPlayer() != null && prefs.getTeamId() != null) {
                        Log.d(TAG, "Found saved preferences: player=" + prefs.getPlayer() + ", teamId=" + prefs.getTeamId());
                        
                        // Сначала ищем точное совпадение (сохраненный плеер + озвучка)
                        for (EpisodeResponse.PlayerData player : players) {
                            if (player.getPlayer() != null && 
                                player.getPlayer().equals(prefs.getPlayer()) && 
                                player.getTeam() != null && 
                                player.getTeam().getId() == prefs.getTeamId()) {
                                matchingPlayer = player;
                                Log.d(TAG, "Found exact match in saved player: " + player.getPlayer() + 
                                      ", team: " + player.getTeam().getName());
                                break;
                            }
                        }
                        
                        // Если не найдено в сохраненном плеере, ищем озвучку в других плеерах
                        if (matchingPlayer == null) {
                            Log.d(TAG, "Team not found in saved player, searching in other players");
                            for (EpisodeResponse.PlayerData player : players) {
                                if (player.getTeam() != null && 
                                    player.getTeam().getId() == prefs.getTeamId()) {
                                    matchingPlayer = player;
                                    Log.d(TAG, "Found team in different player: " + player.getPlayer() + 
                                          ", team: " + player.getTeam().getName());
                                    break;
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "No saved player preferences found");
                    }
                    
                    // Сохраняем финальный результат для передачи в UI поток
                    EpisodeResponse.PlayerData finalMatchingPlayer = matchingPlayer;
                    
                    // Переключаемся в UI поток для обновления интерфейса
                    safeRunOnUiThread(() -> {
                        hideLoading();
                        
                        if (finalMatchingPlayer != null) {
                            // Автоматически выбираем найденный плеер БЕЗ показа меню
                            Log.d(TAG, "Auto-selecting player for episode change: " + 
                                  finalMatchingPlayer.getPlayer() + ", Team: " + 
                                  (finalMatchingPlayer.getTeam() != null ? finalMatchingPlayer.getTeam().getName() : "null"));
                            
                            // Сохраняем данные плееров БЕЗ показа меню
                            setPlayersDataSilent(players);
                            
                            Log.d(TAG, "Players data saved, now calling playerSelectionCallback");
                            
                            // Вызываем callback для выбора плеера
                            if (playerSelectionCallback != null) {
                                playerSelectionCallback.onPlayerSelected(finalMatchingPlayer);
                            } else {
                                Log.w(TAG, "playerSelectionCallback is null!");
                            }
                        } else {
                            // НЕ показываем меню при переключении эпизода
                            // Автоматически выбираем первую доступную озвучку
                            Log.d(TAG, "No matching player found for episode, auto-selecting first available");
                            setPlayersDataSilent(players);
                            
                            // Выбираем первую доступную озвучку
                            if (!players.isEmpty()) {
                                EpisodeResponse.PlayerData firstPlayer = players.get(0);
                                Log.d(TAG, "Auto-selecting first player: " + firstPlayer.getPlayer() + 
                                      ", Team: " + (firstPlayer.getTeam() != null ? firstPlayer.getTeam().getName() : "null"));
                                
                                if (playerSelectionCallback != null) {
                                    playerSelectionCallback.onPlayerSelected(firstPlayer);
                                } else {
                                    Log.w(TAG, "playerSelectionCallback is null!");
                                }
                            } else {
                                Log.e(TAG, "No players available to auto-select!");
                            }
                        }
                        
                        if (dataCallback != null) {
                            dataCallback.onPlayersLoaded(allPlayers);
                        }
                    });
                } else {
                    safeRunOnUiThread(() -> {
                        hideLoading();
                        Log.e(TAG, "No players found in response");
                        if (dataCallback != null) {
                            dataCallback.onPlayersError("Плееры не найдены");
                        }
                    });
                }
            }

            @Override
            public void onError(String error) {
                safeRunOnUiThread(() -> {
                    hideLoading();
                    Log.e(TAG, "Error loading players: " + error);
                    
                    if (dataCallback != null) {
                        dataCallback.onPlayersError(error);
                    }
                });
            }
        });
    }
    
    /**
     * Установка данных плееров
     */
    public void setPlayersData(List<EpisodeResponse.PlayerData> players) {
        Log.d(TAG, "=== setPlayersData START ===");
        Log.d(TAG, "Input players count: " + (players != null ? players.size() : "null"));
        
        if (players == null || players.isEmpty()) {
            Log.e(TAG, "ERROR: players list is null or empty!");
            return;
        }
        
        // Store all players data
        allPlayers.clear();
        allPlayers.addAll(players);
        Log.d(TAG, "allPlayers size after adding: " + allPlayers.size());
        
        // Debug: print all players
        for (int i = 0; i < players.size(); i++) {
            EpisodeResponse.PlayerData p = players.get(i);
            Log.d(TAG, "  [" + i + "] Player: " + (p.getPlayer() != null ? p.getPlayer() : "null") + 
                  ", Team: " + (p.getTeam() != null ? p.getTeam().getName() : "null"));
        }
        
        // Separate players by type (case-insensitive)
        animelibPlayers = allPlayers.stream()
                .filter(p -> p.getPlayer() != null && "animelib".equalsIgnoreCase(p.getPlayer()))
                .collect(Collectors.toList());

        kodikPlayers = allPlayers.stream()
                .filter(p -> p.getPlayer() != null && "kodik".equalsIgnoreCase(p.getPlayer()))
                .collect(Collectors.toList());
        
        Log.d(TAG, "After filtering - AnimeLib: " + animelibPlayers.size() + ", Kodik: " + kodikPlayers.size());
        
        // Update menu with players
        updateMenuWithData();
        
        // Try to auto-select preferred player if available
        EpisodeResponse.PlayerData preferredPlayer = findPreferredPlayer();
        if (preferredPlayer != null) {
            Log.d(TAG, "Auto-selecting preferred player: " + preferredPlayer.getPlayer());
            // Добавляем небольшую задержку чтобы пользователь мог увидеть меню
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                onPlayerSelected(preferredPlayer);
            }, 500);
        } else if (!allPlayers.isEmpty()) {
            Log.d(TAG, "No preferred player found, auto-selecting first available player");
            EpisodeResponse.PlayerData firstPlayer = allPlayers.get(0);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                onPlayerSelected(firstPlayer);
            }, 500);
        } else {
            // Show menu for user to select player manually
            Log.d(TAG, "No players found, showing menu");
            showMenu();
        }
        
        Log.d(TAG, "=== setPlayersData END ===");
    }
    
    /**
     * Устанавливает данные плееров БЕЗ показа меню
     * Используется при автовыборе через сохраненные предпочтения
     */
    public void setPlayersDataSilent(List<EpisodeResponse.PlayerData> players) {
        Log.d(TAG, "Setting players data silently (no menu): " + players.size() + " players");
        
        // Store all players data
        allPlayers.clear();
        allPlayers.addAll(players);
        
        // Debug: print all players
        for (EpisodeResponse.PlayerData p : players) {
            Log.d(TAG, "  Player: " + p.getPlayer() + ", Team: " + 
                  (p.getTeam() != null ? p.getTeam().getName() : "null"));
        }
        
        // Separate players by type (case-insensitive)
        animelibPlayers = allPlayers.stream()
                .filter(p -> p.getPlayer() != null && "animelib".equalsIgnoreCase(p.getPlayer()))
                .collect(Collectors.toList());

        kodikPlayers = allPlayers.stream()
                .filter(p -> p.getPlayer() != null && "kodik".equalsIgnoreCase(p.getPlayer()))
                .collect(Collectors.toList());
        
        Log.d(TAG, "AnimeLib players: " + animelibPlayers.size() + ", Kodik players: " + kodikPlayers.size());
        
        // Update menu with players (but don't show it)
        updateMenuWithData();
    }
    
    /**
     * Обновление меню с данными плееров
     */
    public void updateMenuWithData() {
        if (voiceoverBottomSheet != null && voiceoverBottomSheet.isShowing()) {
            voiceoverBottomSheet.updateData(animelibPlayers, kodikPlayers, currentPlayerData);
        }

        boolean hasAnimelib = animelibPlayers != null && !animelibPlayers.isEmpty();
        boolean hasKodik = kodikPlayers != null && !kodikPlayers.isEmpty();

        if (currentPlayerData != null && currentPlayerData.getPlayer() != null) {
            String p = currentPlayerData.getPlayer().toLowerCase();
            if ("kodik".equals(p) && hasKodik) {
                sidePanelActiveTab = "kodik";
            } else if ("animelib".equals(p) && hasAnimelib) {
                sidePanelActiveTab = "animelib";
            } else if (hasAnimelib) {
                sidePanelActiveTab = "animelib";
            } else if (hasKodik) {
                sidePanelActiveTab = "kodik";
            } else {
                sidePanelActiveTab = "animelib";
            }
        } else if (!hasAnimelib && hasKodik) {
            sidePanelActiveTab = "kodik";
        } else {
            sidePanelActiveTab = "animelib";
        }

        updateSidePanelTabContent();
    }

    private void forceSelectTabAndPage(int index) {
        if (sidePanelViewPager == null) return;

        sidePanelViewPager.setCurrentItem(index, false);

        View child = sidePanelViewPager.getChildAt(0);
        if (child instanceof androidx.recyclerview.widget.RecyclerView) {
            androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) child;
            androidx.recyclerview.widget.RecyclerView.LayoutManager lm = rv.getLayoutManager();
            if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                ((androidx.recyclerview.widget.LinearLayoutManager) lm).scrollToPositionWithOffset(index, 0);
            } else {
                rv.scrollToPosition(index);
            }
        }

        if (tabLayout != null) {
            TabLayout.Tab tab = tabLayout.getTabAt(index);
            if (tab != null && !tab.isSelected()) {
                tab.select();
            }
            tabLayout.setScrollPosition(index, 0f, true);
        }
    }

    private void updateSidePanelTabContent() {
        if (sidePanelTabsAdapter != null) {
            sidePanelTabsAdapter.updateData(animelibPlayers, kodikPlayers, currentPlayerData);
        }

        if (sidePanelViewPager != null) {
            int selectedIndex = "kodik".equals(sidePanelActiveTab) ? 1 : 0;

            if (!isMenuVisible) {
                sidePanelViewPager.setUserInputEnabled(false);
            } else {
                sidePanelViewPager.setUserInputEnabled(true);
            }

            forceSelectTabAndPage(selectedIndex);

            sidePanelViewPager.post(() -> {
                forceSelectTabAndPage(selectedIndex);
            });
        }
    }

    private List<EpisodeResponse.PlayerData> getCurrentListForSidePanelTab() {
        if ("kodik".equals(sidePanelActiveTab)) {
            return kodikPlayers != null ? kodikPlayers : new ArrayList<>();
        }
        return animelibPlayers != null ? animelibPlayers : new ArrayList<>();
    }
    
    /**
     * Поиск предпочитаемого плеера
     */
    private EpisodeResponse.PlayerData findPreferredPlayer() {
        if (preferredPlayerType == null) {
            return null;
        }

        // Try to find exact match for preferred player
        if ("animelib".equals(preferredPlayerType) && preferredAnimelibPlayer != null) {
            for (EpisodeResponse.PlayerData player : animelibPlayers) {
                if (isSamePlayer(preferredAnimelibPlayer, player)) {
                    return player;
                }
            }
            // If exact match not found, return first available AnimeLib player
            return animelibPlayers.isEmpty() ? null : animelibPlayers.get(0);
        } else if ("kodik".equals(preferredPlayerType) && preferredKodikPlayer != null) {
            for (EpisodeResponse.PlayerData player : kodikPlayers) {
                if (isSamePlayer(preferredKodikPlayer, player)) {
                    return player;
                }
            }
            // If exact match not found, return first available Kodik player
            return kodikPlayers.isEmpty() ? null : kodikPlayers.get(0);
        }

        return null;
    }
    
    /**
     * Сравнение плееров
     */
    private boolean isSamePlayer(EpisodeResponse.PlayerData player1, EpisodeResponse.PlayerData player2) {
        if (player1 == null || player2 == null) return false;
        
        // Compare by team name and player type
        boolean sameTeam = (player1.getTeam() != null && player2.getTeam() != null) ?
                player1.getTeam().getName().equals(player2.getTeam().getName()) :
                (player1.getTeam() == null && player2.getTeam() == null);
        
        boolean samePlayer = (player1.getPlayer() != null && player2.getPlayer() != null) ?
                player1.getPlayer().equalsIgnoreCase(player2.getPlayer()) :
                (player1.getPlayer() == null && player2.getPlayer() == null);
        
        return sameTeam && samePlayer;
    }
    
    public void setCurrentKodikResponse(com.example.animelib.models.KodikResponse response) {
        this.currentKodikResponse = response;
    }

    /**
     * Получение доступных качеств для текущего плеера
     */
    public List<String> getAvailableQualities() {
        List<String> qualities = new ArrayList<>();

        if (currentPlayerData == null) {
            return qualities;
        }

        if ("animelib".equalsIgnoreCase(currentPlayerData.getPlayer())) {
            // AnimeLib qualities are in video.quality array
            if (currentPlayerData.getVideo() != null && currentPlayerData.getVideo().getQuality() != null) {
                List<EpisodeResponse.QualityData> qList = new ArrayList<>(currentPlayerData.getVideo().getQuality());
                qList.sort((q1, q2) -> Integer.compare(q2.getQuality(), q1.getQuality())); // Descending
                for (EpisodeResponse.QualityData qualityData : qList) {
                    int res = qualityData.getQuality();
                    if (res == 2160 && !enable4K) {
                        Log.d(TAG, "Skipping 4K quality (not enabled)");
                        continue;
                    }
                    String qStr = res + "p";
                    if (!qualities.contains(qStr)) {
                        qualities.add(qStr);
                    }
                }
            }
        } else if ("kodik".equalsIgnoreCase(currentPlayerData.getPlayer())) {
            if (currentKodikResponse != null && currentKodikResponse.getData() != null && !currentKodikResponse.getData().isEmpty()) {
                List<String> rawKeys = new ArrayList<>(currentKodikResponse.getData().keySet());
                rawKeys.sort((k1, k2) -> Integer.compare(
                        com.example.animelib.util.AutoQualityHelper.extractResolution(k2),
                        com.example.animelib.util.AutoQualityHelper.extractResolution(k1)
                ));
                for (String key : rawKeys) {
                    int res = com.example.animelib.util.AutoQualityHelper.extractResolution(key);
                    if (res == 2160 && !enable4K) continue;
                    String qStr = res > 0 ? res + "p" : key;
                    if (!qualities.contains(qStr)) {
                        qualities.add(qStr);
                    }
                }
            }
            if (qualities.isEmpty()) {
                qualities.add("720p");
                qualities.add("480p");
                qualities.add("360p");
            }
        }

        return qualities;
    }
    
    /**
     * Установка настройки 4K
     */
    public void setEnable4K(boolean enable4K) {
        this.enable4K = enable4K;
        Log.d(TAG, "4K setting updated: " + enable4K);
    }
    
    /**
     * Показать индикатор загрузки
     */
    private void showLoading() {
        isLoading = true;
        if (menuLoadingOverlay != null) {
            menuLoadingOverlay.setVisibility(View.VISIBLE);
        }
        if (menuLoadingIndicator != null) {
            menuLoadingIndicator.setVisibility(View.VISIBLE);
        }
        if (voiceoverBottomSheet != null && voiceoverBottomSheet.isShowing()) {
            voiceoverBottomSheet.setLoading(true);
        }
    }
    
    /**
     * Скрыть индикатор загрузки
     */
    private void hideLoading() {
        isLoading = false;
        if (menuLoadingOverlay != null) {
            menuLoadingOverlay.setVisibility(View.GONE);
        }
        if (menuLoadingIndicator != null) {
            menuLoadingIndicator.setVisibility(View.GONE);
        }
        if (voiceoverBottomSheet != null && voiceoverBottomSheet.isShowing()) {
            voiceoverBottomSheet.setLoading(false);
        }
    }
    
    /**
     * Скрытие всех UI элементов плееров (для PiP режима)
     */
    public void hideAllPlayersUI() {
        if (context instanceof com.example.animelib.VideoPlayerActivity) {
            ((com.example.animelib.VideoPlayerActivity) context).closeMenuPanel();
        }
        if (menuOverlay != null) {
            menuOverlay.setVisibility(View.GONE);
        }
        isMenuVisible = false;
    }
    
    /**
     * Показ всех UI элементов плееров (выход из PiP режима)
     */
    public void showAllPlayersUI() {
        // Menu is shown only when user requests it
    }
    
    // Getters
    public List<EpisodeResponse.PlayerData> getAllPlayers() {
        return allPlayers;
    }
    
    public List<EpisodeResponse.PlayerData> getAnimelibPlayers() {
        return animelibPlayers;
    }
    
    public List<EpisodeResponse.PlayerData> getKodikPlayers() {
        return kodikPlayers;
    }
    
    public EpisodeResponse.PlayerData getCurrentPlayerData() {
        return currentPlayerData;
    }
    
    public boolean isMenuVisible() {
        return isMenuVisible;
    }
    
    /**
     * Вызывается в начале drag жеста панели
     */
    public void onPanelDragStart() {
        if (sidePanelViewPager != null) {
            sidePanelViewPager.setUserInputEnabled(false);
        }
    }

    /**
     * Вызывается когда панель полностью открыта
     */
    public void onPanelOpened() {
        Log.d(TAG, "Panel opened");
        isMenuVisible = true;
        updateMenuWithData();
        int selectedIndex = "kodik".equals(sidePanelActiveTab) ? 1 : 0;
        forceSelectTabAndPage(selectedIndex);
        if (sidePanelViewPager != null) {
            sidePanelViewPager.setUserInputEnabled(true);
        }
        if (visibilityCallback != null) {
            visibilityCallback.onPlayersVisibilityChanged(true);
        }
    }

    /**
     * Вызывается когда панель закрывается через драг
     */
    public void onPanelClosedByDrag() {
        Log.d(TAG, "Panel closed by drag, updating isMenuVisible flag");
        isMenuVisible = false;
        if (sidePanelViewPager != null) {
            sidePanelViewPager.setUserInputEnabled(false);
        }
    }
    
    public String getPreferredPlayerType() {
        return preferredPlayerType;
    }
    
    public EpisodeResponse.PlayerData getPreferredAnimelibPlayer() {
        return preferredAnimelibPlayer;
    }
    
    public EpisodeResponse.PlayerData getPreferredKodikPlayer() {
        return preferredKodikPlayer;
    }
    
    // Setters
    public void setMenuWidth(float menuWidth) {
        this.menuWidth = menuWidth;
    }
    
    public void setCurrentPlayerData(EpisodeResponse.PlayerData currentPlayerData) {
        this.currentPlayerData = currentPlayerData;
        updateMenuWithData();
    }
    
    public void setPreferredPlayerType(String preferredPlayerType) {
        this.preferredPlayerType = preferredPlayerType;
    }
    
    public void setPreferredAnimelibPlayer(EpisodeResponse.PlayerData preferredAnimelibPlayer) {
        this.preferredAnimelibPlayer = preferredAnimelibPlayer;
    }
    
    public void setPreferredKodikPlayer(EpisodeResponse.PlayerData preferredKodikPlayer) {
        this.preferredKodikPlayer = preferredKodikPlayer;
    }
    
    // Callback setters
    public void setPlayerSelectionCallback(PlayerSelectionCallback callback) {
        this.playerSelectionCallback = callback;
    }
    
    public void setVisibilityCallback(PlayersVisibilityCallback callback) {
        this.visibilityCallback = callback;
    }
    
    public void setDataCallback(PlayersDataCallback callback) {
        this.dataCallback = callback;
    }
    
    /**
     * Очистка ресурсов
     */
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

    public void cleanup() {
        allPlayers.clear();
        animelibPlayers.clear();
        kodikPlayers.clear();
        currentPlayerData = null;
        playerSelectionCallback = null;
        visibilityCallback = null;
        dataCallback = null;
    }
    
    /**
     * Завершает drag жест с решением открыть или закрыть панель плееров
     */
    public void completeDrag(boolean shouldOpen) {
        Log.d(TAG, "Complete players drag: shouldOpen=" + shouldOpen);
        
        if (shouldOpen) {
            isMenuVisible = true;
            updateMenuWithData();
            
            // Уведомить о изменении видимости
            if (visibilityCallback != null) {
                visibilityCallback.onPlayersVisibilityChanged(true);
            }
        } else {
            hideMenu();
        }
    }
    
    /**
     * Обновляет состояние после drag (вызывается после завершения анимации DraggableSidePanel)
     */
    public void updateDragState(boolean isOpen) {
        Log.d(TAG, "Update drag state: isOpen=" + isOpen);
        
        if (isOpen) {
            isMenuVisible = true;
            updateMenuWithData();
            
            if (visibilityCallback != null) {
                visibilityCallback.onPlayersVisibilityChanged(true);
            }
        } else {
            isMenuVisible = false;
            if (visibilityCallback != null) {
                visibilityCallback.onPlayersVisibilityChanged(false);
            }
        }
    }
}
