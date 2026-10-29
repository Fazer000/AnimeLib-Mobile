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

    private FranchiseResponse franchiseResponse;
    private final long shikimoriId;
    private final ApiService apiService;

    private TextView tvMapCountBadge;
    private ImageButton btnCloseMap;
    private FranchiseMapGraphView franchiseGraphView;
    private View mapGraphContainer;
    private View mapLoadingOverlay;
    private View mapLoadingProgress;
    private TextView tvMapLoadingText;
    private View mapErrorContainer;
    private View zoomControlsCard;
    private ImageButton btnZoomIn;
    private ImageButton btnZoomOut;
    private TextView tvZoomPercentage;

    public FranchiseMapBottomSheet(@NonNull Context context, FranchiseResponse response) {
        this(context, response, -1);
    }

    public FranchiseMapBottomSheet(@NonNull Context context, FranchiseResponse response, long shikimoriId) {
        super(context);
        this.franchiseResponse = response;
        this.shikimoriId = shikimoriId;
        this.apiService = new ApiService(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bs_franchise_map);

        initViews();
        setupBottomSheetHeightAndTouch();

        if (franchiseResponse != null && franchiseResponse.getNodes() != null && !franchiseResponse.getNodes().isEmpty()) {
            bindFranchiseData(franchiseResponse);
        } else if (shikimoriId > 0) {
            loadMapData();
        } else {
            showError("Не удалось определить ID франшизы");
        }
    }

    private void initViews() {
        tvMapCountBadge = findViewById(R.id.tvMapCountBadge);
        btnCloseMap = findViewById(R.id.btnCloseMap);
        franchiseGraphView = findViewById(R.id.franchiseGraphView);
        mapGraphContainer = findViewById(R.id.mapGraphContainer);
        mapLoadingOverlay = findViewById(R.id.mapLoadingOverlay);
        mapLoadingProgress = findViewById(R.id.mapLoadingProgress);
        tvMapLoadingText = findViewById(R.id.tvMapLoadingText);
        mapErrorContainer = findViewById(R.id.mapErrorContainer);
        zoomControlsCard = findViewById(R.id.zoomControlsCard);

        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);
        tvZoomPercentage = findViewById(R.id.tvZoomPercentage);

        View btnRetry = findViewById(R.id.btnRetryMapLoad);
        if (btnRetry != null) {
            btnRetry.setOnClickListener(v -> loadMapData());
        }

        if (btnCloseMap != null) {
            btnCloseMap.setOnClickListener(v -> dismiss());
        }
    }

    private void showLoadingOverlay(boolean animate, String message) {
        if (mapLoadingOverlay == null) return;

        if (tvMapLoadingText != null) {
            if (message != null && !message.trim().isEmpty()) {
                tvMapLoadingText.setText(message);
                tvMapLoadingText.setVisibility(View.VISIBLE);
            } else {
                tvMapLoadingText.setVisibility(View.GONE);
            }
        }

        mapLoadingOverlay.animate().cancel();
        if (animate) {
            mapLoadingOverlay.setAlpha(0f);
            mapLoadingOverlay.setVisibility(View.VISIBLE);
            mapLoadingOverlay.animate()
                    .alpha(1.0f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .start();
        } else {
            mapLoadingOverlay.setAlpha(1.0f);
            mapLoadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideLoadingOverlay(boolean animate) {
        if (mapLoadingOverlay == null) return;

        mapLoadingOverlay.animate().cancel();
        if (animate && mapLoadingOverlay.getVisibility() == View.VISIBLE) {
            mapLoadingOverlay.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (mapLoadingOverlay != null) {
                            mapLoadingOverlay.setVisibility(View.GONE);
                            mapLoadingOverlay.setAlpha(1.0f);
                        }
                    })
                    .start();
        } else {
            mapLoadingOverlay.setVisibility(View.GONE);
            mapLoadingOverlay.setAlpha(1.0f);
        }
    }

    private void loadMapData() {
        if (shikimoriId <= 0) {
            showError("Некорректный ID Shikimori");
            return;
        }

        showLoadingOverlay(false, null);
        if (mapErrorContainer != null) mapErrorContainer.setVisibility(View.GONE);
        if (franchiseGraphView != null) franchiseGraphView.setVisibility(View.GONE);
        if (zoomControlsCard != null) zoomControlsCard.setVisibility(View.GONE);

        apiService.getFranchise(shikimoriId, new ApiService.FranchiseCallback() {
            @Override
            public void onFranchiseReceived(FranchiseResponse response) {
                Activity act = getActivityFromContext(getContext());
                if (act == null || act.isFinishing()) return;

                act.runOnUiThread(() -> {
                    franchiseResponse = response;
                    hideLoadingOverlay(true);
                    if (mapErrorContainer != null) mapErrorContainer.setVisibility(View.GONE);
                    if (franchiseGraphView != null) franchiseGraphView.setVisibility(View.VISIBLE);
                    if (zoomControlsCard != null) zoomControlsCard.setVisibility(View.VISIBLE);
                    bindFranchiseData(response);
                });
            }

            @Override
            public void onError(String error) {
                Activity act = getActivityFromContext(getContext());
                if (act == null || act.isFinishing()) return;

                act.runOnUiThread(() -> {
                    hideLoadingOverlay(false);
                    showError("Не удалось загрузить карту франшизы");
                });
            }
        });
    }

    private void showError(String message) {
        hideLoadingOverlay(false);
        if (franchiseGraphView != null) franchiseGraphView.setVisibility(View.GONE);
        if (zoomControlsCard != null) zoomControlsCard.setVisibility(View.GONE);
        if (mapErrorContainer != null) {
            TextView tvMsg = mapErrorContainer.findViewById(R.id.tvMapErrorMessage);
            if (tvMsg != null && message != null) {
                tvMsg.setText(message);
            }
            mapErrorContainer.setVisibility(View.VISIBLE);
        }
    }

    private void bindFranchiseData(FranchiseResponse response) {
        if (response == null || response.getNodes() == null) {
            showError("Нет данных для отображения карты");
            return;
        }

        if (tvMapCountBadge != null) {
            tvMapCountBadge.setText(response.getNodes().size() + " частей");
        }

        if (franchiseGraphView != null) {
            franchiseGraphView.setVisibility(View.VISIBLE);
            franchiseGraphView.setFranchiseData(response, (node, relation) -> watchSelectedNode(node));

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
                tvZoomPercentage.setOnClickListener(v -> {
                    franchiseGraphView.setScaleFactor(0.70f);
                    franchiseGraphView.centerOnCurrent(true);
                });
            }
        }

        if (zoomControlsCard != null) {
            zoomControlsCard.setVisibility(View.VISIBLE);
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

        Log.d(TAG, "Navigating to node: " + node.getName() + " (ID: " + node.getId() + ")");

        showLoadingOverlay(true, "Открываем " + node.getName() + "…");

        long animeId = node.getId();

        apiService.fetchShikimoriAnimeDetails(animeId, new ApiService.ShikimoriAnimeDetailsCallback() {
            @Override
            public void onSuccess(String name, String russianName) {
                String searchQuery = (name != null && !name.trim().isEmpty()) ? name.trim() : node.getName();
                performAnimelibSearchAndNavigate(node, searchQuery, russianName);
            }

            @Override
            public void onError(String error) {
                performAnimelibSearchAndNavigate(node, node.getName(), null);
            }
        });
    }

    private void performAnimelibSearchAndNavigate(FranchiseResponse.NodeItem node, String searchQuery, String fallbackQuery) {
        apiService.searchAnime(searchQuery, new ApiService.SearchCallback() {
            @Override
            public void onSearchResults(SearchResponse response) {
                Activity act = getActivityFromContext(getContext());
                if (act == null || act.isFinishing()) return;

                act.runOnUiThread(() -> {
                    if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                        SearchResponse.AnimeSearchItem bestMatch = response.getData().get(0);
                        String slugUrl = bestMatch.getSlugUrl();
                        if (slugUrl == null || slugUrl.trim().isEmpty()) {
                            slugUrl = bestMatch.getSlug();
                        }

                        hideLoadingOverlay(false);
                        dismiss();

                        String titleName = bestMatch.getRusName();
                        if (titleName == null || titleName.trim().isEmpty()) {
                            titleName = bestMatch.getName();
                        }
                        if (titleName == null || titleName.trim().isEmpty()) {
                            titleName = searchQuery;
                        }

                        String webUrl = com.example.animelib.adapters.HorizontalRelatedTitlesAdapter.buildWebUrl(slugUrl, bestMatch.getModel(), getContext());
                        if (webUrl == null || webUrl.isEmpty()) {
                            webUrl = "https://v5.animelib.org/ru/anime/" + slugUrl;
                        }

                        TitleWebViewBottomSheet webSheet = new TitleWebViewBottomSheet(act, titleName, webUrl);
                        webSheet.show();
                    } else if (fallbackQuery != null && !fallbackQuery.equals(searchQuery)) {
                        performAnimelibSearchAndNavigate(node, fallbackQuery, null);
                    } else {
                        hideLoadingOverlay(false);
                        dismiss();
                        String searchUrl = "https://v5.animelib.org/ru/anime?q=" + Uri.encode(searchQuery);
                        TitleWebViewBottomSheet webSheet = new TitleWebViewBottomSheet(act, searchQuery, searchUrl);
                        webSheet.show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                Activity act = getActivityFromContext(getContext());
                if (act == null || act.isFinishing()) return;

                act.runOnUiThread(() -> {
                    if (fallbackQuery != null && !fallbackQuery.equals(searchQuery)) {
                        performAnimelibSearchAndNavigate(node, fallbackQuery, null);
                    } else {
                        hideLoadingOverlay(false);
                        dismiss();
                        String searchUrl = "https://v5.animelib.org/ru/anime?q=" + Uri.encode(searchQuery);
                        TitleWebViewBottomSheet webSheet = new TitleWebViewBottomSheet(act, searchQuery, searchUrl);
                        webSheet.show();
                    }
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
