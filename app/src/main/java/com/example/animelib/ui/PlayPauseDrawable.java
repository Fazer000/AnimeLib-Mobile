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
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * YouTube-style Morphing Play/Pause Drawable.
 * Morphing path transformation with rotation and elastic pulse scaling.
 */
public class PlayPauseDrawable extends Drawable {

    private final Paint paint;
    private final Path leftPath;
    private final Path rightPath;

    private float progress = 0.0f; // 0.0f = PLAY, 1.0f = PAUSE
    private float rotation = 0.0f; // in degrees
    private boolean isPause = false;
    private ValueAnimator animator;

    private int color = Color.WHITE;

    public PlayPauseDrawable() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);

        leftPath = new Path();
        rightPath = new Path();
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

    public boolean isAnimating() {
        return animator != null && animator.isRunning();
    }

    public void setPause(boolean pause, boolean animate) {
        if (this.isPause == pause) {
            if (isAnimating()) {
                // If it's already morphing towards this state, let it complete
                return;
            }
            if (!animate) {
                this.progress = pause ? 1.0f : 0.0f;
                this.rotation = pause ? 90.0f : 0.0f;
                invalidateSelf();
            }
            return;
        }

        this.isPause = pause;
        float targetProgress = pause ? 1.0f : 0.0f;
        float targetRotation = pause ? 90.0f : 0.0f;

        if (!animate) {
            if (animator != null) {
                animator.cancel();
            }
            this.progress = targetProgress;
            this.rotation = targetRotation;
            invalidateSelf();
            return;
        }

        if (animator != null) {
            animator.cancel();
        }

        float startProgress = this.progress;
        float startRotation = this.rotation;

        animator = ValueAnimator.ofFloat(0.0f, 1.0f);
        animator.setDuration(320);
        animator.setInterpolator(new android.view.animation.OvershootInterpolator(1.2f));
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            progress = startProgress + (targetProgress - startProgress) * fraction;
            rotation = startRotation + (targetRotation - startRotation) * fraction;
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

        int saveCount = canvas.save();

        // 1. Elastic pulse scaling during morphing
        float pulse = 1.0f - 0.12f * (float) Math.sin(progress * Math.PI);
        canvas.scale(pulse, pulse, cx, cy);

        // 2. Rotate canvas
        canvas.rotate(rotation, cx, cy);

        // 3. Smooth corner rounding
        float cornerRadius = (2.2f - 0.8f * progress) * scale;
        paint.setPathEffect(new CornerPathEffect(cornerRadius));

        float p = progress;

        // Path 1 (Left bar <-> Top triangle half)
        float x11 = 7.5f - 2.75f * p;
        float y11 = 4.75f + 2.0f * p;

        float x12 = 18.5f + 0.75f * p;
        float y12 = 12.0f - 5.25f * p;

        float x13 = 18.5f + 0.75f * p;
        float y13 = 12.0f - 0.75f * p;

        float x14 = 7.5f - 2.75f * p;
        float y14 = 12.0f - 0.75f * p;

        leftPath.rewind();
        leftPath.moveTo(cx + (x11 - 12.0f) * scale, cy + (y11 - 12.0f) * scale);
        leftPath.lineTo(cx + (x12 - 12.0f) * scale, cy + (y12 - 12.0f) * scale);
        leftPath.lineTo(cx + (x13 - 12.0f) * scale, cy + (y13 - 12.0f) * scale);
        leftPath.lineTo(cx + (x14 - 12.0f) * scale, cy + (y14 - 12.0f) * scale);
        leftPath.close();

        // Path 2 (Right bar <-> Bottom triangle half)
        float x21 = 7.5f - 2.75f * p;
        float y21 = 12.0f + 0.75f * p;

        float x22 = 18.5f + 0.75f * p;
        float y22 = 12.0f + 0.75f * p;

        float x23 = 18.5f + 0.75f * p;
        float y23 = 12.0f + 5.25f * p;

        float x24 = 7.5f - 2.75f * p;
        float y24 = 19.25f - 2.0f * p;

        rightPath.rewind();
        rightPath.moveTo(cx + (x21 - 12.0f) * scale, cy + (y21 - 12.0f) * scale);
        rightPath.lineTo(cx + (x22 - 12.0f) * scale, cy + (y22 - 12.0f) * scale);
        rightPath.lineTo(cx + (x23 - 12.0f) * scale, cy + (y23 - 12.0f) * scale);
        rightPath.lineTo(cx + (x24 - 12.0f) * scale, cy + (y24 - 12.0f) * scale);
        rightPath.close();

        canvas.drawPath(leftPath, paint);
        canvas.drawPath(rightPath, paint);

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
