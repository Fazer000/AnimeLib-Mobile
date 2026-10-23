package com.example.animelib.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.OverScroller;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.animelib.R;
import com.example.animelib.models.FranchiseResponse;
import com.example.animelib.ui.FranchiseChronologyBottomSheet;
import com.example.animelib.util.ImageLoader;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Интерактивная граф-карта франшизы аниме с выделенной главной веткой,
 * разветвлениями (сайд-стори, спин-оффами, рекапами) и поддержкой зума (Pinch-to-Zoom).
 */
public class FranchiseMapGraphView extends ViewGroup {

    private static final String TAG = "FranchiseMapGraphView";

    public interface OnNodeClickListener {
        void onNodeClick(FranchiseResponse.NodeItem node, String relation);
    }

    public interface OnZoomChangeListener {
        void onZoomChanged(float scaleFactor);
    }

    public static class MapLink {
        private final long id;
        private final long sourceId;
        private final long targetId;
        private final String relation;

        public MapLink(long id, long sourceId, long targetId, String relation) {
            this.id = id;
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.relation = relation;
        }

        public long getId() { return id; }
        public long getSourceId() { return sourceId; }
        public long getTargetId() { return targetId; }
        public String getRelation() { return relation; }
    }

    private FranchiseResponse franchiseData;
    private OnNodeClickListener listener;
    private OnZoomChangeListener zoomChangeListener;

    private final List<FranchiseResponse.NodeItem> mainTrunkNodes = new ArrayList<>();
    private final Map<Long, List<FranchiseResponse.NodeItem>> branchNodesMap = new HashMap<>();
    private final Map<Long, PointF> nodePositionsMap = new HashMap<>();
    private final List<MapLink> canonicalLinks = new ArrayList<>();

    private final Map<Long, View> nodeViewsMap = new HashMap<>();
    private final Map<Long, RectF> nodeBoundsMap = new HashMap<>();
    private final Map<Long, String> nodeRelationsMap = new HashMap<>();

    private long selectedNodeId = -1;
    private final Set<Long> connectedNodeIds = new HashSet<>();

    // Zoom & Scale
    private float scaleFactor = 0.75f; // Изначально немного уменьшенный зум (75%)
    private static final float MIN_SCALE = 0.35f;
    private static final float MAX_SCALE = 2.20f;
    private ScaleGestureDetector scaleGestureDetector;

    // 2D Scroll & Fling
    private OverScroller scroller;
    private GestureDetector gestureDetector;
    private int contentWidth = 0;
    private int contentHeight = 0;

    // Paints for Canvas curves
    private Paint linePaintSequel;
    private Paint linePaintSide;
    private Paint linePaintSummary;
    private Paint linePaintMain;
    private Paint linePaintDefault;
    private Paint linePaintDimmed;
    private Paint arrowPaint;

    // Layout dimensions in dp
    private int cardWidthPx;
    private int cardHeightPx;
    private int colSpacingPx;
    private int rowSpacingPx;
    private int paddingX;
    private int paddingY;

    public FranchiseMapGraphView(Context context) {
        super(context);
        init(context);
    }

    public FranchiseMapGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public FranchiseMapGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        scroller = new OverScroller(context);

        float density = context.getResources().getDisplayMetrics().density;
        cardWidthPx = (int) (250 * density);
        cardHeightPx = (int) (105 * density);
        colSpacingPx = (int) (110 * density);
        rowSpacingPx = (int) (65 * density);
        paddingX = (int) (70 * density);
        paddingY = (int) (90 * density);

        initPaints(context, density);

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                setScaleFactor(scaleFactor * factor, focusX, focusY);
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float canvasX = (e.getX() + getScrollX()) / scaleFactor;
                float canvasY = (e.getY() + getScrollY()) / scaleFactor;

                // 1. Check if tap falls strictly inside any card bounds on canvas
                for (Map.Entry<Long, RectF> entry : nodeBoundsMap.entrySet()) {
                    RectF bounds = entry.getValue();
                    if (bounds != null && bounds.contains(canvasX, canvasY)) {
                        long nodeId = entry.getKey();
                        if (franchiseData != null && franchiseData.getNodes() != null) {
                            for (FranchiseResponse.NodeItem node : franchiseData.getNodes()) {
                                if (node.getId() == nodeId) {
                                    selectNode(node.getId());
                                    if (listener != null) {
                                        String relation = nodeRelationsMap.get(node.getId());
                                        listener.onNodeClick(node, relation);
                                    }
                                    return true;
                                }
                            }
                        }
                    }
                }

                // 2. Tap on empty space: check for branch curve selection
                MapLink clickedLink = findLinkAt(canvasX, canvasY);
                if (clickedLink != null) {
                    selectBranchLink(clickedLink);
                    return true;
                } else {
                    // Tap on empty background clears selection
                    selectBranchLink(null);
                    return true;
                }
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                scrollBy((int) distanceX, (int) distanceY);
                clampScroll();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                scroller.fling(getScrollX(), getScrollY(),
                        (int) -velocityX, (int) -velocityY,
                        0, getMaxScrollX(),
                        0, getMaxScrollY());
                invalidate();
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                if (!scroller.isFinished()) {
                    scroller.forceFinished(true);
                }
                return true;
            }
        });
    }

    private void initPaints(Context context, float density) {
        linePaintSequel = createCurvePaint(0xFFA855F7, 1.4f * density, false);  // Фиолетовый сиквел/приквел
        linePaintMain = createCurvePaint(0xFF3B82F6, 1.4f * density, false);    // Синий основной сюжет
        linePaintSide = createCurvePaint(0xFF06B6D4, 1.1f * density, true);     // Бирюзовый пунктир сайд-стори
        linePaintSummary = createCurvePaint(0xFFF59E0B, 1.0f * density, false); // Янтарный рекап
        linePaintDefault = createCurvePaint(0xFF8E8E93, 1.0f * density, false); // Серый по умолчанию
        linePaintDimmed = createCurvePaint(0x338E8E93, 0.8f * density, false);  // Приглушенный

        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setStyle(Paint.Style.FILL);
    }

    private Paint createCurvePaint(int color, float strokeWidth, boolean isDashed) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        if (isDashed) {
            float d = getContext().getResources().getDisplayMetrics().density;
            paint.setPathEffect(new DashPathEffect(new float[]{10 * d, 6 * d}, 0));
        }
        return paint;
    }

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomChangeListener = listener;
    }

    public float getScaleFactor() {
        return scaleFactor;
    }

    public void setScaleFactor(float newScale, float focusX, float focusY) {
        float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        if (Math.abs(this.scaleFactor - clamped) > 0.001f) {
            float oldScale = this.scaleFactor;
            this.scaleFactor = clamped;

            float canvasFocusX = (getScrollX() + focusX) / oldScale;
            float canvasFocusY = (getScrollY() + focusY) / oldScale;

            int newScrollX = Math.round(canvasFocusX * clamped - focusX);
            int newScrollY = Math.round(canvasFocusY * clamped - focusY);

            int maxScrollX = getMaxScrollX();
            int maxScrollY = getMaxScrollY();

            newScrollX = Math.max(0, Math.min(newScrollX, maxScrollX));
            newScrollY = Math.max(0, Math.min(newScrollY, maxScrollY));

            scrollTo(newScrollX, newScrollY);
            invalidate();

            if (zoomChangeListener != null) {
                zoomChangeListener.onZoomChanged(this.scaleFactor);
            }
        }
    }

    public void setScaleFactor(float newScale) {
        float focusX = getWidth() > 0 ? getWidth() / 2f : 0f;
        float focusY = getHeight() > 0 ? getHeight() / 2f : 0f;
        setScaleFactor(newScale, focusX, focusY);
    }

    public void zoomIn() {
        float focusX = getWidth() > 0 ? getWidth() / 2f : 0f;
        float focusY = getHeight() > 0 ? getHeight() / 2f : 0f;
        setScaleFactor(scaleFactor + 0.15f, focusX, focusY);
    }

    public void zoomOut() {
        float focusX = getWidth() > 0 ? getWidth() / 2f : 0f;
        float focusY = getHeight() > 0 ? getHeight() / 2f : 0f;
        setScaleFactor(scaleFactor - 0.15f, focusX, focusY);
    }

    public void setFranchiseData(FranchiseResponse franchise, OnNodeClickListener listener) {
        this.franchiseData = franchise;
        this.listener = listener;

        removeAllViews();
        mainTrunkNodes.clear();
        branchNodesMap.clear();
        nodePositionsMap.clear();
        nodeViewsMap.clear();
        nodeBoundsMap.clear();
        nodeRelationsMap.clear();
        canonicalLinks.clear();
        selectedNodeId = -1;
        connectedNodeIds.clear();

        if (franchise == null || franchise.getNodes() == null || franchise.getNodes().isEmpty()) {
            contentWidth = 0;
            contentHeight = 0;
            requestLayout();
            invalidate();
            return;
        }

        buildCanonicalLinks();
        nodeRelationsMap.putAll(FranchiseChronologyBottomSheet.buildRelationsMap(franchise));

        calculateTreeLayout();

        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (FranchiseResponse.NodeItem node : franchise.getNodes()) {
            View cardView = createNodeCardView(inflater, node, franchise.getCurrentId());
            nodeViewsMap.put(node.getId(), cardView);
            addView(cardView);
        }

        requestLayout();
        invalidate();
    }

    private void buildCanonicalLinks() {
        canonicalLinks.clear();
        if (franchiseData == null || franchiseData.getLinks() == null) return;

        Map<Long, Long> nodeDateMap = new HashMap<>();
        if (franchiseData.getNodes() != null) {
            for (FranchiseResponse.NodeItem node : franchiseData.getNodes()) {
                long sortKey = (node.getYear() > 0 ? (long) node.getYear() * 10000000000L : 0L) + node.getDate();
                nodeDateMap.put(node.getId(), sortKey);
            }
        }

        Map<String, MapLink> deduplicated = new LinkedHashMap<>();

        for (FranchiseResponse.LinkItem link : franchiseData.getLinks()) {
            long s = link.getSourceId();
            long t = link.getTargetId();
            if (s <= 0 || t <= 0 || s == t) continue;

            String rel = link.getRelation() != null ? link.getRelation().toLowerCase().trim() : "";

            long normSource = s;
            long normTarget = t;
            String normRel = rel;

            // Normalize direction so canonical flow is always Parent/Predecessor -> Child/Branch
            if (rel.equals("prequel")) {
                normSource = t;
                normTarget = s;
                normRel = "sequel";
            } else if (rel.equals("parent_story") || rel.equals("full_story")) {
                normSource = t;
                normTarget = s;
                normRel = "side_story";
            } else if (rel.equals("sequel") || rel.equals("side_story") || rel.equals("spin_off") || rel.equals("summary")) {
                normSource = s;
                normTarget = t;
                normRel = rel;
            } else {
                long dateS = nodeDateMap.containsKey(s) ? nodeDateMap.get(s) : 0L;
                long dateT = nodeDateMap.containsKey(t) ? nodeDateMap.get(t) : 0L;
                if (dateS > dateT || (dateS == dateT && s > t)) {
                    normSource = t;
                    normTarget = s;
                } else {
                    normSource = s;
                    normTarget = t;
                }
                normRel = rel;
            }

            String pairKey = Math.min(s, t) + "_" + Math.max(s, t);
            MapLink candidate = new MapLink(link.getId(), normSource, normTarget, normRel);

            if (!deduplicated.containsKey(pairKey)) {
                deduplicated.put(pairKey, candidate);
            } else {
                MapLink existing = deduplicated.get(pairKey);
                int existingPriority = getRelationPriority(existing.getRelation());
                int candidatePriority = getRelationPriority(candidate.getRelation());
                if (candidatePriority < existingPriority) {
                    deduplicated.put(pairKey, candidate);
                }
            }
        }

        canonicalLinks.addAll(deduplicated.values());
    }

    private static int getRelationPriority(String relation) {
        if (relation == null) return 10;
        switch (relation.toLowerCase().trim()) {
            case "sequel": return 1;
            case "side_story": return 2;
            case "spin_off": return 3;
            case "summary": return 4;
            case "alternative_version": return 5;
            case "alternative_setting": return 6;
            case "character": return 7;
            default: return 8;
        }
    }

    private void calculateTreeLayout() {
        if (franchiseData == null || franchiseData.getNodes() == null || franchiseData.getNodes().isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        List<FranchiseResponse.NodeItem> allSorted = FranchiseChronologyBottomSheet.sortNodesChronologically(franchiseData);

        // 1. Build directed graph relationships from canonical deduplicated links
        Map<Long, Long> parentMap = new HashMap<>();
        Map<Long, List<Long>> childrenMap = new HashMap<>();

        for (MapLink link : canonicalLinks) {
            long p = link.getSourceId();
            long c = link.getTargetId();
            if (!parentMap.containsKey(c)) {
                parentMap.put(c, p);
            }
            if (!childrenMap.containsKey(p)) {
                childrenMap.put(p, new ArrayList<>());
            }
            if (!childrenMap.get(p).contains(c)) {
                childrenMap.get(p).add(c);
            }
        }

        // 2. Identify main storyline trunk
        Set<Long> mainSet = new HashSet<>();
        for (FranchiseResponse.NodeItem node : allSorted) {
            String kind = node.getFormattedKind() != null ? node.getFormattedKind().toLowerCase() : "";
            String rel = nodeRelationsMap.get(node.getId());
            String relLower = rel != null ? rel.toLowerCase() : "";

            boolean isMainType = (kind.contains("tv") || kind.contains("сериал") || kind.contains("фильм") ||
                    relLower.contains("сиквел") || relLower.contains("приквел") || relLower.contains("основной") || relLower.contains("оригинал"));

            if (isMainType || !parentMap.containsKey(node.getId())) {
                mainTrunkNodes.add(node);
                mainSet.add(node.getId());
            }
        }

        if (mainTrunkNodes.isEmpty()) {
            mainTrunkNodes.addAll(allSorted);
            for (FranchiseResponse.NodeItem n : allSorted) mainSet.add(n.getId());
        }

        // 3. Associate branches with trunk nodes
        for (FranchiseResponse.NodeItem node : allSorted) {
            if (!mainSet.contains(node.getId())) {
                long parentId = parentMap.containsKey(node.getId()) ? parentMap.get(node.getId()) : -1;
                while (parentId > 0 && !mainSet.contains(parentId) && parentMap.containsKey(parentId)) {
                    parentId = parentMap.get(parentId);
                }
                if (parentId <= 0 || !mainSet.contains(parentId)) {
                    parentId = mainTrunkNodes.get(0).getId();
                }

                if (!branchNodesMap.containsKey(parentId)) {
                    branchNodesMap.put(parentId, new ArrayList<>());
                }
                branchNodesMap.get(parentId).add(node);
            }
        }

        // 4. Calculate initial organic 2D constellation layout
        int maxTopBranches = 0;
        int maxBottomBranches = 0;
        for (List<FranchiseResponse.NodeItem> branches : branchNodesMap.values()) {
            int top = (branches.size() + 1) / 2;
            int bottom = branches.size() / 2;
            if (top > maxTopBranches) maxTopBranches = top;
            if (bottom > maxBottomBranches) maxBottomBranches = bottom;
        }

        float yCenter = paddingY + Math.max(1, maxTopBranches) * (cardHeightPx + rowSpacingPx * 1.35f);
        float currentX = paddingX;

        for (int i = 0; i < mainTrunkNodes.size(); i++) {
            FranchiseResponse.NodeItem mainNode = mainTrunkNodes.get(i);
            List<FranchiseResponse.NodeItem> branches = branchNodesMap.get(mainNode.getId());
            int branchCount = (branches != null) ? branches.size() : 0;

            // Gentle organic vertical wave for main storyline
            float trunkWave = (float) Math.sin(i * 1.25f) * (cardHeightPx * 0.20f);
            float trunkJitterX = ((float) Math.sin(mainNode.getId() * 0.31f)) * (12f * density);
            float trunkY = yCenter + trunkWave;
            float trunkX = currentX + trunkJitterX;

            nodePositionsMap.put(mainNode.getId(), new PointF(trunkX, trunkY));

            if (branches != null && !branches.isEmpty()) {
                // Organic radial/diagonal fan-out around the trunk node
                // Offset is always forward (>= 0) so branches never sit on incoming trunk links!
                int topCount = 0;
                int bottomCount = 0;

                for (int b = 0; b < branches.size(); b++) {
                    FranchiseResponse.NodeItem branch = branches.get(b);
                    boolean isTop = (b % 2 == 0);

                    float bx;
                    float by;

                    // Deterministic subtle organic jitter
                    float jx = ((float) Math.sin(branch.getId() * 1.73f)) * (14f * density);
                    float jy = ((float) Math.cos(branch.getId() * 2.19f)) * (10f * density);

                    if (isTop) {
                        topCount++;
                        int tier = (topCount - 1) / 2 + 1;
                        boolean farShift = (topCount % 2 == 1);
                        float offsetX = farShift ? (cardWidthPx * 0.45f) : (cardWidthPx * 0.12f);
                        bx = trunkX + offsetX + (tier - 1) * (cardWidthPx * 0.35f) + jx;
                        by = trunkY - tier * (cardHeightPx + rowSpacingPx * 1.35f) + jy;
                    } else {
                        bottomCount++;
                        int tier = (bottomCount - 1) / 2 + 1;
                        boolean farShift = (bottomCount % 2 == 1);
                        float offsetX = farShift ? (cardWidthPx * 0.45f) : (cardWidthPx * 0.12f);
                        bx = trunkX + offsetX + (tier - 1) * (cardWidthPx * 0.35f) + jx;
                        by = trunkY + tier * (cardHeightPx + rowSpacingPx * 1.35f) + jy;
                    }

                    nodePositionsMap.put(branch.getId(), new PointF(bx, by));
                }
            }

            // Variable spacing between main nodes based on how many branches occupy the space
            float step = cardWidthPx + colSpacingPx + (branchCount * (cardWidthPx * 0.42f));
            currentX += step;
        }

        // 5. Overlap Prevention, Line Clearance & Physics Relaxation (Pass 2)
        float minGapX = cardWidthPx + (40f * density);
        float minGapY = cardHeightPx + (32f * density);

        for (int iter = 0; iter < 40; iter++) {
            // A. Collision repulsion between all pairs of nodes
            for (int i = 0; i < allSorted.size(); i++) {
                long idA = allSorted.get(i).getId();
                PointF posA = nodePositionsMap.get(idA);
                if (posA == null) continue;
                boolean isMainA = mainSet.contains(idA);

                for (int j = i + 1; j < allSorted.size(); j++) {
                    long idB = allSorted.get(j).getId();
                    PointF posB = nodePositionsMap.get(idB);
                    if (posB == null) continue;
                    boolean isMainB = mainSet.contains(idB);

                    float dx = (posB.x + cardWidthPx / 2f) - (posA.x + cardWidthPx / 2f);
                    float dy = (posB.y + cardHeightPx / 2f) - (posA.y + cardHeightPx / 2f);

                    if (Math.abs(dx) < minGapX && Math.abs(dy) < minGapY) {
                        float overlapX = minGapX - Math.abs(dx);
                        float overlapY = minGapY - Math.abs(dy);

                        float signX = (dx >= 0) ? 1.0f : -1.0f;
                        float signY = (dy >= 0) ? 1.0f : -1.0f;

                        float pushX = signX * overlapX * 0.50f;
                        float pushY = signY * overlapY * 0.50f;

                        // Main trunk nodes are more rigid, branch nodes take more displacement
                        float weightA = isMainA ? (isMainB ? 0.5f : 0.15f) : (isMainB ? 0.85f : 0.5f);
                        float weightB = 1.0f - weightA;

                        posA.x -= pushX * weightA;
                        posA.y -= pushY * weightA;
                        posB.x += pushX * weightB;
                        posB.y += pushY * weightB;
                    }
                }
            }

            // B. Line-to-card clearance pass: actively push cards away from intersecting connection lines
            for (MapLink link : canonicalLinks) {
                PointF pA = nodePositionsMap.get(link.getSourceId());
                PointF pB = nodePositionsMap.get(link.getTargetId());
                if (pA == null || pB == null) continue;

                float ax = pA.x + cardWidthPx / 2f;
                float ay = pA.y + cardHeightPx / 2f;
                float bx = pB.x + cardWidthPx / 2f;
                float by = pB.y + cardHeightPx / 2f;

                float segMinX = Math.min(ax, bx) - 16f * density;
                float segMaxX = Math.max(ax, bx) + 16f * density;
                float denom = bx - ax;

                for (FranchiseResponse.NodeItem node : allSorted) {
                    long nId = node.getId();
                    if (nId == link.getSourceId() || nId == link.getTargetId()) continue;

                    PointF pC = nodePositionsMap.get(nId);
                    if (pC == null) continue;

                    float cx = pC.x + cardWidthPx / 2f;
                    float cy = pC.y + cardHeightPx / 2f;

                    if (cx > segMinX && cx < segMaxX && Math.abs(denom) > 10f * density) {
                        float t = Math.max(0.05f, Math.min(0.95f, (cx - ax) / denom));
                        float lineY = ay + t * (by - ay);

                        float reqClearanceY = (cardHeightPx / 2f) + (36f * density);
                        float diffY = cy - lineY;

                        if (Math.abs(diffY) < reqClearanceY) {
                            float push = (reqClearanceY - Math.abs(diffY)) * 0.50f;
                            if (diffY >= 0) {
                                pC.y += push;
                            } else {
                                pC.y -= push;
                            }
                        }
                    }
                }
            }

            // C. Directional link flow: target should be to the right of source
            for (MapLink link : canonicalLinks) {
                PointF pSrc = nodePositionsMap.get(link.getSourceId());
                PointF pTgt = nodePositionsMap.get(link.getTargetId());
                if (pSrc == null || pTgt == null) continue;

                if (pTgt.x < pSrc.x + 24f * density) {
                    float push = (pSrc.x + 24f * density - pTgt.x) * 0.25f;
                    pTgt.x += push;
                }
            }
        }

        // 6. Normalize bounds and add generous 2D padding
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        for (PointF pt : nodePositionsMap.values()) {
            if (pt.x < minX) minX = pt.x;
            if (pt.y < minY) minY = pt.y;
        }

        float shiftX = paddingX - minX;
        float shiftY = paddingY - minY;
        for (PointF pt : nodePositionsMap.values()) {
            pt.x += shiftX;
            pt.y += shiftY;
        }

        nodeBoundsMap.clear();
        float maxX = 0;
        float maxY = 0;
        for (Map.Entry<Long, PointF> entry : nodePositionsMap.entrySet()) {
            long nodeId = entry.getKey();
            PointF pt = entry.getValue();
            if (pt.x + cardWidthPx > maxX) maxX = pt.x + cardWidthPx;
            if (pt.y + cardHeightPx > maxY) maxY = pt.y + cardHeightPx;
            nodeBoundsMap.put(nodeId, new RectF(pt.x, pt.y, pt.x + cardWidthPx, pt.y + cardHeightPx));
        }

        contentWidth = (int) (maxX + paddingX * 1.5f);
        contentHeight = Math.max((int) (680 * density), (int) (maxY + paddingY * 1.5f));
    }

    private View createNodeCardView(LayoutInflater inflater, FranchiseResponse.NodeItem node, long currentId) {
        View view = inflater.inflate(R.layout.item_franchise_map_card, this, false);

        MaterialCardView card = view.findViewById(R.id.cardMapNode);
        ImageView ivCover = view.findViewById(R.id.ivMapCardCover);
        TextView tvCurrentBadge = view.findViewById(R.id.tvMapCardCurrentBadge);
        TextView tvRelation = view.findViewById(R.id.tvMapCardRelation);
        TextView tvTitle = view.findViewById(R.id.tvMapCardTitle);
        TextView tvKind = view.findViewById(R.id.tvMapCardKind);

        if (tvTitle != null) tvTitle.setText(node.getName());
        if (tvKind != null) tvKind.setText(node.getFormattedKind());

        String relation = nodeRelationsMap.get(node.getId());
        if (tvRelation != null) {
            if (!TextUtils.isEmpty(relation)) {
                tvRelation.setText(relation);
                tvRelation.setVisibility(VISIBLE);
            } else {
                tvRelation.setVisibility(GONE);
            }
        }

        String imageUrl = node.getFullImageUrl();
        if (ivCover != null) {
            if (!TextUtils.isEmpty(imageUrl)) {
                ImageLoader.getInstance().loadInto(ivCover, imageUrl, R.drawable.ic_image_placeholder);
            } else {
                ivCover.setImageResource(R.drawable.ic_image_placeholder);
            }
        }

        boolean isCurrent = (node.getId() == currentId);
        if (isCurrent && card != null && tvCurrentBadge != null) {
            tvCurrentBadge.setVisibility(VISIBLE);
            card.setStrokeColor(ContextCompat.getColor(getContext(), R.color.purple_primary));
            card.setStrokeWidth(Math.max(1, (int) (0.5f * getResources().getDisplayMetrics().density + 0.5f)));
        }

        view.setClickable(false);
        view.setFocusable(false);
        if (card != null) {
            card.setClickable(false);
            card.setFocusable(false);
        }

        return view;
    }

    public void selectNode(long nodeId) {
        this.selectedNodeId = nodeId;
        this.connectedNodeIds.clear();

        if (nodeId > 0) {
            connectedNodeIds.add(nodeId);
            for (MapLink link : canonicalLinks) {
                if (link.getSourceId() == nodeId) {
                    connectedNodeIds.add(link.getTargetId());
                } else if (link.getTargetId() == nodeId) {
                    connectedNodeIds.add(link.getSourceId());
                }
            }
        }

        for (Map.Entry<Long, View> entry : nodeViewsMap.entrySet()) {
            View view = entry.getValue();
            long id = entry.getKey();
            if (selectedNodeId <= 0 || connectedNodeIds.contains(id)) {
                view.setAlpha(1.0f);
            } else {
                view.setAlpha(0.35f);
            }
        }

        invalidate();
    }

    public void selectBranchLink(MapLink link) {
        if (link == null) {
            this.selectedNodeId = -1;
            this.connectedNodeIds.clear();
            for (View view : nodeViewsMap.values()) {
                view.setAlpha(1.0f);
            }
            invalidate();
            return;
        }

        this.selectedNodeId = -1;
        this.connectedNodeIds.clear();

        Set<Long> branchNodes = new HashSet<>();
        collectBranchNodes(link.getSourceId(), branchNodes);
        collectBranchNodes(link.getTargetId(), branchNodes);
        this.connectedNodeIds.addAll(branchNodes);

        for (Map.Entry<Long, View> entry : nodeViewsMap.entrySet()) {
            View view = entry.getValue();
            long id = entry.getKey();
            if (connectedNodeIds.contains(id)) {
                view.setAlpha(1.0f);
            } else {
                view.setAlpha(0.30f);
            }
        }

        invalidate();
    }

    private void collectBranchNodes(long startNodeId, Set<Long> visited) {
        if (canonicalLinks.isEmpty()) return;

        List<Long> queue = new ArrayList<>();
        queue.add(startNodeId);
        visited.add(startNodeId);

        int head = 0;
        while (head < queue.size()) {
            long current = queue.get(head++);
            for (MapLink link : canonicalLinks) {
                if (link.getSourceId() == current && !visited.contains(link.getTargetId())) {
                    visited.add(link.getTargetId());
                    queue.add(link.getTargetId());
                } else if (link.getTargetId() == current && !visited.contains(link.getSourceId())) {
                    visited.add(link.getSourceId());
                    queue.add(link.getSourceId());
                }
            }
        }
    }

    private static class CurvePoints {
        float startX, startY;
        float c1x, c1y;
        float c2x, c2y;
        float endX, endY;
    }

    private CurvePoints calculateCurvePoints(MapLink link, float density) {
        RectF sourceRect = nodeBoundsMap.get(link.getSourceId());
        RectF targetRect = nodeBoundsMap.get(link.getTargetId());
        if (sourceRect == null || targetRect == null) return null;

        CurvePoints pts = new CurvePoints();

        float dx = targetRect.centerX() - sourceRect.centerX();
        float dy = targetRect.centerY() - sourceRect.centerY();

        // 1. Dynamic Port Anchor Points Selection based on relative vector
        if (dx >= 20f * density) {
            // Target is to the RIGHT of source (forward flow)
            pts.startX = sourceRect.right;
            pts.endX = targetRect.left;

            if (dy < -40f * density) {
                // Target is higher up
                pts.startY = sourceRect.top + 22f * density;
                pts.endY = targetRect.bottom - 22f * density;
            } else if (dy > 40f * density) {
                // Target is lower down
                pts.startY = sourceRect.bottom - 22f * density;
                pts.endY = targetRect.top + 22f * density;
            } else {
                // Roughly in the same horizontal band
                pts.startY = sourceRect.centerY();
                pts.endY = targetRect.centerY();
            }

            float curveOffset = Math.max(38f * density, dx * 0.45f);
            pts.c1x = pts.startX + curveOffset;
            pts.c1y = pts.startY;
            pts.c2x = pts.endX - curveOffset;
            pts.c2y = pts.endY;

        } else if (dx <= -20f * density) {
            // Target is to the LEFT of source
            pts.startX = sourceRect.left;
            pts.endX = targetRect.right;
            pts.startY = sourceRect.centerY();
            pts.endY = targetRect.centerY();

            float curveOffset = Math.max(38f * density, -dx * 0.45f);
            pts.c1x = pts.startX - curveOffset;
            pts.c1y = pts.startY;
            pts.c2x = pts.endX + curveOffset;
            pts.c2y = pts.endY;

        } else {
            // Nearly vertical alignment (|dx| < 20dp)
            pts.startX = sourceRect.centerX();
            pts.endX = targetRect.centerX();

            if (dy > 0) {
                // Target is below
                pts.startY = sourceRect.bottom;
                pts.endY = targetRect.top;
            } else {
                // Target is above
                pts.startY = sourceRect.top;
                pts.endY = targetRect.bottom;
            }

            float arcOffset = 30f * density;
            pts.c1x = pts.startX + arcOffset;
            pts.c1y = pts.startY + dy * 0.35f;
            pts.c2x = pts.endX + arcOffset;
            pts.c2y = pts.endY - dy * 0.35f;
        }

        // 2. Obstacle Avoidance: bypass intermediate card rects using 5-point curve sampling
        float minX = Math.min(pts.startX, pts.endX) - 10f * density;
        float maxX = Math.max(pts.startX, pts.endX) + 10f * density;

        for (Map.Entry<Long, RectF> entry : nodeBoundsMap.entrySet()) {
            long nodeId = entry.getKey();
            if (nodeId == link.getSourceId() || nodeId == link.getTargetId()) continue;

            RectF rect = entry.getValue();
            if (rect == null) continue;

            if (rect.right < minX || rect.left > maxX) continue;

            boolean intersects = false;
            for (int s = 1; s <= 5; s++) {
                float t = s / 6.0f;
                float oneMinusT = 1f - t;
                float px = oneMinusT * oneMinusT * oneMinusT * pts.startX
                        + 3f * oneMinusT * oneMinusT * t * pts.c1x
                        + 3f * oneMinusT * t * t * pts.c2x
                        + t * t * t * pts.endX;
                float py = oneMinusT * oneMinusT * oneMinusT * pts.startY
                        + 3f * oneMinusT * oneMinusT * t * pts.c1y
                        + 3f * oneMinusT * t * t * pts.c2y
                        + t * t * t * pts.endY;

                if (px >= rect.left - 12f * density && px <= rect.right + 12f * density
                        && py >= rect.top - 12f * density && py <= rect.bottom + 12f * density) {
                    intersects = true;
                    break;
                }
            }

            if (intersects) {
                float midY = (pts.startY + pts.endY) / 2f;
                if (midY < rect.centerY()) {
                    float avoidY = rect.top - 24f * density;
                    pts.c1y = Math.min(pts.c1y, avoidY);
                    pts.c2y = Math.min(pts.c2y, avoidY);
                } else {
                    float avoidY = rect.bottom + 24f * density;
                    pts.c1y = Math.max(pts.c1y, avoidY);
                    pts.c2y = Math.max(pts.c2y, avoidY);
                }
            }
        }

        return pts;
    }

    private MapLink findLinkAt(float canvasX, float canvasY) {
        if (canonicalLinks.isEmpty()) return null;

        float density = getResources().getDisplayMetrics().density;
        float maxTouchDistPx = 14f * density;
        MapLink bestLink = null;
        float minDistance = maxTouchDistPx;

        for (MapLink link : canonicalLinks) {
            CurvePoints pts = calculateCurvePoints(link, density);
            if (pts == null) continue;

            for (int i = 0; i <= 16; i++) {
                float t = i / 16f;
                float oneMinusT = 1f - t;

                float px = oneMinusT * oneMinusT * oneMinusT * pts.startX
                        + 3f * oneMinusT * oneMinusT * t * pts.c1x
                        + 3f * oneMinusT * t * t * pts.c2x
                        + t * t * t * pts.endX;

                float py = oneMinusT * oneMinusT * oneMinusT * pts.startY
                        + 3f * oneMinusT * oneMinusT * t * pts.c1y
                        + 3f * oneMinusT * t * t * pts.c2y
                        + t * t * t * pts.endY;

                float dist = (float) Math.hypot(canvasX - px, canvasY - py);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestLink = link;
                }
            }
        }
        return bestLink;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int childWidthSpec = MeasureSpec.makeMeasureSpec(cardWidthPx, MeasureSpec.EXACTLY);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(cardHeightPx, MeasureSpec.EXACTLY);

        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec);
        }

        int measuredWidth = resolveSize((int) (contentWidth * scaleFactor), widthMeasureSpec);
        int measuredHeight = resolveSize((int) (contentHeight * scaleFactor), heightMeasureSpec);

        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        nodeBoundsMap.clear();

        for (Map.Entry<Long, PointF> entry : nodePositionsMap.entrySet()) {
            long nodeId = entry.getKey();
            PointF pos = entry.getValue();
            View child = nodeViewsMap.get(nodeId);

            if (child != null && pos != null) {
                int left = (int) pos.x;
                int top = (int) pos.y;
                int right = left + cardWidthPx;
                int bottom = top + cardHeightPx;

                child.layout(left, top, right, bottom);
                nodeBoundsMap.put(nodeId, new RectF(left, top, right, bottom));
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(scaleFactor, scaleFactor);

        // 1. Draw Connecting Bezier Curves between nodes
        drawConnectionCurves(canvas);

        // 2. Draw native Child Views (Node Cards) on top
        super.dispatchDraw(canvas);

        canvas.restore();
    }

    private void drawConnectionCurves(Canvas canvas) {
        if (canonicalLinks.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        Path bezierPath = new Path();

        boolean isSelectionActive = !connectedNodeIds.isEmpty() || selectedNodeId > 0;

        for (MapLink link : canonicalLinks) {
            CurvePoints pts = calculateCurvePoints(link, density);
            if (pts == null) continue;

            boolean isLinkInActiveBranch = isSelectionActive &&
                    connectedNodeIds.contains(link.getSourceId()) &&
                    connectedNodeIds.contains(link.getTargetId());

            Paint paintToUse;
            if (isSelectionActive && !isLinkInActiveBranch) {
                paintToUse = linePaintDimmed;
            } else {
                paintToUse = getPaintForRelation(link.getRelation());
            }

            bezierPath.reset();
            bezierPath.moveTo(pts.startX, pts.startY);
            bezierPath.cubicTo(pts.c1x, pts.c1y, pts.c2x, pts.c2y, pts.endX, pts.endY);

            float origWidth = paintToUse.getStrokeWidth();
            if (isSelectionActive && isLinkInActiveBranch) {
                paintToUse.setStrokeWidth(2.2f * density);
            }

            canvas.drawPath(bezierPath, paintToUse);

            arrowPaint.setColor(paintToUse.getColor());
            canvas.drawCircle(pts.endX, pts.endY, 2.2f * density, arrowPaint);

            if (isSelectionActive && isLinkInActiveBranch) {
                paintToUse.setStrokeWidth(origWidth);
            }
        }
    }

    private Paint getPaintForRelation(String relation) {
        if (relation == null) return linePaintDefault;
        switch (relation.toLowerCase().trim()) {
            case "sequel":
            case "prequel":
                return linePaintSequel;
            case "parent_story":
            case "full_story":
                return linePaintMain;
            case "side_story":
            case "spin_off":
                return linePaintSide;
            case "summary":
                return linePaintSummary;
            default:
                return linePaintDefault;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        scaleGestureDetector.onTouchEvent(ev);

        if (scaleGestureDetector.isInProgress()) {
            return true;
        }

        gestureDetector.onTouchEvent(ev);

        if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
            clampScroll();
        }

        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return dispatchTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    private void clampScroll() {
        int maxScrollX = getMaxScrollX();
        int maxScrollY = getMaxScrollY();

        int clampedX = Math.max(0, Math.min(getScrollX(), maxScrollX));
        int clampedY = Math.max(0, Math.min(getScrollY(), maxScrollY));

        if (clampedX != getScrollX() || clampedY != getScrollY()) {
            scrollTo(clampedX, clampedY);
        }
    }

    private int getMaxScrollX() {
        return Math.max(0, (int) (contentWidth * scaleFactor) - getWidth());
    }

    private int getMaxScrollY() {
        return Math.max(0, (int) (contentHeight * scaleFactor) - getHeight());
    }
}
