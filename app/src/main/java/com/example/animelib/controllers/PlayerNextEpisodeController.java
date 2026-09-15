package com.example.animelib.controllers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.example.animelib.R;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.models.EpisodesListResponse;
import com.google.android.material.button.MaterialButton;

/**
 * Контроллер обратного отсчета и показа оверлея следующего эпизода.
 */
public class PlayerNextEpisodeController {

    private static final String TAG = "NextEpisodeController";
    private static final int DEFAULT_COUNTDOWN_SECONDS = 7;

    private View nextEpisodeOverlay;
    private TextView nextEpisodeNumber;
    private TextView nextEpisodeCountdown;
    private MaterialButton cancelNextEpisodeButton;
    private MaterialButton playNextEpisodeButton;

    private final Handler nextEpisodeHandler = new Handler(Looper.getMainLooper());
    private Runnable nextEpisodeRunnable;
    private int countdownSeconds = DEFAULT_COUNTDOWN_SECONDS;

    private EpisodesManager episodesManager;

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

    public void setupNextEpisodeButton(android.widget.ImageButton button) {
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

    public void cleanup() {
        stopCountdown();
    }
}
