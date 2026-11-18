package com.example.animelib.api;

import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.models.AnimeBookmarkResponse;
import com.example.animelib.models.BookmarksListResponse;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Доменный API-сервис для закладок пользователя и статусов просмотра аниме.
 */
public class BookmarksApi {
    private static final String TAG = "BookmarksApi";
    private static final long BOOKMARK_CACHE_TTL_MS = 2 * 60 * 1000L;

    private final ApiClientCore core;

    public BookmarksApi(ApiClientCore core) {
        this.core = core;
    }

    public void invalidateAnimeBookmarkCache(String mediaSlug) {
        if (mediaSlug != null && !mediaSlug.trim().isEmpty()) {
            String normalized = ApiClientCore.cleanUrlSegment(mediaSlug).toLowerCase(Locale.ROOT);
            ApiCacheManager.ANIME_BOOKMARK.remove(normalized);
            String numericId = ApiClientCore.extractAnimeId(normalized);
            if (numericId != null && !numericId.isEmpty()) {
                ApiCacheManager.ANIME_BOOKMARK.remove(numericId);
            }
        }
    }

    public ApiService.BookmarkSaveResult addBookmarkSync(String mediaSlug, int episodeId, int teamId,
                                                         int translationType, String playerName,
                                                         int episodeNumber, String currentTimecode, Object statusId) {
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            return new ApiService.BookmarkSaveResult(false, false, 0, "Не удалось определить аниме");
        }
        if (episodeId <= 0) {
            return new ApiService.BookmarkSaveResult(false, false, 0, "Некорректный эпизод для закладки");
        }
        if (!core.isAuthorized()) {
            return new ApiService.BookmarkSaveResult(false, false, 401, "Без авторизации поставить закладку нельзя");
        }

        try {
            JsonObject requestBody = createBookmarkRequestBody(
                    mediaSlug, episodeId, teamId, translationType, playerName, episodeNumber, currentTimecode, statusId
            );
            Request request = core.buildApiRequest(ApiClientCore.API_BASE_HAPI + "/bookmarks")
                    .post(core.createJsonRequestBody(requestBody))
                    .build();

            try (Response response = core.getHttpClient().newCall(request).execute()) {
                int code = response.code();
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    invalidateAnimeBookmarkCache(mediaSlug);
                    return new ApiService.BookmarkSaveResult(true, false, code, "Закладка сохранена");
                } else {
                    String serverMsg = core.extractServerErrorMessage(responseBody, code);
                    boolean retryable = (code == 408 || code == 429 || code >= 500);
                    return new ApiService.BookmarkSaveResult(false, retryable, code, serverMsg);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "addBookmarkSync network failure", e);
            return new ApiService.BookmarkSaveResult(false, true, 0, "Ошибка сети: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "addBookmarkSync unexpected failure", e);
            return new ApiService.BookmarkSaveResult(false, false, 0, "Неожиданная ошибка: " + e.getMessage());
        }
    }

    public void addBookmark(String mediaSlug, int episodeId, int teamId, int episodeNumber,
                            String currentTimecode, Object statusId, ApiService.BookmarkCallback callback) {
        core.safeExecute(() -> {
            try {
                JsonObject requestBody = createBookmarkRequestBody(
                        mediaSlug, episodeId, teamId, 2, "Animelib", episodeNumber, currentTimecode, statusId
                );
                Request request = core.buildApiRequest(ApiClientCore.API_BASE_HAPI + "/bookmarks")
                        .post(core.createJsonRequestBody(requestBody))
                        .build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, "Bookmark request failed", e);
                        if (callback != null) {
                            core.safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                        }
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            if (response.isSuccessful()) {
                                invalidateAnimeBookmarkCache(mediaSlug);
                                if (callback != null) {
                                    core.safeRunOnUiThread(() -> callback.onSuccess("Закладка добавлена успешно"));
                                }
                            } else {
                                String serverMsg = core.extractServerErrorMessage(responseBody, response.code());
                                if (callback != null) {
                                    core.safeRunOnUiThread(() -> callback.onError(serverMsg));
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing bookmark response", e);
                            if (callback != null) {
                                core.safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                            }
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error while adding bookmark", e);
                if (callback != null) {
                    core.safeRunOnUiThread(() -> callback.onError("Неожиданная ошибка: " + e.getMessage()));
                }
            }
        });
    }

    public void updateWatchStatus(String mediaSlug, Object statusId, ApiService.BookmarkCallback callback) {
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            if (callback != null) {
                core.safeRunOnUiThread(() -> callback.onError("Слаг аниме не определен"));
            }
            return;
        }

        core.safeExecute(() -> {
            try {
                JsonObject requestBody = createWatchStatusRequestBody(mediaSlug.trim(), statusId);
                Request request = core.buildApiRequest(ApiClientCore.API_BASE_HAPI + "/bookmarks")
                        .post(core.createJsonRequestBody(requestBody))
                        .build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, "updateWatchStatus request failed", e);
                        if (callback != null) {
                            core.safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                        }
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            if (response.isSuccessful()) {
                                invalidateAnimeBookmarkCache(mediaSlug);
                                if (callback != null) {
                                    core.safeRunOnUiThread(() -> callback.onSuccess("Статус обновлен"));
                                }
                            } else {
                                String serverMsg = core.extractServerErrorMessage(responseBody, response.code());
                                if (callback != null) {
                                    core.safeRunOnUiThread(() -> callback.onError(serverMsg));
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error reading updateWatchStatus response", e);
                            if (callback != null) {
                                core.safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа"));
                            }
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error in updateWatchStatus", e);
                if (callback != null) {
                    core.safeRunOnUiThread(() -> callback.onError("Неожиданная ошибка: " + e.getMessage()));
                }
            }
        });
    }

    public ApiService.WatchStatusSyncResult updateWatchStatusSync(String mediaSlug, Object statusId) {
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            return new ApiService.WatchStatusSyncResult(false, false, 0, "Слаг аниме не определен");
        }
        try {
            JsonObject requestBody = createWatchStatusRequestBody(mediaSlug.trim(), statusId);
            Request request = core.buildApiRequest(ApiClientCore.API_BASE_HAPI + "/bookmarks")
                    .post(core.createJsonRequestBody(requestBody))
                    .build();

            try (Response response = core.getHttpClient().newCall(request).execute()) {
                int code = response.code();
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    invalidateAnimeBookmarkCache(mediaSlug);
                    return new ApiService.WatchStatusSyncResult(true, false, code, "Статус обновлен");
                } else if (code == 429) {
                    return new ApiService.WatchStatusSyncResult(false, true, 429, "Слишком много запросов (429 Rate Limit)");
                } else {
                    String serverMsg = core.extractServerErrorMessage(responseBody, code);
                    return new ApiService.WatchStatusSyncResult(false, false, code, serverMsg);
                }
            }
        } catch (IOException e) {
            return new ApiService.WatchStatusSyncResult(false, false, 0, "Ошибка сети: " + e.getMessage());
        } catch (Exception e) {
            return new ApiService.WatchStatusSyncResult(false, false, 0, "Ошибка: " + e.getMessage());
        }
    }

    public ApiService.BookmarksPageSyncResult fetchBookmarksPageSync(int page, int status, String userId) {
        try {
            String apiUrl = ApiClientCore.API_BASE_HAPI + "/bookmarks?status=" + status
                    + "&user_id=" + (userId != null ? userId.trim() : "")
                    + "&sort_by=name&sort_type=desc&type_id=&genres_soft_search=0&tags_soft_search=0&page=" + page;

            Request request = core.buildApiRequest(apiUrl).build();

            try (Response response = core.getHttpClient().newCall(request).execute()) {
                int code = response.code();
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    BookmarksListResponse listResponse = core.getGson().fromJson(body, BookmarksListResponse.class);
                    return new ApiService.BookmarksPageSyncResult(true, false, code, listResponse, null);
                } else if (code == 429) {
                    return new ApiService.BookmarksPageSyncResult(false, true, 429, null, "Слишком много запросов (429 Rate Limit)");
                } else {
                    return new ApiService.BookmarksPageSyncResult(false, false, code, null, "HTTP " + code);
                }
            }
        } catch (IOException e) {
            return new ApiService.BookmarksPageSyncResult(false, false, 0, null, "Ошибка сети: " + e.getMessage());
        } catch (Exception e) {
            return new ApiService.BookmarksPageSyncResult(false, false, 0, null, "Ошибка: " + e.getMessage());
        }
    }

    public void fetchAnimeBookmark(String mediaSlug, ApiService.AnimeBookmarkCallback callback) {
        String cleaned = ApiClientCore.cleanUrlSegment(mediaSlug);
        if (cleaned.isEmpty()) {
            if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Пустой mediaSlug"));
            return;
        }

        final String cacheKey = cleaned.toLowerCase(Locale.ROOT);
        AnimeBookmarkResponse cached = ApiCacheManager.ANIME_BOOKMARK.getValid(cacheKey, BOOKMARK_CACHE_TTL_MS);
        if (cached == null && cacheKey.contains("--")) {
            String numericId = ApiClientCore.extractAnimeId(cacheKey);
            if (numericId != null && !numericId.isEmpty()) {
                cached = ApiCacheManager.ANIME_BOOKMARK.getValid(numericId, BOOKMARK_CACHE_TTL_MS);
            }
        }
        if (cached != null) {
            final AnimeBookmarkResponse finalCached = cached;
            if (callback != null) core.safeRunOnUiThread(() -> callback.onBookmarkReceived(finalCached));
            return;
        }

        if (!core.isAuthorized()) {
            if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Не авторизован"));
            return;
        }

        String apiUrl = ApiClientCore.API_BASE_HAPI + "/anime/" + cacheKey + "/bookmark";
        core.executeCachedGet(
                ApiCacheManager.ANIME_BOOKMARK,
                cacheKey,
                cacheKey,
                apiUrl,
                true,
                false,
                new ApiCacheManager.CallbackNotifier<AnimeBookmarkResponse>() {
                    @Override
                    public void onSuccess(AnimeBookmarkResponse data) {
                        if (callback != null) core.safeRunOnUiThread(() -> callback.onBookmarkReceived(data));
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) core.safeRunOnUiThread(() -> callback.onError(error));
                    }
                },
                "Ошибка сети: ",
                "Ошибка при получении закладки: ",
                "Неверный формат ответа закладки",
                body -> {
                    AnimeBookmarkResponse bookmarkResponse = core.getGson().fromJson(body, AnimeBookmarkResponse.class);
                    if (bookmarkResponse != null) {
                        ApiCacheManager.ANIME_BOOKMARK.put(cacheKey, bookmarkResponse);
                        String extractedId = ApiClientCore.extractAnimeId(cacheKey);
                        if (extractedId != null && !extractedId.isEmpty()) {
                            ApiCacheManager.ANIME_BOOKMARK.put(extractedId, bookmarkResponse);
                        }
                    }
                    return bookmarkResponse;
                }
        );
    }

    public void fetchBookmarksList(ApiService.BookmarksListCallback callback) {
        core.safeExecute(() -> {
            try {
                TokenEntity tokenEntity = core.getValidUserTokenEntity();
                if (tokenEntity == null) {
                    if (callback != null) core.safeRunOnUiThread(() -> callback.onError("No token in database"));
                    return;
                }

                String userId = tokenEntity.getUserId();
                if (userId == null || userId.trim().isEmpty()) {
                    userId = ApiClientCore.extractUserIdFromToken(tokenEntity.getAccessToken());
                }

                String apiUrl = ApiClientCore.API_BASE_HAPI + "/bookmarks?page=1&sort_by=name&sort_type=desc&status=21";
                if (userId != null && !userId.trim().isEmpty()) {
                    apiUrl += "&user_id=" + userId.trim();
                }

                Request request = core.buildApiRequest(apiUrl).build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, "Bookmarks list request failed", e);
                        if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            if (response.isSuccessful() && response.body() != null) {
                                String responseBody = response.body().string();
                                BookmarksListResponse bookmarksResponse = core.getGson().fromJson(responseBody, BookmarksListResponse.class);
                                if (bookmarksResponse != null && bookmarksResponse.getData() != null && !bookmarksResponse.getData().isEmpty()) {
                                    if (callback != null) core.safeRunOnUiThread(() -> callback.onBookmarksReceived(bookmarksResponse));
                                } else {
                                    if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Закладок нет"));
                                }
                            } else {
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка при получении закладок: " + response.code()));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing bookmarks list response", e);
                            if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error while fetching bookmarks list", e);
                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Неожиданная ошибка: " + e.getMessage()));
            }
        });
    }

    private static int resolveStatusValue(Object statusId) {
        if (statusId instanceof Number) {
            int parsed = ((Number) statusId).intValue();
            if (parsed > 0) return parsed;
        } else if (statusId instanceof String) {
            String strStatus = ((String) statusId).trim();
            if ("other".equalsIgnoreCase(strStatus)) {
                return 28;
            }
            try {
                int parsed = Integer.parseInt(strStatus);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {}
        }
        return 21;
    }

    private static JsonObject createWatchStatusRequestBody(String mediaSlug, Object statusId) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("media_type", "anime");
        requestBody.addProperty("media_slug", mediaSlug);

        JsonObject bookmark = new JsonObject();
        bookmark.addProperty("status", resolveStatusValue(statusId));
        requestBody.add("bookmark", bookmark);
        requestBody.add("meta", new JsonObject());
        return requestBody;
    }

    private static JsonObject createBookmarkRequestBody(String mediaSlug, int episodeId, int teamId,
                                                        int translationType, String playerName,
                                                        int episodeNumber, String currentTimecode, Object statusId) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("media_type", "anime");
        requestBody.addProperty("media_slug", mediaSlug);

        JsonObject bookmark = new JsonObject();
        bookmark.addProperty("item_id", episodeId);
        bookmark.addProperty("status", resolveStatusValue(statusId));
        bookmark.addProperty("progress", currentTimecode != null ? currentTimecode : "00:00");
        requestBody.add("bookmark", bookmark);

        JsonObject meta = new JsonObject();
        if (teamId > 0) {
            meta.addProperty("team", teamId);
        }
        meta.addProperty("translation_type", translationType > 0 ? translationType : 2);
        String resolvedPlayer = (playerName != null && !playerName.trim().isEmpty()) ? playerName.trim() : "Animelib";
        if ("animelib".equalsIgnoreCase(resolvedPlayer)) {
            resolvedPlayer = "Animelib";
        } else if ("kodik".equalsIgnoreCase(resolvedPlayer)) {
            resolvedPlayer = "Kodik";
        }
        meta.addProperty("player", resolvedPlayer);
        meta.addProperty("item_number", episodeNumber > 0 ? episodeNumber : 1);
        requestBody.add("meta", meta);

        return requestBody;
    }
}
