package com.example.animelib.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.api.ApiService;
import com.example.animelib.models.SearchResponse;
import com.example.animelib.util.AnixartParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Менеджер очередей для импорта списка просмотренного из Anixart в AnimeLib (ID статуса 24).
 * Имеет встроенную защиту от 429 Too Many Requests:
 * - Базовая пауза между запросами (по умолчанию 900 мс)
 * - Экспоненциальный откат (backoff) при 429: пауза 5с, 10с, 20с с авто-повтором
 * - Возможность отмены / паузы
 * - Оповещение UI о прогрессе (успешно, пропущено, не найдено, ошибка)
 */
public class AnixartImportManager {

    private static final String TAG = "AnixartImportManager";
    public static final int WATCHED_STATUS_ID = 24;

    private static final long BASE_DELAY_MS = 900L;
    private static final int MAX_429_RETRIES = 5;

    public enum ItemResultStatus {
        PENDING,
        PROCESSING,
        SUCCESS,
        NOT_FOUND,
        ERROR,
        SKIPPED
    }

    public static class ImportItemProgress {
        public final AnixartParser.AnixartEntry entry;
        public ItemResultStatus status = ItemResultStatus.PENDING;
        public String foundTitle = null;
        public String foundSlug = null;
        public String errorMessage = null;

        public ImportItemProgress(AnixartParser.AnixartEntry entry) {
            this.entry = entry;
        }
    }

    public interface ImportListener {
        void onProgress(int current, int total, ImportItemProgress item);
        void onLog(String message);
        void onFinished(int successCount, int notFoundCount, int errorCount, boolean cancelled);
    }

    private final ApiService apiService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    public AnixartImportManager(Context context) {
        this.apiService = new ApiService(context.getApplicationContext());
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public void cancel() {
        if (isRunning.get()) {
            isCancelled.set(true);
            Log.d(TAG, "Import cancelled by user");
        }
    }

    public void startImport(List<AnixartParser.AnixartEntry> entries, @NonNull ImportListener listener) {
        if (entries == null || entries.isEmpty()) {
            listener.onFinished(0, 0, 0, false);
            return;
        }

        if (isRunning.getAndSet(true)) {
            listener.onLog("Импорт уже выполняется!");
            return;
        }

        isCancelled.set(false);

        executor.execute(() -> {
            int total = entries.size();
            int successCount = 0;
            int notFoundCount = 0;
            int errorCount = 0;

            postLog(listener, "Запуск импорта " + total + " тайтлов...");

            try {
                for (int i = 0; i < total; i++) {
                    if (isCancelled.get()) {
                        postLog(listener, "Импорт прерван пользователем.");
                        break;
                    }

                    AnixartParser.AnixartEntry entry = entries.get(i);
                    ImportItemProgress itemProgress = new ImportItemProgress(entry);
                    itemProgress.status = ItemResultStatus.PROCESSING;
                    postProgress(listener, i + 1, total, itemProgress);

                    String query = entry.getEffectiveTitle();
                    postLog(listener, "[" + (i + 1) + "/" + total + "] Поиск: \"" + query + "\"");

                    // 1. Поиск аниме с защитой от 429
                    ApiService.SearchSyncResult searchResult = performSearchWithBackoff(query, listener);
                    if (isCancelled.get()) break;

                    if (!searchResult.success || searchResult.response == null) {
                        itemProgress.status = ItemResultStatus.ERROR;
                        itemProgress.errorMessage = searchResult.message != null ? searchResult.message : "Ошибка поиска";
                        errorCount++;
                        postProgress(listener, i + 1, total, itemProgress);
                        postLog(listener, "❌ Ошибка поиска: " + itemProgress.errorMessage);
                        sleepSafe(BASE_DELAY_MS);
                        continue;
                    }

                    List<SearchResponse.AnimeSearchItem> searchItems = searchResult.response.getData();
                    if (searchItems == null || searchItems.isEmpty()) {
                        // Попробуем альтернативно найти по первому названию (русскому), если английское не дало результатов
                        if (!entry.getPrimaryTitle().isEmpty() && !entry.getPrimaryTitle().equalsIgnoreCase(query)) {
                            postLog(listener, "Поиск по альт. названию: \"" + entry.getPrimaryTitle() + "\"");
                            sleepSafe(600);
                            searchResult = performSearchWithBackoff(entry.getPrimaryTitle(), listener);
                            if (searchResult.success && searchResult.response != null) {
                                searchItems = searchResult.response.getData();
                            }
                        }
                    }

                    if (searchItems == null || searchItems.isEmpty()) {
                        itemProgress.status = ItemResultStatus.NOT_FOUND;
                        itemProgress.errorMessage = "Не найдено в базе AnimeLib";
                        notFoundCount++;
                        postProgress(listener, i + 1, total, itemProgress);
                        postLog(listener, "⚠️ Не найдено в базе AnimeLib");
                        sleepSafe(BASE_DELAY_MS);
                        continue;
                    }

                    // Берем первый результат
                    SearchResponse.AnimeSearchItem firstItem = searchItems.get(0);
                    String slug = firstItem.getSlug();
                    String titleFound = firstItem.getRusName() != null && !firstItem.getRusName().isEmpty()
                            ? firstItem.getRusName()
                            : firstItem.getName();

                    itemProgress.foundSlug = slug;
                    itemProgress.foundTitle = titleFound;

                    postLog(listener, "Найдено: \"" + titleFound + "\" (" + slug + "). Добавление статуса Просмотрено (24)...");

                    // Пауза перед установкой статуса
                    sleepSafe(BASE_DELAY_MS);
                    if (isCancelled.get()) break;

                    // 2. Отправка статуса 24 в закладки с защитой от 429
                    ApiService.WatchStatusSyncResult statusResult = performStatusUpdateWithBackoff(slug, WATCHED_STATUS_ID, listener);
                    if (isCancelled.get()) break;

                    if (statusResult.success) {
                        itemProgress.status = ItemResultStatus.SUCCESS;
                        successCount++;
                        postProgress(listener, i + 1, total, itemProgress);
                        postLog(listener, "✅ Успешно добавлен в «Просмотрено»");
                    } else {
                        itemProgress.status = ItemResultStatus.ERROR;
                        itemProgress.errorMessage = statusResult.message != null ? statusResult.message : "Ошибка добавления в закладки";
                        errorCount++;
                        postProgress(listener, i + 1, total, itemProgress);
                        postLog(listener, "❌ Ошибка сохранения: " + itemProgress.errorMessage);
                    }

                    // Базовая вежливая пауза перед следующим тайтлом
                    sleepSafe(BASE_DELAY_MS);
                }
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error in import loop", e);
                postLog(listener, "Критическая ошибка импорта: " + e.getMessage());
            } finally {
                boolean cancelled = isCancelled.get();
                isRunning.set(false);
                int finalSuccess = successCount;
                int finalNotFound = notFoundCount;
                int finalError = errorCount;
                postFinished(listener, finalSuccess, finalNotFound, finalError, cancelled);
            }
        });
    }

    private ApiService.SearchSyncResult performSearchWithBackoff(String query, ImportListener listener) {
        long currentBackoff = 4000L;
        for (int attempt = 0; attempt < MAX_429_RETRIES; attempt++) {
            if (isCancelled.get()) {
                return new ApiService.SearchSyncResult(false, false, 0, null, "Отменено");
            }
            ApiService.SearchSyncResult res = apiService.searchAnimeSync(query);
            if (res.rateLimited) {
                postLog(listener, "⏳ Лимит запросов (429). Пауза " + (currentBackoff / 1000) + " сек...");
                sleepSafe(currentBackoff);
                currentBackoff = Math.min(currentBackoff * 2, 25000L);
                continue;
            }
            return res;
        }
        return new ApiService.SearchSyncResult(false, true, 429, null, "Превышен лимит запросов (429)");
    }

    private ApiService.WatchStatusSyncResult performStatusUpdateWithBackoff(String slug, int statusId, ImportListener listener) {
        long currentBackoff = 4000L;
        for (int attempt = 0; attempt < MAX_429_RETRIES; attempt++) {
            if (isCancelled.get()) {
                return new ApiService.WatchStatusSyncResult(false, false, 0, "Отменено");
            }
            ApiService.WatchStatusSyncResult res = apiService.updateWatchStatusSync(slug, statusId);
            if (res.rateLimited) {
                postLog(listener, "⏳ Лимит запросов (429) при сохранении. Пауза " + (currentBackoff / 1000) + " сек...");
                sleepSafe(currentBackoff);
                currentBackoff = Math.min(currentBackoff * 2, 25000L);
                continue;
            }
            return res;
        }
        return new ApiService.WatchStatusSyncResult(false, true, 429, "Превышен лимит запросов (429)");
    }

    private void sleepSafe(long ms) {
        try {
            long waited = 0;
            long step = 200;
            while (waited < ms && !isCancelled.get()) {
                Thread.sleep(Math.min(step, ms - waited));
                waited += step;
            }
        } catch (InterruptedException ignored) {}
    }

    private void postProgress(ImportListener listener, int current, int total, ImportItemProgress item) {
        mainHandler.post(() -> listener.onProgress(current, total, item));
    }

    private void postLog(ImportListener listener, String message) {
        mainHandler.post(() -> listener.onLog(message));
    }

    private void postFinished(ImportListener listener, int success, int notFound, int error, boolean cancelled) {
        mainHandler.post(() -> listener.onFinished(success, notFound, error, cancelled));
    }
}
