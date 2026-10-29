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
import androidx.core.content.ContextCompat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import com.example.animelib.R;
import com.example.animelib.managers.DownloadsManager;
import com.example.animelib.models.DownloadTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Фоновый сервис скачивания списка серий
 */
public class DownloadService extends Service {
    private static final String TAG = "DownloadService";
    private static final String CHANNEL_ID = "animelib_downloads_v2";
    private static final int PROGRESS_NOTIFICATION_ID = 4201;
    private static final int RESULT_NOTIFICATION_ID = 4202;

    public static final String ACTION_START_QUEUE = "com.example.animelib.action.DOWNLOAD_START_QUEUE";
    public static final String ACTION_CANCEL = "com.example.animelib.action.DOWNLOAD_CANCEL";
    private static final String EXTRA_TASKS = "download_tasks";

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

    public interface QueueProgressListener {
        void onQueueUpdated();
    }

    private static volatile ProgressListener listener;
    private static final List<QueueProgressListener> queueProgressListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean running;
    private static volatile int currentTaskIndex;
    private static volatile int totalTasks;
    private static volatile int currentTaskPercent;

    private static final List<TaskProgressItem> activeTaskItems = java.util.Collections.synchronizedList(new ArrayList<>());

    public static void addQueueProgressListener(QueueProgressListener listener) {
        if (listener != null && !queueProgressListeners.contains(listener)) {
            queueProgressListeners.add(listener);
        }
    }

    public static void removeQueueProgressListener(QueueProgressListener listener) {
        if (listener != null) {
            queueProgressListeners.remove(listener);
        }
    }

    public static void setQueueProgressListener(QueueProgressListener listener) {
        if (listener != null) {
            addQueueProgressListener(listener);
        }
    }

    public static List<TaskProgressItem> getActiveTaskItems() {
        synchronized (activeTaskItems) {
            return new ArrayList<>(activeTaskItems);
        }
    }

    private DownloadsManager downloadsManager;
    private NotificationManager notificationManager;
    private android.os.PowerManager.WakeLock wakeLock;
    private boolean cancelRequested;
    private final List<DownloadTask> taskQueue = new ArrayList<>();

    public static void startQueue(Context context, ArrayList<DownloadTask> tasks) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_START_QUEUE);
        intent.putExtra(EXTRA_TASKS, tasks);
        context.startForegroundService(intent);
    }

    public static void cancel(Context context) {
        if (!running) return;
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_CANCEL);
        context.startService(intent);
    }

    public static void clearQueue() {
        synchronized (activeTaskItems) {
            activeTaskItems.clear();
        }
        for (QueueProgressListener ql : queueProgressListeners) {
            try {
                ql.onQueueUpdated();
            } catch (Exception ignored) {}
        }
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
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire(15 * 60 * 1000L); // 15 mins
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
        boolean sameAnime = java.util.Objects.equals(t1.getAnimeId(), t2.getAnimeId());
        if (!sameAnime) return false;

        if (t1.getEpisodeId() > 0 && t2.getEpisodeId() > 0) {
            return t1.getEpisodeId() == t2.getEpisodeId();
        }
        return java.util.Objects.equals(t1.getEpisodeNumber(), t2.getEpisodeNumber());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_CANCEL.equals(action)) {
            Log.d(TAG, "Cancel requested");
            cancelRequested = true;
            downloadsManager.cancel();
            taskQueue.clear();
            running = false;
            releaseWakeLock();

            int completedCount = 0;
            int errorCount = 0;
            synchronized (activeTaskItems) {
                for (TaskProgressItem item : activeTaskItems) {
                    if (item.status == TaskProgressItem.STATUS_COMPLETED) {
                        completedCount++;
                    } else if (item.status == TaskProgressItem.STATUS_ERROR) {
                        errorCount++;
                    }
                }
            }

            showResultNotification("Скачивание остановлено", completedCount > 0 ?
                    ("Скачано серий: " + completedCount) : "Загрузка отменена");
            notifyQueueUpdated();
            stopAfterResult();
            if (listener != null) {
                listener.onFinished(completedCount, errorCount, true);
            }
            return START_NOT_STICKY;
        }

        if (ACTION_START_QUEUE.equals(action)) {
            @SuppressWarnings("unchecked")
            ArrayList<DownloadTask> tasks = (ArrayList<DownloadTask>) intent.getSerializableExtra(EXTRA_TASKS);
            if (tasks != null && !tasks.isEmpty()) {
                if (!running) {
                    activeTaskItems.clear();
                }

                ArrayList<DownloadTask> newTasksToEnqueue = new ArrayList<>();
                synchronized (activeTaskItems) {
                    for (DownloadTask t : tasks) {
                        boolean isDuplicate = false;
                        for (TaskProgressItem existingItem : activeTaskItems) {
                            if (existingItem.task != null && isSameEpisodeTask(existingItem.task, t)) {
                                if (existingItem.status == TaskProgressItem.STATUS_WAITING ||
                                    existingItem.status == TaskProgressItem.STATUS_DOWNLOADING ||
                                    existingItem.status == TaskProgressItem.STATUS_COMPLETED) {
                                    isDuplicate = true;
                                    break;
                                }
                            }
                        }
                        if (!isDuplicate) {
                            for (DownloadTask queuedTask : taskQueue) {
                                if (isSameEpisodeTask(queuedTask, t)) {
                                    isDuplicate = true;
                                    break;
                                }
                            }
                        }
                        if (!isDuplicate) {
                            newTasksToEnqueue.add(t);
                            activeTaskItems.add(new TaskProgressItem(t));
                        }
                    }
                }

                if (!newTasksToEnqueue.isEmpty()) {
                    taskQueue.addAll(newTasksToEnqueue);
                    acquireWakeLock();
                    startForegroundCompat(PROGRESS_NOTIFICATION_ID, buildProgressNotification("Подготовка к скачиванию...", 0));
                    if (!running) {
                        running = true;
                        cancelRequested = false;
                        totalTasks = activeTaskItems.size();
                        currentTaskIndex = 0;
                        startNextTask();
                    } else {
                        totalTasks = activeTaskItems.size();
                    }
                    notifyQueueUpdated();
                } else {
                    Log.d(TAG, "All enqueued tasks are duplicates and skipped.");
                }
            }
        }

        return START_STICKY;
    }

    private TaskProgressItem currentProgressItem;

    private void notifyQueueUpdated() {
        for (QueueProgressListener ql : queueProgressListeners) {
            try {
                ql.onQueueUpdated();
            } catch (Exception ignored) {}
        }
    }

    private void startNextTask() {
        if (cancelRequested || taskQueue.isEmpty()) {
            running = false;

            int completedCount = 0;
            int errorCount = 0;
            synchronized (activeTaskItems) {
                for (TaskProgressItem item : activeTaskItems) {
                    if (item.status == TaskProgressItem.STATUS_COMPLETED) {
                        completedCount++;
                    } else if (item.status == TaskProgressItem.STATUS_ERROR) {
                        errorCount++;
                    }
                }
            }

            if (cancelRequested) {
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

            synchronized (activeTaskItems) {
                activeTaskItems.clear();
            }

            notifyQueueUpdated();
            stopAfterResult();
            if (listener != null) {
                listener.onFinished(completedCount, errorCount, cancelRequested);
            }
            return;
        }

        DownloadTask task = taskQueue.remove(0);
        currentTaskIndex++;
        currentTaskPercent = 0;

        currentProgressItem = null;
        synchronized (activeTaskItems) {
            for (TaskProgressItem item : activeTaskItems) {
                boolean isSameTask = (item.task == task) ||
                        (item.task.getEpisodeId() == task.getEpisodeId() &&
                         java.util.Objects.equals(item.task.getTeamName(), task.getTeamName()) &&
                         java.util.Objects.equals(item.task.getQuality(), task.getQuality()));
                if (isSameTask && (item.status == TaskProgressItem.STATUS_WAITING || item.status == TaskProgressItem.STATUS_DOWNLOADING)) {
                    item.status = TaskProgressItem.STATUS_DOWNLOADING;
                    item.percent = 0;
                    currentProgressItem = item;
                    break;
                }
            }
            if (currentProgressItem == null) {
                for (TaskProgressItem item : activeTaskItems) {
                    if (item.status == TaskProgressItem.STATUS_WAITING) {
                        item.status = TaskProgressItem.STATUS_DOWNLOADING;
                        item.percent = 0;
                        currentProgressItem = item;
                        break;
                    }
                }
            }
        }
        notifyQueueUpdated();

        String taskTitle = (task.getAnimeTitle() != null ? task.getAnimeTitle() : "Аниме") + " - Серия " + task.getEpisodeNumber();
        startForegroundCompat(PROGRESS_NOTIFICATION_ID, buildProgressNotification(taskTitle, 0));

        final long[] lastNotifTime = new long[1];

        downloadsManager.downloadTask(task, new DownloadsManager.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                currentTaskPercent = percent;
                synchronized (activeTaskItems) {
                    for (TaskProgressItem item : activeTaskItems) {
                        if (item.status == TaskProgressItem.STATUS_DOWNLOADING) {
                            item.percent = percent;
                            break;
                        }
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
                Log.d(TAG, "Task finished: " + taskTitle);
                if (currentProgressItem != null) {
                    currentProgressItem.status = TaskProgressItem.STATUS_COMPLETED;
                    currentProgressItem.percent = 100;
                }
                notifyQueueUpdated();
                startNextTask();
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Task error: " + message);
                if (currentProgressItem != null) {
                    currentProgressItem.status = TaskProgressItem.STATUS_ERROR;
                    currentProgressItem.errorMessage = message;
                }
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
        running = false;
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
