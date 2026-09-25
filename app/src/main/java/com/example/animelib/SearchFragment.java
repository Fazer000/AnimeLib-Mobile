package com.example.animelib;

import android.content.Context;
import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.adapters.SearchResultsAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.SearchResponse;
import com.google.android.material.tabs.TabLayout;

/**
 * Фрагмент быстрого поиска аниме в виде всплывающего алерт-окна
 */
public class SearchFragment extends Fragment {
    private static final String TAG = "SearchFragment";
    private static final long SEARCH_DELAY_MS = 350; // Быстрый и отзывчивый debounce
    
    private View searchRootLayout;
    private CardView searchCardContainer;
    private EditText searchInput;
    private ImageButton clearButton;
    private ImageButton closeButton;
    private TabLayout searchTabLayout;
    private RecyclerView searchResults;
    private LinearLayout emptyState;
    private LinearLayout noResultsState;
    private TextView noResultsTitle;
    private TextView noResultsSubtitle;
    private View loadingIndicator;
    
    private SearchResultsAdapter adapter;
    private ApiService apiService;
    private Handler searchHandler;
    private Runnable searchRunnable;
    private String currentQuery = "";
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search, container, false);
        
        apiService = new ApiService(requireContext());
        searchHandler = new Handler(Looper.getMainLooper());
        
        initializeViews(view);
        setupListeners();
        
        Log.d(TAG, "SearchFragment created");
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
        if (searchHandler != null && searchRunnable != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }
        closeKeyboard();
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
        searchInput = view.findViewById(R.id.searchInput);
        clearButton = view.findViewById(R.id.clearButton);
        closeButton = view.findViewById(R.id.closeButton);
        searchTabLayout = view.findViewById(R.id.searchTabLayout);
        searchResults = view.findViewById(R.id.searchResults);
        emptyState = view.findViewById(R.id.emptyState);
        noResultsState = view.findViewById(R.id.noResultsState);
        noResultsTitle = view.findViewById(R.id.noResultsTitle);
        noResultsSubtitle = view.findViewById(R.id.noResultsSubtitle);
        loadingIndicator = view.findViewById(R.id.loadingIndicator);
        
        // Setup RecyclerView
        adapter = new SearchResultsAdapter();
        adapter.setOnItemClickListener(new SearchResultsAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(SearchResponse.AnimeSearchItem item) {
                onAnimeItemClick(item);
            }
            
            @Override
            public void onItemLongClick(SearchResponse.AnimeSearchItem item) {
                onAnimeItemLongClick(item);
            }
        });
        searchResults.setAdapter(adapter);
        searchResults.setLayoutManager(new LinearLayoutManager(getContext()));
        searchResults.setVisibility(View.GONE);
        
        setupTabLayout();
    }
    
    private void setupTabLayout() {
        for (int i = 0; i < searchTabLayout.getTabCount(); i++) {
            TabLayout.Tab tab = searchTabLayout.getTabAt(i);
            if (tab != null && tab.view != null) {
                View tabTextView = tab.view.getChildAt(1);
                if (tabTextView instanceof TextView) {
                    ((TextView) tabTextView).setAllCaps(false);
                }
            }
        }
        
        searchTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                Log.d(TAG, "Tab selected: " + tab.getPosition());
            }
            
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }
            
            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
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
    
    private void performSearch(String query) {
        if (query.trim().isEmpty()) {
            showEmptyState();
            return;
        }
        
        Log.d(TAG, "Performing search for: " + query);
        showLoading();
        
        apiService.searchAnime(query, new ApiService.SearchCallback() {
            @Override
            public void onSearchResults(SearchResponse response) {
                if (getActivity() == null || !isAdded()) return;
                
                getActivity().runOnUiThread(() -> {
                    // Check if current search input still corresponds to this query
                    String activeInput = searchInput.getText().toString().trim();
                    if (!activeInput.isEmpty() && !activeInput.equalsIgnoreCase(query)) {
                        // Ignore outdated search response
                        return;
                    }
                    
                    if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                        adapter.setItems(response.getData());
                        showResults();
                        Log.d(TAG, "Search completed: " + response.getData().size() + " results");
                    } else {
                        adapter.clearItems();
                        showNoResults(query);
                        Log.d(TAG, "Search completed: no results for " + query);
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                if (getActivity() == null || !isAdded()) return;
                
                getActivity().runOnUiThread(() -> {
                    Log.e(TAG, "Search error: " + error);
                    com.example.animelib.util.CustomToast.showWarning(getContext(), "Ошибка поиска: " + error);
                    showNoResults(query);
                });
            }
        });
    }
    
    /**
     * Обычный клик - открывает страницу аниме в WebView
     */
    private void onAnimeItemClick(SearchResponse.AnimeSearchItem item) {
        Log.d(TAG, "Anime clicked: " + item.getRusName() + " (slug_url: " + item.getSlugUrl() + ")");
        
        closeKeyboard();
        String webViewUrl = "/ru/anime/" + item.getSlugUrl();
        
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
            
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).loadUrlInWebView(webViewUrl);
            }
        }
    }
    
    /**
     * Долгий клик - открывает VideoPlayerActivity
     */
    private void onAnimeItemLongClick(SearchResponse.AnimeSearchItem item) {
        Log.d(TAG, "Anime long clicked: " + item.getRusName() + " (slug_url: " + item.getSlugUrl() + ")");
        
        closeKeyboard();
        String animeUrl = "https://api.cdnlibs.org/api/anime/" + item.getSlugUrl();
        
        Intent intent = new Intent(getContext(), VideoPlayerActivity.class);
        intent.putExtra("anime_url", animeUrl);
        startActivity(intent);
        
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }
    
    private void showEmptyState() {
        if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
        if (noResultsState != null) noResultsState.setVisibility(View.GONE);
        if (searchResults != null) searchResults.setVisibility(View.GONE);
        if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);
    }
    
    private void showNoResults(String query) {
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
