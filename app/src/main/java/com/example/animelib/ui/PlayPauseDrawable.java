package com.example.animelib.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/**
 * High-performance Morphing Outlined Play/Pause Drawable.
 * Directly morphs an outlined (stroke) rounded Play triangle into two outlined (stroke) Pause bars.
 * No rotation. Generous corner rounding. Ultra-smooth Material cubic interpolation with subtle tactile pulse.
 */
public class PlayPauseDrawable extends Drawable {

    private final Paint paint;
    private final Path playPath;
    private final Path leftBarPath;
    private final Path rightBarPath;

    private float progress = 0.0f; // 0.0f = PLAY, 1.0f = PAUSE
    private boolean isPause = false;
    private ValueAnimator animator;

    private int color = Color.WHITE;

    public PlayPauseDrawable() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);

        playPath = new Path();
        leftBarPath = new Path();
        rightBarPath = new Path();
    }

    public PlayPauseDrawable(int color) {
        this();
        setColor(color);
    }

    public void setColor(int color) {
        this.color = color;
        paint.setColor(color);
        invalidateSelf();
    }

    public boolean isPause() {
        return isPause;
    }

    public boolean isAnimating() {
        return animator != null && animator.isRunning();
    }

    public void setPause(boolean pause, boolean animate) {
        if (this.isPause == pause) {
            if (isAnimating()) {
                return;
            }
            if (!animate) {
                this.progress = pause ? 1.0f : 0.0f;
                invalidateSelf();
            }
            return;
        }

        this.isPause = pause;
        float targetProgress = pause ? 1.0f : 0.0f;

        if (!animate) {
            if (animator != null) {
                animator.cancel();
            }
            this.progress = targetProgress;
            invalidateSelf();
            return;
        }

        if (animator != null) {
            animator.cancel();
        }

        float startProgress = this.progress;

        animator = ValueAnimator.ofFloat(startProgress, targetProgress);
        animator.setDuration(260);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
        animator.start();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        float width = bounds.width();
        float height = bounds.height();
        float size = Math.min(width, height);
        if (size <= 0) return;

        float scale = size / 24.0f;
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();

        // Stroke width aligned with the outlined player theme
        float strokeWidth = 1.85f * scale;
        paint.setStrokeWidth(strokeWidth);

        int saveCount = canvas.save();

        float p = Math.max(0.0f, Math.min(1.0f, progress));

        // Subtle organic tactile pulse during transition (compress slightly then release)
        float pulse = 1.0f - 0.08f * (float) Math.sin(p * Math.PI);
        canvas.scale(pulse, pulse, cx, cy);

        // Generous corner rounding for the play triangle
        float cornerRadius = 3.2f * scale;

        if (p <= 0.001f) {
            // Pure PLAY: Single continuous stroked triangle with soft, smooth, rounded corners
            paint.setPathEffect(new CornerPathEffect(cornerRadius));

            playPath.rewind();
            playPath.moveTo(cx + (7.5f - 12.0f) * scale, cy + (5.0f - 12.0f) * scale);
            playPath.lineTo(cx + (19.2f - 12.0f) * scale, cy + (12.0f - 12.0f) * scale);
            playPath.lineTo(cx + (7.5f - 12.0f) * scale, cy + (19.0f - 12.0f) * scale);
            playPath.close();

            canvas.drawPath(playPath, paint);
            paint.setPathEffect(null);
        } else if (p >= 0.999f) {
            // Pure PAUSE: Two vertical parallel stroke bars with round caps
            paint.setPathEffect(null);
            float x1 = cx + (8.5f - 12.0f) * scale;
            float x2 = cx + (15.5f - 12.0f) * scale;
            float yTop = cy + (5.5f - 12.0f) * scale;
            float yBot = cy + (18.5f - 12.0f) * scale;

            canvas.drawLine(x1, yTop, x1, yBot, paint);
            canvas.drawLine(x2, yTop, x2, yBot, paint);
        } else {
            // Smooth morphing in-between without rotation
            float currentRadius = cornerRadius * (1.0f - p);
            if (currentRadius > 0.1f) {
                paint.setPathEffect(new CornerPathEffect(currentRadius));
            } else {
                paint.setPathEffect(null);
            }

            float x_left = 7.5f + (8.5f - 7.5f) * p;

            float x_top = 7.5f + (15.5f - 7.5f) * p;
            float y_top = 5.0f + 0.5f * p;

            float x_apex = 19.2f - (19.2f - 15.5f) * p;
            float y_apex = 12.0f;

            float x_bot = 7.5f + (15.5f - 7.5f) * p;
            float y_bot = 19.0f - 0.5f * p;

            // Left bar
            leftBarPath.rewind();
            leftBarPath.moveTo(cx + (x_left - 12.0f) * scale, cy + (5.5f - 12.0f) * scale);
            leftBarPath.lineTo(cx + (x_left - 12.0f) * scale, cy + (18.5f - 12.0f) * scale);

            // Right chevron morphing into vertical bar
            rightBarPath.rewind();
            rightBarPath.moveTo(cx + (x_top - 12.0f) * scale, cy + (y_top - 12.0f) * scale);
            rightBarPath.lineTo(cx + (x_apex - 12.0f) * scale, cy + (y_apex - 12.0f) * scale);
            rightBarPath.lineTo(cx + (x_bot - 12.0f) * scale, cy + (y_bot - 12.0f) * scale);

            canvas.drawPath(leftBarPath, paint);
            canvas.drawPath(rightBarPath, paint);
            paint.setPathEffect(null);
        }

        canvas.restoreToCount(saveCount);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
