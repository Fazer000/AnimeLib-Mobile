package com.example.animelib.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.Set;
import java.util.HashSet;

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

    private View btnSortChronology;
    private TextView tvSortChronologyText;

    private void initViews() {
        tvFranchiseCountBadge = findViewById(R.id.tvFranchiseCountBadge);
        btnCloseFranchise = findViewById(R.id.btnCloseFranchise);
        btnSortChronology = findViewById(R.id.btnSortChronology);
        tvSortChronologyText = findViewById(R.id.tvSortChronologyText);
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

        if (btnSortChronology != null) {
            btnSortChronology.setOnClickListener(v -> showSortPopupWindow(btnSortChronology));
        }
    }

    private void showSortPopupWindow(View anchorView) {
        Context context = getContext();
        if (context == null || anchorView == null) return;

        try {
            View popupView = android.view.LayoutInflater.from(context).inflate(R.layout.popup_chronology_sort, null);

            float density = context.getResources().getDisplayMetrics().density;
            int popupWidth = (int) (190 * density);

            android.widget.PopupWindow popupWindow = new android.widget.PopupWindow(
                    popupView,
                    popupWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
            );

            popupWindow.setOutsideTouchable(true);
            popupWindow.setFocusable(true);
            popupWindow.setElevation(12 * density);
            popupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

            TextView itemAsc = popupView.findViewById(R.id.item_sort_asc);
            TextView itemDesc = popupView.findViewById(R.id.item_sort_desc);
            TextView itemNameAsc = popupView.findViewById(R.id.item_sort_name_asc);
            TextView itemNameDesc = popupView.findViewById(R.id.item_sort_name_desc);

            resetPopupItemStyle(itemAsc);
            resetPopupItemStyle(itemDesc);
            resetPopupItemStyle(itemNameAsc);
            resetPopupItemStyle(itemNameDesc);

            if ("desc".equals(currentSortMode)) {
                setPopupItemSelectedStyle(itemDesc);
            } else if ("name_asc".equals(currentSortMode)) {
                setPopupItemSelectedStyle(itemNameAsc);
            } else if ("name_desc".equals(currentSortMode)) {
                setPopupItemSelectedStyle(itemNameDesc);
            } else {
                setPopupItemSelectedStyle(itemAsc);
            }

            if (itemAsc != null) {
                itemAsc.setOnClickListener(v -> {
                    popupWindow.dismiss();
                    currentSortMode = "asc";
                    if (tvSortChronologyText != null) tvSortChronologyText.setText("Хронологически");
                    applySorting();
                });
            }

            if (itemDesc != null) {
                itemDesc.setOnClickListener(v -> {
                    popupWindow.dismiss();
                    currentSortMode = "desc";
                    if (tvSortChronologyText != null) tvSortChronologyText.setText("Новые сначала");
                    applySorting();
                });
            }

            if (itemNameAsc != null) {
                itemNameAsc.setOnClickListener(v -> {
                    popupWindow.dismiss();
                    currentSortMode = "name_asc";
                    if (tvSortChronologyText != null) tvSortChronologyText.setText("По названию (А-Я)");
                    applySorting();
                });
            }

            if (itemNameDesc != null) {
                itemNameDesc.setOnClickListener(v -> {
                    popupWindow.dismiss();
                    currentSortMode = "name_desc";
                    if (tvSortChronologyText != null) tvSortChronologyText.setText("По названию (Я-А)");
                    applySorting();
                });
            }

            popupWindow.showAsDropDown(anchorView, 0, (int) (6 * density));
        } catch (Exception e) {
            Log.e(TAG, "Error showing chronology sort popup", e);
        }
    }

    private void resetPopupItemStyle(TextView textView) {
        if (textView == null) return;
        textView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        textView.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.accent_text_color));
    }

    private void setPopupItemSelectedStyle(TextView textView) {
        if (textView == null) return;
        textView.setBackgroundResource(R.drawable.bg_sort_popup_item_selected);
        textView.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.sort_popup_item_selected_text));
    }

    private String currentSortMode = "asc";

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

        sortedNodes = sortNodesChronologically(franchiseResponse);
        applySorting();
    }

    private void applySorting() {
        if (sortedNodes == null) return;

        if ("desc".equals(currentSortMode)) {
            List<FranchiseResponse.NodeItem> list = sortNodesChronologically(franchiseResponse);
            Collections.reverse(list);
            sortedNodes = list;
        } else if ("name_asc".equals(currentSortMode)) {
            List<FranchiseResponse.NodeItem> list = new ArrayList<>(franchiseResponse.getNodes());
            Collections.sort(list, (n1, n2) -> n1.getName().compareToIgnoreCase(n2.getName()));
            sortedNodes = list;
        } else if ("name_desc".equals(currentSortMode)) {
            List<FranchiseResponse.NodeItem> list = new ArrayList<>(franchiseResponse.getNodes());
            Collections.sort(list, (n1, n2) -> n2.getName().compareToIgnoreCase(n1.getName()));
            sortedNodes = list;
        } else { // "asc"
            sortedNodes = sortNodesChronologically(franchiseResponse);
        }

        if (adapter != null) {
            adapter.updateData(sortedNodes, relationsMap, franchiseResponse.getCurrentId());
        }
    }

    /**
     * Сортировка франшизы по дате выхода (совпадает с Shikimori 1:1)
     */
    public static List<FranchiseResponse.NodeItem> sortNodesChronologically(FranchiseResponse franchise) {
        if (franchise == null || franchise.getNodes() == null) return new ArrayList<>();

        List<FranchiseResponse.NodeItem> nodes = new ArrayList<>(franchise.getNodes());
        if (nodes.size() <= 1) return nodes;

        Collections.sort(nodes, (n1, n2) -> {
            if (n1.getYear() > 0 && n2.getYear() > 0 && n1.getYear() != n2.getYear()) {
                return Integer.compare(n1.getYear(), n2.getYear());
            }
            if (n1.getDate() > 0 && n2.getDate() > 0 && n1.getDate() != n2.getDate()) {
                return Long.compare(n1.getDate(), n2.getDate());
            }
            if (n1.getWeight() != n2.getWeight()) {
                return Integer.compare(n1.getWeight(), n2.getWeight());
            }
            return Long.compare(n1.getId(), n2.getId());
        });

        return nodes;
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
            case "alternative_setting":
                return "Альт. сеттинг";
            case "alternative_version":
                return "Альт. версия";
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
