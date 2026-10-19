package com.example.animelib.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
import com.google.android.material.bottomsheet.BottomSheetBehavior;

/**
 * Интерактивная карта франшизы аниме в виде таблицы по годам
 */
public class FranchiseMapBottomSheet extends FlexibleBottomSheetDialog {

    private static final String TAG = "FranchiseMapBS";

    private final FranchiseResponse franchiseResponse;
    private final ApiService apiService;

    private TextView tvMapCountBadge;
    private ImageButton btnCloseMap;
    private FranchiseMapGraphView franchiseGraphView;
    private View mapGraphContainer;

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
        setupBottomSheetHeightAndTouch();
    }

    private void initViews() {
        tvMapCountBadge = findViewById(R.id.tvMapCountBadge);
        btnCloseMap = findViewById(R.id.btnCloseMap);
        franchiseGraphView = findViewById(R.id.franchiseGraphView);
        mapGraphContainer = findViewById(R.id.mapGraphContainer);

        ImageButton btnZoomIn = findViewById(R.id.btnZoomIn);
        ImageButton btnZoomOut = findViewById(R.id.btnZoomOut);
        TextView tvZoomPercentage = findViewById(R.id.tvZoomPercentage);

        if (btnCloseMap != null) {
            btnCloseMap.setOnClickListener(v -> dismiss());
        }

        if (franchiseResponse != null && franchiseResponse.getNodes() != null) {
            if (tvMapCountBadge != null) {
                tvMapCountBadge.setText(franchiseResponse.getNodes().size() + " частей");
            }

            if (franchiseGraphView != null) {
                // Instantly navigate on node click (Requirement #5)
                franchiseGraphView.setFranchiseData(franchiseResponse, (node, relation) -> watchSelectedNode(node));

                // Wire up zoom controls (Requirement #6)
                if (btnZoomIn != null) {
                    btnZoomIn.setOnClickListener(v -> franchiseGraphView.zoomIn());
                }
                if (btnZoomOut != null) {
                    btnZoomOut.setOnClickListener(v -> franchiseGraphView.zoomOut());
                }
                if (tvZoomPercentage != null) {
                    tvZoomPercentage.setText(Math.round(franchiseGraphView.getScaleFactor() * 100) + "%");
                    franchiseGraphView.setOnZoomChangeListener(scale ->
                            tvZoomPercentage.setText(Math.round(scale * 100) + "%")
                    );
                }
            }
        }
    }

    private void setupBottomSheetHeightAndTouch() {
        Context context = getContext();
        if (context == null) return;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenHeight = dm.heightPixels;
        // Height must be at least 50% screen height and up to 85% (Requirement #4)
        int minHeight = screenHeight / 2;
        int targetHeight = Math.max(minHeight, (int) (screenHeight * 0.85));

        View bsRoot = findViewById(R.id.bsMapRoot);
        if (bsRoot != null) {
            ViewGroup.LayoutParams lp = bsRoot.getLayoutParams();
            if (lp != null) {
                lp.height = targetHeight;
                bsRoot.setLayoutParams(lp);
            }
        }

        FrameLayout bottomSheet = findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
            behavior.setSkipCollapsed(true);
            behavior.setPeekHeight(targetHeight);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

            ViewGroup.LayoutParams lp = bottomSheet.getLayoutParams();
            if (lp != null) {
                lp.height = targetHeight;
                bottomSheet.setLayoutParams(lp);
            }
        }

        // Prevent bottom sheet from dragging/closing when interacting with the map view canvas (Requirement #2)
        if (mapGraphContainer != null) {
            mapGraphContainer.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
        }
    }

    private void watchSelectedNode(FranchiseResponse.NodeItem node) {
        if (node == null) return;

        Log.d(TAG, "Navigating directly to node: " + node.getName());
        CustomToast.showInfo(getContext(), "Переход: " + node.getName());

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
