package com.example.animelib.api;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.models.*;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;

public class ApiService {
    // Fallback токен если нет токена в БД
    private static final String FALLBACK_BEARER_TOKEN = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJhdWQiOiIxIiwianRpIjoiOTZkYjliMDI4NGM0OWQ1Yzc2NTIxMzkxZTRlNDJkNjAwNTFmMDUzMDU2NjBjZGQzYTRjYmEzN2FjMmRmYTZhNjEyM2VmNDgxZDBjMGU0Y2MiLCJpYXQiOjE3NTc0MzEyNDcuOTg2MjE5LCJuYmYiOjE3NTc0MzEyNDcuOTg2MjIxLCJleHAiOjE3NjAwMjMyNDcuOTgyNTM3LCJzdWIiOiI5NDM5MzIxIiwic2NvcGVzIjpbXX0.FG2bBdeF0328Prrsr9Q_SL-VkQyeJMqE9b9uQ1E74JsCnJPveeMMLYNuJt_cTp5XpkvFK3XHltfCM7wi4Gg-x3rlpG-sTELMaoMNWv-4TmNcQbrKwSnTSVJfUFlnguVA7kpGHBgfAaL3NVKSwu_Pu1xqq6UwqpV9hBSJ6iTHG7T3vz7e_HxhGWQ7AZ47xmoo76aOnWQ2vIceF-zq6gF0peKBsHXuG8Prl-88xyltkT2SSnAJrTl4xmPQsM0F0OntkkFZGU6XPdFwXw-orxvtpCfsv556ra5fdbACMjqfZ3euwqXEHGRtkjMJpmku1-sV_xubQvCgbwuO8WRc-ukuWv3x2WTffkXypFKviEdNTXLBFki5ex4sblvaYhDUd4IrZwIjL-GRPQ9_X6WZITz7Lic5faKs1kr3mxXDSuK7u7tC2WSCom_I_CYR9_aIytJ_XkxixG-aa3LP9-jaOn0n7iZS8XNjaIlLHyqr2Of9wPvJ-A1NVv41EeaptXWs7VcSWg42-fUkofNyS2Qn1Qdo9DzVKmqzO9jMpe-8suwBVGl3gpr4nCwn4J8tIKOTzWX--xHkotH5w1TYaQAtzKs6ocyptylNdAD8WRm_FU3E3pdY5Ecarem7SK8ij5rh724GMiBXN9y9s6jBSwPoIAD9W-R4UoXo1mhsRNGiJ4EkC0U";

    public interface EpisodesCallback {
        void onEpisodesReceived(EpisodesListResponse response);
        void onError(String error);
    }

    public interface EpisodeDataCallback {
        void onEpisodeDataReceived(EpisodeResponse response);
        void onError(String error);
    }

    public interface KodikVideoCallback {
        void onKodikVideoReceived(KodikResponse response);
        void onError(String error);
    }
    
    public interface AnimeInfoCallback {
        void onAnimeInfoReceived(AnimeInfoResponse response);
        void onError(String error);
    }
    
    public interface RelatedTitlesCallback {
        void onRelatedTitlesReceived(RelatedTitlesResponse response);
        void onError(String error);
    }
    
    public interface EpisodeCommentsCallback {
        void onCommentsReceived(CommentsResponse response);
        void onError(String error);
    }

    public interface StickyCommentsCallback {
        void onStickyCommentsReceived(List<CommentsResponse.CommentItem> stickyComments);
        void onError(String error);
    }

    public interface PostCommentCallback {
        void onSuccess(CommentsResponse.CommentItem commentItem);
        void onError(String error);
    }

    public interface VoteCommentCallback {
        void onSuccess(int newVoteValue, CommentsResponse.Votes votes);
        void onError(String error);
    }

    public interface DeleteCommentCallback {
        void onSuccess();
        void onError(String error);
    }

    public interface EditCommentCallback {
        void onSuccess(CommentsResponse.CommentItem commentItem);
        void onError(String error);
    }

    public interface SearchCallback {
        void onSearchResults(SearchResponse response);
        void onError(String error);
    }


    public interface ToastCheckCallback {
        void onToastReceived(String message, String newUrl);
        void onError(String error);
    }
    
    public interface BookmarkCallback {
        void onSuccess(String message);
        void onError(String error);
    }
    
    public interface AnimeBookmarkCallback {
        void onBookmarkReceived(AnimeBookmarkResponse response);
        void onError(String error);
    }
    
    public interface BookmarksListCallback {
        void onBookmarksReceived(BookmarksListResponse response);
        void onError(String error);
    }

    public interface ArticleCallback {
        void onArticleReceived(ArticleResponse response);
        void onError(String error);
    }

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ExecutorService executor;
    private final Context context;
    private final KodikLinksExtractor kodikLinksExtractor;

    // Database manager for all DB operations
    private final com.example.animelib.data.DatabaseManager databaseManager;

    public ApiService(Context context) {
        this.context = context; // Сохраняем оригинальный context для runOnUiThread
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
        this.kodikLinksExtractor = new KodikLinksExtractor(this.httpClient, this.gson);
        this.executor = Executors.newSingleThreadExecutor();
        this.databaseManager = new com.example.animelib.data.DatabaseManager(context.getApplicationContext());
    }

    private Request.Builder buildApiRequest(String url) {
        String siteUrl = getSiteUrlFromDb();
        if (siteUrl == null || siteUrl.isEmpty()) {
            siteUrl = "https://" + context.getString(com.example.animelib.R.string.site_url);
        }
        if (siteUrl.endsWith("/")) {
            siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
        }
        String referer = siteUrl + "/";
        
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + getBearerToken())
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", siteUrl)
                .addHeader("Referer", referer)
                .addHeader("Sec-Ch-Ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Android WebView\";v=\"150\"")
                .addHeader("Sec-Ch-Ua-Mobile", "?1")
                .addHeader("Sec-Ch-Ua-Platform", "\"Android\"")
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "cross-site")
                .addHeader("Site-Id", "5")
                .addHeader("X-Requested-With", "com.unixshells.devbrowser")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                .addHeader("Client-Time-Zone", "Europe/Moscow")
                .addHeader("Priority", "u=1, i");
    }
    
    /**
     * Строит API запрос для поиска (без Site-Id заголовка)
     */
    private Request.Builder buildSearchApiRequest(String url) {
        String siteUrl = getSiteUrlFromDb();
        if (siteUrl == null || siteUrl.isEmpty()) {
            siteUrl = "https://" + context.getString(com.example.animelib.R.string.site_url);
        }
        if (siteUrl.endsWith("/")) {
            siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
        }
        String referer = siteUrl + "/";
        
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + getBearerToken())
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", siteUrl)
                .addHeader("Referer", referer)
                .addHeader("Sec-Ch-Ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Android WebView\";v=\"150\"")
                .addHeader("Sec-Ch-Ua-Mobile", "?1")
                .addHeader("Sec-Ch-Ua-Platform", "\"Android\"")
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "cross-site")
                .addHeader("X-Requested-With", "com.unixshells.devbrowser")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                .addHeader("Client-Time-Zone", "Europe/Moscow")
                .addHeader("Priority", "u=1, i");
    }

    private String getSiteUrlFromDb() {
        return databaseManager.getSiteUrl();
    }

    /**
     * Возвращает основные заголовки для видеозапросов (Referer, Authorization и базовые HTTP)
     */
    public Map<String, String> getVideoRequestHeaders() {
        Map<String, String> headers = new HashMap<>();

        String token = getBearerToken();
        if (token != null && !token.trim().isEmpty()) {
            if (token.toLowerCase().startsWith("bearer ")) {
                headers.put("Authorization", token);
            } else {
                headers.put("Authorization", "Bearer " + token);
            }
        }

        headers.put("Referer", "https://animelib.org/");
        headers.put("Origin", "https://animelib.org");
        headers.put("Content-Type", "video");
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36");
        headers.put("accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5");
        headers.put("accept-language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("sec-ch-ua", "\"Not=A?Brand\";v=\"99\", \"Android WebView\";v=\"151\", \"Chromium\";v=\"151\"");
        headers.put("sec-ch-ua-mobile", "?1");
        headers.put("sec-ch-ua-platform", "\"Android\"");
        headers.put("sec-fetch-dest", "video");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "cross-site");
        return headers;
    }
    
    /**
     * Получает токен авторизации из базы данных или возвращает fallback токен
     */
    private String getBearerToken() {
        try {
            TokenEntity token = databaseManager.getToken();
            if (token != null && token.getAccessToken() != null && !token.getAccessToken().trim().isEmpty()) {
                String tokenStr = token.getAccessToken().trim();
                if (tokenStr.toLowerCase().startsWith("bearer ")) {
                    tokenStr = tokenStr.substring(7).trim();
                }
                Log.d("ApiService", "Using token from database");
                return tokenStr;
            }
        } catch (Exception e) {
            Log.e("ApiService", "Failed to get token from database", e);
        }
        
        Log.d("ApiService", "Using fallback token");
        String fallbackStr = FALLBACK_BEARER_TOKEN.trim();
        if (fallbackStr.toLowerCase().startsWith("bearer ")) {
            fallbackStr = fallbackStr.substring(7).trim();
        }
        return fallbackStr;
    }

    /**
     * Проверяет, авторизован ли пользователь (есть ли токен авторизации в БД)
     */
    public boolean isAuthorized() {
        if (databaseManager == null) return false;
        try {
            TokenEntity token = databaseManager.getToken();
            return token != null && token.getAccessToken() != null && !token.getAccessToken().trim().isEmpty();
        } catch (Exception e) {
            Log.e("ApiService", "Failed to check authorization status", e);
            return false;
        }
    }

    /**
     * Извлекает userId из JWT токена (claims: sub, user_id, id, nameid, uid)
     */
    public static String extractUserIdFromToken(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                byte[] decoded = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                String jsonStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                org.json.JSONObject json = new org.json.JSONObject(jsonStr);
                if (json.has("sub")) {
                    return json.optString("sub");
                } else if (json.has("user_id")) {
                    return json.optString("user_id");
                } else if (json.has("id")) {
                    return json.optString("id");
                } else if (json.has("nameid")) {
                    return json.optString("nameid");
                } else if (json.has("uid")) {
                    return json.optString("uid");
                } else if (json.has("user")) {
                    org.json.JSONObject uJson = json.optJSONObject("user");
                    if (uJson != null && uJson.has("id")) {
                        return uJson.optString("id");
                    }
                }
            }
        } catch (Exception e) {
            Log.e("ApiService", "Error extracting userId from token", e);
        }
        return null;
    }

    /**
     * Получает ID текущего авторизованного пользователя
     */
    public String getCurrentUserId() {
        try {
            TokenEntity token = databaseManager.getToken();
            if (token != null) {
                if (token.getUserId() != null && !token.getUserId().trim().isEmpty()) {
                    return token.getUserId().trim();
                }
                if (token.getAccessToken() != null && !token.getAccessToken().trim().isEmpty()) {
                    String extracted = extractUserIdFromToken(token.getAccessToken());
                    if (extracted != null && !extracted.trim().isEmpty()) {
                        return extracted.trim();
                    }
                }
            }
        } catch (Exception e) {
            Log.e("ApiService", "Error getting current userId", e);
        }
        return null;
    }

    public void fetchAnimeInfo(String animeSlugOrId, AnimeInfoCallback callback) {
        safeExecute(() -> {
            try {
                String apiUrl = "https://api.cdnlibs.org/api/anime/" + animeSlugOrId + "?fields[]=background&fields[]=eng_name&fields[]=otherNames&fields[]=summary&fields[]=releaseDate&fields[]=type_id&fields[]=caution&fields[]=views&fields[]=close_view&fields[]=rate_avg&fields[]=rate&fields[]=genres&fields[]=tags&fields[]=teams&fields[]=authors&fields[]=publisher&fields[]=userRating&fields[]=anime_status_id&fields[]=episodes&fields[]=episodes_count&fields[]=shiki_rate";
                Request request = buildApiRequest(apiUrl).build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        callback.onError("Ошибка сети: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        if (!response.isSuccessful()) {
                            callback.onError("HTTP " + response.code());
                            return;
                        }
                        assert response.body() != null;
                        String body = response.body().string();
                        try {
                            AnimeInfoResponse info = gson.fromJson(body, AnimeInfoResponse.class);
                            callback.onAnimeInfoReceived(info);
                        } catch (Exception ex) {
                            Log.e("ApiService", "Failed to parse anime info JSON: " + ex.getMessage(), ex);
                            callback.onError("Ошибка парсинга: " + ex.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                callback.onError("Ошибка запроса: " + e.getMessage());
            }
        });
    }
    
    /**
     * Поиск аниме по запросу
     * @param query Поисковый запрос
     * @param callback Callback для обработки результатов
     */
    public void searchAnime(String query, SearchCallback callback) {
        safeExecute(() -> {
            try {
                // URL-encode query
                String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
                String apiUrl = "https://api.cdnlibs.org/api/anime?fields[]=rate_avg&fields[]=rate&fields[]=releaseDate&q=" + encodedQuery;
                Log.d("ApiService", "Searching anime with query: " + query);
                
                // Используем buildSearchApiRequest без Site-Id заголовка
                Request request = buildSearchApiRequest(apiUrl).build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Search request failed", e);
                        callback.onError("Ошибка сети: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        if (!response.isSuccessful()) {
                            Log.e("ApiService", "Search failed with code: " + response.code());
                            callback.onError("HTTP " + response.code());
                            return;
                        }
                        assert response.body() != null;
                        String body = response.body().string();
                        Log.d("ApiService", "Search response received, parsing...");
                        try {
                            SearchResponse searchResponse = gson.fromJson(body, SearchResponse.class);
                            Log.d("ApiService", "Search results: " + (searchResponse.getData() != null ? searchResponse.getData().size() : 0) + " items");
                            callback.onSearchResults(searchResponse);
                        } catch (Exception ex) {
                            Log.e("ApiService", "Search parsing error", ex);
                            callback.onError("Ошибка парсинга: " + ex.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("ApiService", "Search request error", e);
                callback.onError("Ошибка запроса: " + e.getMessage());
            }
        });
    }

    public void fetchEpisodesList(String animeId, EpisodesCallback callback) {
        Log.d("AnimeApiService", "fetchEpisodesList called with animeId: " + animeId);
        safeExecute(() -> {
            try {
                String apiUrl = "https://api.cdnlibs.org/api/episodes?anime_id=" + animeId;
                Log.d("AnimeApiService", "Fetching episodes list for anime_id: " + animeId);

                Request request = buildApiRequest(apiUrl).build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("AnimeApiService", "Episodes list request failed", e);
                        callback.onError("Ошибка загрузки эпизодов: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            if (response.isSuccessful()) {
                                assert response.body() != null;
                                String responseBody = response.body().string();
                                Log.d("AnimeApiService", "Episodes list response received");

                                EpisodesListResponse episodesResponse = gson.fromJson(responseBody, EpisodesListResponse.class);
                                if (episodesResponse != null && episodesResponse.getData() != null) {
                                    callback.onEpisodesReceived(episodesResponse);
                                } else {
                                    callback.onError("Неверный формат ответа эпизодов");
                                }
                            } else {
                                callback.onError("HTTP ошибка: " + response.code());
                            }
                        } catch (Exception e) {
                            Log.e("AnimeApiService", "Error processing episodes response", e);
                            callback.onError("Ошибка обработки данных эпизодов: " + e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("AnimeApiService", "Error in episodes API call", e);
                callback.onError("Ошибка выполнения запроса: " + e.getMessage());
            }
        });
    }

    public void fetchEpisodeData(int episodeId, EpisodeDataCallback callback) {
        safeExecute(() -> {
            try {
                String apiUrl = "https://api.cdnlibs.org/api/episodes/" + episodeId;
                Log.d("AnimeApiService", "Fetching episode data for episode_id: " + episodeId);

                Request request = buildApiRequest(apiUrl).build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("AnimeApiService", "Episode data request failed", e);
                        callback.onError("Ошибка загрузки данных эпизода: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            if (response.isSuccessful()) {
                                assert response.body() != null;
                                String responseBody = response.body().string();
                                Log.d("AnimeApiService", "Episode data response received");

                                EpisodeResponse episodeResponse = gson.fromJson(responseBody, EpisodeResponse.class);
                                if (episodeResponse != null && episodeResponse.getData() != null) {
                                    callback.onEpisodeDataReceived(episodeResponse);
                                } else {
                                    callback.onError("Неверный формат ответа эпизода");
                                }
                            } else {
                                callback.onError("HTTP ошибка: " + response.code());
                            }
                        } catch (Exception e) {
                            Log.e("AnimeApiService", "Error processing episode data response", e);
                            callback.onError("Ошибка обработки данных эпизода: " + e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("AnimeApiService", "Error in episode data API call", e);
                callback.onError("Ошибка выполнения запроса: " + e.getMessage());
            }
        });
    }

    /**
     * Получает ссылки Kodik напрямую, без внешнего сервиса.
     */
    public void fetchKodikVideoLinks(String kodikSrc, KodikVideoCallback callback) {
        safeExecute(() -> {
            try {
                Log.d("AnimeApiService", "Fetching Kodik video links for src: " + kodikSrc);
                KodikResponse kodikResponse = kodikLinksExtractor.getLinks(kodikSrc);

                if (kodikResponse.isSuccess() && kodikResponse.getData() != null
                        && !kodikResponse.getData().isEmpty()) {
                    callback.onKodikVideoReceived(kodikResponse);
                } else {
                    callback.onError("Kodik не вернул доступных качеств");
                }
            } catch (Exception e) {
                Log.e("AnimeApiService", "Error in Kodik links extraction", e);
                callback.onError("Ошибка получения ссылок Kodik: " + e.getMessage());
            }
        });
    }

    public void fetchEpisodeComments(long episodeId, String sortType, EpisodeCommentsCallback callback) {
        fetchEpisodeComments(episodeId, sortType, 1, callback);
    }

    public void fetchEpisodeComments(long episodeId, String sortType, int page, EpisodeCommentsCallback callback) {
        safeExecute(() -> {
            try {
                String apiUrl = getSort(episodeId, sortType, page);

                Request request = buildApiRequest(apiUrl).build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        if (!response.isSuccessful()) {
                            safeRunOnUiThread(() -> callback.onError("HTTP " + response.code()));
                            return;
                        }
                        if (response.body() == null) {
                            safeRunOnUiThread(() -> callback.onError("Пустой ответ сервера"));
                            return;
                        }
                        String body = response.body().string();
                        try {
                            CommentsResponse comments = gson.fromJson(body, CommentsResponse.class);
                            safeRunOnUiThread(() -> callback.onCommentsReceived(comments));
                        } catch (Exception ex) {
                            safeRunOnUiThread(() -> callback.onError("Ошибка парсинга"));
                        }
                    }
                });
            } catch (Exception e) {
                safeRunOnUiThread(() -> callback.onError("Ошибка запроса: " + e.getMessage()));
            }
        });
    }

    public void fetchStickyComments(long episodeId, StickyCommentsCallback callback) {
        safeExecute(() -> {
            try {
                String apiUrl = "https://hapi.hentaicdn.org/api/comments/sticky?post_id=" + episodeId + "&post_type=episodes";
                Request request = buildApiRequest(apiUrl).build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        if (!response.isSuccessful()) {
                            safeRunOnUiThread(() -> callback.onError("HTTP " + response.code()));
                            return;
                        }
                        if (response.body() == null) {
                            safeRunOnUiThread(() -> callback.onError("Пустой ответ сервера"));
                            return;
                        }
                        String body = response.body().string();
                        try {
                            com.example.animelib.models.StickyCommentsResponse stickyResp = gson.fromJson(body, com.example.animelib.models.StickyCommentsResponse.class);
                            List<CommentsResponse.CommentItem> list = (stickyResp != null && stickyResp.getData() != null)
                                    ? stickyResp.getData() : new ArrayList<>();
                            for (CommentsResponse.CommentItem item : list) {
                                if (item != null) {
                                    item.setSticky(true);
                                }
                            }
                            safeRunOnUiThread(() -> callback.onStickyCommentsReceived(list));
                        } catch (Exception ex) {
                            safeRunOnUiThread(() -> callback.onError("Ошибка парсинга"));
                        }
                    }
                });
            } catch (Exception e) {
                safeRunOnUiThread(() -> callback.onError("Ошибка запроса: " + e.getMessage()));
            }
        });
    }

    @NonNull
    private static String getSort(long episodeId, String sortType, int page) {
        String safeSort = (sortType == null || sortType.isEmpty()) ? "desc" : sortType;
        int safePage = Math.max(1, page);
        // API: для популярного нужна сортировка по полю голосов
        boolean byVotes = "votes_up".equalsIgnoreCase(safeSort);
        String sortBy = byVotes ? "votes_up" : "id";
        String sortDir = byVotes ? "desc" : safeSort;
        return "https://hapi.hentaicdn.org/api/comments?page=" + safePage +
                "&post_id=" + episodeId +
                "&post_type=episodes&sort_by=" + sortBy + "&sort_type=" + sortDir;
    }

    /**
     * Отправка нового комментария или ответа к эпизоду
     */
    public void postComment(long episodeId, String rawCommentText, Long parentCommentId, Long rootId, int commentLevel, PostCommentCallback callback) {
        safeExecute(() -> {
            try {
                TokenEntity tokenEntity = databaseManager.getToken();
                if (tokenEntity == null || tokenEntity.getAccessToken() == null || tokenEntity.getAccessToken().trim().isEmpty()) {
                    Log.d("ApiService", "No token in DB. Cannot post comment.");
                    safeRunOnUiThread(() -> callback.onError("Необходима авторизация. Войдите в аккаунт на сайте."));
                    return;
                }

                String apiUrl = "https://hapi.hentaicdn.org/api/comments";
                com.google.gson.JsonObject requestBody = new com.google.gson.JsonObject();

                com.google.gson.JsonObject docObject = com.example.animelib.util.CommentDocBuilder.buildDoc(rawCommentText);
                requestBody.add("comment", docObject);
                requestBody.add("attachments", new com.google.gson.JsonArray());

                requestBody.addProperty("post_type", "episodes");
                requestBody.addProperty("post_id", episodeId);

                if (parentCommentId != null && parentCommentId > 0) {
                    requestBody.addProperty("parent_comment", parentCommentId);
                    if (rootId != null && rootId > 0) {
                        requestBody.addProperty("root_id", rootId);
                    } else {
                        requestBody.addProperty("root_id", parentCommentId);
                    }
                    requestBody.addProperty("comment_level", commentLevel > 0 ? commentLevel : 1);
                } else {
                    requestBody.add("parent_comment", com.google.gson.JsonNull.INSTANCE);
                    requestBody.add("root_id", com.google.gson.JsonNull.INSTANCE);
                    requestBody.addProperty("comment_level", 0);
                }

                String jsonString = requestBody.toString();
                Log.d("ApiService", "Posting comment payload: " + jsonString);

                okhttp3.RequestBody body = okhttp3.RequestBody.create(jsonString, okhttp3.MediaType.get("application/json; charset=utf-8"));
                
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer " + getBearerToken())
                        .addHeader("Referer", "https://v5.animelib.org/")
                        .post(body)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Post comment failed", e);

                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            Log.d("ApiService", "Post comment response (" + response.code() + "): " + responseBody);

                            if (response.isSuccessful()) {
                                CommentsResponse.CommentItem commentItem = null;
                                try {
                                    com.google.gson.JsonObject obj = gson.fromJson(responseBody, com.google.gson.JsonObject.class);
                                    if (obj != null && obj.has("data") && !obj.get("data").isJsonNull()) {
                                        commentItem = gson.fromJson(obj.get("data"), CommentsResponse.CommentItem.class);
                                    } else {
                                        commentItem = gson.fromJson(responseBody, CommentsResponse.CommentItem.class);
                                    }
                                } catch (Exception e) {
                                    Log.w("ApiService", "Failed to parse created comment item", e);
                                }
                                CommentsResponse.CommentItem finalItem = commentItem;
                                safeRunOnUiThread(() -> callback.onSuccess(finalItem));
                            } else if (response.code() == 401) {
                                safeRunOnUiThread(() -> callback.onError("Ошибка 401: Токен недействителен или сессия истекла. Переавторизуйтесь на сайте."));
                            } else {
                                String err = extractServerErrorMessage(responseBody, response.code());
                                safeRunOnUiThread(() -> callback.onError(err));
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error parsing post comment response", e);
                            safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("ApiService", "Error posting comment", e);
                safeRunOnUiThread(() -> callback.onError("Ошибка: " + e.getMessage()));
            }
        });
    }

    /**
     * Редактирование своего комментария (PUT https://hapi.hentaicdn.org/api/comments/{commentId})
     */
    public void editComment(long commentId, long episodeId, String rawCommentText, Long parentCommentId, Long rootId, int commentLevel, EditCommentCallback callback) {
        safeExecute(() -> {
            try {
                TokenEntity tokenEntity = databaseManager.getToken();
                if (tokenEntity == null || tokenEntity.getAccessToken() == null || tokenEntity.getAccessToken().trim().isEmpty()) {
                    Log.d("ApiService", "No token in DB. Cannot edit comment.");
                    safeRunOnUiThread(() -> callback.onError("Необходима авторизация. Войдите в аккаунт на сайте."));
                    return;
                }

                String apiUrl = "https://hapi.hentaicdn.org/api/comments/" + commentId;
                com.google.gson.JsonObject requestBody = new com.google.gson.JsonObject();

                com.google.gson.JsonObject docObject = com.example.animelib.util.CommentDocBuilder.buildDoc(rawCommentText);
                requestBody.add("comment", docObject);
                requestBody.add("attachments", new com.google.gson.JsonArray());

                requestBody.addProperty("post_type", "episodes");
                requestBody.addProperty("post_id", episodeId);

                if (parentCommentId != null && parentCommentId > 0) {
                    requestBody.addProperty("parent_comment", parentCommentId);
                    if (rootId != null && rootId > 0) {
                        requestBody.addProperty("root_id", rootId);
                    } else {
                        requestBody.addProperty("root_id", parentCommentId);
                    }
                    requestBody.addProperty("comment_level", commentLevel > 0 ? commentLevel : 1);
                } else {
                    requestBody.add("parent_comment", com.google.gson.JsonNull.INSTANCE);
                    requestBody.add("root_id", com.google.gson.JsonNull.INSTANCE);
                    requestBody.addProperty("comment_level", 0);
                }

                String jsonString = requestBody.toString();
                Log.d("ApiService", "Editing comment payload: " + jsonString);

                okhttp3.RequestBody body = okhttp3.RequestBody.create(jsonString, okhttp3.MediaType.get("application/json; charset=utf-8"));

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer " + getBearerToken())
                        .addHeader("Referer", "https://v5.animelib.org/")
                        .put(body)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Edit comment failed", e);
                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            Log.d("ApiService", "Edit comment response (" + response.code() + "): " + responseBody);

                            if (response.isSuccessful()) {
                                CommentsResponse.CommentItem commentItem = null;
                                try {
                                    com.google.gson.JsonObject obj = gson.fromJson(responseBody, com.google.gson.JsonObject.class);
                                    if (obj != null && obj.has("data") && !obj.get("data").isJsonNull()) {
                                        commentItem = gson.fromJson(obj.get("data"), CommentsResponse.CommentItem.class);
                                    } else {
                                        commentItem = gson.fromJson(responseBody, CommentsResponse.CommentItem.class);
                                    }
                                } catch (Exception e) {
                                    Log.w("ApiService", "Failed to parse edited comment item", e);
                                }
                                CommentsResponse.CommentItem finalItem = commentItem;
                                safeRunOnUiThread(() -> callback.onSuccess(finalItem));
                            } else if (response.code() == 401) {
                                safeRunOnUiThread(() -> callback.onError("Ошибка 401: Токен недействителен или сессия истекла. Переавторизуйтесь на сайте."));
                            } else {
                                String err = extractServerErrorMessage(responseBody, response.code());
                                safeRunOnUiThread(() -> callback.onError(err));
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error parsing edit comment response", e);
                            safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("ApiService", "Error editing comment", e);
                safeRunOnUiThread(() -> callback.onError("Ошибка: " + e.getMessage()));
            }
        });
    }

    /**
     * Голосование за комментарий (1 - лайк, 0 - дизлайк)
     */
    public void voteComment(long commentId, int voteValue, VoteCommentCallback callback) {
        safeExecute(() -> {
            try {
                TokenEntity tokenEntity = databaseManager.getToken();
                if (tokenEntity == null || tokenEntity.getAccessToken() == null || tokenEntity.getAccessToken().trim().isEmpty()) {
                    safeRunOnUiThread(() -> callback.onError("Необходима авторизация. Войдите в аккаунт на сайте."));
                    return;
                }

                String apiUrl = "https://hapi.hentaicdn.org/api/comments/" + commentId + "/vote";
                com.google.gson.JsonObject requestBody = new com.google.gson.JsonObject();
                
                // cdnlibs API expects vote: 1 (like), 0 (dislike)
                requestBody.addProperty("vote", voteValue);

                String jsonString = gson.toJson(requestBody);
                Log.d("ApiService", "Vote comment " + commentId + " payload: " + jsonString);

                okhttp3.RequestBody body = okhttp3.RequestBody.create(jsonString, okhttp3.MediaType.get("application/json; charset=utf-8"));
                Request request = buildApiRequest(apiUrl)
                        .post(body)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Vote comment failed", e);
                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            Log.d("ApiService", "Vote response (" + response.code() + "): " + responseBody);
                            if (response.isSuccessful()) {
                                CommentsResponse.Votes newVotes = null;
                                try {
                                    com.google.gson.JsonObject obj = gson.fromJson(responseBody, com.google.gson.JsonObject.class);
                                    if (obj != null && obj.has("data") && obj.getAsJsonObject("data").has("votes")) {
                                        newVotes = gson.fromJson(obj.getAsJsonObject("data").get("votes"), CommentsResponse.Votes.class);
                                    } else if (obj != null && obj.has("votes")) {
                                        newVotes = gson.fromJson(obj.get("votes"), CommentsResponse.Votes.class);
                                    }
                                } catch (Exception ignored) {}

                                CommentsResponse.Votes finalVotes = newVotes;
                                safeRunOnUiThread(() -> callback.onSuccess(voteValue, finalVotes));
                            } else if (response.code() == 401) {
                                safeRunOnUiThread(() -> callback.onError("Ошибка 401: Необходима авторизация. Войдите в аккаунт."));
                            } else {
                                String err = extractServerErrorMessage(responseBody, response.code());
                                safeRunOnUiThread(() -> callback.onError(err));
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error parsing vote response", e);
                            safeRunOnUiThread(() -> callback.onError("Ошибка ответа"));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("ApiService", "Error voting comment", e);
                safeRunOnUiThread(() -> callback.onError("Ошибка: " + e.getMessage()));
            }
        });
    }

    /**
     * Удаление комментария пользователя
     */
    public void deleteComment(long commentId, DeleteCommentCallback callback) {
        safeExecute(() -> {
            try {
                TokenEntity tokenEntity = databaseManager.getToken();
                if (tokenEntity == null || tokenEntity.getAccessToken() == null || tokenEntity.getAccessToken().trim().isEmpty()) {
                    Log.d("ApiService", "No token in DB. Cannot delete comment.");
                    safeRunOnUiThread(() -> callback.onError("Необходима авторизация. Войдите в аккаунт."));
                    return;
                }

                String apiUrl = "https://hapi.hentaicdn.org/api/comments/" + commentId;
                Log.d("ApiService", "Deleting comment URL: " + apiUrl);

                Request request = buildApiRequest(apiUrl)
                        .delete()
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Delete comment failed", e);
                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            Log.d("ApiService", "Delete comment response (" + response.code() + "): " + responseBody);
                            if (response.isSuccessful()) {
                                safeRunOnUiThread(() -> callback.onSuccess());
                            } else if (response.code() == 401) {
                                safeRunOnUiThread(() -> callback.onError("Ошибка 401: Сессия истекла. Переавторизуйтесь."));
                            } else if (response.code() == 403) {
                                safeRunOnUiThread(() -> callback.onError("Ошибка 403: Вы можете удалять только свои комментарии."));
                            } else {
                                String err = extractServerErrorMessage(responseBody, response.code());
                                safeRunOnUiThread(() -> callback.onError(err));
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error handling delete comment response", e);
                            safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("ApiService", "Error executing delete comment request", e);
                safeRunOnUiThread(() -> callback.onError("Ошибка выполнения запроса: " + e.getMessage()));
            }
        });
    }

    public interface IgnoreUserCallback {
        void onSuccess(String message);
        void onError(String errorMsg);
    }

    /**
     * Добавление пользователя в игнор-лист (POST https://hapi.hentaicdn.org/api/ignore)
     */
    public void ignoreUser(long sourceableId, long targetUserId, String commentText, IgnoreUserCallback callback) {
        safeExecute(() -> {
            try {
                TokenEntity tokenEntity = databaseManager.getToken();
                if (tokenEntity == null || tokenEntity.getAccessToken() == null || tokenEntity.getAccessToken().trim().isEmpty()) {
                    Log.d("ApiService", "No token in DB. Cannot ignore user.");
                    safeRunOnUiThread(() -> callback.onError("Необходима авторизация. Войдите в аккаунт."));
                    return;
                }

                String apiUrl = "https://hapi.hentaicdn.org/api/ignore";
                com.google.gson.JsonObject requestBody = new com.google.gson.JsonObject();
                requestBody.addProperty("sourceable_id", sourceableId);
                requestBody.addProperty("sourceable_type", "user");
                requestBody.addProperty("user_id", targetUserId);
                if (commentText != null && !commentText.trim().isEmpty()) {
                    requestBody.addProperty("comment", commentText.trim());
                }

                String jsonString = requestBody.toString();
                Log.d("ApiService", "Ignore user payload: " + jsonString);

                okhttp3.RequestBody body = okhttp3.RequestBody.create(jsonString, okhttp3.MediaType.get("application/json; charset=utf-8"));

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer " + getBearerToken())
                        .addHeader("Referer", "https://v5.animelib.org/")
                        .post(body)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Ignore user request failed", e);
                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            Log.d("ApiService", "Ignore user response (" + response.code() + "): " + responseBody);

                            if (response.isSuccessful()) {
                                safeRunOnUiThread(() -> callback.onSuccess("Пользователь добавлен в игнор-лист"));
                            } else if (response.code() == 401) {
                                safeRunOnUiThread(() -> callback.onError("Ошибка 401: Сессия истекла. Авторизуйтесь заново."));
                            } else {
                                String err = extractServerErrorMessage(responseBody, response.code());
                                safeRunOnUiThread(() -> callback.onError(err));
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error parsing ignore user response", e);
                            safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("ApiService", "Error ignoring user", e);
                safeRunOnUiThread(() -> callback.onError("Ошибка: " + e.getMessage()));
            }
        });
    }

    /**
     * Creates OkHttpClient with disabled SSL verification for domains with certificate issues
     */
    private OkHttpClient getUnsafeOkHttpClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            @SuppressLint("CustomX509TrustManager") final TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @SuppressLint("TrustAllX509TrustManager")
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @SuppressLint("TrustAllX509TrustManager")
                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[]{};
                    }
                }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager)trustAllCerts[0]);
            builder.hostnameVerifier(new HostnameVerifier() {
                @SuppressLint("BadHostnameVerifier")
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Extract anime_id from URL like "/ru/anime/23875--kanojo-okarishimasu-4th-season-anime/watch"
     */
    public String extractAnimeId(String url) {
        if (url == null) return null;
        try {
            String[] parts = url.split("/");
            for (String part : parts) {
                if (part.contains("--")) {
                    return part.split("--")[0];
                }
            }
            for (String part : parts) {
                if (part.matches("\\d+")) {
                    return part;
                }
            }
            for (String part : parts) {
                if (part.matches("^\\d+.*")) {
                    return part.replaceAll("^(\\d+).*", "$1");
                }
            }
            String identifier = com.example.animelib.ui.VideoUrlHelper.extractAnimeIdentifier(url);
            if (identifier != null) {
                String numericId = com.example.animelib.ui.VideoUrlHelper.extractAnimeId(identifier);
                if (numericId != null) return numericId;
            }
        } catch (Exception e) {
            Log.e("AnimeApiService", "Error extracting anime_id from URL: " + url, e);
        }
        return null;
    }

    /**
     * Extract full anime slug segment e.g. "24653--sakamoto-days-part-2-anime" from URL
     */
    public String extractAnimeSlug(String url) {
        try {
            String[] parts = url.split("/");
            for (String part : parts) {
                if (part.contains("--")) {
                    return part; // return full slug with id and name
                }
            }
        } catch (Exception e) {
            Log.e("AnimeApiService", "Error extracting anime slug from URL: " + url, e);
        }
        return null;
    }

    /**
     * Load episodes for anime and get first episode data
     */
    public void loadAnimeFromUrl(String animeUrl, EpisodeDataCallback callback) {
        String animeId = extractAnimeId(animeUrl);
        if (animeId == null) {
            callback.onError("Не удалось извлечь ID аниме из URL");
            return;
        }

        Log.d("AnimeApiService", "Extracted anime_id: " + animeId + " from URL: " + animeUrl);

        safeExecute(() -> {
            try {
                String apiUrl = "https://api.cdnlibs.org/api/episodes?anime_id=" + animeId;
                Log.d("AnimeApiService", "Making direct API request to: " + apiUrl);

                Request request = buildApiRequest(apiUrl).build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("AnimeApiService", "Direct API request failed", e);
                        callback.onError("Ошибка API: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            if (response.isSuccessful()) {
                                assert response.body() != null;
                                String responseBody = response.body().string();
                                Log.d("AnimeApiService", "Direct API response: " + responseBody);

                                EpisodesListResponse episodesResponse = gson.fromJson(responseBody, EpisodesListResponse.class);
                                if (episodesResponse != null && episodesResponse.getData() != null && !episodesResponse.getData().isEmpty()) {
                                    // Select first episode (index 0)
                                    int episodeId = episodesResponse.getData().get(0).getId();
                                    Log.d("AnimeApiService", "Selected episode ID: " + episodeId);
                                    fetchEpisodeData(episodeId, callback);
                                } else {
                                    callback.onError("Эпизоды не найдены");
                                }
                            } else {
                                Log.e("AnimeApiService", "API response not successful: " + response.code());
                                callback.onError("Ошибка API: " + response.code());
                            }
                        } catch (Exception e) {
                            Log.e("AnimeApiService", "Error processing API response", e);
                            callback.onError("Ошибка обработки ответа");
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("AnimeApiService", "Error in API call", e);
                callback.onError("Ошибка запроса");
            }
        });
    }

    /**
     * Устаревший псевдоним fetchKodikVideoLinks.
     */
    @Deprecated
    public void fetchKodikVideoLinksUnsafe(String kodikSrc, KodikVideoCallback callback) {
        fetchKodikVideoLinks(kodikSrc, callback);
    }

    public void save4KSetting(boolean enable4K) {
        databaseManager.save4KSetting(enable4K);
    }
    
    public boolean load4KSetting() {
        return databaseManager.load4KSetting();
    }
    
    public void saveAmbientLightSetting(boolean enableAmbientLight) {
        databaseManager.saveAmbientLightSetting(enableAmbientLight);
    }
    
    public boolean loadAmbientLightSetting() {
        return databaseManager.loadAmbientLightSetting();
    }

    public void saveVideoFilters(float brightness, float contrast, float saturation, float gamma, float hue) {
        databaseManager.saveVideoFilters(brightness, contrast, saturation, gamma, hue);
    }

    public float[] loadVideoFilters() {
        return databaseManager.loadVideoFilters();
    }
    
    public void saveSurroundSoundSetting(boolean enableSurroundSound) {
        databaseManager.saveSurroundSoundSetting(enableSurroundSound);
    }
    
    public boolean loadSurroundSoundSetting() {
        return databaseManager.loadSurroundSoundSetting();
    }

    public void saveSurround3DSettings(int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost) {
        databaseManager.saveSurround3DSettings(mode, spatialWidth, dialogueBoost, bassBoost, trebleBoost);
    }

    public int loadSurround3DMode() {
        return databaseManager.loadSurround3DMode();
    }

    public float loadSurroundSpatialWidth() {
        return databaseManager.loadSurroundSpatialWidth();
    }

    public float loadSurroundDialogueBoost() {
        return databaseManager.loadSurroundDialogueBoost();
    }

    public float loadSurroundBassBoost() {
        return databaseManager.loadSurroundBassBoost();
    }

    public float loadSurroundTrebleBoost() {
        return databaseManager.loadSurroundTrebleBoost();
    }
    
    public void saveAutoPlaySetting(boolean autoPlay) {
        databaseManager.saveAutoPlaySetting(autoPlay);
    }
    
    public boolean loadAutoPlaySetting() {
        return databaseManager.loadAutoPlaySetting();
    }
    
    public void saveLongSkipDurationSetting(int duration) {
        databaseManager.saveLongSkipDurationSetting(duration);
    }
    
    public int loadLongSkipDurationSetting() {
        return databaseManager.loadLongSkipDurationSetting();
    }
    
    public void saveThemeSetting(int themeMode) {
        databaseManager.saveThemeSetting(themeMode);
    }
    
    public int loadThemeSetting() {
        return databaseManager.loadThemeSetting();
    }

    public void saveSubtitlesEnabledSetting(boolean enable) {
        databaseManager.saveSubtitlesEnabledSetting(enable);
    }

    public boolean loadSubtitlesEnabledSetting() {
        return databaseManager.loadSubtitlesEnabledSetting();
    }

    public void saveSubtitleFormatSetting(String format) {
        databaseManager.saveSubtitleFormatSetting(format);
    }

    public String loadSubtitleFormatSetting() {
        return databaseManager.loadSubtitleFormatSetting();
    }

    public void saveSubtitleStyleSettings(float textSize, int textColor, int bgColor, int edgeType, int edgeColor) {
        databaseManager.saveSubtitleStyleSettings(textSize, textColor, bgColor, edgeType, edgeColor);
    }

    public float loadSubtitleTextSizeSetting() {
        return databaseManager.loadSubtitleTextSizeSetting();
    }

    public int loadSubtitleTextColorSetting() {
        return databaseManager.loadSubtitleTextColorSetting();
    }

    public int loadSubtitleBackgroundColorSetting() {
        return databaseManager.loadSubtitleBackgroundColorSetting();
    }

    public int loadSubtitleEdgeTypeSetting() {
        return databaseManager.loadSubtitleEdgeTypeSetting();
    }

    public int loadSubtitleEdgeColorSetting() {
        return databaseManager.loadSubtitleEdgeColorSetting();
    }
    
    public void savePlayerPreferences(String player, Integer teamId) {
        databaseManager.savePlayerPreferences(player, teamId);
    }
    
    public void savePlayerPreferences(String player, Integer teamId, String preferredQuality) {
        databaseManager.savePlayerPreferences(player, teamId, preferredQuality);
    }
    
    public com.example.animelib.data.entity.PlayerPreferences loadPlayerPreferences() {
        return databaseManager.loadPlayerPreferences();
    }
    
    public com.example.animelib.data.DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    public OkHttpClient getHttpClient() {
        return httpClient;
    }
    
    public Gson getGson() {
        return gson;
    }

    public void checkApiForToast(ToastCheckCallback callback) {
        safeExecute(() -> {
            String apiUrl = "https://api.cdnlibs.org/api/";
            Request request = buildApiRequest(apiUrl).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e("AnimeApiService", "Toast API request failed", e);
                    callback.onError("Хуй там - " + e);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (response) {
                        if (response.isSuccessful()) {
                            assert response.body() != null;
                            String responseBody = response.body().string();
                            Log.d("AnimeApiService", "Toast API Response: " + responseBody);

                            // Проверяем, что ответ является валидным JSON объектом
                            if (responseBody.trim().startsWith("{") && responseBody.trim().endsWith("}")) {
                                ApiResponse apiResponse = gson.fromJson(responseBody, ApiResponse.class);

                                if (apiResponse != null && apiResponse.getData() != null
                                    && apiResponse.getData().getToast() != null
                                    && apiResponse.getData().getToast().getButtons() != null
                                    && !apiResponse.getData().getToast().getButtons().isEmpty()) {

                                    ToastData.ButtonData button = apiResponse.getData().getToast().getButtons().get(0);
                                    String message = button.getText();

                                    if (message != null && message.contains("Перейти на зеркало")) {
                                        // Извлекаем новый URL из href
                                        String newUrl = button.getHref();
                                        if (newUrl != null && !newUrl.isEmpty()) {
                                            callback.onToastReceived(message, newUrl);
                                            Log.d("AnimeApiService", "Mirror URL found: " + newUrl);
                                        } else {
                                            callback.onError("Хуй там нет URL");
                                        }
                                    } else if (message != null) {
                                        // Показываем обычное сообщение
                                        callback.onToastReceived(message, null);
                                    } else {
                                        callback.onError("Хуй там нет текста");
                                    }
                                } else {
                                    callback.onError("Хуй там нет текста");
                                }
                            } else {
                                // Ответ не является JSON объектом
                                Log.w("AnimeApiService", "Toast API returned non-JSON response: " + responseBody);
                                callback.onError("Неверный формат ответа API");
                            }
                        } else {
                            Log.e("AnimeApiService", "Toast API request failed with code: " + response.code());
                            callback.onError("HTTP " + response.code());
                        }
                    } catch (Exception e) {
                        Log.e("AnimeApiService", "Error parsing toast API response", e);
                        callback.onError("Хуй там - " + e);
                    }
                }
            });
        });
    }

    public interface ViewCallback {
        void onSuccess();
        void onError(String error);
    }

    /**
     * Помечает эпизод просмотренным (Синхронно)
     * POST https://hapi.hentaicdn.org/api/anime/{anime_id}/players/{player_id}/view
     */
    public boolean markEpisodeViewedSync(String animeId, int playerId) {
        if (animeId == null || animeId.isEmpty() || playerId <= 0) {
            Log.e("ApiService", "Invalid parameters for markEpisodeViewedSync: animeId=" + animeId + ", playerId=" + playerId);
            return false;
        }
        String cleanAnimeId = com.example.animelib.ui.VideoUrlHelper.extractAnimeId(animeId);
        if (cleanAnimeId == null || cleanAnimeId.isEmpty()) {
            cleanAnimeId = animeId;
        }
        String url = "https://hapi.hentaicdn.org/api/anime/" + cleanAnimeId + "/players/" + playerId + "/view";
        Log.d("ApiService", "Marking episode viewed: " + url);
        try {
            okhttp3.RequestBody emptyBody = okhttp3.RequestBody.create("", okhttp3.MediaType.parse("application/json; charset=utf-8"));
            Request request = buildApiRequest(url)
                    .post(emptyBody)
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                boolean success = response.isSuccessful();
                Log.d("ApiService", "markEpisodeViewedSync response code: " + response.code() + ", success: " + success);
                return success;
            }
        } catch (Exception e) {
            Log.e("ApiService", "Error in markEpisodeViewedSync for " + url, e);
            return false;
        }
    }

    /**
     * Помечает эпизод просмотренным (Асинхронно)
     */
    public void markEpisodeViewed(String animeId, int playerId, ViewCallback callback) {
        safeExecute(() -> {
            boolean success = markEpisodeViewedSync(animeId, playerId);
            if (success) {
                if (callback != null) safeRunOnUiThread(callback::onSuccess);
            } else {
                if (callback != null) safeRunOnUiThread(() -> callback.onError("Failed to mark episode as viewed"));
            }
        });
    }

    /**
     * Добавляет серию в закладки (Синхронно)
     */
    public boolean addBookmarkSync(String mediaSlug, int episodeId, int teamId, int episodeNumber, String currentTimecode) {
        return addBookmarkSync(mediaSlug, episodeId, teamId, episodeNumber, currentTimecode, null);
    }

    public boolean addBookmarkSync(String mediaSlug, int episodeId, int teamId, int episodeNumber, String currentTimecode, Object statusId) {
        if (mediaSlug == null || mediaSlug.isEmpty()) return false;
        try {
            com.google.gson.JsonObject requestBody = createBookmarkRequestBody(
                    mediaSlug, episodeId, teamId, episodeNumber, currentTimecode, statusId
            );
            String jsonString = gson.toJson(requestBody);
            okhttp3.RequestBody body = okhttp3.RequestBody.create(jsonString, okhttp3.MediaType.get("application/json; charset=utf-8"));
            Request request = buildApiRequest("https://api.cdnlibs.org/api/bookmarks")
                    .post(body)
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                boolean success = response.isSuccessful();
                Log.d("ApiService", "addBookmarkSync response code: " + response.code() + ", success: " + success);
                return success;
            }
        } catch (Exception e) {
            Log.e("ApiService", "Error in addBookmarkSync", e);
            return false;
        }
    }

    /**
     * Добавляет серию в закладки
     * @param mediaSlug Слаг медиа (например: "23811--kaijuu-8-gou-2nd-season-anime")
     * @param episodeId ID эпизода
     * @param teamId ID команды перевода
     * @param episodeNumber Номер эпизода
     * @param currentTimecode Текущее время в формате "12:01"
     * @param callback Колбэк для результата операции
     */
    public void addBookmark(String mediaSlug, int episodeId, int teamId, int episodeNumber, 
                           String currentTimecode, BookmarkCallback callback) {
        addBookmark(mediaSlug, episodeId, teamId, episodeNumber, currentTimecode, null, callback);
    }

    public void addBookmark(String mediaSlug, int episodeId, int teamId, int episodeNumber, 
                           String currentTimecode, Object statusId, BookmarkCallback callback) {
        
        Log.d("ApiService", "Adding bookmark - mediaSlug: " + mediaSlug + 
                   ", episodeId: " + episodeId + 
                   ", teamId: " + teamId + 
                   ", episodeNumber: " + episodeNumber + 
                   ", timecode: " + currentTimecode +
                   ", statusId: " + statusId);
        
        safeExecute(() -> {
            try {
                // Создаем JSON объект для запроса
                com.google.gson.JsonObject requestBody = createBookmarkRequestBody(
                    mediaSlug, episodeId, teamId, episodeNumber, currentTimecode, statusId
                );
                
                String jsonString = gson.toJson(requestBody);
                Log.d("ApiService", "Request body: " + jsonString);
                
                // Создаем HTTP запрос
                okhttp3.RequestBody body = okhttp3.RequestBody.create(jsonString, okhttp3.MediaType.get("application/json; charset=utf-8"));
                Request request = buildApiRequest("https://api.cdnlibs.org/api/bookmarks")
                    .post(body)
                    .build();
                
                // Выполняем запрос
                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Bookmark request failed", e);
                        // Вызываем колбэк в главном потоке
                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }
                    
                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            if (response.isSuccessful()) {
                                String responseBody = response.body() != null ? response.body().string() : "";
                                Log.d("ApiService", "Bookmark added successfully: " + responseBody);
                                // Вызываем колбэк в главном потоке
                                safeRunOnUiThread(() -> callback.onSuccess("Закладка добавлена успешно"));
                            } else {
                                String errorBody = response.body() != null ? response.body().string() : "";
                                Log.e("ApiService", "Failed to add bookmark. Code: " + response.code() + ", Body: " + errorBody);
                                // Вызываем колбэк в главном потоке
                                safeRunOnUiThread(() -> callback.onError("Ошибка при добавлении закладки: " + response.code()));
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error processing bookmark response", e);
                            // Вызываем колбэк в главном потоке
                            safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
                
            } catch (Exception e) {
                Log.e("ApiService", "Unexpected error while adding bookmark", e);
                // Вызываем колбэк в главном потоке
                safeRunOnUiThread(() -> callback.onError("Неожиданная ошибка: " + e.getMessage()));
            }
        });
    }
    
    /**
     * Обновляет статус просмотра аниме (POST https://hapi.hentaicdn.org/api/bookmarks)
     * @param mediaSlug Слаг медиа (например: "26610--nijusseiki-denki-mokuroku-eureka-evrika-anime")
     * @param statusId ID статуса (например: 21, 22, 23, 24, 25, 26, 27 или "other")
     * @param callback Колбэк для результата операции
     */
    public void updateWatchStatus(String mediaSlug, Object statusId, BookmarkCallback callback) {
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            Log.e("ApiService", "updateWatchStatus: mediaSlug is null or empty");
            if (callback != null) {
                safeRunOnUiThread(() -> callback.onError("Слаг аниме не определен"));
            }
            return;
        }

        Log.d("ApiService", "Updating watch status - mediaSlug: " + mediaSlug + ", statusId: " + statusId);

        safeExecute(() -> {
            try {
                com.google.gson.JsonObject requestBody = new com.google.gson.JsonObject();
                requestBody.addProperty("media_type", "anime");
                requestBody.addProperty("media_slug", mediaSlug);

                com.google.gson.JsonObject bookmark = new com.google.gson.JsonObject();
                if (statusId instanceof Number) {
                    bookmark.addProperty("status", ((Number) statusId).intValue());
                } else if (statusId instanceof String) {
                    try {
                        int parsedInt = Integer.parseInt((String) statusId);
                        bookmark.addProperty("status", parsedInt);
                    } catch (NumberFormatException e) {
                        bookmark.addProperty("status", (String) statusId);
                    }
                } else if (statusId != null) {
                    bookmark.addProperty("status", statusId.toString());
                }
                requestBody.add("bookmark", bookmark);
                requestBody.add("meta", new com.google.gson.JsonObject());

                String jsonString = gson.toJson(requestBody);
                Log.d("ApiService", "updateWatchStatus request body: " + jsonString);

                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        jsonString,
                        okhttp3.MediaType.get("application/json; charset=utf-8")
                );

                Request request = buildApiRequest("https://api.cdnlibs.org/api/bookmarks")
                        .post(body)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "updateWatchStatus request failed", e);
                        if (callback != null) {
                            safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                        }
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            Log.d("ApiService", "updateWatchStatus response (" + response.code() + "): " + responseBody);
                            if (response.isSuccessful()) {
                                if (callback != null) {
                                    safeRunOnUiThread(() -> callback.onSuccess("Статус обновлен"));
                                }
                            } else {
                                if (callback != null) {
                                    safeRunOnUiThread(() -> callback.onError("Ошибка при обновлении статуса: " + response.code()));
                                }
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error reading updateWatchStatus response", e);
                            if (callback != null) {
                                safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа"));
                            }
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("ApiService", "Unexpected error in updateWatchStatus", e);
                if (callback != null) {
                    safeRunOnUiThread(() -> callback.onError("Неожиданная ошибка: " + e.getMessage()));
                }
            }
        });
    }

    /**
     * Создает JSON объект для запроса добавления закладки
     */
    private com.google.gson.JsonObject createBookmarkRequestBody(String mediaSlug, int episodeId, int teamId, 
                                               int episodeNumber, String currentTimecode, Object statusId) {
        
        com.google.gson.JsonObject requestBody = new com.google.gson.JsonObject();
        requestBody.addProperty("media_type", "anime");
        requestBody.addProperty("media_slug", mediaSlug);
        
        // Создаем объект bookmark
        com.google.gson.JsonObject bookmark = new com.google.gson.JsonObject();
        bookmark.addProperty("item_id", episodeId);
        
        if (statusId instanceof Number) {
            bookmark.addProperty("status", ((Number) statusId).intValue());
        } else if (statusId instanceof String) {
            try {
                int parsedInt = Integer.parseInt((String) statusId);
                bookmark.addProperty("status", parsedInt);
            } catch (NumberFormatException e) {
                bookmark.addProperty("status", (String) statusId);
            }
        } else if (statusId != null) {
            bookmark.addProperty("status", statusId.toString());
        } else {
            bookmark.addProperty("status", 21);
        }
        
        bookmark.addProperty("progress", currentTimecode);
        requestBody.add("bookmark", bookmark);
        
        // Создаем объект meta
        com.google.gson.JsonObject meta = new com.google.gson.JsonObject();
        meta.addProperty("team", teamId);
        meta.addProperty("translation_type", 2);
        meta.addProperty("player", "Animelib");
        meta.addProperty("item_number", episodeNumber);
        requestBody.add("meta", meta);
        
        return requestBody;
    }
    
    /**
     * Извлекает media_slug из URL аниме
     * @param animeUrl URL аниме
     * @return media_slug или null если не удалось извлечь
     */
    public static String extractMediaSlugFromUrl(String animeUrl) {
        if (animeUrl == null || animeUrl.isEmpty()) {
            return null;
        }
        
        try {
            // Ищем паттерн /anime/{slug} в URL
            String pattern = "/anime/([^/\\?]+)";
            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher matcher = regex.matcher(animeUrl);
            
            if (matcher.find()) {
                String slug = matcher.group(1);
                Log.d("ApiService", "Extracted media slug: " + slug);
                return slug;
            }
            
            Log.w("ApiService", "Could not extract media slug from URL: " + animeUrl);
            return null;
            
        } catch (Exception e) {
            Log.e("ApiService", "Error extracting media slug from URL: " + animeUrl, e);
            return null;
        }
    }
    
    /**
     * Форматирует время в формат "MM:SS"
     * @param milliseconds Время в миллисекундах
     * @return Отформатированное время
     */
    public static String formatTimecode(long milliseconds) {
        if (milliseconds < 0) {
            return "00:00";
        }
        
        long totalSeconds = milliseconds / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        return String.format("%02d:%02d", minutes, seconds);
    }
    
    /**
     * Безопасно выполняет задачу в executor
     */
    private void safeExecute(Runnable task) {
        try {
            if (executor != null && !executor.isShutdown()) {
                executor.execute(task);
            } else {
                Log.w("ApiService", "Executor is shutdown, skipping task execution");
            }
        } catch (Exception e) {
            Log.e("ApiService", "Error executing task", e);
        }
    }

    /**
     * Получает закладку аниме
     * @param mediaSlug Слаг медиа (например: "23811--kaijuu-8-gou-2nd-season-anime")
     * @param callback Колбэк для результата операции
     */
    public void fetchAnimeBookmark(String mediaSlug, AnimeBookmarkCallback callback) {
        Log.d("ApiService", "Fetching anime bookmark for mediaSlug: " + mediaSlug);
        
        safeExecute(() -> {
            try {
                String apiUrl = "https://api.cdnlibs.org/api/anime/" + mediaSlug + "/bookmark";
                Request request = buildApiRequest(apiUrl).build();
                
                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Anime bookmark request failed", e);
                        // Вызываем колбэк в главном потоке
                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }
                    
                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            if (response.isSuccessful()) {
                                String responseBody = response.body() != null ? response.body().string() : "";
                                Log.d("ApiService", "Anime bookmark response: " + responseBody);
                                
                                AnimeBookmarkResponse bookmarkResponse = gson.fromJson(responseBody, AnimeBookmarkResponse.class);
                                if (bookmarkResponse != null) {
                                    // Вызываем колбэк в главном потоке (даже если data == null, это нормально - нет закладки)
                                    safeRunOnUiThread(() -> callback.onBookmarkReceived(bookmarkResponse));
                                } else {
                                    // Вызываем колбэк в главном потоке
                                    safeRunOnUiThread(() -> callback.onError("Неверный формат ответа закладки"));
                                }
                            } else {
                                String errorBody = response.body() != null ? response.body().string() : "";
                                Log.e("ApiService", "Failed to fetch anime bookmark. Code: " + response.code() + ", Body: " + errorBody);
                                // Вызываем колбэк в главном потоке
                                safeRunOnUiThread(() -> callback.onError("Ошибка при получении закладки: " + response.code()));
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error processing anime bookmark response", e);
                            // Вызываем колбэк в главном потоке
                            safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
                
            } catch (Exception e) {
                Log.e("ApiService", "Unexpected error while fetching anime bookmark", e);
                // Вызываем колбэк в главном потоке
                safeRunOnUiThread(() -> callback.onError("Неожиданная ошибка: " + e.getMessage()));
            }
        });
    }

    /**
     * Получает список закладок пользователя strictly с использованием токена из БД.
     * Если токена в БД нет — запрос не выполняется и возвращается ошибка.
     * @param callback Колбэк для результата операции
     */
    public void fetchBookmarksList(BookmarksListCallback callback) {
        Log.d("ApiService", "Fetching bookmarks list");
        
        safeExecute(() -> {
            try {
                TokenEntity tokenEntity = databaseManager.getToken();
                if (tokenEntity == null || tokenEntity.getAccessToken() == null || tokenEntity.getAccessToken().trim().isEmpty()) {
                    Log.d("ApiService", "No token in DB. Skipping bookmarks request.");
                    safeRunOnUiThread(() -> callback.onError("No token in database"));
                    return;
                }

                String accessToken = tokenEntity.getAccessToken();
                String userId = tokenEntity.getUserId();
                if (userId == null || userId.trim().isEmpty()) {
                    userId = extractUserIdFromToken(accessToken);
                }

                String apiUrl = "https://api.cdnlibs.org/api/bookmarks?page=1&sort_by=name&sort_type=desc&status=21";
                if (userId != null && !userId.trim().isEmpty()) {
                    apiUrl += "&user_id=" + userId;
                }

                Request request = buildApiRequest(apiUrl).build();
                
                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Bookmarks list request failed", e);
                        // Вызываем колбэк в главном потоке
                        safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }
                    
                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            if (response.isSuccessful()) {
                                String responseBody = response.body() != null ? response.body().string() : "";
                                Log.d("ApiService", "Bookmarks list response: " + responseBody);
                                
                                BookmarksListResponse bookmarksResponse = gson.fromJson(responseBody, BookmarksListResponse.class);
                                if (bookmarksResponse != null && bookmarksResponse.getData() != null && !bookmarksResponse.getData().isEmpty()) {
                                    // Вызываем колбэк в главном потоке
                                    safeRunOnUiThread(() -> callback.onBookmarksReceived(bookmarksResponse));
                                } else {
                                    // Вызываем колбэк в главном потоке
                                    safeRunOnUiThread(() -> callback.onError("Закладок нет"));
                                }
                            } else {
                                String errorBody = response.body() != null ? response.body().string() : "";
                                Log.e("ApiService", "Failed to fetch bookmarks list. Code: " + response.code() + ", Body: " + errorBody);
                                // Вызываем колбэк в главном потоке
                                safeRunOnUiThread(() -> callback.onError("Ошибка при получении закладок: " + response.code()));
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error processing bookmarks list response", e);
                            // Вызываем колбэк в главном потоке
                            safeRunOnUiThread(() -> callback.onError("Ошибка обработки ответа: " + e.getMessage()));
                        }
                    }
                });
                
            } catch (Exception e) {
                Log.e("ApiService", "Unexpected error while fetching bookmarks list", e);
                // Вызываем колбэк в главном потоке
                safeRunOnUiThread(() -> callback.onError("Неожиданная ошибка: " + e.getMessage()));
            }
        });
    }

    /**
     * Безопасно вызывает колбэк в главном потоке
     */
    private void safeRunOnUiThread(Runnable runnable) {
        if (runnable == null) return;
        try {
            if (context instanceof android.app.Activity) {
                android.app.Activity activity = (android.app.Activity) context;
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    activity.runOnUiThread(runnable);
                } else {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(runnable);
                }
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(runnable);
            }
        } catch (Exception e) {
            Log.e("ApiService", "Error calling UI thread callback", e);
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(runnable);
            } catch (Exception ex) {
                Log.e("ApiService", "Error in fallback callback", ex);
            }
        }
    }

    /**
     * Получает связанные тайтлы для аниме
     * @param animeSlug Слаг аниме
     * @param callback Callback для получения результата
     */
    public void getRelatedTitles(String animeSlug, RelatedTitlesCallback callback) {
        safeExecute(() -> {
            try {
                String url = "https://api.cdnlibs.org/api/anime/" + animeSlug + "/relations";
                Log.d("ApiService", "Fetching related titles from: " + url);

                Request request = buildApiRequest(url).build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("ApiService", "Failed to fetch related titles", e);
                        callback.onError("Network error: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try {
                            if (response.isSuccessful()) {
                                String responseBody = response.body().string();
                                Log.d("ApiService", "Related titles response: " + responseBody);

                                Gson gson = new Gson();
                                RelatedTitlesResponse relatedTitlesResponse = gson.fromJson(responseBody, RelatedTitlesResponse.class);

                                callback.onRelatedTitlesReceived(relatedTitlesResponse);
                            } else {
                                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                                Log.e("ApiService", "Failed to fetch related titles: " + response.code() + " - " + errorBody);
                                callback.onError("Server error: " + response.code());
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error parsing related titles response", e);
                            callback.onError("Parse error: " + e.getMessage());
                        } finally {
                            response.close();
                        }
                    }
                });

            } catch (Exception e) {
                Log.e("ApiService", "Error in getRelatedTitles", e);
                callback.onError("Request error: " + e.getMessage());
            }
        });
    }

    private String extractServerErrorMessage(String responseBody, int responseCode) {
        if (responseBody != null && !responseBody.trim().isEmpty()) {
            String trimmed = responseBody.trim();
            if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html")) {
                return "Ошибка сервера (" + responseCode + ")";
            }
            try {
                com.google.gson.JsonObject errObj = gson.fromJson(trimmed, com.google.gson.JsonObject.class);
                if (errObj != null) {
                    // 1. Проверяем data.toast.message (структура {"data":{"toast":{"type":"error","message":"..."}}})
                    if (errObj.has("data") && errObj.get("data").isJsonObject()) {
                        com.google.gson.JsonObject dataObj = errObj.getAsJsonObject("data");
                        if (dataObj.has("toast") && dataObj.get("toast").isJsonObject()) {
                            com.google.gson.JsonObject toastObj = dataObj.getAsJsonObject("toast");
                            if (toastObj.has("message") && !toastObj.get("message").isJsonNull()) {
                                String msg = toastObj.get("message").getAsString();
                                if (!msg.trim().isEmpty()) {
                                    return msg;
                                }
                            }
                        }
                    }
                    // 2. Проверяем toast.message на верхнем уровне
                    if (errObj.has("toast") && errObj.get("toast").isJsonObject()) {
                        com.google.gson.JsonObject toastObj = errObj.getAsJsonObject("toast");
                        if (toastObj.has("message") && !toastObj.get("message").isJsonNull()) {
                            String msg = toastObj.get("message").getAsString();
                            if (!msg.trim().isEmpty()) {
                                return msg;
                            }
                        }
                    }

                    // 3. Проверяем валидационные ошибки по полям {"data":{"sourceable_id":["Пользователь уже добавлен в игнор-лист"]}} или {"errors":{...}}
                    List<String> fieldErrors = new ArrayList<>();
                    if (errObj.has("data") && errObj.get("data").isJsonObject()) {
                        extractFieldErrorsFromObject(errObj.getAsJsonObject("data"), fieldErrors);
                    }
                    if (errObj.has("errors") && errObj.get("errors").isJsonObject()) {
                        extractFieldErrorsFromObject(errObj.getAsJsonObject("errors"), fieldErrors);
                    }
                    if (!fieldErrors.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < fieldErrors.size(); i++) {
                            if (i > 0) sb.append("\n");
                            sb.append(fieldErrors.get(i));
                        }
                        return sb.toString();
                    }

                    // 4. Проверяем message и errors
                    String serverMsg = "";
                    if (errObj.has("message") && !errObj.get("message").isJsonNull()) {
                        serverMsg = errObj.get("message").getAsString();
                    }
                    if (errObj.has("errors") && !errObj.get("errors").isJsonNull()) {
                        String errorsStr = errObj.get("errors").toString();
                        if (!serverMsg.contains(errorsStr)) {
                            serverMsg += (serverMsg.isEmpty() ? "" : " | ") + errorsStr;
                        }
                    }
                    if (!serverMsg.isEmpty()) {
                        return serverMsg;
                    }
                }
            } catch (Exception ignored) {
                return trimmed;
            }
        }
        return "Ошибка (" + responseCode + ")";
    }

    private void extractFieldErrorsFromObject(com.google.gson.JsonObject container, List<String> resultList) {
        if (container == null) return;
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : container.entrySet()) {
            if ("toast".equals(entry.getKey())) continue;
            com.google.gson.JsonElement val = entry.getValue();
            if (val != null && val.isJsonArray()) {
                com.google.gson.JsonArray arr = val.getAsJsonArray();
                for (com.google.gson.JsonElement item : arr) {
                    if (item != null && item.isJsonPrimitive()) {
                        String s = item.getAsString();
                        if (s != null && !s.trim().isEmpty() && !resultList.contains(s.trim())) {
                            resultList.add(s.trim());
                        }
                    }
                }
            } else if (val != null && val.isJsonPrimitive()) {
                String s = val.getAsString();
                if (s != null && !s.trim().isEmpty() && !resultList.contains(s.trim())) {
                    resultList.add(s.trim());
                }
            }
        }
    }

    public void getRulesArticle(ArticleCallback callback) {
        executor.execute(() -> {
            try {
                String url = "https://hapi.hentaicdn.org/api/faq/articles/37";
                Request request = buildApiRequest(url).get().build();
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        mainHandler.post(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            if (!response.isSuccessful()) {
                                int code = response.code();
                                mainHandler.post(() -> callback.onError("Ошибка сервера: " + code));
                                return;
                            }
                            String json = response.body() != null ? response.body().string() : "";
                            ArticleResponse articleResponse = gson.fromJson(json, ArticleResponse.class);
                            if (articleResponse != null && articleResponse.getPost() != null) {
                                mainHandler.post(() -> callback.onArticleReceived(articleResponse));
                            } else {
                                mainHandler.post(() -> callback.onError("Неверный формат ответа правил"));
                            }
                        } catch (Exception e) {
                            Log.e("ApiService", "Error parsing rules article", e);
                            mainHandler.post(() -> callback.onError("Ошибка обработки: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("ApiService", "Error making rules article request", e);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onError("Ошибка запроса: " + e.getMessage()));
            }
        });
    }

    public OkHttpClient getOkHttpClient() {
        return httpClient;
    }

    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                // Ждем завершения текущих задач максимум 2 секунды
                if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.w("ApiService", "Executor did not terminate gracefully, forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.e("ApiService", "Interrupted while waiting for executor termination", e);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }
}
