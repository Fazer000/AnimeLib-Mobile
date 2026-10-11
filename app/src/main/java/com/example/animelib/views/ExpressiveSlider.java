package com.example.animelib.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.example.animelib.R;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

public class ExpressiveSlider extends Slider {

    private final Paint activeTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inactiveTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF activeRect = new RectF();
    private final RectF inactiveRect = new RectF();
    private final RectF thumbRect = new RectF();

    public ExpressiveSlider(@NonNull Context context) {
        this(context, null);
    }

    public ExpressiveSlider(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, com.google.android.material.R.attr.sliderStyle);
    }

    public ExpressiveSlider(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        activeTrackPaint.setStyle(Paint.Style.FILL);
        inactiveTrackPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setStyle(Paint.Style.FILL);
        endDotPaint.setStyle(Paint.Style.FILL);
        activeDotPaint.setStyle(Paint.Style.FILL);

        int primaryColor = ContextCompat.getColor(context, R.color.purple_primary);
        int inactiveColor = ContextCompat.getColor(context, R.color.purple_alpha_25);

        activeTrackPaint.setColor(primaryColor);
        inactiveTrackPaint.setColor(inactiveColor);
        thumbPaint.setColor(primaryColor);
        endDotPaint.setColor(primaryColor);
        activeDotPaint.setColor(android.graphics.Color.argb(160, 255, 255, 255));

        setTickVisible(false);
        setLabelBehavior(LabelFormatter.LABEL_GONE);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        // Do NOT call super.onDraw(canvas) so Material 3 default track artwork isn't rendered under our custom layout.

        float density = getResources().getDisplayMetrics().density;

        // Custom Expressive Slider Dimensions (exact match to screenshot)
        float trackHeight = 14f * density;
        float thumbWidth = 5.5f * density; // Thicker thumb
        float thumbHeight = 30f * density;
        float gap = 5.5f * density;
        float dotRadius = 2.2f * density;
        float dotMarginRight = 7f * density;

        // Update colors from Slider tint lists if configured
        ColorStateList activeTint = getTrackActiveTintList();
        if (activeTint != null) {
            int color = activeTint.getColorForState(getDrawableState(), activeTrackPaint.getColor());
            activeTrackPaint.setColor(color);
            endDotPaint.setColor(color);
        }
        ColorStateList thumbTint = getThumbTintList();
        if (thumbTint != null) {
            int color = thumbTint.getColorForState(getDrawableState(), thumbPaint.getColor());
            thumbPaint.setColor(color);
        }
        ColorStateList inactiveTint = getTrackInactiveTintList();
        if (inactiveTint != null) {
            int color = inactiveTint.getColorForState(getDrawableState(), inactiveTrackPaint.getColor());
            inactiveTrackPaint.setColor(color);
        }

        if (!isEnabled()) {
            activeTrackPaint.setAlpha(128);
            thumbPaint.setAlpha(128);
            endDotPaint.setAlpha(128);
        }

        float valFrom = getValueFrom();
        float valTo = getValueTo();
        float currentVal = getValue();

        float fraction = (valTo > valFrom) ? (currentVal - valFrom) / (valTo - valFrom) : 0f;
        if (fraction < 0f) fraction = 0f;
        if (fraction > 1f) fraction = 1f;

        // Side padding for thumb center positioning
        float sidePadding = thumbWidth / 2f + 12f * density;
        float startX = getPaddingLeft() + sidePadding;
        float endX = getWidth() - getPaddingRight() - sidePadding;
        float availableWidth = Math.max(0f, endX - startX);

        float thumbCenterX = startX + fraction * availableWidth;
        float centerY = getHeight() / 2f;

        float trackLeftBoundary = getPaddingLeft() + 4f * density;
        float trackRightBoundary = getWidth() - getPaddingRight() - 4f * density;

        float thumbLeft = thumbCenterX - thumbWidth / 2f;
        float thumbRight = thumbCenterX + thumbWidth / 2f;

        // 1. Draw Active Track (Left segment)
        float activeRight = thumbLeft - gap;
        if (activeRight > trackLeftBoundary) {
            activeRect.set(
                trackLeftBoundary,
                centerY - trackHeight / 2f,
                activeRight,
                centerY + trackHeight / 2f
            );
            float cornerRadius = trackHeight / 2f;
            canvas.drawRoundRect(activeRect, cornerRadius, cornerRadius, activeTrackPaint);
        }

        // 2. Draw Inactive Track (Right segment)
        float inactiveLeft = thumbRight + gap;
        if (trackRightBoundary > inactiveLeft) {
            inactiveRect.set(
                inactiveLeft,
                centerY - trackHeight / 2f,
                trackRightBoundary,
                centerY + trackHeight / 2f
            );
            float cornerRadius = trackHeight / 2f;
            canvas.drawRoundRect(inactiveRect, cornerRadius, cornerRadius, inactiveTrackPaint);

        }

        // 3. Draw Dots for step divisions inside the slider or single end dot
        float stepSize = getStepSize();
        int numSteps = (stepSize > 0 && valTo > valFrom) ? Math.round((valTo - valFrom) / stepSize) : 0;

        if (numSteps >= 1 && numSteps <= 30) {
            for (int i = 0; i <= numSteps; i++) {
                float stepFraction = (float) i / (float) numSteps;
                float dotX = startX + stepFraction * availableWidth;

                // Don't draw dot if covered by the thumb or thumb gap
                if (dotX >= thumbLeft - gap && dotX <= thumbRight + gap) {
                    continue;
                }

                if (dotX < thumbLeft - gap && dotX >= trackLeftBoundary + dotRadius) {
                    // Inside active track (subtle light dot)
                    canvas.drawCircle(dotX, centerY, dotRadius, activeDotPaint);
                } else if (dotX > thumbRight + gap && dotX <= trackRightBoundary - dotRadius) {
                    // Inside inactive track (purple dot, same as end dot)
                    canvas.drawCircle(dotX, centerY, dotRadius, endDotPaint);
                }
            }
        } else if (trackRightBoundary > inactiveLeft) {
            // Continuous slider: single dot near the right end of inactive track
            float dotX = trackRightBoundary - dotMarginRight;
            if (dotX > inactiveLeft + dotMarginRight) {
                canvas.drawCircle(dotX, centerY, dotRadius, endDotPaint);
            }
        }

        // 4. Draw Thumb (Vertical thin rounded line)
        thumbRect.set(
            thumbLeft,
            centerY - thumbHeight / 2f,
            thumbRight,
            centerY + thumbHeight / 2f
        );
        float thumbCornerRadius = thumbWidth / 2f;
        canvas.drawRoundRect(thumbRect, thumbCornerRadius, thumbCornerRadius, thumbPaint);
    }
}
