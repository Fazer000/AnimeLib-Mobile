package com.example.animelib.util;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.R;

public class SkeletonHelper {

    /**
     * Custom shimmer drawable for text skeletons
     */
    public static class SkeletonShimmerDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectF = new RectF();
        private final float cornerRadius;
        private ValueAnimator animator;
        private float gradientTranslate = 0f;
        private final int baseColor;
        private final int shimmerColor;

        public SkeletonShimmerDrawable(float cornerRadiusDp, boolean isDarkTheme) {
            float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
            this.cornerRadius = cornerRadiusDp * density;

            if (isDarkTheme) {
                baseColor = 0x22FFFFFF; // ~13% white
                shimmerColor = 0x55FFFFFF; // ~33% white
            } else {
                baseColor = 0x1A000000; // ~10% black
                shimmerColor = 0x3D000000; // ~24% black
            }
            startAnimation();
        }

        private void startAnimation() {
            animator = ValueAnimator.ofFloat(-1.2f, 2.2f);
            animator.setDuration(1200);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                gradientTranslate = (float) animation.getAnimatedValue();
                invalidateSelf();
            });
            animator.start();
        }

        public void stopAnimation() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            rectF.set(bounds);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            float width = rectF.width();
            if (width <= 0) return;

            float shimmerWidth = width * 0.9f;
            float left = gradientTranslate * width;

            LinearGradient shader = new LinearGradient(
                    left, 0, left + shimmerWidth, 0,
                    new int[]{baseColor, shimmerColor, baseColor},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            );

            paint.setShader(shader);
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Показывает скелетон-анимацию на TextView или любом другом View (ImageView, etc.)
     */
    public static void showSkeleton(android.view.View view) {
        showSkeleton(view, 100, 8f);
    }

    public static void showSkeleton(android.view.View view, int minWidthDp) {
        showSkeleton(view, minWidthDp, 8f);
    }

    public static void showSkeleton(android.view.View view, int minWidthDp, float cornerRadiusDp) {
        if (view == null) return;

        // Если скелетон уже запущен, пропускаем
        if (Boolean.TRUE.equals(view.getTag(R.id.tag_skeleton_active))) {
            return;
        }

        boolean isDark = CustomToast.isDarkTheme(view.getContext());

        view.setTag(R.id.tag_skeleton_bg, view.getBackground());
        view.setTag(R.id.tag_skeleton_active, true);

        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            tv.setTag(R.id.tag_skeleton_color, tv.getCurrentTextColor());
            tv.setTextColor(android.graphics.Color.TRANSPARENT);

            CharSequence currentText = tv.getText();
            if (currentText == null || currentText.length() == 0) {
                tv.setText("                      ");
            }
        } else if (view instanceof android.widget.ImageView) {
            android.widget.ImageView iv = (android.widget.ImageView) view;
            iv.setTag(R.id.tag_skeleton_image_drawable, iv.getDrawable());
            iv.setImageDrawable(null);
        }

        float density = view.getResources().getDisplayMetrics().density;
        int minPx = Math.round(minWidthDp * density);
        if (view.getWidth() < minPx && minWidthDp > 0) {
            view.setMinimumWidth(minPx);
        }

        SkeletonShimmerDrawable drawable = new SkeletonShimmerDrawable(cornerRadiusDp, isDark);
        view.setBackground(drawable);
    }

    public static void showSkeletons(android.view.View... views) {
        if (views == null) return;
        for (android.view.View v : views) {
            showSkeleton(v);
        }
    }

    /**
     * Скрывает скелетон и восстанавливает исходное состояние View
     */
    public static void hideSkeleton(android.view.View view, CharSequence actualText) {
        if (view == null) return;

        if (!Boolean.TRUE.equals(view.getTag(R.id.tag_skeleton_active))) {
            if (view instanceof TextView && actualText != null) {
                ((TextView) view).setText(actualText);
            }
            return;
        }

        Drawable bg = view.getBackground();
        if (bg instanceof SkeletonShimmerDrawable) {
            ((SkeletonShimmerDrawable) bg).stopAnimation();
        }

        // Восстанавливаем фон
        Object origBg = view.getTag(R.id.tag_skeleton_bg);
        if (origBg instanceof Drawable) {
            view.setBackground((Drawable) origBg);
        } else {
            view.setBackground(null);
        }

        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            Object origColor = tv.getTag(R.id.tag_skeleton_color);
            if (origColor instanceof Integer) {
                tv.setTextColor((Integer) origColor);
            } else {
                tv.setTextColor(0xFFFFFFFF);
            }
            tv.setText(actualText != null ? actualText : "");
        } else if (view instanceof android.widget.ImageView) {
            android.widget.ImageView iv = (android.widget.ImageView) view;
            Object origImg = iv.getTag(R.id.tag_skeleton_image_drawable);
            if (origImg instanceof Drawable) {
                iv.setImageDrawable((Drawable) origImg);
            }
        }

        // Сбрасываем минимальную ширину и активный флаг
        view.setMinimumWidth(0);
        view.setTag(R.id.tag_skeleton_active, false);

        // Плавно подставляем текст или рисунок с короткой анимацией альфа
        view.setAlpha(0.2f);
        view.animate()
                .alpha(1.0f)
                .setDuration(220)
                .start();
    }
}
