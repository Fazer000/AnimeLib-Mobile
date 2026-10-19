package com.example.animelib.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.example.animelib.MainActivity;
import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.FranchiseResponse;
import com.example.animelib.models.SearchResponse;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.views.FranchiseMapGraphView;
import com.google.android.material.button.MaterialButton;

/**
 * Всплывающая интерактивная карта хронологии франшизы аниме с соединенными кривыми и годами
 */
public class FranchiseMapBottomSheet extends FlexibleBottomSheetDialog {

    private static final String TAG = "FranchiseMapBS";

    private final FranchiseResponse franchiseResponse;
    private final ApiService apiService;

    private TextView tvMapCountBadge;
    private ImageButton btnCloseMap;
    private FranchiseMapGraphView franchiseGraphView;

    private View cardMapSelection;
    private TextView tvMapSelectedRelation;
    private TextView tvMapSelectedTitle;
    private TextView tvMapSelectedSub;
    private MaterialButton btnMapWatchAnime;

    private FranchiseResponse.NodeItem selectedNode;

    public FranchiseMapBottomSheet(@NonNull Context context, FranchiseResponse response) {
        super(context);
        this.franchiseResponse = response;
        this.apiService = new ApiService(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bs_franchise_map);

        initViews();
    }

    private void initViews() {
        tvMapCountBadge = findViewById(R.id.tvMapCountBadge);
        btnCloseMap = findViewById(R.id.btnCloseMap);
        franchiseGraphView = findViewById(R.id.franchiseGraphView);

        cardMapSelection = findViewById(R.id.cardMapSelection);
        tvMapSelectedRelation = findViewById(R.id.tvMapSelectedRelation);
        tvMapSelectedTitle = findViewById(R.id.tvMapSelectedTitle);
        tvMapSelectedSub = findViewById(R.id.tvMapSelectedSub);
        btnMapWatchAnime = findViewById(R.id.btnMapWatchAnime);

        if (btnCloseMap != null) {
            btnCloseMap.setOnClickListener(v -> dismiss());
        }

        if (franchiseResponse != null && franchiseResponse.getNodes() != null) {
            if (tvMapCountBadge != null) {
                tvMapCountBadge.setText(franchiseResponse.getNodes().size() + " частей");
            }

            if (franchiseGraphView != null) {
                franchiseGraphView.setFranchiseData(franchiseResponse, this::onNodeClickedOnMap);
            }
        }

        if (btnMapWatchAnime != null) {
            btnMapWatchAnime.setOnClickListener(v -> {
                if (selectedNode != null) {
                    watchSelectedNode(selectedNode);
                }
            });
        }
    }

    private void onNodeClickedOnMap(FranchiseResponse.NodeItem node, String relation) {
        if (node == null) return;
        this.selectedNode = node;

        if (cardMapSelection != null) {
            cardMapSelection.setVisibility(View.VISIBLE);
        }

        if (tvMapSelectedRelation != null) {
            tvMapSelectedRelation.setText(relation != null ? relation : "Часть франшизы");
        }

        if (tvMapSelectedTitle != null) {
            tvMapSelectedTitle.setText(node.getName());
        }

        if (tvMapSelectedSub != null) {
            String kind = node.getFormattedKind();
            String year = node.getYear() > 0 ? String.valueOf(node.getYear()) : "";
            tvMapSelectedSub.setText(kind + (year.isEmpty() ? "" : " • " + year));
        }
    }

    private void watchSelectedNode(FranchiseResponse.NodeItem node) {
        if (node == null) return;

        Log.d(TAG, "Navigating to watched node from map: " + node.getName());
        CustomToast.showInfo(getContext(), "Поиск " + node.getName() + "...");

        apiService.searchAnime(node.getName(), new ApiService.SearchCallback() {
            @Override
            public void onSearchResults(SearchResponse response) {
                Activity act = getActivityFromContext(getContext());
                if (act == null || act.isFinishing()) return;

                act.runOnUiThread(() -> {
                    if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                        SearchResponse.AnimeSearchItem bestMatch = response.getData().get(0);
                        String slugUrl = bestMatch.getSlugUrl();

                        dismiss();
                        String webViewUrl = "/ru/anime/" + slugUrl;
                        if (act instanceof MainActivity) {
                            ((MainActivity) act).loadUrlInWebView(webViewUrl);
                        } else {
                            TitleWebViewBottomSheet webSheet = new TitleWebViewBottomSheet(act, bestMatch.getRusName(), webViewUrl);
                            webSheet.show();
                        }
                    } else {
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
