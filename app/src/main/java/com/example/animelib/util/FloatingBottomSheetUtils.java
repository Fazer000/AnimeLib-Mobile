package com.example.animelib.util;

import com.google.android.material.bottomsheet.BottomSheetDialog;

public class FloatingBottomSheetUtils {

    public static String getQualityTag(String quality) {
        return FlexibleBottomSheetUtils.getQualityTag(quality);
    }

    public static String getStrictResolutionBadge(String quality) {
        return FlexibleBottomSheetUtils.getStrictResolutionBadge(quality);
    }

    public static void setupFloatingStyle(BottomSheetDialog dialog) {
        FlexibleBottomSheetUtils.setupFlexibleStyle(dialog);
    }

    public static void setupFloatingStyle(BottomSheetDialog dialog, boolean fullWidthWithMargins, int backgroundResId) {
        FlexibleBottomSheetUtils.setupFlexibleStyle(dialog, fullWidthWithMargins, backgroundResId);
    }

    public static void setupFloatingStyle(BottomSheetDialog dialog, boolean fullWidthWithMargins, boolean noSideMargins, int backgroundResId) {
        FlexibleBottomSheetUtils.setupFlexibleStyle(dialog, fullWidthWithMargins, noSideMargins, backgroundResId);
    }

    public static void applyFloatingToView(BottomSheetDialog dialog) {
        FlexibleBottomSheetUtils.applyFlexibleToView(dialog);
    }

    public static void applyFloatingToView(BottomSheetDialog dialog, boolean fullWidthWithMargins, boolean noSideMargins, int backgroundResId) {
        FlexibleBottomSheetUtils.applyFlexibleToView(dialog, fullWidthWithMargins, noSideMargins, backgroundResId);
    }
}
