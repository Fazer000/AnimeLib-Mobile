package com.example.animelib;

import com.example.animelib.api.ApiService;
import com.example.animelib.managers.BookmarkManager;
import com.example.animelib.managers.BookmarkSyncDispatcher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class BookmarkSyncDispatcherUnitTest {

    private ExecutorService executor;

    @Before
    public void setUp() {
        executor = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    public void testRapidExitAutoSavesCoalesceAndLatestTimecodeWins() throws Exception {
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        CountDownLatch allCallbacksDone = new CountDownLatch(3);

        List<String> sentTimecodes = Collections.synchronizedList(new ArrayList<>());

        BookmarkSyncDispatcher dispatcher = new BookmarkSyncDispatcher(
                null,
                executor,
                null,
                request -> {
                    sentTimecodes.add(request.timecode);
                    if (sentTimecodes.size() == 1) {
                        firstRequestStarted.countDown();
                        try {
                            releaseFirstRequest.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return new ApiService.BookmarkSaveResult(true, false, 200, "OK");
                }
        );

        BookmarkManager.BookmarkAddCallback cb = new BookmarkManager.BookmarkAddCallback() {
            @Override
            public void onBookmarkAdded(int episodeId) {
                allCallbacksDone.countDown();
            }

            @Override
            public void onBookmarkError(String error) {
                fail("Unexpected error: " + error);
            }
        };

        // 1. Первое сохранение уходит в сеть (например, при нажатии Назад / finish())
        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "23811--kaijuu-8", 101, 5, 2, "Animelib", 3, "10:15", 1, false, cb
        ));
        assertTrue(firstRequestStarted.await(2, TimeUnit.SECONDS));

        // 2. Пока первый запрос летит, прилетают каскадные вызовы onPause() и onStop() с уточненным временем
        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "23811--kaijuu-8", 101, 5, 2, "Animelib", 3, "10:16", null, false, cb
        ));
        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "23811--kaijuu-8", 101, 5, 2, "Animelib", 3, "10:18", null, false, cb
        ));

        releaseFirstRequest.countDown();
        assertTrue(allCallbacksDone.await(3, TimeUnit.SECONDS));

        // Второй и третий вызовы склеились в один финальный запрос с самым свежим таймкодом (10:18)
        assertEquals(2, sentTimecodes.size());
        assertEquals("10:15", sentTimecodes.get(0));
        assertEquals("10:18", sentTimecodes.get(1));
    }

    @Test
    public void testTransientNetworkFailureRetriesAndSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);

        BookmarkSyncDispatcher dispatcher = new BookmarkSyncDispatcher(
                null,
                executor,
                null,
                request -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        return new ApiService.BookmarkSaveResult(false, true, 503, "Ошибка сервера: 503");
                    }
                    return new ApiService.BookmarkSaveResult(true, false, 200, "Закладка сохранена");
                }
        );

        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "1234--test-anime", 202, 10, 2, "Kodik", 5, "05:42", 1, false,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        assertEquals(202, episodeId);
                        successLatch.countDown();
                    }

                    @Override
                    public void onBookmarkError(String error) {
                        fail("Should succeed after retry, got: " + error);
                    }
                }
        ));

        assertTrue(successLatch.await(4, TimeUnit.SECONDS));
        assertEquals(3, attempts.get());
    }

    @Test
    public void testNonRetryableErrorInvokesErrorCallbackImmediatelyAndFormatsToastText() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicReference<String> receivedError = new AtomicReference<>();
        CountDownLatch errorLatch = new CountDownLatch(1);

        BookmarkSyncDispatcher dispatcher = new BookmarkSyncDispatcher(
                null,
                executor,
                null,
                request -> {
                    attempts.incrementAndGet();
                    return new ApiService.BookmarkSaveResult(false, false, 401, "401 Unauthorized");
                }
        );

        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "1234--test-anime", 202, 10, 2, "Animelib", 5, "05:42", 1, true,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        fail("Should fail on 401");
                    }

                    @Override
                    public void onBookmarkError(String error) {
                        receivedError.set(error);
                        errorLatch.countDown();
                    }
                }
        ));

        assertTrue(errorLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());
        assertEquals("401 Unauthorized", receivedError.get());

        // Проверяем форматирование текста ошибки для Toast
        assertEquals(
                "Ошибка добавления закладки: необходима авторизация",
                BookmarkSyncDispatcher.formatBookmarkErrorToast("401 Unauthorized")
        );
        assertEquals(
                "Не удалось добавить закладку: ошибка сети",
                BookmarkSyncDispatcher.formatBookmarkErrorToast("Ошибка сети: timeout")
        );
        assertEquals(
                "Не удалось добавить закладку: ошибка сервера",
                BookmarkSyncDispatcher.formatBookmarkErrorToast("Ошибка сервера: 502")
        );
        assertEquals(
                "Не удалось добавить закладку",
                BookmarkSyncDispatcher.formatBookmarkErrorToast("")
        );
    }

    @Test
    public void testDuplicateAutoSaveSkippedUntilPositionOrEpisodeChanges() throws Exception {
        AtomicInteger networkCalls = new AtomicInteger(0);

        BookmarkSyncDispatcher dispatcher = new BookmarkSyncDispatcher(
                null,
                executor,
                null,
                request -> {
                    networkCalls.incrementAndGet();
                    return new ApiService.BookmarkSaveResult(true, false, 200, "OK");
                }
        );

        // 1. Первое автосохранение уходит в сеть
        CountDownLatch latch1 = new CountDownLatch(1);
        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "555--naruto", 11, 3, 2, "Animelib", 1, "12:00", 1, false,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        latch1.countDown();
                    }

                    @Override
                    public void onBookmarkError(String error) {}
                }
        ));
        assertTrue(latch1.await(2, TimeUnit.SECONDS));
        assertEquals(1, networkCalls.get());

        // 2. Точно такое же автосохранение (например, повторный onDestroy после onStop без сдвига времени) пропускается без лишнего запроса
        CountDownLatch latch2 = new CountDownLatch(1);
        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "555--naruto", 11, 3, 2, "Animelib", 1, "12:00", 1, false,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        latch2.countDown();
                    }

                    @Override
                    public void onBookmarkError(String error) {}
                }
        ));
        assertTrue(latch2.await(2, TimeUnit.SECONDS));
        assertEquals(1, networkCalls.get());

        // 3. Но при ручном нажатии кнопки закладки (showSuccessToast = true) запрос отправляется принудительно
        CountDownLatch latchManual = new CountDownLatch(1);
        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "555--naruto", 11, 3, 2, "Animelib", 1, "12:00", 1, true,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        latchManual.countDown();
                    }

                    @Override
                    public void onBookmarkError(String error) {}
                }
        ));
        assertTrue(latchManual.await(2, TimeUnit.SECONDS));
        assertEquals(2, networkCalls.get());

        // 4. И при смене таймкода или эпизода автосохранение снова уходит в сеть
        CountDownLatch latch3 = new CountDownLatch(1);
        dispatcher.enqueueBookmark(new BookmarkSyncDispatcher.BookmarkRequest(
                "555--naruto", 12, 3, 2, "Animelib", 2, "00:05", 1, false,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        latch3.countDown();
                    }

                    @Override
                    public void onBookmarkError(String error) {}
                }
        ));
        assertTrue(latch3.await(2, TimeUnit.SECONDS));
        assertEquals(3, networkCalls.get());
    }
}
