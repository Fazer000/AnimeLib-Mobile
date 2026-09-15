package com.example.animelib.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.EpisodesListResponse;

import java.util.List;

public class HorizontalEpisodesAdapter extends RecyclerView.Adapter<HorizontalEpisodesAdapter.EpisodeViewHolder> {

    private final List<EpisodesListResponse.EpisodeItem> episodes;
    private EpisodesListResponse.EpisodeItem currentEpisode;
    private final OnEpisodeSelectedListener listener;
    private final boolean isHorizontalInPlayer;
    private com.example.animelib.models.AnimeBookmarkResponse.BookmarkData animeBookmark;

    public interface OnEpisodeSelectedListener {
        void onEpisodeSelected(EpisodesListResponse.EpisodeItem episode);
    }

    public HorizontalEpisodesAdapter(List<EpisodesListResponse.EpisodeItem> episodes,
                                   EpisodesListResponse.EpisodeItem currentEpisode,
                                   OnEpisodeSelectedListener listener) {
        this(episodes, currentEpisode, false, listener);
    }

    public HorizontalEpisodesAdapter(List<EpisodesListResponse.EpisodeItem> episodes,
                                   EpisodesListResponse.EpisodeItem currentEpisode,
                                   boolean isHorizontalInPlayer,
                                   OnEpisodeSelectedListener listener) {
        this.episodes = episodes;
        this.currentEpisode = currentEpisode;
        this.isHorizontalInPlayer = isHorizontalInPlayer;
        this.listener = listener;
    }
    
    @SuppressLint("NotifyDataSetChanged")
    public void setAnimeBookmark(com.example.animelib.models.AnimeBookmarkResponse.BookmarkData bookmark) {
        this.animeBookmark = bookmark;
        notifyDataSetChanged();
    }
    
    @SuppressLint("NotifyDataSetChanged")
    public void setCurrentEpisode(EpisodesListResponse.EpisodeItem currentEpisode) {
        this.currentEpisode = currentEpisode;
        notifyDataSetChanged();
        android.util.Log.d("EpisodesAdapter", "Current episode updated to: " + 
            (currentEpisode != null ? currentEpisode.getNumber() + " (ID: " + currentEpisode.getId() + ")" : "null"));
    }

    @NonNull
    @Override
    public EpisodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_horizontal_episode, parent, false);
        return new EpisodeViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EpisodeViewHolder holder, int position) {
        EpisodesListResponse.EpisodeItem episode = episodes.get(position);

        // Set episode number + "серия"
        String episodeText = episode.getNumber() + " серия";
        holder.episodeText.setText(episodeText);

        // Status tag (e.g. RECAP, SPECIAL)
        String statusTag = com.example.animelib.util.EpisodeUtils.getTransliteratedStatusLabel(episode.getStatus());
        if (holder.statusTagText != null) {
            if (statusTag != null && !statusTag.isEmpty()) {
                holder.statusTagText.setText(statusTag);
                holder.statusTagText.setVisibility(View.VISIBLE);
            } else {
                holder.statusTagText.setVisibility(View.GONE);
            }
        }
        
        // Check if this episode has a bookmark
        boolean hasBookmark = false;
        String bookmarkProgress = null;
        if (animeBookmark != null && animeBookmark.getItemId() == episode.getId()) {
            hasBookmark = true;
            bookmarkProgress = animeBookmark.getProgress();
        }

        Context context = holder.itemView.getContext();
        // Show/hide bookmark icon
        if (holder.bookmarkIcon != null) {
            holder.bookmarkIcon.setVisibility(hasBookmark ? android.view.View.VISIBLE : android.view.View.GONE);
            if (hasBookmark) {
                holder.bookmarkIcon.setColorFilter(ContextCompat.getColor(context, R.color.bookmark_color)); // Красный цвет
            }
        }

        // Check if this is the current episode
        boolean isCurrentEpisode = false;
        if (currentEpisode != null) {
            // Try ID first
            if (currentEpisode.getId() == episode.getId()) {
                isCurrentEpisode = true;
            } else {
                // Compare numbers (string or numeric-equivalent)
                String a = currentEpisode.getNumber();
                String b = episode.getNumber();
                if (a != null && b != null) {
                    if (a.equals(b)) {
                        isCurrentEpisode = true;
                    } else {
                        try {
                            int ai = Integer.parseInt(a.trim());
                            int bi = Integer.parseInt(b.trim());
                            isCurrentEpisode = ai == bi;
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
        }
        
        // Debug logging
        android.util.Log.d("EpisodesAdapter", "Episode " + episode.getNumber() + 
            " (ID: " + episode.getId() + ") at position " + position + 
            ": isCurrentEpisode=" + isCurrentEpisode + 
            ", currentEpisode=" + (currentEpisode != null ? 
                currentEpisode.getNumber() + " (ID: " + currentEpisode.getId() + ")" : "null"));

        // Set selected state and colors
        holder.itemView.setSelected(isCurrentEpisode);

        TypedValue typedValue = new TypedValue();
        Resources.Theme theme = holder.itemView.getContext().getTheme();

        if (isHorizontalInPlayer) {
            if (isCurrentEpisode) {
                holder.episodeText.setTextColor(ContextCompat.getColor(context, R.color.player_item_active_text));
                holder.itemView.setBackgroundResource(R.drawable.player_episode_item_horizontal_selected);
            } else {
                holder.episodeText.setTextColor(ContextCompat.getColor(context, R.color.player_item_inactive_text));
                holder.itemView.setBackgroundResource(R.drawable.player_episode_item_horizontal_normal);
            }
        } else {
            if (isCurrentEpisode) {
                holder.episodeText.setTextColor(ContextCompat.getColor(context, R.color.purple_primary));
                holder.itemView.setBackgroundResource(R.drawable.player_episode_item_selected);
            } else {
                holder.episodeText.setTextColor(ContextCompat.getColor(context, R.color.accent_text_color));
                holder.itemView.setBackgroundResource(R.drawable.player_episode_item_normal);
            }
        }

        // Ensure stable view state during scrolling
        holder.itemView.setScaleX(1.0f);
        holder.itemView.setScaleY(1.0f);
        holder.itemView.setAlpha(1.0f);

        // Set click listener
        holder.itemView.setOnClickListener(v -> {
            com.example.animelib.util.ItemAnimationUtils.animateItemClick(v, () -> {
                if (listener != null) {
                    listener.onEpisodeSelected(episode);
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        return episodes != null ? episodes.size() : 0;
    }

    public static class EpisodeViewHolder extends RecyclerView.ViewHolder {
        TextView episodeText;
        TextView statusTagText;
        android.widget.ImageView bookmarkIcon;

        EpisodeViewHolder(@NonNull View itemView) {
            super(itemView);
            episodeText = itemView.findViewById(R.id.episodeText);
            statusTagText = itemView.findViewById(R.id.statusTagText);
            bookmarkIcon = itemView.findViewById(R.id.bookmarkIcon);
        }
    }
}
