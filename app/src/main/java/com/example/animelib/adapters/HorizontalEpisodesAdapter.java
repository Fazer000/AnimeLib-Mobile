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
    
    private boolean isMatchingEpisode(EpisodesListResponse.EpisodeItem ep1, EpisodesListResponse.EpisodeItem ep2) {
        if (ep1 == null || ep2 == null) return false;
        if (ep1.getId() != 0 && ep2.getId() != 0 && ep1.getId() == ep2.getId()) {
            return true;
        }
        String a = ep1.getNumber();
        String b = ep2.getNumber();
        if (a != null && b != null) {
            if (a.equalsIgnoreCase(b)) {
                return true;
            }
            try {
                int ai = Integer.parseInt(a.trim());
                int bi = Integer.parseInt(b.trim());
                return ai == bi;
            } catch (Exception ignore) {
            }
        }
        return false;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setAnimeBookmark(com.example.animelib.models.AnimeBookmarkResponse.BookmarkData bookmark) {
        Integer oldBookmarkedId = (this.animeBookmark != null) ? this.animeBookmark.getItemId() : null;
        this.animeBookmark = bookmark;
        Integer newBookmarkedId = (bookmark != null) ? bookmark.getItemId() : null;

        if (episodes == null || episodes.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        int oldIndex = -1;
        int newIndex = -1;
        for (int i = 0; i < episodes.size(); i++) {
            EpisodesListResponse.EpisodeItem ep = episodes.get(i);
            if (ep != null) {
                if (oldBookmarkedId != null && ep.getId() == oldBookmarkedId.longValue()) {
                    oldIndex = i;
                }
                if (newBookmarkedId != null && ep.getId() == newBookmarkedId.longValue()) {
                    newIndex = i;
                }
            }
        }
        if (oldIndex != -1 && newIndex != -1) {
            notifyItemChanged(oldIndex);
            if (oldIndex != newIndex) notifyItemChanged(newIndex);
        } else if (newIndex != -1) {
            notifyItemChanged(newIndex);
        } else if (oldIndex != -1) {
            notifyItemChanged(oldIndex);
        } else {
            notifyDataSetChanged();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setEpisodes(List<EpisodesListResponse.EpisodeItem> newEpisodes, EpisodesListResponse.EpisodeItem currentEpisode) {
        this.episodes.clear();
        if (newEpisodes != null) {
            this.episodes.addAll(newEpisodes);
        }
        this.currentEpisode = currentEpisode;
        notifyDataSetChanged();
    }
    
    @SuppressLint("NotifyDataSetChanged")
    public void setCurrentEpisode(EpisodesListResponse.EpisodeItem currentEpisode) {
        EpisodesListResponse.EpisodeItem previousEpisode = this.currentEpisode;
        this.currentEpisode = currentEpisode;

        if (episodes == null || episodes.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        int oldIndex = -1;
        int newIndex = -1;
        for (int i = 0; i < episodes.size(); i++) {
            EpisodesListResponse.EpisodeItem ep = episodes.get(i);
            if (ep != null) {
                if (previousEpisode != null && isMatchingEpisode(ep, previousEpisode)) {
                    oldIndex = i;
                }
                if (currentEpisode != null && isMatchingEpisode(ep, currentEpisode)) {
                    newIndex = i;
                }
            }
        }
        if (oldIndex != -1 && newIndex != -1) {
            notifyItemChanged(oldIndex);
            if (oldIndex != newIndex) notifyItemChanged(newIndex);
        } else if (newIndex != -1) {
            notifyItemChanged(newIndex);
        } else if (oldIndex != -1) {
            notifyItemChanged(oldIndex);
        } else {
            notifyDataSetChanged();
        }
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
        boolean isCurrentEpisode = isMatchingEpisode(episode, currentEpisode);
        
        // Debug logging
        android.util.Log.d("EpisodesAdapter", "Episode " + episode.getNumber() + 
            " (ID: " + episode.getId() + ") at position " + position + 
            ": isCurrentEpisode=" + isCurrentEpisode + 
            ", currentEpisode=" + (currentEpisode != null ? 
                currentEpisode.getNumber() + " (ID: " + currentEpisode.getId() + ")" : "null"));

        // Set selected state and colors
        holder.itemView.setSelected(isCurrentEpisode);

        if (isHorizontalInPlayer) {
            if (isCurrentEpisode) {
                holder.episodeText.setTextColor(ContextCompat.getColor(context, R.color.purple_primary));
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
