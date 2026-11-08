package com.example.animelib.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.api.ApiService;
import com.example.animelib.models.BookmarksListResponse;
import com.example.animelib.models.SearchResponse;
import com.example.animelib.util.AnixartParser;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Менеджер очередей для импорта списка просмотренного из Anixart в AnimeLib (ID статуса 24).
 * Имеет встроенную защиту от 429 Too Many Requests:
 * - Загрузка всех существующих закладок со статусом 24 для исключения уже добавленных по полю name
 * - Базовая пауза между запросами (900 мс)
 * - Экспоненциальный откат (backoff) при 429: пауза 5с, 10с, 20с с авто-повтором
 * - Возможность отмены / паузы
 * - Оповещение UI о прогрессе (добавлено, пропущено/уже в списке, не найдено, ошибка)
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
        ALREADY_EXISTS,
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
        void onFinished(int successCount, int alreadyExistsCount, int notFoundCount, int errorCount, boolean cancelled);
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

    /**
     * Нормализует название тайтла для надежного сравнения (удаляет знаки пунктуации, лишние пробелы, приводит к нижнему регистру)
     */
    public static String normalizeTitle(String title) {
        if (title == null) return "";
        return title.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\"«»'’“”.,:;!?_\\-—/\\\\]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public void startImport(List<AnixartParser.AnixartEntry> entries, @NonNull ImportListener listener) {
        if (entries == null || entries.isEmpty()) {
            listener.onFinished(0, 0, 0, 0, false);
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
            int alreadyExistsCount = 0;
            int notFoundCount = 0;
            int errorCount = 0;

            postLog(listener, "Запуск импорта " + total + " тайтлов...");

            try {
                // 1. Предварительная загрузка всех уже существующих закладок пользователя со статусом 24
                Set<String> existingTitleNames = fetchAllExistingWatchedTitles(listener);
                if (isCancelled.get()) {
                    postLog(listener, "Импорт прерван пользователем.");
                    return;
                }

                postLog(listener, "Загружено уже просмотренных тайтлов: " + existingTitleNames.size() + ". Начинаем проверку...");

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
                    postLog(listener, "[" + (i + 1) + "/" + total + "] Проверка: \"" + query + "\"");

                    // Быстрая проверка до сетевого запроса: вдруг в списке уже есть точно такое же название
                    String normalizedEffective = normalizeTitle(entry.getEffectiveTitle());
                    String normalizedPrimary = normalizeTitle(entry.getPrimaryTitle());
                    String normalizedSearch = normalizeTitle(entry.getSearchTitle());

                    if ((!normalizedEffective.isEmpty() && existingTitleNames.contains(normalizedEffective))
                            || (!normalizedPrimary.isEmpty() && existingTitleNames.contains(normalizedPrimary))
                            || (!normalizedSearch.isEmpty() && existingTitleNames.contains(normalizedSearch))) {
                        itemProgress.status = ItemResultStatus.ALREADY_EXISTS;
                        itemProgress.foundTitle = entry.getEffectiveTitle();
                        alreadyExistsCount++;
                        postProgress(listener, i + 1, total, itemProgress);
                        postLog(listener, "ℹ️ Уже в списке «Просмотрено», пропускаем");
                        continue;
                    }

                    // 2. Поиск аниме в AnimeLib API с защитой от 429
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
                    String titleName = firstItem.getName();
                    String titleRus = firstItem.getRusName();
                    String titleEng = firstItem.getEngName();

                    String displayTitle = (titleRus != null && !titleRus.isEmpty()) ? titleRus : titleName;
                    itemProgress.foundSlug = slug;
                    itemProgress.foundTitle = displayTitle;

                    // 3. Проверка по полю name и другим полям найденного тайтла в уже имеющихся закладках
                    if (isAlreadyInBookmarks(firstItem, existingTitleNames)) {
                        itemProgress.status = ItemResultStatus.ALREADY_EXISTS;
                        alreadyExistsCount++;
                        postProgress(listener, i + 1, total, itemProgress);
                        postLog(listener, "ℹ️ Тайтл \"" + displayTitle + "\" уже есть в списке просмотренного (name=" + titleName + "), пропускаем");
                        sleepSafe(400);
                        continue;
                    }

                    postLog(listener, "Найдено: \"" + displayTitle + "\" (" + slug + "). Добавление статуса Просмотрено (24)...");

                    // Пауза перед установкой статуса
                    sleepSafe(BASE_DELAY_MS);
                    if (isCancelled.get()) break;

                    // 4. Отправка статуса 24 в закладки с защитой от 429
                    ApiService.WatchStatusSyncResult statusResult = performStatusUpdateWithBackoff(slug, WATCHED_STATUS_ID, listener);
                    if (isCancelled.get()) break;

                    if (statusResult.success) {
                        itemProgress.status = ItemResultStatus.SUCCESS;
                        successCount++;
                        // Добавляем тайтл в локальный сет, чтобы не дублировать, если в списке встретится повтор
                        addTitleToExistingSet(existingTitleNames, titleName, titleRus, titleEng, slug);
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
                int finalAlreadyExists = alreadyExistsCount;
                int finalNotFound = notFoundCount;
                int finalError = errorCount;
                postFinished(listener, finalSuccess, finalAlreadyExists, finalNotFound, finalError, cancelled);
            }
        });
    }

    /**
     * Проверяет, содержится ли найденный тайтл среди существующих закладок пользователя по полю name и другим идентификаторам
     */
    private boolean isAlreadyInBookmarks(SearchResponse.AnimeSearchItem item, Set<String> existingTitleNames) {
        if (item == null || existingTitleNames == null || existingTitleNames.isEmpty()) return false;

        if (item.getName() != null) {
            String norm = normalizeTitle(item.getName());
            if (!norm.isEmpty() && existingTitleNames.contains(norm)) return true;
        }
        if (item.getRusName() != null) {
            String norm = normalizeTitle(item.getRusName());
            if (!norm.isEmpty() && existingTitleNames.contains(norm)) return true;
        }
        if (item.getEngName() != null) {
            String norm = normalizeTitle(item.getEngName());
            if (!norm.isEmpty() && existingTitleNames.contains(norm)) return true;
        }
        if (item.getSlug() != null) {
            String norm = normalizeTitle(item.getSlug());
            if (!norm.isEmpty() && existingTitleNames.contains(norm)) return true;
        }
        return false;
    }

    private void addTitleToExistingSet(Set<String> set, String name, String rus, String eng, String slug) {
        if (set == null) return;
        if (name != null) set.add(normalizeTitle(name));
        if (rus != null) set.add(normalizeTitle(rus));
        if (eng != null) set.add(normalizeTitle(eng));
        if (slug != null) set.add(normalizeTitle(slug));
    }

    /**
     * Постранично загружает весь список закладок со статусом 24 (Просмотрено)
     * Запрос: https://hapi.hentaicdn.org/api/bookmarks?status=24&user_id=<ID>&sort_by=name&sort_type=desc&type_id=&genres_soft_search=0&tags_soft_search=0&page=X
     */
    private Set<String> fetchAllExistingWatchedTitles(ImportListener listener) {
        Set<String> titles = Collections.synchronizedSet(new HashSet<>());
        String userId = apiService.getCurrentUserId();
        if (userId == null || userId.trim().isEmpty()) {
            postLog(listener, "Пользователь не авторизован или userId не найден. Проверка существующих закладок пропущена.");
            return titles;
        }

        postLog(listener, "Получение существующих закладок со статусом «Просмотрено» (24)...");
        int currentPage = 1;

        while (!isCancelled.get()) {
            ApiService.BookmarksPageSyncResult pageResult = fetchBookmarksPageWithBackoff(currentPage, WATCHED_STATUS_ID, userId, listener);
            if (isCancelled.get()) break;

            if (!pageResult.success || pageResult.response == null) {
                postLog(listener, "⚠️ Не удалось загрузить страницу " + currentPage + " закладок: " + pageResult.message);
                break;
            }

            BookmarksListResponse response = pageResult.response;
            List<BookmarksListResponse.BookmarkItem> data = response.getData();
            if (data == null || data.isEmpty()) {
                break;
            }

            for (BookmarksListResponse.BookmarkItem item : data) {
                if (item != null && item.getMedia() != null) {
                    BookmarksListResponse.MediaData media = item.getMedia();
                    addTitleToExistingSet(titles, media.getName(), media.getRusName(), media.getEngName(), media.getSlug());
                }
            }

            postLog(listener, "Загружена страница " + currentPage + " закладок (всего в базе " + titles.size() + ")");

            // Проверяем, есть ли следующая страница
            boolean hasNext = false;
            if (response.getLinks() != null && response.getLinks().getNext() != null && !response.getLinks().getNext().trim().isEmpty()) {
                hasNext = true;
            } else if (response.getMeta() != null && response.getMeta().isNextPageUrl()) {
                hasNext = true;
            }

            if (!hasNext) {
                break;
            }

            currentPage++;
            sleepSafe(500); // Небольшая пауза между страницами
        }

        return titles;
    }

    private ApiService.BookmarksPageSyncResult fetchBookmarksPageWithBackoff(int page, int status, String userId, ImportListener listener) {
        long currentBackoff = 4000L;
        for (int attempt = 0; attempt < MAX_429_RETRIES; attempt++) {
            if (isCancelled.get()) {
                return new ApiService.BookmarksPageSyncResult(false, false, 0, null, "Отменено");
            }
            ApiService.BookmarksPageSyncResult res = apiService.fetchBookmarksPageSync(page, status, userId);
            if (res.rateLimited) {
                postLog(listener, "⏳ Лимит запросов (429) при загрузке закладок. Пауза " + (currentBackoff / 1000) + " сек...");
                sleepSafe(currentBackoff);
                currentBackoff = Math.min(currentBackoff * 2, 25000L);
                continue;
            }
            return res;
        }
        return new ApiService.BookmarksPageSyncResult(false, true, 429, null, "Превышен лимит запросов (429)");
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

    private void postFinished(ImportListener listener, int success, int alreadyExists, int notFound, int error, boolean cancelled) {
        mainHandler.post(() -> listener.onFinished(success, alreadyExists, notFound, error, cancelled));
    }
}
