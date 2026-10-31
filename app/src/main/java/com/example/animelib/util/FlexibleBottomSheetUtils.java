package com.example.animelib.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.animelib.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public class FlexibleBottomSheetUtils {

    /**
     * Возвращает строгий короткий бейдж разрешения ("4K", "2K", "FHD", "HD", "SD") или "",
     * никогда не возвращая "AUTO" или "СКАЧАНО".
     */
    public static String getStrictResolutionBadge(String quality) {
        if (quality == null || quality.trim().isEmpty() || AutoQualityHelper.isAutoQuality(quality)) {
            return "";
        }
        int res = AutoQualityHelper.extractResolution(quality);
        if (res >= 2160) return "4K";
        if (res >= 1440) return "2K";
        if (res >= 1080) return "FHD";
        if (res >= 720) return "HD";
        if (res > 0) return "SD";
        return "";
    }

    /**
     * Возвращает тег качества ("4K", "2K", "FHD", "HD", "SD") по строковому значению ("2160p", "1080p", "720p", "480p", etc.)
     */
    public static String getQualityTag(String quality) {
        if (quality == null || quality.trim().isEmpty()) return "";
        String strict = getStrictResolutionBadge(quality);
        if (!strict.isEmpty()) {
            return strict;
        }
        String q = quality.toLowerCase().trim();
        if (q.contains("auto") || q.contains("авто")) {
            return "AUTO";
        } else if (q.contains("загруженное") || q.contains("скачанное") || q.contains("скачанный") || q.contains("локальное") || q.contains("офлайн")) {
            return "СКАЧАНО";
        }
        return q.replace("p", "").toUpperCase();
    }

    /**
     * Настраивает BottomSheetDialog как flexible парящую панель
     */
    public static void setupFlexibleStyle(@Nullable BottomSheetDialog dialog) {
        setupFlexibleStyle(dialog, false, false, R.drawable.bs_background);
    }

    public static void setupFlexibleStyle(@Nullable BottomSheetDialog dialog, boolean fullWidthWithMargins, int backgroundResId) {
        setupFlexibleStyle(dialog, fullWidthWithMargins, false, backgroundResId);
    }

    public static void setupFlexibleStyle(@Nullable BottomSheetDialog dialog, boolean fullWidthWithMargins, boolean noSideMargins, int backgroundResId) {
        if (dialog == null) return;

        dialog.setDismissWithAnimation(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
        }

        dialog.setOnShowListener(d -> applyFlexibleToView(dialog, fullWidthWithMargins, noSideMargins, backgroundResId));
    }

    public static void applyFlexibleToView(@Nullable BottomSheetDialog dialog) {
        applyFlexibleToView(dialog, false, false, R.drawable.bs_background);
    }

    public static void applyFlexibleToView(@Nullable BottomSheetDialog dialog, boolean fullWidthWithMargins, boolean noSideMargins, int backgroundResId) {
        if (dialog == null) return;

        FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet == null) return;

        Context context = bottomSheet.getContext();
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();

        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;

        int bgDrawableRes = (backgroundResId != 0) ? backgroundResId : R.drawable.bs_background;

        int marginHorizontal = noSideMargins ? 0 : (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14, metrics);
        int marginVertical = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, metrics);

        bottomSheet.setBackgroundColor(Color.TRANSPARENT);
        float elevationPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, metrics);
        ViewCompat.setElevation(bottomSheet, elevationPx);

        if (bottomSheet.getChildCount() > 0) {
            View childView = bottomSheet.getChildAt(0);
            ViewCompat.setElevation(childView, elevationPx);
            Drawable background = ContextCompat.getDrawable(context, bgDrawableRes);
            if (background != null) {
                childView.setBackground(background);
            }
            childView.setClipToOutline(true);

            ViewGroup.MarginLayoutParams childLp = (ViewGroup.MarginLayoutParams) childView.getLayoutParams();
            if (childLp != null) {
                childLp.topMargin = marginVertical;
                childLp.bottomMargin = marginVertical;
                childLp.leftMargin = 0;
                childLp.rightMargin = 0;
                childView.setLayoutParams(childLp);
            }
        } else {
            Drawable background = ContextCompat.getDrawable(context, bgDrawableRes);
            if (background != null) {
                bottomSheet.setBackground(background);
            }
            bottomSheet.setClipToOutline(true);
        }

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bottomSheet.getLayoutParams();
        if (lp != null) {
            if (noSideMargins) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.leftMargin = 0;
                lp.rightMargin = 0;
            } else if (fullWidthWithMargins) {
                int availableWidth = screenWidth - (marginHorizontal * 2);
                lp.width = availableWidth;
                lp.leftMargin = marginHorizontal;
                lp.rightMargin = marginHorizontal;
            } else {
                int maxAllowedWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 418, metrics);
                int availableWidth = screenWidth - (marginHorizontal * 2);
                if (availableWidth > maxAllowedWidth) {
                    lp.width = maxAllowedWidth;
                    int extraMargin = (screenWidth - maxAllowedWidth) / 2;
                    lp.leftMargin = extraMargin;
                    lp.rightMargin = extraMargin;
                } else {
                    lp.width = availableWidth;
                    lp.leftMargin = marginHorizontal;
                    lp.rightMargin = marginHorizontal;
                }
            }
            lp.topMargin = 0;
            lp.bottomMargin = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;

            if (lp instanceof CoordinatorLayout.LayoutParams) {
                ((CoordinatorLayout.LayoutParams) lp).gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            }

            bottomSheet.setLayoutParams(lp);
        }

        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
        behavior.setFitToContents(true);
        behavior.setHideable(true);
        behavior.setSkipCollapsed(true);
        behavior.setExpandedOffset(0);
        behavior.setGestureInsetBottomIgnored(true);

        int maxHeight = (int) (screenHeight * 0.88f);
        behavior.setMaxHeight(maxHeight);

        behavior.setPeekHeight(screenHeight * 2);

        bottomSheet.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (dialog.isShowing()) {
                if (behavior.getState() != BottomSheetBehavior.STATE_EXPANDED && behavior.getState() != BottomSheetBehavior.STATE_HIDDEN) {
                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                }
            }
        });

        if (bottomSheet.getTag() == null) {
            bottomSheet.setTag(true);
            behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(@NonNull View bottomSheetView, int newState) {
                    if (newState == BottomSheetBehavior.STATE_COLLAPSED || newState == BottomSheetBehavior.STATE_HALF_EXPANDED) {
                        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                    } else if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                        if (dialog.isShowing()) {
                            try {
                                dialog.dismiss();
                            } catch (Exception ignored) {}
                        }
                    }
                }

                @Override
                public void onSlide(@NonNull View bottomSheetView, float slideOffset) {
                }
            });
        }

        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

        ViewCompat.setOnApplyWindowInsetsListener(bottomSheet, (v, insets) -> WindowInsetsCompat.CONSUMED);

        View container = dialog.findViewById(com.google.android.material.R.id.container);
        if (container instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) container;
            ViewCompat.setOnApplyWindowInsetsListener(vg, (v, insets) -> WindowInsetsCompat.CONSUMED);
            vg.setClipChildren(false);
            vg.setClipToPadding(false);
        }

        View touchOutside = dialog.findViewById(com.google.android.material.R.id.touch_outside);
        if (touchOutside instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) touchOutside;
            ViewCompat.setOnApplyWindowInsetsListener(vg, (v, insets) -> WindowInsetsCompat.CONSUMED);
            vg.setClipChildren(false);
            vg.setClipToPadding(false);
        }

        if (bottomSheet.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) bottomSheet.getParent();
            ViewCompat.setOnApplyWindowInsetsListener(parent, (v, insets) -> WindowInsetsCompat.CONSUMED);
            parent.setClipChildren(false);
            parent.setClipToPadding(false);
        }
    }
}
