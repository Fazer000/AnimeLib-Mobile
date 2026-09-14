package com.example.animelib.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.models.UpdateInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateManager {
    private static final String TAG = "UpdateManager";
    public static final String GITHUB_RELEASES_API = "https://api.github.com/repos/Fazer000/AnimeLib-Mobile/releases/latest";

    public interface CheckUpdateCallback {
        void onUpdateCheckResult(boolean hasUpdate, UpdateInfo updateInfo, String currentVersion);
        void onError(String errorMessage);
    }

    public static String getCurrentVersion(Context context) {
        if (context != null) {
            try {
                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                if (pInfo.versionName != null && !pInfo.versionName.isEmpty()) {
                    return pInfo.versionName;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading package info", e);
            }
        }
        return "1.8.7";
    }

    public static void checkForUpdates(Context context, OkHttpClient client, CheckUpdateCallback callback) {
        String currentVersion = getCurrentVersion(context);

        OkHttpClient httpClient = client != null ? client : new OkHttpClient();
        Request request = new Request.Builder()
                .url(GITHUB_RELEASES_API)
                .header("User-Agent", "AnimeLib-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Error fetching releases", e);
                if (callback != null) {
                    callback.onError("Ошибка сети при проверке обновлений");
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    if (callback != null) {
                        if (response.code() == 404) {
                            callback.onUpdateCheckResult(false, null, currentVersion);
                        } else {
                            callback.onError(" Ошибка GitHub API (HTTP " + response.code() + ")");
                        }
                    }
                    return;
                }

                try {
                    String jsonStr = response.body() != null ? response.body().string() : null;
                    if (jsonStr == null || jsonStr.isEmpty()) {
                        if (callback != null) callback.onError("Пустой ответ от сервера");
                        return;
                    }

                    JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
                    String tagName = json.has("tag_name") && !json.get("tag_name").isJsonNull() ? json.get("tag_name").getAsString() : "";
                    String releaseName = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : tagName;
                    String body = json.has("body") && !json.get("body").isJsonNull() ? json.get("body").getAsString() : "Описание изменений отсутствует.";
                    String publishedAt = json.has("published_at") && !json.get("published_at").isJsonNull() ? json.get("published_at").getAsString() : "";
                    String htmlUrl = json.has("html_url") && !json.get("html_url").isJsonNull() ? json.get("html_url").getAsString() : "https://github.com/Fazer000/AnimeLib-Mobile";

                    String apkUrl = htmlUrl;
                    long apkSize = 0;

                    if (json.has("assets") && json.get("assets").isJsonArray()) {
                        JsonArray assets = json.getAsJsonArray("assets");
                        JsonObject bestAsset = null;
                        int bestPriority = 0; // 0: none, 1: any .apk, 2: release .apk, 3: app-release.apk

                        for (JsonElement assetElem : assets) {
                            if (assetElem.isJsonObject()) {
                                JsonObject asset = assetElem.getAsJsonObject();
                                String name = asset.has("name") && !asset.get("name").isJsonNull() ? asset.get("name").getAsString() : "";
                                String downloadUrl = asset.has("browser_download_url") && !asset.get("browser_download_url").isJsonNull() ? asset.get("browser_download_url").getAsString() : "";

                                if (downloadUrl.isEmpty()) continue;

                                String lowerName = name.toLowerCase();
                                if (lowerName.equals("app-release.apk") || downloadUrl.toLowerCase().endsWith("/app-release.apk")) {
                                    bestAsset = asset;
                                    bestPriority = 3;
                                    break;
                                } else if (lowerName.contains("release") && lowerName.endsWith(".apk") && bestPriority < 2) {
                                    bestAsset = asset;
                                    bestPriority = 2;
                                } else if (lowerName.endsWith(".apk") && bestPriority < 1) {
                                    bestAsset = asset;
                                    bestPriority = 1;
                                }
                            }
                        }

                        if (bestAsset != null) {
                            apkUrl = bestAsset.has("browser_download_url") && !bestAsset.get("browser_download_url").isJsonNull() ? bestAsset.get("browser_download_url").getAsString() : htmlUrl;
                            apkSize = bestAsset.has("size") && !bestAsset.get("size").isJsonNull() ? bestAsset.get("size").getAsLong() : 0;
                        }
                    }

                    UpdateInfo updateInfo = new UpdateInfo(tagName, releaseName, body, publishedAt, htmlUrl, apkUrl, apkSize);
                    boolean hasUpdate = isUpdateAvailable(currentVersion, tagName);

                    if (callback != null) {
                        callback.onUpdateCheckResult(hasUpdate, updateInfo, currentVersion);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse release response", e);
                    if (callback != null) {
                        callback.onError(" Ошибка чтения данных обновления");
                    }
                }
            }
        });
    }

    public static boolean isUpdateAvailable(String currentVersion, String latestTag) {
        if (latestTag == null || latestTag.trim().isEmpty()) return false;

        String cleanCurrent = cleanVersion(currentVersion);
        String cleanLatest = cleanVersion(latestTag);

        String[] currentParts = cleanCurrent.split("\\.");
        String[] latestParts = cleanLatest.split("\\.");

        int maxLength = Math.max(currentParts.length, latestParts.length);
        for (int i = 0; i < maxLength; i++) {
            int cPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
            int lPart = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;

            if (lPart > cPart) {
                return true;
            } else if (lPart < cPart) {
                return false;
            }
        }
        return false;
    }

    public static String cleanVersion(String version) {
        if (version == null) return "0.0.0";
        String clean = version.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }
        return clean;
    }

    private static int parseVersionPart(String part) {
        try {
            String num = part.replaceAll("[^0-9].*", "");
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (Exception e) {
            return 0;
        }
    }
}
