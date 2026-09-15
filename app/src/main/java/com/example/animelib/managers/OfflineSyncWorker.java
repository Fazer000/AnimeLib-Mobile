package com.example.animelib.managers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.PendingSyncTaskEntity;

import java.util.List;

public class OfflineSyncWorker extends Worker {
    private static final String TAG = "OfflineSyncWorker";

    public OfflineSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting offline sync worker execution");
        Context context = getApplicationContext();
        DatabaseManager dbManager = new DatabaseManager(context);
        ApiService apiService = new ApiService(context);

        List<PendingSyncTaskEntity> tasks = dbManager.getAllPendingSyncTasksSync();
        if (tasks == null || tasks.isEmpty()) {
            Log.d(TAG, "No pending tasks to process");
            return Result.success();
        }

        Log.d(TAG, "Found " + tasks.size() + " pending tasks to sync");
        boolean hasFailures = false;

        for (PendingSyncTaskEntity task : tasks) {
            boolean success = false;
            try {
                if ("VIEW".equalsIgnoreCase(task.getTaskType())) {
                    Log.d(TAG, "Processing VIEW task for animeId: " + task.getAnimeId() + ", playerId: " + task.getPlayerId());
                    success = apiService.markEpisodeViewedSync(task.getAnimeId(), task.getPlayerId());
                } else if ("BOOKMARK".equalsIgnoreCase(task.getTaskType())) {
                    Log.d(TAG, "Processing BOOKMARK task for mediaSlug: " + task.getMediaSlug() + ", episodeId: " + task.getEpisodeId());
                    success = apiService.addBookmarkSync(
                            task.getMediaSlug(),
                            task.getEpisodeId(),
                            task.getTeamId(),
                            task.getEpisodeNumber(),
                            task.getTimecode(),
                            task.getStatusId()
                    );
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception processing task ID: " + task.getId(), e);
                success = false;
            }

            if (success) {
                Log.d(TAG, "Task ID " + task.getId() + " succeeded, removing from database");
                dbManager.deletePendingSyncTaskById(task.getId());
            } else {
                Log.w(TAG, "Task ID " + task.getId() + " failed, incrementing retry count");
                task.setRetryCount(task.getRetryCount() + 1);
                dbManager.updatePendingSyncTask(task);
                hasFailures = true;
            }
        }

        if (hasFailures) {
            Log.w(TAG, "Some tasks failed during sync, returning Result.retry()");
            return Result.retry();
        }

        Log.d(TAG, "All tasks synced successfully");
        return Result.success();
    }
}
