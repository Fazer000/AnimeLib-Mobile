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

        // Precision gap (2.0dp) between badge and gear cutout
        gapPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.0f, getResources().getDisplayMetrics());
        // Badge has 3.5dp corner radius; concentric cutout radius = 3.5dp + 2.0dp = 5.5dp
        badgeCornerRadiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3.5f, getResources().getDisplayMetrics());
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
