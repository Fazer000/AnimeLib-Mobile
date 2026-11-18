package com.example.animelib.managers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

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
        OfflineSyncManager syncManager = OfflineSyncManager.getInstance(context);
        OfflineSyncManager.SyncPassResult result = syncManager.executeWorkerSyncBlocking();

        if (result.hasRetryableFailure) {
            Log.w(TAG, "Some tasks failed with retryable error during sync, returning Result.retry()");
            return Result.retry();
        }

        Log.d(TAG, "Offline sync worker completed (processed=" + result.processedCount + ")");
        return Result.success();
    }
}
