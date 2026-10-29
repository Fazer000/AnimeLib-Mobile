package com.example.animelib.views;

import android.animation.ValueAnimator;
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
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
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
 * Интерактивная граф-карта франшизы аниме в виде эластичной паутины (Spiderweb Graph).
 * - Радиальное логическое распределение вокруг текущего аниме (текущий тайтл в центре)
 * - Интерактивное перетягивание карточек с эластичной физикой паутины (связанные айтемы тянутся следом)
 * - Прямые плавные кривые Безье между карточками без искусственного огибания
 * - Поддержка зума (Pinch-to-Zoom), панорамирования (Pan & Fling) и выделения веток
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

    private final Map<Long, PointF> nodePositionsMap = new HashMap<>();
    private final List<MapLink> canonicalLinks = new ArrayList<>();

    private final Map<Long, View> nodeViewsMap = new HashMap<>();
    private final Map<Long, RectF> nodeBoundsMap = new HashMap<>();
    private final Map<Long, String> nodeRelationsMap = new HashMap<>();

    private long selectedNodeId = -1;
    private MapLink selectedLink = null;
    private final Set<Long> connectedNodeIds = new HashSet<>();

    // Zoom & Scale
    private float scaleFactor = 0.70f;
    private static final float MIN_SCALE = 0.30f;
    private static final float MAX_SCALE = 2.20f;
    private ScaleGestureDetector scaleGestureDetector;

    // 2D Scroll & Fling
    private OverScroller scroller;
    private GestureDetector gestureDetector;
    private int contentWidth = 0;
    private int contentHeight = 0;

    // Interactive Dragging & Spiderweb Elastic Physics
    private long draggedNodeId = -1;
    private boolean isDraggingNode = false;
    private boolean hasDragged = false;
    private float dragStartFingerX = 0f;
    private float dragStartFingerY = 0f;
    private float touchSlopPx = 16f;

    private final Map<Long, PointF> dragStartPositions = new HashMap<>();
    private final Set<Long> degree1Neighbors = new HashSet<>();
    private final Set<Long> degree2Neighbors = new HashSet<>();
    private ValueAnimator settleAnimator = null;

    // Paints for Canvas curves
    private Paint linePaintSequel;
    private Paint linePaintSide;
    private Paint linePaintSummary;
    private Paint linePaintMain;
    private Paint linePaintDefault;
    private Paint linePaintDimmed;
    private Paint linePaintAlternative;
    private Paint dotPaint;

    // Layout dimensions in dp
    private int cardWidthPx;
    private int cardHeightPx;
    private int paddingX;
    private int paddingY;
    private long currentFocalId = -1;

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
        touchSlopPx = ViewConfiguration.get(context).getScaledTouchSlop();

        float density = context.getResources().getDisplayMetrics().density;
        cardWidthPx = (int) (240 * density);
        cardHeightPx = (int) (100 * density);
        paddingX = (int) (650 * density);
        paddingY = (int) (650 * density);

        initPaints(context, density);

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (Float.isNaN(factor) || Float.isInfinite(factor) || factor <= 0f) {
                    return true;
                }
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                if (Float.isNaN(focusX) || Float.isInfinite(focusX)) {
                    focusX = getWidth() > 0 ? getWidth() / 2f : 0f;
                }
                if (Float.isNaN(focusY) || Float.isInfinite(focusY)) {
                    focusY = getHeight() > 0 ? getHeight() / 2f : 0f;
                }
                setScaleFactor(scaleFactor * factor, focusX, focusY);
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float canvasX = (e.getX() + getScrollX()) / scaleFactor;
                float canvasY = (e.getY() + getScrollY()) / scaleFactor;

                // 1. Tap on empty space: check for branch curve selection
                MapLink clickedLink = findLinkAt(canvasX, canvasY);
                if (clickedLink != null) {
                    selectBranchLink(clickedLink);
                    return true;
                } else {
                    // Tap on background clears selection
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
        linePaintSequel = createCurvePaint(0xFFA855F7, 3.2f * density, false);      // Фиолетовый сиквел/приквел
        linePaintMain = createCurvePaint(0xFF3B82F6, 3.2f * density, false);        // Синий основной сюжет
        linePaintSide = createCurvePaint(0xFF06B6D4, 1.8f * density, true);         // Бирюзовый пунктир сайд-стори
        linePaintAlternative = createCurvePaint(0xFF10B981, 1.8f * density, true);  // Изумрудный пунктир альтернатива
        linePaintSummary = createCurvePaint(0xFFF59E0B, 1.6f * density, false);     // Янтарный рекап
        linePaintDefault = createCurvePaint(0xFF8E8E93, 1.8f * density, false);     // Серый по умолчанию
        linePaintDimmed = createCurvePaint(0x338E8E93, 1.0f * density, false);      // Приглушенный

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);
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
        if (Float.isNaN(newScale) || Float.isInfinite(newScale) || newScale <= 0f) {
            return;
        }
        if (Float.isNaN(focusX) || Float.isInfinite(focusX)) {
            focusX = getWidth() > 0 ? getWidth() / 2f : 0f;
        }
        if (Float.isNaN(focusY) || Float.isInfinite(focusY)) {
            focusY = getHeight() > 0 ? getHeight() / 2f : 0f;
        }

        float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        if (Math.abs(this.scaleFactor - clamped) > 0.001f) {
            float oldScale = this.scaleFactor;
            if (oldScale <= 0.001f || Float.isNaN(oldScale)) {
                oldScale = 1.0f;
            }
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
        nodePositionsMap.clear();
        nodeViewsMap.clear();
        nodeBoundsMap.clear();
        nodeRelationsMap.clear();
        canonicalLinks.clear();
        selectedNodeId = -1;
        selectedLink = null;
        connectedNodeIds.clear();

        if (settleAnimator != null && settleAnimator.isRunning()) {
            settleAnimator.cancel();
        }

        if (franchise == null || franchise.getNodes() == null || franchise.getNodes().isEmpty()) {
            contentWidth = 0;
            contentHeight = 0;
            requestLayout();
            invalidate();
            return;
        }

        buildCanonicalLinks();
        nodeRelationsMap.putAll(FranchiseChronologyBottomSheet.buildRelationsMap(franchise));

        calculateRadialSpiderwebLayout();

        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (FranchiseResponse.NodeItem node : franchise.getNodes()) {
            View cardView = createNodeCardView(inflater, node, franchise.getCurrentId());
            nodeViewsMap.put(node.getId(), cardView);
            addView(cardView);
        }

        requestLayout();
        invalidate();

        // Автоцентрирование на текущем тайтле после построения паутины
        post(() -> centerOnCurrent(false));
    }

    public void centerOnCurrent(boolean animate) {
        if (currentFocalId > 0) {
            centerOnNode(currentFocalId, animate);
        }
    }

    public void centerOnNode(long nodeId, boolean animate) {
        PointF pos = nodePositionsMap.get(nodeId);
        if (pos == null) return;

        float cardCenterX = pos.x + cardWidthPx / 2f;
        float cardCenterY = pos.y + cardHeightPx / 2f;

        int targetScrollX = Math.round(cardCenterX * scaleFactor - getWidth() / 2f);
        int targetScrollY = Math.round(cardCenterY * scaleFactor - getHeight() / 2f);

        int clampedX = Math.max(0, Math.min(targetScrollX, getMaxScrollX()));
        int clampedY = Math.max(0, Math.min(targetScrollY, getMaxScrollY()));

        if (animate) {
            scroller.startScroll(getScrollX(), getScrollY(), clampedX - getScrollX(), clampedY - getScrollY(), 350);
            postInvalidateOnAnimation();
        } else {
            scrollTo(clampedX, clampedY);
        }
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

            // Нормализуем направление Parent/Predecessor -> Child/Branch
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

    /**
     * Радиальное распределение паутины (Spiderweb Radial Layout) с фокусом на текущем аниме
     */
    private void calculateRadialSpiderwebLayout() {
        if (franchiseData == null || franchiseData.getNodes() == null || franchiseData.getNodes().isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        List<FranchiseResponse.NodeItem> allSorted = FranchiseChronologyBottomSheet.sortNodesChronologically(franchiseData);

        // 1. Определение фокальной ноды (центр паутины)
        long focalId = franchiseData.getCurrentId();
        boolean focalExists = false;
        for (FranchiseResponse.NodeItem n : allSorted) {
            if (n.getId() == focalId) {
                focalExists = true;
                break;
            }
        }
        if (!focalExists) {
            focalId = allSorted.get(0).getId();
        }
        currentFocalId = focalId;

        // 2. Построение графа смежности
        Map<Long, List<Long>> adj = new HashMap<>();
        for (FranchiseResponse.NodeItem n : allSorted) {
            adj.put(n.getId(), new ArrayList<>());
        }
        for (MapLink link : canonicalLinks) {
            long s = link.getSourceId();
            long t = link.getTargetId();
            if (adj.containsKey(s) && adj.containsKey(t)) {
                if (!adj.get(s).contains(t)) adj.get(s).add(t);
                if (!adj.get(t).contains(s)) adj.get(t).add(s);
            }
        }

        // 3. BFS слои расстояний от фокальной ноды
        Map<Long, Integer> layerMap = new HashMap<>();
        Map<Long, Long> bfsParentMap = new HashMap<>();
        List<Long> queue = new ArrayList<>();

        layerMap.put(focalId, 0);
        queue.add(focalId);

        int head = 0;
        while (head < queue.size()) {
            long curr = queue.get(head++);
            int currLayer = layerMap.get(curr);
            List<Long> neighbors = adj.get(curr);
            if (neighbors != null) {
                for (long nbr : neighbors) {
                    if (!layerMap.containsKey(nbr)) {
                        layerMap.put(nbr, currLayer + 1);
                        bfsParentMap.put(nbr, curr);
                        queue.add(nbr);
                    }
                }
            }
        }

        // Для изолированных нод (если есть отдельные компоненты графа)
        for (FranchiseResponse.NodeItem n : allSorted) {
            if (!layerMap.containsKey(n.getId())) {
                layerMap.put(n.getId(), 2);
                bfsParentMap.put(n.getId(), focalId);
            }
        }

        // 4. Группировка нод по слоям
        Map<Integer, List<FranchiseResponse.NodeItem>> nodesByLayer = new HashMap<>();
        int maxLayer = 0;
        for (FranchiseResponse.NodeItem n : allSorted) {
            int l = layerMap.get(n.getId());
            if (l > maxLayer) maxLayer = l;
            if (!nodesByLayer.containsKey(l)) {
                nodesByLayer.put(l, new ArrayList<>());
            }
            nodesByLayer.get(l).add(n);
        }

        // 5. Размещение центра и концентрических орбит паутины
        float virtualCenterX = 2400f * density;
        float virtualCenterY = 2400f * density;

        nodePositionsMap.put(focalId, new PointF(
                virtualCenterX - cardWidthPx / 2f,
                virtualCenterY - cardHeightPx / 2f
        ));

        float baseRadius = Math.max(390f * density, cardWidthPx * 1.60f);
        float layerRadiusStep = Math.max(340f * density, cardWidthPx * 1.40f);

        // Хранилище углов нод в радианах
        Map<Long, Float> nodeAnglesMap = new HashMap<>();
        nodeAnglesMap.put(focalId, 0f);

        // Распределение Слоя 1 (Непосредственные связи)
        List<FranchiseResponse.NodeItem> layer1Nodes = nodesByLayer.get(1);
        if (layer1Nodes != null && !layer1Nodes.isEmpty()) {
            List<FranchiseResponse.NodeItem> sequels = new ArrayList<>();
            List<FranchiseResponse.NodeItem> prequels = new ArrayList<>();
            List<FranchiseResponse.NodeItem> topBranches = new ArrayList<>();
            List<FranchiseResponse.NodeItem> bottomBranches = new ArrayList<>();

            for (FranchiseResponse.NodeItem item : layer1Nodes) {
                String rel = nodeRelationsMap.get(item.getId());
                String r = rel != null ? rel.toLowerCase() : "";

                if (r.contains("сиквел") || r.contains("sequel")) {
                    sequels.add(item);
                } else if (r.contains("приквел") || r.contains("prequel")) {
                    prequels.add(item);
                } else {
                    // Равномерно раскидываем в верхнее и нижнее полушарие
                    if (topBranches.size() <= bottomBranches.size()) {
                        topBranches.add(item);
                    } else {
                        bottomBranches.add(item);
                    }
                }
            }

            // Сиквелы располагаются на горизонтальной правой оси (0 рад)
            distributeNodesArc(sequels, 0f, (float) Math.toRadians(40), baseRadius, virtualCenterX, virtualCenterY, nodeAnglesMap);

            // Приквелы располагаются на горизонтальной левой оси (PI рад)
            distributeNodesArc(prequels, (float) Math.PI, (float) Math.toRadians(40), baseRadius, virtualCenterX, virtualCenterY, nodeAnglesMap);

            // Верхние ветви (сайд-стори) в верхнем секторе [-140°, -40°]
            distributeNodesSector(topBranches, (float) Math.toRadians(-145), (float) Math.toRadians(-35), baseRadius, virtualCenterX, virtualCenterY, nodeAnglesMap);

            // Нижние ветви (спин-оффы, рекапы) в нижнем секторе [40°, 140°]
            distributeNodesSector(bottomBranches, (float) Math.toRadians(35), (float) Math.toRadians(145), baseRadius, virtualCenterX, virtualCenterY, nodeAnglesMap);
        }

        // Распределение Слоев 2+ (Концентрические кольца паутины)
        for (int l = 2; l <= maxLayer; l++) {
            List<FranchiseResponse.NodeItem> layerNodes = nodesByLayer.get(l);
            if (layerNodes == null || layerNodes.isEmpty()) continue;

            float r = baseRadius + (l - 1) * layerRadiusStep;

            // Группируем по BFS-родителям для сохранения органической топологии паутины
            Map<Long, List<FranchiseResponse.NodeItem>> byParent = new HashMap<>();
            for (FranchiseResponse.NodeItem n : layerNodes) {
                long pId = bfsParentMap.containsKey(n.getId()) ? bfsParentMap.get(n.getId()) : focalId;
                if (!byParent.containsKey(pId)) {
                    byParent.put(pId, new ArrayList<>());
                }
                byParent.get(pId).add(n);
            }

            for (Map.Entry<Long, List<FranchiseResponse.NodeItem>> entry : byParent.entrySet()) {
                long parentId = entry.getKey();
                List<FranchiseResponse.NodeItem> children = entry.getValue();
                float parentAngle = nodeAnglesMap.containsKey(parentId) ? nodeAnglesMap.get(parentId) : 0f;

                float maxSpan = Math.min((float) Math.PI * 0.65f, (children.size() - 1) * (float) Math.toRadians(32));
                distributeNodesArc(children, parentAngle, maxSpan, r, virtualCenterX, virtualCenterY, nodeAnglesMap);
            }
        }

        // 6. Релаксация перекрытий (Physics Repulsion Pass) — гарантия читаемости без наслоений
        float minGapX = cardWidthPx + (36f * density);
        float minGapY = cardHeightPx + (28f * density);

        for (int iter = 0; iter < 24; iter++) {
            for (int i = 0; i < allSorted.size(); i++) {
                long idA = allSorted.get(i).getId();
                PointF posA = nodePositionsMap.get(idA);
                if (posA == null) continue;
                boolean isFocalA = (idA == focalId);

                for (int j = i + 1; j < allSorted.size(); j++) {
                    long idB = allSorted.get(j).getId();
                    PointF posB = nodePositionsMap.get(idB);
                    if (posB == null) continue;
                    boolean isFocalB = (idB == focalId);

                    float centerAx = posA.x + cardWidthPx / 2f;
                    float centerAy = posA.y + cardHeightPx / 2f;
                    float centerBx = posB.x + cardWidthPx / 2f;
                    float centerBy = posB.y + cardHeightPx / 2f;

                    float dx = centerBx - centerAx;
                    float dy = centerBy - centerAy;

                    if (Math.abs(dx) < minGapX && Math.abs(dy) < minGapY) {
                        float overlapX = minGapX - Math.abs(dx);
                        float overlapY = minGapY - Math.abs(dy);

                        float signX = (dx >= 0) ? 1.0f : -1.0f;
                        float signY = (dy >= 0) ? 1.0f : -1.0f;

                        float pushX = signX * overlapX * 0.28f;
                        float pushY = signY * overlapY * 0.28f;

                        float weightA = isFocalA ? 0f : (isFocalB ? 1f : 0.5f);
                        float weightB = 1.0f - weightA;

                        posA.x -= pushX * weightA;
                        posA.y -= pushY * weightA;
                        posB.x += pushX * weightB;
                        posB.y += pushY * weightB;
                    }
                }
            }
        }

        // 7. Нормализация координат и установка виртуальных границ холста
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

        contentWidth = Math.max((int) (3200 * density), (int) (maxX + paddingX));
        contentHeight = Math.max((int) (3000 * density), (int) (maxY + paddingY));
    }

    private void distributeNodesArc(List<FranchiseResponse.NodeItem> nodes, float centerAngle, float maxSpan,
                                    float radius, float cx, float cy, Map<Long, Float> anglesMap) {
        if (nodes == null || nodes.isEmpty()) return;
        int count = nodes.size();

        float startAngle;
        float angleStep;

        if (count == 1) {
            startAngle = centerAngle;
            angleStep = 0f;
        } else {
            float span = Math.min(maxSpan, (count - 1) * (float) Math.toRadians(30));
            startAngle = centerAngle - span / 2f;
            angleStep = span / (count - 1);
        }

        for (int i = 0; i < count; i++) {
            FranchiseResponse.NodeItem n = nodes.get(i);
            float angle = startAngle + i * angleStep;
            anglesMap.put(n.getId(), angle);

            float px = cx + radius * (float) Math.cos(angle) - cardWidthPx / 2f;
            float py = cy + radius * (float) Math.sin(angle) - cardHeightPx / 2f;
            nodePositionsMap.put(n.getId(), new PointF(px, py));
        }
    }

    private void distributeNodesSector(List<FranchiseResponse.NodeItem> nodes, float minAngle, float maxAngle,
                                       float radius, float cx, float cy, Map<Long, Float> anglesMap) {
        if (nodes == null || nodes.isEmpty()) return;
        int count = nodes.size();

        float step = (maxAngle - minAngle) / (count + 1);
        for (int i = 0; i < count; i++) {
            FranchiseResponse.NodeItem n = nodes.get(i);
            float angle = minAngle + (i + 1) * step;
            anglesMap.put(n.getId(), angle);

            float px = cx + radius * (float) Math.cos(angle) - cardWidthPx / 2f;
            float py = cy + radius * (float) Math.sin(angle) - cardHeightPx / 2f;
            nodePositionsMap.put(n.getId(), new PointF(px, py));
        }
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
            card.setStrokeWidth(Math.max(1, (int) (1.2f * getResources().getDisplayMetrics().density + 0.5f)));
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
        this.selectedLink = null;
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
                view.setAlpha(0.25f);
            }
        }

        invalidate();
    }

    public void selectBranchLink(MapLink link) {
        if (link == null || (selectedLink != null && (selectedLink.getId() == link.getId() ||
                (selectedLink.getSourceId() == link.getSourceId() && selectedLink.getTargetId() == link.getTargetId())))) {
            this.selectedLink = null;
            this.selectedNodeId = -1;
            this.connectedNodeIds.clear();
            for (View view : nodeViewsMap.values()) {
                view.setAlpha(1.0f);
            }
            invalidate();
            return;
        }

        this.selectedLink = link;
        this.selectedNodeId = -1;
        this.connectedNodeIds.clear();

        Set<Long> lineNodes = findNodesForLine(link);
        this.connectedNodeIds.addAll(lineNodes);

        for (Map.Entry<Long, View> entry : nodeViewsMap.entrySet()) {
            View view = entry.getValue();
            long id = entry.getKey();
            if (connectedNodeIds.contains(id)) {
                view.setAlpha(1.0f);
            } else {
                view.setAlpha(0.25f);
            }
        }

        invalidate();
    }

    private Set<Long> findNodesForLine(MapLink link) {
        Set<Long> result = new HashSet<>();
        if (link == null) return result;

        result.add(link.getSourceId());
        result.add(link.getTargetId());

        // Рекурсивно собираем всю ветвь потомков
        List<Long> queue = new ArrayList<>();
        queue.add(link.getTargetId());
        int head = 0;
        while (head < queue.size()) {
            long curr = queue.get(head++);
            for (MapLink l : canonicalLinks) {
                if (l.getSourceId() == curr) {
                    long childId = l.getTargetId();
                    if (!result.contains(childId)) {
                        result.add(childId);
                        queue.add(childId);
                    }
                }
            }
        }

        return result;
    }

    public MapLink getSelectedLink() {
        return selectedLink;
    }

    private static class CurvePoints {
        float startX, startY;
        float c1x, c1y;
        float c2x, c2y;
        float endX, endY;
    }

    /**
     * Прямая плавная кривая Безье между карточками (без огибания препятствий)
     * Соединение автоматически выбирает наиболее естественную сторону выхода и входа.
     */
    private CurvePoints calculateCurvePoints(MapLink link, float density) {
        RectF sourceRect = nodeBoundsMap.get(link.getSourceId());
        RectF targetRect = nodeBoundsMap.get(link.getTargetId());
        if (sourceRect == null || targetRect == null) return null;

        float scx = sourceRect.centerX();
        float scy = sourceRect.centerY();
        float tcx = targetRect.centerX();
        float tcy = targetRect.centerY();

        float dx = tcx - scx;
        float dy = tcy - scy;

        CurvePoints pts = new CurvePoints();

        // Определение портов выхода и входа на основе взаимного расположения
        if (Math.abs(dx) >= Math.abs(dy)) {
            // Горизонтально-доминантное соединение
            if (dx >= 0) {
                pts.startX = sourceRect.right;
                pts.startY = scy;
                pts.endX = targetRect.left;
                pts.endY = tcy;
                float lead = Math.max(30f * density, Math.abs(pts.endX - pts.startX) * 0.44f);
                pts.c1x = pts.startX + lead;
                pts.c1y = pts.startY;
                pts.c2x = pts.endX - lead;
                pts.c2y = pts.endY;
            } else {
                pts.startX = sourceRect.left;
                pts.startY = scy;
                pts.endX = targetRect.right;
                pts.endY = tcy;
                float lead = Math.max(30f * density, Math.abs(pts.startX - pts.endX) * 0.44f);
                pts.c1x = pts.startX - lead;
                pts.c1y = pts.startY;
                pts.c2x = pts.endX + lead;
                pts.c2y = pts.endY;
            }
        } else {
            // Вертикально-доминантное соединение
            if (dy >= 0) {
                pts.startX = scx;
                pts.startY = sourceRect.bottom;
                pts.endX = tcx;
                pts.endY = targetRect.top;
                float lead = Math.max(30f * density, Math.abs(pts.endY - pts.startY) * 0.44f);
                pts.c1x = pts.startX;
                pts.c1y = pts.startY + lead;
                pts.c2x = pts.endX;
                pts.c2y = pts.endY - lead;
            } else {
                pts.startX = scx;
                pts.startY = sourceRect.top;
                pts.endX = tcx;
                pts.endY = targetRect.bottom;
                float lead = Math.max(30f * density, Math.abs(pts.startY - pts.endY) * 0.44f);
                pts.c1x = pts.startX;
                pts.c1y = pts.startY - lead;
                pts.c2x = pts.endX;
                pts.c2y = pts.endY + lead;
            }
        }

        return pts;
    }

    private MapLink findLinkAt(float canvasX, float canvasY) {
        if (canonicalLinks.isEmpty()) return null;

        float density = getResources().getDisplayMetrics().density;
        float effectiveScale = Math.max(0.30f, Math.min(1.5f, scaleFactor));
        float maxTouchDistPx = (24f * density) / effectiveScale;
        MapLink bestLink = null;
        float minDistance = maxTouchDistPx;

        final int sampleSteps = 20;

        for (MapLink link : canonicalLinks) {
            CurvePoints pts = calculateCurvePoints(link, density);
            if (pts == null) continue;

            float prevPx = pts.startX;
            float prevPy = pts.startY;

            for (int i = 1; i <= sampleSteps; i++) {
                float t = (float) i / sampleSteps;
                float oneMinusT = 1f - t;

                float px = oneMinusT * oneMinusT * oneMinusT * pts.startX
                        + 3f * oneMinusT * oneMinusT * t * pts.c1x
                        + 3f * oneMinusT * t * t * pts.c2x
                        + t * t * t * pts.endX;

                float py = oneMinusT * oneMinusT * oneMinusT * pts.startY
                        + 3f * oneMinusT * oneMinusT * t * pts.c1y
                        + 3f * oneMinusT * t * t * pts.c2y
                        + t * t * t * pts.endY;

                float dist = distanceToSegment(canvasX, canvasY, prevPx, prevPy, px, py);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestLink = link;
                }

                prevPx = px;
                prevPy = py;
            }
        }
        return bestLink;
    }

    private static float distanceToSegment(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float lenSq = dx * dx + dy * dy;
        if (lenSq == 0f) {
            return (float) Math.hypot(px - x1, py - y1);
        }
        float t = Math.max(0f, Math.min(1f, ((px - x1) * dx + (py - y1) * dy) / lenSq));
        float projX = x1 + t * dx;
        float projY = y1 + t * dy;
        return (float) Math.hypot(px - projX, py - projY);
    }

    private long findNodeAt(float canvasX, float canvasY) {
        for (Map.Entry<Long, RectF> entry : nodeBoundsMap.entrySet()) {
            RectF bounds = entry.getValue();
            if (bounds != null && bounds.contains(canvasX, canvasY)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    // ==========================================
    // Эластичная физика паутины (Spiderweb Elastic Drag)
    // ==========================================

    private void initDragPhysics(long nodeId, float fingerX, float fingerY) {
        if (settleAnimator != null && settleAnimator.isRunning()) {
            settleAnimator.cancel();
        }

        draggedNodeId = nodeId;
        isDraggingNode = true;
        hasDragged = false;
        dragStartFingerX = fingerX;
        dragStartFingerY = fingerY;

        dragStartPositions.clear();
        for (Map.Entry<Long, PointF> entry : nodePositionsMap.entrySet()) {
            dragStartPositions.put(entry.getKey(), new PointF(entry.getValue().x, entry.getValue().y));
        }

        degree1Neighbors.clear();
        degree2Neighbors.clear();

        // Поиск соседей 1-й степени (напрямую соединены в графе)
        for (MapLink link : canonicalLinks) {
            if (link.getSourceId() == nodeId) {
                degree1Neighbors.add(link.getTargetId());
            } else if (link.getTargetId() == nodeId) {
                degree1Neighbors.add(link.getSourceId());
            }
        }

        // Поиск соседей 2-й степени (соединены с соседями 1-й степени)
        for (MapLink link : canonicalLinks) {
            long s = link.getSourceId();
            long t = link.getTargetId();
            if (degree1Neighbors.contains(s) && t != nodeId && !degree1Neighbors.contains(t)) {
                degree2Neighbors.add(t);
            } else if (degree1Neighbors.contains(t) && s != nodeId && !degree1Neighbors.contains(s)) {
                degree2Neighbors.add(s);
            }
        }
    }

    private void applyDragMove(float fingerX, float fingerY) {
        if (!isDraggingNode || draggedNodeId <= 0) return;

        float deltaX = fingerX - dragStartFingerX;
        float deltaY = fingerY - dragStartFingerY;

        if (!hasDragged && Math.hypot(deltaX, deltaY) > (touchSlopPx / scaleFactor)) {
            hasDragged = true;
        }

        if (!hasDragged) return;

        PointF startDragged = dragStartPositions.get(draggedNodeId);
        if (startDragged == null) return;

        float density = getResources().getDisplayMetrics().density;
        float minSafeCoord = 20f * density;

        // 1. Перемещаемая нода точно следует за пальцем
        PointF currentDragged = nodePositionsMap.get(draggedNodeId);
        if (currentDragged != null) {
            currentDragged.x = Math.max(minSafeCoord, startDragged.x + deltaX);
            currentDragged.y = Math.max(minSafeCoord, startDragged.y + deltaY);
        }

        // 2. Соседи 1-й степени натягиваются эластично (натяжение 32%)
        float tension1 = 0.32f;
        for (long id1 : degree1Neighbors) {
            PointF startPos = dragStartPositions.get(id1);
            PointF currPos = nodePositionsMap.get(id1);
            if (startPos != null && currPos != null) {
                currPos.x = Math.max(minSafeCoord, startPos.x + deltaX * tension1);
                currPos.y = Math.max(minSafeCoord, startPos.y + deltaY * tension1);
            }
        }

        // 3. Соседи 2-й степени передают волновое натяжение паутины (натяжение 10%)
        float tension2 = 0.10f;
        for (long id2 : degree2Neighbors) {
            PointF startPos = dragStartPositions.get(id2);
            PointF currPos = nodePositionsMap.get(id2);
            if (startPos != null && currPos != null) {
                currPos.x = Math.max(minSafeCoord, startPos.x + deltaX * tension2);
                currPos.y = Math.max(minSafeCoord, startPos.y + deltaY * tension2);
            }
        }

        // Авто-расширение холста если перетянули близко к краю
        if (currentDragged != null) {
            if (currentDragged.x + cardWidthPx + paddingX > contentWidth) {
                contentWidth = (int) (currentDragged.x + cardWidthPx + paddingX * 1.5f);
            }
            if (currentDragged.y + cardHeightPx + paddingY > contentHeight) {
                contentHeight = (int) (currentDragged.y + cardHeightPx + paddingY * 1.5f);
            }
        }

        updateViewPositionsAndBounds();
        invalidate();
    }

    private void finishDrag(boolean isUp) {
        if (!isDraggingNode) return;

        if (!hasDragged && isUp && draggedNodeId > 0) {
            // Клик по карточке
            long tappedId = draggedNodeId;
            selectNode(tappedId);
            if (listener != null && franchiseData != null && franchiseData.getNodes() != null) {
                for (FranchiseResponse.NodeItem node : franchiseData.getNodes()) {
                    if (node.getId() == tappedId) {
                        String relation = nodeRelationsMap.get(node.getId());
                        listener.onNodeClick(node, relation);
                        break;
                    }
                }
            }
            isDraggingNode = false;
            draggedNodeId = -1;
            hasDragged = false;
            return;
        }

        if (hasDragged) {
            // Плавное успокоение паутины: соседи мягко стабилизируются в новом равновесии
            animateSpiderwebSettle();
        } else {
            isDraggingNode = false;
            draggedNodeId = -1;
            hasDragged = false;
        }
    }

    private void animateSpiderwebSettle() {
        final Map<Long, PointF> fromMap = new HashMap<>();
        final Map<Long, PointF> toMap = new HashMap<>();

        PointF startDragged = dragStartPositions.get(draggedNodeId);
        PointF currentDragged = nodePositionsMap.get(draggedNodeId);

        float totalDeltaX = (startDragged != null && currentDragged != null) ? (currentDragged.x - startDragged.x) : 0f;
        float totalDeltaY = (startDragged != null && currentDragged != null) ? (currentDragged.y - startDragged.y) : 0f;

        // Перемещенная карточка остается там, куда ее перетянул пользователь!
        // Соседи мягко расслабляют избыточное натяжение до гармоничного равновесия
        for (long id1 : degree1Neighbors) {
            PointF curr = nodePositionsMap.get(id1);
            PointF orig = dragStartPositions.get(id1);
            if (curr != null && orig != null) {
                fromMap.put(id1, new PointF(curr.x, curr.y));
                toMap.put(id1, new PointF(orig.x + totalDeltaX * 0.16f, orig.y + totalDeltaY * 0.16f));
            }
        }

        for (long id2 : degree2Neighbors) {
            PointF curr = nodePositionsMap.get(id2);
            PointF orig = dragStartPositions.get(id2);
            if (curr != null && orig != null) {
                fromMap.put(id2, new PointF(curr.x, curr.y));
                toMap.put(id2, new PointF(orig.x + totalDeltaX * 0.04f, orig.y + totalDeltaY * 0.04f));
            }
        }

        if (fromMap.isEmpty()) {
            isDraggingNode = false;
            draggedNodeId = -1;
            hasDragged = false;
            clampScroll();
            return;
        }

        settleAnimator = ValueAnimator.ofFloat(0f, 1f);
        settleAnimator.setDuration(260);
        settleAnimator.setInterpolator(new DecelerateInterpolator());
        settleAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            for (Map.Entry<Long, PointF> entry : fromMap.entrySet()) {
                long nId = entry.getKey();
                PointF from = entry.getValue();
                PointF to = toMap.get(nId);
                PointF curr = nodePositionsMap.get(nId);
                if (from != null && to != null && curr != null) {
                    curr.x = from.x + (to.x - from.x) * fraction;
                    curr.y = from.y + (to.y - from.y) * fraction;
                }
            }
            updateViewPositionsAndBounds();
            invalidate();
        });

        settleAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                isDraggingNode = false;
                draggedNodeId = -1;
                hasDragged = false;
                clampScroll();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                isDraggingNode = false;
                draggedNodeId = -1;
                hasDragged = false;
            }
        });

        settleAnimator.start();
    }

    private void updateViewPositionsAndBounds() {
        for (Map.Entry<Long, PointF> entry : nodePositionsMap.entrySet()) {
            long nodeId = entry.getKey();
            PointF pos = entry.getValue();
            View child = nodeViewsMap.get(nodeId);

            if (child != null && pos != null) {
                int left = Math.round(pos.x);
                int top = Math.round(pos.y);
                int right = left + cardWidthPx;
                int bottom = top + cardHeightPx;

                child.layout(left, top, right, bottom);
                nodeBoundsMap.put(nodeId, new RectF(left, top, right, bottom));
            }
        }
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
        updateViewPositionsAndBounds();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(scaleFactor, scaleFactor);

        // 1. Рисуем соединительные кривые паутины под карточками
        drawConnectionCurves(canvas);

        // 2. Рисуем сами нативные дочерние вьюшки (карточки)
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

            boolean isMain = isMainRelation(link.getRelation());
            float origWidth = paintToUse.getStrokeWidth();
            if (isSelectionActive && isLinkInActiveBranch) {
                paintToUse.setStrokeWidth(isMain ? (4.2f * density) : (2.8f * density));
            }

            canvas.drawPath(bezierPath, paintToUse);

            dotPaint.setColor(paintToUse.getColor());
            float dotRadius = isMain ? (3.8f * density) : (2.6f * density);
            canvas.drawCircle(pts.endX, pts.endY, dotRadius, dotPaint);
            canvas.drawCircle(pts.startX, pts.startY, dotRadius * 0.75f, dotPaint);

            if (isSelectionActive && isLinkInActiveBranch) {
                paintToUse.setStrokeWidth(origWidth);
            }
        }
    }

    private boolean isMainRelation(String relation) {
        if (relation == null) return false;
        String r = relation.toLowerCase().trim();
        return r.contains("sequel") || r.contains("prequel") ||
                r.contains("parent") || r.contains("full") ||
                r.contains("main") || r.contains("сиквел") ||
                r.contains("приквел") || r.contains("основной");
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
            case "alternative_setting":
            case "alternative_version":
                return linePaintAlternative;
            case "summary":
                return linePaintSummary;
            default:
                return linePaintDefault;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Перехватываем все касания: граф сам обрабатывает зум, драг и скролл
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        // 1. Масштабирование щипком (Pinch-to-zoom)
        scaleGestureDetector.onTouchEvent(ev);

        if (scaleGestureDetector.isInProgress()) {
            if (isDraggingNode) {
                isDraggingNode = false;
                draggedNodeId = -1;
                hasDragged = false;
            }
            return true;
        }

        // 2. Поглощаем мультитач, предотвращая срыв в драг ноды или рывок скролла
        if (ev.getPointerCount() > 1) {
            if (isDraggingNode) {
                isDraggingNode = false;
                draggedNodeId = -1;
                hasDragged = false;
            }
            return true;
        }

        float canvasX = (ev.getX() + getScrollX()) / scaleFactor;
        float canvasY = (ev.getY() + getScrollY()) / scaleFactor;

        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) {
                    scroller.forceFinished(true);
                }
                long hitId = findNodeAt(canvasX, canvasY);
                if (hitId > 0) {
                    initDragPhysics(hitId, canvasX, canvasY);
                    return true;
                } else {
                    isDraggingNode = false;
                    draggedNodeId = -1;
                    return gestureDetector.onTouchEvent(ev);
                }

            case MotionEvent.ACTION_MOVE:
                if (isDraggingNode) {
                    applyDragMove(canvasX, canvasY);
                    return true;
                } else {
                    return gestureDetector.onTouchEvent(ev);
                }

            case MotionEvent.ACTION_UP:
                if (isDraggingNode) {
                    finishDrag(true);
                    return true;
                } else {
                    boolean handled = gestureDetector.onTouchEvent(ev);
                    clampScroll();
                    return handled;
                }

            case MotionEvent.ACTION_CANCEL:
                if (isDraggingNode) {
                    finishDrag(false);
                    return true;
                } else {
                    boolean handled = gestureDetector.onTouchEvent(ev);
                    clampScroll();
                    return handled;
                }
        }

        return gestureDetector.onTouchEvent(ev);
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
