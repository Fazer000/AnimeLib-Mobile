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
    private boolean hasCustomShadowBounds = false;
    private float shadowLeft = 0f;
    private float shadowTop = 0f;
    private float shadowRight = 0f;
    private float shadowBottom = 0f;
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
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w <= 0 || h <= 0) {
                        outline.setEmpty();
                        return;
                    }
                    if (cornerRadiusPx > 0.5f) {
                        outline.setRoundRect(0, 0, w, h, cornerRadiusPx);
                    } else if (view == RoundedCornerFrameLayout.this && hasCustomShadowBounds) {
                        int l = shadowLeft > 0.5f ? (int) Math.ceil(shadowLeft) : 0;
                        int t = shadowTop > 0.5f ? (int) Math.ceil(shadowTop) : 0;
                        int r = shadowRight < (w - 0.5f) ? (int) Math.floor(shadowRight) : w;
                        int b = shadowBottom < (h - 0.5f) ? (int) Math.floor(shadowBottom) : h;
                        l = Math.max(0, Math.min(w, l));
                        t = Math.max(0, Math.min(h, t));
                        r = Math.max(l, Math.min(w, r));
                        b = Math.max(t, Math.min(h, b));
                        if (r > l && b > t) {
                            outline.setRect(l, t, r, b);
                            outline.setAlpha(1f);
                        } else {
                            outline.setRect(0, 0, w, h);
                        }
                    } else {
                        outline.setRect(0, 0, w, h);
                    }
                }
            };
            setOutlineProvider(outlineProvider);
            setClipToOutline(true);
        }
    }

    public void setVideoShadowBounds(float left, float top, float right, float bottom) {
        if (right <= left || bottom <= top) {
            clearVideoShadowBounds();
            return;
        }
        if (!hasCustomShadowBounds
                || Math.abs(shadowLeft - left) >= 0.5f
                || Math.abs(shadowTop - top) >= 0.5f
                || Math.abs(shadowRight - right) >= 0.5f
                || Math.abs(shadowBottom - bottom) >= 0.5f) {
            hasCustomShadowBounds = true;
            shadowLeft = left;
            shadowTop = top;
            shadowRight = right;
            shadowBottom = bottom;
            updateClipAndOutline();
        }
    }

    public void clearVideoShadowBounds() {
        if (hasCustomShadowBounds) {
            hasCustomShadowBounds = false;
            shadowLeft = 0f;
            shadowTop = 0f;
            shadowRight = 0f;
            shadowBottom = 0f;
            updateClipAndOutline();
        }
    }

    private boolean isShadowInsetFromContainer(int width, int height) {
        if (!hasCustomShadowBounds || width <= 0 || height <= 0) {
            return false;
        }
        return shadowLeft > 0.5f
                || shadowTop > 0.5f
                || shadowRight < (width - 0.5f)
                || shadowBottom < (height - 0.5f);
    }

    private void updateClipAndOutline() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            boolean shouldClip = cornerRadiusPx > 0.5f
                    || !isShadowInsetFromContainer(getWidth(), getHeight());
            if (getClipToOutline() != shouldClip) {
                setClipToOutline(shouldClip);
            }
            invalidateOutline();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateClipAndOutline();
    }

    public void setCornerRadius(float radiusPx) {
        float newRadius = Math.max(0f, radiusPx);
        if (Math.abs(this.cornerRadiusPx - newRadius) > 0.1f) {
            this.cornerRadiusPx = newRadius;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                updateClipAndOutline();
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
