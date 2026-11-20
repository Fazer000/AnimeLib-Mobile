package com.example.animelib.adapters;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.controllers.PlayerDownloadController;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.util.ImageLoader;

import java.util.List;

public class PlayerOptionsAdapter extends RecyclerView.Adapter<PlayerOptionsAdapter.PlayerViewHolder> {
    private static final String TAG = "PlayerOptionsAdapter";
    private static final Object PAYLOAD_SELECTION = new Object();
    private static final long SELECTION_ANIM_DURATION_MS = 180L;
    private static final long CLICK_DISPATCH_DELAY_MS = 190L;
    private static final long SAME_ITEM_CLICK_DELAY_MS = 120L;

    private List<EpisodeResponse.PlayerData> players;
    private EpisodeResponse.PlayerData currentPlayer;
    private EpisodesListResponse.EpisodeItem currentEpisode;
    private List<DownloadedEpisodeEntity> downloadedEpisodes;
    private final OnPlayerSelectedListener listener;
    @Nullable
    private OnVisualSelectionListener visualSelectionListener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private Runnable pendingSelectionRunnable;

    public interface OnPlayerSelectedListener {
        void onPlayerSelected(EpisodeResponse.PlayerData player);
    }

    public interface OnVisualSelectionListener {
        void onVisualPlayerSelected(EpisodeResponse.PlayerData player);
    }

    public PlayerOptionsAdapter(List<EpisodeResponse.PlayerData> players,
                               EpisodeResponse.PlayerData currentPlayer,
                               OnPlayerSelectedListener listener) {
        this(players, currentPlayer, null, null, listener);
    }

    public PlayerOptionsAdapter(List<EpisodeResponse.PlayerData> players,
                               EpisodeResponse.PlayerData currentPlayer,
                               EpisodesListResponse.EpisodeItem currentEpisode,
                               List<DownloadedEpisodeEntity> downloadedEpisodes,
                               OnPlayerSelectedListener listener) {
        this.players = players;
        this.currentPlayer = currentPlayer;
        this.currentEpisode = currentEpisode;
        this.downloadedEpisodes = downloadedEpisodes;
        this.listener = listener;

        Log.d(TAG, "Constructor - players: " + (players != null ? players.size() : "null"));
    }

    public void setVisualSelectionListener(@Nullable OnVisualSelectionListener visualSelectionListener) {
        this.visualSelectionListener = visualSelectionListener;
    }

    public void updatePlayers(List<EpisodeResponse.PlayerData> players, EpisodeResponse.PlayerData currentPlayer) {
        updatePlayers(players, currentPlayer, this.currentEpisode, this.downloadedEpisodes);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updatePlayers(List<EpisodeResponse.PlayerData> players,
                              EpisodeResponse.PlayerData currentPlayer,
                              EpisodesListResponse.EpisodeItem currentEpisode,
                              List<DownloadedEpisodeEntity> downloadedEpisodes) {
        boolean sameEpisode = (this.currentEpisode == currentEpisode)
                || (this.currentEpisode != null && currentEpisode != null
                && this.currentEpisode.getId() == currentEpisode.getId());
        boolean sameDownloaded = (this.downloadedEpisodes == downloadedEpisodes);
        boolean sameList = isSamePlayersList(this.players, players);

        this.players = players;
        this.currentEpisode = currentEpisode;
        this.downloadedEpisodes = downloadedEpisodes;

        if (sameList && sameEpisode && sameDownloaded) {
            updateCurrentPlayer(currentPlayer, false);
        } else {
            this.currentPlayer = currentPlayer;
            notifyDataSetChanged();
        }
    }

    public void updateCurrentPlayer(@Nullable EpisodeResponse.PlayerData newPlayer, boolean animate) {
        EpisodeResponse.PlayerData previousPlayer = this.currentPlayer;
        this.currentPlayer = newPlayer;

        if (players == null || players.isEmpty()) {
            return;
        }

        for (int i = 0; i < players.size(); i++) {
            EpisodeResponse.PlayerData item = players.get(i);
            boolean wasSelected = isMatchingPlayer(item, previousPlayer);
            boolean isSelected = isMatchingPlayer(item, newPlayer);
            if (wasSelected != isSelected) {
                if (animate) {
                    notifyItemChanged(i, PAYLOAD_SELECTION);
                } else {
                    notifyItemChanged(i);
                }
            }
        }
    }

    private boolean isSamePlayersList(@Nullable List<EpisodeResponse.PlayerData> a,
                                      @Nullable List<EpisodeResponse.PlayerData> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!isMatchingPlayer(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isMatchingPlayer(@Nullable EpisodeResponse.PlayerData player,
                                           @Nullable EpisodeResponse.PlayerData target) {
        if (player == null || target == null) {
            return false;
        }
        if (player == target) {
            return true;
        }
        if (player.getId() > 0 && target.getId() > 0 && player.getId() == target.getId()) {
            return true;
        }

        String playerTeam = (player.getTeam() != null && player.getTeam().getName() != null)
                ? player.getTeam().getName().trim() : "";
        String targetTeam = (target.getTeam() != null && target.getTeam().getName() != null)
                ? target.getTeam().getName().trim() : "";

        int playerTeamId = player.getTeam() != null ? player.getTeam().getId() : 0;
        int targetTeamId = target.getTeam() != null ? target.getTeam().getId() : 0;

        boolean teamMatch = (!playerTeam.isEmpty() && playerTeam.equalsIgnoreCase(targetTeam))
                || (playerTeamId > 0 && playerTeamId == targetTeamId);

        boolean playerTypeMatch = (player.getPlayer() == null || target.getPlayer() == null
                || player.getPlayer().isEmpty() || target.getPlayer().isEmpty()
                || player.getPlayer().equalsIgnoreCase(target.getPlayer()));

        int playerTransId = player.getTranslationType() != null ? player.getTranslationType().getId() : 0;
        int targetTransId = target.getTranslationType() != null ? target.getTranslationType().getId() : 0;
        boolean translationMatch = (playerTransId <= 0 || targetTransId <= 0 || playerTransId == targetTransId);

        return teamMatch && playerTypeMatch && translationMatch;
    }

    public static boolean isVoiceoverDownloaded(EpisodeResponse.PlayerData player,
                                                 EpisodesListResponse.EpisodeItem currentEpisode,
                                                 List<DownloadedEpisodeEntity> downloadedEpisodes) {
        if (player == null || currentEpisode == null || downloadedEpisodes == null || downloadedEpisodes.isEmpty()) {
            return false;
        }
        return PlayerDownloadController.findDownloadedEpisodeForVoiceover(downloadedEpisodes, currentEpisode, player) != null;
    }

    @NonNull
    @Override
    public PlayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_player_option, parent, false);
        return new PlayerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlayerViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            EpisodeResponse.PlayerData player = players.get(position);
            boolean isCurrentPlayer = isMatchingPlayer(player, currentPlayer);
            applySelectionState(holder, isCurrentPlayer, true);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onBindViewHolder(@NonNull PlayerViewHolder holder, int position) {
        EpisodeResponse.PlayerData player = players.get(position);

        // Set team info as main title
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

        // Tags: SUB, FHD, 4K
        boolean hasFhd = false;
        boolean hasFourK = false;
        if ("animelib".equalsIgnoreCase(player.getPlayer())) {
            if (player.getVideo() != null && player.getVideo().getQuality() != null) {
                for (EpisodeResponse.QualityData q : player.getVideo().getQuality()) {
                    if (q != null) {
                        if (q.getQuality() >= 2160) {
                            hasFourK = true;
                        } else if (q.getQuality() >= 1080) {
                            hasFhd = true;
                        }
                    }
                }
            }
        }

        boolean is4kEnabled = false;
        try {
            is4kEnabled = com.example.animelib.data.DatabaseManager.getInstance(holder.itemView.getContext()).load4KSetting();
        } catch (Exception ignored) {
            is4kEnabled = false;
        }

        if (is4kEnabled) {
            if (holder.fhdTag != null) {
                holder.fhdTag.setVisibility(hasFhd && !hasFourK ? View.VISIBLE : View.GONE);
            }
            if (holder.fourKTag != null) {
                holder.fourKTag.setVisibility(hasFourK ? View.VISIBLE : View.GONE);
            }
        } else {
            // Если в настройках выключен 4K: тег выше FHD не пишем, при наличии 4K пишем FHD
            if (holder.fhdTag != null) {
                holder.fhdTag.setVisibility((hasFhd || hasFourK) ? View.VISIBLE : View.GONE);
            }
            if (holder.fourKTag != null) {
                holder.fourKTag.setVisibility(View.GONE);
            }
        }

        if (holder.subTag != null) {
            holder.subTag.setVisibility(player.isSubtitles() ? View.VISIBLE : View.GONE);
        }

        if (holder.downloadedTag != null) {
            boolean isDownloaded = isVoiceoverDownloaded(player, currentEpisode, downloadedEpisodes);
            holder.downloadedTag.setVisibility(isDownloaded ? View.VISIBLE : View.GONE);
        }

        // Apply active/inactive state cleanly without jerky alpha blinks
        boolean isCurrentPlayer = isMatchingPlayer(player, currentPlayer);
        holder.itemView.animate().cancel();
        holder.itemView.setScaleX(1.0f);
        holder.itemView.setScaleY(1.0f);
        holder.itemView.setAlpha(1.0f);
        applySelectionState(holder, isCurrentPlayer, false);

        // Set click listener with native ripple + smooth highlight transfer
        holder.itemView.setOnClickListener(v -> {
            if (pendingSelectionRunnable != null) {
                mainHandler.removeCallbacks(pendingSelectionRunnable);
                pendingSelectionRunnable = null;
            }

            boolean alreadySelected = isMatchingPlayer(player, currentPlayer);
            if (!alreadySelected) {
                updateCurrentPlayer(player, true);
                if (visualSelectionListener != null) {
                    visualSelectionListener.onVisualPlayerSelected(player);
                }
            }

            long dispatchDelay = alreadySelected ? SAME_ITEM_CLICK_DELAY_MS : CLICK_DISPATCH_DELAY_MS;
            pendingSelectionRunnable = () -> {
                pendingSelectionRunnable = null;
                if (listener != null) {
                    listener.onPlayerSelected(player);
                }
            };
            mainHandler.postDelayed(pendingSelectionRunnable, dispatchDelay);
        });
    }

    private void applySelectionState(@NonNull PlayerViewHolder holder, boolean isSelected, boolean animate) {
        Context context = holder.itemView.getContext();
        int targetTextColor = ContextCompat.getColor(
                context,
                isSelected ? R.color.purple_primary : R.color.primary_text_color
        );

        holder.cancelColorAnimator();

        if (animate) {
            holder.itemView.setSelected(isSelected);
            int startColor = holder.firstRow.getCurrentTextColor();
            if (startColor != targetTextColor) {
                ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, targetTextColor);
                animator.setDuration(SELECTION_ANIM_DURATION_MS);
                animator.setInterpolator(new DecelerateInterpolator());
                animator.addUpdateListener(anim -> holder.firstRow.setTextColor((int) anim.getAnimatedValue()));
                holder.textColorAnimator = animator;
                animator.start();
            } else {
                holder.firstRow.setTextColor(targetTextColor);
            }
        } else {
            holder.itemView.setSelected(isSelected);
            holder.itemView.jumpDrawablesToCurrentState();
            holder.firstRow.setTextColor(targetTextColor);
        }
    }

    @Override
    public void onViewRecycled(@NonNull PlayerViewHolder holder) {
        super.onViewRecycled(holder);
        holder.cancelColorAnimator();
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (pendingSelectionRunnable != null) {
            mainHandler.removeCallbacks(pendingSelectionRunnable);
            pendingSelectionRunnable = null;
        }
    }

    @Override
    public int getItemCount() {
        return players != null ? players.size() : 0;
    }

    public static class PlayerViewHolder extends RecyclerView.ViewHolder {
        TextView firstRow;
        TextView fhdTag;
        TextView fourKTag;
        TextView subTag;
        View downloadedTag;
        ImageView teamIcon;
        @Nullable
        ValueAnimator textColorAnimator;

        PlayerViewHolder(@NonNull View itemView) {
            super(itemView);
            firstRow = itemView.findViewById(R.id.firstRow);
            fhdTag = itemView.findViewById(R.id.fhdTag);
            fourKTag = itemView.findViewById(R.id.fourKTag);
            subTag = itemView.findViewById(R.id.subTag);
            downloadedTag = itemView.findViewById(R.id.downloadedTag);
            teamIcon = itemView.findViewById(R.id.teamIcon);
        }

        void cancelColorAnimator() {
            if (textColorAnimator != null) {
                textColorAnimator.cancel();
                textColorAnimator = null;
            }
        }
    }
}
