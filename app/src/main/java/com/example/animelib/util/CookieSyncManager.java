package com.example.animelib.util;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import com.example.animelib.AnimeLibApplication;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.TokenEntity;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Менеджер синхронизации и дублирования куки (включая XSRF-TOKEN) и localStorage объекта auth
 * между всеми доменам сети Lib (AnimeLib, MangaLib, RanobeLib, HentaiLib, SlashLib, Lib.Social).
 * Выполняется асинхронно и ТОЛЬКО при наличии авторизации на AnimeLib (токен в БД).
 */
public class CookieSyncManager {
    private static final String TAG = "CookieSyncManager";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static volatile String savedAuthJson = null;
    private static volatile String lastSyncedCookieHash = "";
    private static volatile long lastSyncTimestamp = 0;
    private static volatile String lastSyncSourceUrl = "";

    /**
     * Предварительно прогревает кэш токена и куки в фоновом потоке при старте приложения,
     * чтобы не блокировать UI-поток во время навигации WebView.
     */
    public static void warmUpAsync(final Context context) {
        executor.execute(() -> {
            try {
                Context appCtx = context != null ? context.getApplicationContext() : AnimeLibApplication.getInstance();
                if (appCtx != null) {
                    DatabaseManager dbManager = DatabaseManager.getInstance(appCtx);
                    dbManager.getSiteUrl();
                    getAuthJson(appCtx);
                }
                syncFromUrlInternal(null);
            } catch (Exception e) {
                Log.w(TAG, "Warmup error", e);
            }
        });
    }

    // Все ключевые URL сети Lib
    public static final List<String> ALL_LIB_URLS = Arrays.asList(
        "https://animelib.me",
        "https://animelib.org",
        "https://v5.animelib.org",
        "https://v2.animelib.me",
        "https://mangalib.me",
        "https://mangalib.org",
        "https://v5.mangalib.org",
        "https://v2.mangalib.me",
        "https://ranobelib.me",
        "https://ranobelib.org",
        "https://v5.ranobelib.org",
        "https://v2.ranobelib.me",
        "https://hentailib.me",
        "https://hentailib.org",
        "https://v5.hentailib.org",
        "https://slashlib.me",
        "https://slashlib.org",
        "https://v5.slashlib.org",
        "https://lib.social"
    );

    // Корневые домены для установки Domain куков
    public static final List<String> ALL_LIB_DOMAINS = Arrays.asList(
        ".animelib.org",
        ".animelib.me",
        ".mangalib.org",
        ".mangalib.me",
        ".ranobelib.org",
        ".ranobelib.me",
        ".hentailib.org",
        ".hentailib.me",
        ".slashlib.org",
        ".slashlib.me",
        ".lib.social"
    );

    /**
     * Проверяет, авторизован ли пользователь на AnimeLib (наличие токена в БД)
     */
    public static boolean isAuthorizedOnAnimeLib(Context context) {
        if (savedAuthJson != null && !savedAuthJson.trim().isEmpty() && !"null".equals(savedAuthJson)) {
            return true;
        }
        try {
            Context appContext = context != null ? context.getApplicationContext() : AnimeLibApplication.getInstance();
            if (appContext != null) {
                DatabaseManager dbManager = DatabaseManager.getInstance(appContext);
                if (dbManager != null && dbManager.hasToken()) {
                    TokenEntity token = dbManager.getToken();
                    return token != null && token.getAccessToken() != null && !token.getAccessToken().trim().isEmpty();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking authorization in database", e);
        }
        return false;
    }

    /**
     * Сохраняет JSON объект auth из localStorage и в базе данных
     */
    public static void saveAuthJson(String authJson) {
        if (authJson != null && !authJson.trim().isEmpty() && !"null".equals(authJson) && !"undefined".equals(authJson)) {
            savedAuthJson = authJson.trim();
            Log.d(TAG, "Saved auth object from localStorage for cross-domain sync");
            try {
                Context appContext = AnimeLibApplication.getInstance();
                if (appContext != null) {
                    DatabaseManager dbManager = DatabaseManager.getInstance(appContext);
                    if (dbManager != null) {
                        dbManager.updateAuthJson(savedAuthJson);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error persisting authJson to database", e);
            }
        }
    }

    /**
     * Очищает сохранённый auth объект (при выходе)
     */
    public static void clearAuthJson() {
        savedAuthJson = null;
        lastSyncedCookieHash = "";
        Log.d(TAG, "Cleared saved auth object");
    }

    /**
     * Получает JSON строку auth. Если savedAuthJson не задан в памяти, считывает колонку authJson из БД.
     */
    public static String getAuthJson(Context context) {
        if (savedAuthJson != null && !savedAuthJson.trim().isEmpty() && !"null".equals(savedAuthJson)) {
            return savedAuthJson;
        }
        try {
            Context appContext = context != null ? context.getApplicationContext() : AnimeLibApplication.getInstance();
            if (appContext != null) {
                DatabaseManager dbManager = DatabaseManager.getInstance(appContext);
                TokenEntity token = dbManager.getToken();
                if (token != null) {
                    if (token.getAuthJson() != null && !token.getAuthJson().trim().isEmpty() && !"null".equals(token.getAuthJson())) {
                        savedAuthJson = token.getAuthJson().trim();
                        return savedAuthJson;
                    }
                    if (token.getAccessToken() != null && !token.getAccessToken().isEmpty()) {
                        JsonObject tokenObj = new JsonObject();
                        tokenObj.addProperty("token_type", token.getTokenType() != null ? token.getTokenType() : "Bearer");
                        tokenObj.addProperty("expires_in", token.getExpiresIn() > 0 ? token.getExpiresIn() : 2678399L);
                        tokenObj.addProperty("access_token", token.getAccessToken());
                        if (token.getRefreshToken() != null) {
                            tokenObj.addProperty("refresh_token", token.getRefreshToken());
                        }
                        tokenObj.addProperty("timestamp", token.getTimestamp() > 0 ? token.getTimestamp() : System.currentTimeMillis());

                        JsonObject root = new JsonObject();
                        root.add("token", tokenObj);

                        if (token.getUserId() != null || token.getUsername() != null) {
                            JsonObject authObj = new JsonObject();
                            if (token.getUserId() != null) {
                                try {
                                    authObj.addProperty("id", Long.parseLong(token.getUserId()));
                                } catch (Exception ignored) {
                                    authObj.addProperty("id", token.getUserId());
                                }
                            }
                            if (token.getUsername() != null) {
                                authObj.addProperty("username", token.getUsername());
                            }
                            root.add("auth", authObj);
                        }

                        savedAuthJson = root.toString();
                        return savedAuthJson;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error generating auth JSON from DB token", e);
        }
        return null;
    }

    /**
     * Внедряет объект auth в localStorage загружаемой страницы WebView без блокировки главного потока на чтении БД.
     */
    public static void injectAuthLocalStorage(final WebView webView) {
        if (webView == null) return;

        final Context appContext = webView.getContext() != null
                ? webView.getContext().getApplicationContext()
                : AnimeLibApplication.getInstance();

        String fastCachedAuth = savedAuthJson;
        if (fastCachedAuth != null && !fastCachedAuth.isEmpty() && !"null".equals(fastCachedAuth)) {
            postAuthScriptToWebView(webView, fastCachedAuth);
            return;
        }

        executor.execute(() -> {
            try {
                if (!isAuthorizedOnAnimeLib(appContext)) {
                    return;
                }
                final String authStr = getAuthJson(appContext);
                if (authStr == null || authStr.isEmpty() || "null".equals(authStr)) {
                    return;
                }
                postAuthScriptToWebView(webView, authStr);
            } catch (Exception e) {
                Log.e(TAG, "Error preparing auth injection off main thread", e);
            }
        });
    }

    private static void postAuthScriptToWebView(final WebView webView, final String authStr) {
        if (webView == null || authStr == null || authStr.isEmpty()) return;
        final String quotedJsonLiteral = org.json.JSONObject.quote(authStr);
        webView.post(() -> {
            try {
                String jsScript =
                    "(function() {" +
                    "  try {" +
                    "    var authStr = " + quotedJsonLiteral + ";" +
                    "    var current = localStorage.getItem('auth');" +
                    "    if (authStr && current !== authStr) {" +
                    "      localStorage.setItem('auth', authStr);" +
                    "    }" +
                    "  } catch(e) {}" +
                    "})();";
                webView.evaluateJavascript(jsScript, null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to inject auth into localStorage", e);
            }
        });
    }

    /**
     * Синхронизирует все куки с основных доменов сети Lib на остальные (асинхронно).
     * ВЫПОЛНЯЕТСЯ ТОЛЬКО ЕСЛИ ПОЛЬЗОВАТЕЛЬ АВТОРИЗОВАН НА ANIMELIB.
     */
    public static void syncAllCookies() {
        syncFromUrl(null);
    }

    /**
     * Считывает куки с sourceUrl и асинхронно копирует на остальные сайты Lib (XSRF-TOKEN и др.).
     * ВЫПОЛНЯЕТСЯ ТОЛЬКО ЕСЛИ ПОЛЬЗОВАТЕЛЬ АВТОРИЗОВАН НА ANIMELIB.
     */
    public static void syncFromUrl(final String sourceUrl) {
        long now = System.currentTimeMillis();
        String normalizedSource = sourceUrl != null ? sourceUrl : "";
        if ((now - lastSyncTimestamp < 1500) && normalizedSource.equals(lastSyncSourceUrl)) {
            return;
        }
        lastSyncSourceUrl = normalizedSource;

        executor.execute(() -> syncFromUrlInternal(sourceUrl));
    }

    private static void syncFromUrlInternal(final String sourceUrl) {
        try {
            Context context = AnimeLibApplication.getInstance();
            if (!isAuthorizedOnAnimeLib(context)) {
                return;
            }

            CookieManager cookieManager = CookieManager.getInstance();
            if (cookieManager == null) return;

            cookieManager.setAcceptCookie(true);

            Set<String> sourceUrls = new HashSet<>();
            if (sourceUrl != null && !sourceUrl.trim().isEmpty() && sourceUrl.startsWith("http")) {
                sourceUrls.add(sourceUrl);
            }
            sourceUrls.add("https://v5.animelib.org");
            sourceUrls.add("https://animelib.org");
            sourceUrls.add("https://animelib.me");
            sourceUrls.add("https://mangalib.me");
            sourceUrls.add("https://ranobelib.me");
            sourceUrls.add("https://lib.social");

            StringBuilder combinedCookies = new StringBuilder();
            for (String src : sourceUrls) {
                String cookies = cookieManager.getCookie(src);
                if (cookies != null && !cookies.trim().isEmpty()) {
                    combinedCookies.append(cookies).append("; ");
                }
            }

            String fullCookieStr = combinedCookies.toString().trim();
            if (!fullCookieStr.isEmpty()) {
                copyCookieStringToAllDomainsInternal(cookieManager, fullCookieStr);
            }

            lastSyncTimestamp = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "Error in async syncFromUrl", e);
        }
    }

    /**
     * Асинхронно копирует строку куки на все домены сети Lib
     */
    public static void copyCookieStringToAllDomains(final String cookieHeader) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) return;

        executor.execute(() -> {
            try {
                Context context = AnimeLibApplication.getInstance();
                if (!isAuthorizedOnAnimeLib(context)) {
                    return;
                }

                CookieManager cookieManager = CookieManager.getInstance();
                if (cookieManager == null) return;
                cookieManager.setAcceptCookie(true);

                copyCookieStringToAllDomainsInternal(cookieManager, cookieHeader);
            } catch (Exception e) {
                Log.e(TAG, "Error copying cookies to all domains", e);
            }
        });
    }

    private static void copyCookieStringToAllDomainsInternal(CookieManager cookieManager, String cookieHeader) {
        String hash = String.valueOf(cookieHeader.hashCode());
        if (hash.equals(lastSyncedCookieHash)) {
            return;
        }

        String[] pairs = cookieHeader.split(";");
        boolean updated = false;

        for (String pair : pairs) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) continue;

            int eqIdx = trimmed.indexOf('=');
            if (eqIdx <= 0) continue;

            String key = trimmed.substring(0, eqIdx).trim();
            String val = trimmed.substring(eqIdx + 1).trim();

            if (key.isEmpty()) continue;

            // 1. Копируем куки на основные URL
            String cookieValue = key + "=" + val + "; Path=/; SameSite=Lax";
            String cookieValueSecure = key + "=" + val + "; Path=/; Secure; SameSite=Lax";

            for (String targetUrl : ALL_LIB_URLS) {
                cookieManager.setCookie(targetUrl, cookieValue);
                cookieManager.setCookie(targetUrl, cookieValueSecure);
            }

            // 2. Копируем с директивой Domain на корневые домены
            for (String domain : ALL_LIB_DOMAINS) {
                String domainCookieValue = key + "=" + val + "; Path=/; Domain=" + domain + "; SameSite=Lax";
                String domainCookieValueSecure = key + "=" + val + "; Path=/; Domain=" + domain + "; Secure; SameSite=Lax";
                String baseUrl = "https://" + (domain.startsWith(".") ? domain.substring(1) : domain);
                cookieManager.setCookie(baseUrl, domainCookieValue);
                cookieManager.setCookie(baseUrl, domainCookieValueSecure);
            }
            updated = true;
        }

        if (updated) {
            cookieManager.flush();
            lastSyncedCookieHash = hash;
            Log.d(TAG, "Successfully duplicated cookies (including XSRF-TOKEN) across all Lib domains off UI thread.");
        }
    }
}
