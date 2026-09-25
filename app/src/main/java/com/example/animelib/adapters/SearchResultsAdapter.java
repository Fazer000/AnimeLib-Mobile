package com.example.animelib.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.SearchResponse;
import com.example.animelib.util.ImageLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Адаптер для отображения результатов поиска аниме в стиле Alert Dialog
 */
public class SearchResultsAdapter extends RecyclerView.Adapter<SearchResultsAdapter.SearchViewHolder> {
    
    private List<SearchResponse.AnimeSearchItem> items = new ArrayList<>();
    private OnItemClickListener listener;
    
    public interface OnItemClickListener {
        void onItemClick(SearchResponse.AnimeSearchItem item);
        void onItemLongClick(SearchResponse.AnimeSearchItem item);
    }
    
    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }
    
    public void setItems(List<SearchResponse.AnimeSearchItem> items) {
        this.items = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }
    
    public void clearItems() {
        this.items.clear();
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public SearchViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_result, parent, false);
        return new SearchViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull SearchViewHolder holder, int position) {
        SearchResponse.AnimeSearchItem item = items.get(position);
        holder.bind(item, listener);
    }
    
    @Override
    public int getItemCount() {
        return items.size();
    }
    
    static class SearchViewHolder extends RecyclerView.ViewHolder {
        private final ImageView cover;
        private final TextView title;
        private final TextView subtitle;
        private final TextView typeBadge;
        private final TextView statusBadge;
        private final TextView yearBadge;
        private final TextView rating;
        private final TextView votes;
        
        public SearchViewHolder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.searchItemCover);
            title = itemView.findViewById(R.id.searchItemTitle);
            subtitle = itemView.findViewById(R.id.searchItemSubtitle);
            typeBadge = itemView.findViewById(R.id.searchItemTypeBadge);
            statusBadge = itemView.findViewById(R.id.searchItemStatusBadge);
            yearBadge = itemView.findViewById(R.id.searchItemYearBadge);
            rating = itemView.findViewById(R.id.searchItemRating);
            votes = itemView.findViewById(R.id.searchItemVotes);
        }
        
        public void bind(SearchResponse.AnimeSearchItem item, OnItemClickListener listener) {
            // Main Title & Subtitle
            String mainTitle = item.getRusName();
            String originalName = item.getName();
            String engName = item.getEngName();
            
            if (mainTitle != null && !mainTitle.trim().isEmpty()) {
                title.setText(mainTitle);
                // Subtitle: English or Japanese original if different
                String sub = (engName != null && !engName.isEmpty()) ? engName : originalName;
                if (sub != null && !sub.equalsIgnoreCase(mainTitle)) {
                    subtitle.setText(sub);
                    subtitle.setVisibility(View.VISIBLE);
                } else {
                    subtitle.setVisibility(View.GONE);
                }
            } else if (originalName != null && !originalName.trim().isEmpty()) {
                title.setText(originalName);
                subtitle.setVisibility(View.GONE);
            } else if (engName != null) {
                title.setText(engName);
                subtitle.setVisibility(View.GONE);
            } else {
                title.setText("Без названия");
                subtitle.setVisibility(View.GONE);
            }
            
            // Cover Image
            String coverUrl = null;
            if (item.getCover() != null) {
                if (item.getCover().getThumbnail() != null) {
                    coverUrl = item.getCover().getThumbnail();
                } else if (item.getCover().getDefaultUrl() != null) {
                    coverUrl = item.getCover().getDefaultUrl();
                } else if (item.getCover().getMd() != null) {
                    coverUrl = item.getCover().getMd();
                }
            }
            
            if (coverUrl != null && !coverUrl.isEmpty()) {
                ImageLoader.getInstance().loadInto(cover, coverUrl, R.drawable.placeholder_image);
            } else {
                cover.setImageResource(R.drawable.placeholder_image);
            }
            
            // Type badge
            if (item.getType() != null && item.getType().getLabel() != null && !item.getType().getLabel().isEmpty()) {
                typeBadge.setText(item.getType().getLabel());
                typeBadge.setVisibility(View.VISIBLE);
            } else {
                typeBadge.setVisibility(View.GONE);
            }
            
            // Status badge
            if (item.getStatus() != null && item.getStatus().getLabel() != null && !item.getStatus().getLabel().isEmpty()) {
                statusBadge.setText(item.getStatus().getLabel());
                statusBadge.setVisibility(View.VISIBLE);
            } else {
                statusBadge.setVisibility(View.GONE);
            }
            
            // Year badge
            String date = item.getReleaseDate();
            if (date != null && !date.isEmpty()) {
                String year = date.contains("-") ? date.split("-")[0] : date;
                yearBadge.setText(year);
                yearBadge.setVisibility(View.VISIBLE);
            } else if (item.getReleaseDateString() != null && !item.getReleaseDateString().isEmpty()) {
                yearBadge.setText(item.getReleaseDateString());
                yearBadge.setVisibility(View.VISIBLE);
            } else {
                yearBadge.setVisibility(View.GONE);
            }
            
            // Rating
            if (item.getRating() != null) {
                String avg = item.getRating().getAverageFormated();
                if (avg == null || avg.isEmpty()) {
                    avg = item.getRating().getAverage();
                }
                rating.setText(avg != null && !avg.isEmpty() ? avg : "—");
                
                String votesStr = item.getRating().getVotesFormated();
                if (votesStr != null && !votesStr.isEmpty()) {
                    votes.setText("(" + votesStr + ")");
                    votes.setVisibility(View.VISIBLE);
                } else if (item.getRating().getVotes() > 0) {
                    votes.setText("(" + item.getRating().getVotes() + ")");
                    votes.setVisibility(View.VISIBLE);
                } else {
                    votes.setVisibility(View.GONE);
                }
            } else {
                rating.setText("—");
                votes.setVisibility(View.GONE);
            }
            
            // Click listener - opens anime page in WebView
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(item);
                }
            });
            
            // Long click listener - opens VideoPlayerActivity
            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onItemLongClick(item);
                    return true;
                }
                return false;
            });
        }
    }
}
