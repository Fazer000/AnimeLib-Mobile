package com.example.animelib.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
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
 * No rotation. Continuous Bézier interpolation without abrupt corner popping.
 * Sleek 1.45dp line weight matching the player's vector icon theme.
 */
public class PlayPauseDrawable extends Drawable {

    private final Paint paint;
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
        long duration = (long) (180 * Math.abs(targetProgress - startProgress));
        if (duration <= 0) duration = 180;

        animator = ValueAnimator.ofFloat(startProgress, targetProgress);
        animator.setDuration(duration);
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

        // Refined stroke thickness (1.45dp standard, matching the player outline icons)
        float strokeWidth = 1.45f * scale;
        paint.setStrokeWidth(strokeWidth);

        int saveCount = canvas.save();

        float p = Math.max(0.0f, Math.min(1.0f, progress));

        // Subtle organic tactile pulse during transition (compress slightly then release)
        float pulse = 1.0f - 0.07f * (float) Math.sin(p * Math.PI);
        canvas.scale(pulse, pulse, cx, cy);

        // --- Left Path: Exactly matches ic_player_play.xml left half at p=0, morphs to M8.5,5.5V18.5 at p=1 ---
        float lx1 = 9.9f + (8.5f - 9.9f) * p;
        float ly1 = 5.02f + (5.5f - 5.02f) * p;

        float lcp1x = 8.85f + (8.5f - 8.85f) * p;
        float lcp1y = 4.37f + (6.0f - 4.37f) * p;

        float lcp2x = 7.5f + (8.5f - 7.5f) * p;
        float lcp2y = 5.12f + (6.8f - 5.12f) * p;

        float lx2 = 7.5f + (8.5f - 7.5f) * p;
        float ly2 = 6.35f + (7.5f - 6.35f) * p;

        float lx3 = 7.5f + (8.5f - 7.5f) * p;
        float ly3 = 17.65f + (16.5f - 17.65f) * p;

        float lcp3x = 7.5f + (8.5f - 7.5f) * p;
        float lcp3y = 18.88f + (17.2f - 18.88f) * p;

        float lcp4x = 8.85f + (8.5f - 8.85f) * p;
        float lcp4y = 19.63f + (18.0f - 19.63f) * p;

        float lx4 = 9.9f + (8.5f - 9.9f) * p;
        float ly4 = 18.98f + (18.5f - 18.98f) * p;

        leftBarPath.rewind();
        leftBarPath.moveTo(cx + (lx1 - 12f) * scale, cy + (ly1 - 12f) * scale);
        leftBarPath.cubicTo(
                cx + (lcp1x - 12f) * scale, cy + (lcp1y - 12f) * scale,
                cx + (lcp2x - 12f) * scale, cy + (lcp2y - 12f) * scale,
                cx + (lx2 - 12f) * scale, cy + (ly2 - 12f) * scale
        );
        leftBarPath.lineTo(cx + (lx3 - 12f) * scale, cy + (ly3 - 12f) * scale);
        leftBarPath.cubicTo(
                cx + (lcp3x - 12f) * scale, cy + (lcp3y - 12f) * scale,
                cx + (lcp4x - 12f) * scale, cy + (lcp4y - 12f) * scale,
                cx + (lx4 - 12f) * scale, cy + (ly4 - 12f) * scale
        );

        // --- Right Path: Exactly matches ic_player_play.xml apex nose & diagonals at p=0, morphs to M15.5,5.5V18.5 at p=1 ---
        float rx1 = 9.9f + (15.5f - 9.9f) * p;
        float ry1 = 5.02f + (5.5f - 5.02f) * p;

        float rx2 = 18.85f + (15.5f - 18.85f) * p;
        float ry2 = 10.67f + (10.5f - 10.67f) * p;

        float rcp1x = 19.83f + (15.5f - 19.83f) * p;
        float rcp1y = 11.28f + (11.5f - 11.28f) * p;

        float rcp2x = 19.83f + (15.5f - 19.83f) * p;
        float rcp2y = 12.72f + (12.5f - 12.72f) * p;

        float rx3 = 18.85f + (15.5f - 18.85f) * p;
        float ry3 = 13.33f + (13.5f - 13.33f) * p;

        float rx4 = 9.9f + (15.5f - 9.9f) * p;
        float ry4 = 18.98f + (18.5f - 18.98f) * p;

        rightBarPath.rewind();
        rightBarPath.moveTo(cx + (rx1 - 12f) * scale, cy + (ry1 - 12f) * scale);
        rightBarPath.lineTo(cx + (rx2 - 12f) * scale, cy + (ry2 - 12f) * scale);
        rightBarPath.cubicTo(
                cx + (rcp1x - 12f) * scale, cy + (rcp1y - 12f) * scale,
                cx + (rcp2x - 12f) * scale, cy + (rcp2y - 12f) * scale,
                cx + (rx3 - 12f) * scale, cy + (ry3 - 12f) * scale
        );
        rightBarPath.lineTo(cx + (rx4 - 12f) * scale, cy + (ry4 - 12f) * scale);

        // Draw both paths directly without snapping or mode switching
        canvas.drawPath(leftBarPath, paint);
        canvas.drawPath(rightBarPath, paint);

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
