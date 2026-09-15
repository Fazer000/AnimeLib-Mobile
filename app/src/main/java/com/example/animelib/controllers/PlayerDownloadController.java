package com.example.animelib.controllers;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.example.animelib.R;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.services.DownloadService;
import com.example.animelib.ui.DownloadBottomSheet;
import com.example.animelib.ui.DownloadProgressBottomSheet;
import com.example.animelib.util.DownloadAnimationUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Контроллер скачивания серий и отслеживания прогресса скачивания.
 */
public class PlayerDownloadController {

    private static final String TAG = "PlayerDownloadCtrl";

    private final FragmentActivity activity;
    private final DatabaseManager databaseManager;

    private View portraitDownloadProgressContainer;
    private TextView tvPortraitDownloadPercent;
    private ImageButton downloadButton;
    private ImageButton downloadButtonTop;
    private View btnDownloadFromMenu;
    private ImageButton portraitDownloadButton;

    private boolean isDownloading = false;
    private int currentDownloadPercent = 0;

    public interface DownloadCallback {
        String getAnimeId();
        String getAnimeTitle();
        String getPosterUrl();
        String getCurrentVideoUrl();
        void safeRunOnUiThread(Runnable runnable);
    }

    private DownloadCallback callback;

    public PlayerDownloadController(FragmentActivity activity, DatabaseManager databaseManager) {
        this.activity = activity;
        this.databaseManager = databaseManager;
    }

    public void setCallback(DownloadCallback callback) {
        this.callback = callback;
    }

    public void initViews(View rootView) {
        if (rootView == null) return;
        portraitDownloadProgressContainer = rootView.findViewById(R.id.portraitDownloadProgressContainer);
        tvPortraitDownloadPercent = rootView.findViewById(R.id.tvPortraitDownloadPercent);
        btnDownloadFromMenu = rootView.findViewById(R.id.btnDownloadFromMenu);
        portraitDownloadButton = rootView.findViewById(R.id.portraitDownloadButton);
    }

    public void setupDownloadListener(PlayersManager playersManager) {
        DownloadService.setListener(new DownloadService.ProgressListener() {
            @Override
            public void onProgress(int currentTaskIndex, int totalTasks, int taskPercent, String currentTaskTitle) {
                if (callback != null) {
                    callback.safeRunOnUiThread(() -> showDownloadProgress(taskPercent, playersManager));
                }
            }

            @Override
            public void onFinished(int totalDownloaded, int errorCount, boolean wasCancelled) {
                if (callback != null) {
                    callback.safeRunOnUiThread(() -> {
                        resetDownloadUi(playersManager);
                    });
                }
            }

            @Override
            public void onError(String message) {
                if (callback != null) {
                    callback.safeRunOnUiThread(() -> {
                        resetDownloadUi(playersManager);
                    });
                }
            }
        });

        if (DownloadService.isRunning()) {
            showDownloadProgress(DownloadService.getCurrentTaskPercent(), playersManager);
        } else {
            resetDownloadUi(playersManager);
        }
    }

    public void showDownloadProgress(int percent, PlayersManager playersManager) {
        isDownloading = true;
        currentDownloadPercent = percent;

        if (portraitDownloadProgressContainer != null) {
            portraitDownloadProgressContainer.setVisibility(View.VISIBLE);
            if (tvPortraitDownloadPercent != null) {
                if (percent > 0) {
                    tvPortraitDownloadPercent.setText("Скачивание: " + percent + "%");
                } else {
                    tvPortraitDownloadPercent.setText("Подготовка к скачиванию...");
                }
            }
        }

        if (downloadButton != null) {
            DownloadAnimationUtils.startDownloadAnimation(downloadButton);
        }
        if (downloadButtonTop != null) {
            DownloadAnimationUtils.startDownloadAnimation(downloadButtonTop);
        }
        if (btnDownloadFromMenu != null) {
            btnDownloadFromMenu.setAlpha(1.0f);
            DownloadAnimationUtils.startDownloadAnimation(btnDownloadFromMenu);
        }
        if (portraitDownloadButton != null) {
            portraitDownloadButton.setAlpha(1.0f);
            DownloadAnimationUtils.startDownloadAnimation(portraitDownloadButton);
        }

        if (playersManager != null) {
            playersManager.updateDownloadButtonState(true);
        }
    }

    public void resetDownloadUi(PlayersManager playersManager) {
        isDownloading = false;
        currentDownloadPercent = 0;

        if (portraitDownloadProgressContainer != null) {
            portraitDownloadProgressContainer.setVisibility(View.GONE);
        }

        if (downloadButton != null) {
            DownloadAnimationUtils.stopDownloadAnimation(downloadButton);
        }
        if (downloadButtonTop != null) {
            DownloadAnimationUtils.stopDownloadAnimation(downloadButtonTop);
        }
        if (btnDownloadFromMenu != null) {
            DownloadAnimationUtils.stopDownloadAnimation(btnDownloadFromMenu);
        }
        if (portraitDownloadButton != null) {
            DownloadAnimationUtils.stopDownloadAnimation(portraitDownloadButton);
        }

        if (playersManager != null) {
            playersManager.updateDownloadButtonState(false);
        }
    }

    public void showDownloadBottomSheet(EpisodesManager episodesManager, PlayersManager playersManager) {
        if (episodesManager == null || callback == null) return;
        List<EpisodesListResponse.EpisodeItem> episodeList = episodesManager.getEpisodes();
        if (episodeList == null || episodeList.isEmpty()) {
            return;
        }

        List<EpisodeResponse.PlayerData> players = playersManager != null ? playersManager.getAllPlayers() : new ArrayList<>();

        DownloadBottomSheet bottomSheet = DownloadBottomSheet.newInstance(
                callback.getAnimeId(),
                callback.getAnimeTitle(),
                callback.getPosterUrl(),
                episodeList,
                players
        );
        bottomSheet.show(activity.getSupportFragmentManager(), "DownloadBottomSheet");
    }

    public void showDownloadProgressBottomSheet() {
        DownloadProgressBottomSheet progressSheet = DownloadProgressBottomSheet.newInstance();
        progressSheet.show(activity.getSupportFragmentManager(), "DownloadProgressBottomSheet");
    }

    public DownloadedEpisodeEntity getDownloadedEpisodeForActive(String animeId, EpisodesManager episodesManager, PlayersManager playersManager, String localFilePath) {
        if (databaseManager == null) return null;

        if (localFilePath != null) {
            DownloadedEpisodeEntity dep = databaseManager.findEpisodeByPath(localFilePath);
            if (dep != null) return dep;
        }

        if (episodesManager == null) return null;
        EpisodesListResponse.EpisodeItem activeEp = episodesManager.getCurrentEpisode();
        if (activeEp == null) return null;

        int epId = activeEp.getId();
        String epNum = activeEp.getNumber();
        EpisodeResponse.PlayerData currentPlayerData = playersManager != null ? playersManager.getCurrentPlayerData() : null;
        String team = (currentPlayerData != null && currentPlayerData.getTeam() != null) ? currentPlayerData.getTeam().getName() : null;

        DownloadedEpisodeEntity downloaded = null;
        if (epId != 0) {
            downloaded = databaseManager.findDownloadedEpisode(animeId, epId, epNum, team);
        }
        if (downloaded == null && epNum != null) {
            downloaded = databaseManager.findDownloadedEpisode(animeId, epNum, team);
        }
        if (downloaded == null && animeId != null) {
            List<DownloadedEpisodeEntity> downloadedEps = databaseManager.getEpisodesForAnimeSync(animeId);
            if (downloadedEps != null) {
                for (DownloadedEpisodeEntity ep : downloadedEps) {
                    if (epId != 0 && ep.getEpisodeId() == epId) {
                        downloaded = ep;
                        break;
                    } else if (epNum != null && epNum.equals(ep.getEpisodeNumber())) {
                        downloaded = ep;
                        break;
                    }
                }
            }
        }
        return downloaded;
    }

    public List<String> getQualitiesWithDownloadedOption(List<String> onlineQualities, DownloadedEpisodeEntity downloadedEp) {
        List<String> qualities = new ArrayList<>();
        if (downloadedEp != null) {
            qualities.add("Скачанный файл (" + downloadedEp.getQuality() + ")");
        }
        if (onlineQualities != null && !onlineQualities.isEmpty()) {
            qualities.add("Авто");
            for (String q : onlineQualities) {
                if (!qualities.contains(q)) {
                    qualities.add(q);
                }
            }
        }
        return qualities;
    }

    public boolean isDownloadedQuality(String quality) {
        return com.example.animelib.util.AutoQualityHelper.isDownloadedQuality(quality);
    }

    public void cleanup() {
        DownloadService.setListener(null);
        if (btnDownloadFromMenu != null) {
            DownloadAnimationUtils.stopDownloadAnimation(btnDownloadFromMenu);
        }
        if (downloadButton != null) {
            DownloadAnimationUtils.stopDownloadAnimation(downloadButton);
        }
        if (downloadButtonTop != null) {
            DownloadAnimationUtils.stopDownloadAnimation(downloadButtonTop);
        }
        if (portraitDownloadButton != null) {
            DownloadAnimationUtils.stopDownloadAnimation(portraitDownloadButton);
        }
    }

    public boolean isDownloading() {
        return isDownloading;
    }

    public int getCurrentDownloadPercent() {
        return currentDownloadPercent;
    }
}
