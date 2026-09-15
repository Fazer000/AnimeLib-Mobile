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
import com.example.animelib.util.ItemAnimationUtils;

import java.util.List;

public class CustomSelectAdapter extends RecyclerView.Adapter<CustomSelectAdapter.OptionViewHolder> {

    public interface OnOptionClickListener {
        void onOptionClick(String value, String text);
    }

    private final List<String> options;
    private final List<String> values;
    private final String currentValue;
    private final OnOptionClickListener listener;

    public CustomSelectAdapter(List<String> options, List<String> values, String currentValue, OnOptionClickListener listener) {
        this.options = options;
        this.values = values;
        this.currentValue = currentValue;
        this.listener = listener;
    }

    @NonNull
    @Override
    public OptionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_custom_select_option, parent, false);
        return new OptionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull OptionViewHolder holder, int position) {
        String text = options != null && position < options.size() ? options.get(position) : "";
        String value = values != null && position < values.size() ? values.get(position) : text;
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

        if (holder.optionText != null) {
            holder.optionText.setText(text);
        }

        boolean isCurrent = (value != null && value.equalsIgnoreCase(currentValue))
                || (text != null && text.equalsIgnoreCase(currentValue));

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

        if (holder.optionText != null) {
            holder.optionText.setTextColor(isCurrent ? secondaryColorVal.data : primaryColorVal.data);
        }

        holder.itemView.setSelected(isCurrent);

        View.OnClickListener clickListener = v -> {
            ItemAnimationUtils.animateItemClick(v, () -> {
                if (listener != null) {
                    listener.onOptionClick(value, text);
                }
            });
        };
        holder.itemView.setOnClickListener(clickListener);
        if (holder.itemContainer != null) {
            holder.itemContainer.setOnClickListener(clickListener);
        }
    }

    @Override
    public int getItemCount() {
        return options != null ? options.size() : 0;
    }

    public static class OptionViewHolder extends RecyclerView.ViewHolder {
        View itemContainer;
        TextView optionText;
        View selectedPill;
        View unselectedIndicator;

        OptionViewHolder(@NonNull View itemView) {
            super(itemView);
            itemContainer = itemView.findViewById(R.id.itemContainer);
            optionText = itemView.findViewById(R.id.optionText);
            selectedPill = itemView.findViewById(R.id.selectedPill);
            unselectedIndicator = itemView.findViewById(R.id.unselectedIndicator);
        }
    }
}
