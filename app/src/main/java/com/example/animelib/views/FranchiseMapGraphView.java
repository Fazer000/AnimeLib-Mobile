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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
    private final Map<Long, Integer> chronologicalStepMap = new HashMap<>();

    // Zoom & Scale
    private float scaleFactor = 0.80f;
    private static final float MIN_SCALE = 0.25f;
    private static final float MAX_SCALE = 2.00f;
    private ScaleGestureDetector scaleGestureDetector;
    private boolean wasMultiTouch = false;

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
        cardWidthPx = (int) (260 * density);
        cardHeightPx = (int) (105 * density);
        paddingX = (int) (220 * density);
        paddingY = (int) (220 * density);

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
        chronologicalStepMap.clear();
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

        calculateSpiderwebLayout();

        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (FranchiseResponse.NodeItem node : franchise.getNodes()) {
            View cardView = createNodeCardView(inflater, node, franchise.getCurrentId());
            nodeViewsMap.put(node.getId(), cardView);
            addView(cardView);
        }

        requestLayout();
        invalidate();

        // Автоцентрирование на текущем тайтле после построения карты
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
     * Оптимизированное радиальное распределение графа франшизы (Clean Spiderweb Radial Layout).
     * - Транзитивная редукция прямых переходов для устранения путаницы дублирующих линий.
     * - Четкое зонирование секторов (хронологическая ось сиквелов вправо, предыстория влево, спин-оффы и адаптации по вертикали).
     * - Существенно увеличенные радиальные орбиты и безопасные дистанции между карточками.
     * - Итеративная 2D релаксация коллизий с увеличенными зазорами.
     * - Просторные внешние отступы холста (paddingX/paddingY = 220dp).
     */
    private void calculateSpiderwebLayout() {
        if (franchiseData == null || franchiseData.getNodes() == null || franchiseData.getNodes().isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        List<FranchiseResponse.NodeItem> allNodes = franchiseData.getNodes();

        List<FranchiseResponse.NodeItem> allSorted = FranchiseChronologyBottomSheet.sortNodesChronologically(franchiseData);
        if (allSorted.isEmpty()) return;

        int totalCount = allSorted.size();
        chronologicalStepMap.clear();
        for (int i = 0; i < totalCount; i++) {
            chronologicalStepMap.put(allSorted.get(i).getId(), i + 1);
        }

        // 1. Находим фокальный тайтл (текущее аниме)
        long focalId = franchiseData.getCurrentId();
        boolean focalExists = false;
        for (FranchiseResponse.NodeItem n : allNodes) {
            if (n.getId() == focalId) {
                focalExists = true;
                break;
            }
        }
        if (!focalExists) {
            focalId = allNodes.get(0).getId();
        }
        currentFocalId = focalId;

        // Транзитивная редукция избыточных длинных связей для минимизации путаницы линий:
        // Если между A и B есть альтернативный путь через другие ноды (например A->C->B),
        // убираем прямую диагональную хорду A->B, чтобы линии не пересекались и не путались.
        simplifyCanonicalLinksTransitive();

        // 2. Индексируем ноды и двунаправленные связи графа
        Map<Long, FranchiseResponse.NodeItem> nodeMap = new HashMap<>();
        Map<Long, List<Long>> adj = new HashMap<>();
        for (FranchiseResponse.NodeItem n : allNodes) {
            nodeMap.put(n.getId(), n);
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

        // 3. Вычисляем орбитальные слои (BFS глубину от текущего тайтла)
        Map<Long, Integer> depthMap = new HashMap<>();
        Map<Long, Long> parentMap = new HashMap<>();
        Queue<Long> queue = new LinkedList<>();

        depthMap.put(focalId, 0);
        queue.add(focalId);

        while (!queue.isEmpty()) {
            long curr = queue.poll();
            int currDepth = depthMap.get(curr);
            List<Long> neighbors = adj.get(curr);
            if (neighbors != null) {
                for (long neighbor : neighbors) {
                    if (!depthMap.containsKey(neighbor)) {
                        depthMap.put(neighbor, currDepth + 1);
                        parentMap.put(neighbor, curr);
                        queue.add(neighbor);
                    }
                }
            }
        }

        for (FranchiseResponse.NodeItem n : allNodes) {
            if (!depthMap.containsKey(n.getId())) {
                depthMap.put(n.getId(), 1);
            }
        }

        // Центр виртуального холста паутины с большим запасом свободного пространства
        float centerX = 2400f * density;
        float centerY = 2400f * density;

        nodePositionsMap.clear();
        nodePositionsMap.put(focalId, new PointF(centerX - cardWidthPx / 2f, centerY - cardHeightPx / 2f));

        // Группируем элементы по орбитальным слоям (слой 1, слой 2...)
        Map<Integer, List<Long>> nodesByDepth = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : depthMap.entrySet()) {
            int d = entry.getValue();
            if (d == 0) continue;
            if (!nodesByDepth.containsKey(d)) {
                nodesByDepth.put(d, new ArrayList<>());
            }
            nodesByDepth.get(d).add(entry.getKey());
        }

        List<Integer> sortedDepths = new ArrayList<>(nodesByDepth.keySet());
        Collections.sort(sortedDepths);

        Map<Long, Float> nodeAngleMap = new HashMap<>();

        // 4. Распределяем карточки по угловым секторам на просторных радиальных кольцах
        for (int depth : sortedDepths) {
            List<Long> layerNodes = nodesByDepth.get(depth);
            if (layerNodes == null || layerNodes.isEmpty()) continue;

            // Увеличенный радиус орбит для создания простора между слоями: 380dp + шаг 340dp
            float baseRadius = (380f + (depth - 1) * 340f) * density;
            int count = layerNodes.size();

            // Категоризация нод слоя по смысловым секторам для минимизации пересечений
            List<Long> sequels = new ArrayList<>();
            List<Long> prequels = new ArrayList<>();
            List<Long> sideStories = new ArrayList<>();
            List<Long> spinOffs = new ArrayList<>();
            List<Long> others = new ArrayList<>();

            for (long nId : layerNodes) {
                String rel = nodeRelationsMap.get(nId);
                if (rel == null) rel = "";
                String relLower = rel.toLowerCase();
                FranchiseResponse.NodeItem node = nodeMap.get(nId);
                String kind = node != null && node.getKind() != null ? node.getKind().toLowerCase() : "";

                if (relLower.contains("сиквел") || relLower.contains("sequel")) {
                    sequels.add(nId);
                } else if (relLower.contains("приквел") || relLower.contains("prequel") || relLower.contains("parent")) {
                    prequels.add(nId);
                } else if (relLower.contains("сайд") || relLower.contains("side")) {
                    sideStories.add(nId);
                } else if (relLower.contains("спин") || relLower.contains("spin") || relLower.contains("alternat")) {
                    spinOffs.add(nId);
                } else if (kind.contains("movie") || kind.contains("ova") || kind.contains("special")) {
                    sideStories.add(nId);
                } else {
                    others.add(nId);
                }
            }

            if (depth == 1) {
                // Первый слой: строгое разделение по 4 четвертям компаса без наложения веток
                assignAnglesToSubgroup(sequels, -25f, 25f, nodeAngleMap);      // Восток: сиквелы
                assignAnglesToSubgroup(prequels, 155f, 205f, nodeAngleMap);    // Запад: приквелы
                assignAnglesToSubgroup(sideStories, 65f, 115f, nodeAngleMap);  // Юг: сайд-стори / фильмы
                assignAnglesToSubgroup(spinOffs, -115f, -65f, nodeAngleMap);   // Север: спин-оффы / альтернативы
                assignAnglesToSubgroup(others, 215f, 325f, nodeAngleMap);      // Диагонали: прочие части
            } else {
                // Ноды следующих слоев наследуют угол родителя с веерным разнесением, не перехлестываясь
                Map<Long, List<Long>> childrenByParent = new HashMap<>();
                for (long nId : layerNodes) {
                    Long pId = parentMap.get(nId);
                    if (pId == null) pId = focalId;
                    if (!childrenByParent.containsKey(pId)) {
                        childrenByParent.put(pId, new ArrayList<>());
                    }
                    childrenByParent.get(pId).add(nId);
                }

                for (Map.Entry<Long, List<Long>> entry : childrenByParent.entrySet()) {
                    long pId = entry.getKey();
                    List<Long> siblings = entry.getValue();
                    float parentAngle = nodeAngleMap.containsKey(pId) ? nodeAngleMap.get(pId) : 0f;
                    int sibCount = siblings.size();
                    float angularSpread = Math.min(60f, Math.max(24f, sibCount * 18f));
                    float startAngle = parentAngle - (float) Math.toRadians(angularSpread / 2f);
                    float step = sibCount > 1 ? (float) Math.toRadians(angularSpread / (sibCount - 1)) : 0f;

                    for (int s = 0; s < sibCount; s++) {
                        float angleRad = sibCount == 1 ? parentAngle : (startAngle + s * step);
                        nodeAngleMap.put(siblings.get(s), angleRad);
                    }
                }
            }

            for (long nodeId : layerNodes) {
                float angleRad = nodeAngleMap.containsKey(nodeId) ? nodeAngleMap.get(nodeId) : 0f;
                float px = centerX + (float) Math.cos(angleRad) * baseRadius - cardWidthPx / 2f;
                float py = centerY + (float) Math.sin(angleRad) * baseRadius - cardHeightPx / 2f;
                nodePositionsMap.put(nodeId, new PointF(px, py));
            }
        }

        // 5. Двухмерная физическая релаксация коллизий с увеличенными безопасными зазорами
        // Зазор между карточками увеличен до 70dp по горизонтали и 55dp по вертикали
        float minGapX = cardWidthPx + (70f * density);
        float minGapY = cardHeightPx + (55f * density);
        float minDist = (float) Math.hypot(minGapX, minGapY);

        for (int iter = 0; iter < 32; iter++) {
            for (int i = 0; i < allNodes.size(); i++) {
                long idA = allNodes.get(i).getId();
                if (idA == focalId) continue; // Центральный айтем зафиксирован в центре
                PointF posA = nodePositionsMap.get(idA);
                if (posA == null) continue;

                for (int j = i + 1; j < allNodes.size(); j++) {
                    long idB = allNodes.get(j).getId();
                    PointF posB = nodePositionsMap.get(idB);
                    if (posB == null) continue;

                    float cAx = posA.x + cardWidthPx / 2f;
                    float cAy = posA.y + cardHeightPx / 2f;
                    float cBx = posB.x + cardWidthPx / 2f;
                    float cBy = posB.y + cardHeightPx / 2f;

                    float dx = cBx - cAx;
                    float dy = cBy - cAy;
                    float dist = (float) Math.hypot(dx, dy);

                    if (dist < minDist && dist > 0.001f) {
                        float overlap = minDist - dist;
                        float nx = dx / dist;
                        float ny = dy / dist;

                        float pushX = nx * overlap * 0.45f;
                        float pushY = ny * overlap * 0.45f;

                        if (idA != focalId) {
                            posA.x -= pushX * 0.5f;
                            posA.y -= pushY * 0.5f;
                        }
                        if (idB != focalId) {
                            posB.x += pushX * 0.5f;
                            posB.y += pushY * 0.5f;
                        }
                    }
                }
            }
        }

        // 6. Нормализация координат с большими отступами (paddingX/paddingY = 220dp)
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

        // Холст гарантированно имеет избыточное свободное пространство вокруг со всех сторон
        contentWidth = Math.max((int) (2200 * density), (int) (maxX + paddingX * 1.5f));
        contentHeight = Math.max((int) (1800 * density), (int) (maxY + paddingY * 1.5f));
    }

    private void assignAnglesToSubgroup(List<Long> nodes, float startDeg, float endDeg, Map<Long, Float> targetMap) {
        if (nodes == null || nodes.isEmpty()) return;
        int count = nodes.size();
        if (count == 1) {
            float midDeg = (startDeg + endDeg) / 2f;
            targetMap.put(nodes.get(0), (float) Math.toRadians(midDeg));
        } else {
            float step = (endDeg - startDeg) / (count - 1);
            for (int i = 0; i < count; i++) {
                float deg = startDeg + i * step;
                targetMap.put(nodes.get(i), (float) Math.toRadians(deg));
            }
        }
    }

    /**
     * Устраняет транзитивные избыточные связи (например, если есть A->B и B->C, а также A->C,
     * то длинная диагональная связь A->C удаляется, чтобы не перечеркивать граф).
     */
    private void simplifyCanonicalLinksTransitive() {
        if (canonicalLinks.size() <= 2) return;

        Map<Long, Set<Long>> directNeighbors = new HashMap<>();
        for (MapLink l : canonicalLinks) {
            long s = l.getSourceId();
            long t = l.getTargetId();
            if (!directNeighbors.containsKey(s)) directNeighbors.put(s, new HashSet<>());
            if (!directNeighbors.containsKey(t)) directNeighbors.put(t, new HashSet<>());
            directNeighbors.get(s).add(t);
            directNeighbors.get(t).add(s);
        }

        List<MapLink> filtered = new ArrayList<>();
        for (MapLink candidate : canonicalLinks) {
            long s = candidate.getSourceId();
            long t = candidate.getTargetId();

            // Проверяем: есть ли альтернативный короткий путь длины 2 (s -> k -> t),
            // где k соединен с обоими узлами
            boolean hasBridge = false;
            Set<Long> sNeighbors = directNeighbors.get(s);
            Set<Long> tNeighbors = directNeighbors.get(t);
            if (sNeighbors != null && tNeighbors != null) {
                for (long bridge : sNeighbors) {
                    if (bridge != t && tNeighbors.contains(bridge)) {
                        // Исключаем удаление связи, если обе ноды являются прямыми сиквелами в цепочке
                        int candPriority = getRelationPriority(candidate.getRelation());
                        if (candPriority > 1) {
                            hasBridge = true;
                            break;
                        }
                    }
                }
            }

            if (!hasBridge) {
                filtered.add(candidate);
            }
        }

        // Если редукция не оставила оторванных островов, сохраняем очищенный набор
        if (filtered.size() >= canonicalLinks.size() / 2) {
            canonicalLinks.clear();
            canonicalLinks.addAll(filtered);
        }
    }

    private boolean isLikelyMainTitle(FranchiseResponse.NodeItem item) {
        if (item == null) return false;
        String kind = item.getKind() != null ? item.getKind().toLowerCase() : "";
        return kind.contains("tv") || kind.contains("serial") || (!kind.contains("movie") && !kind.contains("ova") && !kind.contains("special"));
    }

    private View createNodeCardView(LayoutInflater inflater, FranchiseResponse.NodeItem node, long currentId) {
        View view = inflater.inflate(R.layout.item_franchise_map_card, this, false);

        MaterialCardView card = view.findViewById(R.id.cardMapNode);
        ImageView ivCover = view.findViewById(R.id.ivMapCardCover);
        TextView tvStepNumber = view.findViewById(R.id.tvMapCardStepNumber);
        TextView tvCurrentBadge = view.findViewById(R.id.tvMapCardCurrentBadge);
        TextView tvRelation = view.findViewById(R.id.tvMapCardRelation);
        TextView tvTitle = view.findViewById(R.id.tvMapCardTitle);
        TextView tvKind = view.findViewById(R.id.tvMapCardKind);

        if (tvTitle != null) tvTitle.setText(node.getName());
        if (tvKind != null) tvKind.setText(node.getFormattedKind());

        if (tvStepNumber != null) {
            Integer step = chronologicalStepMap.get(node.getId());
            if (step != null && step > 0) {
                tvStepNumber.setText(String.valueOf(step));
                tvStepNumber.setVisibility(VISIBLE);
            } else {
                tvStepNumber.setVisibility(GONE);
            }
        }

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
     * Плавная кривая Безье между карточками с интеллектуальным выбором естественных точек привязки.
     * Выбирает ближайшие грани карточек (право->лево, низ->верх и т.д.), исключая прохождение линий
     * сквозь тела карточек и создавая эстетичные направляющие усики без петель и заломов.
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

        // 1. Горизонтальное доминирование: когда карточки разнесены по X дальше, чем перекрытие
        if (dx > cardWidthPx * 0.45f) {
            // Целевая карточка справа (хронологическое развитие вперед)
            pts.startX = sourceRect.right;
            pts.startY = scy;
            pts.endX = targetRect.left;
            pts.endY = tcy;
            float lead = Math.max(45f * density, (pts.endX - pts.startX) * 0.48f);
            pts.c1x = pts.startX + lead;
            pts.c1y = pts.startY;
            pts.c2x = pts.endX - lead;
            pts.c2y = pts.endY;
        } else if (dx < -cardWidthPx * 0.45f) {
            // Целевая карточка слева (приквел / возврат к началу)
            pts.startX = sourceRect.left;
            pts.startY = scy;
            pts.endX = targetRect.right;
            pts.endY = tcy;
            float lead = Math.max(45f * density, (pts.startX - pts.endX) * 0.48f);
            pts.c1x = pts.startX - lead;
            pts.c1y = pts.startY;
            pts.c2x = pts.endX + lead;
            pts.c2y = pts.endY;
        } else {
            // 2. Вертикальное доминирование: когда карточки одна над другой (спин-оффы, спешлы, фильмы)
            if (dy >= 0) {
                // Целевая карточка снизу
                pts.startX = scx;
                pts.startY = sourceRect.bottom;
                pts.endX = tcx;
                pts.endY = targetRect.top;
                float lead = Math.max(38f * density, (pts.endY - pts.startY) * 0.45f);
                pts.c1x = pts.startX;
                pts.c1y = pts.startY + lead;
                pts.c2x = pts.endX;
                pts.c2y = pts.endY - lead;
            } else {
                // Целевая карточка сверху
                pts.startX = scx;
                pts.startY = sourceRect.top;
                pts.endX = tcx;
                pts.endY = targetRect.bottom;
                float lead = Math.max(38f * density, (pts.startY - pts.endY) * 0.45f);
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
            canvas.drawCircle(pts.startX, pts.startY, dotRadius * 0.75f, dotPaint);

            // Направленная стрелочка слева направо в точку входа в карточку
            drawArrowHead(canvas, pts, paintToUse, density, isMain);

            if (isSelectionActive && isLinkInActiveBranch) {
                paintToUse.setStrokeWidth(origWidth);
            }
        }
    }

    private void drawArrowHead(Canvas canvas, CurvePoints pts, Paint paint, float density, boolean isMain) {
        float arrowSize = (isMain ? 7.5f : 6.0f) * density;
        Path arrowPath = new Path();

        float vx = pts.endX - pts.c2x;
        float vy = pts.endY - pts.c2y;
        float len = (float) Math.hypot(vx, vy);
        if (len == 0f) {
            vx = 1f;
            vy = 0f;
            len = 1f;
        }
        float ux = vx / len;
        float uy = vy / len;

        float nx = -uy;
        float ny = ux;

        float tipX = pts.endX;
        float tipY = pts.endY;
        float baseX = tipX - ux * arrowSize;
        float baseY = tipY - uy * arrowSize;

        float wing1X = baseX + nx * (arrowSize * 0.55f);
        float wing1Y = baseY + ny * (arrowSize * 0.55f);
        float wing2X = baseX - nx * (arrowSize * 0.55f);
        float wing2Y = baseY - ny * (arrowSize * 0.55f);

        arrowPath.moveTo(tipX, tipY);
        arrowPath.lineTo(wing1X, wing1Y);
        arrowPath.lineTo(wing2X, wing2Y);
        arrowPath.close();

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(paint.getColor());
        canvas.drawPath(arrowPath, fillPaint);
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

        int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            wasMultiTouch = false;
        }

        if (ev.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            wasMultiTouch = true;
            if (isDraggingNode) {
                isDraggingNode = false;
                draggedNodeId = -1;
                hasDragged = false;
            }
        }

        // 1. Масштабирование щипком (Pinch-to-zoom)
        scaleGestureDetector.onTouchEvent(ev);

        if (scaleGestureDetector.isInProgress() || ev.getPointerCount() > 1) {
            return true;
        }

        // 2. Если Жест был мультитачем (щипком), поглощаем оставшиеся события отпускания пальцев,
        // предотвращая рывок/сдвиг карты к месту последнего пальца и ложный флинг.
        if (wasMultiTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                wasMultiTouch = false;
                clampScroll();
            }
            return true;
        }

        float canvasX = (ev.getX() + getScrollX()) / scaleFactor;
        float canvasY = (ev.getY() + getScrollY()) / scaleFactor;

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
