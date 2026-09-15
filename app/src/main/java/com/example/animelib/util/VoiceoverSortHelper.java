package com.example.animelib.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.animelib.R;
import com.example.animelib.adapters.PlayerTabsAdapter;

public class VoiceoverSortHelper {

    public static String getSortLabel(String sortType) {
        if (PlayerTabsAdapter.SORT_NAME_DESC.equals(sortType)) {
            return "По названию (Я-А)";
        } else if (PlayerTabsAdapter.SORT_QUALITY_DESC.equals(sortType)) {
            return "По качеству";
        } else if (PlayerTabsAdapter.SORT_SUBTITLES_FIRST.equals(sortType)) {
            return "С субтитрами";
        } else {
            return "По названию (А-Я)";
        }
    }

    public static void showSortPopup(View anchorView, Context context, PlayerTabsAdapter adapter) {
        if (anchorView == null || context == null || adapter == null) return;
        try {
            View popupView = LayoutInflater.from(context).inflate(R.layout.popup_voiceovers_sort, null);

            float density = context.getResources().getDisplayMetrics().density;
            int widthPx = (int) (210 * density);
            PopupWindow popupWindow = new PopupWindow(
                    popupView,
                    widthPx,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
            );

            popupWindow.setOutsideTouchable(true);
            popupWindow.setFocusable(true);
            popupWindow.setElevation(12 * density);
            popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            TextView itemAsc = popupView.findViewById(R.id.item_sort_name_asc);
            TextView itemDesc = popupView.findViewById(R.id.item_sort_name_desc);
            TextView itemQuality = popupView.findViewById(R.id.item_sort_quality);
            TextView itemSubtitles = popupView.findViewById(R.id.item_sort_subtitles);

            resetPopupItemStyle(context, itemAsc);
            resetPopupItemStyle(context, itemDesc);
            resetPopupItemStyle(context, itemQuality);
            resetPopupItemStyle(context, itemSubtitles);

            String currentSort = adapter.getSortType();
            if (PlayerTabsAdapter.SORT_NAME_DESC.equals(currentSort)) {
                setPopupItemSelectedStyle(context, itemDesc);
            } else if (PlayerTabsAdapter.SORT_QUALITY_DESC.equals(currentSort)) {
                setPopupItemSelectedStyle(context, itemQuality);
            } else if (PlayerTabsAdapter.SORT_SUBTITLES_FIRST.equals(currentSort)) {
                setPopupItemSelectedStyle(context, itemSubtitles);
            } else {
                setPopupItemSelectedStyle(context, itemAsc);
            }

            TextView tvSortText = anchorView.findViewById(R.id.tvSortVoiceovers);

            itemAsc.setOnClickListener(v -> {
                try { popupWindow.dismiss(); } catch (Exception ignored) {}
                adapter.setSortType(PlayerTabsAdapter.SORT_NAME_ASC);
                if (tvSortText != null) tvSortText.setText(getSortLabel(PlayerTabsAdapter.SORT_NAME_ASC));
            });
            itemDesc.setOnClickListener(v -> {
                try { popupWindow.dismiss(); } catch (Exception ignored) {}
                adapter.setSortType(PlayerTabsAdapter.SORT_NAME_DESC);
                if (tvSortText != null) tvSortText.setText(getSortLabel(PlayerTabsAdapter.SORT_NAME_DESC));
            });
            itemQuality.setOnClickListener(v -> {
                try { popupWindow.dismiss(); } catch (Exception ignored) {}
                adapter.setSortType(PlayerTabsAdapter.SORT_QUALITY_DESC);
                if (tvSortText != null) tvSortText.setText(getSortLabel(PlayerTabsAdapter.SORT_QUALITY_DESC));
            });
            itemSubtitles.setOnClickListener(v -> {
                try { popupWindow.dismiss(); } catch (Exception ignored) {}
                adapter.setSortType(PlayerTabsAdapter.SORT_SUBTITLES_FIRST);
                if (tvSortText != null) tvSortText.setText(getSortLabel(PlayerTabsAdapter.SORT_SUBTITLES_FIRST));
            });

            // Calculate x offset so popup right edge is at least 14dp from the right screen boundary
            int[] location = new int[2];
            anchorView.getLocationOnScreen(location);
            int anchorX = location[0];
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int rightMarginPx = (int) (14 * density);

            int targetX = screenWidth - widthPx - rightMarginPx;
            int offsetX = targetX - anchorX;
            int offsetY = (int) (6 * density);

            popupWindow.showAsDropDown(anchorView, offsetX, offsetY);
        } catch (Exception e) {
            Log.e("VoiceoverSortHelper", "Error showing voiceovers sort popup", e);
        }
    }

    private static void resetPopupItemStyle(Context context, TextView textView) {
        if (textView == null) return;
        textView.setBackgroundResource(R.drawable.bg_popup_item_ripple);
        textView.setTextColor(ContextCompat.getColor(context, R.color.primary_text_color));
    }

    private static void setPopupItemSelectedStyle(Context context, TextView textView) {
        if (textView == null) return;
        textView.setBackgroundResource(R.drawable.bg_sort_popup_item_selected);
        textView.setTextColor(ContextCompat.getColor(context, R.color.sort_popup_item_selected_text));
    }
}
