package com.example.animelib.managers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.PendingSyncTaskEntity;
import com.example.animelib.models.AnimeBookmarkResponse;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class OfflineSyncManager {
    private static final String TAG = "OfflineSyncManager";
    private static final String WORK_NAME = "offline_sync_work";
    private static final long MAX_TASK_AGE_MS = 24L * 60L * 60L * 1000L; // 24 часа
    private static final int MAX_RETRIES = 5;

    private static volatile OfflineSyncManager instance;

    public static class SyncPassResult {
        public final int processedCount;
        public final boolean hasRetryableFailure;

        public SyncPassResult(int processedCount, boolean hasRetryableFailure) {
            this.processedCount = processedCount;
            this.hasRetryableFailure = hasRetryableFailure;
        }
    }

    public interface PendingTaskStore {
        List<PendingSyncTaskEntity> getAllTasks();
        @Nullable PendingSyncTaskEntity findTaskById(long id);
        void saveTask(PendingSyncTaskEntity task);
        void updateTask(PendingSyncTaskEntity task);
        void deleteTaskById(long id);
        @Nullable DownloadedEpisodeEntity findDownloadedEpisode(String animeId, int episodeId, String episodeNumber);
    }

    public interface SyncRemoteGateway {
        boolean isAuthorized();
        @Nullable ApiService.AnimeInfoSyncResult fetchAnimeInfoSync(String animeIdOrSlug);
        @Nullable EpisodesListResponse fetchEpisodesListSync(String animeId);
        @Nullable EpisodeResponse fetchEpisodeDataSync(int episodeId);
        @Nullable AnimeBookmarkResponse fetchAnimeBookmarkSync(String mediaSlug);
        ApiService.BookmarkSaveResult addBookmarkSync(String mediaSlug, int episodeId, int teamId,
                                                     int translationType, String playerName,
                                                     int episodeNumber, String timecode, Object statusId);
        boolean markEpisodeViewedSync(String animeId, int playerId);
        void invalidateAnimeBookmarkCache(String mediaSlug);
        boolean wasOnlineBookmarkSavedAfter(@Nullable String animeId, @Nullable String mediaSlug, long taskCreatedAtMs);
    }

    public interface SyncScheduler {
        boolean isNetworkAvailable();
        void scheduleSync();
        void cancelSync();
    }

    private final Context context;
    private final DatabaseManager databaseManager;
    private final ApiService apiService;
    private final ExecutorService executor;
    private final TaskCoordinator coordinator;
    private final Object syncExecutionLock = new Object();
    private final AtomicBoolean isAsyncSyncing = new AtomicBoolean(false);
    private final AtomicBoolean pendingAsyncResync = new AtomicBoolean(false);
    private boolean isNetworkCallbackRegistered = false;

    private OfflineSyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.databaseManager = DatabaseManager.getInstance(this.context);
        this.apiService = new ApiService(this.context);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "offline-sync-worker");
            t.setDaemon(true);
            return t;
        });

        PendingTaskStore store = new PendingTaskStore() {
            @Override
            public List<PendingSyncTaskEntity> getAllTasks() {
                return databaseManager.getAllPendingSyncTasksSync();
            }

            @Override
            public PendingSyncTaskEntity findTaskById(long id) {
                return databaseManager.findPendingSyncTaskByIdSync(id);
            }

            @Override
            public void saveTask(PendingSyncTaskEntity task) {
                databaseManager.savePendingSyncTask(task);
            }

            @Override
            public void updateTask(PendingSyncTaskEntity task) {
                databaseManager.updatePendingSyncTask(task);
            }

            @Override
            public void deleteTaskById(long id) {
                databaseManager.deletePendingSyncTaskById(id);
            }

            @Override
            public DownloadedEpisodeEntity findDownloadedEpisode(String animeId, int episodeId, String episodeNumber) {
                if (animeId == null || animeId.trim().isEmpty()) return null;
                DownloadedEpisodeEntity ep = databaseManager.findDownloadedEpisode(animeId, episodeId, episodeNumber, null);
                if (ep == null) {
                    String numericId = extractNumericAnimeId(animeId);
                    if (numericId != null && !numericId.equals(animeId)) {
                        ep = databaseManager.findDownloadedEpisode(numericId, episodeId, episodeNumber, null);
                    }
                }
                return ep;
            }
        };

        SyncRemoteGateway gateway = new SyncRemoteGateway() {
            @Override
            public boolean isAuthorized() {
                return apiService.isAuthorized();
            }

            @Override
            public ApiService.AnimeInfoSyncResult fetchAnimeInfoSync(String animeIdOrSlug) {
                return apiService.fetchAnimeInfoSync(animeIdOrSlug);
            }

            @Override
            public EpisodesListResponse fetchEpisodesListSync(String animeId) {
                return apiService.fetchEpisodesListSync(animeId);
            }

            @Override
            public EpisodeResponse fetchEpisodeDataSync(int episodeId) {
                return apiService.fetchEpisodeDataSync(episodeId);
            }

            @Override
            public AnimeBookmarkResponse fetchAnimeBookmarkSync(String mediaSlug) {
                return apiService.fetchAnimeBookmarkSync(mediaSlug);
            }

            @Override
            public ApiService.BookmarkSaveResult addBookmarkSync(String mediaSlug, int episodeId, int teamId,
                                                                 int translationType, String playerName,
                                                                 int episodeNumber, String timecode, Object statusId) {
                return apiService.addBookmarkSync(
                        mediaSlug, episodeId, teamId, translationType, playerName, episodeNumber, timecode, statusId
                );
            }

            @Override
            public boolean markEpisodeViewedSync(String animeId, int playerId) {
                return apiService.markEpisodeViewedSync(animeId, playerId);
            }

            @Override
            public void invalidateAnimeBookmarkCache(String mediaSlug) {
                apiService.invalidateAnimeBookmarkCache(mediaSlug);
            }

            @Override
            public boolean wasOnlineBookmarkSavedAfter(String animeId, String mediaSlug, long taskCreatedAtMs) {
                return BookmarkSyncDispatcher.wasOnlineBookmarkSavedAfter(animeId, mediaSlug, taskCreatedAtMs);
            }
        };

        SyncScheduler scheduler = new SyncScheduler() {
            @Override
            public boolean isNetworkAvailable() {
                return OfflineSyncManager.isNetworkAvailable(OfflineSyncManager.this.context);
            }

            @Override
            public void scheduleSync() {
                scheduleWorkManagerSync();
            }

            @Override
            public void cancelSync() {
                cancelWorkManagerSync();
            }
        };

        this.coordinator = new TaskCoordinator(store, gateway, scheduler);
        registerNetworkCallback();

        executor.execute(() -> {
            try {
                if (isNetworkAvailable(this.context)) {
                    List<PendingSyncTaskEntity> pending = databaseManager.getAllPendingSyncTasksSync();
                    if (pending != null && !pending.isEmpty()) {
                        syncPendingTasksNow();
                    }
                }
            } catch (Exception e) {
                safeLogWarn("Initial pending tasks check failed: " + e.getMessage());
            }
        });
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
            safeLogError("Error checking network availability", e);
            return false;
        }
    }

    @Nullable
    public static String extractNumericAnimeId(@Nullable String animeIdOrSlug) {
        if (animeIdOrSlug == null) return null;
        String cleaned = animeIdOrSlug.trim();
        if (cleaned.isEmpty()) return null;

        int qIdx = cleaned.indexOf('?');
        if (qIdx >= 0) cleaned = cleaned.substring(0, qIdx);
        int hIdx = cleaned.indexOf('#');
        if (hIdx >= 0) cleaned = cleaned.substring(0, hIdx);

        String[] parts = cleaned.split("/");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int dash = part.indexOf("--");
            if (dash > 0) {
                String prefix = part.substring(0, dash).trim();
                if (prefix.matches("\\d+")) {
                    return prefix;
                }
            }
        }
        for (String part : parts) {
            if (part.matches("\\d+")) {
                return part;
            }
        }
        for (String part : parts) {
            if (part.matches("^\\d+.*")) {
                return part.replaceFirst("^(\\d+).*", "$1");
            }
        }
        return null;
    }

    public static boolean isSameAnimeTitle(@Nullable PendingSyncTaskEntity task, @Nullable String animeIdOrSlug) {
        if (task == null || animeIdOrSlug == null) return false;
        String targetTrimmed = animeIdOrSlug.trim();
        if (targetTrimmed.isEmpty()) return false;

        String targetNumeric = extractNumericAnimeId(targetTrimmed);
        String taskAnimeId = task.getAnimeId() != null ? task.getAnimeId().trim() : "";
        String taskMediaSlug = task.getMediaSlug() != null ? task.getMediaSlug().trim() : "";

        if (!taskAnimeId.isEmpty() && targetTrimmed.equalsIgnoreCase(taskAnimeId)) {
            return true;
        }
        if (!taskMediaSlug.isEmpty() && targetTrimmed.equalsIgnoreCase(taskMediaSlug)) {
            return true;
        }
        if (targetNumeric != null) {
            String taskAnimeNum = extractNumericAnimeId(taskAnimeId);
            if (targetNumeric.equals(taskAnimeNum)) {
                return true;
            }
            String taskSlugNum = extractNumericAnimeId(taskMediaSlug);
            if (targetNumeric.equals(taskSlugNum)) {
                return true;
            }
        }
        return false;
    }

    public void enqueueViewTask(String animeId, int playerId) {
        if (animeId == null || animeId.isEmpty() || playerId <= 0) {
            safeLogError("Cannot enqueue VIEW task with invalid parameters: animeId=" + animeId + ", playerId=" + playerId, null);
            return;
        }

        executor.execute(() -> {
            boolean success = false;
            if (isNetworkAvailable(context)) {
                safeLogDebug("Network available, trying to send VIEW request immediately");
                success = apiService.markEpisodeViewedSync(animeId, playerId);
            }

            if (!success) {
                safeLogWarn("VIEW request offline or failed. Queueing pending task in database");
                PendingSyncTaskEntity task = PendingSyncTaskEntity.createViewTask(animeId, playerId);
                coordinator.saveViewTask(task);
                scheduleWorkManagerSync();
            } else {
                safeLogDebug("VIEW request sent successfully on first try");
            }
        });
    }

    /**
     * Вызывается ТОЛЬКО из офлайн-плеера после сохранения локальной офлайн-закладки в Room.
     * Если интернета нет — ставит задачу в PendingSyncTasks и планирует OfflineSyncWorker.
     * Если интернет есть — пытается сразу отправить на API без создания воркера (и ставит в воркер только при сетевом сбое).
     */
    public void handleOfflinePlayerBookmark(@Nullable String animeId,
                                            @Nullable String mediaSlug,
                                            int episodeId,
                                            int teamId,
                                            int episodeNumber,
                                            @Nullable String timecode,
                                            @Nullable Object statusId) {
        executor.execute(() -> coordinator.enqueueOfflinePlayerBookmark(
                animeId, mediaSlug, episodeId, teamId, episodeNumber, timecode, statusId
        ));
    }

    public void saveOrReplacePendingBookmarkTask(String mediaSlug, int episodeId, int teamId,
                                                 int episodeNumber, String timecode, Object statusId) {
        saveOrReplacePendingBookmarkTask(extractNumericAnimeId(mediaSlug), mediaSlug, episodeId, teamId, episodeNumber, timecode, statusId);
    }

    public void saveOrReplacePendingBookmarkTask(@Nullable String animeId, @Nullable String mediaSlug,
                                                 int episodeId, int teamId, int episodeNumber,
                                                 @Nullable String timecode, @Nullable Object statusId) {
        executor.execute(() -> coordinator.saveOrReplacePendingBookmarkTask(
                animeId, mediaSlug, episodeId, teamId, episodeNumber, timecode, statusId
        ));
    }

    public void clearPendingBookmarkTasksForSlug(String mediaSlug) {
        clearPendingBookmarkTasksForTitle(mediaSlug);
    }

    public void clearPendingBookmarkTasksForTitle(@Nullable String animeIdOrSlug) {
        if (animeIdOrSlug == null || animeIdOrSlug.trim().isEmpty()) {
            return;
        }
        executor.execute(() -> coordinator.clearPendingBookmarkTasksForTitle(animeIdOrSlug));
    }

    public void clearPendingBookmarkTasksForTitleBlocking(@Nullable String animeIdOrSlug) {
        if (animeIdOrSlug == null || animeIdOrSlug.trim().isEmpty()) {
            return;
        }
        coordinator.clearPendingBookmarkTasksForTitle(animeIdOrSlug);
    }

    public void syncPendingTasksNow() {
        if (!isAsyncSyncing.compareAndSet(false, true)) {
            pendingAsyncResync.set(true);
            return;
        }
        executor.execute(() -> {
            try {
                do {
                    pendingAsyncResync.set(false);
                    executeWorkerSyncBlocking();
                } while (pendingAsyncResync.get() && isNetworkAvailable(context));
            } finally {
                isAsyncSyncing.set(false);
            }
        });
    }

    public SyncPassResult executeWorkerSyncBlocking() {
        synchronized (syncExecutionLock) {
            return coordinator.runSyncPass();
        }
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
            safeLogDebug("Scheduled WorkManager offline sync task successfully");
        } catch (Exception e) {
            safeLogError("Error scheduling WorkManager task", e);
        }
    }

    public void cancelWorkManagerSync() {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
            safeLogDebug("Cancelled WorkManager offline sync task (no pending tasks left)");
        } catch (Exception e) {
            safeLogError("Error cancelling WorkManager task", e);
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
                    safeLogDebug("Network became available! Triggering syncPendingTasksNow");
                    syncPendingTasksNow();
                }
            });
            isNetworkCallbackRegistered = true;
            safeLogDebug("Registered network callback for immediate sync on reconnection");
        } catch (Exception e) {
            safeLogError("Failed to register network callback", e);
        }
    }

    /**
     * Чистая тестируемая логика координатора задач офлайн-синхронизации.
     */
    public static class TaskCoordinator {
        private final PendingTaskStore store;
        private final SyncRemoteGateway gateway;
        private final SyncScheduler scheduler;
        private final Object dbLock = new Object();

        public TaskCoordinator(@NonNull PendingTaskStore store,
                               @NonNull SyncRemoteGateway gateway,
                               @NonNull SyncScheduler scheduler) {
            this.store = store;
            this.gateway = gateway;
            this.scheduler = scheduler;
        }

        public void saveViewTask(PendingSyncTaskEntity task) {
            if (task == null) return;
            synchronized (dbLock) {
                store.saveTask(task);
            }
        }

        public void enqueueOfflinePlayerBookmark(@Nullable String animeId,
                                                 @Nullable String mediaSlug,
                                                 int episodeId,
                                                 int teamId,
                                                 int episodeNumber,
                                                 @Nullable String timecode,
                                                 @Nullable Object statusId) {
            if (!gateway.isAuthorized()) {
                safeLogDebug("Offline bookmark saved locally only: user is not authorized for API sync");
                return;
            }

            PendingSyncTaskEntity candidate = buildBookmarkTaskEntity(
                    animeId, mediaSlug, episodeId, teamId, episodeNumber, timecode, statusId
            );
            if (candidate == null) {
                return;
            }

            String titleKey = candidate.getAnimeId() != null ? candidate.getAnimeId() : candidate.getMediaSlug();
            if (!scheduler.isNetworkAvailable()) {
                saveOrReplaceBookmarkLocked(candidate, titleKey);
                scheduler.scheduleSync();
                safeLogDebug("Offline mode & no network: queued BOOKMARK worker task for " + titleKey
                        + " ep#" + candidate.getEpisodeNumber() + " (" + candidate.getTimecode() + ")");
                return;
            }

            // Интернет есть даже при запуске офлайн-плеера: пробуем сразу отправить на API без очереди воркера
            clearPendingBookmarkTasksLocked(titleKey);
            if (gateway.wasOnlineBookmarkSavedAfter(candidate.getAnimeId(), candidate.getMediaSlug(), candidate.getCreatedAt())) {
                return;
            }

            TaskOutcome directOutcome = executeBookmarkTask(candidate, false);
            if (directOutcome == TaskOutcome.RETRY) {
                saveOrReplaceBookmarkLocked(candidate, titleKey);
                scheduler.scheduleSync();
                safeLogWarn("Direct sync from offline player hit retryable failure; queued BOOKMARK worker task for " + titleKey);
            } else {
                safeLogDebug("Direct bookmark sync from offline player finished with outcome=" + directOutcome);
            }
        }

        public void saveOrReplacePendingBookmarkTask(@Nullable String animeId,
                                                     @Nullable String mediaSlug,
                                                     int episodeId,
                                                     int teamId,
                                                     int episodeNumber,
                                                     @Nullable String timecode,
                                                     @Nullable Object statusId) {
            PendingSyncTaskEntity task = buildBookmarkTaskEntity(
                    animeId, mediaSlug, episodeId, teamId, episodeNumber, timecode, statusId
            );
            if (task == null) return;
            String titleKey = task.getAnimeId() != null ? task.getAnimeId() : task.getMediaSlug();
            saveOrReplaceBookmarkLocked(task, titleKey);
            scheduler.scheduleSync();
        }

        public void clearPendingBookmarkTasksForTitle(@Nullable String animeIdOrSlug) {
            if (animeIdOrSlug == null || animeIdOrSlug.trim().isEmpty()) {
                return;
            }
            boolean cancelScheduler;
            synchronized (dbLock) {
                clearPendingBookmarkTasksLocked(animeIdOrSlug);
                List<PendingSyncTaskEntity> remaining = store.getAllTasks();
                cancelScheduler = (remaining == null || remaining.isEmpty());
            }
            if (cancelScheduler) {
                scheduler.cancelSync();
            }
        }

        public SyncPassResult runSyncPass() {
            if (!scheduler.isNetworkAvailable()) {
                safeLogDebug("Sync pass skipped: network is not available");
                return new SyncPassResult(0, false);
            }

            List<PendingSyncTaskEntity> tasks;
            synchronized (dbLock) {
                List<PendingSyncTaskEntity> loaded = store.getAllTasks();
                tasks = loaded != null ? new ArrayList<>(loaded) : new ArrayList<>();
            }

            if (tasks.isEmpty()) {
                return new SyncPassResult(0, false);
            }

            int processed = 0;
            boolean hasRetryableFailure = false;
            long now = System.currentTimeMillis();

            for (PendingSyncTaskEntity task : tasks) {
                if (task == null) continue;
                processed++;

                if ("VIEW".equalsIgnoreCase(task.getTaskType())) {
                    if (isExpiredOrExhausted(task, now)) {
                        deleteTaskSafe(task.getId());
                        continue;
                    }
                    boolean success = false;
                    try {
                        success = gateway.markEpisodeViewedSync(task.getAnimeId(), task.getPlayerId());
                    } catch (Exception e) {
                        safeLogError("Error syncing VIEW task ID " + task.getId(), e);
                    }
                    if (success) {
                        deleteTaskSafe(task.getId());
                    } else {
                        markRetryableFailure(task);
                        hasRetryableFailure = true;
                    }
                } else if ("BOOKMARK".equalsIgnoreCase(task.getTaskType())) {
                    TaskOutcome outcome = processQueuedBookmarkTask(task, now);
                    if (outcome == TaskOutcome.RETRY) {
                        hasRetryableFailure = true;
                    }
                } else {
                    deleteTaskSafe(task.getId());
                }
            }

            return new SyncPassResult(processed, hasRetryableFailure);
        }

        private TaskOutcome processQueuedBookmarkTask(PendingSyncTaskEntity task, long now) {
            if (isExpiredOrExhausted(task, now)) {
                deleteTaskSafe(task.getId());
                return TaskOutcome.DONE;
            }
            if (!gateway.isAuthorized()) {
                safeLogWarn("Dropping BOOKMARK sync task ID " + task.getId() + ": user not authorized");
                deleteTaskSafe(task.getId());
                return TaskOutcome.DONE;
            }

            if (!isTaskStillActiveAndRelevant(task)) {
                deleteTaskSafe(task.getId());
                return TaskOutcome.DONE;
            }

            TaskOutcome outcome = executeBookmarkTask(task, true);
            if (outcome == TaskOutcome.DONE) {
                deleteTaskSafe(task.getId());
            } else {
                markRetryableFailure(task);
            }
            return outcome;
        }

        private TaskOutcome executeBookmarkTask(PendingSyncTaskEntity task, boolean verifyStoredTask) {
            String initialIdentifier = firstNonEmpty(task.getMediaSlug(), task.getAnimeId());
            if (initialIdentifier == null) {
                return TaskOutcome.DONE;
            }

            // 1. Разрешаем канонический media_slug (вида "25673--slug")
            String canonicalSlug = initialIdentifier;
            String numericAnimeId = extractNumericAnimeId(firstNonEmpty(task.getAnimeId(), canonicalSlug));
            if (!canonicalSlug.contains("--")) {
                ApiService.AnimeInfoSyncResult infoRes = gateway.fetchAnimeInfoSync(canonicalSlug);
                if (infoRes != null && infoRes.success && infoRes.response != null && infoRes.response.getData() != null) {
                    AnimeInfoResponse.Data data = infoRes.response.getData();
                    if (data.getSlug_url() != null && !data.getSlug_url().trim().isEmpty()) {
                        canonicalSlug = data.getSlug_url().trim();
                    }
                    if (numericAnimeId == null && data.getId() > 0) {
                        numericAnimeId = String.valueOf(data.getId());
                    }
                } else if (infoRes != null && !infoRes.success && infoRes.retryable) {
                    return TaskOutcome.RETRY;
                }
            }

            // 2. Разрешаем episodeId, если он не был известен (например, для старых загрузок)
            int episodeId = task.getEpisodeId();
            int episodeNumber = task.getEpisodeNumber() > 0 ? task.getEpisodeNumber() : 1;
            if (episodeId <= 0) {
                String episodesQueryId = numericAnimeId != null ? numericAnimeId : canonicalSlug;
                EpisodesListResponse epListRes = gateway.fetchEpisodesListSync(episodesQueryId);
                if (epListRes != null && epListRes.getData() != null) {
                    String targetNum = String.valueOf(episodeNumber);
                    for (EpisodesListResponse.EpisodeItem ep : epListRes.getData()) {
                        if (ep != null && ep.getNumber() != null && targetNum.equals(ep.getNumber().trim())) {
                            episodeId = ep.getId();
                            break;
                        }
                    }
                }
                if (episodeId <= 0) {
                    // Если список эпизодов не загрузился (возможный обрыв сети) — даём ретрай
                    return epListRes == null ? TaskOutcome.RETRY : TaskOutcome.DONE;
                }
            }

            // 3. Определяем playerName, teamId, translationType и текущий статус просмотра (best-effort)
            String playerName = "Animelib";
            int teamId = task.getTeamId();
            int translationType = 2;
            Object statusId = task.getStatusId();

            DownloadedEpisodeEntity downloadedEp = store.findDownloadedEpisode(
                    firstNonEmpty(task.getAnimeId(), numericAnimeId, canonicalSlug),
                    episodeId,
                    String.valueOf(episodeNumber)
            );
            if (downloadedEp != null) {
                if (downloadedEp.getPlayerType() != null && !downloadedEp.getPlayerType().trim().isEmpty()) {
                    playerName = normalizePlayerName(downloadedEp.getPlayerType());
                }
                if (teamId <= 0 && downloadedEp.getTeamName() != null && !downloadedEp.getTeamName().trim().isEmpty()) {
                    EpisodeResponse epData = gateway.fetchEpisodeDataSync(episodeId);
                    if (epData != null && epData.getData() != null && epData.getData().getPlayers() != null) {
                        EpisodeResponse.PlayerData matched = findMatchingPlayer(
                                epData.getData().getPlayers(),
                                downloadedEp.getTeamName().trim(),
                                downloadedEp.getPlayerType()
                        );
                        if (matched != null) {
                            if (matched.getTeam() != null && matched.getTeam().getId() > 0) {
                                teamId = matched.getTeam().getId();
                            }
                            if (matched.getTranslationType() != null && matched.getTranslationType().getId() > 0) {
                                translationType = matched.getTranslationType().getId();
                            }
                            if (matched.getPlayer() != null && !matched.getPlayer().trim().isEmpty()) {
                                playerName = normalizePlayerName(matched.getPlayer());
                            }
                        }
                    }
                }
            }

            if (statusId == null) {
                AnimeBookmarkResponse existingBm = gateway.fetchAnimeBookmarkSync(canonicalSlug);
                if (existingBm != null && existingBm.getData() != null) {
                    if (existingBm.getData().getStatus() > 0) {
                        statusId = existingBm.getData().getStatus();
                    }
                }
            }

            // 4. Финальная проверка перед отправкой: вдруг за это время пользователь поставил закладку в онлайн-плеере
            if (verifyStoredTask && !isTaskStillActiveAndRelevant(task)) {
                safeLogDebug("BOOKMARK task ID " + task.getId() + " superseded by online player bookmark, skipping");
                return TaskOutcome.DONE;
            }
            if (!verifyStoredTask && gateway.wasOnlineBookmarkSavedAfter(task.getAnimeId(), canonicalSlug, task.getCreatedAt())) {
                return TaskOutcome.DONE;
            }

            String safeTimecode = (task.getTimecode() != null && !task.getTimecode().trim().isEmpty())
                    ? task.getTimecode().trim()
                    : "00:00";

            try {
                ApiService.BookmarkSaveResult res = gateway.addBookmarkSync(
                        canonicalSlug,
                        episodeId,
                        teamId,
                        translationType,
                        playerName,
                        episodeNumber,
                        safeTimecode,
                        statusId
                );
                if (res != null && res.success) {
                    gateway.invalidateAnimeBookmarkCache(canonicalSlug);
                    if (numericAnimeId != null && !numericAnimeId.equals(canonicalSlug)) {
                        gateway.invalidateAnimeBookmarkCache(numericAnimeId);
                    }
                    safeLogDebug("Synced offline BOOKMARK to API: slug=" + canonicalSlug
                            + ", epId=" + episodeId + ", timecode=" + safeTimecode);
                    return TaskOutcome.DONE;
                }
                if (res != null && !res.retryable) {
                    safeLogWarn("BOOKMARK task non-retryable failure (" + res.httpCode + "): " + res.message);
                    return TaskOutcome.DONE;
                }
                safeLogWarn("BOOKMARK task retryable failure: " + (res != null ? res.message : "null"));
                return TaskOutcome.RETRY;
            } catch (Exception e) {
                safeLogError("Exception executing BOOKMARK sync task", e);
                return TaskOutcome.RETRY;
            }
        }

        private boolean isTaskStillActiveAndRelevant(PendingSyncTaskEntity task) {
            if (task.getId() > 0) {
                synchronized (dbLock) {
                    PendingSyncTaskEntity stored = store.findTaskById(task.getId());
                    if (stored == null) {
                        return false;
                    }
                }
            }
            return !gateway.wasOnlineBookmarkSavedAfter(task.getAnimeId(), task.getMediaSlug(), task.getCreatedAt());
        }

        private void saveOrReplaceBookmarkLocked(PendingSyncTaskEntity task, String titleKey) {
            synchronized (dbLock) {
                clearPendingBookmarkTasksLocked(titleKey);
                store.saveTask(task);
            }
        }

        private void clearPendingBookmarkTasksLocked(String animeIdOrSlug) {
            List<PendingSyncTaskEntity> tasks = store.getAllTasks();
            if (tasks == null) return;
            for (PendingSyncTaskEntity existing : tasks) {
                if (existing != null
                        && "BOOKMARK".equalsIgnoreCase(existing.getTaskType())
                        && isSameAnimeTitle(existing, animeIdOrSlug)) {
                    store.deleteTaskById(existing.getId());
                }
            }
        }

        private void deleteTaskSafe(long id) {
            if (id <= 0) return;
            synchronized (dbLock) {
                store.deleteTaskById(id);
            }
        }

        private void markRetryableFailure(PendingSyncTaskEntity task) {
            synchronized (dbLock) {
                task.setRetryCount(task.getRetryCount() + 1);
                store.updateTask(task);
            }
        }

        private static boolean isExpiredOrExhausted(PendingSyncTaskEntity task, long now) {
            return (task.getCreatedAt() > 0 && now - task.getCreatedAt() > MAX_TASK_AGE_MS)
                    || task.getRetryCount() >= MAX_RETRIES;
        }

        @Nullable
        private static PendingSyncTaskEntity buildBookmarkTaskEntity(@Nullable String animeId,
                                                                     @Nullable String mediaSlug,
                                                                     int episodeId,
                                                                     int teamId,
                                                                     int episodeNumber,
                                                                     @Nullable String timecode,
                                                                     @Nullable Object statusId) {
            String cleanAnimeId = animeId != null ? animeId.trim() : "";
            String cleanSlug = mediaSlug != null ? mediaSlug.trim() : "";
            String numericId = extractNumericAnimeId(!cleanAnimeId.isEmpty() ? cleanAnimeId : cleanSlug);
            String effectiveAnimeId = numericId != null
                    ? numericId
                    : (!cleanAnimeId.isEmpty() ? cleanAnimeId : cleanSlug);
            String effectiveSlug = !cleanSlug.isEmpty() ? cleanSlug : effectiveAnimeId;

            if (effectiveAnimeId.isEmpty() && effectiveSlug.isEmpty()) {
                return null;
            }
            if (episodeId <= 0 && episodeNumber <= 0) {
                return null;
            }

            PendingSyncTaskEntity entity = PendingSyncTaskEntity.createBookmarkTask(
                    effectiveSlug,
                    Math.max(0, episodeId),
                    Math.max(0, teamId),
                    episodeNumber > 0 ? episodeNumber : 1,
                    (timecode != null && !timecode.trim().isEmpty()) ? timecode.trim() : "00:00",
                    statusId
            );
            entity.setAnimeId(effectiveAnimeId);
            return entity;
        }

        @Nullable
        private static EpisodeResponse.PlayerData findMatchingPlayer(List<EpisodeResponse.PlayerData> players,
                                                                     String targetTeamName,
                                                                     @Nullable String targetPlayerType) {
            if (players == null || targetTeamName == null) return null;
            for (EpisodeResponse.PlayerData p : players) {
                if (p == null || p.getTeam() == null || p.getTeam().getName() == null) continue;
                if (targetTeamName.equalsIgnoreCase(p.getTeam().getName().trim())
                        && (targetPlayerType == null || targetPlayerType.trim().isEmpty()
                        || (p.getPlayer() != null && targetPlayerType.trim().equalsIgnoreCase(p.getPlayer().trim())))) {
                    return p;
                }
            }
            for (EpisodeResponse.PlayerData p : players) {
                if (p == null || p.getTeam() == null || p.getTeam().getName() == null) continue;
                if (targetTeamName.equalsIgnoreCase(p.getTeam().getName().trim())) {
                    return p;
                }
            }
            return null;
        }

        private static String normalizePlayerName(String rawPlayer) {
            if (rawPlayer == null || rawPlayer.trim().isEmpty()) return "Animelib";
            String trimmed = rawPlayer.trim();
            if ("animelib".equalsIgnoreCase(trimmed)) return "Animelib";
            if ("kodik".equalsIgnoreCase(trimmed)) return "Kodik";
            return trimmed;
        }

        @Nullable
        private static String firstNonEmpty(String... candidates) {
            if (candidates == null) return null;
            for (String candidate : candidates) {
                if (candidate != null && !candidate.trim().isEmpty()) {
                    return candidate.trim();
                }
            }
            return null;
        }

        private enum TaskOutcome {
            DONE,
            RETRY
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

    private static void safeLogError(String msg, @Nullable Throwable t) {
        try {
            if (t != null) Log.e(TAG, msg, t);
            else Log.e(TAG, msg);
        } catch (RuntimeException ignored) {}
    }
}
