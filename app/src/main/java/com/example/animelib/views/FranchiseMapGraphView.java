package com.example.animelib.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
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
 * Интерактивная карта франшизы аниме с поддержкой зума (Pinch-to-Zoom),
 * табличной группировкой по годам и плавными кривыми Безье.
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

    private final List<Integer> yearList = new ArrayList<>();
    private final Map<Integer, List<FranchiseResponse.NodeItem>> yearNodesMap = new HashMap<>();
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

    // Paints for Canvas curves & header
    private Paint linePaintSequel;
    private Paint linePaintSide;
    private Paint linePaintSummary;
    private Paint linePaintMain;
    private Paint linePaintDefault;
    private Paint linePaintDimmed;
    private Paint headerTextPaint;
    private Paint columnDividerPaint;
    private Paint arrowPaint;

    // Layout dimensions in dp
    private int cardWidthPx;
    private int cardHeightPx;
    private int colSpacingPx;
    private int rowSpacingPx;
    private int headerHeightPx;
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
        cardWidthPx = (int) (168 * density);
        cardHeightPx = (int) (88 * density);
        colSpacingPx = (int) (56 * density);
        rowSpacingPx = (int) (20 * density);
        headerHeightPx = (int) (48 * density);
        paddingX = (int) (24 * density);
        paddingY = (int) (20 * density);

        initPaints(context, density);

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                setScaleFactor(scaleFactor * factor);
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float canvasX = (e.getX() + getScrollX()) / scaleFactor;
                float canvasY = (e.getY() + getScrollY()) / scaleFactor;

                FranchiseResponse.LinkItem clickedLink = findLinkAt(canvasX, canvasY);
                if (clickedLink != null) {
                    selectBranchLink(clickedLink);
                    return true;
                } else {
                    // Tap on empty space clears selection
                    selectBranchLink(null);
                    return super.onSingleTapUp(e);
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
        linePaintSequel = createCurvePaint(0xFFA855F7, 2.2f * density, false);  // Фиолетовый сиквел/приквел
        linePaintMain = createCurvePaint(0xFF3B82F6, 2.2f * density, false);    // Синий основной сюжет
        linePaintSide = createCurvePaint(0xFF06B6D4, 1.8f * density, true);     // Бирюзовый пунктир сайд-стори
        linePaintSummary = createCurvePaint(0xFFF59E0B, 1.6f * density, false); // Янтарный рекап
        linePaintDefault = createCurvePaint(0xFF8E8E93, 1.5f * density, false); // Серый по умолчанию
        linePaintDimmed = createCurvePaint(0x338E8E93, 1.0f * density, false);  // Приглушенный

        int textColor = ContextCompat.getColor(context, R.color.primary_text_color);
        headerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerTextPaint.setColor(textColor);
        headerTextPaint.setTextSize(15f * density);
        headerTextPaint.setTextAlign(Paint.Align.LEFT);
        headerTextPaint.setFakeBoldText(true);

        int strokeColor = ContextCompat.getColor(context, R.color.chip_unselected_stroke);
        columnDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        columnDividerPaint.setColor(strokeColor);
        columnDividerPaint.setStrokeWidth(1.5f * density);
        columnDividerPaint.setStyle(Paint.Style.STROKE);

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

    public void setScaleFactor(float newScale) {
        float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        if (Math.abs(this.scaleFactor - clamped) > 0.001f) {
            this.scaleFactor = clamped;
            clampScroll();
            invalidate();
            if (zoomChangeListener != null) {
                zoomChangeListener.onZoomChanged(this.scaleFactor);
            }
        }
    }

    public void zoomIn() {
        setScaleFactor(scaleFactor + 0.15f);
    }

    public void zoomOut() {
        setScaleFactor(scaleFactor - 0.15f);
    }

    public void setFranchiseData(FranchiseResponse franchise, OnNodeClickListener listener) {
        this.franchiseData = franchise;
        this.listener = listener;

        removeAllViews();
        yearList.clear();
        yearNodesMap.clear();
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

        // Group nodes by year
        for (FranchiseResponse.NodeItem node : franchise.getNodes()) {
            int year = node.getYear() > 0 ? node.getYear() : 2000;
            if (!yearNodesMap.containsKey(year)) {
                yearNodesMap.put(year, new ArrayList<>());
                yearList.add(year);
            }
            yearNodesMap.get(year).add(node);
        }

        Collections.sort(yearList);

        // Populate node views
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int col = 0; col < yearList.size(); col++) {
            int year = yearList.get(col);
            List<FranchiseResponse.NodeItem> nodes = yearNodesMap.get(year);

            if (nodes != null) {
                // Sort nodes inside year by date ascending
                Collections.sort(nodes, (n1, n2) -> Long.compare(n1.getDate(), n2.getDate()));

                for (int row = 0; row < nodes.size(); row++) {
                    FranchiseResponse.NodeItem node = nodes.get(row);
                    View cardView = createNodeCardView(inflater, node, franchise.getCurrentId());
                    nodeViewsMap.put(node.getId(), cardView);
                    addView(cardView);
                }
            }
        }

        requestLayout();
        invalidate();
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
            card.setStrokeWidth((int) (2 * getResources().getDisplayMetrics().density));
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

        // Highlight connected cards
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

    private FranchiseResponse.LinkItem findLinkAt(float canvasX, float canvasY) {
        if (franchiseData == null || franchiseData.getLinks() == null) return null;

        float density = getResources().getDisplayMetrics().density;
        float maxTouchDistPx = 28f * density; // Touch tolerance around curve
        FranchiseResponse.LinkItem bestLink = null;
        float minDistance = maxTouchDistPx;

        for (FranchiseResponse.LinkItem link : franchiseData.getLinks()) {
            RectF sourceRect = nodeBoundsMap.get(link.getSourceId());
            RectF targetRect = nodeBoundsMap.get(link.getTargetId());
            if (sourceRect == null || targetRect == null) continue;

            float startX, startY, endX, endY;
            if (sourceRect.left < targetRect.left) {
                startX = sourceRect.right;
                startY = sourceRect.centerY();
                endX = targetRect.left;
                endY = targetRect.centerY();
            } else if (sourceRect.left > targetRect.left) {
                startX = sourceRect.left;
                startY = sourceRect.centerY();
                endX = targetRect.right;
                endY = targetRect.centerY();
            } else {
                startX = sourceRect.right;
                startY = sourceRect.centerY();
                endX = targetRect.right;
                endY = targetRect.centerY();
            }

            float dx = Math.abs(endX - startX) / 2f;
            float curveOffset = Math.max(dx, 36f * density);

            float control1X, control1Y, control2X, control2Y;
            if (sourceRect.left == targetRect.left) {
                control1X = startX + 50f * density;
                control1Y = startY;
                control2X = startX + 50f * density;
                control2Y = endY;
            } else {
                control1X = (startX < endX) ? (startX + curveOffset) : (startX - curveOffset);
                control1Y = startY;
                control2X = (startX < endX) ? (endX - curveOffset) : (endX + curveOffset);
                control2Y = endY;
            }

            for (int i = 0; i <= 16; i++) {
                float t = i / 16f;
                float oneMinusT = 1f - t;

                float px = oneMinusT * oneMinusT * oneMinusT * startX
                        + 3f * oneMinusT * oneMinusT * t * control1X
                        + 3f * oneMinusT * t * t * control2X
                        + t * t * t * endX;

                float py = oneMinusT * oneMinusT * oneMinusT * startY
                        + 3f * oneMinusT * oneMinusT * t * control1Y
                        + 3f * oneMinusT * t * t * control2Y
                        + t * t * t * endY;

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
        int maxRows = 0;
        for (List<FranchiseResponse.NodeItem> list : yearNodesMap.values()) {
            if (list.size() > maxRows) {
                maxRows = list.size();
            }
        }

        int numCols = yearList.size();
        if (numCols > 0) {
            contentWidth = paddingX * 2 + numCols * cardWidthPx + Math.max(0, numCols - 1) * colSpacingPx;
        } else {
            contentWidth = getPaddingLeft() + getPaddingRight();
        }

        if (maxRows > 0) {
            contentHeight = paddingY * 2 + headerHeightPx + maxRows * cardHeightPx + Math.max(0, maxRows - 1) * rowSpacingPx;
        } else {
            contentHeight = getPaddingTop() + getPaddingBottom();
        }

        // Measure children
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

        int currentX = paddingX;

        for (int col = 0; col < yearList.size(); col++) {
            int year = yearList.get(col);
            List<FranchiseResponse.NodeItem> nodes = yearNodesMap.get(year);

            int currentY = paddingY + headerHeightPx;

            if (nodes != null) {
                for (FranchiseResponse.NodeItem node : nodes) {
                    View child = nodeViewsMap.get(node.getId());
                    if (child != null) {
                        int left = currentX;
                        int top = currentY;
                        int right = left + cardWidthPx;
                        int bottom = top + cardHeightPx;

                        child.layout(left, top, right, bottom);
                        nodeBoundsMap.put(node.getId(), new RectF(left, top, right, bottom));

                        currentY += cardHeightPx + rowSpacingPx;
                    }
                }
            }

            currentX += cardWidthPx + colSpacingPx;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(scaleFactor, scaleFactor);

        // 1. Draw Year Headers (without pill) and Vertical Column Divider Lines (on left of each year)
        drawYearHeadersAndColumns(canvas);

        // 2. Draw Connecting Bezier Curves between nodes
        drawConnectionCurves(canvas);

        // 3. Draw native Child Views (Node Cards) on top
        super.dispatchDraw(canvas);

        canvas.restore();
    }

    private void drawYearHeadersAndColumns(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        int currentX = paddingX;

        float tableTop = paddingY;
        float tableBottom = contentHeight - paddingY;

        for (int col = 0; col < yearList.size(); col++) {
            int year = yearList.get(col);

            // Left vertical divider line for this year column (spanning full table height)
            float lineX = currentX - (colSpacingPx / 2f);
            canvas.drawLine(lineX, tableTop, lineX, tableBottom, columnDividerPaint);

            // Year Header Text (no background pill, clear text above column)
            float textY = tableTop + 22f * density;
            canvas.drawText(String.valueOf(year), currentX, textY, headerTextPaint);

            currentX += cardWidthPx + colSpacingPx;
        }

        // Draw right vertical border line for the last column
        if (!yearList.isEmpty()) {
            float lineXRight = currentX - (colSpacingPx / 2f);
            canvas.drawLine(lineXRight, tableTop, lineXRight, tableBottom, columnDividerPaint);
        }

        // Draw horizontal divider line separating year header row from title cards
        float headerLineY = tableTop + headerHeightPx - (rowSpacingPx / 2f);
        float startX = paddingX - (colSpacingPx / 2f);
        float endX = currentX - (colSpacingPx / 2f);
        canvas.drawLine(startX, headerLineY, endX, headerLineY, columnDividerPaint);
    }

    private void drawConnectionCurves(Canvas canvas) {
        if (franchiseData == null || franchiseData.getLinks() == null) return;

        float density = getResources().getDisplayMetrics().density;
        Path bezierPath = new Path();

        boolean isSelectionActive = !connectedNodeIds.isEmpty() || selectedNodeId > 0;

        for (FranchiseResponse.LinkItem link : franchiseData.getLinks()) {
            RectF sourceRect = nodeBoundsMap.get(link.getSourceId());
            RectF targetRect = nodeBoundsMap.get(link.getTargetId());

            if (sourceRect == null || targetRect == null) continue;

            boolean isLinkInActiveBranch = isSelectionActive &&
                    connectedNodeIds.contains(link.getSourceId()) &&
                    connectedNodeIds.contains(link.getTargetId());

            Paint paintToUse;
            if (isSelectionActive && !isLinkInActiveBranch) {
                paintToUse = linePaintDimmed;
            } else {
                paintToUse = getPaintForRelation(link.getRelation());
            }

            // Determine Anchor points (right side of source -> left side of target)
            float startX, startY, endX, endY;

            if (sourceRect.left < targetRect.left) {
                startX = sourceRect.right;
                startY = sourceRect.centerY();
                endX = targetRect.left;
                endY = targetRect.centerY();
            } else if (sourceRect.left > targetRect.left) {
                startX = sourceRect.left;
                startY = sourceRect.centerY();
                endX = targetRect.right;
                endY = targetRect.centerY();
            } else {
                // Same column
                startX = sourceRect.right;
                startY = sourceRect.centerY();
                endX = targetRect.right;
                endY = targetRect.centerY();
            }

            bezierPath.reset();
            bezierPath.moveTo(startX, startY);

            float dx = Math.abs(endX - startX) / 2f;
            float curveOffset = Math.max(dx, 36f * density);

            if (sourceRect.left == targetRect.left) {
                // Same column loop curve
                float controlX = startX + 50f * density;
                bezierPath.cubicTo(controlX, startY, controlX, endY, endX, endY);
            } else {
                float control1X = (startX < endX) ? (startX + curveOffset) : (startX - curveOffset);
                float control2X = (startX < endX) ? (endX - curveOffset) : (endX + curveOffset);
                bezierPath.cubicTo(control1X, startY, control2X, endY, endX, endY);
            }

            float origWidth = paintToUse.getStrokeWidth();
            if (isSelectionActive && isLinkInActiveBranch) {
                paintToUse.setStrokeWidth(3.0f * density);
            }

            canvas.drawPath(bezierPath, paintToUse);

            // Draw target endpoint dot
            arrowPaint.setColor(paintToUse.getColor());
            canvas.drawCircle(endX, endY, 3.0f * density, arrowPaint);

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

    // Touch & Scaling
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
