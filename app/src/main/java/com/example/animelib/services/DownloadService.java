package com.example.animelib.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.example.animelib.R;
import com.example.animelib.managers.DownloadsManager;
import com.example.animelib.models.DownloadTask;
import com.example.animelib.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Фоновый сервис скачивания с очередью тайтлов (скачивается только первый тайтл, остальные в ожидании).
 */
public class DownloadService extends Service {
    private static final String TAG = "DownloadService";
    private static final String CHANNEL_ID = "animelib_downloads_v2";
    private static final int PROGRESS_NOTIFICATION_ID = 4201;
    private static final int RESULT_NOTIFICATION_ID = 4202;

    public static final String ACTION_START_QUEUE = "com.example.animelib.action.DOWNLOAD_START_QUEUE";
    public static final String ACTION_PAUSE = "com.example.animelib.action.DOWNLOAD_PAUSE";
    public static final String ACTION_RESUME = "com.example.animelib.action.DOWNLOAD_RESUME";
    public static final String ACTION_CANCEL = "com.example.animelib.action.DOWNLOAD_CANCEL";
    public static final String ACTION_CANCEL_TITLE = "com.example.animelib.action.DOWNLOAD_CANCEL_TITLE";
    public static final String ACTION_REORDER = "com.example.animelib.action.DOWNLOAD_REORDER";

    private static final String EXTRA_TASKS = "download_tasks";
    private static final String EXTRA_ANIME_ID = "anime_id";
    private static final String EXTRA_ANIME_IDS = "anime_ids";

    public interface ProgressListener {
        void onProgress(int currentTaskIndex, int totalTasks, int taskPercent, String currentTaskTitle);
        void onFinished(int completedCount, int errorCount, boolean wasCancelled);
        void onError(String message);
    }

    public static class TaskProgressItem {
        public static final int STATUS_WAITING = 0;
        public static final int STATUS_DOWNLOADING = 1;
        public static final int STATUS_COMPLETED = 2;
        public static final int STATUS_ERROR = 3;

        public DownloadTask task;
        public int status = STATUS_WAITING;
        public int percent = 0;
        public String errorMessage;

        public TaskProgressItem(DownloadTask task) {
            this.task = task;
        }
    }

    public static class TitleQueueInfo {
        public final String animeId;
        public final String animeTitle;
        public final String posterUrl;
        public final int totalCount;
        public final int completedCount;
        public final int errorCount;
        public final int overallPercent;
        public final boolean isFirstInQueue;
        public final boolean isDownloading;
        public final boolean isPaused;

        public TitleQueueInfo(String animeId, String animeTitle, String posterUrl,
                              int totalCount, int completedCount, int errorCount,
                              int overallPercent, boolean isFirstInQueue,
                              boolean isDownloading, boolean isPaused) {
            this.animeId = animeId;
            this.animeTitle = animeTitle;
            this.posterUrl = posterUrl;
            this.totalCount = totalCount;
            this.completedCount = completedCount;
            this.errorCount = errorCount;
            this.overallPercent = overallPercent;
            this.isFirstInQueue = isFirstInQueue;
            this.isDownloading = isDownloading;
            this.isPaused = isPaused;
        }
    }

    public interface QueueProgressListener {
        void onQueueUpdated();
    }

    private static final Object queueLock = new Object();
    private static final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "download-cleanup-worker");
        t.setDaemon(true);
        return t;
    });

    private static volatile ProgressListener listener;
    private static final List<QueueProgressListener> queueProgressListeners = new CopyOnWriteArrayList<>();

    private static final List<String> titleOrder = new ArrayList<>();
    private static final List<TaskProgressItem> activeTaskItems = new ArrayList<>();

    private static volatile boolean running = false;
    private static volatile boolean cancelRequested = false;
    private static volatile int queueGeneration = 0;
    private static volatile TaskProgressItem currentProgressItem = null;
    private static volatile int currentTaskIndex = 0;
    private static volatile int totalTasks = 0;
    private static volatile int currentTaskPercent = 0;
    private static int batchCompletedCount = 0;
    private static int batchErrorCount = 0;

    private DownloadsManager downloadsManager;
    private NotificationManager notificationManager;
    private android.os.PowerManager.WakeLock wakeLock;

    public static void addQueueProgressListener(QueueProgressListener l) {
        if (l != null && !queueProgressListeners.contains(l)) {
            queueProgressListeners.add(l);
        }
    }

    public static void removeQueueProgressListener(QueueProgressListener l) {
        if (l != null) {
            queueProgressListeners.remove(l);
        }
    }

    public static void setQueueProgressListener(QueueProgressListener l) {
        if (l != null) {
            addQueueProgressListener(l);
        }
    }

    private static String getTitleKey(@Nullable DownloadTask task) {
        if (task == null || task.getAnimeId() == null || task.getAnimeId().trim().isEmpty()) {
            return "unknown";
        }
        return task.getAnimeId().trim();
    }

    public static List<TaskProgressItem> getActiveTaskItems() {
        synchronized (queueLock) {
            return new ArrayList<>(activeTaskItems);
        }
    }

    public static List<TaskProgressItem> getActiveTitleTaskItems() {
        synchronized (queueLock) {
            if (activeTaskItems.isEmpty()) {
                return Collections.emptyList();
            }
            if (titleOrder.isEmpty()) {
                return new ArrayList<>(activeTaskItems);
            }
            String firstId = titleOrder.get(0);
            List<TaskProgressItem> result = new ArrayList<>();
            for (TaskProgressItem item : activeTaskItems) {
                if (firstId.equals(getTitleKey(item.task))) {
                    result.add(item);
                }
            }
            return result;
        }
    }

    public static List<TaskProgressItem> getTaskItemsForTitle(@Nullable String animeId) {
        if (animeId == null || animeId.trim().isEmpty()) {
            return getActiveTitleTaskItems();
        }
        String target = animeId.trim();
        synchronized (queueLock) {
            if (activeTaskItems.isEmpty()) {
                return Collections.emptyList();
            }
            List<TaskProgressItem> result = new ArrayList<>();
            for (TaskProgressItem item : activeTaskItems) {
                if (target.equals(getTitleKey(item.task))) {
                    result.add(item);
                }
            }
            return result;
        }
    }

    public static boolean isTitleFirstInQueue(@Nullable String animeId) {
        if (animeId == null || animeId.trim().isEmpty()) return false;
        String target = animeId.trim();
        synchronized (queueLock) {
            if (titleOrder.isEmpty()) {
                return !activeTaskItems.isEmpty() && target.equals(getTitleKey(activeTaskItems.get(0).task));
            }
            return target.equals(titleOrder.get(0));
        }
    }

    public static boolean isTitleDownloading(@Nullable String animeId) {
        if (animeId == null || animeId.trim().isEmpty()) return false;
        String target = animeId.trim();
        synchronized (queueLock) {
            if (!running) return false;
            if (currentProgressItem != null && target.equals(getTitleKey(currentProgressItem.task))) {
                return true;
            }
            return !titleOrder.isEmpty() && target.equals(titleOrder.get(0)) && hasPendingQueueLocked();
        }
    }

    public static List<TitleQueueInfo> getTitleQueueInfos() {
        synchronized (queueLock) {
            if (activeTaskItems.isEmpty()) {
                return Collections.emptyList();
            }

            if (titleOrder.isEmpty()) {
                for (TaskProgressItem item : activeTaskItems) {
                    String key = getTitleKey(item.task);
                    if (!titleOrder.contains(key)) {
                        titleOrder.add(key);
                    }
                }
            }

            Map<String, List<TaskProgressItem>> grouped = new LinkedHashMap<>();
            for (String id : titleOrder) {
                grouped.put(id, new ArrayList<>());
            }
            for (TaskProgressItem item : activeTaskItems) {
                String key = getTitleKey(item.task);
                List<TaskProgressItem> list = grouped.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    grouped.put(key, list);
                }
                list.add(item);
            }

            List<TitleQueueInfo> result = new ArrayList<>();
            int pos = 0;
            for (Map.Entry<String, List<TaskProgressItem>> entry : grouped.entrySet()) {
                List<TaskProgressItem> items = entry.getValue();
                if (items.isEmpty()) continue;

                String animeId = entry.getKey();
                String title = null;
                String poster = null;
                int total = items.size();
                int completed = 0;
                int errors = 0;
                int inProgressPercentSum = 0;
                boolean hasPending = false;

                for (TaskProgressItem item : items) {
                    if (item.task != null) {
                        if ((title == null || title.trim().isEmpty() || title.startsWith("Аниме #"))
                                && item.task.getAnimeTitle() != null && !item.task.getAnimeTitle().trim().isEmpty()) {
                            title = item.task.getAnimeTitle().trim();
                        }
                        if ((poster == null || poster.trim().isEmpty())
                                && item.task.getPosterUrl() != null && !item.task.getPosterUrl().trim().isEmpty()) {
                            poster = item.task.getPosterUrl().trim();
                        }
                    }
                    if (item.status == TaskProgressItem.STATUS_COMPLETED) {
                        completed++;
                    } else if (item.status == TaskProgressItem.STATUS_ERROR) {
                        errors++;
                    } else {
                        hasPending = true;
                        inProgressPercentSum += Math.max(0, Math.min(99, item.percent));
                    }
                }

                if (!hasPending && result.size() < grouped.size() - 1) {
                    continue;
                }

                int overallPercent = total > 0
                        ? Math.min(100, (int) (((completed * 100.0) + inProgressPercentSum) / total))
                        : 0;
                boolean isFirst = (pos == 0);
                boolean isDownloading = isFirst && running;
                boolean isPaused = isFirst && !running;

                result.add(new TitleQueueInfo(
                        animeId,
                        title != null ? title : "Загрузка",
                        poster,
                        total,
                        completed,
                        errors,
                        overallPercent,
                        isFirst,
                        isDownloading,
                        isPaused
                ));
                pos++;
            }
            return result;
        }
    }

    public static boolean hasPendingQueue() {
        synchronized (queueLock) {
            for (TaskProgressItem item : activeTaskItems) {
                if (item.status == TaskProgressItem.STATUS_WAITING
                        || item.status == TaskProgressItem.STATUS_DOWNLOADING) {
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean isTitleInQueue(@Nullable String animeId) {
        if (animeId == null || animeId.trim().isEmpty()) return false;
        String target = animeId.trim();
        synchronized (queueLock) {
            for (TaskProgressItem item : activeTaskItems) {
                if (target.equals(getTitleKey(item.task))
                        && (item.status == TaskProgressItem.STATUS_WAITING
                        || item.status == TaskProgressItem.STATUS_DOWNLOADING)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static void startQueue(Context context, ArrayList<DownloadTask> tasks) {
        if (context == null || tasks == null || tasks.isEmpty()) return;
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_START_QUEUE);
        intent.putExtra(EXTRA_TASKS, tasks);
        context.startForegroundService(intent);
    }

    /**
     * Ставит очередь на паузу, сохраняя список задач и временные файлы (.part / _segs) для докачки.
     */
    public static void pause(Context context) {
        if (context == null || !running) return;
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_PAUSE);
        context.startService(intent);
    }

    /**
     * Возобновляет скачивание текущей очереди с паузы.
     */
    public static void resume(Context context) {
        if (context == null) return;
        if (running || !hasPendingQueue()) return;
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_RESUME);
        context.startForegroundService(intent);
    }

    /**
     * Полностью отменяет загрузку, очищает очередь и удаляет временные файлы незавершённых серий.
     */
    public static void cancel(Context context) {
        if (context == null) return;
        if (running) {
            Intent intent = new Intent(context, DownloadService.class);
            intent.setAction(ACTION_CANCEL);
            context.startService(intent);
            return;
        }

        // Очередь на паузе (сервис не запущен): очищаем очередь и временные файлы напрямую
        int completedCount = 0;
        int errorCount = 0;
        final List<DownloadTask> tasksToClean = new ArrayList<>();
        synchronized (queueLock) {
            cancelRequested = true;
            queueGeneration++;
            running = false;
            currentProgressItem = null;
            currentTaskIndex = 0;
            totalTasks = 0;
            currentTaskPercent = 0;

            completedCount = batchCompletedCount;
            errorCount = batchErrorCount;
            batchCompletedCount = 0;
            batchErrorCount = 0;

            for (TaskProgressItem item : activeTaskItems) {
                if (item.status == TaskProgressItem.STATUS_COMPLETED) {
                    completedCount++;
                } else {
                    if (item.status == TaskProgressItem.STATUS_ERROR) {
                        errorCount++;
                    }
                    if (item.task != null) {
                        tasksToClean.add(item.task);
                    }
                }
            }
            activeTaskItems.clear();
            titleOrder.clear();
        }

        final Context appCtx = context.getApplicationContext();
        if (!tasksToClean.isEmpty()) {
            cleanupExecutor.execute(() -> deleteTempFilesForTasks(appCtx, tasksToClean));
        }
        try {
            NotificationManager nm = (NotificationManager) appCtx.getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(PROGRESS_NOTIFICATION_ID);
                nm.cancel(RESULT_NOTIFICATION_ID);
            }
        } catch (Exception ignored) {}

        notifyQueueUpdatedStatic();
        ProgressListener l = listener;
        if (l != null) {
            l.onFinished(completedCount, errorCount, true);
        }
    }

    /**
     * Отменяет скачивание конкретного тайтла из очереди.
     */
    public static void cancelTitle(Context context, @Nullable String animeId) {
        if (context == null || animeId == null || animeId.trim().isEmpty()) return;
        String target = animeId.trim();
        if (running) {
            Intent intent = new Intent(context, DownloadService.class);
            intent.setAction(ACTION_CANCEL_TITLE);
            intent.putExtra(EXTRA_ANIME_ID, target);
            context.startService(intent);
            return;
        }

        final List<DownloadTask> tasksToClean = new ArrayList<>();
        boolean queueEmptyAfter;
        synchronized (queueLock) {
            titleOrder.remove(target);
            for (int i = activeTaskItems.size() - 1; i >= 0; i--) {
                TaskProgressItem item = activeTaskItems.get(i);
                if (target.equals(getTitleKey(item.task))) {
                    if (item.status != TaskProgressItem.STATUS_COMPLETED && item.task != null) {
                        tasksToClean.add(item.task);
                    }
                    activeTaskItems.remove(i);
                }
            }
            if (titleOrder.isEmpty() || !hasPendingQueueLocked()) {
                activeTaskItems.clear();
                titleOrder.clear();
                currentProgressItem = null;
                currentTaskIndex = 0;
                totalTasks = 0;
                currentTaskPercent = 0;
                batchCompletedCount = 0;
                batchErrorCount = 0;
                queueEmptyAfter = true;
            } else {
                queueEmptyAfter = false;
            }
        }

        final Context appCtx = context.getApplicationContext();
        if (!tasksToClean.isEmpty()) {
            cleanupExecutor.execute(() -> deleteTempFilesForTasks(appCtx, tasksToClean));
        }
        if (queueEmptyAfter) {
            try {
                NotificationManager nm = (NotificationManager) appCtx.getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(PROGRESS_NOTIFICATION_ID);
                    nm.cancel(RESULT_NOTIFICATION_ID);
                }
            } catch (Exception ignored) {}
        }
        notifyQueueUpdatedStatic();
    }

    /**
     * Применяет новый порядок тайтлов в очереди (после перетаскивания).
     * Если изменился первый тайтл во время активного скачивания, текущий ставится на паузу (с сохранением .part/_segs)
     * и запускается скачивание нового первого тайтла.
     */
    public static void reorderTitles(Context context, List<String> orderedAnimeIds) {
        if (context == null || orderedAnimeIds == null || orderedAnimeIds.isEmpty()) return;

        boolean needServiceSwitch = false;
        synchronized (queueLock) {
            if (titleOrder.isEmpty()) return;

            List<String> newOrder = new ArrayList<>();
            for (String id : orderedAnimeIds) {
                if (id != null && titleOrder.contains(id) && !newOrder.contains(id)) {
                    newOrder.add(id);
                }
            }
            for (String existing : titleOrder) {
                if (!newOrder.contains(existing)) {
                    newOrder.add(existing);
                }
            }

            if (newOrder.equals(titleOrder)) {
                return;
            }

            titleOrder.clear();
            titleOrder.addAll(newOrder);
            rebuildActiveTaskItemsByTitleOrderLocked();

            if (running && currentProgressItem != null && !titleOrder.isEmpty()) {
                String newFirst = titleOrder.get(0);
                String currentId = getTitleKey(currentProgressItem.task);
                if (!newFirst.equals(currentId)) {
                    needServiceSwitch = true;
                }
            }
        }

        notifyQueueUpdatedStatic();

        if (needServiceSwitch) {
            Intent intent = new Intent(context, DownloadService.class);
            intent.setAction(ACTION_REORDER);
            intent.putStringArrayListExtra(EXTRA_ANIME_IDS, new ArrayList<>(orderedAnimeIds));
            context.startService(intent);
        }
    }

    public static void clearQueue() {
        synchronized (queueLock) {
            activeTaskItems.clear();
            titleOrder.clear();
            currentProgressItem = null;
            currentTaskIndex = 0;
            totalTasks = 0;
            currentTaskPercent = 0;
            batchCompletedCount = 0;
            batchErrorCount = 0;
        }
        notifyQueueUpdatedStatic();
    }

    public static boolean isRunning() {
        return running;
    }

    public static int getCurrentTaskIndex() { return currentTaskIndex; }
    public static int getTotalTasks() { return totalTasks; }
    public static int getCurrentTaskPercent() { return currentTaskPercent; }

    public static void setListener(ProgressListener value) {
        listener = value;
    }

    private static void notifyQueueUpdatedStatic() {
        for (QueueProgressListener ql : queueProgressListeners) {
            try {
                ql.onQueueUpdated();
            } catch (Exception ignored) {}
        }
    }

    private static void deleteTempFilesForTasks(Context context, List<DownloadTask> tasks) {
        if (context == null || tasks == null) return;
        for (DownloadTask t : tasks) {
            if (t == null) continue;
            try {
                File outFile = DownloadsManager.resolveOutputFileForTask(context, t);
                FileUtils.deleteDownloadTempFiles(outFile.getAbsolutePath());
            } catch (Exception ignored) {}
        }
    }

    private static boolean hasPendingQueueLocked() {
        for (TaskProgressItem item : activeTaskItems) {
            if (item.status == TaskProgressItem.STATUS_WAITING
                    || item.status == TaskProgressItem.STATUS_DOWNLOADING) {
                return true;
            }
        }
        return false;
    }

    private static void rebuildActiveTaskItemsByTitleOrderLocked() {
        Map<String, List<TaskProgressItem>> grouped = new LinkedHashMap<>();
        for (String id : titleOrder) {
            grouped.put(id, new ArrayList<>());
        }
        for (TaskProgressItem item : activeTaskItems) {
            String key = getTitleKey(item.task);
            List<TaskProgressItem> list = grouped.get(key);
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(key, list);
            }
            list.add(item);
        }
        activeTaskItems.clear();
        for (List<TaskProgressItem> list : grouped.values()) {
            activeTaskItems.addAll(list);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        downloadsManager = new DownloadsManager(this);
        createChannel();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AnimeLIB:DownloadServiceWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null) {
            try {
                wakeLock.acquire(20 * 60 * 1000L);
            } catch (Exception ignored) {}
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
    }

    private void startForegroundCompat(int id, Notification notification) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                ServiceCompat.startForeground(this, id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } catch (Exception e) {
                ServiceCompat.startForeground(this, id, notification, 0);
            }
        } else {
            ServiceCompat.startForeground(this, id, notification, 0);
        }
    }

    private boolean isSameEpisodeTask(DownloadTask t1, DownloadTask t2) {
        if (t1 == null || t2 == null) return false;
        boolean sameAnime = Objects.equals(getTitleKey(t1), getTitleKey(t2));
        if (!sameAnime) return false;

        boolean sameTeam = Objects.equals(t1.getTeamName(), t2.getTeamName());
        if (!sameTeam) return false;

        if (t1.getEpisodeId() > 0 && t2.getEpisodeId() > 0) {
            return t1.getEpisodeId() == t2.getEpisodeId();
        }
        return Objects.equals(t1.getEpisodeNumber(), t2.getEpisodeNumber());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_PAUSE.equals(action)) {
            Log.d(TAG, "Pause requested (preserving partial files for resume)");
            int completedCount = 0;
            int errorCount = 0;
            synchronized (queueLock) {
                cancelRequested = true;
                queueGeneration++;
                running = false;
                currentProgressItem = null;
                completedCount = batchCompletedCount;
                errorCount = batchErrorCount;
                for (TaskProgressItem item : activeTaskItems) {
                    if (item.status == TaskProgressItem.STATUS_COMPLETED) {
                        completedCount++;
                    } else if (item.status == TaskProgressItem.STATUS_ERROR) {
                        errorCount++;
                    } else if (item.status == TaskProgressItem.STATUS_DOWNLOADING) {
                        item.status = TaskProgressItem.STATUS_WAITING;
                    }
                }
            }
            if (downloadsManager != null) {
                downloadsManager.pause();
            }
            releaseWakeLock();
            showResultNotification("Скачивание на паузе", completedCount > 0
                    ? ("Скачано серий: " + completedCount + ". Можно продолжить в загрузках")
                    : "Нажмите «Продолжить» в разделе загрузок");
            notifyQueueUpdated();
            stopAfterResult();
            ProgressListener l = listener;
            if (l != null) {
                l.onFinished(completedCount, errorCount, true);
            }
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(action)) {
            Log.d(TAG, "Cancel requested");
            int completedCount;
            int errorCount;
            final List<DownloadTask> tasksToCleanTemp = new ArrayList<>();
            synchronized (queueLock) {
                cancelRequested = true;
                queueGeneration++;
                running = false;
                currentProgressItem = null;
                currentTaskIndex = 0;
                totalTasks = 0;
                currentTaskPercent = 0;
                completedCount = batchCompletedCount;
                errorCount = batchErrorCount;
                batchCompletedCount = 0;
                batchErrorCount = 0;

                for (TaskProgressItem item : activeTaskItems) {
                    if (item.status == TaskProgressItem.STATUS_COMPLETED) {
                        completedCount++;
                    } else {
                        if (item.status == TaskProgressItem.STATUS_ERROR) {
                            errorCount++;
                        }
                        if (item.task != null) {
                            tasksToCleanTemp.add(item.task);
                        }
                    }
                }
                activeTaskItems.clear();
                titleOrder.clear();
            }

            if (downloadsManager != null) {
                downloadsManager.cancel();
            }
            if (!tasksToCleanTemp.isEmpty()) {
                final Context appCtx = getApplicationContext();
                cleanupExecutor.execute(() -> deleteTempFilesForTasks(appCtx, tasksToCleanTemp));
            }
            releaseWakeLock();

            showResultNotification("Скачивание остановлено", completedCount > 0 ?
                    ("Скачано серий: " + completedCount) : "Загрузка отменена");
            notifyQueueUpdated();
            stopAfterResult();
            ProgressListener l = listener;
            if (l != null) {
                l.onFinished(completedCount, errorCount, true);
            }
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL_TITLE.equals(action)) {
            String targetAnimeId = intent.getStringExtra(EXTRA_ANIME_ID);
            if (targetAnimeId == null || targetAnimeId.trim().isEmpty()) {
                return START_NOT_STICKY;
            }
            String target = targetAnimeId.trim();

            final List<DownloadTask> tasksToCleanTemp = new ArrayList<>();
            boolean wasActiveTitle = false;
            boolean queueNowEmpty = false;
            int finalCompleted = 0;
            int finalErrors = 0;

            synchronized (queueLock) {
                if (currentProgressItem != null && target.equals(getTitleKey(currentProgressItem.task))) {
                    wasActiveTitle = true;
                }
                titleOrder.remove(target);
                for (int i = activeTaskItems.size() - 1; i >= 0; i--) {
                    TaskProgressItem item = activeTaskItems.get(i);
                    if (target.equals(getTitleKey(item.task))) {
                        if (item.status != TaskProgressItem.STATUS_COMPLETED && item.task != null) {
                            tasksToCleanTemp.add(item.task);
                        }
                        activeTaskItems.remove(i);
                    }
                }

                if (titleOrder.isEmpty() || !hasPendingQueueLocked()) {
                    queueNowEmpty = true;
                    cancelRequested = true;
                    queueGeneration++;
                    running = false;
                    currentProgressItem = null;
                    currentTaskIndex = 0;
                    totalTasks = 0;
                    currentTaskPercent = 0;
                    finalCompleted = batchCompletedCount;
                    finalErrors = batchErrorCount;
                    batchCompletedCount = 0;
                    batchErrorCount = 0;
                    for (TaskProgressItem item : activeTaskItems) {
                        if (item.status == TaskProgressItem.STATUS_COMPLETED) finalCompleted++;
                        else if (item.status == TaskProgressItem.STATUS_ERROR) finalErrors++;
                    }
                    activeTaskItems.clear();
                    titleOrder.clear();
                } else if (wasActiveTitle) {
                    queueGeneration++;
                    currentProgressItem = null;
                }
            }

            if (queueNowEmpty) {
                if (downloadsManager != null) {
                    downloadsManager.cancel();
                }
                if (!tasksToCleanTemp.isEmpty()) {
                    final Context appCtx = getApplicationContext();
                    cleanupExecutor.execute(() -> deleteTempFilesForTasks(appCtx, tasksToCleanTemp));
                }
                releaseWakeLock();
                showResultNotification("Скачивание остановлено", finalCompleted > 0 ?
                        ("Скачано серий: " + finalCompleted) : "Загрузка отменена");
                notifyQueueUpdated();
                stopAfterResult();
                ProgressListener l = listener;
                if (l != null) {
                    l.onFinished(finalCompleted, finalErrors, true);
                }
            } else {
                if (wasActiveTitle) {
                    if (downloadsManager != null) {
                        downloadsManager.cancel();
                    }
                    if (!tasksToCleanTemp.isEmpty()) {
                        final Context appCtx = getApplicationContext();
                        cleanupExecutor.execute(() -> deleteTempFilesForTasks(appCtx, tasksToCleanTemp));
                    }
                    startNextTask();
                } else {
                    if (!tasksToCleanTemp.isEmpty()) {
                        final Context appCtx = getApplicationContext();
                        cleanupExecutor.execute(() -> deleteTempFilesForTasks(appCtx, tasksToCleanTemp));
                    }
                    notifyQueueUpdated();
                }
            }
            return START_NOT_STICKY;
        }

        if (ACTION_REORDER.equals(action)) {
            boolean needSwitch = false;
            synchronized (queueLock) {
                if (running && !titleOrder.isEmpty() && currentProgressItem != null) {
                    String newFirst = titleOrder.get(0);
                    String currentId = getTitleKey(currentProgressItem.task);
                    if (!newFirst.equals(currentId)) {
                        queueGeneration++;
                        if (currentProgressItem.status == TaskProgressItem.STATUS_DOWNLOADING) {
                            currentProgressItem.status = TaskProgressItem.STATUS_WAITING;
                        }
                        currentProgressItem = null;
                        needSwitch = true;
                    }
                }
            }
            if (needSwitch) {
                if (downloadsManager != null) {
                    downloadsManager.pause();
                }
                startNextTask();
            } else {
                notifyQueueUpdated();
            }
            return START_NOT_STICKY;
        }

        if (ACTION_RESUME.equals(action)) {
            startForegroundCompat(PROGRESS_NOTIFICATION_ID, buildProgressNotification("Возобновление скачивания...", 0));
            boolean shouldStart = false;
            synchronized (queueLock) {
                if (!running && hasPendingQueueLocked()) {
                    running = true;
                    cancelRequested = false;
                    queueGeneration++;
                    shouldStart = true;
                }
            }
            if (shouldStart) {
                acquireWakeLock();
                startNextTask();
                notifyQueueUpdated();
            } else if (!running) {
                stopAfterResult();
            }
            return START_NOT_STICKY;
        }

        if (ACTION_START_QUEUE.equals(action)) {
            startForegroundCompat(PROGRESS_NOTIFICATION_ID, buildProgressNotification("Подготовка к скачиванию...", 0));
            @SuppressWarnings("unchecked")
            ArrayList<DownloadTask> tasks = (ArrayList<DownloadTask>) intent.getSerializableExtra(EXTRA_TASKS);
            if (tasks != null && !tasks.isEmpty()) {
                boolean shouldStartNext = false;
                boolean hadNewTasks;
                synchronized (queueLock) {
                    if (!running && !hasPendingQueueLocked()) {
                        activeTaskItems.clear();
                        titleOrder.clear();
                        batchCompletedCount = 0;
                        batchErrorCount = 0;
                    }

                    int addedCount = 0;
                    for (DownloadTask t : tasks) {
                        if (t == null) continue;
                        boolean isDuplicate = false;
                        for (int i = activeTaskItems.size() - 1; i >= 0; i--) {
                            TaskProgressItem existingItem = activeTaskItems.get(i);
                            if (existingItem.task != null && isSameEpisodeTask(existingItem.task, t)) {
                                if (existingItem.status == TaskProgressItem.STATUS_WAITING ||
                                        existingItem.status == TaskProgressItem.STATUS_DOWNLOADING ||
                                        existingItem.status == TaskProgressItem.STATUS_COMPLETED) {
                                    isDuplicate = true;
                                    break;
                                } else if (existingItem.status == TaskProgressItem.STATUS_ERROR) {
                                    activeTaskItems.remove(i);
                                }
                            }
                        }
                        if (!isDuplicate) {
                            String key = getTitleKey(t);
                            if (!titleOrder.contains(key)) {
                                titleOrder.add(key);
                            }
                            activeTaskItems.add(new TaskProgressItem(t));
                            addedCount++;
                        }
                    }

                    hadNewTasks = addedCount > 0;
                    if (hadNewTasks) {
                        rebuildActiveTaskItemsByTitleOrderLocked();
                        if (!running) {
                            running = true;
                            cancelRequested = false;
                            queueGeneration++;
                            currentTaskIndex = 0;
                            shouldStartNext = true;
                        }
                    }
                }

                if (hadNewTasks) {
                    acquireWakeLock();
                    if (shouldStartNext) {
                        startNextTask();
                    } else {
                        notifyQueueUpdated();
                    }
                } else {
                    Log.d(TAG, "All enqueued tasks are duplicates and skipped.");
                    if (!running) {
                        stopAfterResult();
                    }
                }
            } else if (!running) {
                stopAfterResult();
            }
        }

        return START_NOT_STICKY;
    }

    private boolean isGenerationCurrent(int expectedGen) {
        synchronized (queueLock) {
            return expectedGen == queueGeneration && running && !cancelRequested;
        }
    }

    private void notifyQueueUpdated() {
        notifyQueueUpdatedStatic();
    }

    private void startNextTask() {
        final DownloadTask task;
        final TaskProgressItem capturedItem;
        final int taskGeneration;
        boolean shouldFinish = false;
        boolean wasCancelledFinish = false;
        int completedCount = 0;
        int errorCount = 0;

        synchronized (queueLock) {
            if (cancelRequested) {
                running = false;
                currentProgressItem = null;
                return;
            }

            // Удаляем с начала очереди уже полностью завершённые тайтлы, если после них есть ещё тайтлы в ожидании
            while (!titleOrder.isEmpty()) {
                String headId = titleOrder.get(0);
                boolean headHasPending = false;
                for (TaskProgressItem item : activeTaskItems) {
                    if (headId.equals(getTitleKey(item.task))
                            && (item.status == TaskProgressItem.STATUS_WAITING
                            || item.status == TaskProgressItem.STATUS_DOWNLOADING)) {
                        headHasPending = true;
                        break;
                    }
                }
                if (headHasPending) {
                    break;
                }
                // Проверим, есть ли ещё другие тайтлы в очереди после headId
                if (titleOrder.size() > 1) {
                    titleOrder.remove(0);
                    for (int i = activeTaskItems.size() - 1; i >= 0; i--) {
                        TaskProgressItem item = activeTaskItems.get(i);
                        if (headId.equals(getTitleKey(item.task))) {
                            if (item.status == TaskProgressItem.STATUS_COMPLETED) batchCompletedCount++;
                            else if (item.status == TaskProgressItem.STATUS_ERROR) batchErrorCount++;
                            activeTaskItems.remove(i);
                        }
                    }
                } else {
                    break;
                }
            }

            TaskProgressItem nextWaiting = null;
            if (!titleOrder.isEmpty()) {
                String activeAnimeId = titleOrder.get(0);
                for (TaskProgressItem item : activeTaskItems) {
                    if (activeAnimeId.equals(getTitleKey(item.task))
                            && item.status == TaskProgressItem.STATUS_WAITING) {
                        nextWaiting = item;
                        break;
                    }
                }
            }

            if (nextWaiting == null) {
                running = false;
                currentProgressItem = null;
                wasCancelledFinish = cancelRequested;
                shouldFinish = true;
                completedCount = batchCompletedCount;
                errorCount = batchErrorCount;
                for (TaskProgressItem item : activeTaskItems) {
                    if (item.status == TaskProgressItem.STATUS_COMPLETED) {
                        completedCount++;
                    } else if (item.status == TaskProgressItem.STATUS_ERROR) {
                        errorCount++;
                    }
                }
                batchCompletedCount = 0;
                batchErrorCount = 0;
                activeTaskItems.clear();
                titleOrder.clear();
                currentTaskIndex = 0;
                totalTasks = 0;
                currentTaskPercent = 0;
                task = null;
                capturedItem = null;
                taskGeneration = queueGeneration;
            } else {
                nextWaiting.status = TaskProgressItem.STATUS_DOWNLOADING;
                currentProgressItem = nextWaiting;
                capturedItem = nextWaiting;
                task = nextWaiting.task;
                taskGeneration = queueGeneration;

                String activeAnimeId = getTitleKey(task);
                int titleTotal = 0;
                int titleDone = 0;
                for (TaskProgressItem item : activeTaskItems) {
                    if (activeAnimeId.equals(getTitleKey(item.task))) {
                        titleTotal++;
                        if (item.status == TaskProgressItem.STATUS_COMPLETED
                                || item.status == TaskProgressItem.STATUS_ERROR) {
                            titleDone++;
                        }
                    }
                }
                totalTasks = Math.max(1, titleTotal);
                currentTaskIndex = Math.min(totalTasks, titleDone + 1);
                currentTaskPercent = Math.max(0, Math.min(99, nextWaiting.percent));
            }
        }

        if (shouldFinish) {
            if (wasCancelledFinish) {
                showResultNotification("Скачивание остановлено", completedCount > 0 ?
                        ("Скачано серий: " + completedCount) : "Загрузка отменена");
            } else if (errorCount > 0) {
                if (completedCount > 0) {
                    showResultNotification("Скачивание завершено с ошибками",
                            "Успешно: " + completedCount + ", ошибок: " + errorCount);
                } else {
                    showResultNotification("Ошибка скачивания",
                            "Не удалось скачать серии (" + errorCount + " с ошибкой)");
                }
            } else {
                showResultNotification("Скачивание завершено",
                        "Все выбранные серии (" + completedCount + ") успешно скачаны");
            }

            notifyQueueUpdated();
            stopAfterResult();
            ProgressListener l = listener;
            if (l != null) {
                l.onFinished(completedCount, errorCount, wasCancelledFinish);
            }
            return;
        }

        acquireWakeLock();
        notifyQueueUpdated();

        String taskTitle = (task.getAnimeTitle() != null ? task.getAnimeTitle() : "Аниме") + " - Серия " + task.getEpisodeNumber();
        startForegroundCompat(PROGRESS_NOTIFICATION_ID, buildProgressNotification(taskTitle, currentTaskPercent));

        final long[] lastNotifTime = new long[1];

        downloadsManager.downloadTask(task, new DownloadsManager.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                synchronized (queueLock) {
                    if (taskGeneration != queueGeneration || !running || cancelRequested) return;
                    currentTaskPercent = percent;
                    if (capturedItem != null) {
                        capturedItem.percent = percent;
                    }
                }
                notifyQueueUpdated();
                long now = System.currentTimeMillis();
                if (now - lastNotifTime[0] > 500 || percent == 100 || percent == 0) {
                    lastNotifTime[0] = now;
                    notificationManager.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(taskTitle, percent));
                }
                ProgressListener l = listener;
                if (l != null) {
                    l.onProgress(currentTaskIndex, totalTasks, percent, taskTitle);
                }
            }

            @Override
            public void onFinished(String localPath) {
                synchronized (queueLock) {
                    if (taskGeneration != queueGeneration || !running || cancelRequested) return;
                    if (capturedItem != null) {
                        capturedItem.status = TaskProgressItem.STATUS_COMPLETED;
                        capturedItem.percent = 100;
                    }
                }
                Log.d(TAG, "Task finished: " + taskTitle);
                notifyQueueUpdated();
                startNextTask();
            }

            @Override
            public void onError(String message) {
                synchronized (queueLock) {
                    if (taskGeneration != queueGeneration || !running || cancelRequested) return;
                    if (capturedItem != null) {
                        capturedItem.status = TaskProgressItem.STATUS_ERROR;
                        capturedItem.errorMessage = message;
                    }
                }
                Log.e(TAG, "Task error: " + message);
                notifyQueueUpdated();
                ProgressListener l = listener;
                if (l != null) {
                    l.onError(message);
                }
                startNextTask();
            }
        });
    }

    @Override
    public void onDestroy() {
        synchronized (queueLock) {
            running = false;
            cancelRequested = true;
            queueGeneration++;
            currentProgressItem = null;
        }
        releaseWakeLock();
        if (downloadsManager != null) {
            downloadsManager.cleanup();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (notificationManager == null) return;
        try {
            notificationManager.deleteNotificationChannel("animelib_downloads");
        } catch (Exception ignored) {}

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Скачивание", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Прогресс скачивания серий");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildProgressNotification(String title, int percent) {
        Intent pauseIntent = new Intent(this, DownloadService.class).setAction(ACTION_PAUSE);
        PendingIntent pausePending = PendingIntent.getService(this, 1, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent cancelIntent = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPending = PendingIntent.getService(this, 0, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent contentIntent = new Intent(this, com.example.animelib.ui.DownloadsActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPending = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String subtext = "Серия " + currentTaskIndex + " из " + totalTasks + " (" + percent + "%)";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(subtext)
                .setContentIntent(contentPending)
                .setProgress(100, percent, percent == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSound(null)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(R.drawable.ic_pause, "Пауза", pausePending)
                .addAction(R.drawable.ic_close, "Отмена", cancelPending);

        return builder.build();
    }

    private void showResultNotification(String title, String text) {
        Intent contentIntent = new Intent(this, com.example.animelib.ui.DownloadsActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPending = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build());
    }

    private void stopAfterResult() {
        releaseWakeLock();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
