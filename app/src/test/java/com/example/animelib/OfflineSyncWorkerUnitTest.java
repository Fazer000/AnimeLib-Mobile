package com.example.animelib;

import com.example.animelib.api.ApiService;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.PendingSyncTaskEntity;
import com.example.animelib.managers.BookmarkManager;
import com.example.animelib.managers.BookmarkSyncDispatcher;
import com.example.animelib.managers.OfflineSyncManager;
import com.example.animelib.models.AnimeBookmarkResponse;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class OfflineSyncWorkerUnitTest {

    private ExecutorService executor;

    @Before
    public void setUp() {
        BookmarkSyncDispatcher.clearOnlineBookmarkHistoryForTests();
        executor = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() {
        BookmarkSyncDispatcher.clearOnlineBookmarkHistoryForTests();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    public void testTitleMatchingAcrossNumericIdSlugAndUrl() {
        PendingSyncTaskEntity offlineTask = PendingSyncTaskEntity.createBookmarkTask(
                "25673", 9001, 0, 4, "08:15", null
        );
        offlineTask.setAnimeId("25673");

        assertTrue(OfflineSyncManager.isSameAnimeTitle(offlineTask, "25673"));
        assertTrue(OfflineSyncManager.isSameAnimeTitle(offlineTask, "25673--kaijuu-8"));
        assertTrue(OfflineSyncManager.isSameAnimeTitle(offlineTask, "https://v3.animelib.org/ru/anime/25673--kaijuu-8"));
        assertFalse(OfflineSyncManager.isSameAnimeTitle(offlineTask, "25674--other-anime"));
        assertFalse(OfflineSyncManager.isSameAnimeTitle(offlineTask, "99999"));
        assertEquals("25673", OfflineSyncManager.extractNumericAnimeId("25673--kaijuu-8"));
        assertEquals("25673", OfflineSyncManager.extractNumericAnimeId("https://animelib.me/ru/anime/25673--test?episode=1"));
    }

    @Test
    public void testOfflinePlayerNoInternetQueuesWorkerTaskAndSyncsWhenOnline() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        DownloadedEpisodeEntity downloadedEp = new DownloadedEpisodeEntity(
                "25673_9001_AniLiberty", "25673", "Kaiju", 9001, "4", "Ep 4",
                "AniLiberty", "kodik", "/tmp/ep4.mp4", 1024L, 1000L, "1080p"
        );
        store.downloadedEpisode = downloadedEp;

        FakeGateway gateway = new FakeGateway();
        gateway.canonicalSlugToReturn = "25673--kaijuu-8";
        gateway.existingBookmarkStatusToReturn = 25;
        gateway.addBookmarkResult = new ApiService.BookmarkSaveResult(true, false, 200, "OK");

        FakeScheduler scheduler = new FakeScheduler(false);
        OfflineSyncManager.TaskCoordinator coordinator = new OfflineSyncManager.TaskCoordinator(store, gateway, scheduler);

        // 1. Пользователь ставит закладку в офлайн-плеере без интернета
        coordinator.enqueueOfflinePlayerBookmark("25673", null, 9001, 0, 4, "08:15", null);

        assertEquals(1, store.getAllTasks().size());
        assertEquals(1, scheduler.scheduleCalls.get());
        assertEquals(0, gateway.bookmarkCalls.size());

        // 2. Появляется интернет -> OfflineSyncWorker запускает sync pass
        scheduler.networkAvailable.set(true);
        OfflineSyncManager.SyncPassResult passResult = coordinator.runSyncPass();

        assertFalse(passResult.hasRetryableFailure);
        assertEquals(1, passResult.processedCount);
        assertEquals(0, store.getAllTasks().size());
        assertEquals(1, gateway.bookmarkCalls.size());

        RecordedBookmarkCall sent = gateway.bookmarkCalls.get(0);
        assertEquals("25673--kaijuu-8", sent.mediaSlug);
        assertEquals(9001, sent.episodeId);
        assertEquals(4, sent.episodeNumber);
        assertEquals("08:15", sent.timecode);
        assertEquals("Kodik", sent.playerName);
        assertEquals("25", String.valueOf(sent.statusId));
        assertTrue(gateway.invalidatedSlugs.contains("25673--kaijuu-8"));
    }

    @Test
    public void testOfflinePlayerWithInternetSendsDirectlyWithoutQueueingWorkerOnSuccess() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        FakeGateway gateway = new FakeGateway();
        gateway.canonicalSlugToReturn = "111--direct-anime";
        gateway.addBookmarkResult = new ApiService.BookmarkSaveResult(true, false, 200, "OK");
        FakeScheduler scheduler = new FakeScheduler(true);

        OfflineSyncManager.TaskCoordinator coordinator = new OfflineSyncManager.TaskCoordinator(store, gateway, scheduler);
        coordinator.enqueueOfflinePlayerBookmark("111", null, 555, 0, 2, "03:20", null);

        assertEquals(1, gateway.bookmarkCalls.size());
        assertEquals(0, store.getAllTasks().size());
        assertEquals(0, scheduler.scheduleCalls.get());
    }

    @Test
    public void testOfflinePlayerUnauthorizedDoesNotQueueWorkerTask() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        FakeGateway gateway = new FakeGateway();
        gateway.authorized = false;
        FakeScheduler scheduler = new FakeScheduler(false);

        OfflineSyncManager.TaskCoordinator coordinator = new OfflineSyncManager.TaskCoordinator(store, gateway, scheduler);
        coordinator.enqueueOfflinePlayerBookmark("111", null, 555, 0, 2, "03:20", null);

        assertEquals(0, store.getAllTasks().size());
        assertEquals(0, scheduler.scheduleCalls.get());
        assertEquals(0, gateway.bookmarkCalls.size());
    }

    @Test
    public void testRetryableVsNonRetryableWorkerOutcomes() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        FakeGateway gateway = new FakeGateway();
        FakeScheduler scheduler = new FakeScheduler(false);
        OfflineSyncManager.TaskCoordinator coordinator = new OfflineSyncManager.TaskCoordinator(store, gateway, scheduler);

        // Кейс 1: временный сбой 503 -> задача остаётся с увеличенным retryCount, воркер возвращает retry
        coordinator.enqueueOfflinePlayerBookmark("200", null, 701, 0, 1, "01:05", null);
        scheduler.networkAvailable.set(true);
        gateway.canonicalSlugToReturn = "200--retry-anime";
        gateway.addBookmarkResult = new ApiService.BookmarkSaveResult(false, true, 503, "503 Service Unavailable");

        OfflineSyncManager.SyncPassResult retryPass = coordinator.runSyncPass();
        assertTrue(retryPass.hasRetryableFailure);
        assertEquals(1, store.getAllTasks().size());
        assertEquals(1, store.getAllTasks().get(0).getRetryCount());

        // Кейс 2: не-ретрайная ошибка 401 -> задача удаляется, воркер не зацикливается
        gateway.addBookmarkResult = new ApiService.BookmarkSaveResult(false, false, 401, "Unauthorized");
        OfflineSyncManager.SyncPassResult nonRetryPass = coordinator.runSyncPass();
        assertFalse(nonRetryPass.hasRetryableFailure);
        assertEquals(0, store.getAllTasks().size());
    }

    @Test
    public void testOnlinePlayerBookmarkDeletesPendingOfflineTaskAndPreventsStaleSync() throws Exception {
        InMemoryTaskStore store = new InMemoryTaskStore();
        FakeGateway gateway = new FakeGateway();
        gateway.canonicalSlugToReturn = "25673--kaijuu-8";
        gateway.addBookmarkResult = new ApiService.BookmarkSaveResult(true, false, 200, "OK");
        FakeScheduler scheduler = new FakeScheduler(false);
        OfflineSyncManager.TaskCoordinator coordinator = new OfflineSyncManager.TaskCoordinator(store, gateway, scheduler);

        // 1. В офлайн-плеере без сети накопилась задача для тайтла 25673
        coordinator.enqueueOfflinePlayerBookmark("25673", null, 9001, 0, 4, "08:15", null);
        assertEquals(1, store.getAllTasks().size());
        PendingSyncTaskEntity queuedTask = store.getAllTasks().get(0);
        queuedTask.setCreatedAt(System.currentTimeMillis() - 5000L);
        store.updateTask(queuedTask);

        // 2. Пользователь успевает поставить закладку в онлайн-плеере на этот же тайтл (по slug "25673--kaijuu-8")
        CountDownLatch onlineDone = new CountDownLatch(1);
        BookmarkSyncDispatcher dispatcher = new BookmarkSyncDispatcher(
                null,
                executor,
                null,
                request -> new ApiService.BookmarkSaveResult(true, false, 200, "OK"),
                coordinator::clearPendingBookmarkTasksForTitle
        );

        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "25673--kaijuu-8", 9005, 10, 2, "Animelib", 5, "14:00", 21, true,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        onlineDone.countDown();
                    }

                    @Override
                    public void onBookmarkError(String error) {
                        fail("Unexpected online bookmark error: " + error);
                    }
                }
        ));

        assertTrue(onlineDone.await(2, TimeUnit.SECONDS));

        // Задача удалена и воркер отменён (т.к. очередь опустела)
        assertEquals(0, store.getAllTasks().size());
        assertEquals(1, scheduler.cancelCalls.get());

        // Даже если бы устаревшая копия задачи попала в воркер, timestamp онлайн-сохранения заблокирует перезапись
        scheduler.networkAvailable.set(true);
        store.saveTask(queuedTask);
        OfflineSyncManager.SyncPassResult workerPass = coordinator.runSyncPass();
        assertFalse(workerPass.hasRetryableFailure);
        assertEquals(0, store.getAllTasks().size());
        assertEquals(0, gateway.bookmarkCalls.size());
    }

    @Test
    public void testOnlinePlayerFailureNeverQueuesWorkerTaskEvenForDownloadedEpisode() throws Exception {
        InMemoryTaskStore store = new InMemoryTaskStore();
        FakeGateway gateway = new FakeGateway();
        FakeScheduler scheduler = new FakeScheduler(true);
        OfflineSyncManager.TaskCoordinator coordinator = new OfflineSyncManager.TaskCoordinator(store, gateway, scheduler);

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger(0);

        BookmarkSyncDispatcher dispatcher = new BookmarkSyncDispatcher(
                null,
                executor,
                null,
                request -> {
                    attempts.incrementAndGet();
                    return new ApiService.BookmarkSaveResult(false, true, 503, "Ошибка сервера: 503");
                },
                coordinator::clearPendingBookmarkTasksForTitle
        );

        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "777--downloaded-in-online", 301, 5, 2, "Animelib", 3, "04:40", 21, false,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        fail("Expected failure");
                    }

                    @Override
                    public void onBookmarkError(String error) {
                        errorLatch.countDown();
                    }
                }
        ));

        assertTrue(errorLatch.await(4, TimeUnit.SECONDS));
        assertEquals(3, attempts.get());
        assertEquals(0, store.getAllTasks().size());
        assertEquals(0, scheduler.scheduleCalls.get());
    }

    private static class InMemoryTaskStore implements OfflineSyncManager.PendingTaskStore {
        private final AtomicLong nextId = new AtomicLong(1);
        private final Map<Long, PendingSyncTaskEntity> tasks = new LinkedHashMap<>();
        DownloadedEpisodeEntity downloadedEpisode;

        @Override
        public synchronized List<PendingSyncTaskEntity> getAllTasks() {
            return new ArrayList<>(tasks.values());
        }

        @Override
        public synchronized PendingSyncTaskEntity findTaskById(long id) {
            return tasks.get(id);
        }

        @Override
        public synchronized void saveTask(PendingSyncTaskEntity task) {
            if (task.getId() <= 0) {
                task.setId(nextId.getAndIncrement());
            }
            tasks.put(task.getId(), task);
        }

        @Override
        public synchronized void updateTask(PendingSyncTaskEntity task) {
            tasks.put(task.getId(), task);
        }

        @Override
        public synchronized void deleteTaskById(long id) {
            tasks.remove(id);
        }

        @Override
        public DownloadedEpisodeEntity findDownloadedEpisode(String animeId, int episodeId, String episodeNumber) {
            return downloadedEpisode;
        }
    }

    private static class RecordedBookmarkCall {
        final String mediaSlug;
        final int episodeId;
        final int teamId;
        final String playerName;
        final int episodeNumber;
        final String timecode;
        final Object statusId;

        RecordedBookmarkCall(String mediaSlug, int episodeId, int teamId, String playerName,
                             int episodeNumber, String timecode, Object statusId) {
            this.mediaSlug = mediaSlug;
            this.episodeId = episodeId;
            this.teamId = teamId;
            this.playerName = playerName;
            this.episodeNumber = episodeNumber;
            this.timecode = timecode;
            this.statusId = statusId;
        }
    }

    private static class FakeGateway implements OfflineSyncManager.SyncRemoteGateway {
        boolean authorized = true;
        String canonicalSlugToReturn;
        int existingBookmarkStatusToReturn = 0;
        ApiService.BookmarkSaveResult addBookmarkResult = new ApiService.BookmarkSaveResult(true, false, 200, "OK");
        final List<RecordedBookmarkCall> bookmarkCalls = Collections.synchronizedList(new ArrayList<>());
        final List<String> invalidatedSlugs = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean isAuthorized() {
            return authorized;
        }

        @Override
        public ApiService.AnimeInfoSyncResult fetchAnimeInfoSync(String animeIdOrSlug) {
            if (canonicalSlugToReturn == null) {
                return new ApiService.AnimeInfoSyncResult(false, false, 404, null, "Not found");
            }
            AnimeInfoResponse info = new com.google.gson.Gson().fromJson(
                    "{\"data\":{\"slug_url\":\"" + canonicalSlugToReturn + "\"}}",
                    AnimeInfoResponse.class
            );
            return new ApiService.AnimeInfoSyncResult(true, false, 200, info, null);
        }

        @Override
        public EpisodesListResponse fetchEpisodesListSync(String animeId) {
            return null;
        }

        @Override
        public EpisodeResponse fetchEpisodeDataSync(int episodeId) {
            return null;
        }

        @Override
        public AnimeBookmarkResponse fetchAnimeBookmarkSync(String mediaSlug) {
            if (existingBookmarkStatusToReturn <= 0) return null;
            AnimeBookmarkResponse res = new AnimeBookmarkResponse();
            AnimeBookmarkResponse.BookmarkData data = new AnimeBookmarkResponse.BookmarkData();
            data.setStatus(existingBookmarkStatusToReturn);
            res.setData(data);
            return res;
        }

        @Override
        public ApiService.BookmarkSaveResult addBookmarkSync(String mediaSlug, int episodeId, int teamId,
                                                             int translationType, String playerName,
                                                             int episodeNumber, String timecode, Object statusId) {
            bookmarkCalls.add(new RecordedBookmarkCall(mediaSlug, episodeId, teamId, playerName, episodeNumber, timecode, statusId));
            return addBookmarkResult;
        }

        @Override
        public boolean markEpisodeViewedSync(String animeId, int playerId) {
            return true;
        }

        @Override
        public void invalidateAnimeBookmarkCache(String mediaSlug) {
            invalidatedSlugs.add(mediaSlug);
        }

        @Override
        public boolean wasOnlineBookmarkSavedAfter(String animeId, String mediaSlug, long taskCreatedAtMs) {
            return BookmarkSyncDispatcher.wasOnlineBookmarkSavedAfter(animeId, mediaSlug, taskCreatedAtMs);
        }
    }

    private static class FakeScheduler implements OfflineSyncManager.SyncScheduler {
        final AtomicBoolean networkAvailable;
        final AtomicInteger scheduleCalls = new AtomicInteger(0);
        final AtomicInteger cancelCalls = new AtomicInteger(0);

        FakeScheduler(boolean initialNetworkAvailable) {
            this.networkAvailable = new AtomicBoolean(initialNetworkAvailable);
        }

        @Override
        public boolean isNetworkAvailable() {
            return networkAvailable.get();
        }

        @Override
        public void scheduleSync() {
            scheduleCalls.incrementAndGet();
        }

        @Override
        public void cancelSync() {
            cancelCalls.incrementAndGet();
        }
    }
}
