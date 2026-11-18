package com.example.animelib.api;

import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.ArticleResponse;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.models.RelatedTitlesResponse;
import com.example.animelib.models.SearchResponse;
import com.example.animelib.models.ToastData;
import com.example.animelib.ui.VideoUrlHelper;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Доменный API-сервис для каталога аниме, эпизодов, потоков Kodik, связанных тайтлов,
 * уведомлений зеркала (toast) и правил.
 */
public class AnimeCatalogApi {
    private static final String TAG = "AnimeCatalogApi";

    private static final long ANIME_INFO_TTL_MS = 5 * 60 * 1000L;
    private static final long EPISODES_LIST_TTL_MS = 3 * 60 * 1000L;
    private static final long EPISODE_DATA_TTL_MS = 5 * 60 * 1000L;
    private static final long KODIK_CACHE_TTL_MS = 8 * 60 * 1000L;
    private static final long RELATED_TITLES_TTL_MS = 10 * 60 * 1000L;
    private static final long SEARCH_CACHE_TTL_MS = 2 * 60 * 1000L;

    private static final String ANIME_INFO_FIELDS =
            "?fields[]=background&fields[]=eng_name&fields[]=otherNames&fields[]=summary" +
            "&fields[]=releaseDate&fields[]=type_id&fields[]=caution&fields[]=views" +
            "&fields[]=close_view&fields[]=rate_avg&fields[]=rate&fields[]=genres" +
            "&fields[]=tags&fields[]=teams&fields[]=authors&fields[]=publisher" +
            "&fields[]=userRating&fields[]=anime_status_id&fields[]=episodes" +
            "&fields[]=episodes_count&fields[]=shiki_rate";

    private final ApiClientCore core;

    public AnimeCatalogApi(ApiClientCore core) {
        this.core = core;
    }

    public void fetchAnimeInfo(String animeSlugOrId, ApiService.AnimeInfoCallback callback) {
        String cleaned = ApiClientCore.cleanUrlSegment(animeSlugOrId);
        if (cleaned.isEmpty()) {
            if (callback != null) callback.onError("Пустой slug или ID");
            return;
        }

        final String cacheKey = cleaned.toLowerCase(Locale.ROOT);
        AnimeInfoResponse cached = ApiCacheManager.ANIME_INFO.getValid(cacheKey, ANIME_INFO_TTL_MS);
        if (cached == null && cacheKey.contains("--")) {
            String numericId = ApiClientCore.extractAnimeId(cacheKey);
            if (numericId != null && !numericId.isEmpty()) {
                cached = ApiCacheManager.ANIME_INFO.getValid(numericId, ANIME_INFO_TTL_MS);
            }
        }
        if (cached != null) {
            if (callback != null) callback.onAnimeInfoReceived(cached);
            return;
        }

        String apiUrl = ApiClientCore.API_BASE_HAPI + "/anime/" + cacheKey + ANIME_INFO_FIELDS;
        core.executeCachedGet(
                ApiCacheManager.ANIME_INFO,
                cacheKey,
                cacheKey,
                apiUrl,
                true,
                false,
                new ApiCacheManager.CallbackNotifier<AnimeInfoResponse>() {
                    @Override
                    public void onSuccess(AnimeInfoResponse data) {
                        if (callback != null) callback.onAnimeInfoReceived(data);
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) callback.onError(error);
                    }
                },
                "Ошибка сети: ",
                "HTTP ",
                "Ошибка парсинга ответа",
                body -> {
                    AnimeInfoResponse info = core.getGson().fromJson(body, AnimeInfoResponse.class);
                    if (info != null && info.getData() != null) {
                        ApiCacheManager.ANIME_INFO.put(cacheKey, info);
                        int id = info.getData().getId();
                        if (id > 0) {
                            ApiCacheManager.ANIME_INFO.put(String.valueOf(id), info);
                        }
                        String slugUrl = info.getData().getSlug_url();
                        if (slugUrl != null && !slugUrl.trim().isEmpty()) {
                            ApiCacheManager.ANIME_INFO.put(slugUrl.trim().toLowerCase(Locale.ROOT), info);
                        }
                        String extractedId = ApiClientCore.extractAnimeId(cacheKey);
                        if (extractedId != null && !extractedId.isEmpty()) {
                            ApiCacheManager.ANIME_INFO.put(extractedId, info);
                        }
                    }
                    return info;
                }
        );
    }

    public ApiService.AnimeInfoSyncResult fetchAnimeInfoSync(String animeSlugOrId) {
        String cleaned = ApiClientCore.cleanUrlSegment(animeSlugOrId);
        if (cleaned.isEmpty()) {
            return new ApiService.AnimeInfoSyncResult(false, false, 0, null, "Пустой slug или ID");
        }

        final String cacheKey = cleaned.toLowerCase(Locale.ROOT);
        AnimeInfoResponse cached = ApiCacheManager.ANIME_INFO.getValid(cacheKey, ANIME_INFO_TTL_MS);
        if (cached == null && cacheKey.contains("--")) {
            String numericId = ApiClientCore.extractAnimeId(cacheKey);
            if (numericId != null && !numericId.isEmpty()) {
                cached = ApiCacheManager.ANIME_INFO.getValid(numericId, ANIME_INFO_TTL_MS);
            }
        }
        if (cached != null && cached.getData() != null) {
            return new ApiService.AnimeInfoSyncResult(true, false, 200, cached, null);
        }

        String apiUrl = ApiClientCore.API_BASE_HAPI + "/anime/" + cacheKey + ANIME_INFO_FIELDS;
        try {
            Request request = core.buildApiRequest(apiUrl, true).build();
            try (Response response = core.getHttpClient().newCall(request).execute()) {
                int code = response.code();
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    AnimeInfoResponse info = core.getGson().fromJson(body, AnimeInfoResponse.class);
                    if (info != null && info.getData() != null) {
                        ApiCacheManager.ANIME_INFO.put(cacheKey, info);
                        int id = info.getData().getId();
                        if (id > 0) {
                            ApiCacheManager.ANIME_INFO.put(String.valueOf(id), info);
                        }
                        String slugUrl = info.getData().getSlug_url();
                        if (slugUrl != null && !slugUrl.trim().isEmpty()) {
                            ApiCacheManager.ANIME_INFO.put(slugUrl.trim().toLowerCase(Locale.ROOT), info);
                        }
                        String extractedId = ApiClientCore.extractAnimeId(cacheKey);
                        if (extractedId != null && !extractedId.isEmpty()) {
                            ApiCacheManager.ANIME_INFO.put(extractedId, info);
                        }
                        return new ApiService.AnimeInfoSyncResult(true, false, code, info, null);
                    }
                    return new ApiService.AnimeInfoSyncResult(false, false, code, null, "Пустой ответ anime info");
                }
                boolean retryable = (code == 408 || code == 429 || code >= 500);
                return new ApiService.AnimeInfoSyncResult(false, retryable, code, null, "HTTP " + code);
            }
        } catch (IOException e) {
            Log.e(TAG, "fetchAnimeInfoSync network failure", e);
            return new ApiService.AnimeInfoSyncResult(false, true, 0, null, "Ошибка сети: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "fetchAnimeInfoSync unexpected failure", e);
            return new ApiService.AnimeInfoSyncResult(false, false, 0, null, "Ошибка: " + e.getMessage());
        }
    }

    public void searchAnime(String query, ApiService.SearchCallback callback) {
        if (query == null || query.trim().isEmpty()) {
            if (callback != null) callback.onError("Пустой поисковый запрос");
            return;
        }

        final String trimmedQuery = query.trim();
        final String cacheKey = trimmedQuery.toLowerCase(Locale.ROOT);

        SearchResponse cached = ApiCacheManager.SEARCH.getValid(cacheKey, SEARCH_CACHE_TTL_MS);
        if (cached != null) {
            if (callback != null) callback.onSearchResults(cached);
            return;
        }

        final String encodedUrl;
        try {
            encodedUrl = ApiClientCore.API_BASE_HAPI + "/anime?fields[]=rate_avg&fields[]=rate&fields[]=releaseDate&q="
                    + URLEncoder.encode(trimmedQuery, "UTF-8");
        } catch (Exception e) {
            if (callback != null) callback.onError("Ошибка кодирования запроса: " + e.getMessage());
            return;
        }

        core.executeCachedGet(
                ApiCacheManager.SEARCH,
                cacheKey,
                cacheKey,
                encodedUrl,
                false,
                false,
                new ApiCacheManager.CallbackNotifier<SearchResponse>() {
                    @Override
                    public void onSuccess(SearchResponse data) {
                        if (callback != null) callback.onSearchResults(data);
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) callback.onError(error);
                    }
                },
                "Ошибка сети: ",
                "HTTP ",
                "Неверный формат поискового ответа",
                body -> {
                    SearchResponse searchResponse = core.getGson().fromJson(body, SearchResponse.class);
                    if (searchResponse != null) {
                        ApiCacheManager.SEARCH.put(cacheKey, searchResponse);
                    }
                    return searchResponse;
                }
        );
    }

    public ApiService.SearchSyncResult searchAnimeSync(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ApiService.SearchSyncResult(false, false, 0, null, "Пустой поисковый запрос");
        }
        try {
            String encodedQuery = URLEncoder.encode(query.trim(), "UTF-8");
            String apiUrl = ApiClientCore.API_BASE_HAPI + "/anime?fields[]=rate_avg&fields[]=rate&fields[]=releaseDate&q=" + encodedQuery;
            Request request = core.buildSearchApiRequest(apiUrl).build();

            try (Response response = core.getHttpClient().newCall(request).execute()) {
                int code = response.code();
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    SearchResponse searchResponse = core.getGson().fromJson(body, SearchResponse.class);
                    return new ApiService.SearchSyncResult(true, false, code, searchResponse, null);
                } else if (code == 429) {
                    return new ApiService.SearchSyncResult(false, true, 429, null, "Слишком много запросов (429 Rate Limit)");
                } else {
                    return new ApiService.SearchSyncResult(false, false, code, null, "HTTP " + code);
                }
            }
        } catch (IOException e) {
            return new ApiService.SearchSyncResult(false, false, 0, null, "Ошибка сети: " + e.getMessage());
        } catch (Exception e) {
            return new ApiService.SearchSyncResult(false, false, 0, null, "Ошибка: " + e.getMessage());
        }
    }

    public void fetchEpisodesList(String animeId, ApiService.EpisodesCallback callback) {
        if (animeId == null || animeId.trim().isEmpty()) {
            if (callback != null) callback.onError("Пустой animeId");
            return;
        }

        final String cacheKey = animeId.trim();
        EpisodesListResponse cached = ApiCacheManager.EPISODES_LIST.getValid(cacheKey, EPISODES_LIST_TTL_MS);
        if (cached != null) {
            if (callback != null) callback.onEpisodesReceived(cached);
            return;
        }

        String apiUrl = ApiClientCore.API_BASE_HAPI + "/episodes?anime_id=" + cacheKey;
        core.executeCachedGet(
                ApiCacheManager.EPISODES_LIST,
                cacheKey,
                cacheKey,
                apiUrl,
                true,
                false,
                new ApiCacheManager.CallbackNotifier<EpisodesListResponse>() {
                    @Override
                    public void onSuccess(EpisodesListResponse data) {
                        if (callback != null) callback.onEpisodesReceived(data);
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) callback.onError(error);
                    }
                },
                "Ошибка загрузки эпизодов: ",
                "HTTP ошибка: ",
                "Неверный формат ответа эпизодов",
                body -> {
                    EpisodesListResponse episodesResponse = core.getGson().fromJson(body, EpisodesListResponse.class);
                    if (episodesResponse != null && episodesResponse.getData() != null) {
                        ApiCacheManager.EPISODES_LIST.put(cacheKey, episodesResponse);
                        return episodesResponse;
                    }
                    return null;
                }
        );
    }

    public EpisodesListResponse fetchEpisodesListSync(String animeId) {
        if (animeId == null || animeId.trim().isEmpty()) {
            return null;
        }
        final String cacheKey = animeId.trim();
        EpisodesListResponse cached = ApiCacheManager.EPISODES_LIST.getValid(cacheKey, EPISODES_LIST_TTL_MS);
        if (cached != null) {
            return cached;
        }
        String apiUrl = ApiClientCore.API_BASE_HAPI + "/episodes?anime_id=" + cacheKey;
        try {
            Request request = core.buildApiRequest(apiUrl, true).build();
            try (Response response = core.getHttpClient().newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    EpisodesListResponse episodesResponse = core.getGson().fromJson(body, EpisodesListResponse.class);
                    if (episodesResponse != null && episodesResponse.getData() != null) {
                        ApiCacheManager.EPISODES_LIST.put(cacheKey, episodesResponse);
                        return episodesResponse;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchEpisodesListSync failed for " + cacheKey + ": " + e.getMessage());
        }
        return null;
    }

    public void fetchEpisodeData(int episodeId, ApiService.EpisodeDataCallback callback) {
        if (episodeId <= 0) {
            if (callback != null) callback.onError("Неверный ID эпизода");
            return;
        }

        EpisodeResponse cached = ApiCacheManager.EPISODE_DATA.getValid(episodeId, EPISODE_DATA_TTL_MS);
        if (cached != null) {
            if (callback != null) callback.onEpisodeDataReceived(cached);
            return;
        }

        String apiUrl = ApiClientCore.API_BASE_HAPI + "/episodes/" + episodeId;
        core.executeCachedGet(
                ApiCacheManager.EPISODE_DATA,
                episodeId,
                episodeId,
                apiUrl,
                true,
                false,
                new ApiCacheManager.CallbackNotifier<EpisodeResponse>() {
                    @Override
                    public void onSuccess(EpisodeResponse data) {
                        if (callback != null) callback.onEpisodeDataReceived(data);
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) callback.onError(error);
                    }
                },
                "Ошибка загрузки данных эпизода: ",
                "HTTP ошибка: ",
                "Неверный формат ответа эпизода",
                body -> {
                    EpisodeResponse episodeResponse = core.getGson().fromJson(body, EpisodeResponse.class);
                    if (episodeResponse != null && episodeResponse.getData() != null) {
                        ApiCacheManager.EPISODE_DATA.put(episodeId, episodeResponse);
                        return episodeResponse;
                    }
                    return null;
                }
        );
    }

    public EpisodeResponse fetchEpisodeDataSync(int episodeId) {
        if (episodeId <= 0) {
            return null;
        }
        EpisodeResponse cached = ApiCacheManager.EPISODE_DATA.getValid(episodeId, EPISODE_DATA_TTL_MS);
        if (cached != null) {
            return cached;
        }
        String apiUrl = ApiClientCore.API_BASE_HAPI + "/episodes/" + episodeId;
        try {
            Request request = core.buildApiRequest(apiUrl, true).build();
            try (Response response = core.getHttpClient().newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    EpisodeResponse episodeResponse = core.getGson().fromJson(body, EpisodeResponse.class);
                    if (episodeResponse != null && episodeResponse.getData() != null) {
                        ApiCacheManager.EPISODE_DATA.put(episodeId, episodeResponse);
                        return episodeResponse;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchEpisodeDataSync failed for " + episodeId + ": " + e.getMessage());
        }
        return null;
    }

    public void loadAnimeFromUrl(String animeUrl, ApiService.EpisodeDataCallback callback) {
        String animeId = ApiClientCore.extractAnimeId(animeUrl);
        if (animeId == null) {
            if (callback != null) callback.onError("Не удалось извлечь ID аниме из URL");
            return;
        }

        fetchEpisodesList(animeId, new ApiService.EpisodesCallback() {
            @Override
            public void onEpisodesReceived(EpisodesListResponse response) {
                if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                    int episodeId = response.getData().get(0).getId();
                    fetchEpisodeData(episodeId, callback);
                } else {
                    if (callback != null) callback.onError("Эпизоды не найдены");
                }
            }

            @Override
            public void onError(String error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    public void invalidateKodikCache(String kodikSrc) {
        if (kodikSrc == null || kodikSrc.trim().isEmpty()) return;
        String cacheKey = KodikLinksExtractor.normalizeCacheKey(kodikSrc);
        ApiCacheManager.KODIK_LINKS.remove(cacheKey);
        ApiCacheManager.KODIK_LINKS.remove(kodikSrc.trim());
    }

    public void fetchKodikVideoLinks(String kodikSrc, boolean forceRefresh, ApiService.KodikVideoCallback callback) {
        if (kodikSrc == null || kodikSrc.trim().isEmpty()) {
            if (callback != null) callback.onError("Пустая ссылка Kodik");
            return;
        }

        final String cacheKey = KodikLinksExtractor.normalizeCacheKey(kodikSrc);

        if (forceRefresh) {
            ApiCacheManager.KODIK_LINKS.remove(cacheKey);
        } else {
            KodikResponse cached = ApiCacheManager.KODIK_LINKS.getValid(cacheKey, KODIK_CACHE_TTL_MS);
            if (cached != null) {
                if (callback != null) callback.onKodikVideoReceived(cached);
                return;
            }
        }

        final String pendingKey = forceRefresh ? (cacheKey + "#refresh") : cacheKey;
        boolean shouldStart = ApiCacheManager.KODIK_LINKS.registerOrAttach(pendingKey, new ApiCacheManager.CallbackNotifier<KodikResponse>() {
            @Override
            public void onSuccess(KodikResponse data) {
                if (callback != null) callback.onKodikVideoReceived(data);
            }

            @Override
            public void onError(String error) {
                if (callback != null) callback.onError(error);
            }
        });
        if (!shouldStart) return;

        Runnable task = () -> {
            try {
                KodikResponse kodikResponse = core.getKodikLinksExtractor().getLinks(cacheKey, forceRefresh);
                if (kodikResponse != null && kodikResponse.isSuccess()
                        && kodikResponse.getData() != null && !kodikResponse.getData().isEmpty()) {
                    ApiCacheManager.KODIK_LINKS.put(cacheKey, kodikResponse);
                    ApiCacheManager.KODIK_LINKS.dispatchSuccess(pendingKey, kodikResponse);
                } else {
                    ApiCacheManager.KODIK_LINKS.dispatchError(pendingKey, "Kodik не вернул доступных качеств");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in Kodik links extraction", e);
                ApiCacheManager.KODIK_LINKS.dispatchError(pendingKey, "Ошибка получения ссылок Kodik: " + e.getMessage());
            }
        };

        try {
            ExecutorService kodikExec = core.getKodikExecutor();
            if (kodikExec != null && !kodikExec.isShutdown()) {
                kodikExec.execute(task);
            } else {
                core.safeExecute(task);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule Kodik extraction", e);
            ApiCacheManager.KODIK_LINKS.dispatchError(pendingKey, "Ошибка планировщика Kodik: " + e.getMessage());
        }
    }

    public void getRelatedTitles(String animeSlug, ApiService.RelatedTitlesCallback callback) {
        String cleaned = ApiClientCore.cleanUrlSegment(animeSlug);
        if (cleaned.isEmpty()) {
            if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Пустой slug"));
            return;
        }

        final String cacheKey = cleaned.toLowerCase(Locale.ROOT);
        RelatedTitlesResponse cached = ApiCacheManager.RELATED_TITLES.getValid(cacheKey, RELATED_TITLES_TTL_MS);
        if (cached == null && cacheKey.contains("--")) {
            String numericId = ApiClientCore.extractAnimeId(cacheKey);
            if (numericId != null && !numericId.isEmpty()) {
                cached = ApiCacheManager.RELATED_TITLES.getValid(numericId, RELATED_TITLES_TTL_MS);
            }
        }
        if (cached != null) {
            final RelatedTitlesResponse finalCached = cached;
            if (callback != null) core.safeRunOnUiThread(() -> callback.onRelatedTitlesReceived(finalCached));
            return;
        }

        String url = ApiClientCore.API_BASE_HAPI + "/anime/" + cacheKey + "/relations";
        core.executeCachedGet(
                ApiCacheManager.RELATED_TITLES,
                cacheKey,
                cacheKey,
                url,
                true,
                false,
                new ApiCacheManager.CallbackNotifier<RelatedTitlesResponse>() {
                    @Override
                    public void onSuccess(RelatedTitlesResponse data) {
                        if (callback != null) core.safeRunOnUiThread(() -> callback.onRelatedTitlesReceived(data));
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) core.safeRunOnUiThread(() -> callback.onError(error));
                    }
                },
                "Network error: ",
                "Server error: ",
                "Parse error: empty response",
                body -> {
                    RelatedTitlesResponse relatedTitlesResponse = core.getGson().fromJson(body, RelatedTitlesResponse.class);
                    if (relatedTitlesResponse != null) {
                        ApiCacheManager.RELATED_TITLES.put(cacheKey, relatedTitlesResponse);
                        String extractedId = ApiClientCore.extractAnimeId(cacheKey);
                        if (extractedId != null && !extractedId.isEmpty()) {
                            ApiCacheManager.RELATED_TITLES.put(extractedId, relatedTitlesResponse);
                        }
                    }
                    return relatedTitlesResponse;
                }
        );
    }

    public boolean markEpisodeViewedSync(String animeId, int playerId) {
        if (animeId == null || animeId.isEmpty() || playerId <= 0) {
            return false;
        }
        String cleanAnimeId = VideoUrlHelper.extractAnimeId(animeId);
        if (cleanAnimeId == null || cleanAnimeId.isEmpty()) {
            cleanAnimeId = animeId;
        }
        String url = ApiClientCore.API_BASE_HAPI + "/anime/" + cleanAnimeId + "/players/" + playerId + "/view";
        try {
            RequestBody emptyBody = RequestBody.create("", ApiClientCore.JSON_MEDIA_TYPE);
            Request request = core.buildApiRequest(url).post(emptyBody).build();
            try (Response response = core.getHttpClient().newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in markEpisodeViewedSync for " + url, e);
            return false;
        }
    }

    public void markEpisodeViewed(String animeId, int playerId, ApiService.ViewCallback callback) {
        core.safeExecute(() -> {
            boolean success = markEpisodeViewedSync(animeId, playerId);
            if (callback != null) {
                if (success) {
                    core.safeRunOnUiThread(callback::onSuccess);
                } else {
                    core.safeRunOnUiThread(() -> callback.onError("Failed to mark episode as viewed"));
                }
            }
        });
    }

    public void checkApiForToast(ApiService.ToastCheckCallback callback) {
        core.safeExecute(() -> {
            String apiUrl = ApiClientCore.API_BASE_HAPI + "/";
            Request request = core.buildApiRequest(apiUrl).build();

            core.getHttpClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "Toast API request failed", e);
                    if (callback != null) callback.onError("Ошибка сети - " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (response) {
                        if (!response.isSuccessful() || response.body() == null) {
                            if (callback != null) callback.onError("HTTP " + response.code());
                            return;
                        }
                        String responseBody = response.body().string().trim();
                        if (!responseBody.startsWith("{") || !responseBody.endsWith("}")) {
                            if (callback != null) callback.onError("Неверный формат ответа API");
                            return;
                        }
                        ApiResponse apiResponse = core.getGson().fromJson(responseBody, ApiResponse.class);
                        if (apiResponse != null && apiResponse.getData() != null
                                && apiResponse.getData().getToast() != null
                                && apiResponse.getData().getToast().getButtons() != null
                                && !apiResponse.getData().getToast().getButtons().isEmpty()) {
                            ToastData.ButtonData button = apiResponse.getData().getToast().getButtons().get(0);
                            String message = button.getText();
                            if (message != null && message.contains("Перейти на зеркало")) {
                                String newUrl = button.getHref();
                                if (newUrl != null && !newUrl.isEmpty()) {
                                    if (callback != null) callback.onToastReceived(message, newUrl);
                                } else {
                                    if (callback != null) callback.onError("URL зеркала не найден");
                                }
                            } else if (message != null) {
                                if (callback != null) callback.onToastReceived(message, null);
                            } else {
                                if (callback != null) callback.onError("Пустое сообщение");
                            }
                        } else {
                            if (callback != null) callback.onError("Нет активных уведомлений");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing toast API response", e);
                        if (callback != null) callback.onError("Ошибка обработки - " + e.getMessage());
                    }
                }
            });
        });
    }

    public void getRulesArticle(ApiService.ArticleCallback callback) {
        core.safeExecute(() -> {
            try {
                String url = ApiClientCore.API_BASE_HAPI + "/faq/articles/37";
                Request request = core.buildApiRequest(url).get().build();

                core.getHttpClient().newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка сети: " + e.getMessage()));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            if (!response.isSuccessful()) {
                                int code = response.code();
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка сервера: " + code));
                                return;
                            }
                            String json = response.body() != null ? response.body().string() : "";
                            ArticleResponse articleResponse = core.getGson().fromJson(json, ArticleResponse.class);
                            if (articleResponse != null && articleResponse.getPost() != null) {
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onArticleReceived(articleResponse));
                            } else {
                                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Неверный формат ответа правил"));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing rules article", e);
                            if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка обработки: " + e.getMessage()));
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error making rules article request", e);
                if (callback != null) core.safeRunOnUiThread(() -> callback.onError("Ошибка запроса: " + e.getMessage()));
            }
        });
    }
}
