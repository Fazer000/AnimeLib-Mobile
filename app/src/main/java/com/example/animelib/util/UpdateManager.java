package com.example.animelib.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.models.UpdateInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateManager {
    private static final String TAG = "UpdateManager";
    public static final String PREF_BETA_UPDATES = "pref_beta_updates";
    public static final String GITHUB_RELEASES_LIST_API = "https://api.github.com/repos/Fazer000/AnimeLib-Mobile/releases?per_page=15";
    public static final String GITHUB_RELEASES_LATEST_API = "https://api.github.com/repos/Fazer000/AnimeLib-Mobile/releases/latest";

    private static volatile UpdateInfo cachedLatestStable = null;
    private static volatile UpdateInfo cachedLatestBeta = null;

    public interface CheckUpdateCallback {
        void onUpdateCheckResult(boolean hasUpdate, UpdateInfo updateInfo, String currentVersion);
        void onError(String errorMessage);
    }

    public static boolean isBetaUpdatesEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return prefs.getBoolean(PREF_BETA_UPDATES, false);
    }

    public static void setBetaUpdatesEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putBoolean(PREF_BETA_UPDATES, enabled).apply();
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
        return "1.8.9-beta";
    }

    public static boolean isVersionBeta(String version) {
        if (version == null || version.trim().isEmpty()) return false;
        String lower = version.toLowerCase();
        return lower.contains("beta") || lower.contains("alpha") || lower.contains("rc") || lower.contains("preview");
    }

    public static boolean isCurrentVersionBeta(Context context) {
        return isVersionBeta(getCurrentVersion(context));
    }

    public static UpdateInfo getCachedLatestStable() {
        return cachedLatestStable;
    }

    public static UpdateInfo getCachedLatestBeta() {
        return cachedLatestBeta;
    }

    public static void checkForUpdates(Context context, OkHttpClient client, CheckUpdateCallback callback) {
        String currentVersion = getCurrentVersion(context);
        boolean allowBeta = isBetaUpdatesEnabled(context);

        OkHttpClient httpClient = client != null ? client : new OkHttpClient();
        Request request = new Request.Builder()
                .url(GITHUB_RELEASES_LIST_API)
                .header("User-Agent", "AnimeLib-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Error fetching releases", e);
                // Fallback attempt with releases/latest
                fallbackToLatest(httpClient, currentVersion, callback);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    if (response.code() == 404) {
                        fallbackToLatest(httpClient, currentVersion, callback);
                    } else if (callback != null) {
                        callback.onError("Ошибка GitHub API (HTTP " + response.code() + ")");
                    }
                    return;
                }

                try {
                    String jsonStr = response.body() != null ? response.body().string() : null;
                    if (jsonStr == null || jsonStr.isEmpty()) {
                        if (callback != null) callback.onError("Пустой ответ от сервера");
                        return;
                    }

                    JsonElement parsed = JsonParser.parseString(jsonStr);
                    List<UpdateInfo> releases = new ArrayList<>();

                    if (parsed.isJsonArray()) {
                        JsonArray arr = parsed.getAsJsonArray();
                        for (JsonElement item : arr) {
                            if (item.isJsonObject()) {
                                JsonObject obj = item.getAsJsonObject();
                                if (obj.has("draft") && obj.get("draft").getAsBoolean()) {
                                    continue;
                                }
                                UpdateInfo info = parseReleaseJsonObject(obj);
                                if (info != null) {
                                    releases.add(info);
                                }
                            }
                        }
                    } else if (parsed.isJsonObject()) {
                        UpdateInfo info = parseReleaseJsonObject(parsed.getAsJsonObject());
                        if (info != null) releases.add(info);
                    }

                    if (releases.isEmpty()) {
                        if (callback != null) {
                            callback.onUpdateCheckResult(false, null, currentVersion);
                        }
                        return;
                    }

                    UpdateInfo latestOverall = null;
                    UpdateInfo latestStable = null;
                    UpdateInfo latestBeta = null;

                    for (UpdateInfo info : releases) {
                        if (info.isBeta()) {
                            if (latestBeta == null || compareVersions(info.getTagName(), latestBeta.getTagName()) > 0) {
                                latestBeta = info;
                            }
                        } else {
                            if (latestStable == null || compareVersions(info.getTagName(), latestStable.getTagName()) > 0) {
                                latestStable = info;
                            }
                        }

                        if (latestOverall == null || compareVersions(info.getTagName(), latestOverall.getTagName()) > 0) {
                            latestOverall = info;
                        }
                    }

                    cachedLatestStable = latestStable;
                    cachedLatestBeta = latestBeta;

                    UpdateInfo targetUpdate;
                    if (allowBeta) {
                        targetUpdate = latestOverall;
                    } else {
                        targetUpdate = latestStable;
                    }

                    if (targetUpdate != null) {
                        targetUpdate.setStableUpdateInfo(latestStable);
                    }

                    boolean hasUpdate = targetUpdate != null && isUpdateAvailable(currentVersion, targetUpdate.getTagName());

                    if (callback != null) {
                        callback.onUpdateCheckResult(hasUpdate, targetUpdate, currentVersion);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse releases response", e);
                    if (callback != null) {
                        callback.onError("Ошибка чтения данных обновления");
                    }
                }
            }
        });
    }

    private static void fallbackToLatest(OkHttpClient client, String currentVersion, CheckUpdateCallback callback) {
        Request request = new Request.Builder()
                .url(GITHUB_RELEASES_LATEST_API)
                .header("User-Agent", "AnimeLib-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
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
                            callback.onError("Ошибка GitHub API (HTTP " + response.code() + ")");
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
                    UpdateInfo updateInfo = parseReleaseJsonObject(json);

                    if (updateInfo != null) {
                        cachedLatestStable = updateInfo;
                        updateInfo.setStableUpdateInfo(updateInfo);
                        boolean hasUpdate = isUpdateAvailable(currentVersion, updateInfo.getTagName());
                        if (callback != null) {
                            callback.onUpdateCheckResult(hasUpdate, updateInfo, currentVersion);
                        }
                    } else if (callback != null) {
                        callback.onUpdateCheckResult(false, null, currentVersion);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in fallback release parse", e);
                    if (callback != null) {
                        callback.onError("Ошибка чтения данных обновления");
                    }
                }
            }
        });
    }

    public static UpdateInfo parseReleaseJsonObject(JsonObject json) {
        if (json == null) return null;

        String tagName = json.has("tag_name") && !json.get("tag_name").isJsonNull() ? json.get("tag_name").getAsString() : "";
        String releaseName = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : tagName;
        String body = json.has("body") && !json.get("body").isJsonNull() ? json.get("body").getAsString() : "Описание изменений отсутствует.";
        String publishedAt = json.has("published_at") && !json.get("published_at").isJsonNull() ? json.get("published_at").getAsString() : "";
        String htmlUrl = json.has("html_url") && !json.get("html_url").isJsonNull() ? json.get("html_url").getAsString() : "https://github.com/Fazer000/AnimeLib-Mobile";
        boolean isPrerelease = json.has("prerelease") && json.get("prerelease").getAsBoolean();

        boolean isBeta = isPrerelease || isVersionBeta(tagName) || isVersionBeta(releaseName);

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

        return new UpdateInfo(tagName, releaseName, body, publishedAt, htmlUrl, apkUrl, apkSize, isBeta);
    }

    public static boolean isUpdateAvailable(String currentVersion, String latestTag) {
        if (latestTag == null || latestTag.trim().isEmpty()) return false;
        return compareVersions(currentVersion, latestTag) < 0;
    }

    public static int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;

        String clean1 = cleanVersion(v1);
        String clean2 = cleanVersion(v2);

        boolean isBeta1 = isVersionBeta(v1);
        boolean isBeta2 = isVersionBeta(v2);

        String numOnly1 = clean1.replaceAll("-.*", "");
        String numOnly2 = clean2.replaceAll("-.*", "");

        String[] parts1 = numOnly1.split("\\.");
        String[] parts2 = numOnly2.split("\\.");

        int maxLength = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLength; i++) {
            int p1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;

            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }

        // Major.Minor.Patch are identical
        if (isBeta1 && !isBeta2) {
            return -1; // 1.0.1-beta is older than 1.0.1 stable
        } else if (!isBeta1 && isBeta2) {
            return 1;  // 1.0.1 stable is newer than 1.0.1-beta
        }

        return 0;
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
