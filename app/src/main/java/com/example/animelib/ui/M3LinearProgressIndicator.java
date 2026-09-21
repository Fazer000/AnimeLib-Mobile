package com.example.animelib.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.example.animelib.R;

/**
 * Material 3 Linear Progress Indicator with:
 * - Active pill with rounded corners
 * - Separation gap between active and inactive tracks
 * - Inactive pill with rounded corners
 * - Stop dot at the end with a separation gap
 * - Smooth progress animation and M3 indeterminate animation
 */
public class M3LinearProgressIndicator extends View {

    private int max = 100;
    private int progress = 0;
    private float animatedProgress = 0f;
    private boolean isIndeterminate = false;

    private float trackHeight;
    private float gapSize;
    private float stopDotSize;
    private boolean showStopDot = true;

    @ColorInt
    private int indicatorColor;
    @ColorInt
    private int trackColor;
    @ColorInt
    private int stopDotColor;

    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stopDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();

    private ValueAnimator progressAnimator;
    private ValueAnimator indeterminateAnimator;
    private float indeterminateFraction = 0f;

    public M3LinearProgressIndicator(@NonNull Context context) {
        this(context, null);
    }

    public M3LinearProgressIndicator(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public M3LinearProgressIndicator(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {
        float density = getResources().getDisplayMetrics().density;
        trackHeight = 8f * density;
        gapSize = 4f * density;
        stopDotSize = 4f * density;

        indicatorColor = ContextCompat.getColor(context, R.color.purple_primary);
        trackColor = ContextCompat.getColor(context, R.color.purple_alpha_25);
        stopDotColor = indicatorColor;

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.M3LinearProgressIndicator);
            try {
                trackHeight = a.getDimension(R.styleable.M3LinearProgressIndicator_m3TrackHeight, trackHeight);
                gapSize = a.getDimension(R.styleable.M3LinearProgressIndicator_m3GapSize, gapSize);
                stopDotSize = a.getDimension(R.styleable.M3LinearProgressIndicator_m3StopDotSize, stopDotSize);
                showStopDot = a.getBoolean(R.styleable.M3LinearProgressIndicator_m3ShowStopDot, showStopDot);
                indicatorColor = a.getColor(R.styleable.M3LinearProgressIndicator_m3IndicatorColor, indicatorColor);
                trackColor = a.getColor(R.styleable.M3LinearProgressIndicator_m3TrackColor, trackColor);
                stopDotColor = a.getColor(R.styleable.M3LinearProgressIndicator_m3StopDotColor, indicatorColor);

                max = a.getInt(R.styleable.M3LinearProgressIndicator_android_max, 100);
                progress = a.getInt(R.styleable.M3LinearProgressIndicator_android_progress, 0);
                isIndeterminate = a.getBoolean(R.styleable.M3LinearProgressIndicator_android_indeterminate, false);
            } finally {
                a.recycle();
            }
        }

        indicatorPaint.setStyle(Paint.Style.FILL);
        indicatorPaint.setColor(indicatorColor);

        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(trackColor);

        stopDotPaint.setStyle(Paint.Style.FILL);
        stopDotPaint.setColor(stopDotColor);

        animatedProgress = progress;

        if (isIndeterminate) {
            startIndeterminateAnimation();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int minHeight = (int) (trackHeight + getPaddingTop() + getPaddingBottom());
        int height = resolveSize(minHeight, heightMeasureSpec);
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float availWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float availHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (availWidth <= 0 || trackHeight <= 0) return;

        float left = getPaddingLeft();
        float top = getPaddingTop() + (availHeight - trackHeight) / 2f;
        float bottom = top + trackHeight;
        float right = left + availWidth;
        float cornerRadius = trackHeight / 2f;
        float stopDotRadius = stopDotSize > 0 ? Math.min(stopDotSize / 2f, cornerRadius) : (cornerRadius / 2f);

        // Center stop dot inside the rounded cap at the end of the track
        float stopDotCenterX = right - cornerRadius;
        float stopDotCenterY = top + cornerRadius;

        if (isIndeterminate) {
            drawIndeterminate(canvas, left, top, right, bottom, cornerRadius, stopDotRadius,
                    stopDotCenterX, stopDotCenterY);
        } else {
            drawDeterminate(canvas, left, top, right, bottom, cornerRadius, stopDotRadius,
                    stopDotCenterX, stopDotCenterY);
        }
    }

    private void drawDeterminate(Canvas canvas, float left, float top, float right, float bottom,
                                  float cornerRadius, float stopDotRadius,
                                  float stopDotCenterX, float stopDotCenterY) {
        float fraction = (max > 0) ? Math.max(0f, Math.min(1f, animatedProgress / (float) max)) : 0f;

        if (fraction <= 0f) {
            // 0% progress: Inactive track spans full width from left to right
            drawRoundedSegment(canvas, left, right, top, bottom, cornerRadius, trackPaint);
            // Stop dot drawn inside the track at the right end
            if (showStopDot) {
                canvas.drawCircle(stopDotCenterX, stopDotCenterY, stopDotRadius, stopDotPaint);
            }
        } else if (fraction >= 1f) {
            // 100% Complete: Fully filled active track from left to right
            drawRoundedSegment(canvas, left, right, top, bottom, cornerRadius, indicatorPaint);
        } else {
            float totalWidth = right - left;
            float activeEnd = left + fraction * totalWidth;

            // Draw active track
            if (activeEnd > left) {
                drawRoundedSegment(canvas, left, activeEnd, top, bottom, cornerRadius, indicatorPaint);
            }

            // Draw inactive track with gap separation and rounded corners, extending all the way to right
            float inactiveStart = activeEnd + gapSize;
            if (right > inactiveStart) {
                drawRoundedSegment(canvas, inactiveStart, right, top, bottom, cornerRadius, trackPaint);
            }

            // Draw stop dot INSIDE the inactive track at the right end
            if (showStopDot && (activeEnd + gapSize) < (stopDotCenterX + stopDotRadius)) {
                canvas.drawCircle(stopDotCenterX, stopDotCenterY, stopDotRadius, stopDotPaint);
            }
        }
    }

    private void drawIndeterminate(Canvas canvas, float left, float top, float right, float bottom,
                                    float cornerRadius, float stopDotRadius,
                                    float stopDotCenterX, float stopDotCenterY) {
        // Draw background track across the full width
        drawRoundedSegment(canvas, left, right, top, bottom, cornerRadius, trackPaint);

        // Draw stop dot inside the track at the end
        if (showStopDot) {
            canvas.drawCircle(stopDotCenterX, stopDotCenterY, stopDotRadius, stopDotPaint);
        }

        float totalSpan = right - left;
        if (totalSpan <= 0) return;

        float t = indeterminateFraction;

        // Primary moving segment
        float s1StartNorm = Math.max(0f, t * 1.5f - 0.4f);
        float s1EndNorm = Math.min(1f, t * 1.4f);
        if (s1EndNorm > s1StartNorm) {
            float s1Left = left + s1StartNorm * totalSpan;
            float s1Right = left + s1EndNorm * totalSpan;
            if (s1Right > s1Left) {
                drawRoundedSegment(canvas, s1Left, s1Right, top, bottom, cornerRadius, indicatorPaint);
            }
        }

        // Secondary moving segment
        float t2 = (t + 0.45f) % 1.0f;
        float s2StartNorm = Math.max(0f, t2 * 1.5f - 0.4f);
        float s2EndNorm = Math.min(1f, t2 * 1.3f);
        if (s2EndNorm > s2StartNorm) {
            float s2Left = left + s2StartNorm * totalSpan;
            float s2Right = left + s2EndNorm * totalSpan;
            if (s2Right > s2Left) {
                drawRoundedSegment(canvas, s2Left, s2Right, top, bottom, cornerRadius, indicatorPaint);
            }
        }
    }

    private void drawRoundedSegment(Canvas canvas, float startX, float endX, float top, float bottom,
                                     float cornerRadius, Paint paint) {
        float width = endX - startX;
        if (width <= 0f) return;
        if (width < cornerRadius * 2f) {
            float r = width / 2f;
            canvas.drawCircle(startX + r, (top + bottom) / 2f, r, paint);
        } else {
            rectF.set(startX, top, endX, bottom);
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
        }
    }

    public void setProgress(int progress) {
        setProgress(progress, true);
    }

    public void setProgress(int progress, boolean animate) {
        int clamped = Math.max(0, Math.min(max, progress));
        this.progress = clamped;

        if (!animate || Math.abs(animatedProgress - clamped) < 0.5f) {
            if (progressAnimator != null) {
                progressAnimator.cancel();
            }
            animatedProgress = clamped;
            postInvalidateOnAnimation();
        } else {
            if (progressAnimator != null) {
                progressAnimator.cancel();
            }
            progressAnimator = ValueAnimator.ofFloat(animatedProgress, clamped);
            progressAnimator.setDuration(240);
            progressAnimator.setInterpolator(new FastOutSlowInInterpolator());
            progressAnimator.addUpdateListener(anim -> {
                animatedProgress = (float) anim.getAnimatedValue();
                postInvalidateOnAnimation();
            });
            progressAnimator.start();
        }
    }

    public int getProgress() {
        return progress;
    }

    public void setMax(int max) {
        if (max <= 0) max = 1;
        this.max = max;
        if (progress > max) {
            progress = max;
            animatedProgress = max;
        }
        postInvalidateOnAnimation();
    }

    public int getMax() {
        return max;
    }

    public void setIndeterminate(boolean indeterminate) {
        if (this.isIndeterminate == indeterminate) return;
        this.isIndeterminate = indeterminate;
        if (indeterminate) {
            startIndeterminateAnimation();
        } else {
            stopIndeterminateAnimation();
            animatedProgress = progress;
        }
        postInvalidateOnAnimation();
    }

    public boolean isIndeterminate() {
        return isIndeterminate;
    }

    public void setIndicatorColor(@ColorInt int color) {
        this.indicatorColor = color;
        indicatorPaint.setColor(color);
        postInvalidateOnAnimation();
    }

    public void setTrackColor(@ColorInt int color) {
        this.trackColor = color;
        trackPaint.setColor(color);
        postInvalidateOnAnimation();
    }

    public void setStopDotColor(@ColorInt int color) {
        this.stopDotColor = color;
        stopDotPaint.setColor(color);
        postInvalidateOnAnimation();
    }

    public void setShowStopDot(boolean showStopDot) {
        this.showStopDot = showStopDot;
        postInvalidateOnAnimation();
    }

    public void setTrackHeight(float trackHeightPx) {
        this.trackHeight = trackHeightPx;
        requestLayout();
        postInvalidateOnAnimation();
    }

    public void setGapSize(float gapSizePx) {
        this.gapSize = gapSizePx;
        postInvalidateOnAnimation();
    }

    private void startIndeterminateAnimation() {
        stopIndeterminateAnimation();
        indeterminateAnimator = ValueAnimator.ofFloat(0f, 1f);
        indeterminateAnimator.setDuration(1600);
        indeterminateAnimator.setInterpolator(new LinearInterpolator());
        indeterminateAnimator.setRepeatCount(ValueAnimator.INFINITE);
        indeterminateAnimator.setRepeatMode(ValueAnimator.RESTART);
        indeterminateAnimator.addUpdateListener(anim -> {
            indeterminateFraction = (float) anim.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        indeterminateAnimator.start();
    }

    private void stopIndeterminateAnimation() {
        if (indeterminateAnimator != null) {
            indeterminateAnimator.cancel();
            indeterminateAnimator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isIndeterminate && getVisibility() == VISIBLE) {
            startIndeterminateAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopIndeterminateAnimation();
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            if (isIndeterminate) {
                startIndeterminateAnimation();
            }
        } else {
            stopIndeterminateAnimation();
            if (progressAnimator != null) {
                progressAnimator.cancel();
            }
        }
    }
}
