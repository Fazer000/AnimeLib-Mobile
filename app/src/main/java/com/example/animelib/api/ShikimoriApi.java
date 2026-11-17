package com.example.animelib.api;

import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.models.FranchiseResponse;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Доменный API-сервис для взаимодействия с Shikimori (франшизы, хронология, детали аниме).
 */
public class ShikimoriApi {
    private static final String TAG = "ShikimoriApi";
    private static final String SHIKIMORI_BASE_URL = "https://shikimori.io/api/animes/";
    private static final String SHIKIMORI_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private final ApiClientCore core;

    public ShikimoriApi(ApiClientCore core) {
        this.core = core;
    }

    public static FranchiseResponse getCachedFranchise(long shikimoriId) {
        if (shikimoriId <= 0) return null;
        return ApiCacheManager.FRANCHISE.get(shikimoriId);
    }

    public static void cacheFranchise(long shikimoriId, FranchiseResponse response) {
        if (shikimoriId > 0 && response != null && response.getNodes() != null && !response.getNodes().isEmpty()) {
            ApiCacheManager.FRANCHISE.put(shikimoriId, response);
        }
    }

    private static Request buildShikimoriRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", SHIKIMORI_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", "https://shikimori.io/")
                .get()
                .build();
    }

    public void getFranchise(long shikimoriId, ApiService.FranchiseCallback callback) {
        if (shikimoriId <= 0) {
            if (callback != null) callback.onError("Invalid Shikimori ID");
            return;
        }

        FranchiseResponse cached = getCachedFranchise(shikimoriId);
        if (cached != null && cached.getNodes() != null && !cached.getNodes().isEmpty()) {
            if (callback != null) callback.onFranchiseReceived(cached);
            return;
        }

        core.safeExecute(() -> {
            try {
                String[] endpoints = new String[]{
                        SHIKIMORI_BASE_URL + shikimoriId + "/franchise"
                };
                fetchFranchiseWithFallback(endpoints, 0, shikimoriId, callback);
            } catch (Exception e) {
                Log.w(TAG, "Error in getFranchise: " + e.getMessage());
                if (callback != null) callback.onError("Request error: " + e.getMessage());
            }
        });
    }

    private void fetchFranchiseWithFallback(String[] endpoints, int index, long shikimoriId,
                                            ApiService.FranchiseCallback callback) {
        if (index >= endpoints.length) {
            if (callback != null) callback.onError("Сервер Shikimori недоступен");
            return;
        }

        String url = endpoints[index];
        Request request = buildShikimoriRequest(url);

        core.getShikimoriHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Franchise fetch failed for " + url + ": " + e.getMessage());
                fetchFranchiseWithFallback(endpoints, index + 1, shikimoriId, callback);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        FranchiseResponse franchiseResponse = core.getGson().fromJson(responseBody, FranchiseResponse.class);
                        if (franchiseResponse != null && franchiseResponse.getNodes() != null && !franchiseResponse.getNodes().isEmpty()) {
                            cacheFranchise(shikimoriId, franchiseResponse);
                            if (callback != null) {
                                callback.onFranchiseReceived(franchiseResponse);
                            }
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error parsing franchise response from: " + url + " -> " + e.getMessage());
                }
                fetchFranchiseWithFallback(endpoints, index + 1, shikimoriId, callback);
            }
        });
    }

    public void fetchShikimoriAnimeDetails(long animeId, ApiService.ShikimoriAnimeDetailsCallback callback) {
        if (animeId <= 0) {
            if (callback != null) callback.onError("Invalid Shikimori ID");
            return;
        }

        core.safeExecute(() -> {
            try {
                String[] endpoints = new String[]{
                        SHIKIMORI_BASE_URL + animeId
                };
                fetchShikimoriAnimeDetailsWithFallback(endpoints, 0, animeId, callback);
            } catch (Exception e) {
                Log.w(TAG, "Error in fetchShikimoriAnimeDetails: " + e.getMessage());
                if (callback != null) callback.onError("Request error: " + e.getMessage());
            }
        });
    }

    private void fetchShikimoriAnimeDetailsWithFallback(String[] endpoints, int index, long animeId,
                                                        ApiService.ShikimoriAnimeDetailsCallback callback) {
        if (index >= endpoints.length) {
            if (callback != null) callback.onError("Сервер Shikimori недоступен");
            return;
        }

        String url = endpoints[index];
        Request request = buildShikimoriRequest(url);

        core.getShikimoriHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Shikimori anime details failed for " + url + ": " + e.getMessage());
                fetchShikimoriAnimeDetailsWithFallback(endpoints, index + 1, animeId, callback);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        JSONObject json = new JSONObject(responseBody);
                        String name = json.optString("name", null);
                        String russian = json.optString("russian", null);
                        if (callback != null) {
                            callback.onSuccess(name, russian);
                        }
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error parsing Shikimori details JSON from: " + url + " -> " + e.getMessage());
                }
                fetchShikimoriAnimeDetailsWithFallback(endpoints, index + 1, animeId, callback);
            }
        });
    }
}
