package com.example.animelib.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.R;

/**
 * Custom container for the Settings button and its Quality badge (e.g. HD / FHD / 4K).
 * When the quality badge is visible, a clean cutout with a small uniform margin is masked out
 * of the settings gear icon underneath, creating a modern YouTube-style integrated badge cutout.
 */
public class SettingsBadgeContainer extends FrameLayout {

    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cutoutRect = new RectF();

    private View settingsButton;
    private View settingsQualityTag;

    private float gapPx;
    private float badgeCornerRadiusPx;

    public SettingsBadgeContainer(@NonNull Context context) {
        super(context);
        init();
    }

    public SettingsBadgeContainer(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SettingsBadgeContainer(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClipChildren(false);
        setClipToPadding(false);

        clearPaint.setStyle(Paint.Style.FILL);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        // Precision gap (1.5dp) between badge and gear cutout
        gapPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, getResources().getDisplayMetrics());
        // Badge has 2.5dp corner radius; concentric cutout radius = 2.5dp + 1.5dp = 4.0dp
        badgeCornerRadiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, getResources().getDisplayMetrics());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        resolveChildViews();
    }

    private void resolveChildViews() {
        if (settingsButton == null) {
            settingsButton = findViewById(R.id.settingsButton);
        }
        if (settingsQualityTag == null) {
            View tag = findViewById(R.id.settingsQualityTag);
            if (tag != null) {
                settingsQualityTag = tag;
                settingsQualityTag.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (settingsButton != null) {
                        settingsButton.invalidate();
                    }
                    invalidate();
                });
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        resolveChildViews();
        if (settingsQualityTag != null && settingsQualityTag.getVisibility() != GONE) {
            int tagWidth = settingsQualityTag.getMeasuredWidth();
            int tagHeight = settingsQualityTag.getMeasuredHeight();
            MarginLayoutParams lp = (MarginLayoutParams) settingsQualityTag.getLayoutParams();
            int requiredWidth = tagWidth + Math.max(0, lp.leftMargin) + Math.max(0, lp.rightMargin);
            int requiredHeight = tagHeight + Math.max(0, lp.topMargin) + Math.max(0, lp.bottomMargin);

            int finalWidth = Math.max(getMeasuredWidth(), requiredWidth);
            int finalHeight = Math.max(getMeasuredHeight(), requiredHeight);

            if (finalWidth != getMeasuredWidth() || finalHeight != getMeasuredHeight()) {
                setMeasuredDimension(finalWidth, finalHeight);
            }
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        resolveChildViews();

        if (child == settingsButton && settingsQualityTag != null
                && settingsQualityTag.getVisibility() == VISIBLE
                && settingsQualityTag.getWidth() > 0
                && settingsQualityTag.getHeight() > 0) {

            // Save offscreen layer covering the settings button bounds
            int saveCount = canvas.saveLayer(
                    child.getLeft(),
                    child.getTop(),
                    child.getRight(),
                    child.getBottom(),
                    null
            );

            // Draw the settings gear icon into the offscreen layer
            boolean result = super.drawChild(canvas, child, drawingTime);

            // Calculate concentric cutout rectangle around the quality badge with uniform gap
            float gap = gapPx;
            cutoutRect.set(
                    settingsQualityTag.getLeft() - gap,
                    settingsQualityTag.getTop() - gap,
                    settingsQualityTag.getRight() + gap,
                    settingsQualityTag.getBottom() + gap
            );
            float cutoutRadius = badgeCornerRadiusPx + gap;

            // Clear out the cutout shape to full transparency
            canvas.drawRoundRect(cutoutRect, cutoutRadius, cutoutRadius, clearPaint);

            canvas.restoreToCount(saveCount);
            return result;
        }

        return super.drawChild(canvas, child, drawingTime);
    }
}
