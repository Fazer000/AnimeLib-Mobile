package com.example.animelib.api;

import android.util.Log;

import com.example.animelib.models.KodikResponse;
import com.example.animelib.util.AutoQualityHelper;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Надёжный и быстрый экстрактор прямых HLS/MP4 ссылок Kodik.
 *
 * Особенности:
 * - Корректный POST-запрос с параметрами сессии (urlParams / videoInfo) и cookies + fallback на GET.
 * - Автоматический поиск актуального endpoint (/ftor, /kor, /gvi и т.д.) как в HTML, так и в JS-бандле плеера
 *   с потокобезопасным кэшированием.
 * - Автоматическое переключение между зеркалами Kodik (kodikplayer.com, aniqit.com, kodik.info, kodik.cc)
 *   при таймаутах, сбросах соединений или блокировках домена.
 * - Самовосстанавливающееся декодирование шифра (ROT18 по умолчанию + авто-подбор сдвига 0..25 + поддержка прямых URL).
 */
public class KodikLinksExtractor {

    private static final String TAG = "KodikExtractor";
    private static final String DEFAULT_PLAYER_DOMAIN = "kodikplayer.com";
    private static final String DEFAULT_ENDPOINT = "/ftor";
    private static final int DEFAULT_ROT_SHIFT = 18;

    private static final List<String> FALLBACK_HOSTS = Arrays.asList(
            "kodikplayer.com",
            "aniqit.com",
            "kodik.info",
            "kodik.cc"
    );

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36";

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(?i)^(?:https?:)?//(?<host>[a-z0-9.\\-]+\\.[a-z]{2,})/(?<type>[a-z0-9_\\-]+)/(?<id>\\d+)/(?<hash>[0-9a-z]+)(?:/(?<quality>\\d+p?))?(?:[?#].*)?$");

    private static final List<Pattern> ENDPOINT_PATTERNS = Arrays.asList(
            Pattern.compile("type\\s*:\\s*[\"']POST[\"']\\s*,\\s*url\\s*:\\s*atob\\([\"']([^\"']+)[\"']\\)"),
            Pattern.compile("url\\s*:\\s*atob\\([\"']([^\"']+)[\"']\\)\\s*,\\s*type\\s*:\\s*[\"']POST[\"']"),
            Pattern.compile("\\$\\.ajax\\(\\{[^}]*url\\s*:\\s*atob\\([\"']([^\"']+)[\"']\\)")
    );

    private static final Pattern SCRIPT_SRC_PATTERN = Pattern.compile(
            "(?i)<script[^>]+src=[\"']([^\"']*app\\.(?:player_single|serial)[^\"']*\\.js[^\"']*)[\"']");

    private static final Pattern URL_PARAMS_SINGLE_QUOTE_PATTERN = Pattern.compile(
            "urlParams\\s*=\\s*'(\\{[^']+\\})'");
    private static final Pattern URL_PARAMS_DOUBLE_QUOTE_PATTERN = Pattern.compile(
            "urlParams\\s*=\\s*\"(\\{(?:\\\\.|[^\"\\\\])+\\})\"");

    private static final Pattern VIDEO_TYPE_PATTERN = Pattern.compile(
            "(?:videoInfo\\.type|vInfo\\.type)\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern VIDEO_HASH_PATTERN = Pattern.compile(
            "(?:videoInfo\\.hash|vInfo\\.hash)\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "(?:videoInfo\\.id|vInfo\\.id)\\s*=\\s*['\"]?(\\d+)['\"]?");

    private static final Pattern VAR_DOMAIN_PATTERN = Pattern.compile("var\\s+domain\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern VAR_D_SIGN_PATTERN = Pattern.compile("var\\s+d_sign\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern VAR_PD_PATTERN = Pattern.compile("var\\s+pd\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern VAR_PD_SIGN_PATTERN = Pattern.compile("var\\s+pd_sign\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern VAR_REF_PATTERN = Pattern.compile("var\\s+ref\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern VAR_REF_SIGN_PATTERN = Pattern.compile("var\\s+ref_sign\\s*=\\s*[\"']([^\"']*)[\"']");

    private static final Pattern ROT_SHIFT_PATTERN = Pattern.compile(
            "charCodeAt\\(0\\)\\s*\\+\\s*(\\d{1,2})");

    // Глобальный кэш динамических параметров плеера Kodik
    private static volatile String cachedEndpoint = DEFAULT_ENDPOINT;
    private static volatile int cachedRotShift = DEFAULT_ROT_SHIFT;
    private static volatile String lastWorkingHost = null;
    private static final Map<String, String> SCRIPT_ENDPOINT_CACHE = new ConcurrentHashMap<>();

    private final OkHttpClient httpClient;
    private final Gson gson;

    public KodikLinksExtractor(OkHttpClient baseClient, Gson gson) {
        this.gson = gson != null ? gson : new Gson();
        OkHttpClient sourceClient = baseClient != null ? baseClient : new OkHttpClient();
        this.httpClient = sourceClient.newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .cookieJar(new InMemoryCookieJar())
                .build();
    }

    /**
     * Канонизирует ключ кэша для ссылки Kodik (игнорирует разницу между // и https://).
     */
    public static String normalizeCacheKey(String kodikLink) {
        if (kodikLink == null) return "";
        String normalized = normalizeLinkStatic(kodikLink.trim());
        int hashIdx = normalized.indexOf('#');
        if (hashIdx >= 0) {
            normalized = normalized.substring(0, hashIdx);
        }
        return normalized;
    }

    /**
     * Возвращает ссылки по качествам для ссылки на плеер Kodik.
     */
    public KodikResponse getLinks(String kodikLink) throws IOException {
        return getLinks(kodikLink, false);
    }

    /**
     * Возвращает ссылки по качествам с поддержкой принудительного обхода устаревших кэшей endpoint/хоста.
     */
    public KodikResponse getLinks(String kodikLink, boolean forceRefresh) throws IOException {
        String link = normalizeLink(kodikLink);
        ParsedKodikLink parsed = parseKodikLink(link);
        if (parsed == null) {
            throw new IOException("Ссылка Kodik не распознана: " + kodikLink);
        }

        List<String> candidateHosts = buildCandidateHosts(parsed.host, forceRefresh);
        IOException lastException = null;

        for (int i = 0; i < candidateHosts.size(); i++) {
            String targetHost = candidateHosts.get(i);
            try {
                KodikResponse response = extractFromHost(parsed, targetHost, forceRefresh);
                if (response != null && response.isSuccess() && response.getData() != null && !response.getData().isEmpty()) {
                    lastWorkingHost = targetHost;
                    return response;
                }
            } catch (IOException e) {
                lastException = e;
                logWarn("Host " + targetHost + " failed: " + e.getMessage());
            } catch (Exception e) {
                lastException = new IOException("Ошибка обработки ответа от " + targetHost + ": " + e.getMessage(), e);
                logWarn("Unexpected error on host " + targetHost + ": " + e.getMessage());
            }

            // Короткая пауза перед переключением на следующее зеркало
            if (i < candidateHosts.size() - 1) {
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("Kodik не вернул доступные видеоссылки ни с одного зеркала");
    }

    private KodikResponse extractFromHost(ParsedKodikLink parsed, String host, boolean forceRefresh) throws IOException {
        String iframeUrl = parsed.buildIframeUrl(host);
        String currentEndpoint = cachedEndpoint;

        // 1. Быстрый путь (1 RTT): прямой запрос к текущему известному endpoint (пропускается при forceRefresh, чтобы гарантированно обновить d_sign/cookies/endpoint)
        if (!forceRefresh) {
            Map<String, String> fastParams = buildBaseFormParams(parsed, host);
            JsonObject fastPayload = requestVideoInfo(host, currentEndpoint, iframeUrl, fastParams, true);
            KodikResponse fastResponse = toValidResponse(fastPayload);
            if (fastResponse != null) {
                return fastResponse;
            }
        }

        // 2. Полный путь: загружаем страницу iframe (получаем редирект-домен, cookies, подписи d_sign/pd_sign и JS-скрипт)
        IframeContext iframeCtx = fetchIframeContext(iframeUrl, host, parsed, forceRefresh);
        String effectiveHost = iframeCtx.effectiveHost;
        String effectiveEndpoint = iframeCtx.endpoint;

        JsonObject fullPayload = requestVideoInfo(
                effectiveHost,
                effectiveEndpoint,
                iframeCtx.effectiveIframeUrl,
                iframeCtx.formParams,
                true
        );
        KodikResponse fullResponse = toValidResponse(fullPayload);
        if (fullResponse != null) {
            cachedEndpoint = effectiveEndpoint;
            return fullResponse;
        }

        // 3. Fallback на GET-запрос (для старых/нестандартных нод Kodik)
        JsonObject getPayload = requestVideoInfo(
                effectiveHost,
                effectiveEndpoint,
                iframeCtx.effectiveIframeUrl,
                iframeCtx.formParams,
                false
        );
        KodikResponse getResponse = toValidResponse(getPayload);
        if (getResponse != null) {
            cachedEndpoint = effectiveEndpoint;
            return getResponse;
        }

        // Если ни актуальный endpoint, ни DEFAULT_ENDPOINT не совпали — проверим DEFAULT_ENDPOINT с полными параметрами
        if (!DEFAULT_ENDPOINT.equals(effectiveEndpoint)) {
            JsonObject fallbackPayload = requestVideoInfo(
                    effectiveHost,
                    DEFAULT_ENDPOINT,
                    iframeCtx.effectiveIframeUrl,
                    iframeCtx.formParams,
                    true
            );
            KodikResponse fallbackResponse = toValidResponse(fallbackPayload);
            if (fallbackResponse != null) {
                cachedEndpoint = DEFAULT_ENDPOINT;
                return fallbackResponse;
            }
        }

        return null;
    }

    private KodikResponse toValidResponse(JsonObject payload) {
        if (payload == null || !payload.has("links") || !payload.get("links").isJsonObject()) {
            return null;
        }
        KodikResponse response = buildResponse(payload.getAsJsonObject("links"));
        return (response.isSuccess() && response.getData() != null && !response.getData().isEmpty())
                ? response : null;
    }

    private List<String> buildCandidateHosts(String primaryHost, boolean forceRefresh) {
        Set<String> hosts = new LinkedHashSet<>();
        if (primaryHost != null && !primaryHost.isEmpty()) {
            hosts.add(primaryHost.toLowerCase(Locale.ROOT));
        }
        String remembered = lastWorkingHost;
        if (!forceRefresh && remembered != null && !remembered.isEmpty()) {
            hosts.add(remembered.toLowerCase(Locale.ROOT));
        }
        hosts.addAll(FALLBACK_HOSTS);
        return new ArrayList<>(hosts);
    }

    private Map<String, String> buildBaseFormParams(ParsedKodikLink parsed, String host) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("d", parsed.queryParams.getOrDefault("d", "kodik.info"));
        if (parsed.queryParams.containsKey("d_sign")) {
            params.put("d_sign", parsed.queryParams.get("d_sign"));
        }
        params.put("pd", parsed.queryParams.getOrDefault("pd", host));
        if (parsed.queryParams.containsKey("pd_sign")) {
            params.put("pd_sign", parsed.queryParams.get("pd_sign"));
        }
        params.put("ref", parsed.queryParams.getOrDefault("ref", ""));
        if (parsed.queryParams.containsKey("ref_sign")) {
            params.put("ref_sign", parsed.queryParams.get("ref_sign"));
        }
        params.put("bad_user", "true");
        params.put("cdn_is_working", "true");
        params.put("type", parsed.type);
        params.put("id", parsed.id);
        params.put("hash", parsed.hash);
        params.put("info", "{}");
        return params;
    }

    /**
     * Запрашивает информацию о видео (POST по протоколу плеера Kodik или GET в режиме совместимости).
     */
    private JsonObject requestVideoInfo(
            String host,
            String endpoint,
            String refererUrl,
            Map<String, String> params,
            boolean usePost
    ) throws IOException {
        String normalizedEndpoint = (endpoint == null || endpoint.isEmpty())
                ? DEFAULT_ENDPOINT
                : (endpoint.startsWith("/") ? endpoint : "/" + endpoint);

        String baseUrl = "https://" + host + normalizedEndpoint;
        String origin = "https://" + host;
        String effectiveReferer = (refererUrl != null && !refererUrl.isEmpty())
                ? refererUrl
                : (origin + "/");

        Request.Builder reqBuilder = new Request.Builder()
                .header("User-Agent", USER_AGENT)
                .header("Referer", effectiveReferer)
                .header("Origin", origin)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("X-Requested-With", "XMLHttpRequest");

        if (usePost) {
            FormBody.Builder formBuilder = new FormBody.Builder(StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    formBuilder.add(entry.getKey(), entry.getValue());
                }
            }
            reqBuilder.url(baseUrl).post(formBuilder.build());
        } else {
            HttpUrl parsedUrl = HttpUrl.parse(baseUrl);
            if (parsedUrl == null) {
                return null;
            }
            HttpUrl.Builder urlBuilder = parsedUrl.newBuilder();
            urlBuilder.addQueryParameter("hash", params.getOrDefault("hash", ""));
            urlBuilder.addQueryParameter("id", params.getOrDefault("id", ""));
            urlBuilder.addQueryParameter("type", params.getOrDefault("type", ""));
            if (params.containsKey("d")) urlBuilder.addQueryParameter("d", params.get("d"));
            if (params.containsKey("d_sign")) urlBuilder.addQueryParameter("d_sign", params.get("d_sign"));
            if (params.containsKey("pd")) urlBuilder.addQueryParameter("pd", params.get("pd"));
            if (params.containsKey("pd_sign")) urlBuilder.addQueryParameter("pd_sign", params.get("pd_sign"));
            reqBuilder.url(urlBuilder.build()).get();
        }

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                logDebug("Video info HTTP " + response.code() + " (" + (usePost ? "POST" : "GET") + ") on " + baseUrl);
                return null;
            }
            String body = response.body().string().trim();
            if (!body.startsWith("{")) {
                return null;
            }
            return gson.fromJson(body, JsonObject.class);
        } catch (com.google.gson.JsonSyntaxException jse) {
            logWarn("Invalid JSON from " + baseUrl + ": " + jse.getMessage());
            return null;
        }
    }

    /**
     * Загружает HTML страницы плеера, извлекает актуальный домен после редиректов,
     * подписанные параметры urlParams / videoInfo и актуальный API endpoint из бандла плеера.
     */
    private IframeContext fetchIframeContext(
            String iframeUrl,
            String initialHost,
            ParsedKodikLink parsed,
            boolean forceRefresh
    ) throws IOException {
        Request request = new Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://animelib.org/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .build();

        String html;
        String effectiveHost = initialHost;
        String effectiveIframeUrl = iframeUrl;

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Страница плеера Kodik вернула HTTP " + response.code());
            }
            HttpUrl finalUrl = response.request().url();
            if (finalUrl != null) {
                effectiveHost = finalUrl.host();
                effectiveIframeUrl = finalUrl.toString();
            }
            html = response.body().string();
        }

        Map<String, String> params = buildBaseFormParams(parsed, effectiveHost);

        // Парсим JSON urlParams со страницы плеера (поддерживаем как одинарные кавычки вокруг JSON, так и экранированные двойные)
        String rawUrlParams = null;
        Matcher singleQuoteMatcher = URL_PARAMS_SINGLE_QUOTE_PATTERN.matcher(html);
        if (singleQuoteMatcher.find()) {
            rawUrlParams = singleQuoteMatcher.group(1);
        } else {
            Matcher doubleQuoteMatcher = URL_PARAMS_DOUBLE_QUOTE_PATTERN.matcher(html);
            if (doubleQuoteMatcher.find()) {
                rawUrlParams = doubleQuoteMatcher.group(1).replace("\\\"", "\"");
            }
        }
        if (rawUrlParams != null) {
            try {
                JsonObject urlParamsJson = gson.fromJson(rawUrlParams, JsonObject.class);
                if (urlParamsJson != null) {
                    for (Map.Entry<String, JsonElement> entry : urlParamsJson.entrySet()) {
                        if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                            params.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Парсим отдельные JS-переменные на случай отсутствия urlParams
        putIfMatched(VAR_DOMAIN_PATTERN, html, "d", params);
        putIfMatched(VAR_D_SIGN_PATTERN, html, "d_sign", params);
        putIfMatched(VAR_PD_PATTERN, html, "pd", params);
        putIfMatched(VAR_PD_SIGN_PATTERN, html, "pd_sign", params);
        putIfMatched(VAR_REF_PATTERN, html, "ref", params);
        putIfMatched(VAR_REF_SIGN_PATTERN, html, "ref_sign", params);

        putIfMatched(VIDEO_TYPE_PATTERN, html, "type", params);
        putIfMatched(VIDEO_ID_PATTERN, html, "id", params);
        putIfMatched(VIDEO_HASH_PATTERN, html, "hash", params);

        // Пробуем найти endpoint прямо в HTML
        String discoveredEndpoint = extractEndpointFromText(html);

        // Если в HTML нет — извлекаем из подключенного JS-скрипта плеера (app.player_single.*.js / app.serial.*.js)
        if (discoveredEndpoint == null) {
            Matcher scriptMatcher = SCRIPT_SRC_PATTERN.matcher(html);
            if (scriptMatcher.find()) {
                String scriptPath = scriptMatcher.group(1);
                String scriptUrl = resolveAgainstHost(scriptPath, effectiveHost);
                discoveredEndpoint = fetchEndpointFromPlayerScript(scriptUrl, effectiveIframeUrl, forceRefresh);
            }
        }

        if (discoveredEndpoint == null || discoveredEndpoint.isEmpty()) {
            discoveredEndpoint = cachedEndpoint != null ? cachedEndpoint : DEFAULT_ENDPOINT;
        }

        return new IframeContext(effectiveHost, effectiveIframeUrl, discoveredEndpoint, params);
    }

    private String fetchEndpointFromPlayerScript(String scriptUrl, String referer, boolean forceRefresh) {
        if (scriptUrl == null || scriptUrl.isEmpty()) {
            return null;
        }
        if (!forceRefresh) {
            String cached = SCRIPT_ENDPOINT_CACHE.get(scriptUrl);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        Request request = new Request.Builder()
                .url(scriptUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "*/*")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String js = response.body().string();
            Matcher shiftMatcher = ROT_SHIFT_PATTERN.matcher(js);
            if (shiftMatcher.find()) {
                try {
                    int shift = Integer.parseInt(shiftMatcher.group(1));
                    if (shift >= 0 && shift < 26) {
                        cachedRotShift = shift;
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            String endpoint = extractEndpointFromText(js);
            if (endpoint != null && !endpoint.isEmpty()) {
                SCRIPT_ENDPOINT_CACHE.put(scriptUrl, endpoint);
                cachedEndpoint = endpoint;
                return endpoint;
            }
        } catch (Exception e) {
            logWarn("Failed to inspect Kodik player script: " + e.getMessage());
        }
        return null;
    }

    static String extractEndpointFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        for (Pattern pattern : ENDPOINT_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String decoded = decodeBase64Clean(matcher.group(1));
                if (!decoded.isEmpty() && decoded.startsWith("/")) {
                    return decoded;
                }
            }
        }
        return null;
    }

    private static void putIfMatched(Pattern pattern, String text, String targetKey, Map<String, String> out) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.isEmpty()) {
                out.put(targetKey, value);
            }
        }
    }

    private static String resolveAgainstHost(String urlOrPath, String host) {
        if (urlOrPath == null || urlOrPath.isEmpty()) return "";
        if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            return urlOrPath;
        }
        if (urlOrPath.startsWith("//")) {
            return "https:" + urlOrPath;
        }
        return "https://" + host + (urlOrPath.startsWith("/") ? urlOrPath : "/" + urlOrPath);
    }

    private String normalizeLink(String link) {
        return normalizeLinkStatic(link);
    }

    private static String normalizeLinkStatic(String link) {
        if (link == null || link.trim().isEmpty()) {
            return "";
        }
        String trimmed = link.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + DEFAULT_PLAYER_DOMAIN + (trimmed.startsWith("/") ? trimmed : "/" + trimmed);
    }

    /**
     * Собирает KodikResponse из карты качеств, расшифровывая и валидируя все зеркала.
     */
    KodikResponse buildResponse(JsonObject links) {
        Map<String, KodikResponse.VideoQuality[]> data = new HashMap<>();

        if (links != null) {
            for (String quality : links.keySet()) {
                KodikResponse.VideoQuality[] parsed;
                try {
                    parsed = gson.fromJson(links.get(quality), KodikResponse.VideoQuality[].class);
                } catch (Exception e) {
                    continue;
                }
                if (parsed == null || parsed.length == 0) {
                    continue;
                }

                List<KodikResponse.VideoQuality> validItems = new ArrayList<>(parsed.length);
                Set<String> seenUrls = new LinkedHashSet<>();

                for (KodikResponse.VideoQuality item : parsed) {
                    if (item == null || item.getSrc() == null || item.getSrc().trim().isEmpty()) {
                        continue;
                    }
                    String decodedUrl = decodeKodikSrc(item.getSrc());
                    if (decodedUrl != null && !decodedUrl.isEmpty() && seenUrls.add(decodedUrl)) {
                        KodikResponse.VideoQuality cleanItem = new KodikResponse.VideoQuality();
                        cleanItem.setSrc(decodedUrl);
                        cleanItem.setType(item.getType());
                        validItems.add(cleanItem);
                    }
                }

                if (!validItems.isEmpty()) {
                    data.put(quality, validItems.toArray(new KodikResponse.VideoQuality[0]));
                }
            }
        }

        KodikResponse response = new KodikResponse();
        response.setSuccess(!data.isEmpty());
        response.setData(data);
        return response;
    }

    /**
     * Расшифровывает значение поля src из ответа Kodik:
     * поддерживает уже открытые ссылки, текущий сдвиг Цезаря (по умолчанию ROT18) и авто-подбор сдвига 0..25.
     */
    public static String decodeKodikSrc(String rawSrc) {
        if (rawSrc == null) return "";
        String trimmed = rawSrc.trim();
        if (trimmed.isEmpty()) return "";

        // Если ссылка уже в открытом виде (встречается на старых релизах Kodik)
        if (isPlausibleMediaUrl(trimmed)) {
            return normalizeDecodedMediaUrl(trimmed);
        }

        // 1. Пробуем актуальный сдвиг (обычно ROT18)
        int preferredShift = cachedRotShift;
        String candidate = decodeBase64Clean(caesarShift(trimmed, preferredShift));
        if (isPlausibleMediaUrl(candidate)) {
            return normalizeDecodedMediaUrl(candidate);
        }

        // 2. Авто-подбор сдвига на случай смены ROT18 -> ROT13 / другой сдвиг на стороне Kodik
        for (int shift = 0; shift < 26; shift++) {
            if (shift == preferredShift) continue;
            String test = decodeBase64Clean(caesarShift(trimmed, shift));
            if (isPlausibleMediaUrl(test)) {
                cachedRotShift = shift;
                return normalizeDecodedMediaUrl(test);
            }
        }

        return "";
    }

    static boolean isPlausibleMediaUrl(String value) {
        if (value == null || value.length() < 8) return false;
        String v = value.trim();
        // Должны отсутствовать управляющие бинарные символы
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\uFFFD') {
                return false;
            }
        }
        boolean hasUrlPrefix = v.startsWith("//") || v.startsWith("https://") || v.startsWith("http://");
        boolean hasMediaHint = v.contains(".m3u8") || v.contains(".mp4") || v.contains("kodik") || v.contains("/useruploads/");
        if (hasUrlPrefix && v.indexOf('.', 2) > 0) {
            return true;
        }
        return hasMediaHint && v.contains("/") && v.indexOf('.') > 0;
    }

    static String normalizeDecodedMediaUrl(String decodedUrl) {
        if (decodedUrl == null) return "";
        String clean = decodedUrl.trim();
        if (clean.isEmpty()) return "";

        if (clean.startsWith("//")) {
            clean = "https:" + clean;
        } else if (clean.startsWith("/")) {
            clean = "https://kodik.info" + clean;
        } else if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://" + clean;
        }

        // Защита от пустого хоста вида "https:" или "https:///"
        if (clean.length() <= 10 || clean.startsWith("https:///") || clean.startsWith("http:///")) {
            return "";
        }
        return clean;
    }

    /**
     * Собирает упорядоченный список всех доступных рабочих URL из KodikResponse:
     * сначала все зеркала целевого качества, затем резервные качества по близости разрешения.
     */
    public static List<String> collectCandidateUrls(KodikResponse response, String effectiveQuality, boolean enable4K) {
        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            return Collections.emptyList();
        }

        int targetRes = AutoQualityHelper.extractResolution(effectiveQuality);
        if (targetRes <= 0) {
            targetRes = 720;
        }

        List<String> keys = new ArrayList<>(response.getData().keySet());
        final int finalTargetRes = targetRes;
        keys.sort((k1, k2) -> {
            int r1 = AutoQualityHelper.extractResolution(k1);
            int r2 = AutoQualityHelper.extractResolution(k2);
            boolean m1 = (r1 == finalTargetRes) || k1.equalsIgnoreCase(effectiveQuality) || (k1 + "p").equalsIgnoreCase(effectiveQuality);
            boolean m2 = (r2 == finalTargetRes) || k2.equalsIgnoreCase(effectiveQuality) || (k2 + "p").equalsIgnoreCase(effectiveQuality);
            if (m1 != m2) return m1 ? -1 : 1;

            // При прочих равных предпочитаем понижение качества (480p после 720p грузится стабильнее, чем 1080p)
            boolean lowerOrEqual1 = r1 <= finalTargetRes;
            boolean lowerOrEqual2 = r2 <= finalTargetRes;
            if (lowerOrEqual1 != lowerOrEqual2) return lowerOrEqual1 ? -1 : 1;

            int diff1 = Math.abs(finalTargetRes - r1);
            int diff2 = Math.abs(finalTargetRes - r2);
            if (diff1 != diff2) return Integer.compare(diff1, diff2);
            return Integer.compare(r2, r1);
        });

        Set<String> orderedUrls = new LinkedHashSet<>();
        for (String key : keys) {
            int res = AutoQualityHelper.extractResolution(key);
            if (res == 2160 && !enable4K) {
                continue;
            }
            KodikResponse.VideoQuality[] items = response.getData().get(key);
            if (items == null) continue;
            for (KodikResponse.VideoQuality item : items) {
                if (item != null && item.getSrc() != null) {
                    String normalized = normalizeDecodedMediaUrl(item.getSrc());
                    if (!normalized.isEmpty()) {
                        orderedUrls.add(normalized);
                    }
                }
            }
        }
        return new ArrayList<>(orderedUrls);
    }

    /**
     * Сдвигает латинские буквы на shift позиций внутри своего регистра.
     */
    static String caesarShift(String value, int shift) {
        if (shift % 26 == 0) return value;
        int normalizedShift = ((shift % 26) + 26) % 26;
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char symbol = value.charAt(i);
            if (symbol >= 'a' && symbol <= 'z') {
                int shifted = symbol + normalizedShift;
                result.append((char) (shifted <= 'z' ? shifted : shifted - 26));
            } else if (symbol >= 'A' && symbol <= 'Z') {
                int shifted = symbol + normalizedShift;
                result.append((char) (shifted <= 'Z' ? shifted : shifted - 26));
            } else {
                result.append(symbol);
            }
        }
        return result.toString();
    }

    /**
     * Декодирует стандартный или URL-safe Base64 с автоматическим выравниванием padding.
     */
    static String decodeBase64Clean(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            String cleaned = value.trim()
                    .replace('-', '+')
                    .replace('_', '/')
                    .replaceAll("\\s+", "");
            int eqIndex = cleaned.indexOf('=');
            if (eqIndex > 0) {
                cleaned = cleaned.substring(0, eqIndex);
            }
            int mod = cleaned.length() % 4;
            if (mod == 1) {
                return "";
            }
            StringBuilder padded = new StringBuilder(cleaned);
            while (padded.length() % 4 != 0) {
                padded.append('=');
            }
            byte[] decoded = Base64.getDecoder().decode(padded.toString());
            return new String(decoded, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    static ParsedKodikLink parseKodikLink(String normalizedLink) {
        if (normalizedLink == null || normalizedLink.isEmpty()) return null;
        Matcher matcher = LINK_PATTERN.matcher(normalizedLink);
        if (!matcher.find()) {
            return null;
        }
        String host = matcher.group("host").toLowerCase(Locale.ROOT);
        String type = matcher.group("type");
        String id = matcher.group("id");
        String hash = matcher.group("hash");
        String quality = matcher.group("quality");
        if (quality == null || quality.isEmpty()) {
            quality = "720p";
        } else if (!quality.endsWith("p")) {
            quality = quality + "p";
        }

        Map<String, String> queryParams = new LinkedHashMap<>();
        String rawQuery = null;
        try {
            URI uri = URI.create(normalizedLink);
            rawQuery = uri.getRawQuery();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                for (String pair : rawQuery.split("&")) {
                    int idx = pair.indexOf('=');
                    if (idx > 0) {
                        String k = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                        String v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                        queryParams.put(k, v);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return new ParsedKodikLink(host, type, id, hash, quality, rawQuery, queryParams);
    }

    static final class ParsedKodikLink {
        final String host;
        final String type;
        final String id;
        final String hash;
        final String quality;
        final String rawQuery;
        final Map<String, String> queryParams;

        ParsedKodikLink(String host, String type, String id, String hash, String quality,
                        String rawQuery, Map<String, String> queryParams) {
            this.host = host;
            this.type = type;
            this.id = id;
            this.hash = hash;
            this.quality = quality;
            this.rawQuery = rawQuery;
            this.queryParams = queryParams;
        }

        String buildIframeUrl(String targetHost) {
            String base = "https://" + targetHost + "/" + type + "/" + id + "/" + hash + "/" + quality;
            if (rawQuery != null && !rawQuery.isEmpty() && targetHost.equalsIgnoreCase(host)) {
                return base + "?" + rawQuery;
            }
            return base;
        }
    }

    private static final class IframeContext {
        final String effectiveHost;
        final String effectiveIframeUrl;
        final String endpoint;
        final Map<String, String> formParams;

        IframeContext(String effectiveHost, String effectiveIframeUrl, String endpoint, Map<String, String> formParams) {
            this.effectiveHost = effectiveHost;
            this.effectiveIframeUrl = effectiveIframeUrl;
            this.endpoint = endpoint;
            this.formParams = formParams;
        }
    }

    /**
     * Легковесный потокобезопасный CookieJar для сохранения сессионных и поддоменных cookies между iframe и API Kodik.
     */
    static final class InMemoryCookieJar implements CookieJar {
        private final Map<String, Cookie> store = new ConcurrentHashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            if (url == null || cookies == null || cookies.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (Cookie c : cookies) {
                String key = c.domain() + "|" + c.path() + "|" + c.name();
                if (c.expiresAt() <= now) {
                    store.remove(key);
                } else {
                    store.put(key, c);
                }
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            if (url == null || store.isEmpty()) return Collections.emptyList();
            long now = System.currentTimeMillis();
            List<Cookie> valid = new ArrayList<>();
            for (Map.Entry<String, Cookie> entry : store.entrySet()) {
                Cookie c = entry.getValue();
                if (c.expiresAt() <= now) {
                    store.remove(entry.getKey());
                } else if (c.matches(url)) {
                    valid.add(c);
                }
            }
            return valid;
        }
    }

    private static void logDebug(String msg) {
        try {
            Log.d(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    private static void logWarn(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}