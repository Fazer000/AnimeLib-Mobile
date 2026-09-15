package com.example.animelib.adapters;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;

import java.util.List;

public class PlayerTabsAdapter extends RecyclerView.Adapter<PlayerTabsAdapter.PlayerTabViewHolder> {
    private static final String TAG = "PlayerTabsAdapter";

    public interface OnEpisodeSelectedListener {
        void onEpisodeSelected(EpisodesListResponse.EpisodeItem episode);
    }

    private List<EpisodeResponse.PlayerData> animelibPlayers;
    private List<EpisodeResponse.PlayerData> kodikPlayers;
    private EpisodeResponse.PlayerData currentPlayer;
    private final PlayerOptionsAdapter.OnPlayerSelectedListener playerListener;
    public static final String SORT_NAME_ASC = "name_asc";
    public static final String SORT_NAME_DESC = "name_desc";
    public static final String SORT_QUALITY_DESC = "quality_desc";
    public static final String SORT_SUBTITLES_FIRST = "subtitles_first";

    private String filterQuery = "";
    private String sortType = SORT_NAME_ASC;

    // Список активных вкладок (только с озвучками)
    private List<String> activeTabs;

    public PlayerTabsAdapter(List<EpisodeResponse.PlayerData> animelibPlayers,
                           List<EpisodeResponse.PlayerData> kodikPlayers,
                           EpisodeResponse.PlayerData currentPlayer,
                           PlayerOptionsAdapter.OnPlayerSelectedListener playerListener) {
        this.animelibPlayers = animelibPlayers;
        this.kodikPlayers = kodikPlayers;
        this.currentPlayer = currentPlayer;
        this.playerListener = playerListener;
        updateActiveTabs();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateData(List<EpisodeResponse.PlayerData> animelibPlayers,
                          List<EpisodeResponse.PlayerData> kodikPlayers,
                          EpisodeResponse.PlayerData currentPlayer) {
        Log.d(TAG, "updateData called - AnimeLib: " + (animelibPlayers != null ? animelibPlayers.size() : "null") + 
              ", Kodik: " + (kodikPlayers != null ? kodikPlayers.size() : "null"));
        
        this.animelibPlayers = animelibPlayers;
        this.kodikPlayers = kodikPlayers;
        this.currentPlayer = currentPlayer;
        updateActiveTabs();
        
        Log.d(TAG, "Active tabs: " + (activeTabs != null ? activeTabs.size() : "null") + 
              ", items: " + (activeTabs != null ? activeTabs : "null"));
        
        notifyDataSetChanged();
    }
    
    /**
     * Обновляет список активных вкладок (только с озвучками)
     */
    private void updateActiveTabs() {
        activeTabs = new java.util.ArrayList<>();
        // Вкладки плееров
        activeTabs.add("animelib");
        activeTabs.add("kodik");
    }
    
    /**
     * Получает тип плеера по позиции
     */
    public String getPlayerTypeAtPosition(int position) {
        if (position >= 0 && position < activeTabs.size()) {
            return activeTabs.get(position);
        }
        return null;
    }

    /**
     * Получает позицию по типу плеера
     */
    public int getPositionForPlayerType(String playerType) {
        if (activeTabs != null && playerType != null) {
            for (int i = 0; i < activeTabs.size(); i++) {
                if (playerType.equalsIgnoreCase(activeTabs.get(i))) {
                    return i;
                }
            }
        }
        return -1;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updatePlayers(List<EpisodeResponse.PlayerData> animelibPlayers,
                              List<EpisodeResponse.PlayerData> kodikPlayers,
                              EpisodeResponse.PlayerData currentPlayer) {
        this.animelibPlayers = animelibPlayers;
        this.kodikPlayers = kodikPlayers;
        this.currentPlayer = currentPlayer;
        updateActiveTabs();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PlayerTabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.tab_player_options, parent, false);
        return new PlayerTabViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlayerTabViewHolder holder, int position) {
        String playerType = getPlayerTypeAtPosition(position);
        Log.d(TAG, "onBindViewHolder position=" + position + ", playerType=" + playerType);
        
        if ("animelib".equals(playerType)) {
            Log.d(TAG, "Setting up AnimeLib tab with " + (animelibPlayers != null ? animelibPlayers.size() : "null") + " players");
            setupPlayerTab(holder, animelibPlayers, "animelib");
        } else if ("kodik".equals(playerType)) {
            Log.d(TAG, "Setting up Kodik tab with " + (kodikPlayers != null ? kodikPlayers.size() : "null") + " players");
            setupPlayerTab(holder, kodikPlayers, "kodik");
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setFilterQuery(String query) {
        this.filterQuery = query != null ? query : "";
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setSortType(String sortType) {
        if (sortType != null) {
            this.sortType = sortType;
            notifyDataSetChanged();
        }
    }

    public String getSortType() {
        return sortType != null ? sortType : SORT_NAME_ASC;
    }

    private String getPlayerName(EpisodeResponse.PlayerData p) {
        if (p == null) return "";
        if (p.getTeam() != null && p.getTeam().getName() != null && !p.getTeam().getName().trim().isEmpty()) {
            return p.getTeam().getName().trim();
        }
        if (p.getTranslationType() != null && p.getTranslationType().getLabel() != null && !p.getTranslationType().getLabel().trim().isEmpty()) {
            return p.getTranslationType().getLabel().trim();
        }
        return "";
    }

    private int getMaxQuality(EpisodeResponse.PlayerData p) {
        if (p == null || p.getVideo() == null || p.getVideo().getQuality() == null) return 0;
        int max = 0;
        for (EpisodeResponse.QualityData qd : p.getVideo().getQuality()) {
            if (qd != null && qd.getQuality() > max) {
                max = qd.getQuality();
            }
        }
        return max;
    }

    private boolean hasSubtitles(EpisodeResponse.PlayerData p) {
        return p != null && p.isSubtitles();
    }

    private List<EpisodeResponse.PlayerData> filterAndSortPlayers(List<EpisodeResponse.PlayerData> originalList) {
        if (originalList == null) return new java.util.ArrayList<>();
        List<EpisodeResponse.PlayerData> result = new java.util.ArrayList<>();
        String query = filterQuery != null ? filterQuery.trim().toLowerCase() : "";

        for (EpisodeResponse.PlayerData player : originalList) {
            if (player == null) continue;
            if (query.isEmpty()) {
                result.add(player);
            } else {
                String teamName = (player.getTeam() != null && player.getTeam().getName() != null) ? player.getTeam().getName().toLowerCase() : "";
                String transLabel = (player.getTranslationType() != null && player.getTranslationType().getLabel() != null) ? player.getTranslationType().getLabel().toLowerCase() : "";
                if (teamName.contains(query) || transLabel.contains(query)) {
                    result.add(player);
                }
            }
        }

        if (SORT_NAME_DESC.equals(sortType)) {
            java.util.Collections.sort(result, (p1, p2) -> getPlayerName(p2).compareToIgnoreCase(getPlayerName(p1)));
        } else if (SORT_QUALITY_DESC.equals(sortType)) {
            java.util.Collections.sort(result, (p1, p2) -> Integer.compare(getMaxQuality(p2), getMaxQuality(p1)));
        } else if (SORT_SUBTITLES_FIRST.equals(sortType)) {
            java.util.Collections.sort(result, (p1, p2) -> {
                boolean sub1 = hasSubtitles(p1);
                boolean sub2 = hasSubtitles(p2);
                if (sub1 != sub2) return sub1 ? -1 : 1;
                return getPlayerName(p1).compareToIgnoreCase(getPlayerName(p2));
            });
        } else { // default SORT_NAME_ASC
            java.util.Collections.sort(result, (p1, p2) -> getPlayerName(p1).compareToIgnoreCase(getPlayerName(p2)));
        }

        return result;
    }

    @SuppressLint("SetTextI18n")
    private void setupPlayerTab(PlayerTabViewHolder holder,
                                List<EpisodeResponse.PlayerData> players,
                                String playerType) {
        Log.d(TAG, "setupPlayerTab for " + playerType + " with " + (players != null ? players.size() : "null") + " players");
        
        List<EpisodeResponse.PlayerData> processedPlayers = filterAndSortPlayers(players);
        boolean isEmpty = processedPlayers.isEmpty();

        if (holder.tvEmptyVoiceovers != null) {
            if (players != null && !players.isEmpty() && processedPlayers.isEmpty() && !filterQuery.isEmpty()) {
                holder.tvEmptyVoiceovers.setText("Ничего не найдено по запросу \"" + filterQuery + "\"");
            } else {
                holder.tvEmptyVoiceovers.setText("Нет доступных озвучек");
            }
            holder.tvEmptyVoiceovers.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
        if (holder.playersRecyclerView != null) {
            holder.playersRecyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            if (!isEmpty) {
                if (holder.playersRecyclerView.getLayoutManager() == null) {
                    holder.playersRecyclerView.setLayoutManager(
                        new androidx.recyclerview.widget.LinearLayoutManager(holder.itemView.getContext())
                    );
                }
                PlayerOptionsAdapter adapter = new PlayerOptionsAdapter(processedPlayers, currentPlayer, playerListener);
                holder.playersRecyclerView.setAdapter(adapter);
            }
        }
    }

    @Override
    public int getItemCount() {
        return activeTabs != null ? activeTabs.size() : 0;
    }

    public static class PlayerTabViewHolder extends RecyclerView.ViewHolder {
        RecyclerView playersRecyclerView;
        TextView tvEmptyVoiceovers;

        PlayerTabViewHolder(@NonNull View itemView) {
            super(itemView);
            playersRecyclerView = itemView.findViewById(R.id.playersRecyclerView);
            tvEmptyVoiceovers = itemView.findViewById(R.id.tvEmptyVoiceovers);
        }
    }

}
