package com.example.animelib.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
 * Интерактивный кастомный ViewGroup с отрисовкой связывающих кривых Безье
 * и группировкой аниме по годам (колонкам).
 */
public class FranchiseMapGraphView extends ViewGroup {

    private static final String TAG = "FranchiseMapGraphView";

    public interface OnNodeClickListener {
        void onNodeClick(FranchiseResponse.NodeItem node, String relation);
    }

    private FranchiseResponse franchiseData;
    private OnNodeClickListener listener;

    private final List<Integer> yearList = new ArrayList<>();
    private final Map<Integer, List<FranchiseResponse.NodeItem>> yearNodesMap = new HashMap<>();
    private final Map<Long, View> nodeViewsMap = new HashMap<>();
    private final Map<Long, RectF> nodeBoundsMap = new HashMap<>();
    private final Map<Long, String> nodeRelationsMap = new HashMap<>();

    private long selectedNodeId = -1;
    private final Set<Long> connectedNodeIds = new HashSet<>();

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
    private Paint headerBgPaint;
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
        colSpacingPx = (int) (64 * density);
        rowSpacingPx = (int) (20 * density);
        headerHeightPx = (int) (52 * density);
        paddingX = (int) (24 * density);
        paddingY = (int) (20 * density);

        initPaints(density);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
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

    private void initPaints(float density) {
        linePaintSequel = createCurvePaint(0xFFA855F7, 3.5f * density, false);  // Яркий фиолетовый для прямого продолжения
        linePaintMain = createCurvePaint(0xFF3B82F6, 3.5f * density, false);    // Синий для основной истории
        linePaintSide = createCurvePaint(0xFF06B6D4, 2.5f * density, true);     // Бирюзовый пунктир для сайд-стори
        linePaintSummary = createCurvePaint(0xFFF59E0B, 2.2f * density, false); // Янтарный для рекапов
        linePaintDefault = createCurvePaint(0xFF8E8E93, 2.0f * density, false); // Серый
        linePaintDimmed = createCurvePaint(0x338E8E93, 1.5f * density, false);  // Затененный

        headerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerBgPaint.setColor(0x269333EA);
        headerBgPaint.setStyle(Paint.Style.FILL);

        headerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerTextPaint.setColor(0xFFE2E8F0);
        headerTextPaint.setTextSize(14f * density);
        headerTextPaint.setTextAlign(Paint.Align.CENTER);
        headerTextPaint.setFakeBoldText(true);

        columnDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        columnDividerPaint.setColor(0x1AFFFFFF);
        columnDividerPaint.setStrokeWidth(1f * density);
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

        int measuredWidth = resolveSize(contentWidth, widthMeasureSpec);
        int measuredHeight = resolveSize(contentHeight, heightMeasureSpec);

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
        // 1. Draw Year Column Headers and Vertical Guidelines
        drawYearHeadersAndColumns(canvas);

        // 2. Draw Connecting Bezier Curves between nodes
        drawConnectionCurves(canvas);

        // 3. Draw native Child Views (Node Cards) on top
        super.dispatchDraw(canvas);
    }

    private void drawYearHeadersAndColumns(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        int currentX = paddingX;

        for (int col = 0; col < yearList.size(); col++) {
            int year = yearList.get(col);

            // Column vertical guideline
            float colCenterX = currentX + cardWidthPx / 2f;
            canvas.drawLine(colCenterX, paddingY + headerHeightPx, colCenterX, contentHeight - paddingY, columnDividerPaint);

            // Year Header Pill
            RectF pillRect = new RectF(currentX + 16 * density, paddingY, currentX + cardWidthPx - 16 * density, paddingY + 34 * density);
            canvas.drawRoundRect(pillRect, 12 * density, 12 * density, headerBgPaint);

            // Year Text
            canvas.drawText(String.valueOf(year), pillRect.centerX(), pillRect.centerY() + 5 * density, headerTextPaint);

            currentX += cardWidthPx + colSpacingPx;
        }
    }

    private void drawConnectionCurves(Canvas canvas) {
        if (franchiseData == null || franchiseData.getLinks() == null) return;

        Path bezierPath = new Path();

        for (FranchiseResponse.LinkItem link : franchiseData.getLinks()) {
            RectF sourceRect = nodeBoundsMap.get(link.getSourceId());
            RectF targetRect = nodeBoundsMap.get(link.getTargetId());

            if (sourceRect == null || targetRect == null) continue;

            boolean isConnectedToSelection = (selectedNodeId > 0 &&
                    (link.getSourceId() == selectedNodeId || link.getTargetId() == selectedNodeId));

            Paint paintToUse;
            if (selectedNodeId > 0 && !isConnectedToSelection) {
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
            float dy = Math.abs(endY - startY) / 2f;
            float curveOffset = Math.max(dx, 40f * getResources().getDisplayMetrics().density);

            if (sourceRect.left == targetRect.left) {
                // Same column loop curve
                float controlX = startX + 60f * getResources().getDisplayMetrics().density;
                bezierPath.cubicTo(controlX, startY, controlX, endY, endX, endY);
            } else {
                float control1X = (startX < endX) ? (startX + curveOffset) : (startX - curveOffset);
                float control2X = (startX < endX) ? (endX - curveOffset) : (endX + curveOffset);
                bezierPath.cubicTo(control1X, startY, control2X, endY, endX, endY);
            }

            canvas.drawPath(bezierPath, paintToUse);

            // Draw target endpoint dot
            arrowPaint.setColor(paintToUse.getColor());
            canvas.drawCircle(endX, endY, 4f * getResources().getDisplayMetrics().density, arrowPaint);
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

    // Touch & Scrolling
    @Override
    public boolean onTouchEvent(MotionEvent event) {
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
        return Math.max(0, contentWidth - getWidth());
    }

    private int getMaxScrollY() {
        return Math.max(0, contentHeight - getHeight());
    }
}
