package com.example.animelib.api;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.SiteUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Ядро сетевого клиента API: общие пулы соединений, OkHttp-клиенты, авторизация,
 * генерация заголовков, парсинг ошибок сервера и утилиты URL.
 */
public class ApiClientCore {
    private static final String TAG = "ApiService";

    public static final String API_BASE_CDNLIBS = "https://hapi.hentaicdn.org/api";
    public static final String API_BASE_HAPI = "https://hapi.hentaicdn.org/api";
    public static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final String USER_AGENT_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36";

    // Fallback токен если нет токена в БД
    private static final String FALLBACK_BEARER_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJhdWQiOiIxIiwianRpIjoiOTZkYjliMDI4NGM0OWQ1Yzc2NTIxMzkxZTRlNDJkNjAwNTFmMDUzMDU2NjBjZGQzYTRjYmEzN2FjMmRmYTZhNjEyM2VmNDgxZDBjMGU0Y2MiLCJpYXQiOjE3NTc0MzEyNDcuOTg2MjE5LCJuYmYiOjE3NTc0MzEyNDcuOTg2MjIxLCJleHAiOjE3NjAwMjMyNDcuOTgyNTM3LCJzdWIiOiI5NDM5MzIxIiwic2NvcGVzIjpbXX0.FG2bBdeF0328Prrsr9Q_SL-VkQyeJMqE9b9uQ1E74JsCnJPveeMMLYNuJt_cTp5XpkvFK3XHltfCM7wi4Gg-x3rlpG-sTELMaoMNWv-4TmNcQbrKwSnTSVJfUFlnguVA7kpGHBgfAaL3NVKSwu_Pu1xqq6UwqpV9hBSJ6iTHG7T3vz7e_HxhGWQ7AZ47xmoo76aOnWQ2vIceF-zq6gF0peKBsHXuG8Prl-88xyltkT2SSnAJrTl4xmPQsM0F0OntkkFZGU6XPdFwXw-orxvtpCfsv556ra5fdbACMjqfZ3euwqXEHGRtkjMJpmku1-sV_xubQvCgbwuO8WRc-ukuWv3x2WTffkXypFKviEdNTXLBFki5ex4sblvaYhDUd4IrZwIjL-GRPQ9_X6WZITz7Lic5faKs1kr3mxXDSuK7u7tC2WSCom_I_CYR9_aIytJ_XkxixG-aa3LP9-jaOn0n7iZS8XNjaIlLHyqr2Of9wPvJ-A1NVv41EeaptXWs7VcSWg42-fUkofNyS2Qn1Qdo9DzVKmqzO9jMpe-8suwBVGl3gpr4nCwn4J8tIKOTzWX--xHkotH5w1TYaQAtzKs6ocyptylNdAD8WRm_FU3E3pdY5Ecarem7SK8ij5rh724GMiBXN9y9s6jBSwPoIAD9W-R4UoXo1mhsRNGiJ4EkC0U";

    private static final ConnectionPool SHARED_API_POOL = new ConnectionPool(10, 3, TimeUnit.MINUTES);
    private static final Pattern MEDIA_SLUG_PATTERN = Pattern.compile("/anime/([^/\\?#]+)");
    private static final Pattern SHIKIMORI_ANIME_ID_PATTERN = Pattern.compile("/animes/[zZ]?(\\d+)");
    private static final Pattern DIGITS_PATTERN = Pattern.compile("(\\d+)");

    private static final Map<String, String> KODIK_STREAM_HEADERS_IMMUTABLE;
    static {
        Map<String, String> h = new HashMap<>();
        h.put("Referer", "https://kodik.info/");
        h.put("Origin", "https://kodik.info");
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36");
        h.put("Accept", "*/*");
        h.put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        h.put("Sec-Fetch-Dest", "empty");
        h.put("Sec-Fetch-Mode", "cors");
        h.put("Sec-Fetch-Site", "cross-site");
        KODIK_STREAM_HEADERS_IMMUTABLE = Collections.unmodifiableMap(h);
    }

    private static volatile OkHttpClient sharedHttpClient;
    private static volatile OkHttpClient sharedShikimoriClient;
    private static volatile Gson sharedGson;
    private static volatile ExecutorService sharedKodikExecutor;
    private static volatile ExecutorService sharedApiExecutor;

    private final Context context;
    private final OkHttpClient httpClient;
    private final OkHttpClient shikimoriHttpClient;
    private final Gson gson;
    private final ExecutorService executor;
    private final ExecutorService kodikExecutor;
    private final DatabaseManager databaseManager;
    private final KodikLinksExtractor kodikLinksExtractor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile String cachedVideoHeadersToken = null;
    private volatile Map<String, String> cachedVideoHeadersMap = null;

    private static final long AUTH_META_CACHE_TTL_MS = 1500L;
    private volatile String cachedSiteUrl = null;
    private volatile long cachedSiteUrlAtMs = 0L;
    private volatile TokenEntity cachedTokenEntity = null;
    private volatile boolean hasCachedTokenEntity = false;
    private volatile long cachedTokenAtMs = 0L;

    public ApiClientCore(Context context) {
        this.context = context;
        this.httpClient = getOrCreateHttpClient();
        this.shikimoriHttpClient = getOrCreateShikimoriClient();
        this.gson = getOrCreateGson();
        this.kodikExecutor = getOrCreateKodikExecutor();
        this.kodikLinksExtractor = new KodikLinksExtractor(this.httpClient, this.gson);
        this.executor = getOrCreateApiExecutor();
        this.databaseManager = DatabaseManager.getInstance(context.getApplicationContext());
    }

    public static OkHttpClient getSharedHttpClient() {
        return getOrCreateHttpClient();
    }

    public static Gson getSharedGson() {
        return getOrCreateGson();
    }

    private static OkHttpClient getOrCreateHttpClient() {
        if (sharedHttpClient == null) {
            synchronized (ApiClientCore.class) {
                if (sharedHttpClient == null) {
                    sharedHttpClient = new OkHttpClient.Builder()
                            .connectionPool(SHARED_API_POOL)
                            .dispatcher(FastDns.createDispatcher(48, 12))
                            .dns(FastDns.INSTANCE)
                            .connectTimeout(8, TimeUnit.SECONDS)
                            .readTimeout(12, TimeUnit.SECONDS)
                            .writeTimeout(10, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return sharedHttpClient;
    }

    private static OkHttpClient getOrCreateShikimoriClient() {
        if (sharedShikimoriClient == null) {
            synchronized (ApiClientCore.class) {
                if (sharedShikimoriClient == null) {
                    sharedShikimoriClient = new OkHttpClient.Builder()
                            .connectionPool(SHARED_API_POOL)
                            .dns(FastDns.INSTANCE)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return sharedShikimoriClient;
    }

    private static Gson getOrCreateGson() {
        if (sharedGson == null) {
            synchronized (ApiClientCore.class) {
                if (sharedGson == null) {
                    sharedGson = new Gson();
                }
            }
        }
        return sharedGson;
    }

    private static ExecutorService getOrCreateKodikExecutor() {
        if (sharedKodikExecutor == null || sharedKodikExecutor.isShutdown()) {
            synchronized (ApiClientCore.class) {
                if (sharedKodikExecutor == null || sharedKodikExecutor.isShutdown()) {
                    sharedKodikExecutor = Executors.newFixedThreadPool(4, r -> {
                        Thread t = new Thread(r, "kodik-extractor");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return sharedKodikExecutor;
    }

    private static ExecutorService getOrCreateApiExecutor() {
        if (sharedApiExecutor == null || sharedApiExecutor.isShutdown()) {
            synchronized (ApiClientCore.class) {
                if (sharedApiExecutor == null || sharedApiExecutor.isShutdown()) {
                    sharedApiExecutor = Executors.newFixedThreadPool(4, r -> {
                        Thread t = new Thread(r, "api-client-worker");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return sharedApiExecutor;
    }

    public Context getContext() {
        return context;
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public OkHttpClient getShikimoriHttpClient() {
        return shikimoriHttpClient;
    }

    public Gson getGson() {
        return gson;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public KodikLinksExtractor getKodikLinksExtractor() {
        return kodikLinksExtractor;
    }

    public ExecutorService getKodikExecutor() {
        return kodikExecutor;
    }

    public RequestBody createJsonRequestBody(Object payload) {
        String json = (payload instanceof String) ? (String) payload : gson.toJson(payload);
        return RequestBody.create(json, JSON_MEDIA_TYPE);
    }

    public Request.Builder buildApiRequest(String url) {
        return buildApiRequest(url, true);
    }

    public Request.Builder buildSearchApiRequest(String url) {
        return buildApiRequest(url, false);
    }

    public String resolveSiteUrl() {
        long now = System.currentTimeMillis();
        String cached = cachedSiteUrl;
        if (cached != null && (now - cachedSiteUrlAtMs) < AUTH_META_CACHE_TTL_MS) {
            return cached;
        }
        String rawSiteUrl = databaseManager != null ? databaseManager.getSiteUrl() : null;
        boolean isOtherRegion = SiteUtils.isOtherRegion(rawSiteUrl, context);
        String siteUrl = isOtherRegion ? "https://animelib.org" : "https://v5.animelib.org";
        cachedSiteUrl = siteUrl;
        cachedSiteUrlAtMs = now;
        return siteUrl;
    }

    public String getRefererUrl() {
        return resolveSiteUrl() + "/";
    }

    private TokenEntity resolveTokenEntity() {
        if (databaseManager == null) return null;
        long now = System.currentTimeMillis();
        if (hasCachedTokenEntity && (now - cachedTokenAtMs) < AUTH_META_CACHE_TTL_MS) {
            return cachedTokenEntity;
        }
        try {
            TokenEntity token = databaseManager.getToken();
            cachedTokenEntity = token;
            hasCachedTokenEntity = true;
            cachedTokenAtMs = now;
            return token;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read token from database", e);
            return null;
        }
    }

    public void invalidateAuthMetaCache() {
        hasCachedTokenEntity = false;
        cachedTokenEntity = null;
        cachedTokenAtMs = 0L;
        cachedSiteUrl = null;
        cachedSiteUrlAtMs = 0L;
    }

    public Request.Builder buildApiRequest(String url, boolean includeSiteId) {
        String siteUrl = resolveSiteUrl();
        String referer = siteUrl + "/";

        Request.Builder builder = new Request.Builder()
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
                .addHeader("Sec-Fetch-Site", "cross-site");

        if (includeSiteId) {
            builder.addHeader("Site-Id", "5");
        }

        return builder
                .addHeader("X-Requested-With", "com.unixshells.devbrowser")
                .addHeader("User-Agent", USER_AGENT_MOBILE)
                .addHeader("Client-Time-Zone", "Europe/Moscow")
                .addHeader("Priority", "u=1, i");
    }

    public String getBearerToken() {
        try {
            TokenEntity token = resolveTokenEntity();
            if (token != null && token.getAccessToken() != null && !token.getAccessToken().trim().isEmpty()) {
                return stripBearerPrefix(token.getAccessToken());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get token from database", e);
        }

        return stripBearerPrefix(FALLBACK_BEARER_TOKEN);
    }

    private static String stripBearerPrefix(String rawToken) {
        if (rawToken == null) return "";
        String trimmed = rawToken.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    public TokenEntity getValidUserTokenEntity() {
        TokenEntity token = resolveTokenEntity();
        if (token != null && token.getAccessToken() != null && !token.getAccessToken().trim().isEmpty()) {
            return token;
        }
        return null;
    }

    public boolean isAuthorized() {
        return getValidUserTokenEntity() != null;
    }

    public static String extractUserIdFromToken(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                String jsonStr = new String(decoded, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                String[] directKeys = {"sub", "user_id", "id", "nameid", "uid"};
                for (String key : directKeys) {
                    if (json.has(key)) {
                        return json.optString(key);
                    }
                }
                if (json.has("user")) {
                    JSONObject uJson = json.optJSONObject("user");
                    if (uJson != null && uJson.has("id")) {
                        return uJson.optString("id");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting userId from token", e);
        }
        return null;
    }

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
            Log.e(TAG, "Error getting current userId", e);
        }
        return null;
    }

    public Map<String, String> getVideoRequestHeaders() {
        String token = getBearerToken();
        String normalizedToken = token != null ? token.trim() : "";
        String siteUrl = resolveSiteUrl();
        String cacheKey = normalizedToken + "|" + siteUrl;
        Map<String, String> snapshot = cachedVideoHeadersMap;
        if (snapshot != null && cacheKey.equals(cachedVideoHeadersToken)) {
            return snapshot;
        }

        Map<String, String> headers = new HashMap<>();
        if (!normalizedToken.isEmpty()) {
            headers.put("Authorization", "Bearer " + stripBearerPrefix(normalizedToken));
        }

        headers.put("Referer", siteUrl + "/");
        headers.put("Origin", siteUrl);
        headers.put("User-Agent", USER_AGENT_MOBILE);
        headers.put("accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5");
        headers.put("accept-language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("sec-ch-ua", "\"Not=A?Brand\";v=\"99\", \"Android WebView\";v=\"151\", \"Chromium\";v=\"151\"");
        headers.put("sec-ch-ua-mobile", "?1");
        headers.put("sec-ch-ua-platform", "\"Android\"");
        headers.put("sec-fetch-dest", "video");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "cross-site");

        Map<String, String> unmodifiable = Collections.unmodifiableMap(headers);
        cachedVideoHeadersToken = cacheKey;
        cachedVideoHeadersMap = unmodifiable;
        return unmodifiable;
    }

    public static boolean isKodikStreamUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("cdnlibs.org") || lower.contains("imglib.info") || lower.contains("animelib")) {
            return false;
        }
        return lower.contains("kodik")
                || lower.contains("aniqit")
                || lower.contains(":hls:")
                || lower.contains(".m3u8");
    }

    public static Map<String, String> buildKodikStreamHeaders() {
        return KODIK_STREAM_HEADERS_IMMUTABLE;
    }

    public Map<String, String> getVideoRequestHeadersForUrl(String url) {
        return isKodikStreamUrl(url) ? buildKodikStreamHeaders() : getVideoRequestHeaders();
    }

    public static String cleanUrlSegment(String raw) {
        if (raw == null) return "";
        String clean = raw.trim();
        int qIdx = clean.indexOf('?');
        if (qIdx >= 0) clean = clean.substring(0, qIdx);
        int hIdx = clean.indexOf('#');
        if (hIdx >= 0) clean = clean.substring(0, hIdx);
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean.trim();
    }

    public static String extractAnimeId(String url) {
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
            String identifier = VideoUrlHelper.extractAnimeIdentifier(url);
            if (identifier != null) {
                String numericId = VideoUrlHelper.extractAnimeId(identifier);
                if (numericId != null) return numericId;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting anime_id from URL: " + url, e);
        }
        return null;
    }

    public static String extractMediaSlugFromUrl(String animeUrl) {
        if (animeUrl == null || animeUrl.isEmpty()) {
            return null;
        }
        try {
            Matcher matcher = MEDIA_SLUG_PATTERN.matcher(animeUrl);
            if (matcher.find()) {
                return matcher.group(1);
            }
            if (!animeUrl.contains("/")) {
                return cleanUrlSegment(animeUrl);
            }
            String[] parts = animeUrl.split("/");
            for (String part : parts) {
                if (part.contains("--")) {
                    return cleanUrlSegment(part);
                }
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting media slug from URL: " + animeUrl, e);
            return null;
        }
    }

    public static String extractAnimeSlug(String url) {
        if (url == null || url.isEmpty()) return null;
        String mediaSlug = extractMediaSlugFromUrl(url);
        return (mediaSlug != null && !mediaSlug.isEmpty()) ? cleanUrlSegment(mediaSlug) : null;
    }

    public static long extractShikimoriId(String shikimoriHref) {
        if (shikimoriHref == null || shikimoriHref.trim().isEmpty()) {
            return -1;
        }
        String href = shikimoriHref.trim();
        Matcher matcher = SHIKIMORI_ANIME_ID_PATTERN.matcher(href);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (Exception ignored) {}
        }
        Matcher digitsMatcher = DIGITS_PATTERN.matcher(href);
        if (digitsMatcher.find()) {
            try {
                return Long.parseLong(digitsMatcher.group(1));
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public static String formatTimecode(long milliseconds) {
        if (milliseconds < 0) {
            return "00:00";
        }
        long totalSeconds = milliseconds / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    public String extractServerErrorMessage(String responseBody, int responseCode) {
        if (responseBody != null && !responseBody.trim().isEmpty()) {
            String trimmed = responseBody.trim();
            if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html")) {
                return "Ошибка сервера (" + responseCode + ")";
            }
            try {
                JsonObject errObj = gson.fromJson(trimmed, JsonObject.class);
                if (errObj != null) {
                    // 1. data.toast.message
                    if (errObj.has("data") && errObj.get("data").isJsonObject()) {
                        JsonObject dataObj = errObj.getAsJsonObject("data");
                        String toastMsg = extractToastMessage(dataObj);
                        if (toastMsg != null) return toastMsg;
                    }
                    // 2. toast.message на верхнем уровне
                    String topToastMsg = extractToastMessage(errObj);
                    if (topToastMsg != null) return topToastMsg;

                    // 3. Валидационные ошибки по полям
                    List<String> fieldErrors = new ArrayList<>();
                    if (errObj.has("data") && errObj.get("data").isJsonObject()) {
                        extractFieldErrorsFromObject(errObj.getAsJsonObject("data"), fieldErrors);
                    }
                    if (errObj.has("errors") && errObj.get("errors").isJsonObject()) {
                        extractFieldErrorsFromObject(errObj.getAsJsonObject("errors"), fieldErrors);
                    }
                    if (!fieldErrors.isEmpty()) {
                        return String.join("\n", fieldErrors);
                    }

                    // 4. message и errors
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

    private static String extractToastMessage(JsonObject parent) {
        if (parent != null && parent.has("toast") && parent.get("toast").isJsonObject()) {
            JsonObject toastObj = parent.getAsJsonObject("toast");
            if (toastObj.has("message") && !toastObj.get("message").isJsonNull()) {
                String msg = toastObj.get("message").getAsString();
                if (msg != null && !msg.trim().isEmpty()) {
                    return msg;
                }
            }
        }
        return null;
    }

    private static void extractFieldErrorsFromObject(JsonObject container, List<String> resultList) {
        if (container == null) return;
        for (Map.Entry<String, JsonElement> entry : container.entrySet()) {
            if ("toast".equals(entry.getKey())) continue;
            JsonElement val = entry.getValue();
            if (val != null && val.isJsonArray()) {
                JsonArray arr = val.getAsJsonArray();
                for (JsonElement item : arr) {
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

    public void safeExecute(Runnable task) {
        try {
            if (executor != null && !executor.isShutdown()) {
                executor.execute(task);
            } else {
                Log.w(TAG, "Executor is shutdown, skipping task execution");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing task", e);
        }
    }

    public void safeRunOnUiThread(Runnable runnable) {
        if (runnable == null) return;
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runnable.run();
                return;
            }
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    activity.runOnUiThread(runnable);
                    return;
                }
            }
            mainHandler.post(runnable);
        } catch (Exception e) {
            Log.e(TAG, "Error calling UI thread callback", e);
            try {
                mainHandler.post(runnable);
            } catch (Exception ex) {
                Log.e(TAG, "Error in fallback callback", ex);
            }
        }
    }

    @FunctionalInterface
    public interface CachedResponseHandler<T> {
        T parseAndCache(String responseBody) throws Exception;
    }

    /**
     * Лёгкий токен отмены для сетевых запросов (немедленно отменяет OkHttp Call и подавляет колбэк).
     */
    public static final class RequestHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Call call;
        private volatile Runnable detachAction;

        public void attachCall(Call call) {
            this.call = call;
            if (cancelled.get() && call != null) {
                try {
                    call.cancel();
                } catch (Exception ignored) {}
            }
        }

        public void setDetachAction(Runnable action) {
            this.detachAction = action;
            if (cancelled.get() && action != null) {
                try {
                    action.run();
                } catch (Exception ignored) {}
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Runnable detach = this.detachAction;
                if (detach != null) {
                    try {
                        detach.run();
                    } catch (Exception ignored) {}
                } else {
                    Call c = this.call;
                    if (c != null) {
                        try {
                            c.cancel();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    /**
     * Общий асинхронный GET-исполнитель для кэшируемых API-вызовов с дедупликацией in-flight
     * запросов через {@link ApiCacheManager.RequestCache} и fallback на устаревший кэш при HTTP 429.
     */
    public <K, T> void executeCachedGet(
            ApiCacheManager.RequestCache<K, T> cache,
            K requestKey,
            K staleFallbackKey,
            String apiUrl,
            boolean includeSiteId,
            boolean forceRefresh,
            ApiCacheManager.CallbackNotifier<T> notifier,
            String networkErrorPrefix,
            String httpErrorPrefix,
            String invalidFormatError,
            CachedResponseHandler<T> responseHandler) {
        executeCachedGetWithHandle(
                cache,
                requestKey,
                staleFallbackKey,
                apiUrl,
                includeSiteId,
                forceRefresh,
                notifier,
                networkErrorPrefix,
                httpErrorPrefix,
                invalidFormatError,
                responseHandler
        );
    }

    public <K, T> RequestHandle executeCachedGetWithHandle(
            ApiCacheManager.RequestCache<K, T> cache,
            K requestKey,
            K staleFallbackKey,
            String apiUrl,
            boolean includeSiteId,
            boolean forceRefresh,
            ApiCacheManager.CallbackNotifier<T> notifier,
            String networkErrorPrefix,
            String httpErrorPrefix,
            String invalidFormatError,
            CachedResponseHandler<T> responseHandler) {
        final RequestHandle handle = new RequestHandle();
        final ApiCacheManager.CallbackNotifier<T> guardedNotifier = new ApiCacheManager.CallbackNotifier<T>() {
            @Override
            public void onSuccess(T data) {
                if (!handle.isCancelled() && notifier != null) {
                    notifier.onSuccess(data);
                }
            }

            @Override
            public void onError(String error) {
                if (!handle.isCancelled() && notifier != null) {
                    notifier.onError(error);
                }
            }
        };

        boolean shouldStartCall = cache.registerOrAttach(requestKey, guardedNotifier);
        final Call[] callHolder = new Call[1];
        handle.setDetachAction(() -> {
            boolean noMoreListeners = cache.detachPendingNotifier(requestKey, guardedNotifier);
            if (noMoreListeners && callHolder[0] != null) {
                try {
                    callHolder[0].cancel();
                } catch (Exception ignored) {}
            }
        });

        if (!shouldStartCall) {
            return handle;
        }

        safeExecute(() -> {
            if (handle.isCancelled()) {
                cache.detachPendingNotifier(requestKey, guardedNotifier);
                return;
            }
            try {
                Request.Builder reqBuilder = buildApiRequest(apiUrl, includeSiteId);
                if (forceRefresh) {
                    reqBuilder.header("Cache-Control", "no-cache, no-store, max-age=0");
                    reqBuilder.header("Pragma", "no-cache");
                }
                Request request = reqBuilder.build();
                Call call = httpClient.newCall(request);
                callHolder[0] = call;
                handle.attachCall(call);
                if (handle.isCancelled()) {
                    call.cancel();
                    cache.detachPendingNotifier(requestKey, guardedNotifier);
                    return;
                }

                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        if (call.isCanceled()) {
                            cache.detachPendingNotifier(requestKey, guardedNotifier);
                            return;
                        }
                        cache.dispatchError(requestKey, networkErrorPrefix + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (response) {
                            if (call.isCanceled()) {
                                cache.detachPendingNotifier(requestKey, guardedNotifier);
                                return;
                            }
                            if (response.isSuccessful() && response.body() != null) {
                                String responseBody = response.body().string();
                                if (call.isCanceled()) {
                                    cache.detachPendingNotifier(requestKey, guardedNotifier);
                                    return;
                                }
                                T parsed = responseHandler.parseAndCache(responseBody);
                                if (parsed != null) {
                                    cache.dispatchSuccess(requestKey, parsed);
                                } else {
                                    cache.dispatchError(requestKey, invalidFormatError);
                                }
                            } else {
                                T stale = staleFallbackKey != null ? cache.getStale(staleFallbackKey) : null;
                                if (response.code() == 429 && stale != null) {
                                    Log.w(TAG, "HTTP 429 for key=" + staleFallbackKey + " — using stale cached data");
                                    cache.dispatchSuccess(requestKey, stale);
                                } else {
                                    cache.dispatchError(requestKey, httpErrorPrefix + response.code());
                                }
                            }
                        } catch (Exception ex) {
                            if (call.isCanceled()) {
                                cache.detachPendingNotifier(requestKey, guardedNotifier);
                                return;
                            }
                            Log.e(TAG, "Error processing cached GET response for " + apiUrl, ex);
                            cache.dispatchError(requestKey, "Ошибка обработки: " + ex.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error executing cached GET for " + apiUrl, e);
                cache.dispatchError(requestKey, "Ошибка запроса: " + e.getMessage());
            }
        });

        return handle;
    }

    public void shutdown() {
        // Shared daemon executors and DatabaseManager singleton stay alive across callers.
        mainHandler.removeCallbacksAndMessages(null);
    }
}
