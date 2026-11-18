package com.example.animelib.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.api.ApiCacheManager;
import com.example.animelib.api.ApiService;
import com.example.animelib.managers.OfflineSyncManager;
import com.example.animelib.models.AnimeInfoResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LatestViewsManager {
    private static final String TAG = "LatestViewsManager";
    private static final String PREF_NAME = "latest_views_pref";
    private static final String KEY_LATEST_VIEWS = "latest_views_json";
    private static final int MAX_LATEST_VIEWS = 50;

    public interface AnimeInfoFetcher {
        @Nullable
        AnimeInfoResponse.Data fetchAnimeInfoSync(@NonNull String animeIdOrSlug);
    }

    private static String cachedJson = null;

    private static final ExecutorService ENRICH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "latest-views-enricher");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> IN_FLIGHT_IDENTIFIERS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean IS_ENRICHING = new AtomicBoolean(false);

    /**
     * Сохраняет или обновляет объект аниме в latest-views.
     * Если аниме уже есть в списке (совпадение по media.id, числовому ID из slug или slug_url),
     * существующие полные метаданные (постер, названия, канонический slug_url) бережно сохраняются.
     */
    public static synchronized void saveLatestView(Context context, JsonObject newEntry) {
        if (context == null || newEntry == null) return;
        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String existingJson = prefs.getString(KEY_LATEST_VIEWS, "[]");
            JsonArray currentArray = parseJsonArraySafe(existingJson);

            JsonArray updatedArray = mergeIntoLatestViewsArray(currentArray, newEntry, MAX_LATEST_VIEWS);

            String updatedJson = updatedArray.toString();
            prefs.edit().putString(KEY_LATEST_VIEWS, updatedJson).apply();
            cachedJson = updatedJson;

            safeLogDebug("Saved latest-view entry successfully. Total items in list: " + updatedArray.size());
        } catch (Exception e) {
            safeLogError("Failed to save latest view", e);
        }
    }

    /**
     * Обновляет локальное хранилище из JavaScript (если сайт сам изменил latest-views).
     * Если сайт прислал запись со старыми/неполными данными для уже обогащённого тайтла,
     * сохранённые полные поля media не затираются (при этом удаление пользователем на сайте уважается).
     */
    public static synchronized void updateFromJS(Context context, String jsonFromJS) {
        if (context == null || jsonFromJS == null || jsonFromJS.trim().isEmpty() || "null".equals(jsonFromJS)) {
            return;
        }
        try {
            String trimmed = jsonFromJS.trim();
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            JsonArray existingArray = parseJsonArraySafe(prefs.getString(KEY_LATEST_VIEWS, "[]"));
            JsonArray jsArray = parseJsonArraySafe(trimmed);

            String finalJson;
            if ("[]".equals(trimmed)) {
                finalJson = "[]";
            } else {
                JsonArray merged = mergeArrayFromJs(existingArray, jsArray, MAX_LATEST_VIEWS);
                finalJson = merged.toString();
            }

            prefs.edit().putString(KEY_LATEST_VIEWS, finalJson).apply();
            cachedJson = finalJson;
            safeLogDebug("Updated latest-views from JS");
        } catch (Exception e) {
            safeLogError("Failed to update latest-views from JS", e);
        }
    }

    /**
     * Возвращает сохранённую строку JSON массива latest-views
     */
    public static synchronized String getLatestViewsJson(Context context) {
        if (cachedJson != null) return cachedJson;
        if (context == null) return "[]";
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        cachedJson = prefs.getString(KEY_LATEST_VIEWS, "[]");
        return cachedJson;
    }

    /**
     * Проверяет, есть ли в latest-views записи без постера, канонического slug_url или названия.
     */
    public static synchronized boolean hasIncompleteEntries(@Nullable Context context) {
        if (context == null) return false;
        JsonArray array = parseJsonArraySafe(getLatestViewsJson(context));
        for (int i = 0; i < array.size(); i++) {
            JsonElement el = array.get(i);
            if (el != null && el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                JsonObject media = getMediaObject(obj);
                if (isIncompleteMedia(media) && resolveEnrichmentIdentifier(media) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Внедряет объект latest-views в localStorage сайта в WebView.
     * Если среди сохранённых записей есть неполные (например, после офлайн-просмотра),
     * дополнительно запускает фоновое дообогащение через кэш/API и повторно синхронизирует WebView.
     */
    public static void injectLatestViewsToWebView(WebView webView) {
        injectLatestViewsToWebViewInternal(webView, true);
    }

    private static void injectLatestViewsToWebViewInternal(@Nullable WebView webView, boolean allowBackgroundEnrich) {
        if (webView == null) return;
        String currentUrl = webView.getUrl();
        if (currentUrl != null && !currentUrl.isEmpty() && !currentUrl.toLowerCase(Locale.ROOT).contains("animelib")) {
            safeLogDebug("Skipping latest-views injection for non-animelib domain page: " + currentUrl);
            return;
        }
        final Context appContext = webView.getContext().getApplicationContext();

        // Быстро применяем кэш AnimeInfo, если он уже есть в памяти, чтобы не отправлять в WebView пустышку
        enrichFromCacheOnly(appContext);

        final String latestViewsJson = getLatestViewsJson(appContext);
        if (latestViewsJson != null && !latestViewsJson.isEmpty()
                && !"[]".equals(latestViewsJson) && !"null".equals(latestViewsJson)) {
            webView.post(() -> {
                try {
                    String jsScript = buildWebViewMergeScript(latestViewsJson);
                    webView.evaluateJavascript(jsScript, null);
                } catch (Exception e) {
                    safeLogError("Error injecting latest-views into WebView", e);
                }
            });
        }

        if (allowBackgroundEnrich && hasIncompleteEntries(appContext)) {
            enrichIncompleteLatestViewsAsync(appContext, webView);
        }
    }

    public static void enrichIncompleteLatestViewsAsync(@Nullable Context context, @Nullable WebView webViewToRefresh) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        ENRICH_EXECUTOR.execute(() -> {
            boolean updated = enrichIncompleteLatestViewsSync(appContext);
            if (updated && webViewToRefresh != null) {
                webViewToRefresh.post(() -> injectLatestViewsToWebViewInternal(webViewToRefresh, false));
            }
        });
    }

    public static boolean enrichIncompleteLatestViewsSync(@Nullable Context context) {
        if (context == null) return false;
        final Context appContext = context.getApplicationContext();
        if (!IS_ENRICHING.compareAndSet(false, true)) {
            return false;
        }
        try {
            // 1. Всегда сначала пробуем обогатить из in-memory кэша (работает даже без сети)
            boolean updated = enrichFromCacheOnly(appContext);

            // 2. Если остались неполные записи и сеть доступна — подтягиваем полную карточку тайтла с API
            if (hasIncompleteEntries(appContext) && OfflineSyncManager.isNetworkAvailable(appContext)) {
                ApiService apiService = new ApiService(appContext);
                boolean remoteUpdated = enrichIncompleteEntriesWithFetcher(
                        appContext,
                        animeIdOrSlug -> {
                            ApiService.AnimeInfoSyncResult res = apiService.fetchAnimeInfoSync(animeIdOrSlug);
                            if (res != null && res.success && res.response != null) {
                                return res.response.getData();
                            }
                            return null;
                        }
                );
                updated = updated || remoteUpdated;
            }
            return updated;
        } catch (Exception e) {
            safeLogError("Error enriching incomplete latest-views", e);
            return false;
        } finally {
            IS_ENRICHING.set(false);
        }
    }

    private static synchronized boolean enrichFromCacheOnly(@NonNull Context appContext) {
        String existingJson = getLatestViewsJson(appContext);
        JsonArray array = parseJsonArraySafe(existingJson);
        boolean changed = false;

        for (int i = 0; i < array.size(); i++) {
            JsonElement el = array.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            JsonObject media = getMediaObject(entry);
            if (!isIncompleteMedia(media)) continue;

            AnimeInfoResponse.Data cachedData = findCachedAnimeInfoData(media);
            if (cachedData != null) {
                JsonObject richMedia = buildMediaObjectFromAnimeInfo(cachedData);
                entry.add("media", mergeMediaObjects(richMedia, media));
                changed = true;
            }
        }

        if (changed) {
            String updatedJson = array.toString();
            SharedPreferences prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_LATEST_VIEWS, updatedJson).apply();
            cachedJson = updatedJson;
            safeLogDebug("Enriched incomplete latest-views entry from in-memory AnimeInfo cache");
        }
        return changed;
    }

    public static boolean enrichIncompleteEntriesWithFetcher(@NonNull Context context,
                                                             @NonNull AnimeInfoFetcher fetcher) {
        final Context appContext = context.getApplicationContext();
        JsonArray snapshot;
        synchronized (LatestViewsManager.class) {
            snapshot = parseJsonArraySafe(getLatestViewsJson(appContext));
        }
        if (snapshot.size() == 0) return false;

        boolean anyUpdated = false;
        for (int i = 0; i < snapshot.size(); i++) {
            JsonElement el = snapshot.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            JsonObject media = getMediaObject(entry);
            if (!isIncompleteMedia(media)) continue;

            String identifier = resolveEnrichmentIdentifier(media);
            if (identifier == null) continue;
            String flightKey = identifier.toLowerCase(Locale.ROOT);
            if (!IN_FLIGHT_IDENTIFIERS.add(flightKey)) {
                continue;
            }
            try {
                AnimeInfoResponse.Data data = fetcher.fetchAnimeInfoSync(identifier);
                if (data != null) {
                    JsonObject richMedia = buildMediaObjectFromAnimeInfo(data);
                    synchronized (LatestViewsManager.class) {
                        SharedPreferences prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                        JsonArray current = parseJsonArraySafe(prefs.getString(KEY_LATEST_VIEWS, "[]"));
                        boolean patched = applyEnrichedMediaToArray(current, media, richMedia);
                        if (patched) {
                            String updatedJson = current.toString();
                            prefs.edit().putString(KEY_LATEST_VIEWS, updatedJson).apply();
                            cachedJson = updatedJson;
                            anyUpdated = true;
                            safeLogDebug("Enriched latest-views entry from API for identifier=" + identifier);
                        }
                    }
                }
            } catch (Exception e) {
                safeLogError("Failed to fetch AnimeInfo for latest-views enrichment: " + identifier, e);
            } finally {
                IN_FLIGHT_IDENTIFIERS.remove(flightKey);
            }
        }
        return anyUpdated;
    }

    // ==================== Pure Testable Helpers ====================

    public static JsonArray mergeIntoLatestViewsArray(@Nullable JsonArray existingArray,
                                                      @NonNull JsonObject newEntry,
                                                      int maxItems) {
        JsonArray source = existingArray != null ? existingArray : new JsonArray();
        JsonObject candidate = newEntry.deepCopy();
        JsonObject matchedExisting = null;
        JsonArray remaining = new JsonArray();

        for (int i = 0; i < source.size(); i++) {
            JsonElement el = source.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject existing = el.getAsJsonObject();
            if (isSameMediaEntry(candidate, existing)) {
                if (matchedExisting == null) {
                    matchedExisting = existing;
                } else {
                    // Если ранее накопились дубли — сливаем их тоже, не теряя обложку/название
                    matchedExisting = mergeEntries(matchedExisting, existing);
                }
            } else {
                remaining.add(existing);
            }
        }

        JsonObject finalEntry = matchedExisting != null
                ? mergeEntries(candidate, matchedExisting)
                : normalizeSingleEntry(candidate);

        JsonArray updatedArray = new JsonArray();
        updatedArray.add(finalEntry);
        int limit = maxItems > 0 ? maxItems : MAX_LATEST_VIEWS;
        for (int i = 0; i < remaining.size() && updatedArray.size() < limit; i++) {
            updatedArray.add(remaining.get(i));
        }
        return updatedArray;
    }

    public static JsonArray mergeArrayFromJs(@Nullable JsonArray existingArray,
                                             @Nullable JsonArray jsArray,
                                             int maxItems) {
        JsonArray srcJs = jsArray != null ? jsArray : new JsonArray();
        JsonArray srcExisting = existingArray != null ? existingArray : new JsonArray();
        JsonArray result = new JsonArray();
        int limit = maxItems > 0 ? maxItems : MAX_LATEST_VIEWS;

        for (int i = 0; i < srcJs.size() && result.size() < limit; i++) {
            JsonElement el = srcJs.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject jsItem = el.getAsJsonObject();

            JsonObject matchedExisting = null;
            for (int j = 0; j < srcExisting.size(); j++) {
                JsonElement exEl = srcExisting.get(j);
                if (exEl != null && exEl.isJsonObject()) {
                    JsonObject exItem = exEl.getAsJsonObject();
                    if (isSameMediaEntry(jsItem, exItem)) {
                        matchedExisting = exItem;
                        break;
                    }
                }
            }

            if (matchedExisting != null) {
                result.add(mergeEntries(jsItem, matchedExisting));
            } else {
                result.add(normalizeSingleEntry(jsItem));
            }
        }
        return result;
    }

    public static boolean applyEnrichedMediaToArray(@Nullable JsonArray array,
                                                    @Nullable JsonObject lookupMedia,
                                                    @NonNull JsonObject richMedia) {
        if (array == null) return false;
        JsonObject lookupWrapper = new JsonObject();
        lookupWrapper.add("media", lookupMedia != null ? lookupMedia : richMedia);
        JsonObject richWrapper = new JsonObject();
        richWrapper.add("media", richMedia);

        boolean changed = false;
        for (int i = 0; i < array.size(); i++) {
            JsonElement el = array.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            if (isSameMediaEntry(entry, richWrapper) || isSameMediaEntry(entry, lookupWrapper)) {
                JsonObject currentMedia = getMediaObject(entry);
                entry.add("media", mergeMediaObjects(richMedia, currentMedia));
                changed = true;
            }
        }
        return changed;
    }

    @NonNull
    public static JsonObject mergeEntries(@NonNull JsonObject newerEntry, @Nullable JsonObject existingEntry) {
        if (existingEntry == null) {
            return normalizeSingleEntry(newerEntry);
        }
        JsonObject result = newerEntry.deepCopy();

        JsonObject newerMedia = getMediaObject(newerEntry);
        JsonObject existingMedia = getMediaObject(existingEntry);
        result.add("media", mergeMediaObjects(newerMedia, existingMedia));

        // Если в офлайн-записи item.id неизвестен (<= 0), а в предыдущей записи есть реальный ID той же серии — сохраняем его
        JsonObject newerItem = getChildObject(result, "item");
        JsonObject existingItem = getChildObject(existingEntry, "item");
        if (newerItem != null && existingItem != null) {
            int newEpId = getInt(newerItem, "id");
            int oldEpId = getInt(existingItem, "id");
            String newNum = getString(newerItem, "number");
            String oldNum = getString(existingItem, "number");
            if (newEpId <= 0 && oldEpId > 0 && !newNum.isEmpty() && newNum.equals(oldNum)) {
                newerItem.addProperty("id", oldEpId);
                JsonObject meta = getChildObject(result, "meta");
                if (meta != null && getInt(meta, "episode") <= 0) {
                    meta.addProperty("episode", oldEpId);
                }
            }
        }

        return result;
    }

    @NonNull
    private static JsonObject normalizeSingleEntry(@NonNull JsonObject entry) {
        JsonObject copy = entry.deepCopy();
        JsonObject media = getMediaObject(copy);
        if (media != null) {
            copy.add("media", mergeMediaObjects(media, null));
        }
        return copy;
    }

    @NonNull
    public static JsonObject mergeMediaObjects(@Nullable JsonObject preferredIncoming, @Nullable JsonObject existingMedia) {
        JsonObject inc = preferredIncoming != null ? preferredIncoming : new JsonObject();
        JsonObject ex = existingMedia != null ? existingMedia : new JsonObject();

        JsonObject out = ex.deepCopy();
        for (String key : inc.keySet()) {
            if ("cover".equals(key) || "name".equals(key) || "rus_name".equals(key)
                    || "eng_name".equals(key) || "slug".equals(key) || "slug_url".equals(key) || "id".equals(key)) {
                continue;
            }
            JsonElement val = inc.get(key);
            if (val != null && !val.isJsonNull()) {
                out.add(key, val.deepCopy());
            }
        }

        // 1. ID
        int incId = getInt(inc, "id");
        int exId = getInt(ex, "id");
        int extractedNumId = Math.max(extractNumericId(inc), extractNumericId(ex));
        int finalId = incId > 0 ? incId : (exId > 0 ? exId : extractedNumId);
        out.addProperty("id", finalId);

        // 2. Cover (никогда не заменяем рабочий постер пустым {} или локальным путём)
        boolean incHasCover = hasValidCover(inc);
        boolean exHasCover = hasValidCover(ex);
        JsonObject mergedCover = new JsonObject();
        if (exHasCover) {
            JsonObject exCover = getChildObject(ex, "cover");
            if (exCover != null) {
                for (String k : exCover.keySet()) {
                    String v = getString(exCover, k);
                    if (!v.isEmpty() && isValidRemoteCoverValue(v)) {
                        mergedCover.addProperty(k, v);
                    }
                }
            }
        }
        if (incHasCover) {
            JsonObject incCover = getChildObject(inc, "cover");
            if (incCover != null) {
                for (String k : incCover.keySet()) {
                    String v = getString(incCover, k);
                    if (!v.isEmpty() && isValidRemoteCoverValue(v)) {
                        mergedCover.addProperty(k, v);
                    }
                }
            }
        }
        out.add("cover", mergedCover);

        // 3. slug_url и slug
        String incSlugUrl = getString(inc, "slug_url");
        String exSlugUrl = getString(ex, "slug_url");
        String finalSlugUrl;
        if (isCanonicalSlugUrl(incSlugUrl)) {
            finalSlugUrl = incSlugUrl;
        } else if (isCanonicalSlugUrl(exSlugUrl)) {
            finalSlugUrl = exSlugUrl;
        } else if (!isGenericOrEmptySlug(incSlugUrl)) {
            finalSlugUrl = incSlugUrl;
        } else if (!isGenericOrEmptySlug(exSlugUrl)) {
            finalSlugUrl = exSlugUrl;
        } else {
            finalSlugUrl = finalId > 0 ? String.valueOf(finalId) : (!incSlugUrl.isEmpty() ? incSlugUrl : exSlugUrl);
        }
        out.addProperty("slug_url", finalSlugUrl);

        String incSlug = getString(inc, "slug");
        String exSlug = getString(ex, "slug");
        String finalSlug = extractSlugSuffix(finalSlugUrl);
        if (finalSlug.isEmpty()) {
            if (!isGenericOrEmptySlug(incSlug) && !incSlug.matches("^\\d+$")) {
                finalSlug = incSlug;
            } else if (!isGenericOrEmptySlug(exSlug) && !exSlug.matches("^\\d+$")) {
                finalSlug = exSlug;
            } else if (!incSlug.isEmpty() && !"anime".equalsIgnoreCase(incSlug)) {
                finalSlug = incSlug;
            } else {
                finalSlug = exSlug;
            }
        }
        out.addProperty("slug", finalSlug);

        // 4. Названия (name, rus_name, eng_name)
        boolean preserveExistingRichTitles = !incHasCover && exHasCover;
        out.addProperty("name", pickBestTitle(getString(inc, "name"), getString(ex, "name"), preserveExistingRichTitles));
        out.addProperty("rus_name", pickBestTitle(getString(inc, "rus_name"), getString(ex, "rus_name"), preserveExistingRichTitles));
        out.addProperty("eng_name", pickBestTitle(getString(inc, "eng_name"), getString(ex, "eng_name"), preserveExistingRichTitles));

        if (!out.has("site") || out.get("site").isJsonNull()) {
            out.addProperty("site", 5);
        }
        if (!out.has("model") || out.get("model").isJsonNull()) {
            out.addProperty("model", "anime");
        }

        return out;
    }

    @NonNull
    public static JsonObject buildMediaObjectFromAnimeInfo(@NonNull AnimeInfoResponse.Data data) {
        JsonObject mediaObj = new JsonObject();
        mediaObj.addProperty("id", data.getId());

        String name = data.getName() != null ? data.getName().trim() : "";
        String rusName = data.getRus_name() != null ? data.getRus_name().trim() : "";
        String engName = data.getEng_name() != null ? data.getEng_name().trim() : "";
        if (name.isEmpty() && !rusName.isEmpty()) {
            name = rusName;
        }

        mediaObj.addProperty("name", name);
        mediaObj.addProperty("rus_name", rusName);
        mediaObj.addProperty("eng_name", engName);

        String slugUrl = data.getSlug_url() != null ? data.getSlug_url().trim() : "";
        if (slugUrl.isEmpty() && data.getId() > 0) {
            slugUrl = String.valueOf(data.getId());
        }
        mediaObj.addProperty("slug_url", slugUrl);
        mediaObj.addProperty("slug", extractSlugSuffix(slugUrl));

        JsonObject coverObj = new JsonObject();
        if (data.getCover() != null) {
            AnimeInfoResponse.Cover c = data.getCover();
            if (isValidRemoteCoverValue(c.getFilename())) coverObj.addProperty("filename", c.getFilename().trim());
            if (isValidRemoteCoverValue(c.getThumbnail())) coverObj.addProperty("thumbnail", c.getThumbnail().trim());
            if (isValidRemoteCoverValue(c.getDefaultUrl())) coverObj.addProperty("default", c.getDefaultUrl().trim());
            if (isValidRemoteCoverValue(c.getMd())) coverObj.addProperty("md", c.getMd().trim());
        }
        mediaObj.add("cover", coverObj);
        mediaObj.addProperty("site", 5);
        mediaObj.addProperty("model", "anime");
        return mediaObj;
    }

    public static boolean isSameMediaEntry(@Nullable JsonObject entryA, @Nullable JsonObject entryB) {
        if (entryA == null || entryB == null) return false;
        JsonObject mediaA = getMediaObject(entryA);
        JsonObject mediaB = getMediaObject(entryB);
        if (mediaA == null || mediaB == null) return false;

        int idA = extractNumericId(mediaA);
        int idB = extractNumericId(mediaB);
        if (idA > 0 && idB > 0 && idA == idB) {
            return true;
        }

        String slugUrlA = getString(mediaA, "slug_url");
        String slugUrlB = getString(mediaB, "slug_url");
        if (!isGenericOrEmptySlug(slugUrlA) && !isGenericOrEmptySlug(slugUrlB)
                && slugUrlA.equalsIgnoreCase(slugUrlB)) {
            return true;
        }

        String slugA = getString(mediaA, "slug");
        String slugB = getString(mediaB, "slug");
        if (!isGenericOrEmptySlug(slugA) && !isGenericOrEmptySlug(slugB)
                && !slugA.matches("^\\d+$") && !slugB.matches("^\\d+$")
                && slugA.equalsIgnoreCase(slugB)) {
            return true;
        }
        return false;
    }

    public static boolean isIncompleteMedia(@Nullable JsonObject media) {
        if (media == null) return true;
        if (!hasValidCover(media)) return true;
        if (extractNumericId(media) <= 0) return true;
        String name = getString(media, "name");
        String rusName = getString(media, "rus_name");
        if (isPlaceholderTitle(name) && isPlaceholderTitle(rusName)) return true;
        String slugUrl = getString(media, "slug_url");
        return !isCanonicalSlugUrl(slugUrl);
    }

    public static boolean hasValidCover(@Nullable JsonObject media) {
        if (media == null) return false;
        JsonObject cover = getChildObject(media, "cover");
        if (cover == null) return false;
        return isValidRemoteCoverValue(getString(cover, "default"))
                || isValidRemoteCoverValue(getString(cover, "md"))
                || isValidRemoteCoverValue(getString(cover, "thumbnail"))
                || isValidRemoteCoverValue(getString(cover, "filename"));
    }

    public static boolean isValidRemoteCoverValue(@Nullable String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty()) return false;
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file://")
                || lower.startsWith("content://")
                || lower.startsWith("/data/")
                || lower.startsWith("/storage/")
                || lower.startsWith("/sdcard/")
                || lower.contains("placeholder")) {
            return false;
        }
        return true;
    }

    public static boolean isPlaceholderTitle(@Nullable String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty() || "—".equals(t) || "-".equals(t)) return true;
        String lower = t.toLowerCase(Locale.ROOT);
        if ("anime".equals(lower) || "аниме".equals(lower)
                || "загрузка".equals(lower) || "загрузка...".equals(lower)) {
            return true;
        }
        return lower.matches("^(аниме|anime)\\s*#\\d+$");
    }

    @Nullable
    public static String resolveEnrichmentIdentifier(@Nullable JsonObject media) {
        if (media == null) return null;
        String slugUrl = getString(media, "slug_url");
        if (!isGenericOrEmptySlug(slugUrl)) {
            return slugUrl;
        }
        int numId = extractNumericId(media);
        if (numId > 0) {
            return String.valueOf(numId);
        }
        String slug = getString(media, "slug");
        if (!isGenericOrEmptySlug(slug)) {
            return slug;
        }
        return null;
    }

    @Nullable
    public static AnimeInfoResponse.Data findCachedAnimeInfoData(@Nullable JsonObject media) {
        if (media == null) return null;
        String slugUrl = getString(media, "slug_url");
        if (!isGenericOrEmptySlug(slugUrl)) {
            AnimeInfoResponse resp = ApiCacheManager.ANIME_INFO.getStale(slugUrl.toLowerCase(Locale.ROOT));
            if (resp != null && resp.getData() != null) return resp.getData();
        }
        int numId = extractNumericId(media);
        if (numId > 0) {
            AnimeInfoResponse resp = ApiCacheManager.ANIME_INFO.getStale(String.valueOf(numId));
            if (resp != null && resp.getData() != null) return resp.getData();
        }
        String slug = getString(media, "slug");
        if (!isGenericOrEmptySlug(slug)) {
            AnimeInfoResponse resp = ApiCacheManager.ANIME_INFO.getStale(slug.toLowerCase(Locale.ROOT));
            if (resp != null && resp.getData() != null) return resp.getData();
        }
        return null;
    }

    @NonNull
    public static String buildWebViewMergeScript(@NonNull String latestViewsJson) {
        return "(function() {" +
            "  try {" +
            "    var newViews = " + latestViewsJson + ";" +
            "    if (!Array.isArray(newViews) || newViews.length === 0) return;" +
            "    var existingStr = localStorage.getItem('latest-views');" +
            "    var list = [];" +
            "    if (existingStr) { try { list = JSON.parse(existingStr); } catch(e) {} }" +
            "    if (!Array.isArray(list)) list = [];" +
            "    function isPlaceholderTitle(s) {" +
            "      if (!s || typeof s !== 'string') return true;" +
            "      var t = s.trim();" +
            "      if (!t || t === '-' || t === '—') return true;" +
            "      return /^(anime|аниме|загрузка\\.{0,3}|аниме\\s*#\\d+|anime\\s*#\\d+)$/i.test(t);" +
            "    }" +
            "    function hasValidCover(c) {" +
            "      if (!c || typeof c !== 'object') return false;" +
            "      var vals = [c.default, c.md, c.thumbnail, c.filename];" +
            "      for (var i = 0; i < vals.length; i++) {" +
            "        var v = vals[i];" +
            "        if (typeof v === 'string') {" +
            "          v = v.trim();" +
            "          var low = v.toLowerCase();" +
            "          if (v && low.indexOf('file://') !== 0 && low.indexOf('content://') !== 0 &&" +
            "              low.indexOf('/data/') !== 0 && low.indexOf('/storage/') !== 0 &&" +
            "              low.indexOf('/sdcard/') !== 0 && low.indexOf('placeholder') === -1) return true;" +
            "        }" +
            "      }" +
            "      return false;" +
            "    }" +
            "    function extractNumericId(m) {" +
            "      if (!m) return 0;" +
            "      if (m.id && parseInt(m.id, 10) > 0) return parseInt(m.id, 10);" +
            "      var s = m.slug_url || m.slug || '';" +
            "      var match = String(s).match(/^(\\d+)/);" +
            "      return match ? parseInt(match[1], 10) : 0;" +
            "    }" +
            "    function isSameMedia(a, b) {" +
            "      if (!a || !b) return false;" +
            "      var idA = extractNumericId(a), idB = extractNumericId(b);" +
            "      if (idA > 0 && idB > 0 && idA === idB) return true;" +
            "      var suA = (a.slug_url || '').toLowerCase(), suB = (b.slug_url || '').toLowerCase();" +
            "      if (suA && suB && suA !== 'anime' && suB !== 'anime' && suA === suB) return true;" +
            "      var sA = (a.slug || '').toLowerCase(), sB = (b.slug || '').toLowerCase();" +
            "      if (sA && sB && sA !== 'anime' && sB !== 'anime' && !/^\\d+$/.test(sA) && sA === sB) return true;" +
            "      return false;" +
            "    }" +
            "    function mergeMedia(incoming, existing) {" +
            "      if (!existing) return incoming;" +
            "      var out = Object.assign({}, existing, incoming);" +
            "      var incCov = hasValidCover(incoming.cover);" +
            "      var exCov = hasValidCover(existing.cover);" +
            "      if (!incCov && exCov) { out.cover = existing.cover; }" +
            "      else if (incCov && exCov) { out.cover = Object.assign({}, existing.cover, incoming.cover); }" +
            "      var incId = (incoming.id && parseInt(incoming.id, 10) > 0) ? parseInt(incoming.id, 10) : 0;" +
            "      var exId = (existing.id && parseInt(existing.id, 10) > 0) ? parseInt(existing.id, 10) : 0;" +
            "      out.id = incId > 0 ? incId : (exId > 0 ? exId : extractNumericId(out));" +
            "      var incSu = incoming.slug_url || '', exSu = existing.slug_url || '';" +
            "      if (incSu.indexOf('--') === -1 && exSu.indexOf('--') !== -1) { out.slug_url = exSu; }" +
            "      else if ((!incSu || incSu === 'anime') && exSu) { out.slug_url = exSu; }" +
            "      var fSu = out.slug_url || '';" +
            "      if (fSu.indexOf('--') !== -1) {" +
            "        out.slug = fSu.split('--').slice(1).join('--') || existing.slug || incoming.slug || '';" +
            "      } else if ((!incoming.slug || incoming.slug === 'anime' || /^\\d+$/.test(incoming.slug)) && existing.slug && existing.slug !== 'anime' && !/^\\d+$/.test(existing.slug)) {" +
            "        out.slug = existing.slug;" +
            "      }" +
            "      ['name', 'rus_name', 'eng_name'].forEach(function(k) {" +
            "        var incV = incoming[k] || '', exV = existing[k] || '';" +
            "        if (exV && !isPlaceholderTitle(exV)) {" +
            "          if (isPlaceholderTitle(incV) || (!incCov && exCov)) { out[k] = exV; }" +
            "        } else if (isPlaceholderTitle(incV)) { out[k] = ''; }" +
            "      });" +
            "      if (!out.site) out.site = 5;" +
            "      if (!out.model) out.model = 'anime';" +
            "      return out;" +
            "    }" +
            "    var updated = false;" +
            "    for (var k = newViews.length - 1; k >= 0; k--) {" +
            "      var newItem = newViews[k];" +
            "      if (!newItem || !newItem.media) continue;" +
            "      var idx = -1;" +
            "      for (var i = 0; i < list.length; i++) {" +
            "        if (list[i] && list[i].media && isSameMedia(newItem.media, list[i].media)) {" +
            "          idx = i; break;" +
            "        }" +
            "      }" +
            "      if (idx !== -1) {" +
            "        var mergedEntry = Object.assign({}, list[idx], newItem);" +
            "        mergedEntry.media = mergeMedia(newItem.media, list[idx].media);" +
            "        if (mergedEntry.item && list[idx].item && (!(mergedEntry.item.id > 0)) && list[idx].item.id > 0 && mergedEntry.item.number === list[idx].item.number) {" +
            "          mergedEntry.item.id = list[idx].item.id;" +
            "          if (mergedEntry.meta && !(mergedEntry.meta.episode > 0)) mergedEntry.meta.episode = list[idx].item.id;" +
            "        }" +
            "        list[idx] = mergedEntry;" +
            "        updated = true;" +
            "      } else {" +
            "        var cleanItem = Object.assign({}, newItem);" +
            "        cleanItem.media = mergeMedia(newItem.media, null);" +
            "        list.unshift(cleanItem);" +
            "        updated = true;" +
            "      }" +
            "    }" +
            "    if (updated) {" +
            "      if (list.length > 50) list = list.slice(0, 50);" +
            "      localStorage.setItem('latest-views', JSON.stringify(list));" +
            "      console.log('[LatestViewsManager] Synced latest-views into localStorage');" +
            "    }" +
            "  } catch(e) {" +
            "    console.error('[LatestViewsManager] Error injecting latest-views:', e);" +
            "  }" +
            "})();";
    }

    private static String pickBestTitle(String incVal, String exVal, boolean preferExistingRich) {
        boolean incPlaceholder = isPlaceholderTitle(incVal);
        boolean exPlaceholder = isPlaceholderTitle(exVal);
        if (!exPlaceholder) {
            if (incPlaceholder || preferExistingRich) {
                return exVal.trim();
            }
            return incVal.trim();
        }
        return !incPlaceholder ? incVal.trim() : "";
    }

    private static boolean isCanonicalSlugUrl(@Nullable String slugUrl) {
        if (slugUrl == null) return false;
        String s = slugUrl.trim();
        int idx = s.indexOf("--");
        return idx > 0 && idx + 2 < s.length();
    }

    private static boolean isGenericOrEmptySlug(@Nullable String slug) {
        if (slug == null) return true;
        String s = slug.trim();
        return s.isEmpty() || "anime".equalsIgnoreCase(s);
    }

    @NonNull
    private static String extractSlugSuffix(@Nullable String slugUrlOrSlug) {
        if (slugUrlOrSlug == null) return "";
        String s = slugUrlOrSlug.trim();
        int idx = s.indexOf("--");
        if (idx >= 0 && idx + 2 < s.length()) {
            return s.substring(idx + 2).trim();
        }
        if (!s.isEmpty() && !"anime".equalsIgnoreCase(s) && !s.matches("^\\d+$")) {
            return s;
        }
        return "";
    }

    public static int extractNumericId(@Nullable JsonObject media) {
        if (media == null) return 0;
        int directId = getInt(media, "id");
        if (directId > 0) return directId;

        String slugUrl = getString(media, "slug_url");
        String numFromSlugUrl = OfflineSyncManager.extractNumericAnimeId(slugUrl);
        if (numFromSlugUrl != null) {
            try { return Integer.parseInt(numFromSlugUrl); } catch (NumberFormatException ignored) {}
        }

        String slug = getString(media, "slug");
        String numFromSlug = OfflineSyncManager.extractNumericAnimeId(slug);
        if (numFromSlug != null) {
            try { return Integer.parseInt(numFromSlug); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    @NonNull
    public static JsonArray parseJsonArraySafe(@Nullable String json) {
        if (json == null || json.trim().isEmpty() || "null".equals(json.trim())) {
            return new JsonArray();
        }
        try {
            JsonElement elem = JsonParser.parseString(json);
            if (elem != null && elem.isJsonArray()) {
                return elem.getAsJsonArray();
            }
        } catch (Exception ignored) {}
        return new JsonArray();
    }

    @Nullable
    private static JsonObject getMediaObject(@Nullable JsonObject obj) {
        return getChildObject(obj, "media");
    }

    @Nullable
    private static JsonObject getChildObject(@Nullable JsonObject obj, @NonNull String key) {
        try {
            if (obj != null && obj.has(key) && obj.get(key) != null && obj.get(key).isJsonObject()) {
                return obj.getAsJsonObject(key);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int getInt(@Nullable JsonObject obj, @NonNull String key) {
        try {
            if (obj != null && obj.has(key) && obj.get(key) != null && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsInt();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    @NonNull
    private static String getString(@Nullable JsonObject obj, @NonNull String key) {
        try {
            if (obj != null && obj.has(key) && obj.get(key) != null && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsString().trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static void safeLogDebug(String msg) {
        try { Log.d(TAG, msg); } catch (RuntimeException ignored) {}
    }

    private static void safeLogError(String msg, @Nullable Throwable t) {
        try {
            if (t != null) Log.e(TAG, msg, t);
            else Log.e(TAG, msg);
        } catch (RuntimeException ignored) {}
    }
}
