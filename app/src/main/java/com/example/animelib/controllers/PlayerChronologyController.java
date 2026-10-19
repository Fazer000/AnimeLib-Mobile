package com.example.animelib.controllers;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.adapters.FranchiseChronologyAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.FranchiseResponse;
import com.example.animelib.ui.FranchiseChronologyBottomSheet;
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
    private View btnOpenFullChronology;
    private View portraitChronologyLoadingIndicator;
    private RecyclerView portraitChronologyRecyclerView;

    private FranchiseChronologyAdapter adapter;
    private FranchiseResponse currentFranchiseResponse;
    private Map<Long, String> currentRelationsMap;

    public PlayerChronologyController(ChronologyCallback callback) {
        this.callback = callback;
    }

    public void initViews(View rootView) {
        if (rootView == null) return;

        portraitChronologyContainer = rootView.findViewById(R.id.portraitChronologyContainer);
        tvPortraitChronologyCount = rootView.findViewById(R.id.tvPortraitChronologyCount);
        btnOpenFullChronology = rootView.findViewById(R.id.btnOpenFullChronology);
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

        if (btnOpenFullChronology != null) {
            btnOpenFullChronology.setOnClickListener(v -> openFullChronologySheet());
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

        List<FranchiseResponse.NodeItem> sortedChronologicalNodes = new ArrayList<>(response.getNodes());
        Collections.sort(sortedChronologicalNodes, (n1, n2) -> {
            int cmp = Long.compare(n1.getDate(), n2.getDate());
            if (cmp == 0) {
                cmp = Integer.compare(n1.getYear(), n2.getYear());
            }
            if (cmp == 0) {
                cmp = Long.compare(n1.getId(), n2.getId());
            }
            return cmp;
        });

        if (tvPortraitChronologyCount != null) {
            tvPortraitChronologyCount.setText(String.valueOf(sortedChronologicalNodes.size()));
            tvPortraitChronologyCount.setVisibility(View.VISIBLE);
        }

        if (adapter != null) {
            adapter.updateData(sortedChronologicalNodes, currentRelationsMap, response.getCurrentId());
        }

        if (portraitChronologyRecyclerView != null) {
            portraitChronologyRecyclerView.setVisibility(View.VISIBLE);
        }

        if (portraitChronologyContainer != null) {
            portraitChronologyContainer.setVisibility(View.VISIBLE);
        }
    }

    public void openFullChronologySheet() {
        if (currentFranchiseResponse == null || callback == null) return;
        Context context = callback.getContext();
        if (context == null) return;

        try {
            FranchiseChronologyBottomSheet sheet = new FranchiseChronologyBottomSheet(context, currentFranchiseResponse);
            sheet.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing FranchiseChronologyBottomSheet", e);
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
