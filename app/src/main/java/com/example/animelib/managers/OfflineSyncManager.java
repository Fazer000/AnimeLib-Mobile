package com.example.animelib.managers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.PendingSyncTaskEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OfflineSyncManager {
    private static final String TAG = "OfflineSyncManager";
    private static final String WORK_NAME = "offline_sync_work";
    private static volatile OfflineSyncManager instance;

    private final Context context;
    private final DatabaseManager databaseManager;
    private final ApiService apiService;
    private final ExecutorService executor;
    private boolean isNetworkCallbackRegistered = false;

    private OfflineSyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.databaseManager = new DatabaseManager(this.context);
        this.apiService = new ApiService(this.context);
        this.executor = Executors.newSingleThreadExecutor();
        registerNetworkCallback();
    }

    public static OfflineSyncManager getInstance(Context context) {
        if (instance == null) {
            synchronized (OfflineSyncManager.class) {
                if (instance == null) {
                    instance = new OfflineSyncManager(context);
                }
            }
        }
        return instance;
    }

    public static boolean isNetworkAvailable(Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return false;
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
            return capabilities != null &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            Log.e(TAG, "Error checking network availability", e);
            return false;
        }
    }

    public void enqueueViewTask(String animeId, int playerId) {
        if (animeId == null || animeId.isEmpty() || playerId <= 0) {
            Log.e(TAG, "Cannot enqueue VIEW task with invalid parameters: animeId=" + animeId + ", playerId=" + playerId);
            return;
        }

        executor.execute(() -> {
            boolean success = false;
            if (isNetworkAvailable(context)) {
                Log.d(TAG, "Network available, trying to send VIEW request immediately");
                success = apiService.markEpisodeViewedSync(animeId, playerId);
            }

            if (!success) {
                Log.w(TAG, "VIEW request offline or failed. Queueing pending task in database");
                PendingSyncTaskEntity task = PendingSyncTaskEntity.createViewTask(animeId, playerId);
                databaseManager.savePendingSyncTask(task);
                scheduleWorkManagerSync();
            } else {
                Log.d(TAG, "VIEW request sent successfully on first try");
            }
        });
    }

    private static final long MAX_BOOKMARK_TASK_AGE_MS = 24L * 60L * 60L * 1000L; // 24 часа

    public void saveOrReplacePendingBookmarkTask(String mediaSlug, int episodeId, int teamId,
                                                 int episodeNumber, String timecode, Object statusId) {
        if (mediaSlug == null || mediaSlug.trim().isEmpty() || episodeId <= 0) {
            return;
        }
        executor.execute(() -> {
            try {
                java.util.List<PendingSyncTaskEntity> tasks = databaseManager.getAllPendingSyncTasksSync();
                if (tasks != null) {
                    for (PendingSyncTaskEntity existing : tasks) {
                        if ("BOOKMARK".equalsIgnoreCase(existing.getTaskType())
                                && mediaSlug.equalsIgnoreCase(existing.getMediaSlug())) {
                            databaseManager.deletePendingSyncTaskById(existing.getId());
                        }
                    }
                }
                PendingSyncTaskEntity task = PendingSyncTaskEntity.createBookmarkTask(
                        mediaSlug, episodeId, teamId, episodeNumber, timecode, statusId
                );
                databaseManager.savePendingSyncTask(task);
                scheduleWorkManagerSync();
                Log.d(TAG, "Queued fallback BOOKMARK sync task for " + mediaSlug + " ep=" + episodeId + " (" + timecode + ")");
            } catch (Exception e) {
                Log.e(TAG, "Error saving pending BOOKMARK task", e);
            }
        });
    }

    public void clearPendingBookmarkTasksForSlug(String mediaSlug) {
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                java.util.List<PendingSyncTaskEntity> tasks = databaseManager.getAllPendingSyncTasksSync();
                if (tasks != null) {
                    for (PendingSyncTaskEntity existing : tasks) {
                        if ("BOOKMARK".equalsIgnoreCase(existing.getTaskType())
                                && mediaSlug.equalsIgnoreCase(existing.getMediaSlug())) {
                            databaseManager.deletePendingSyncTaskById(existing.getId());
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error clearing pending BOOKMARK tasks for " + mediaSlug, e);
            }
        });
    }

    public void syncPendingTasksNow() {
        executor.execute(() -> {
            if (!isNetworkAvailable(context)) {
                Log.d(TAG, "syncPendingTasksNow called but network is not available");
                return;
            }

            Log.d(TAG, "syncPendingTasksNow running on background thread");
            java.util.List<PendingSyncTaskEntity> tasks = databaseManager.getAllPendingSyncTasksSync();
            if (tasks == null || tasks.isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();
            Log.d(TAG, "Processing " + tasks.size() + " pending tasks in syncPendingTasksNow");
            for (PendingSyncTaskEntity task : tasks) {
                if ("VIEW".equalsIgnoreCase(task.getTaskType())) {
                    boolean success = false;
                    try {
                        success = apiService.markEpisodeViewedSync(task.getAnimeId(), task.getPlayerId());
                    } catch (Exception e) {
                        Log.e(TAG, "Error syncing VIEW task ID " + task.getId(), e);
                    }

                    if (success) {
                        databaseManager.deletePendingSyncTaskById(task.getId());
                    } else {
                        task.setRetryCount(task.getRetryCount() + 1);
                        databaseManager.updatePendingSyncTask(task);
                    }
                } else if ("BOOKMARK".equalsIgnoreCase(task.getTaskType())) {
                    if (task.getMediaSlug() == null || task.getEpisodeId() <= 0
                            || (task.getCreatedAt() > 0 && now - task.getCreatedAt() > MAX_BOOKMARK_TASK_AGE_MS)
                            || task.getRetryCount() >= 5) {
                        databaseManager.deletePendingSyncTaskById(task.getId());
                        continue;
                    }
                    try {
                        ApiService.BookmarkSaveResult res = apiService.addBookmarkSync(
                                task.getMediaSlug(),
                                task.getEpisodeId(),
                                task.getTeamId(),
                                2,
                                "Animelib",
                                task.getEpisodeNumber(),
                                task.getTimecode(),
                                task.getStatusId()
                        );
                        if (res.success || !res.retryable) {
                            databaseManager.deletePendingSyncTaskById(task.getId());
                        } else {
                            task.setRetryCount(task.getRetryCount() + 1);
                            databaseManager.updatePendingSyncTask(task);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error syncing BOOKMARK task ID " + task.getId(), e);
                        task.setRetryCount(task.getRetryCount() + 1);
                        databaseManager.updatePendingSyncTask(task);
                    }
                } else {
                    databaseManager.deletePendingSyncTaskById(task.getId());
                }
            }
        });
    }

    public void scheduleWorkManagerSync() {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            OneTimeWorkRequest syncWorkRequest = new OneTimeWorkRequest.Builder(OfflineSyncWorker.class)
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            10,
                            TimeUnit.SECONDS
                    )
                    .build();

            WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    syncWorkRequest
            );
            Log.d(TAG, "Scheduled WorkManager offline sync task successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling WorkManager task", e);
        }
    }

    private void registerNetworkCallback() {
        if (isNetworkCallbackRegistered) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    Log.d(TAG, "Network became available! Triggering syncPendingTasksNow");
                    syncPendingTasksNow();
                }
            });
            isNetworkCallbackRegistered = true;
            Log.d(TAG, "Registered network callback for immediate sync on reconnection");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }
}
