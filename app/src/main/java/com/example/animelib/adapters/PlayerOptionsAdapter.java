package com.example.animelib.adapters;

import android.content.res.Resources;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.util.ImageLoader;

import java.util.List;

public class PlayerOptionsAdapter extends RecyclerView.Adapter<PlayerOptionsAdapter.PlayerViewHolder> {
    private static final String TAG = "PlayerOptionsAdapter";

    private List<EpisodeResponse.PlayerData> players;
    private EpisodeResponse.PlayerData currentPlayer;
    private OnPlayerSelectedListener listener;

    public interface OnPlayerSelectedListener {
        void onPlayerSelected(EpisodeResponse.PlayerData player);
    }

    public PlayerOptionsAdapter(List<EpisodeResponse.PlayerData> players,
                               EpisodeResponse.PlayerData currentPlayer,
                               OnPlayerSelectedListener listener) {
        this.players = players;
        this.currentPlayer = currentPlayer;
        this.listener = listener;
        
        Log.d(TAG, "Constructor - players: " + (players != null ? players.size() : "null"));
        if (players != null) {
            for (int i = 0; i < players.size(); i++) {
                EpisodeResponse.PlayerData p = players.get(i);
                Log.d(TAG, "  [" + i + "] " + p.getPlayer() + " - " + 
                      (p.getTeam() != null ? p.getTeam().getName() : "no team"));
            }
        }
    }

    public void updatePlayers(List<EpisodeResponse.PlayerData> players, EpisodeResponse.PlayerData currentPlayer) {
        this.players = players;
        this.currentPlayer = currentPlayer;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PlayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_player_option, parent, false);
        return new PlayerViewHolder(view);
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onBindViewHolder(@NonNull PlayerViewHolder holder, int position) {
        EpisodeResponse.PlayerData player = players.get(position);

        // Set translation name as main title
        String translationName = "";
        if (player.getTranslationType() != null && player.getTranslationType().getLabel() != null) {
            translationName = player.getTranslationType().getLabel();
        } else {
            translationName = "Неизвестный перевод";
        }

        // Set team info as subtitle
        String teamInfo = "";
        if (player.getTeam() != null && player.getTeam().getName() != null) {
            teamInfo = player.getTeam().getName();
        }
        holder.firstRow.setText(teamInfo);

        // Load team/voiceover icon
        if (holder.teamIcon != null) {
            String coverUrl = player.getCoverUrl();
            if (coverUrl != null && !coverUrl.isEmpty()) {
                ImageLoader.getInstance().loadInto(holder.teamIcon, coverUrl, R.drawable.ic_avatar_placeholder);
            } else {
                holder.teamIcon.setImageResource(R.drawable.ic_avatar_placeholder);
            }
        }

        // FHD tag (1080p)
        boolean hasFhd = false;
        boolean hasFourK = false;
        if ("animelib".equalsIgnoreCase(player.getPlayer())) {
            if (player.getVideo() != null && player.getVideo().getQuality() != null) {
                for (EpisodeResponse.QualityData q : player.getVideo().getQuality()) {
                    if (q != null && q.getQuality() == 1080) {
                        hasFhd = true;
                        break;
                    }
                }
                for (EpisodeResponse.QualityData q : player.getVideo().getQuality()) {
                    if (q != null && q.getQuality() == 2160) {
                        hasFourK = true;
                        break;
                    }
                }
            }
        }
        if (holder.fhdTag != null) {
            holder.fhdTag.setVisibility(hasFhd && !hasFourK ? View.VISIBLE : View.GONE);
        }

        if (holder.fourKTag != null) {
            holder.fourKTag.setVisibility(hasFourK ? View.VISIBLE : View.GONE);
        }

        if (holder.subTag != null) {
            holder.subTag.setVisibility(player.isSubtitles() ? View.VISIBLE : View.GONE);
        }

        // Show current player indicator
        boolean isCurrentPlayer = currentPlayer != null &&
                player.getPlayer() != null && currentPlayer.getPlayer() != null &&
                player.getPlayer().equalsIgnoreCase(currentPlayer.getPlayer()) &&
                ((player.getTranslationType() == null && currentPlayer.getTranslationType() == null) ||
                        (player.getTranslationType() != null && currentPlayer.getTranslationType() != null &&
                                player.getTranslationType().getLabel() != null && currentPlayer.getTranslationType().getLabel() != null &&
                                player.getTranslationType().getLabel().equals(currentPlayer.getTranslationType().getLabel()))) &&
                ((player.getTeam() == null && currentPlayer.getTeam() == null) ||
                        (player.getTeam() != null && currentPlayer.getTeam() != null &&
                                player.getTeam().getName() != null && currentPlayer.getTeam().getName() != null &&
                                player.getTeam().getName().equals(currentPlayer.getTeam().getName())));

        // Set selected state and text color
        holder.itemView.setSelected(isCurrentPlayer);

        android.content.Context context = holder.itemView.getContext();
        if (isCurrentPlayer) {
            holder.firstRow.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.secondary_text_color));
        } else {
            holder.firstRow.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.primary_text_color));
        }

        // Smooth transition for active vs inactive state without scaling
        holder.itemView.setScaleX(1.0f);
        holder.itemView.setScaleY(1.0f);
        com.example.animelib.util.ItemAnimationUtils.animateItemStateTransition(holder.itemView, isCurrentPlayer);

        // Set click listener
        holder.itemView.setOnClickListener(v -> {
            com.example.animelib.util.ItemAnimationUtils.animateItemClick(v, () -> {
                if (listener != null) {
                    listener.onPlayerSelected(player);
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        int count = players != null ? players.size() : 0;
        Log.d(TAG, "getItemCount: " + count);
        return count;
    }

    public static class PlayerViewHolder extends RecyclerView.ViewHolder {
        TextView firstRow;
        TextView fhdTag;
        TextView fourKTag;
        TextView subTag;
        ImageView teamIcon;

        PlayerViewHolder(@NonNull View itemView) {
            super(itemView);
            firstRow = itemView.findViewById(R.id.firstRow);
            fhdTag = itemView.findViewById(R.id.fhdTag);
            fourKTag = itemView.findViewById(R.id.fourKTag);
            subTag = itemView.findViewById(R.id.subTag);
            teamIcon = itemView.findViewById(R.id.teamIcon);
        }
    }
}
