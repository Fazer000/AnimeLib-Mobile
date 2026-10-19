package com.example.animelib.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.MainActivity;
import com.example.animelib.R;
import com.example.animelib.VideoPlayerActivity;
import com.example.animelib.adapters.FranchiseChronologyAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.FranchiseResponse;
import com.example.animelib.models.SearchResponse;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Всплывающее окно полной хронологии франшизы аниме
 */
public class FranchiseChronologyBottomSheet extends FlexibleBottomSheetDialog {

    private static final String TAG = "FranchiseBS";

    private final FranchiseResponse franchiseResponse;
    private final ApiService apiService;

    private TextView tvFranchiseCountBadge;
    private ImageButton btnCloseFranchise;
    private MaterialButton btnToggleSortOrder;
    private RecyclerView rvFranchiseList;
    private View franchiseProgress;
    private TextView tvFranchiseError;

    private FranchiseChronologyAdapter adapter;
    private List<FranchiseResponse.NodeItem> sortedNodes = new ArrayList<>();
    private Map<Long, String> relationsMap = new HashMap<>();
    private boolean isAscendingSort = true;

    public FranchiseChronologyBottomSheet(@NonNull Context context, FranchiseResponse response) {
        super(context);
        this.franchiseResponse = response;
        this.apiService = new ApiService(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bs_franchise_chronology);

        initViews();
        processFranchiseData();
    }

    private void initViews() {
        tvFranchiseCountBadge = findViewById(R.id.tvFranchiseCountBadge);
        btnCloseFranchise = findViewById(R.id.btnCloseFranchise);
        btnToggleSortOrder = findViewById(R.id.btnToggleSortOrder);
        rvFranchiseList = findViewById(R.id.rvFranchiseList);
        franchiseProgress = findViewById(R.id.franchiseProgress);
        tvFranchiseError = findViewById(R.id.tvFranchiseError);

        if (btnCloseFranchise != null) {
            btnCloseFranchise.setOnClickListener(v -> dismiss());
        }

        if (rvFranchiseList != null) {
            rvFranchiseList.setLayoutManager(new LinearLayoutManager(getContext()));
            adapter = new FranchiseChronologyAdapter(this::onNodeClicked, true);
            rvFranchiseList.setAdapter(adapter);
        }

        if (btnToggleSortOrder != null) {
            btnToggleSortOrder.setOnClickListener(v -> {
                isAscendingSort = !isAscendingSort;
                if (isAscendingSort) {
                    btnToggleSortOrder.setText("Хронологически (сначала старые)");
                } else {
                    btnToggleSortOrder.setText("Новые сначала");
                }
                applySorting();
            });
        }
    }

    private void processFranchiseData() {
        if (franchiseResponse == null || franchiseResponse.getNodes() == null || franchiseResponse.getNodes().isEmpty()) {
            if (tvFranchiseError != null) tvFranchiseError.setVisibility(View.VISIBLE);
            if (rvFranchiseList != null) rvFranchiseList.setVisibility(View.GONE);
            return;
        }

        if (tvFranchiseError != null) tvFranchiseError.setVisibility(View.GONE);
        if (rvFranchiseList != null) rvFranchiseList.setVisibility(View.VISIBLE);

        int totalCount = franchiseResponse.getNodes().size();
        if (tvFranchiseCountBadge != null) {
            tvFranchiseCountBadge.setText(totalCount + " элементов");
        }

        // Строим карту связей для узлов
        relationsMap = buildRelationsMap(franchiseResponse);

        sortedNodes = new ArrayList<>(franchiseResponse.getNodes());
        applySorting();
    }

    private void applySorting() {
        if (sortedNodes == null) return;

        Collections.sort(sortedNodes, (n1, n2) -> {
            int cmp = Long.compare(n1.getDate(), n2.getDate());
            if (cmp == 0) {
                cmp = Integer.compare(n1.getYear(), n2.getYear());
            }
            if (cmp == 0) {
                cmp = Long.compare(n1.getId(), n2.getId());
            }
            return isAscendingSort ? cmp : -cmp;
        });

        if (adapter != null) {
            adapter.updateData(sortedNodes, relationsMap, franchiseResponse.getCurrentId());
        }
    }

    public static Map<Long, String> buildRelationsMap(FranchiseResponse franchise) {
        Map<Long, String> map = new HashMap<>();
        if (franchise == null || franchise.getLinks() == null) {
            return map;
        }

        for (FranchiseResponse.LinkItem link : franchise.getLinks()) {
            if (link.getRelation() != null) {
                String label = formatRelationLabel(link.getRelation());
                map.put(link.getTargetId(), label);
            }
        }
        return map;
    }

    public static String formatRelationLabel(String rawRelation) {
        if (rawRelation == null) return "Связанное";
        switch (rawRelation.toLowerCase().trim()) {
            case "sequel":
                return "Сиквел";
            case "prequel":
                return "Приквел";
            case "side_story":
                return "Сайд-стори";
            case "spin_off":
                return "Спин-офф";
            case "summary":
                return "Рекап";
            case "parent_story":
            case "full_story":
                return "Основной сюжет";
            case "character":
                return "Про персонажа";
            case "other":
                return "Другое";
            default:
                return rawRelation;
        }
    }

    private void onNodeClicked(FranchiseResponse.NodeItem node) {
        if (node == null) return;

        Log.d(TAG, "Selected franchise node: " + node.getName() + " (ID: " + node.getId() + ")");
        CustomToast.showInfo(getContext(), "Поиск: " + node.getName());

        if (franchiseProgress != null) franchiseProgress.setVisibility(View.VISIBLE);

        apiService.searchAnime(node.getName(), new ApiService.SearchCallback() {
            @Override
            public void onSearchResults(SearchResponse response) {
                Activity act = getActivityFromContext(getContext());
                if (act == null || act.isFinishing()) return;

                act.runOnUiThread(() -> {
                    if (franchiseProgress != null) franchiseProgress.setVisibility(View.GONE);

                    if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                        SearchResponse.AnimeSearchItem bestMatch = response.getData().get(0);
                        String slugUrl = bestMatch.getSlugUrl();

                        Log.d(TAG, "Found anime on AnimeLib: " + bestMatch.getRusName() + " (slug: " + slugUrl + ")");
                        dismiss();

                        String webViewUrl = "/ru/anime/" + slugUrl;
                        if (act instanceof MainActivity) {
                            ((MainActivity) act).loadUrlInWebView(webViewUrl);
                        } else {
                            TitleWebViewBottomSheet webSheet = new TitleWebViewBottomSheet(act, bestMatch.getRusName(), webViewUrl);
                            webSheet.show();
                        }
                    } else {
                        // Fallback: search by query on site
                        Log.w(TAG, "No direct result found on AnimeLib for " + node.getName() + ", opening search query");
                        dismiss();
                        String searchUrl = "https://v5.animelib.org/ru/anime?q=" + Uri.encode(node.getName());
                        TitleWebViewBottomSheet webSheet = new TitleWebViewBottomSheet(act, node.getName(), searchUrl);
                        webSheet.show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                Activity act = getActivityFromContext(getContext());
                if (act == null || act.isFinishing()) return;

                act.runOnUiThread(() -> {
                    if (franchiseProgress != null) franchiseProgress.setVisibility(View.GONE);
                    dismiss();
                    String searchUrl = "https://v5.animelib.org/ru/anime?q=" + Uri.encode(node.getName());
                    TitleWebViewBottomSheet webSheet = new TitleWebViewBottomSheet(act, node.getName(), searchUrl);
                    webSheet.show();
                });
            }
        });
    }

    private Activity getActivityFromContext(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return (ctx instanceof Activity) ? (Activity) ctx : null;
    }
}
