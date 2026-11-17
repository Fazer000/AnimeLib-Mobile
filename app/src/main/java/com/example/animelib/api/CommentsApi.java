package com.example.animelib.api;

import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.models.StickyCommentsResponse;
import com.example.animelib.util.CommentDocBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Доменный API-сервис для комментариев к эпизодам, закреплённых комментариев,
 * отправки/редактирования/удаления комментариев, голосования и игнор-листа.
 */
public class CommentsApi {
    private static final String TAG = "CommentsApi";

    private static final long COMMENTS_TTL_MS = 2 * 60 * 1000L;
    private static final long STICKY_COMMENTS_TTL_MS = 5 * 60 * 1000L;

    private final ApiClientCore core;

    public CommentsApi(ApiClientCore core) {
        this.core = core;
    }

    public static void invalidateCommentsCacheMapForEpisode(Map<String, ?> commentsCache,
                                                            Map<Long, ?> stickyCache,
                                                            long episodeId) {
        String prefix = episodeId + "_";
        if (commentsCache != null) {
            commentsCache.keySet().removeIf(key -> key != null && key.startsWith(prefix));
        }
        if (stickyCache != null) {
            stickyCache.remove(episodeId);
        }
    }

    public void invalidateEpisodeCommentsCache(long episodeId) {
        invalidateCommentsCacheMapForEpisode(
                ApiCacheManager.COMMENTS.getRawCacheMap(),
                ApiCacheManager.STICKY_COMMENTS.getRawCacheMap(),
                episodeId
        );
    }

    public void invalidateAllCommentsCache() {
        ApiCacheManager.COMMENTS.clear();
        ApiCacheManager.STICKY_COMMENTS.clear();
    }

    public void fetchEpisodeComments(long episodeId, String sortType, int page, boolean forceRefresh,
                                     ApiService.EpisodeCommentsCallback callback) {
        final String cacheKey = episodeId + "_" + (sortType != null ? sortType : "best") + "_" + page;

        if (forceRefresh) {
            invalidateEpisodeCommentsCache(episodeId);
        } else {
            CommentsResponse cached = ApiCacheManager.COMMENTS.getValid(cacheKey, COMMENTS_TTL_MS);
            if (cached != null) {
                if (callback != null) core.safeRunOnUiThread(() -> callback.onCommentsReceived(cached));
                return;
            }
        }

        final String requestKey = forceRefresh ? (cacheKey + "_force_" + System.nanoTime()) : cacheKey;
        boolean shouldStart = ApiCacheManager.COMMENTS.registerOrAttach(requestKey, new ApiCacheManager.CallbackNotifier<CommentsResponse>() {
            @Override
            public void onSuccess(CommentsResponse data) {
                if (callback != null) core.safeRunOnUiThread(() -> callback.onCommentsReceived(data));
            }

            @Override
            public void onError(String error) {
                if (callback != null) core.safeRunOnUiThread(() -> callback.onError(error));
            }
        });
        if (!shouldStart) return;

        core.safeExecute(() -> {
            try {
                String apiUrl = buildCommentsUrl(episodeId, sortType, page);
                Request.Builder reqBuilder = core.buildApiRequest(apiUrl);
                if (forceRefresh) {
                    reqBuilder.header("Cache-Control", "no-cache, no-store, max-age=0");
                    reqBuilder.header("Pragma", "no-cache");
                }
                Request request = reqBuilder.build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        ApiCacheManager.COMMENTS.dispatchError(requestKey, "Ошибка сети: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            if (response.isSuccessful() && response.body() != null) {
                                String body = response.body().string();
                                CommentsResponse comments = core.getGson().fromJson(body, CommentsResponse.class);
                                if (comments != null) {
                                    ApiCacheManager.COMMENTS.put(cacheKey, comments);
                                    ApiCacheManager.COMMENTS.dispatchSuccess(requestKey, comments);
                                } else {
                                    ApiCacheManager.COMMENTS.dispatchError(requestKey, "Ошибка парсинга");
                                }
                            } else {
                                CommentsResponse stale = ApiCacheManager.COMMENTS.getStale(cacheKey);
                                if (response.code() == 429 && stale != null) {
                                    ApiCacheManager.COMMENTS.dispatchSuccess(requestKey, stale);
                                } else {
                                    ApiCacheManager.COMMENTS.dispatchError(requestKey, "HTTP " + response.code());
                                }
                            }
                        } catch (Exception ex) {
                            ApiCacheManager.COMMENTS.dispatchError(requestKey, "Ошибка парсинга: " + ex.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                ApiCacheManager.COMMENTS.dispatchError(requestKey, "Ошибка запроса: " + e.getMessage());
            }
        });
    }

    public void fetchStickyComments(long episodeId, ApiService.StickyCommentsCallback callback) {
        List<CommentsResponse.CommentItem> cached = ApiCacheManager.STICKY_COMMENTS.getValid(episodeId, STICKY_COMMENTS_TTL_MS);
        if (cached != null) {
            if (callback != null) core.safeRunOnUiThread(() -> callback.onStickyCommentsReceived(cached));
            return;
        }

        boolean shouldStart = ApiCacheManager.STICKY_COMMENTS.registerOrAttach(episodeId, new ApiCacheManager.CallbackNotifier<List<CommentsResponse.CommentItem>>() {
            @Override
            public void onSuccess(List<CommentsResponse.CommentItem> data) {
                if (callback != null) core.safeRunOnUiThread(() -> callback.onStickyCommentsReceived(data));
            }

            @Override
            public void onError(String error) {
                if (callback != null) core.safeRunOnUiThread(() -> callback.onError(error));
            }
        });
        if (!shouldStart) return;

        core.safeExecute(() -> {
            try {
                String apiUrl = ApiClientCore.API_BASE_HAPI + "/comments/sticky?post_id=" + episodeId + "&post_type=episodes";
                Request request = core.buildApiRequest(apiUrl).build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        ApiCacheManager.STICKY_COMMENTS.dispatchError(episodeId, "Ошибка сети: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            if (response.isSuccessful() && response.body() != null) {
                                String body = response.body().string();
                                StickyCommentsResponse stickyResp = core.getGson().fromJson(body, StickyCommentsResponse.class);
                                List<CommentsResponse.CommentItem> list = (stickyResp != null && stickyResp.getData() != null)
                                        ? stickyResp.getData() : new ArrayList<>();
                                for (CommentsResponse.CommentItem item : list) {
                                    if (item != null) {
                                        item.setSticky(true);
                                    }
                                }
                                ApiCacheManager.STICKY_COMMENTS.put(episodeId, list);
                                ApiCacheManager.STICKY_COMMENTS.dispatchSuccess(episodeId, list);
                            } else {
                                List<CommentsResponse.CommentItem> stale = ApiCacheManager.STICKY_COMMENTS.getStale(episodeId);
                                if (response.code() == 429 && stale != null) {
                                    ApiCacheManager.STICKY_COMMENTS.dispatchSuccess(episodeId, stale);
                                } else {
                                    ApiCacheManager.STICKY_COMMENTS.dispatchError(episodeId, "HTTP " + response.code());
                                }
                            }
                        } catch (Exception ex) {
                            ApiCacheManager.STICKY_COMMENTS.dispatchError(episodeId, "Ошибка парсинга: " + ex.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                ApiCacheManager.STICKY_COMMENTS.dispatchError(episodeId, "Ошибка запроса: " + e.getMessage());
            }
        });
    }

    @NonNull
    private static String buildCommentsUrl(long episodeId, String sortType, int page) {
        String safeSort = (sortType == null || sortType.isEmpty()) ? "desc" : sortType;
        int safePage = Math.max(1, page);
        boolean byVotes = "votes_up".equalsIgnoreCase(safeSort);
        String sortBy = byVotes ? "votes_up" : "id";
        String sortDir = byVotes ? "desc" : safeSort;
        return ApiClientCore.API_BASE_HAPI + "/comments?page=" + safePage +
                "&post_id=" + episodeId +
                "&post_type=episodes&sort_by=" + sortBy + "&sort_type=" + sortDir;
    }

    public void postComment(long episodeId, String rawCommentText, Long parentCommentId, Long rootId,
                            int commentLevel, ApiService.PostCommentCallback callback) {
        submitCommentMutation(null, episodeId, rawCommentText, parentCommentId, rootId, commentLevel,
                new CommentMutationListener() {
                    @Override
                    public void onSuccess(CommentsResponse.CommentItem item) {
                        if (callback != null) callback.onSuccess(item);
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) callback.onError(error);
                    }
                });
    }

    public void editComment(long commentId, long episodeId, String rawCommentText, Long parentCommentId,
                            Long rootId, int commentLevel, ApiService.EditCommentCallback callback) {
        submitCommentMutation(commentId, episodeId, rawCommentText, parentCommentId, rootId, commentLevel,
                new CommentMutationListener() {
                    @Override
                    public void onSuccess(CommentsResponse.CommentItem item) {
                        if (callback != null) callback.onSuccess(item);
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) callback.onError(error);
                    }
                });
    }

    private interface CommentMutationListener {
        void onSuccess(CommentsResponse.CommentItem item);
        void onError(String error);
    }

    /**
     * Единая логика создания (POST) и редактирования (PUT) комментария без дублирования кода.
     */
    private void submitCommentMutation(Long editCommentId, long episodeId, String rawCommentText,
                                       Long parentCommentId, Long rootId, int commentLevel,
                                       CommentMutationListener listener) {
        core.safeExecute(() -> {
            try {
                TokenEntity tokenEntity = core.getValidUserTokenEntity();
                if (tokenEntity == null) {
                    core.safeRunOnUiThread(() -> listener.onError("Необходима авторизация. Войдите в аккаунт на сайте."));
                    return;
                }

                boolean isEdit = editCommentId != null && editCommentId > 0;
                String apiUrl = isEdit
                        ? (ApiClientCore.API_BASE_HAPI + "/comments/" + editCommentId)
                        : (ApiClientCore.API_BASE_HAPI + "/comments");

                JsonObject requestBody = buildCommentPayload(episodeId, rawCommentText, parentCommentId, rootId, commentLevel);
                RequestBody body = core.createJsonRequestBody(requestBody.toString());

                Request.Builder reqBuilder = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer " + core.getBearerToken())
                        .addHeader("Referer", "https://v5.animelib.org/");

                Request request = isEdit ? reqBuilder.put(body).build() : reqBuilder.post(body).build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, (isEdit ? "Edit" : "Post") + " comment failed", e);
                        core.safeRunOnUiThread(() -> listener.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            if (response.isSuccessful()) {
                                invalidateEpisodeCommentsCache(episodeId);
                                CommentsResponse.CommentItem commentItem = parseCommentItemFromResponse(responseBody);
                                enrichCommentItemIfNeeded(commentItem, episodeId, parentCommentId, rootId, commentLevel, tokenEntity);
                                core.safeRunOnUiThread(() -> listener.onSuccess(commentItem));
                            } else if (response.code() == 401) {
                                core.safeRunOnUiThread(() -> listener.onError("Ошибка 401: Токен недействителен или сессия истекла. Переавторизуйтесь на сайте."));
                            } else {
                                String err = core.extractServerErrorMessage(responseBody, response.code());
                                core.safeRunOnUiThread(() -> listener.onError(err));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing comment mutation response", e);
                            core.safeRunOnUiThread(() -> listener.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error executing comment mutation", e);
                core.safeRunOnUiThread(() -> listener.onError("Ошибка: " + e.getMessage()));
            }
        });
    }

    private static JsonObject buildCommentPayload(long episodeId, String rawCommentText,
                                                  Long parentCommentId, Long rootId, int commentLevel) {
        JsonObject requestBody = new JsonObject();
        requestBody.add("comment", CommentDocBuilder.buildDoc(rawCommentText));
        requestBody.add("attachments", new JsonArray());
        requestBody.addProperty("post_type", "episodes");
        requestBody.addProperty("post_id", episodeId);

        if (parentCommentId != null && parentCommentId > 0) {
            requestBody.addProperty("parent_comment", parentCommentId);
            requestBody.addProperty("root_id", (rootId != null && rootId > 0) ? rootId : parentCommentId);
            requestBody.addProperty("comment_level", commentLevel > 0 ? commentLevel : 1);
        } else {
            requestBody.add("parent_comment", JsonNull.INSTANCE);
            requestBody.add("root_id", JsonNull.INSTANCE);
            requestBody.addProperty("comment_level", 0);
        }
        return requestBody;
    }

    private CommentsResponse.CommentItem parseCommentItemFromResponse(String responseBody) {
        try {
            JsonObject obj = core.getGson().fromJson(responseBody, JsonObject.class);
            if (obj != null && obj.has("data") && !obj.get("data").isJsonNull()) {
                return core.getGson().fromJson(obj.get("data"), CommentsResponse.CommentItem.class);
            }
            return core.getGson().fromJson(responseBody, CommentsResponse.CommentItem.class);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse comment item from response", e);
            return null;
        }
    }

    public void voteComment(long commentId, int voteValue, ApiService.VoteCommentCallback callback) {
        core.safeExecute(() -> {
            try {
                if (core.getValidUserTokenEntity() == null) {
                    if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Необходима авторизация. Войдите в аккаунт на сайте."));
                    return;
                }

                String apiUrl = ApiClientCore.API_BASE_HAPI + "/comments/" + commentId + "/vote";
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("vote", voteValue);

                Request request = core.buildApiRequest(apiUrl)
                        .post(core.createJsonRequestBody(requestBody))
                        .build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, "Vote comment failed", e);
                        if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            if (response.isSuccessful()) {
                                invalidateAllCommentsCache();
                                CommentsResponse.Votes newVotes = null;
                                try {
                                    JsonObject obj = core.getGson().fromJson(responseBody, JsonObject.class);
                                    if (obj != null && obj.has("data") && obj.getAsJsonObject("data").has("votes")) {
                                        newVotes = core.getGson().fromJson(obj.getAsJsonObject("data").get("votes"), CommentsResponse.Votes.class);
                                    } else if (obj != null && obj.has("votes")) {
                                        newVotes = core.getGson().fromJson(obj.get("votes"), CommentsResponse.Votes.class);
                                    }
                                } catch (Exception ignored) {}

                                CommentsResponse.Votes finalVotes = newVotes;
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onSuccess(voteValue, finalVotes));
                            } else if (response.code() == 401) {
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка 401: Необходима авторизация. Войдите в аккаунт."));
                            } else {
                                String err = core.extractServerErrorMessage(responseBody, response.code());
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError(err));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing vote response", e);
                            if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка ответа"));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error voting comment", e);
                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка: " + e.getMessage()));
            }
        });
    }

    public void deleteComment(long commentId, ApiService.DeleteCommentCallback callback) {
        core.safeExecute(() -> {
            try {
                if (core.getValidUserTokenEntity() == null) {
                    if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Необходима авторизация. Войдите в аккаунт."));
                    return;
                }

                String apiUrl = ApiClientCore.API_BASE_HAPI + "/comments/" + commentId;
                Request request = core.buildApiRequest(apiUrl).delete().build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, "Delete comment failed", e);
                        if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            if (response.isSuccessful()) {
                                invalidateAllCommentsCache();
                                if (callback != null) core.safeRunOnUiThread(callback::onSuccess);
                            } else if (response.code() == 401) {
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка 401: Сессия истекла. Переавторизуйтесь."));
                            } else if (response.code() == 403) {
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка 403: Вы можете удалять только свои комментарии."));
                            } else {
                                String err = core.extractServerErrorMessage(responseBody, response.code());
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError(err));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error handling delete comment response", e);
                            if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error executing delete comment request", e);
                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка выполнения запроса: " + e.getMessage()));
            }
        });
    }

    public void ignoreUser(long sourceableId, long targetUserId, String commentText, ApiService.IgnoreUserCallback callback) {
        core.safeExecute(() -> {
            try {
                if (core.getValidUserTokenEntity() == null) {
                    if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Необходима авторизация. Войдите в аккаунт."));
                    return;
                }

                String apiUrl = ApiClientCore.API_BASE_HAPI + "/ignore";
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("sourceable_id", sourceableId);
                requestBody.addProperty("sourceable_type", "user");
                requestBody.addProperty("user_id", targetUserId);
                if (commentText != null && !commentText.trim().isEmpty()) {
                    requestBody.addProperty("comment", commentText.trim());
                }

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer " + core.getBearerToken())
                        .addHeader("Referer", "https://v5.animelib.org/")
                        .post(core.createJsonRequestBody(requestBody.toString()))
                        .build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, "Ignore user request failed", e);
                        if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            if (response.isSuccessful()) {
                                invalidateAllCommentsCache();
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onSuccess("Пользователь добавлен в игнор-лист"));
                            } else if (response.code() == 401) {
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка 401: Сессия истекла. Авторизуйтесь заново."));
                            } else {
                                String err = core.extractServerErrorMessage(responseBody, response.code());
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError(err));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing ignore user response", e);
                            if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error ignoring user", e);
                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка: " + e.getMessage()));
            }
        });
    }

    private void enrichCommentItemIfNeeded(CommentsResponse.CommentItem item,
                                           long episodeId,
                                           Long parentCommentId,
                                           Long rootId,
                                           int commentLevel,
                                           TokenEntity tokenEntity) {
        if (item == null) return;
        if (item.getRelation_id() <= 0) {
            item.setRelation_id(episodeId);
        }
        if (parentCommentId != null && parentCommentId > 0) {
            if (item.getParent_comment() == null || item.getParent_comment() <= 0) {
                item.setParent_comment(parentCommentId);
            }
            Long effectiveRoot = (rootId != null && rootId > 0) ? rootId : parentCommentId;
            if (item.getRoot_id() == null || item.getRoot_id() <= 0) {
                item.setRoot_id(effectiveRoot);
            }
            if (item.getComment_level() <= 0) {
                item.setComment_level(Math.max(1, commentLevel));
            }
        }
        if (item.getCreated_at_ts() <= 0 && (item.getCreated_at() == null || item.getCreated_at().isEmpty())) {
            item.setCreated_at_ts(System.currentTimeMillis() / 1000L);
        }
        if (item.getUser() == null && tokenEntity != null) {
            CommentsResponse.User fallbackUser = null;
            try {
                String authJson = tokenEntity.getAuthJson();
                if (authJson != null && !authJson.trim().isEmpty()) {
                    JsonObject authObj = core.getGson().fromJson(authJson, JsonObject.class);
                    if (authObj != null) {
                        JsonObject userObj = authObj.has("auth") && authObj.get("auth").isJsonObject()
                                ? authObj.getAsJsonObject("auth")
                                : (authObj.has("user") && authObj.get("user").isJsonObject()
                                        ? authObj.getAsJsonObject("user") : authObj);
                        fallbackUser = core.getGson().fromJson(userObj, CommentsResponse.User.class);
                    }
                }
            } catch (Exception ignored) {}

            if (fallbackUser == null) {
                fallbackUser = new CommentsResponse.User();
            }
            if (fallbackUser.getId() <= 0 && tokenEntity.getUserId() != null) {
                try {
                    fallbackUser.setId(Long.parseLong(tokenEntity.getUserId().trim()));
                } catch (Exception ignored) {}
            }
            if ((fallbackUser.getUsername() == null || fallbackUser.getUsername().trim().isEmpty())
                    && tokenEntity.getUsername() != null && !tokenEntity.getUsername().trim().isEmpty()) {
                fallbackUser.setUsername(tokenEntity.getUsername().trim());
            }
            if (fallbackUser.getUsername() != null || fallbackUser.getId() > 0) {
                item.setUser(fallbackUser);
            }
        }
    }
}
