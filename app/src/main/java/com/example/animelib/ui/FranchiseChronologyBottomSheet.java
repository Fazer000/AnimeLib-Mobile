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
                List<String> options = new ArrayList<>();
                List<String> values = new ArrayList<>();

                options.add("Хронологически (сначала старые)");
                values.add("asc");

                options.add("Новые сначала");
                values.add("desc");

                options.add("По названию (А-Я)");
                values.add("name_asc");

                options.add("По названию (Я-А)");
                values.add("name_desc");

                CustomSelectBottomSheet selectSheet = new CustomSelectBottomSheet(
                        getContext(),
                        options,
                        values,
                        currentSortMode,
                        (selectedVal, selectedText) -> {
                            currentSortMode = selectedVal;
                            btnToggleSortOrder.setText(selectedText);
                            applySorting();
                        }
                );
                selectSheet.show();
            });
        }
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
     * Топологическая сортировка франшизы по графу связей и дате (совпадает с Shikimori 1:1)
     */
    public static List<FranchiseResponse.NodeItem> sortNodesChronologically(FranchiseResponse franchise) {
        if (franchise == null || franchise.getNodes() == null) return new ArrayList<>();

        List<FranchiseResponse.NodeItem> nodes = new ArrayList<>(franchise.getNodes());
        if (nodes.size() <= 1) return nodes;

        Map<Long, FranchiseResponse.NodeItem> nodeMap = new HashMap<>();
        Map<Long, Integer> inDegree = new HashMap<>();
        Map<Long, List<Long>> adjacency = new HashMap<>();

        for (FranchiseResponse.NodeItem node : nodes) {
            nodeMap.put(node.getId(), node);
            inDegree.put(node.getId(), 0);
            adjacency.put(node.getId(), new ArrayList<>());
        }

        if (franchise.getLinks() != null) {
            for (FranchiseResponse.LinkItem link : franchise.getLinks()) {
                long source = link.getSourceId();
                long target = link.getTargetId();

                if (!nodeMap.containsKey(source) || !nodeMap.containsKey(target)) continue;

                String rel = link.getRelation() != null ? link.getRelation().toLowerCase().trim() : "";
                long from = -1, to = -1;

                if (rel.equals("sequel") || rel.equals("side_story") || rel.equals("spin_off") || rel.equals("summary")) {
                    from = source;
                    to = target;
                } else if (rel.equals("prequel") || rel.equals("parent_story") || rel.equals("full_story")) {
                    from = target;
                    to = source;
                }

                if (from != -1 && to != -1 && from != to) {
                    if (!adjacency.get(from).contains(to)) {
                        adjacency.get(from).add(to);
                        inDegree.put(to, inDegree.get(to) + 1);
                    }
                }
            }
        }

        List<FranchiseResponse.NodeItem> sorted = new ArrayList<>();
        List<FranchiseResponse.NodeItem> zeroDegreeList = new ArrayList<>();

        for (FranchiseResponse.NodeItem node : nodes) {
            if (inDegree.get(node.getId()) == 0) {
                zeroDegreeList.add(node);
            }
        }

        // Sort root candidates by date/year
        Collections.sort(zeroDegreeList, (n1, n2) -> {
            if (n1.getDate() != n2.getDate()) return Long.compare(n1.getDate(), n2.getDate());
            if (n1.getYear() != n2.getYear()) return Integer.compare(n1.getYear(), n2.getYear());
            return Long.compare(n1.getId(), n2.getId());
        });

        List<FranchiseResponse.NodeItem> queue = new ArrayList<>(zeroDegreeList);
        int head = 0;
        while (head < queue.size()) {
            FranchiseResponse.NodeItem current = queue.get(head++);
            sorted.add(current);

            List<Long> neighbors = adjacency.get(current.getId());
            if (neighbors != null) {
                List<FranchiseResponse.NodeItem> nextNodes = new ArrayList<>();
                for (long neighborId : neighbors) {
                    int newInDegree = inDegree.get(neighborId) - 1;
                    inDegree.put(neighborId, newInDegree);
                    if (newInDegree == 0) {
                        nextNodes.add(nodeMap.get(neighborId));
                    }
                }
                Collections.sort(nextNodes, (n1, n2) -> {
                    if (n1.getDate() != n2.getDate()) return Long.compare(n1.getDate(), n2.getDate());
                    if (n1.getYear() != n2.getYear()) return Integer.compare(n1.getYear(), n2.getYear());
                    return Long.compare(n1.getId(), n2.getId());
                });
                queue.addAll(nextNodes);
            }
        }

        // Fallback for cycles/isolated nodes
        if (sorted.size() < nodes.size()) {
            Set<Long> addedIds = new HashSet<>();
            for (FranchiseResponse.NodeItem n : sorted) addedIds.add(n.getId());

            List<FranchiseResponse.NodeItem> remaining = new ArrayList<>();
            for (FranchiseResponse.NodeItem n : nodes) {
                if (!addedIds.contains(n.getId())) {
                    remaining.add(n);
                }
            }
            Collections.sort(remaining, (n1, n2) -> Long.compare(n1.getDate(), n2.getDate()));
            sorted.addAll(remaining);
        }

        return sorted;
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
