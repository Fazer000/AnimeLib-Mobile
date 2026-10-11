package com.example.animelib.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;

/**
 * Адаптер горизонтальной карусели квадратных кнопок номеров страниц
 */
public class CommentPagesAdapter extends RecyclerView.Adapter<CommentPagesAdapter.PageViewHolder> {

    public interface OnPageClickListener {
        void onPageClick(int page);
    }

    private int currentPage = 1;
    private int totalPages = 1;
    private final OnPageClickListener listener;

    public CommentPagesAdapter(OnPageClickListener listener) {
        this.listener = listener;
    }

    public void setPages(int currentPage, int totalPages) {
        this.currentPage = Math.max(1, currentPage);
        this.totalPages = Math.max(this.currentPage, totalPages);
        notifyDataSetChanged();
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getTotalPages() {
        return totalPages;
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_comment_page, parent, false);
        return new PageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        int page = position + 1;
        holder.bind(page, page == currentPage, listener);
    }

    @Override
    public int getItemCount() {
        return totalPages;
    }

    static class PageViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvPageNumber;
        private final View container;

        public PageViewHolder(@NonNull View itemView) {
            super(itemView);
            container = itemView;
            tvPageNumber = itemView.findViewById(R.id.tvPageNumber);
        }

        public void bind(int page, boolean isSelected, OnPageClickListener listener) {
            tvPageNumber.setText(String.valueOf(page));
            Context context = itemView.getContext();

            if (isSelected) {
                container.setBackgroundResource(R.drawable.player_episode_item_selected);
                tvPageNumber.setTextColor(ContextCompat.getColor(context, R.color.purple_primary));
            } else {
                container.setBackgroundResource(R.drawable.player_episode_item_normal);
                tvPageNumber.setTextColor(ContextCompat.getColor(context, R.color.accent_text_color));
            }

            container.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPageClick(page);
                }
            });
        }
    }
}
