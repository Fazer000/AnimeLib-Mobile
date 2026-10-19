package com.example.animelib.adapters;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.FranchiseResponse;
import com.example.animelib.util.ImageLoader;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Адаптер для отображения карточек хронологии аниме франшизы
 */
public class FranchiseChronologyAdapter extends RecyclerView.Adapter<FranchiseChronologyAdapter.ChronologyViewHolder> {

    public interface OnFranchiseItemClickListener {
        void onFranchiseItemClick(FranchiseResponse.NodeItem node);
    }

    private List<FranchiseResponse.NodeItem> nodes = new ArrayList<>();
    private Map<Long, String> nodeRelations = new HashMap<>();
    private long currentAnimeId = -1;
    private final OnFranchiseItemClickListener listener;
    private final boolean isFullListMode; // false = horizontal carousel, true = full vertical list in bottom sheet

    public FranchiseChronologyAdapter(OnFranchiseItemClickListener listener, boolean isFullListMode) {
        this.listener = listener;
        this.isFullListMode = isFullListMode;
    }

    public void updateData(List<FranchiseResponse.NodeItem> newNodes, Map<Long, String> relations, long currentId) {
        this.nodes = newNodes != null ? new ArrayList<>(newNodes) : new ArrayList<>();
        this.nodeRelations = relations != null ? new HashMap<>(relations) : new HashMap<>();
        this.currentAnimeId = currentId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ChronologyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_franchise_chronology, parent, false);

        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            if (isFullListMode) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else {
                float density = parent.getContext().getResources().getDisplayMetrics().density;
                lp.width = (int) (280 * density);
                lp.height = (int) (110 * density);
            }
            view.setLayoutParams(lp);
        }
        return new ChronologyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChronologyViewHolder holder, int position) {
        FranchiseResponse.NodeItem node = nodes.get(position);
        Context context = holder.itemView.getContext();

        // 1. Порядковый номер в хронологии
        holder.tvStepNumber.setText("#" + (position + 1));

        // 2. Название
        holder.tvTitle.setText(node.getName());

        // 3. Формат/Тип и Год
        holder.tvKind.setText(node.getFormattedKind());
        String yearStr = node.getYear() > 0 ? String.valueOf(node.getYear()) : "—";
        holder.tvYear.setText(yearStr);

        // 4. Связь / Отношение
        String relation = nodeRelations.get(node.getId());
        if (TextUtils.isEmpty(relation)) {
            if (position == 0) {
                relation = "Оригинал / Начало";
            } else {
                relation = node.getFormattedKind();
            }
        }
        holder.tvRelation.setText(relation);

        // 5. Обложка
        String coverUrl = node.getFullImageUrl();
        if (!TextUtils.isEmpty(coverUrl)) {
            ImageLoader.getInstance().loadInto(holder.ivCover, coverUrl, R.drawable.ic_image_placeholder);
        } else {
            holder.ivCover.setImageResource(R.drawable.ic_image_placeholder);
        }

        // 6. Выделение текущего просматриваемого аниме
        boolean isCurrent = (node.getId() == currentAnimeId);
        if (isCurrent) {
            holder.tvCurrentBadge.setVisibility(View.VISIBLE);
            holder.card.setStrokeColor(ContextCompat.getColor(context, R.color.purple_primary));
            holder.card.setStrokeWidth(dpToPx(context, 2f));
        } else {
            holder.tvCurrentBadge.setVisibility(View.GONE);
            holder.card.setStrokeWidth(0);
        }

        // Клик по карточке
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFranchiseItemClick(node);
            }
        });
    }

    @Override
    public int getItemCount() {
        return nodes.size();
    }

    private int dpToPx(Context context, float dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static class ChronologyViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView card;
        ImageView ivCover;
        TextView tvStepNumber;
        TextView tvCurrentBadge;
        TextView tvRelation;
        TextView tvTitle;
        TextView tvKind;
        TextView tvYear;

        ChronologyViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.cardFranchiseItem);
            ivCover = itemView.findViewById(R.id.ivFranchiseCover);
            tvStepNumber = itemView.findViewById(R.id.tvFranchiseStepNumber);
            tvCurrentBadge = itemView.findViewById(R.id.tvFranchiseCurrentBadge);
            tvRelation = itemView.findViewById(R.id.tvFranchiseRelation);
            tvTitle = itemView.findViewById(R.id.tvFranchiseTitle);
            tvKind = itemView.findViewById(R.id.tvFranchiseKind);
            tvYear = itemView.findViewById(R.id.tvFranchiseYear);
        }
    }
}
