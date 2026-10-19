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

    private FranchiseResponse franchiseData;
    private OnNodeClickListener listener;
    private OnZoomChangeListener zoomChangeListener;

    private final List<FranchiseResponse.NodeItem> mainTrunkNodes = new ArrayList<>();
    private final Map<Long, List<FranchiseResponse.NodeItem>> branchNodesMap = new HashMap<>();
    private final Map<Long, PointF> nodePositionsMap = new HashMap<>();

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
        colSpacingPx = (int) (80 * density);
        rowSpacingPx = (int) (50 * density);
        paddingX = (int) (60 * density);
        paddingY = (int) (80 * density);

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

                // 1. If tap is inside any card rectangle, let card View process click natively!
                for (RectF rect : nodeBoundsMap.values()) {
                    if (rect != null && rect.contains(canvasX, canvasY)) {
                        return false;
                    }
                }

                // 2. Tap on empty space: check for branch curve selection
                FranchiseResponse.LinkItem clickedLink = findLinkAt(canvasX, canvasY);
                if (clickedLink != null) {
                    selectBranchLink(clickedLink);
                    return true;
                } else {
                    // Tap on empty background clears selection
                    selectBranchLink(null);
                    return false;
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
        selectedNodeId = -1;
        connectedNodeIds.clear();

        if (franchise == null || franchise.getNodes() == null || franchise.getNodes().isEmpty()) {
            contentWidth = 0;
            contentHeight = 0;
            requestLayout();
            invalidate();
            return;
        }

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

    private void calculateTreeLayout() {
        if (franchiseData == null || franchiseData.getNodes() == null || franchiseData.getNodes().isEmpty()) return;

        List<FranchiseResponse.NodeItem> allSorted = FranchiseChronologyBottomSheet.sortNodesChronologically(franchiseData);

        Map<Long, Long> parentMap = new HashMap<>();
        if (franchiseData.getLinks() != null) {
            for (FranchiseResponse.LinkItem link : franchiseData.getLinks()) {
                String rel = link.getRelation() != null ? link.getRelation().toLowerCase().trim() : "";
                if (rel.equals("sequel")) {
                    parentMap.put(link.getTargetId(), link.getSourceId());
                } else if (rel.equals("prequel")) {
                    parentMap.put(link.getSourceId(), link.getTargetId());
                } else if (rel.equals("side_story") || rel.equals("spin_off") || rel.equals("summary")) {
                    parentMap.put(link.getTargetId(), link.getSourceId());
                }
            }
        }

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

        int maxTopBranches = 0;
        int maxBottomBranches = 0;
        for (List<FranchiseResponse.NodeItem> branches : branchNodesMap.values()) {
            int top = (branches.size() + 1) / 2;
            int bottom = branches.size() / 2;
            if (top > maxTopBranches) maxTopBranches = top;
            if (bottom > maxBottomBranches) maxBottomBranches = bottom;
        }

        int yCenter = paddingY + Math.max(1, maxTopBranches) * (cardHeightPx + rowSpacingPx);

        int currentX = paddingX;
        for (int i = 0; i < mainTrunkNodes.size(); i++) {
            FranchiseResponse.NodeItem mainNode = mainTrunkNodes.get(i);
            nodePositionsMap.put(mainNode.getId(), new PointF(currentX, yCenter));

            List<FranchiseResponse.NodeItem> branches = branchNodesMap.get(mainNode.getId());
            if (branches != null && !branches.isEmpty()) {
                int topIdx = 1;
                int bottomIdx = 1;
                for (int b = 0; b < branches.size(); b++) {
                    FranchiseResponse.NodeItem branch = branches.get(b);
                    float bx = currentX; // Aligned cleanly in main node's column
                    float by;
                    if (b % 2 == 0) {
                        by = yCenter - topIdx * (cardHeightPx + rowSpacingPx);
                        topIdx++;
                    } else {
                        by = yCenter + bottomIdx * (cardHeightPx + rowSpacingPx);
                        bottomIdx++;
                    }
                    nodePositionsMap.put(branch.getId(), new PointF(bx, by));
                }
            }

            currentX += cardWidthPx + colSpacingPx;
        }

        float maxX = 0;
        float maxY = 0;
        for (PointF pt : nodePositionsMap.values()) {
            if (pt.x + cardWidthPx > maxX) maxX = pt.x + cardWidthPx;
            if (pt.y + cardHeightPx > maxY) maxY = pt.y + cardHeightPx;
        }

        float density = getResources().getDisplayMetrics().density;
        contentWidth = (int) (maxX + paddingX);
        contentHeight = Math.max((int) (600 * density), (int) (maxY + paddingY));
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

        view.setOnClickListener(v -> {
            selectNode(node.getId());
            if (listener != null) {
                listener.onNodeClick(node, relation);
            }
        });

        return view;
    }

    public void selectNode(long nodeId) {
        this.selectedNodeId = nodeId;
        this.connectedNodeIds.clear();

        if (nodeId > 0 && franchiseData != null && franchiseData.getLinks() != null) {
            connectedNodeIds.add(nodeId);
            for (FranchiseResponse.LinkItem link : franchiseData.getLinks()) {
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

    public void selectBranchLink(FranchiseResponse.LinkItem link) {
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
        if (franchiseData == null || franchiseData.getLinks() == null) return;

        List<Long> queue = new ArrayList<>();
        queue.add(startNodeId);
        visited.add(startNodeId);

        int head = 0;
        while (head < queue.size()) {
            long current = queue.get(head++);
            for (FranchiseResponse.LinkItem link : franchiseData.getLinks()) {
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

    private CurvePoints calculateCurvePoints(FranchiseResponse.LinkItem link, float density) {
        RectF sourceRect = nodeBoundsMap.get(link.getSourceId());
        RectF targetRect = nodeBoundsMap.get(link.getTargetId());
        if (sourceRect == null || targetRect == null) return null;

        CurvePoints pts = new CurvePoints();

        // 1. Determine anchor points based on relative positions
        float dy = targetRect.centerY() - sourceRect.centerY();

        if (Math.abs(sourceRect.centerX() - targetRect.centerX()) < 15f * density) {
            // Directly above or below in same column
            if (targetRect.top > sourceRect.bottom) {
                pts.startX = sourceRect.centerX();
                pts.startY = sourceRect.bottom;
                pts.endX = targetRect.centerX();
                pts.endY = targetRect.top;
            } else {
                pts.startX = sourceRect.centerX();
                pts.startY = sourceRect.top;
                pts.endX = targetRect.centerX();
                pts.endY = targetRect.bottom;
            }
        } else if (dy < -20f * density) {
            // Target is ABOVE source
            pts.startX = sourceRect.right;
            pts.startY = sourceRect.top + 16f * density;
            pts.endX = targetRect.left;
            pts.endY = targetRect.centerY();
        } else if (dy > 20f * density) {
            // Target is BELOW source
            pts.startX = sourceRect.right;
            pts.startY = sourceRect.bottom - 16f * density;
            pts.endX = targetRect.left;
            pts.endY = targetRect.centerY();
        } else {
            // Same horizontal row
            if (sourceRect.left < targetRect.left) {
                pts.startX = sourceRect.right;
                pts.startY = sourceRect.centerY();
                pts.endX = targetRect.left;
                pts.endY = targetRect.centerY();
            } else {
                pts.startX = sourceRect.left;
                pts.startY = sourceRect.centerY();
                pts.endX = targetRect.right;
                pts.endY = targetRect.centerY();
            }
        }

        // 2. Initial control points
        if (Math.abs(sourceRect.centerX() - targetRect.centerX()) < 15f * density) {
            float arcX = pts.startX + 45f * density;
            pts.c1x = arcX;
            pts.c1y = pts.startY;
            pts.c2x = arcX;
            pts.c2y = pts.endY;
        } else {
            float midDx = Math.abs(pts.endX - pts.startX) * 0.5f;
            float curveOffset = Math.max(midDx, 36f * density);
            pts.c1x = (pts.startX < pts.endX) ? (pts.startX + curveOffset) : (pts.startX - curveOffset);
            pts.c1y = pts.startY;
            pts.c2x = (pts.startX < pts.endX) ? (pts.endX - curveOffset) : (pts.endX + curveOffset);
            pts.c2y = pts.endY;
        }

        // 3. Obstacle Avoidance: bypass intermediate card rects
        float minX = Math.min(pts.startX, pts.endX) - 10f * density;
        float maxX = Math.max(pts.startX, pts.endX) + 10f * density;

        for (Map.Entry<Long, RectF> entry : nodeBoundsMap.entrySet()) {
            long nodeId = entry.getKey();
            if (nodeId == link.getSourceId() || nodeId == link.getTargetId()) continue;

            RectF rect = entry.getValue();
            if (rect == null) continue;

            if (rect.right < minX || rect.left > maxX) continue;

            // Compute curve Y coordinate at rect.centerX()
            float denom = pts.endX - pts.startX;
            if (Math.abs(denom) > 1f) {
                float t = (rect.centerX() - pts.startX) / denom;
                if (t >= 0.15f && t <= 0.85f) {
                    float oneMinusT = 1f - t;
                    float curveY = oneMinusT * oneMinusT * oneMinusT * pts.startY
                            + 3f * oneMinusT * oneMinusT * t * pts.c1y
                            + 3f * oneMinusT * t * t * pts.c2y
                            + t * t * t * pts.endY;

                    // If curve Y intersects or passes through intermediate card
                    if (curveY >= rect.top - 12f * density && curveY <= rect.bottom + 12f * density) {
                        if (pts.startY <= rect.top || pts.endY <= rect.top) {
                            // Route curve ABOVE card
                            float avoidY = rect.top - 20f * density;
                            pts.c1y = Math.min(pts.c1y, avoidY);
                            pts.c2y = Math.min(pts.c2y, avoidY);
                        } else {
                            // Route curve BELOW card
                            float avoidY = rect.bottom + 20f * density;
                            pts.c1y = Math.max(pts.c1y, avoidY);
                            pts.c2y = Math.max(pts.c2y, avoidY);
                        }
                    }
                }
            }
        }

        return pts;
    }

    private FranchiseResponse.LinkItem findLinkAt(float canvasX, float canvasY) {
        if (franchiseData == null || franchiseData.getLinks() == null) return null;

        float density = getResources().getDisplayMetrics().density;
        float maxTouchDistPx = 14f * density;
        FranchiseResponse.LinkItem bestLink = null;
        float minDistance = maxTouchDistPx;

        for (FranchiseResponse.LinkItem link : franchiseData.getLinks()) {
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
        if (franchiseData == null || franchiseData.getLinks() == null) return;

        float density = getResources().getDisplayMetrics().density;
        Path bezierPath = new Path();

        boolean isSelectionActive = !connectedNodeIds.isEmpty() || selectedNodeId > 0;

        for (FranchiseResponse.LinkItem link : franchiseData.getLinks()) {
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

        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        boolean handled = gestureDetector.onTouchEvent(event);
        if (!handled && event.getAction() == MotionEvent.ACTION_UP) {
            clampScroll();
        }
        return true;
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
