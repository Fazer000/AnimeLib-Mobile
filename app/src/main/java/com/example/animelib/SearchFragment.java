package com.example.animelib;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.adapters.HorizontalRelatedTitlesAdapter;
import com.example.animelib.adapters.SearchResultsAdapter;
import com.example.animelib.api.ApiClientCore;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.SearchResponse;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Фрагмент быстрого поиска по сайтам (AnimeLib, MangaLib, RanobeLib) в виде всплывающего алерт-окна
 */
public class SearchFragment extends Fragment {
    private static final String TAG = "SearchFragment";
    private static final String ARG_INITIAL_SITE = "arg_initial_site";
    private static final long SEARCH_DELAY_MS = 350; // Быстрый и отзывчивый debounce

    public static final int TAB_ANIME = 0;
    public static final int TAB_MANGA = 1;
    public static final int TAB_RANOBE = 2;

    public static final int SITE_ID_MANGA = 1;
    public static final int SITE_ID_RANOBE = 3;

    private View searchRootLayout;
    private CardView searchCardContainer;
    private TextView searchHeaderSubtitle;
    private EditText searchInput;
    private ImageButton clearButton;
    private ImageButton closeButton;
    private TabLayout searchTabLayout;
    private RecyclerView searchResults;
    private LinearLayout emptyState;
    private TextView emptyStateTitle;
    private TextView emptyStateSubtitle;
    private View tipClickRow;
    private View tipPlayerRow;
    private LinearLayout noResultsState;
    private TextView noResultsTitle;
    private TextView noResultsSubtitle;
    private View loadingIndicator;

    private SearchResultsAdapter adapter;
    private ApiService apiService;
    private Handler searchHandler;
    private Runnable searchRunnable;
    private String currentQuery = "";
    private int currentTabPosition = TAB_ANIME;

    public static SearchFragment newInstance(String initialSiteKey) {
        SearchFragment fragment = new SearchFragment();
        Bundle args = new Bundle();
        args.putString(ARG_INITIAL_SITE, initialSiteKey);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search, container, false);

        apiService = new ApiService(requireContext());
        searchHandler = new Handler(Looper.getMainLooper());

        if (getArguments() != null) {
            String siteKey = getArguments().getString(ARG_INITIAL_SITE, "animelib");
            if ("mangalib".equalsIgnoreCase(siteKey)) {
                currentTabPosition = TAB_MANGA;
            } else if ("ranobelib".equalsIgnoreCase(siteKey)) {
                currentTabPosition = TAB_RANOBE;
            } else {
                currentTabPosition = TAB_ANIME;
            }
        }

        initializeViews(view);
        setupListeners();

        Log.d(TAG, "SearchFragment created, initialTab=" + currentTabPosition);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupBackPressHandler();
        openKeyboard();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (searchHandler != null) {
            searchHandler.removeCallbacksAndMessages(null);
        }
        closeKeyboard();
    }

    @Override
    public void onDestroy() {
        if (apiService != null) {
            apiService.shutdown();
            apiService = null;
        }
        super.onDestroy();
    }

    /**
     * Закрывает поиск и убирает клавиатуру
     */
    public void dismissSearch() {
        Log.d(TAG, "Dismissing search alert");
        closeKeyboard();
        if (getActivity() != null && isAdded()) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }

    /**
     * Настраивает обработку системной кнопки назад
     */
    private void setupBackPressHandler() {
        requireActivity().getOnBackPressedDispatcher().addCallback(
            getViewLifecycleOwner(),
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    dismissSearch();
                }
            }
        );
    }

    /**
     * Открывает клавиатуру и устанавливает фокус на поле поиска
     */
    private void openKeyboard() {
        if (searchInput != null) {
            searchInput.requestFocus();
            searchInput.postDelayed(() -> {
                if (getContext() == null) return;
                InputMethodManager imm = (InputMethodManager) requireContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
                    Log.d(TAG, "Keyboard opened");
                }
            }, 100);
        }
    }

    /**
     * Закрывает клавиатуру
     */
    private void closeKeyboard() {
        if (searchInput != null && getActivity() != null) {
            InputMethodManager imm = (InputMethodManager) requireActivity()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                Log.d(TAG, "Keyboard closed");
            }
        }
    }

    private void initializeViews(View view) {
        searchRootLayout = view.findViewById(R.id.searchRootLayout);
        searchCardContainer = view.findViewById(R.id.searchCardContainer);
        searchHeaderSubtitle = view.findViewById(R.id.searchHeaderSubtitle);
        searchInput = view.findViewById(R.id.searchInput);
        clearButton = view.findViewById(R.id.clearButton);
        closeButton = view.findViewById(R.id.closeButton);
        searchTabLayout = view.findViewById(R.id.searchTabLayout);
        searchResults = view.findViewById(R.id.searchResults);
        emptyState = view.findViewById(R.id.emptyState);
        emptyStateTitle = view.findViewById(R.id.emptyStateTitle);
        emptyStateSubtitle = view.findViewById(R.id.emptyStateSubtitle);
        tipClickRow = view.findViewById(R.id.tipClickRow);
        tipPlayerRow = view.findViewById(R.id.tipPlayerRow);
        noResultsState = view.findViewById(R.id.noResultsState);
        noResultsTitle = view.findViewById(R.id.noResultsTitle);
        noResultsSubtitle = view.findViewById(R.id.noResultsSubtitle);
        loadingIndicator = view.findViewById(R.id.loadingIndicator);

        // Setup RecyclerView
        adapter = new SearchResultsAdapter();
        adapter.setOnItemClickListener(new SearchResultsAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(SearchResponse.AnimeSearchItem item) {
                onSearchItemClick(item);
            }

            @Override
            public void onItemLongClick(SearchResponse.AnimeSearchItem item) {
                onSearchItemLongClick(item);
            }
        });
        searchResults.setAdapter(adapter);
        searchResults.setLayoutManager(new LinearLayoutManager(getContext()));
        searchResults.setVisibility(View.GONE);

        setupTabLayout();
        updateHintAndEmptyStateForTab(currentTabPosition);
    }

    private static final int[] INACTIVE_TAB_LOGOS = new int[]{
            R.drawable.ic_site_animelib,
            R.drawable.ic_site_mangalib,
            R.drawable.ic_site_ranobelib
    };

    private static final int[] ACTIVE_TAB_LOGOS = new int[]{
            R.drawable.ic_site_animelib_tab_active,
            R.drawable.ic_site_mangalib_tab_active,
            R.drawable.ic_site_ranobelib_tab_active
    };

    private void setupTabLayout() {
        if (searchTabLayout == null) return;
        searchTabLayout.removeAllTabs();

        String[] titles = new String[]{"AnimeLib", "MangaLib", "RanobeLib"};

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < titles.length; i++) {
            TabLayout.Tab tab = searchTabLayout.newTab();
            View customTabView = inflater.inflate(R.layout.item_search_site_tab, searchTabLayout, false);
            ImageView logoView = customTabView.findViewById(R.id.tabSiteLogo);
            TextView titleView = customTabView.findViewById(R.id.tabSiteTitle);

            if (logoView != null) {
                logoView.setImageResource(i == currentTabPosition ? ACTIVE_TAB_LOGOS[i] : INACTIVE_TAB_LOGOS[i]);
            }
            if (titleView != null) {
                titleView.setText(titles[i]);
            }
            tab.setCustomView(customTabView);
            searchTabLayout.addTab(tab, i == currentTabPosition);

            if (tab.view != null) {
                tab.view.setClipToOutline(true);
            }
        }

        updateTabVisualState(currentTabPosition);

        searchTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                Log.d(TAG, "Tab selected: " + pos);
                currentTabPosition = pos;
                updateTabVisualState(pos);
                updateHintAndEmptyStateForTab(pos);

                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }

                String query = searchInput != null ? searchInput.getText().toString().trim() : "";
                currentQuery = query;
                if (!query.isEmpty()) {
                    performSearch(query);
                } else {
                    adapter.clearItems();
                    showEmptyState();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void updateTabVisualState(int selectedPos) {
        if (searchTabLayout == null || getContext() == null) return;
        int selectedColor = ContextCompat.getColor(getContext(), R.color.white_color);
        int unselectedColor = ContextCompat.getColor(getContext(), R.color.primary_text_color);

        for (int i = 0; i < searchTabLayout.getTabCount(); i++) {
            TabLayout.Tab tab = searchTabLayout.getTabAt(i);
            if (tab != null && tab.getCustomView() != null) {
                boolean isSelected = (i == selectedPos);
                TextView titleView = tab.getCustomView().findViewById(R.id.tabSiteTitle);
                if (titleView != null) {
                    titleView.setTextColor(isSelected ? selectedColor : unselectedColor);
                }
                ImageView logoView = tab.getCustomView().findViewById(R.id.tabSiteLogo);
                if (logoView != null && i < ACTIVE_TAB_LOGOS.length) {
                    logoView.setImageResource(isSelected ? ACTIVE_TAB_LOGOS[i] : INACTIVE_TAB_LOGOS[i]);
                }
            }
        }
    }

    private String getDefaultHeaderSubtitleForTab(int tabPosition) {
        if (tabPosition == TAB_MANGA) {
            return "Каталог манги • MangaLib";
        } else if (tabPosition == TAB_RANOBE) {
            return "Каталог ранобэ • RanobeLib";
        } else {
            return "Каталог аниме • AnimeLib";
        }
    }

    private void updateHintAndEmptyStateForTab(int tabPosition) {
        if (searchHeaderSubtitle != null) {
            searchHeaderSubtitle.setText(getDefaultHeaderSubtitleForTab(tabPosition));
        }

        if (searchInput != null) {
            if (tabPosition == TAB_MANGA) {
                searchInput.setHint("Поиск манги...");
            } else if (tabPosition == TAB_RANOBE) {
                searchInput.setHint("Поиск ранобэ...");
            } else {
                searchInput.setHint("Поиск аниме...");
            }
        }

        if (emptyStateTitle != null) {
            if (tabPosition == TAB_MANGA) {
                emptyStateTitle.setText("Поиск по MangaLib");
            } else if (tabPosition == TAB_RANOBE) {
                emptyStateTitle.setText("Поиск по RanobeLib");
            } else {
                emptyStateTitle.setText("Поиск по AnimeLib");
            }
        }

        if (emptyStateSubtitle != null) {
            if (tabPosition == TAB_MANGA) {
                emptyStateSubtitle.setText("Введите название манги на русском или английском языке");
            } else if (tabPosition == TAB_RANOBE) {
                emptyStateSubtitle.setText("Введите название ранобэ на русском или английском языке");
            } else {
                emptyStateSubtitle.setText("Введите название аниме на русском или английском языке");
            }
        }

        boolean isAnimeTab = (tabPosition == TAB_ANIME);
        if (tipPlayerRow != null) {
            tipPlayerRow.setVisibility(isAnimeTab ? View.VISIBLE : View.GONE);
        }
        if (tipClickRow != null) {
            tipClickRow.setBackgroundResource(isAnimeTab ? R.drawable.bg_m3_item_top : R.drawable.bg_m3_item_single);
        }
    }

    private void setupListeners() {
        // Клик по затемненному фону вокруг алерта — закрывает окно
        if (searchRootLayout != null) {
            searchRootLayout.setOnClickListener(v -> dismissSearch());
        }

        // Клик внутри самой карточки алерта не закрывает его
        if (searchCardContainer != null) {
            searchCardContainer.setOnClickListener(v -> {});
        }

        // Close button inside alert
        closeButton.setOnClickListener(v -> dismissSearch());

        // Clear button
        clearButton.setOnClickListener(v -> {
            searchInput.setText("");
            searchInput.requestFocus();
            adapter.clearItems();
            currentQuery = "";
            showEmptyState();
        });

        // Search input text watcher with debounce
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);

                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }

                String query = s.toString().trim();
                currentQuery = query;

                if (!query.isEmpty()) {
                    searchRunnable = () -> performSearch(query);
                    searchHandler.postDelayed(searchRunnable, SEARCH_DELAY_MS);
                } else {
                    adapter.clearItems();
                    showEmptyState();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // Search action on keyboard (IME search)
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = searchInput.getText().toString().trim();
                if (!query.isEmpty()) {
                    if (searchRunnable != null) {
                        searchHandler.removeCallbacks(searchRunnable);
                    }
                    performSearch(query);
                }
                return true;
            }
            return false;
        });
    }

    public static List<SearchResponse.AnimeSearchItem> filterItemsForTab(
            List<SearchResponse.AnimeSearchItem> rawItems, int tabPosition) {
        List<SearchResponse.AnimeSearchItem> filtered = new ArrayList<>();
        if (rawItems == null || rawItems.isEmpty()) {
            return filtered;
        }
        if (tabPosition == TAB_ANIME) {
            filtered.addAll(rawItems);
            return filtered;
        }
        int expectedSiteId = (tabPosition == TAB_RANOBE) ? SITE_ID_RANOBE : SITE_ID_MANGA;
        for (SearchResponse.AnimeSearchItem item : rawItems) {
            if (item != null && item.getSite() == expectedSiteId) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private void performSearch(String query) {
        if (query.trim().isEmpty()) {
            showEmptyState();
            return;
        }

        final int requestedTab = currentTabPosition;
        Log.d(TAG, "Performing search for: " + query + " (tab=" + requestedTab + ")");
        showLoading();

        ApiService.SearchCallback callback = new ApiService.SearchCallback() {
            @Override
            public void onSearchResults(SearchResponse response) {
                if (getActivity() == null || !isAdded()) return;

                getActivity().runOnUiThread(() -> {
                    if (requestedTab != currentTabPosition) {
                        return;
                    }
                    String activeInput = searchInput.getText().toString().trim();
                    if (!activeInput.isEmpty() && !activeInput.equalsIgnoreCase(query)) {
                        return;
                    }

                    List<SearchResponse.AnimeSearchItem> rawList = response != null ? response.getData() : null;
                    List<SearchResponse.AnimeSearchItem> filtered = filterItemsForTab(rawList, requestedTab);

                    if (!filtered.isEmpty()) {
                        adapter.setItems(filtered);
                        if (searchResults != null) {
                            searchResults.scrollToPosition(0);
                        }
                        if (searchHeaderSubtitle != null) {
                            searchHeaderSubtitle.setText(getDefaultHeaderSubtitleForTab(requestedTab) + " • Найдено: " + filtered.size());
                        }
                        showResults();
                        Log.d(TAG, "Search completed: " + filtered.size() + " results for tab " + requestedTab);
                    } else {
                        adapter.clearItems();
                        showNoResults(query);
                        Log.d(TAG, "Search completed: no results for " + query + " on tab " + requestedTab);
                    }
                });
            }

            @Override
            public void onError(String error) {
                if (getActivity() == null || !isAdded()) return;

                getActivity().runOnUiThread(() -> {
                    if (requestedTab != currentTabPosition) {
                        return;
                    }
                    Log.e(TAG, "Search error: " + error);
                    com.example.animelib.util.CustomToast.showWarning(getContext(), "Ошибка поиска: " + error);
                    showNoResults(query);
                });
            }
        };

        if (requestedTab == TAB_ANIME) {
            apiService.searchAnime(query, callback);
        } else {
            apiService.searchMangaAndRanobe(query, callback);
        }
    }

    /**
     * Обычный клик - открывает страницу тайтла (аниме / манги / ранобэ) в WebView
     */
    private void onSearchItemClick(SearchResponse.AnimeSearchItem item) {
        if (item == null) return;
        String slugUrl = item.getSlugUrl();
        if (slugUrl == null || slugUrl.trim().isEmpty()) {
            slugUrl = item.getSlug();
        }
        Log.d(TAG, "Item clicked: " + item.getRusName() + " (slug_url: " + slugUrl + ", site: " + item.getSite() + ", tab: " + currentTabPosition + ")");

        closeKeyboard();

        String webViewUrl;
        if (currentTabPosition == TAB_MANGA || item.getSite() == SITE_ID_MANGA) {
            webViewUrl = HorizontalRelatedTitlesAdapter.buildWebUrl(slugUrl, "manga", SITE_ID_MANGA, getContext());
        } else if (currentTabPosition == TAB_RANOBE || item.getSite() == SITE_ID_RANOBE) {
            webViewUrl = HorizontalRelatedTitlesAdapter.buildWebUrl(slugUrl, "ranobe", SITE_ID_RANOBE, getContext());
        } else {
            webViewUrl = "/ru/anime/" + slugUrl;
        }

        if (getActivity() != null && webViewUrl != null) {
            getActivity().getSupportFragmentManager().popBackStack();

            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).loadUrlInWebView(webViewUrl);
            }
        }
    }

    /**
     * Долгий клик - открывает VideoPlayerActivity для аниме, либо страницу тайтла для манги/ранобэ
     */
    private void onSearchItemLongClick(SearchResponse.AnimeSearchItem item) {
        if (item == null) return;
        if (currentTabPosition != TAB_ANIME || item.getSite() == SITE_ID_MANGA || item.getSite() == SITE_ID_RANOBE) {
            onSearchItemClick(item);
            return;
        }

        Log.d(TAG, "Anime long clicked: " + item.getRusName() + " (slug_url: " + item.getSlugUrl() + ")");

        closeKeyboard();
        String animeUrl = ApiClientCore.API_BASE_HAPI + "/anime/" + item.getSlugUrl();

        if (getContext() != null) {
            VideoPlayerActivity.startFromAnimePage(getContext(), animeUrl);
        }

        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }

    private void showEmptyState() {
        if (searchHeaderSubtitle != null) {
            searchHeaderSubtitle.setText(getDefaultHeaderSubtitleForTab(currentTabPosition));
        }
        if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
        if (noResultsState != null) noResultsState.setVisibility(View.GONE);
        if (searchResults != null) searchResults.setVisibility(View.GONE);
        if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);
    }

    private void showNoResults(String query) {
        if (searchHeaderSubtitle != null) {
            searchHeaderSubtitle.setText(getDefaultHeaderSubtitleForTab(currentTabPosition) + " • Ничего не найдено");
        }
        if (emptyState != null) emptyState.setVisibility(View.GONE);
        if (noResultsState != null) {
            noResultsState.setVisibility(View.VISIBLE);
            if (noResultsSubtitle != null && query != null && !query.isEmpty()) {
                noResultsSubtitle.setText("По запросу «" + query + "» ничего не найдено.\nПопробуйте изменить формулировку.");
            }
        }
        if (searchResults != null) searchResults.setVisibility(View.GONE);
        if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);
    }

    private void showLoading() {
        if (emptyState != null) emptyState.setVisibility(View.GONE);
        if (noResultsState != null) noResultsState.setVisibility(View.GONE);
        if (searchResults != null) searchResults.setVisibility(View.GONE);
        if (loadingIndicator != null) loadingIndicator.setVisibility(View.VISIBLE);
    }

    private void showResults() {
        if (emptyState != null) emptyState.setVisibility(View.GONE);
        if (noResultsState != null) noResultsState.setVisibility(View.GONE);
        if (searchResults != null) searchResults.setVisibility(View.VISIBLE);
        if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);
    }
}
