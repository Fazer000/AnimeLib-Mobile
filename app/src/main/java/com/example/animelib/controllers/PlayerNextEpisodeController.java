package com.example.animelib.controllers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.models.KodikResponse;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * Контроллер обратного отсчета, показа оверлея следующего эпизода и префетча потоков следующей серии.
 */
public class PlayerNextEpisodeController {

    private static final String TAG = "NextEpisodeController";
    private static final int DEFAULT_COUNTDOWN_SECONDS = 7;

    public interface PrefetchHost {
        boolean isOfflineMode();
        boolean isFinishingOrDestroyed();
    }

    private View nextEpisodeOverlay;
    private TextView nextEpisodeNumber;
    private TextView nextEpisodeCountdown;
    private MaterialButton cancelNextEpisodeButton;
    private MaterialButton playNextEpisodeButton;

    private final Handler nextEpisodeHandler = new Handler(Looper.getMainLooper());
    private Runnable nextEpisodeRunnable;
    private int countdownSeconds = DEFAULT_COUNTDOWN_SECONDS;

    private final Handler prefetchHandler = new Handler(Looper.getMainLooper());
    private Runnable prefetchRunnable;
    private String lastPrefetchedNextEpisodeKey = null;

    private EpisodesManager episodesManager;
    private PlayersManager playersManager;
    private ApiService apiService;
    private PrefetchHost prefetchHost;

    public void initViews(View rootView) {
        if (rootView == null) return;
        nextEpisodeOverlay = rootView.findViewById(R.id.nextEpisodeOverlay);
        nextEpisodeNumber = rootView.findViewById(R.id.nextEpisodeNumber);
        nextEpisodeCountdown = rootView.findViewById(R.id.nextEpisodeCountdown);
        cancelNextEpisodeButton = rootView.findViewById(R.id.cancelNextEpisodeButton);
        playNextEpisodeButton = rootView.findViewById(R.id.playNextEpisodeButton);

        if (cancelNextEpisodeButton != null) {
            cancelNextEpisodeButton.setOnClickListener(v -> cancelNextEpisode());
        }
        if (playNextEpisodeButton != null) {
            playNextEpisodeButton.setOnClickListener(v -> playNextEpisodeNow());
        }
    }

    public void setEpisodesManager(EpisodesManager episodesManager) {
        this.episodesManager = episodesManager;
    }

    public void setPrefetchDependencies(ApiService apiService, PlayersManager playersManager, PrefetchHost prefetchHost) {
        this.apiService = apiService;
        this.playersManager = playersManager;
        this.prefetchHost = prefetchHost;
    }

    public void showNextEpisodeOverlay() {
        if (nextEpisodeOverlay == null || episodesManager == null) return;

        EpisodesListResponse.EpisodeItem nextEpisode = episodesManager.getNextEpisode();
        if (nextEpisode == null) {
            Log.d(TAG, "No next episode available");
            return;
        }

        Log.d(TAG, "Showing next episode overlay for episode: " + nextEpisode.getNumber());

        if (nextEpisodeNumber != null) {
            nextEpisodeNumber.setText("эпизод " + nextEpisode.getNumber());
        }

        countdownSeconds = DEFAULT_COUNTDOWN_SECONDS;
        if (nextEpisodeCountdown != null) {
            nextEpisodeCountdown.setText(String.valueOf(countdownSeconds));
        }

        nextEpisodeOverlay.setVisibility(View.VISIBLE);
        nextEpisodeOverlay.setAlpha(0f);
        nextEpisodeOverlay.animate()
                .alpha(1f)
                .setDuration(300)
                .start();

        startCountdown();
    }

    private void startCountdown() {
        stopCountdown();

        nextEpisodeRunnable = new Runnable() {
            @Override
            public void run() {
                countdownSeconds--;

                if (nextEpisodeCountdown != null) {
                    nextEpisodeCountdown.setText(String.valueOf(countdownSeconds));
                }

                if (countdownSeconds > 0) {
                    nextEpisodeHandler.postDelayed(this, 1000);
                } else {
                    playNextEpisodeNow();
                }
            }
        };

        nextEpisodeHandler.postDelayed(nextEpisodeRunnable, 1000);
    }

    public void handlePlaybackEnded(boolean autoPlay) {
        if (autoPlay && episodesManager != null && episodesManager.getNextEpisode() != null) {
            Log.d(TAG, "Video ended, showing next episode overlay");
            showNextEpisodeOverlay();
        } else {
            Log.d(TAG, "Playback ended but autoPlay is disabled or no next episode available");
        }
    }

    public boolean isOverlayVisible() {
        return nextEpisodeOverlay != null && nextEpisodeOverlay.getVisibility() == View.VISIBLE;
    }

    public void setupNextEpisodeButton(ImageButton button) {
        if (button != null) {
            button.setOnClickListener(v -> {
                if (button.isEnabled() && episodesManager != null) {
                    playNextEpisodeNow();
                }
            });
        }
    }

    public void cancelNextEpisode() {
        Log.d(TAG, "Next episode cancelled by user");
        stopCountdown();
        hideNextEpisodeOverlay();
    }

    public void playNextEpisodeNow() {
        Log.d(TAG, "Playing next episode now");
        stopCountdown();
        hideNextEpisodeOverlay();

        if (episodesManager != null) {
            episodesManager.navigateToNextEpisode();
        }
    }

    public void hideNextEpisodeOverlay() {
        if (nextEpisodeOverlay == null) return;

        nextEpisodeOverlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction(() -> {
                    if (nextEpisodeOverlay != null) {
                        nextEpisodeOverlay.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    public void stopCountdown() {
        if (nextEpisodeRunnable != null) {
            nextEpisodeHandler.removeCallbacks(nextEpisodeRunnable);
            nextEpisodeRunnable = null;
        }
    }

    public void cancelPendingNextEpisodePrefetch() {
        if (prefetchRunnable != null) {
            prefetchHandler.removeCallbacks(prefetchRunnable);
            prefetchRunnable = null;
        }
    }

    public void scheduleNextEpisodePrefetch() {
        cancelPendingNextEpisodePrefetch();
        if (prefetchHost == null || prefetchHost.isOfflineMode() || prefetchHost.isFinishingOrDestroyed()) {
            return;
        }
        prefetchRunnable = this::prefetchNextEpisodeStreams;
        prefetchHandler.postDelayed(prefetchRunnable, 1_500L);
    }

    private void prefetchNextEpisodeStreams() {
        if (prefetchHost == null || prefetchHost.isOfflineMode() || prefetchHost.isFinishingOrDestroyed()
                || apiService == null || episodesManager == null) {
            return;
        }
        EpisodesListResponse.EpisodeItem nextEp = episodesManager.getNextEpisode();
        if (nextEp == null || nextEp.getId() <= 0) {
            return;
        }

        EpisodeResponse.PlayerData activePlayer = playersManager != null ? playersManager.getCurrentPlayerData() : null;
        final String targetPlayerName = activePlayer != null && activePlayer.getPlayer() != null ? activePlayer.getPlayer() : "";
        final int targetTeamId = activePlayer != null && activePlayer.getTeam() != null ? activePlayer.getTeam().getId() : -1;
        final String prefetchKey = nextEp.getId() + "_" + targetPlayerName + "_" + targetTeamId;
        if (prefetchKey.equals(lastPrefetchedNextEpisodeKey)) {
            return;
        }
        lastPrefetchedNextEpisodeKey = prefetchKey;

        apiService.fetchEpisodeData(nextEp.getId(), new ApiService.EpisodeDataCallback() {
            @Override
            public void onEpisodeDataReceived(EpisodeResponse response) {
                if (prefetchHost == null || prefetchHost.isFinishingOrDestroyed() || response == null || response.getData() == null
                        || response.getData().getPlayers() == null || response.getData().getPlayers().isEmpty()) {
                    return;
                }
                List<EpisodeResponse.PlayerData> nextPlayers = response.getData().getPlayers();
                EpisodeResponse.PlayerData match = null;
                if (targetTeamId > 0) {
                    for (EpisodeResponse.PlayerData pd : nextPlayers) {
                        if (pd != null && pd.getTeam() != null && pd.getTeam().getId() == targetTeamId
                                && pd.getPlayer() != null && pd.getPlayer().equalsIgnoreCase(targetPlayerName)) {
                            match = pd;
                            break;
                        }
                    }
                    if (match == null) {
                        for (EpisodeResponse.PlayerData pd : nextPlayers) {
                            if (pd != null && pd.getTeam() != null && pd.getTeam().getId() == targetTeamId) {
                                match = pd;
                                break;
                            }
                        }
                    }
                }
                if (match == null) {
                    match = nextPlayers.get(0);
                }
                if (match != null && "kodik".equalsIgnoreCase(match.getPlayer())
                        && match.getSrc() != null && !match.getSrc().trim().isEmpty()) {
                    apiService.fetchKodikVideoLinks(match.getSrc().trim(), false, new ApiService.KodikVideoCallback() {
                        @Override
                        public void onKodikVideoReceived(KodikResponse kodikResponse) {
                            Log.d(TAG, "Prefetched Kodik links for next episode ID: " + nextEp.getId());
                        }

                        @Override
                        public void onError(String error) {}
                    });
                }
            }

            @Override
            public void onError(String error) {}
        });
    }

    public void cleanup() {
        stopCountdown();
        cancelPendingNextEpisodePrefetch();
    }
}
