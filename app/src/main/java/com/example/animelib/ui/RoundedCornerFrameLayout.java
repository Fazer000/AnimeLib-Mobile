package com.example.animelib.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Custom FrameLayout that clips all child views (including TextureView / SurfaceView in ExoPlayer)
 * with anti-aliased rounded corners using both RenderThread Outline clipping and canvas clipPath.
 */
public class RoundedCornerFrameLayout extends FrameLayout {

    private float cornerRadiusPx = 0f;
    private final Path clipPath = new Path();
    private final RectF rectF = new RectF();
    private ViewOutlineProvider outlineProvider;

    public RoundedCornerFrameLayout(@NonNull Context context) {
        super(context);
        init();
    }

    public RoundedCornerFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RoundedCornerFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            outlineProvider = new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    if (cornerRadiusPx > 0.5f) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx);
                    } else {
                        outline.setRect(0, 0, view.getWidth(), view.getHeight());
                    }
                }
            };
            setOutlineProvider(outlineProvider);
            setClipToOutline(true);
        }
    }

    public void setCornerRadius(float radiusPx) {
        float newRadius = Math.max(0f, radiusPx);
        if (Math.abs(this.cornerRadiusPx - newRadius) > 0.1f) {
            this.cornerRadiusPx = newRadius;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                invalidateOutline();
                applyOutlineToChildren(this);
            }
            invalidate();
        }
    }

    private void applyOutlineToChildren(ViewGroup viewGroup) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            child.setOutlineProvider(outlineProvider);
            child.setClipToOutline(cornerRadiusPx > 0.5f);
            child.invalidateOutline();
            if (child instanceof ViewGroup) {
                applyOutlineToChildren((ViewGroup) child);
            }
        }
    }

    public float getCornerRadius() {
        return cornerRadiusPx;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (cornerRadiusPx > 0.5f) {
            int saveCount = canvas.save();
            clipPath.reset();
            rectF.set(0, 0, getWidth(), getHeight());
            clipPath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW);
            canvas.clipPath(clipPath);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(saveCount);
        } else {
            super.dispatchDraw(canvas);
        }
    }
}
