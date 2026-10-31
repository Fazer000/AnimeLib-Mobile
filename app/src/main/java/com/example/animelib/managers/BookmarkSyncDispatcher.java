package com.example.animelib.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.util.CustomToast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Глобальный (application-scoped) диспетчер сохранения закладок.
 * Не зависит от жизненного цикла VideoPlayerActivity, гарантирует последовательную отправку,
 * склейку быстрых повторных вызовов при выходе из плеера, автоматические ретраи при сбоях сети
 * и показ Toast-уведомлений при ошибках.
 */
public class BookmarkSyncDispatcher {
    private static final String TAG = "BookmarkSyncDispatcher";
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] RETRY_DELAYS_MS = new long[]{450L, 1000L};
    private static final long AUTO_ERROR_TOAST_COOLDOWN_MS = 3000L;

    private static volatile BookmarkSyncDispatcher instance;

    public static class BookmarkRequest {
        public final String mediaSlug;
        public final int episodeId;
        public final int teamId;
        public final int translationType;
        public final String playerName;
        public final int episodeNumber;
        public final String timecode;
        public final Object statusId;
        public final boolean showSuccessToast;
        public final List<BookmarkManager.BookmarkAddCallback> callbacks = new ArrayList<>();

        public BookmarkRequest(String mediaSlug,
                               int episodeId,
                               int teamId,
                               int translationType,
                               String playerName,
                               int episodeNumber,
                               String timecode,
                               Object statusId,
                               boolean showSuccessToast,
                               @Nullable BookmarkManager.BookmarkAddCallback callback) {
            this.mediaSlug = mediaSlug != null ? mediaSlug.trim() : "";
            this.episodeId = episodeId;
            this.teamId = teamId;
            this.translationType = translationType > 0 ? translationType : 2;
            this.playerName = (playerName != null && !playerName.trim().isEmpty()) ? playerName.trim() : "Animelib";
            this.episodeNumber = episodeNumber > 0 ? episodeNumber : 1;
            this.timecode = (timecode != null && !timecode.trim().isEmpty()) ? timecode.trim() : "00:00";
            this.statusId = statusId;
            this.showSuccessToast = showSuccessToast;
            if (callback != null) {
                this.callbacks.add(callback);
            }
        }

        public String signature() {
            return episodeId + "|" + timecode + "|" + teamId + "|" + (statusId != null ? statusId.toString() : "default");
        }
    }

    public interface SyncBookmarkSender {
        ApiService.BookmarkSaveResult send(BookmarkRequest request);
    }

    private final Context appContext;
    private final ApiService appApiService;
    private final DatabaseManager appDatabaseManager;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final SyncBookmarkSender sender;

    private final Object lock = new Object();
    private final Map<String, BookmarkRequest> queuedBySlug = new ConcurrentHashMap<>();
    private final Map<String, Boolean> activeWorkerBySlug = new ConcurrentHashMap<>();
    private final Map<String, String> lastSavedSignatureBySlug = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAutoErrorToastTimeBySlug = new ConcurrentHashMap<>();

    private BookmarkSyncDispatcher(@NonNull Context context) {
        this.appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.appApiService = new ApiService(this.appContext);
        this.appDatabaseManager = new DatabaseManager(this.appContext);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bookmark-sync-dispatcher");
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.sender = req -> appApiService.addBookmarkSync(
                req.mediaSlug,
                req.episodeId,
                req.teamId,
                req.translationType,
                req.playerName,
                req.episodeNumber,
                req.timecode,
                req.statusId
        );
    }

    // Конструктор для модульного тестирования без Android окружения
    public BookmarkSyncDispatcher(@Nullable Context context,
                                  @NonNull ExecutorService executor,
                                  @Nullable Handler mainHandler,
                                  @NonNull SyncBookmarkSender sender) {
        this.appContext = context;
        this.appApiService = null;
        this.appDatabaseManager = null;
        this.executor = executor;
        this.mainHandler = mainHandler;
        this.sender = sender;
    }

    public static BookmarkSyncDispatcher getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (BookmarkSyncDispatcher.class) {
                if (instance == null) {
                    instance = new BookmarkSyncDispatcher(context);
                }
            }
        }
        return instance;
    }

    public DatabaseManager getAppDatabaseManager() {
        return appDatabaseManager;
    }

    public boolean isAuthorized() {
        return appApiService != null && appApiService.isAuthorized();
    }

    public void invalidateSavedSignature(@Nullable String mediaSlug) {
        if (mediaSlug != null && !mediaSlug.trim().isEmpty()) {
            lastSavedSignatureBySlug.remove(mediaSlug.trim().toLowerCase());
        }
    }

    public void enqueueBookmark(@NonNull BookmarkRequest incoming) {
        final String key = incoming.mediaSlug.toLowerCase();
        boolean shouldScheduleWorker = false;

        synchronized (lock) {
            // Если это автосохранение и точно такая же закладка уже успешно сохранена и нет новых задач в очереди — пропускаем дубль
            if (!incoming.showSuccessToast
                    && !queuedBySlug.containsKey(key)
                    && !Boolean.TRUE.equals(activeWorkerBySlug.get(key))
                    && incoming.signature().equals(lastSavedSignatureBySlug.get(key))) {
                safeLogDebug("Skipping duplicate auto-bookmark for " + key + " (" + incoming.signature() + ")");
                dispatchSuccessCallbacks(incoming.callbacks, incoming.episodeId);
                return;
            }

            BookmarkRequest existingQueued = queuedBySlug.get(key);
            if (existingQueued != null) {
                boolean mergedShowSuccess = existingQueued.showSuccessToast || incoming.showSuccessToast;
                BookmarkRequest merged = new BookmarkRequest(
                        incoming.mediaSlug,
                        incoming.episodeId,
                        incoming.teamId,
                        incoming.translationType,
                        incoming.playerName,
                        incoming.episodeNumber,
                        incoming.timecode,
                        incoming.statusId != null ? incoming.statusId : existingQueued.statusId,
                        mergedShowSuccess,
                        null
                );
                merged.callbacks.addAll(existingQueued.callbacks);
                merged.callbacks.addAll(incoming.callbacks);
                queuedBySlug.put(key, merged);
            } else {
                queuedBySlug.put(key, incoming);
            }

            if (!Boolean.TRUE.equals(activeWorkerBySlug.get(key))) {
                activeWorkerBySlug.put(key, Boolean.TRUE);
                shouldScheduleWorker = true;
            }
        }

        if (shouldScheduleWorker) {
            executor.execute(() -> processSlugQueue(key));
        }
    }

    private void processSlugQueue(final String key) {
        while (true) {
            final BookmarkRequest current;
            synchronized (lock) {
                current = queuedBySlug.remove(key);
                if (current == null) {
                    activeWorkerBySlug.remove(key);
                    return;
                }
            }

            executeWithRetries(key, current);
        }
    }

    private void executeWithRetries(final String key, @NonNull BookmarkRequest initialRequest) {
        BookmarkRequest current = initialRequest;
        ApiService.BookmarkSaveResult lastResult = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // Если во время ожидания ретрая прилетело более свежее обновление позиции для этого же тайтла — отправляем сразу свежее!
            synchronized (lock) {
                BookmarkRequest newer = queuedBySlug.remove(key);
                if (newer != null) {
                    boolean mergedShowSuccess = current.showSuccessToast || newer.showSuccessToast;
                    BookmarkRequest upgraded = new BookmarkRequest(
                            newer.mediaSlug,
                            newer.episodeId,
                            newer.teamId,
                            newer.translationType,
                            newer.playerName,
                            newer.episodeNumber,
                            newer.timecode,
                            newer.statusId != null ? newer.statusId : current.statusId,
                            mergedShowSuccess,
                            null
                    );
                    upgraded.callbacks.addAll(current.callbacks);
                    upgraded.callbacks.addAll(newer.callbacks);
                    current = upgraded;
                }
            }

            lastResult = sender.send(current);

            if (lastResult != null && lastResult.success) {
                lastSavedSignatureBySlug.put(key, current.signature());
                if (appApiService != null) {
                    appApiService.invalidateAnimeBookmarkCache(current.mediaSlug);
                }
                if (appContext != null) {
                    OfflineSyncManager.getInstance(appContext).clearPendingBookmarkTasksForSlug(current.mediaSlug);
                }

                if (current.showSuccessToast) {
                    showSuccessToast("Закладка сохранена (" + current.timecode + ")");
                }
                dispatchSuccessCallbacks(current.callbacks, current.episodeId);
                return;
            }

            boolean canRetry = lastResult != null && lastResult.retryable && attempt < MAX_ATTEMPTS;
            if (!canRetry) {
                break;
            }

            long delayMs = RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)];
            safeLogWarn("Bookmark save attempt " + attempt + " failed (" + lastResult.message + "), retrying in " + delayMs + "ms");
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        lastSavedSignatureBySlug.remove(key);
        String rawErr = lastResult != null ? lastResult.message : null;
        String friendlyError = formatBookmarkErrorToast(rawErr);

        // Если сбой временный (пропал интернет на выходе из плеера) — ставим задачу в OfflineSyncManager
        if (lastResult != null && lastResult.retryable && appContext != null) {
            OfflineSyncManager.getInstance(appContext).saveOrReplacePendingBookmarkTask(
                    current.mediaSlug,
                    current.episodeId,
                    current.teamId,
                    current.episodeNumber,
                    current.timecode,
                    current.statusId
            );
        }

        // Всегда показываем Toast при ошибке добавления закладки (с защитой от спама при автосохранении)
        boolean shouldShowToast = current.showSuccessToast || shouldShowAutoErrorToast(key);
        if (shouldShowToast) {
            showErrorToast(friendlyError);
        }

        dispatchErrorCallbacks(current.callbacks, rawErr != null ? rawErr : friendlyError);
    }

    private boolean shouldShowAutoErrorToast(String key) {
        long now = System.currentTimeMillis();
        Long prev = lastAutoErrorToastTimeBySlug.get(key);
        if (prev != null && (now - prev) < AUTO_ERROR_TOAST_COOLDOWN_MS) {
            return false;
        }
        lastAutoErrorToastTimeBySlug.put(key, now);
        return true;
    }

    public static String formatBookmarkErrorToast(@Nullable String rawError) {
        if (rawError == null || rawError.trim().isEmpty()) {
            return "Не удалось добавить закладку";
        }
        String trimmed = rawError.trim();
        String lower = trimmed.toLowerCase();

        if (lower.contains("без авторизации") || lower.contains("401") || lower.contains("unauthenticated") || lower.contains("unauthorized")) {
            return "Ошибка добавления закладки: необходима авторизация";
        }
        if (lower.startsWith("ошибка сети") || lower.contains("unable to resolve host")
                || lower.contains("failed to connect") || lower.contains("timeout")
                || lower.contains("network") || lower.contains("socket")) {
            return "Не удалось добавить закладку: ошибка сети";
        }
        if (lower.startsWith("ошибка сервера") || lower.startsWith("ошибка (5")
                || lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("504")) {
            return "Не удалось добавить закладку: ошибка сервера";
        }
        if (lower.startsWith("ошибка обработки") || lower.startsWith("неожиданная ошибка") || lower.startsWith("ошибка (")) {
            return "Не удалось добавить закладку";
        }
        if (lower.contains("закладк")) {
            return trimmed;
        }
        return "Ошибка закладки: " + trimmed;
    }

    private void showSuccessToast(String message) {
        if (appContext == null) return;
        runOnMain(() -> CustomToast.showSuccess(appContext, message));
    }

    private void showErrorToast(String message) {
        if (appContext == null) return;
        runOnMain(() -> CustomToast.showWarning(appContext, message));
    }

    private void dispatchSuccessCallbacks(List<BookmarkManager.BookmarkAddCallback> callbacks, int episodeId) {
        if (callbacks == null || callbacks.isEmpty()) return;
        runOnMain(() -> {
            for (BookmarkManager.BookmarkAddCallback cb : callbacks) {
                try {
                    cb.onBookmarkAdded(episodeId);
                } catch (Exception e) {
                    safeLogWarn("Error in onBookmarkAdded callback: " + e.getMessage());
                }
            }
        });
    }

    private void dispatchErrorCallbacks(List<BookmarkManager.BookmarkAddCallback> callbacks, String error) {
        if (callbacks == null || callbacks.isEmpty()) return;
        runOnMain(() -> {
            for (BookmarkManager.BookmarkAddCallback cb : callbacks) {
                try {
                    cb.onBookmarkError(error);
                } catch (Exception e) {
                    safeLogWarn("Error in onBookmarkError callback: " + e.getMessage());
                }
            }
        });
    }

    private void runOnMain(Runnable runnable) {
        if (mainHandler != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runnable.run();
            } else {
                mainHandler.post(runnable);
            }
        } else {
            runnable.run();
        }
    }

    private static void safeLogDebug(String msg) {
        try {
            Log.d(TAG, msg);
        } catch (RuntimeException ignored) {}
    }

    private static void safeLogWarn(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (RuntimeException ignored) {}
    }
}
