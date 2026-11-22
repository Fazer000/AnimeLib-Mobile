package com.example.animelib.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.example.animelib.R;
import com.example.animelib.UpdateActivity;
import com.example.animelib.api.ApiClientCore;
import com.example.animelib.models.UpdateInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Фоновый ForegroundService для скачивания APK-обновлений приложения.
 *
 * Особенности:
 * - Работает при свёрнутом приложении и выключенном экране (PARTIAL_WAKE_LOCK + DATA_SYNC).
 * - Отображает уведомление о прогрессе с кнопкой «Отмена» (в стиле DownloadService).
 * - Пишет файл атомарно через временный .part и переименовывает в .apk только после 100% завершения.
 * - Сохраняет метаданные скачанного APK в SharedPreferences, чтобы после сворачивания/повторного входа
 *   можно было сразу установить готовый файл без повторного скачивания.
 */
public class AppUpdateDownloadService extends Service {
    private static final String TAG = "AppUpdateDownloadSvc";

    private static final String CHANNEL_ID = "animelib_app_updates_v1";
    private static final int PROGRESS_NOTIFICATION_ID = 4301;
    private static final int RESULT_NOTIFICATION_ID = 4302;

    public static final String ACTION_START_DOWNLOAD = "com.example.animelib.action.APP_UPDATE_START";
    public static final String ACTION_CANCEL = "com.example.animelib.action.APP_UPDATE_CANCEL";

    public static final String EXTRA_UPDATE_INFO = "extra_update_info";
    public static final String EXTRA_FORCE_REDOWNLOAD = "extra_force_redownload";

    private static final String PREFS_NAME = "animelib_app_update_cache";
    private static final String KEY_APK_PATH = "apk_path";
    private static final String KEY_APK_TAG = "apk_tag";
    private static final String KEY_APK_RELEASE_NAME = "apk_release_name";
    private static final String KEY_APK_CHANGELOG = "apk_changelog";
    private static final String KEY_APK_URL = "apk_url";
    private static final String KEY_APK_HTML_URL = "apk_html_url";
    private static final String KEY_APK_PUBLISHED_AT = "apk_published_at";
    private static final String KEY_APK_EXPECTED_SIZE = "apk_expected_size";
    private static final String KEY_APK_ACTUAL_SIZE = "apk_actual_size";
    private static final String KEY_APK_IS_BETA = "apk_is_beta";
    private static final String KEY_APP_LAST_UPDATE_TIME = "app_last_update_time";

    public enum State {
        IDLE,
        CONNECTING,
        DOWNLOADING,
        COMPLETED,
        ERROR,
        CANCELLED
    }

    public static final class DownloadSnapshot {
        @NonNull
        public final State state;
        @Nullable
        public final UpdateInfo targetInfo;
        public final int progress;
        public final long downloadedBytes;
        public final long totalBytes;
        @Nullable
        public final String downloadedFilePath;
        @Nullable
        public final String errorMessage;
        public final boolean pendingAutoInstall;

        public DownloadSnapshot(@NonNull State state,
                                @Nullable UpdateInfo targetInfo,
                                int progress,
                                long downloadedBytes,
                                long totalBytes,
                                @Nullable String downloadedFilePath,
                                @Nullable String errorMessage,
                                boolean pendingAutoInstall) {
            this.state = state;
            this.targetInfo = targetInfo;
            this.progress = progress;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.downloadedFilePath = downloadedFilePath;
            this.errorMessage = errorMessage;
            this.pendingAutoInstall = pendingAutoInstall;
        }

        public boolean isActive() {
            return state == State.CONNECTING || state == State.DOWNLOADING;
        }
    }

    public interface DownloadStateListener {
        void onDownloadStateChanged(@NonNull DownloadSnapshot snapshot);
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final List<DownloadStateListener> listeners = new CopyOnWriteArrayList<>();
    private static final Object stateLock = new Object();
    private static final AtomicInteger generationCounter = new AtomicInteger(0);

    @NonNull
    private static volatile DownloadSnapshot currentSnapshot = new DownloadSnapshot(
            State.IDLE, null, 0, 0L, 0L, null, null, false
    );
    private static volatile Call activeCall = null;
    private static volatile boolean cancelFlag = false;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private Thread downloadThread;

    // ==================== Static Control API ====================

    public static void startDownload(@NonNull Context context,
                                     @NonNull UpdateInfo info,
                                     boolean forceRedownload) {
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, AppUpdateDownloadService.class);
        intent.setAction(ACTION_START_DOWNLOAD);
        intent.putExtra(EXTRA_UPDATE_INFO, info);
        intent.putExtra(EXTRA_FORCE_REDOWNLOAD, forceRedownload);
        try {
            ContextCompat.startForegroundService(appContext, intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to startForegroundService, falling back to startService", e);
            try {
                appContext.startService(intent);
            } catch (Exception ignored) {}
        }
    }

    public static void cancelDownload(@NonNull Context context) {
        cancelFlag = true;
        generationCounter.incrementAndGet();
        Call call = activeCall;
        if (call != null) {
            try {
                call.cancel();
            } catch (Exception ignored) {}
        }
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, AppUpdateDownloadService.class);
        intent.setAction(ACTION_CANCEL);
        try {
            appContext.startService(intent);
        } catch (Exception ignored) {}
    }

    public static void addListener(@NonNull DownloadStateListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(@NonNull DownloadStateListener listener) {
        listeners.remove(listener);
    }

    @NonNull
    public static DownloadSnapshot getSnapshot() {
        return currentSnapshot;
    }

    public static boolean isDownloadActive() {
        return currentSnapshot.isActive();
    }

    public static void consumePendingAutoInstall() {
        synchronized (stateLock) {
            DownloadSnapshot s = currentSnapshot;
            if (s.pendingAutoInstall) {
                currentSnapshot = new DownloadSnapshot(
                        s.state,
                        s.targetInfo,
                        s.progress,
                        s.downloadedBytes,
                        s.totalBytes,
                        s.downloadedFilePath,
                        s.errorMessage,
                        false
                );
            }
        }
    }

    public static void clearResultNotification(@NonNull Context context) {
        try {
            NotificationManager nm = (NotificationManager) context.getApplicationContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(RESULT_NOTIFICATION_ID);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Проверяет, скачан ли уже валидный APK-файл для указанного UpdateInfo (или любой последний готовый APK,
     * если targetInfo == null). Гарантирует проверку существования, размера и целостности архива.
     */
    @Nullable
    public static File getReadyDownloadedApk(@NonNull Context context, @Nullable UpdateInfo targetInfo) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedPath = prefs.getString(KEY_APK_PATH, null);
        if (savedPath == null || savedPath.isEmpty()) {
            return null;
        }

        long savedLastUpdateTime = prefs.getLong(KEY_APP_LAST_UPDATE_TIME, -1L);
        long currentLastUpdateTime = getCurrentAppLastUpdateTime(appContext);
        if (savedLastUpdateTime > 0L && currentLastUpdateTime > 0L && currentLastUpdateTime != savedLastUpdateTime) {
            try {
                new File(savedPath).delete();
            } catch (Exception ignored) {}
            clearDownloadedApkRecord(appContext);
            return null;
        }

        File file = new File(savedPath);
        long savedActualSize = prefs.getLong(KEY_APK_ACTUAL_SIZE, -1L);
        if (!file.exists() || !file.isFile() || file.length() <= 0L) {
            clearDownloadedApkRecord(appContext);
            return null;
        }
        if (savedActualSize > 0L && file.length() != savedActualSize) {
            clearDownloadedApkRecord(appContext);
            return null;
        }

        if (targetInfo != null) {
            String savedTag = prefs.getString(KEY_APK_TAG, "");
            String savedUrl = prefs.getString(KEY_APK_URL, "");
            String savedPublishedAt = prefs.getString(KEY_APK_PUBLISHED_AT, "");
            long savedExpectedSize = prefs.getLong(KEY_APK_EXPECTED_SIZE, 0L);

            String targetTag = targetInfo.getTagName() != null ? targetInfo.getTagName() : "";
            String targetUrl = targetInfo.getApkUrl() != null ? targetInfo.getApkUrl() : "";
            String targetPublishedAt = targetInfo.getPublishedAt() != null ? targetInfo.getPublishedAt() : "";
            long targetSize = targetInfo.getApkSize();

            if (!targetTag.equals(savedTag) || !targetUrl.equals(savedUrl)) {
                return null;
            }
            if (!targetPublishedAt.isEmpty() && !savedPublishedAt.isEmpty() && !targetPublishedAt.equals(savedPublishedAt)) {
                return null;
            }
            if (targetSize > 0L && savedExpectedSize > 0L && targetSize != savedExpectedSize) {
                return null;
            }
        }

        if (!isValidApkFile(appContext, file)) {
            try {
                file.delete();
            } catch (Exception ignored) {}
            clearDownloadedApkRecord(appContext);
            return null;
        }

        return file;
    }

    @Nullable
    public static UpdateInfo getSavedDownloadedUpdateInfo(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedPath = prefs.getString(KEY_APK_PATH, null);
        if (savedPath == null || savedPath.isEmpty()) {
            return null;
        }
        File file = new File(savedPath);
        if (!file.exists() || file.length() <= 0L) {
            return null;
        }
        String tag = prefs.getString(KEY_APK_TAG, "");
        String releaseName = prefs.getString(KEY_APK_RELEASE_NAME, tag);
        String changelog = prefs.getString(KEY_APK_CHANGELOG, "");
        String publishedAt = prefs.getString(KEY_APK_PUBLISHED_AT, "");
        String htmlUrl = prefs.getString(KEY_APK_HTML_URL, "https://github.com/Fazer000/AnimeLib-Mobile");
        String apkUrl = prefs.getString(KEY_APK_URL, "");
        long apkSize = prefs.getLong(KEY_APK_EXPECTED_SIZE, file.length());
        boolean isBeta = prefs.getBoolean(KEY_APK_IS_BETA, false);
        if (tag.isEmpty() && apkUrl.isEmpty()) {
            return null;
        }
        return new UpdateInfo(tag, releaseName, changelog, publishedAt, htmlUrl, apkUrl, apkSize, isBeta);
    }

    public static void clearDownloadedApkRecord(@NonNull Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private static void saveDownloadedApkRecord(@NonNull Context context,
                                                @NonNull UpdateInfo info,
                                                @NonNull File apkFile) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_APK_PATH, apkFile.getAbsolutePath())
                .putString(KEY_APK_TAG, info.getTagName() != null ? info.getTagName() : "")
                .putString(KEY_APK_RELEASE_NAME, info.getReleaseName() != null ? info.getReleaseName() : "")
                .putString(KEY_APK_CHANGELOG, info.getChangelog() != null ? info.getChangelog() : "")
                .putString(KEY_APK_URL, info.getApkUrl() != null ? info.getApkUrl() : "")
                .putString(KEY_APK_HTML_URL, info.getHtmlUrl() != null ? info.getHtmlUrl() : "")
                .putString(KEY_APK_PUBLISHED_AT, info.getPublishedAt() != null ? info.getPublishedAt() : "")
                .putLong(KEY_APK_EXPECTED_SIZE, info.getApkSize())
                .putLong(KEY_APK_ACTUAL_SIZE, apkFile.length())
                .putBoolean(KEY_APK_IS_BETA, info.isBeta())
                .putLong(KEY_APP_LAST_UPDATE_TIME, getCurrentAppLastUpdateTime(context))
                .apply();
    }

    private static long getCurrentAppLastUpdateTime(@NonNull Context context) {
        try {
            PackageInfo pkgInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pkgInfo != null ? pkgInfo.lastUpdateTime : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static boolean isValidApkFile(@NonNull Context context, @NonNull File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            return info != null && info.packageName != null;
        } catch (Exception e) {
            Log.w(TAG, "APK validation failed for " + apkFile.getName(), e);
            return false;
        }
    }

    private static void publishSnapshot(@NonNull DownloadSnapshot snapshot) {
        synchronized (stateLock) {
            currentSnapshot = snapshot;
        }
        mainHandler.post(() -> {
            for (DownloadStateListener listener : listeners) {
                try {
                    listener.onDownloadStateChanged(snapshot);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying update download listener", e);
                }
            }
        });
    }

    // ==================== Service Lifecycle ====================

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            handleCancelRequest();
            return START_NOT_STICKY;
        }

        if (ACTION_START_DOWNLOAD.equals(action)) {
            UpdateInfo info = null;
            try {
                info = (UpdateInfo) intent.getSerializableExtra(EXTRA_UPDATE_INFO);
            } catch (Exception e) {
                Log.e(TAG, "Error deserializing UpdateInfo", e);
            }
            boolean forceRedownload = intent.getBooleanExtra(EXTRA_FORCE_REDOWNLOAD, false);
            if (info == null || info.getApkUrl() == null || info.getApkUrl().isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            startDownloadInternal(info, forceRedownload);
        }

        return START_NOT_STICKY;
    }

    private void handleCancelRequest() {
        cancelFlag = true;
        generationCounter.incrementAndGet();
        Call call = activeCall;
        activeCall = null;
        if (call != null) {
            try {
                call.cancel();
            } catch (Exception ignored) {}
        }

        UpdateInfo target = currentSnapshot.targetInfo;
        publishSnapshot(new DownloadSnapshot(
                State.CANCELLED, target, 0, 0L, 0L, null, null, false
        ));

        clearResultNotification(this);
        stopForegroundAndSelf();
    }

    private void startDownloadInternal(@NonNull UpdateInfo targetInfo, boolean forceRedownload) {
        // Если уже скачан валидный файл и не требуется принудительная перезагрузка — сразу завершаем
        if (!forceRedownload) {
            File existing = getReadyDownloadedApk(this, targetInfo);
            if (existing != null) {
                startForegroundCompat(PROGRESS_NOTIFICATION_ID, buildProgressNotification(targetInfo, 100, existing.length(), existing.length(), false));
                publishSnapshot(new DownloadSnapshot(
                        State.COMPLETED,
                        targetInfo,
                        100,
                        existing.length(),
                        existing.length(),
                        existing.getAbsolutePath(),
                        null,
                        true
                ));
                showCompletedNotification(targetInfo);
                stopForegroundAndSelf();
                return;
            }
        }

        // Отменяем предыдущую загрузку, если шла
        Call prevCall = activeCall;
        if (prevCall != null) {
            try {
                prevCall.cancel();
            } catch (Exception ignored) {}
            activeCall = null;
        }

        if (forceRedownload) {
            clearDownloadedApkRecord(this);
        }

        cancelFlag = false;
        final int myGeneration = generationCounter.incrementAndGet();
        clearResultNotification(this);

        Notification initialNotif = buildProgressNotification(targetInfo, 0, 0L, targetInfo.getApkSize(), true);
        startForegroundCompat(PROGRESS_NOTIFICATION_ID, initialNotif);
        acquireWakeLock();

        publishSnapshot(new DownloadSnapshot(
                State.CONNECTING, targetInfo, 0, 0L, targetInfo.getApkSize(), null, null, false
        ));

        downloadThread = new Thread(() -> runDownloadWorker(targetInfo, myGeneration), "app-update-download");
        downloadThread.start();
    }

    private void runDownloadWorker(@NonNull UpdateInfo targetInfo, int myGeneration) {
        File partFile = null;
        File finalFile = null;
        boolean completedSuccessfully = false;

        try {
            File targetDir = resolveUpdateDirectory();
            if (targetDir == null) {
                failDownload(targetInfo, myGeneration, "Не удалось получить доступ к хранилищу");
                return;
            }
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            cleanupOldUpdateFiles(targetDir);

            String baseFileName = buildApkFileName(targetInfo);
            finalFile = new File(targetDir, baseFileName);
            partFile = new File(targetDir, baseFileName + ".part");
            if (partFile.exists()) {
                partFile.delete();
            }
            if (finalFile.exists()) {
                finalFile.delete();
            }

            OkHttpClient client = ApiClientCore.getSharedHttpClient()
                    .newBuilder()
                    .connectTimeout(25, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();

            Request request = new Request.Builder()
                    .url(targetInfo.getApkUrl())
                    .header("User-Agent", "AnimeLib-App")
                    .build();

            Call call = client.newCall(request);
            activeCall = call;

            if (isStaleOrCancelled(myGeneration)) {
                call.cancel();
                return;
            }

            try (Response response = call.execute()) {
                if (isStaleOrCancelled(myGeneration)) {
                    return;
                }

                if (!response.isSuccessful()) {
                    failDownload(targetInfo, myGeneration, "Ошибка сервера HTTP " + response.code());
                    return;
                }

                ResponseBody body = response.body();
                if (body == null) {
                    failDownload(targetInfo, myGeneration, "Пустой ответ от сервера");
                    return;
                }

                long contentLength = body.contentLength();
                if (contentLength <= 0L && targetInfo.getApkSize() > 0L) {
                    contentLength = targetInfo.getApkSize();
                }

                publishSnapshot(new DownloadSnapshot(
                        State.DOWNLOADING, targetInfo, 0, 0L, contentLength, null, null, false
                ));
                updateProgressNotification(targetInfo, 0, 0L, contentLength, contentLength <= 0L);

                byte[] buffer = new byte[16384];
                long bytesReadTotal = 0L;
                int read;
                long lastUiUpdateMs = 0L;
                long lastNotifUpdateMs = 0L;

                try (InputStream inputStream = body.byteStream();
                     OutputStream outputStream = new FileOutputStream(partFile)) {
                    while ((read = inputStream.read(buffer)) != -1) {
                        if (isStaleOrCancelled(myGeneration) || call.isCanceled()) {
                            return;
                        }
                        outputStream.write(buffer, 0, read);
                        bytesReadTotal += read;

                        long now = System.currentTimeMillis();
                        int progress = contentLength > 0L
                                ? (int) Math.min(100L, (bytesReadTotal * 100L) / contentLength)
                                : 0;

                        if (now - lastUiUpdateMs >= 180L) {
                            lastUiUpdateMs = now;
                            publishSnapshot(new DownloadSnapshot(
                                    State.DOWNLOADING, targetInfo, progress, bytesReadTotal, contentLength, null, null, false
                            ));
                        }

                        if (now - lastNotifUpdateMs >= 450L) {
                            lastNotifUpdateMs = now;
                            updateProgressNotification(targetInfo, progress, bytesReadTotal, contentLength, contentLength <= 0L);
                        }
                    }
                    outputStream.flush();
                }

                if (isStaleOrCancelled(myGeneration)) {
                    return;
                }

                long expectedBodyLen = body.contentLength();
                if (expectedBodyLen > 0L && bytesReadTotal < expectedBodyLen) {
                    failDownload(targetInfo, myGeneration, "Файл загружен не полностью");
                    return;
                }

                if (!partFile.renameTo(finalFile)) {
                    failDownload(targetInfo, myGeneration, "Ошибка сохранения файла обновления");
                    return;
                }

                if (!isValidApkFile(this, finalFile)) {
                    failDownload(targetInfo, myGeneration, "Загруженный APK повреждён");
                    return;
                }

                completedSuccessfully = true;
                saveDownloadedApkRecord(this, targetInfo, finalFile);

                publishSnapshot(new DownloadSnapshot(
                        State.COMPLETED,
                        targetInfo,
                        100,
                        finalFile.length(),
                        finalFile.length(),
                        finalFile.getAbsolutePath(),
                        null,
                        true
                ));
                showCompletedNotification(targetInfo);
                stopForegroundAndSelf();
            }
        } catch (Exception e) {
            if (isStaleOrCancelled(myGeneration)) {
                Log.d(TAG, "App update download cancelled");
                return;
            }
            Log.e(TAG, "Error downloading app update APK", e);
            failDownload(targetInfo, myGeneration, "Ошибка скачивания файла");
        } finally {
            if (activeCall != null && generationCounter.get() == myGeneration) {
                activeCall = null;
            }
            if (!completedSuccessfully) {
                if (partFile != null && partFile.exists()) {
                    try { partFile.delete(); } catch (Exception ignored) {}
                }
                if (finalFile != null && finalFile.exists()) {
                    try { finalFile.delete(); } catch (Exception ignored) {}
                }
            }
            if (generationCounter.get() == myGeneration) {
                releaseWakeLock();
            }
        }
    }

    private void failDownload(@NonNull UpdateInfo targetInfo, int myGeneration, @NonNull String errorMessage) {
        if (isStaleOrCancelled(myGeneration)) return;
        publishSnapshot(new DownloadSnapshot(
                State.ERROR, targetInfo, 0, 0L, 0L, null, errorMessage, false
        ));
        showErrorNotification(targetInfo, errorMessage);
        stopForegroundAndSelf();
    }

    private boolean isStaleOrCancelled(int myGeneration) {
        return cancelFlag || generationCounter.get() != myGeneration;
    }

    @Nullable
    private File resolveUpdateDirectory() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null) {
            return dir;
        }
        return new File(getFilesDir(), "updates");
    }

    private void cleanupOldUpdateFiles(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (name.startsWith("animelib-update-")
                    || name.equals("app-beta-update.apk")
                    || name.equals("app-stable-update.apk")) {
                try {
                    f.delete();
                } catch (Exception ignored) {}
            }
        }
    }

    private static String buildApkFileName(@NonNull UpdateInfo info) {
        String rawTag = info.getTagName() != null ? info.getTagName() : (info.isBeta() ? "beta" : "stable");
        String safeTag = rawTag.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeTag.isEmpty()) {
            safeTag = info.isBeta() ? "beta" : "stable";
        }
        return "animelib-update-" + safeTag + ".apk";
    }

    // ==================== Notifications ====================

    private void createNotificationChannel() {
        if (notificationManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Обновление приложения",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Прогресс загрузки обновлений AnimeLib");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    private void updateProgressNotification(@NonNull UpdateInfo targetInfo,
                                            int percent,
                                            long downloadedBytes,
                                            long totalBytes,
                                            boolean indeterminate) {
        if (notificationManager == null) return;
        try {
            notificationManager.notify(
                    PROGRESS_NOTIFICATION_ID,
                    buildProgressNotification(targetInfo, percent, downloadedBytes, totalBytes, indeterminate)
            );
        } catch (Exception ignored) {}
    }

    private Notification buildProgressNotification(@NonNull UpdateInfo targetInfo,
                                                   int percent,
                                                   long downloadedBytes,
                                                   long totalBytes,
                                                   boolean indeterminate) {
        Intent cancelIntent = new Intent(this, AppUpdateDownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPending = PendingIntent.getService(
                this,
                4301,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent contentIntent = new Intent(this, UpdateActivity.class);
        contentIntent.putExtra(UpdateActivity.EXTRA_UPDATE_INFO, targetInfo);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPending = PendingIntent.getActivity(
                this,
                4302,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String tag = targetInfo.getTagName() != null ? targetInfo.getTagName() : "";
        String title = tag.isEmpty() ? "Обновление AnimeLib" : ("Обновление AnimeLib " + tag);

        String subtext;
        if (indeterminate && downloadedBytes <= 0L) {
            subtext = "Подключение к серверу...";
        } else if (totalBytes > 0L) {
            subtext = String.format(
                    Locale.US,
                    "Загрузка: %d%% (%.1f / %.1f МБ)",
                    percent,
                    downloadedBytes / (1024f * 1024f),
                    totalBytes / (1024f * 1024f)
            );
        } else {
            subtext = String.format(Locale.US, "Загружено: %.1f МБ", downloadedBytes / (1024f * 1024f));
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(subtext)
                .setContentIntent(contentPending)
                .setProgress(100, Math.max(0, Math.min(100, percent)), indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSound(null)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(R.drawable.ic_close, "Отмена", cancelPending)
                .build();
    }

    private void showCompletedNotification(@NonNull UpdateInfo targetInfo) {
        if (notificationManager == null) return;
        try {
            Intent installActivityIntent = new Intent(this, UpdateActivity.class);
            installActivityIntent.putExtra(UpdateActivity.EXTRA_UPDATE_INFO, targetInfo);
            installActivityIntent.putExtra(UpdateActivity.EXTRA_AUTO_INSTALL, true);
            installActivityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent contentPending = PendingIntent.getActivity(
                    this,
                    4303,
                    installActivityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            String tag = targetInfo.getTagName() != null ? targetInfo.getTagName() : "";
            String title = tag.isEmpty() ? "Обновление загружено" : ("Обновление " + tag + " загружено");

            Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText("Нажмите, чтобы установить приложение")
                    .setContentIntent(contentPending)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .addAction(R.drawable.ic_download, "Установить", contentPending)
                    .build();

            notificationManager.notify(RESULT_NOTIFICATION_ID, notif);
        } catch (Exception e) {
            Log.e(TAG, "Failed to post completed update notification", e);
        }
    }

    private void showErrorNotification(@NonNull UpdateInfo targetInfo, @NonNull String errorMessage) {
        if (notificationManager == null) return;
        try {
            Intent contentIntent = new Intent(this, UpdateActivity.class);
            contentIntent.putExtra(UpdateActivity.EXTRA_UPDATE_INFO, targetInfo);
            contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent contentPending = PendingIntent.getActivity(
                    this,
                    4304,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Ошибка загрузки обновления")
                    .setContentText(errorMessage)
                    .setContentIntent(contentPending)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build();

            notificationManager.notify(RESULT_NOTIFICATION_ID, notif);
        } catch (Exception ignored) {}
    }

    private void startForegroundCompat(int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ServiceCompat.startForeground(this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } catch (Exception e) {
                Log.w(TAG, "startForegroundCompat with DATA_SYNC failed, trying plain startForeground", e);
                try {
                    startForeground(id, notification);
                } catch (Exception ignored) {}
            }
        } else {
            try {
                startForeground(id, notification);
            } catch (Exception ignored) {}
        }
    }

    private void stopForegroundAndSelf() {
        releaseWakeLock();
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {}
        stopSelf();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnimeLIB:AppUpdateDownloadWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null) {
            try {
                wakeLock.acquire(15 * 60 * 1000L);
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

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
