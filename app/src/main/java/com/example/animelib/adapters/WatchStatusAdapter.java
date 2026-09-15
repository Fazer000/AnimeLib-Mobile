package com.example.animelib.adapters;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.WatchStatusItem;
import com.example.animelib.util.ItemAnimationUtils;

import java.util.List;

public class WatchStatusAdapter extends RecyclerView.Adapter<WatchStatusAdapter.ViewHolder> {

    public interface OnStatusClickListener {
        void onStatusClick(WatchStatusItem item);
    }

    private final List<WatchStatusItem> items;
    private final Object currentStatusId;
    private final OnStatusClickListener listener;

    public WatchStatusAdapter(List<WatchStatusItem> items, Object currentStatusId, OnStatusClickListener listener) {
        this.items = items;
        this.currentStatusId = currentStatusId;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_watch_status, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        WatchStatusItem item = items.get(position);
        Context context = holder.itemView.getContext();
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

        if (holder.tvStatusLabel != null) {
            holder.tvStatusLabel.setText(item.getLabel());
        }

        boolean isSelected = isSameStatus(currentStatusId, item.getId());

        if (holder.selectedPill != null) {
            holder.selectedPill.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        }
        if (holder.unselectedIndicator != null) {
            holder.unselectedIndicator.setVisibility(isSelected ? View.GONE : View.VISIBLE);
        }

        TypedValue primaryColorVal = new TypedValue();
        TypedValue secondaryColorVal = new TypedValue();
        context.getTheme().resolveAttribute(R.attr.primaryTextColor, primaryColorVal, true);
        context.getTheme().resolveAttribute(R.attr.secondaryColor, secondaryColorVal, true);

        if (holder.tvStatusLabel != null) {
            holder.tvStatusLabel.setTextColor(isSelected ? secondaryColorVal.data : primaryColorVal.data);
        }

        holder.itemView.setSelected(isSelected);

        View.OnClickListener clickListener = v -> {
            ItemAnimationUtils.animateItemClick(v, () -> {
                if (listener != null) {
                    listener.onStatusClick(item);
                }
            });
        };
        holder.itemView.setOnClickListener(clickListener);
        if (holder.itemContainer != null) {
            holder.itemContainer.setOnClickListener(clickListener);
        }
    }

    private boolean isSameStatus(Object id1, Object id2) {
        if (id1 == null || id2 == null) return false;
        if (id1.equals(id2)) return true;
        if (id1 instanceof Number && id2 instanceof Number) {
            return ((Number) id1).intValue() == ((Number) id2).intValue();
        }
        if (id1 instanceof String && id2 instanceof String) {
            return ((String) id1).equalsIgnoreCase((String) id2);
        }
        return false;
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View itemContainer;
        TextView tvStatusLabel;
        View selectedPill;
        View unselectedIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            itemContainer = itemView.findViewById(R.id.itemContainer);
            tvStatusLabel = itemView.findViewById(R.id.tvStatusLabel);
            selectedPill = itemView.findViewById(R.id.selectedPill);
            unselectedIndicator = itemView.findViewById(R.id.unselectedIndicator);
        }
    }
}
