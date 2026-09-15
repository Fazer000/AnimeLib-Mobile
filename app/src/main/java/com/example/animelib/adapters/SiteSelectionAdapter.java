package com.example.animelib.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.SiteOption;
import com.example.animelib.util.ItemAnimationUtils;

import java.util.List;

public class SiteSelectionAdapter extends RecyclerView.Adapter<SiteSelectionAdapter.SiteViewHolder> {

    public interface OnSiteClickListener {
        void onSiteClick(SiteOption site);
    }

    private final List<SiteOption> sites;
    private final OnSiteClickListener listener;

    public SiteSelectionAdapter(List<SiteOption> sites, OnSiteClickListener listener) {
        this.sites = sites;
        this.listener = listener;
    }

    @NonNull
    @Override
    public SiteViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_site_option, parent, false);
        return new SiteViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SiteViewHolder holder, int position) {
        if (sites == null || position >= sites.size()) return;
        SiteOption site = sites.get(position);

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

        if (holder.siteNameText != null) {
            holder.siteNameText.setText(site.getName());
        }

        if (holder.siteLogoIcon != null) {
            if (site.getIconResId() != 0) {
                holder.siteLogoIcon.setImageResource(site.getIconResId());
                holder.siteLogoIcon.setVisibility(View.VISIBLE);
            } else {
                holder.siteLogoIcon.setVisibility(View.GONE);
            }
        }

        View.OnClickListener clickListener = v -> {
            ItemAnimationUtils.animateItemClick(v, () -> {
                if (listener != null) {
                    listener.onSiteClick(site);
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
        return sites != null ? sites.size() : 0;
    }

    public static class SiteViewHolder extends RecyclerView.ViewHolder {
        View itemContainer;
        ImageView siteLogoIcon;
        TextView siteNameText;

        SiteViewHolder(@NonNull View itemView) {
            super(itemView);
            itemContainer = itemView.findViewById(R.id.itemContainer);
            siteLogoIcon = itemView.findViewById(R.id.siteLogoIcon);
            siteNameText = itemView.findViewById(R.id.siteNameText);
        }
    }
}
