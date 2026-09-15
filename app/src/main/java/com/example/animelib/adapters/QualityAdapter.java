package com.example.animelib.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;

import java.util.List;

public class QualityAdapter extends RecyclerView.Adapter<QualityAdapter.QualityViewHolder> {

    public interface OnQualitySelectedListener {
        void onQualitySelected(String quality);
    }

    private final List<String> qualities;
    private String currentQuality;
    private final OnQualitySelectedListener listener;

    public QualityAdapter(List<String> qualities, String currentQuality, OnQualitySelectedListener listener) {
        this.qualities = qualities;
        this.currentQuality = currentQuality;
        this.listener = listener;
    }

    @NonNull
    @Override
    public QualityViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_quality_option, parent, false);
        return new QualityViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull QualityViewHolder holder, int position) {
        String quality = qualities.get(position);
        Context context = holder.itemView.getContext();

        // 1. Grouped M3 background shape
        int itemCount = getItemCount();
        if (holder.itemContainer != null) {
            if (itemCount == 1) {
                holder.itemContainer.setBackgroundResource(R.drawable.bg_m3_item_single);
            } else if (position == 0) {
                holder.itemContainer.setBackgroundResource(R.drawable.bg_m3_item_top);
            } else if (position == itemCount - 1) {
                holder.itemContainer.setBackgroundResource(R.drawable.bg_m3_item_bottom);
            } else {
                holder.itemContainer.setBackgroundResource(R.drawable.bg_m3_item_middle);
            }
        }

        // 2. Title & Tag
        if (holder.qualityText != null) {
            holder.qualityText.setText(quality);
        }

        String tag = com.example.animelib.util.FloatingBottomSheetUtils.getQualityTag(quality);
        if (holder.qualityTagText != null) {
            if (tag != null && !tag.isEmpty()) {
                holder.qualityTagText.setText(tag);
                holder.qualityTagText.setVisibility(View.VISIBLE);
            } else {
                holder.qualityTagText.setVisibility(View.GONE);
            }
        }

        // 3. Subtitle description
        if (holder.qualitySubtitleText != null) {
            holder.qualitySubtitleText.setText(getQualitySubtitle(quality));
        }

        // 4. Active / Inactive selection state
        boolean isCurrent = (position == getSelectedIndex());

        if (holder.selectedPill != null) {
            holder.selectedPill.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
        }
        if (holder.unselectedIndicator != null) {
            holder.unselectedIndicator.setVisibility(isCurrent ? View.GONE : View.VISIBLE);
        }

        TypedValue primaryColorVal = new TypedValue();
        TypedValue secondaryColorVal = new TypedValue();
        context.getTheme().resolveAttribute(R.attr.primaryTextColor, primaryColorVal, true);
        context.getTheme().resolveAttribute(R.attr.secondaryColor, secondaryColorVal, true);

        if (holder.qualityText != null) {
            holder.qualityText.setTextColor(isCurrent ? secondaryColorVal.data : primaryColorVal.data);
        }

        holder.itemView.setSelected(isCurrent);

        View.OnClickListener clickListener = v -> {
            com.example.animelib.util.ItemAnimationUtils.animateItemClick(v, () -> {
                if (listener != null) {
                    listener.onQualitySelected(quality);
                }
            });
        };
        holder.itemView.setOnClickListener(clickListener);
        if (holder.itemContainer != null) {
            holder.itemContainer.setOnClickListener(clickListener);
        }
    }

    private static String getQualitySubtitle(String quality) {
        if (quality == null) return "Видеопоток";
        String q = quality.toLowerCase().trim();
        if (q.contains("auto") || q.contains("авто")) {
            return "Автоматический выбор качества";
        } else if (q.contains("скачан") || q.contains("офлайн") || q.contains("локальн")) {
            return "Загруженный медиафайл";
        }

        int res = com.example.animelib.util.AutoQualityHelper.extractResolution(quality);
        if (res >= 2160 || q.contains("4k")) {
            return "Максимальная четкость";
        } else if (res >= 1440 || q.contains("2k")) {
            return "Высокая детализация";
        } else if (res >= 1080 || q.contains("fhd")) {
            return "Высокое качество";
        } else if (res >= 720 || q.contains("hd")) {
            return "Оптимально для большинства";
        } else if (res >= 480) {
            return "Хороший баланс";
        } else if (res > 0) {
            return "Экономия трафика";
        }
        return "Стандартный видеопоток";
    }

    @Override
    public int getItemCount() {
        return qualities != null ? qualities.size() : 0;
    }

    public int getSelectedIndex() {
        if (qualities == null || qualities.isEmpty()) return -1;

        // 1. Exact or smart quality match
        for (int i = 0; i < qualities.size(); i++) {
            if (com.example.animelib.util.AutoQualityHelper.matchQuality(qualities.get(i), currentQuality)) {
                return i;
            }
        }

        // 2. Fallback if currentQuality is auto-like or null/empty
        if (com.example.animelib.util.AutoQualityHelper.isAutoQuality(currentQuality)) {
            for (int i = 0; i < qualities.size(); i++) {
                if (com.example.animelib.util.AutoQualityHelper.isAutoQuality(qualities.get(i))) {
                    return i;
                }
            }
        }

        // 3. Fallback if currentQuality is downloaded-like
        if (com.example.animelib.util.AutoQualityHelper.isDownloadedQuality(currentQuality)) {
            for (int i = 0; i < qualities.size(); i++) {
                if (com.example.animelib.util.AutoQualityHelper.isDownloadedQuality(qualities.get(i))) {
                    return i;
                }
            }
        }

        // 4. Fallback to first item if available
        return 0;
    }
    
    @SuppressLint("NotifyDataSetChanged")
    public void updateCurrentQuality(String newCurrentQuality) {
        this.currentQuality = newCurrentQuality;
        notifyDataSetChanged();
    }
    
    @SuppressLint("NotifyDataSetChanged")
    public void updateQualities(List<String> newQualities, String newCurrentQuality) {
        if (this.qualities != null) {
            this.qualities.clear();
            if (newQualities != null) {
                this.qualities.addAll(newQualities);
            }
        }
        this.currentQuality = newCurrentQuality;
        notifyDataSetChanged();
    }

    public static class QualityViewHolder extends RecyclerView.ViewHolder {
        View itemContainer;
        TextView qualityText;
        TextView qualityTagText;
        TextView qualitySubtitleText;
        View selectedPill;
        View unselectedIndicator;

        QualityViewHolder(@NonNull View itemView) {
            super(itemView);
            itemContainer = itemView.findViewById(R.id.itemContainer);
            qualityText = itemView.findViewById(R.id.qualityText);
            qualityTagText = itemView.findViewById(R.id.qualityTagText);
            qualitySubtitleText = itemView.findViewById(R.id.qualitySubtitleText);
            selectedPill = itemView.findViewById(R.id.selectedPill);
            unselectedIndicator = itemView.findViewById(R.id.unselectedIndicator);
        }
    }
}
