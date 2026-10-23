package com.example.animelib.controllers;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.adapters.FranchiseChronologyAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.FranchiseResponse;
import com.example.animelib.ui.FranchiseChronologyBottomSheet;
import com.example.animelib.ui.FranchiseMapBottomSheet;
import com.example.animelib.ui.TitleWebViewBottomSheet;
import com.example.animelib.util.CustomToast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Контроллер для управления блоком хронологии аниме франшизы в плеере
 */
public class PlayerChronologyController {

    private static final String TAG = "PlayerChronologyCtrl";

    public interface ChronologyCallback {
        Context getContext();
        ApiService getApiService();
        void safeRunOnUiThread(Runnable runnable);
        PlayerDialogsController getPlayerDialogsController();
        boolean isOfflineMode();
    }

    private final ChronologyCallback callback;

    private View portraitChronologyContainer;
    private TextView tvPortraitChronologyCount;
    private View btnChronologyFilterDropdown;
    private TextView tvChronologyFilterText;
    private View btnOpenChronologyMap;
    private View portraitChronologyLoadingIndicator;
    private RecyclerView portraitChronologyRecyclerView;

    private FranchiseChronologyAdapter adapter;
    private FranchiseResponse currentFranchiseResponse;
    private Map<Long, String> currentRelationsMap;
    private List<FranchiseResponse.NodeItem> allSortedChronologicalNodes = new ArrayList<>();
    private long currentShikimoriId = -1;

    private String currentFilter = "all";       // "all", "no_fillers", "main_only"
    private String currentSort = "chrono";     // "chrono", "newest"

    public PlayerChronologyController(ChronologyCallback callback) {
        this.callback = callback;
    }

    public void initViews(View rootView) {
        if (rootView == null) return;

        portraitChronologyContainer = rootView.findViewById(R.id.portraitChronologyContainer);
        tvPortraitChronologyCount = rootView.findViewById(R.id.tvPortraitChronologyCount);
        btnChronologyFilterDropdown = rootView.findViewById(R.id.btnChronologyFilterDropdown);
        tvChronologyFilterText = rootView.findViewById(R.id.tvChronologyFilterText);
        btnOpenChronologyMap = rootView.findViewById(R.id.btnOpenChronologyMap);
        portraitChronologyLoadingIndicator = rootView.findViewById(R.id.portraitChronologyLoadingIndicator);
        portraitChronologyRecyclerView = rootView.findViewById(R.id.portraitChronologyRecyclerView);

        Context context = callback != null ? callback.getContext() : null;
        if (context != null && portraitChronologyRecyclerView != null) {
            portraitChronologyRecyclerView.setLayoutManager(
                    new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            );
            adapter = new FranchiseChronologyAdapter(this::onNodeSelected, false);
            portraitChronologyRecyclerView.setAdapter(adapter);
        }

        if (btnChronologyFilterDropdown != null) {
            btnChronologyFilterDropdown.setOnClickListener(v -> showFilterPopupWindow(btnChronologyFilterDropdown));
        }

        if (btnOpenChronologyMap != null) {
            btnOpenChronologyMap.setOnClickListener(v -> openChronologyMapSheet());
        }
    }

    public void loadChronologyForAnime(AnimeInfoResponse animeInfo) {
        if (callback != null && callback.isOfflineMode()) {
            if (portraitChronologyContainer != null) portraitChronologyContainer.setVisibility(View.GONE);
            return;
        }

        if (animeInfo == null || animeInfo.getData() == null) {
            Log.w(TAG, "animeInfo is null, cannot load chronology");
            if (portraitChronologyContainer != null) portraitChronologyContainer.setVisibility(View.GONE);
            return;
        }

        String shikimoriHref = animeInfo.getData().getShikimori_href();
        long shikimoriId = ApiService.extractShikimoriId(shikimoriHref);
        this.currentShikimoriId = shikimoriId;

        if (shikimoriId <= 0) {
            Log.w(TAG, "No valid Shikimori ID found in shikimori_href: " + shikimoriHref);
            if (portraitChronologyContainer != null) portraitChronologyContainer.setVisibility(View.GONE);
            return;
        }

        Log.d(TAG, "Loading franchise chronology for Shikimori ID: " + shikimoriId);

        if (portraitChronologyContainer != null) {
            portraitChronologyContainer.setVisibility(View.VISIBLE);
        }
        if (portraitChronologyLoadingIndicator != null) {
            portraitChronologyLoadingIndicator.setVisibility(View.VISIBLE);
        }
        if (portraitChronologyRecyclerView != null) {
            portraitChronologyRecyclerView.setVisibility(View.GONE);
        }

        ApiService apiService = callback != null ? callback.getApiService() : null;
        if (apiService == null) {
            if (portraitChronologyContainer != null) portraitChronologyContainer.setVisibility(View.GONE);
            return;
        }

        apiService.getFranchise(shikimoriId, new ApiService.FranchiseCallback() {
            @Override
            public void onFranchiseReceived(FranchiseResponse response) {
                if (callback != null) {
                    callback.safeRunOnUiThread(() -> displayFranchiseChronology(response));
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to load franchise: " + error);
                if (callback != null) {
                    callback.safeRunOnUiThread(() -> {
                        if (portraitChronologyLoadingIndicator != null) {
                            portraitChronologyLoadingIndicator.setVisibility(View.GONE);
                        }
                        if (portraitChronologyContainer != null) {
                            portraitChronologyContainer.setVisibility(View.GONE);
                        }
                    });
                }
            }
        });
    }

    private void displayFranchiseChronology(FranchiseResponse response) {
        if (portraitChronologyLoadingIndicator != null) {
            portraitChronologyLoadingIndicator.setVisibility(View.GONE);
        }

        if (response == null || response.getNodes() == null || response.getNodes().isEmpty()) {
            Log.d(TAG, "Franchise response has no nodes");
            if (portraitChronologyContainer != null) {
                portraitChronologyContainer.setVisibility(View.GONE);
            }
            return;
        }

        this.currentFranchiseResponse = response;
        this.currentRelationsMap = FranchiseChronologyBottomSheet.buildRelationsMap(response);
        this.allSortedChronologicalNodes = FranchiseChronologyBottomSheet.sortNodesChronologically(response);

        applyFilterAndSort();

        if (portraitChronologyRecyclerView != null) {
            portraitChronologyRecyclerView.setVisibility(View.VISIBLE);
        }

        if (portraitChronologyContainer != null) {
            portraitChronologyContainer.setVisibility(View.VISIBLE);
        }
    }

    private void applyFilterAndSort() {
        if (currentFranchiseResponse == null || allSortedChronologicalNodes == null) return;

        List<FranchiseResponse.NodeItem> result = new ArrayList<>();

        for (int i = 0; i < allSortedChronologicalNodes.size(); i++) {
            FranchiseResponse.NodeItem node = allSortedChronologicalNodes.get(i);
            String rel = currentRelationsMap != null ? currentRelationsMap.get(node.getId()) : null;

            if ("no_fillers".equals(currentFilter)) {
                if (isFillerOrRecap(node, rel)) {
                    continue;
                }
            } else if ("main_only".equals(currentFilter)) {
                if (!isMainStory(node, rel, i)) {
                    continue;
                }
            }
            result.add(node);
        }

        // If filtering filtered out everything, fall back to all sorted nodes
        if (result.isEmpty()) {
            result.addAll(allSortedChronologicalNodes);
        }

        if ("newest".equals(currentSort)) {
            List<FranchiseResponse.NodeItem> reversed = new ArrayList<>(result);
            Collections.reverse(reversed);
            result = reversed;
        }

        if (tvPortraitChronologyCount != null) {
            tvPortraitChronologyCount.setText(String.valueOf(result.size()));
            tvPortraitChronologyCount.setVisibility(View.VISIBLE);
        }

        long activeId = currentFranchiseResponse.getCurrentId() > 0
                ? currentFranchiseResponse.getCurrentId()
                : currentShikimoriId;

        if (adapter != null) {
            adapter.updateData(result, currentRelationsMap, activeId);
        }

        scrollToActiveTitle(result, activeId);
    }

    private boolean isFillerOrRecap(FranchiseResponse.NodeItem node, String relation) {
        if (node == null) return false;
        String kind = node.getKind() != null ? node.getKind().toLowerCase().trim() : "";
        if ("music".equals(kind) || "pv".equals(kind) || "клип".equals(kind) || "промо".equals(kind)) {
            return true;
        }

        if (relation != null) {
            String r = relation.toLowerCase().trim();
            if (r.contains("рекап") || r.contains("summary") || r.contains("компиляц")) {
                return true;
            }
        }

        String name = node.getName().toLowerCase();
        if (name.contains("рекап") || name.contains("recap") || name.contains("summary") || name.contains("фильм-рекап")) {
            return true;
        }

        return false;
    }

    private boolean isMainStory(FranchiseResponse.NodeItem node, String relation, int originalIndex) {
        if (node == null) return false;
        if (isFillerOrRecap(node, relation)) return false;

        // Root entry in chronological order is part of main story
        if (originalIndex == 0) return true;

        if (relation != null) {
            String r = relation.toLowerCase().trim();
            if (r.contains("sequel") || r.contains("prequel") ||
                    r.contains("parent") || r.contains("full") ||
                    r.contains("main") || r.contains("сиквел") ||
                    r.contains("приквел") || r.contains("основной")) {
                return true;
            }
        }

        return false;
    }

    private void scrollToActiveTitle(List<FranchiseResponse.NodeItem> list, long activeId) {
        if (list == null || list.isEmpty() || portraitChronologyRecyclerView == null || activeId <= 0) return;

        int targetIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == activeId) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex >= 0) {
            final int pos = targetIndex;
            portraitChronologyRecyclerView.post(() -> {
                RecyclerView.LayoutManager lm = portraitChronologyRecyclerView.getLayoutManager();
                if (lm instanceof LinearLayoutManager) {
                    float density = portraitChronologyRecyclerView.getContext().getResources().getDisplayMetrics().density;
                    int screenWidth = portraitChronologyRecyclerView.getWidth();
                    int cardWidth = (int) (330 * density);
                    int offset = Math.max((int) (16 * density), (screenWidth - cardWidth) / 2);
                    ((LinearLayoutManager) lm).scrollToPositionWithOffset(pos, offset);
                } else {
                    portraitChronologyRecyclerView.scrollToPosition(pos);
                }
            });
        }
    }

    private void showFilterPopupWindow(View anchorView) {
        if (anchorView == null || callback == null) return;
        Context context = callback.getContext();
        if (context == null) return;

        try {
            View popupView = android.view.LayoutInflater.from(context).inflate(R.layout.popup_chronology_filter, null);

            float density = context.getResources().getDisplayMetrics().density;
            int popupWidth = (int) (190 * density);

            PopupWindow popupWindow = new PopupWindow(
                    popupView,
                    popupWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
            );

            popupWindow.setOutsideTouchable(true);
            popupWindow.setFocusable(true);
            popupWindow.setElevation(12 * density);
            popupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

            TextView itemAll = popupView.findViewById(R.id.item_filter_all);
            TextView itemNoFillers = popupView.findViewById(R.id.item_filter_no_fillers);
            TextView itemMainOnly = popupView.findViewById(R.id.item_filter_main_only);
            TextView itemSortChrono = popupView.findViewById(R.id.item_sort_chrono);
            TextView itemSortNewest = popupView.findViewById(R.id.item_sort_newest);

            resetPopupItemStyle(context, itemAll);
            resetPopupItemStyle(context, itemNoFillers);
            resetPopupItemStyle(context, itemMainOnly);
            resetPopupItemStyle(context, itemSortChrono);
            resetPopupItemStyle(context, itemSortNewest);

            if ("newest".equals(currentSort)) {
                setPopupItemSelectedStyle(context, itemSortNewest);
            } else if ("chrono".equals(currentSort) && !"all".equals(currentFilter)) {
                setPopupItemSelectedStyle(context, itemSortChrono);
            }

            if ("no_fillers".equals(currentFilter)) {
                setPopupItemSelectedStyle(context, itemNoFillers);
            } else if ("main_only".equals(currentFilter)) {
                setPopupItemSelectedStyle(context, itemMainOnly);
            } else if ("all".equals(currentFilter) && "chrono".equals(currentSort)) {
                setPopupItemSelectedStyle(context, itemAll);
            }

            if (itemAll != null) {
                itemAll.setOnClickListener(v -> {
                    popupWindow.dismiss();
                    currentFilter = "all";
                    currentSort = "chrono";
                    if (tvChronologyFilterText != null) tvChronologyFilterText.setText("Все");
                    applyFilterAndSort();
                });
            }

            if (itemNoFillers != null) {
                itemNoFillers.setOnClickListener(v -> {
                    popupWindow.dismiss();
                    currentFilter = "no_fillers";
                    if (tvChronologyFilterText != null) tvChronologyFilterText.setText("Без филлеров");
                    applyFilterAndSort();
                });
            }

            if (itemMainOnly != null) {
                itemMainOnly.setOnClickListener(v -> {
                    popupWindow.dismiss();
                    currentFilter = "main_only";
                    if (tvChronologyFilterText != null) tvChronologyFilterText.setText("Основное");
                    applyFilterAndSort();
                });
            }

            if (itemSortChrono != null) {
                itemSortChrono.setOnClickListener(v -> {
                    popupWindow.dismiss();
                    currentSort = "chrono";
                    if (tvChronologyFilterText != null) {
                        if ("no_fillers".equals(currentFilter)) {
                            tvChronologyFilterText.setText("Без филлеров");
                        } else if ("main_only".equals(currentFilter)) {
                            tvChronologyFilterText.setText("Основное");
                        } else {
                            tvChronologyFilterText.setText("Хронология");
                        }
                    }
                    applyFilterAndSort();
                });
            }

            if (itemSortNewest != null) {
                itemSortNewest.setOnClickListener(v -> {
                    popupWindow.dismiss();
                    currentSort = "newest";
                    if (tvChronologyFilterText != null) tvChronologyFilterText.setText("Новые");
                    applyFilterAndSort();
                });
            }

            // Calculate x offset so popup right edge maintains 16dp margin from the right screen boundary
            int[] location = new int[2];
            anchorView.getLocationOnScreen(location);
            int anchorX = location[0];
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int rightMarginPx = (int) (16 * density);

            int targetX = screenWidth - popupWidth - rightMarginPx;
            if (targetX < rightMarginPx) {
                targetX = rightMarginPx;
            }
            int offsetX = targetX - anchorX;
            int offsetY = (int) (6 * density);

            popupWindow.showAsDropDown(anchorView, offsetX, offsetY);
        } catch (Exception e) {
            Log.e(TAG, "Error showing chronology filter popup", e);
        }
    }

    private void resetPopupItemStyle(Context context, TextView textView) {
        if (textView == null || context == null) return;
        textView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        textView.setTextColor(ContextCompat.getColor(context, R.color.accent_text_color));
    }

    private void setPopupItemSelectedStyle(Context context, TextView textView) {
        if (textView == null || context == null) return;
        textView.setBackgroundResource(R.drawable.bg_sort_popup_item_selected);
        textView.setTextColor(ContextCompat.getColor(context, R.color.sort_popup_item_selected_text));
    }

    public void openChronologyMapSheet() {
        if (currentFranchiseResponse == null || callback == null) return;
        Context context = callback.getContext();
        if (context == null) return;

        try {
            FranchiseMapBottomSheet sheet = new FranchiseMapBottomSheet(context, currentFranchiseResponse);
            sheet.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing FranchiseMapBottomSheet", e);
        }
    }

    private void onNodeSelected(FranchiseResponse.NodeItem node) {
        if (node == null) return;
        Context context = callback != null ? callback.getContext() : null;
        if (context == null) return;

        Log.d(TAG, "Selected chronology node: " + node.getName());
        CustomToast.showInfo(context, "Поиск " + node.getName() + "...");

        ApiService apiService = callback != null ? callback.getApiService() : null;
        if (apiService == null) return;

        apiService.searchAnime(node.getName(), new ApiService.SearchCallback() {
            @Override
            public void onSearchResults(com.example.animelib.models.SearchResponse response) {
                if (callback != null) {
                    callback.safeRunOnUiThread(() -> {
                        if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                            com.example.animelib.models.SearchResponse.AnimeSearchItem item = response.getData().get(0);
                            String webViewUrl = "/ru/anime/" + item.getSlugUrl();

                            if (callback.getPlayerDialogsController() != null) {
                                callback.getPlayerDialogsController().showTitleWebViewBottomSheet(item.getRusName(), webViewUrl);
                            }
                        } else {
                            String searchUrl = "https://v5.animelib.org/ru/anime?q=" + android.net.Uri.encode(node.getName());
                            if (callback.getPlayerDialogsController() != null) {
                                callback.getPlayerDialogsController().showTitleWebViewBottomSheet(node.getName(), searchUrl);
                            }
                        }
                    });
                }
            }

            @Override
            public void onError(String error) {
                if (callback != null) {
                    callback.safeRunOnUiThread(() -> {
                        String searchUrl = "https://v5.animelib.org/ru/anime?q=" + android.net.Uri.encode(node.getName());
                        if (callback.getPlayerDialogsController() != null) {
                            callback.getPlayerDialogsController().showTitleWebViewBottomSheet(node.getName(), searchUrl);
                        }
                    });
                }
            }
        });
    }

    public FranchiseResponse getCurrentFranchiseResponse() {
        return currentFranchiseResponse;
    }
}
