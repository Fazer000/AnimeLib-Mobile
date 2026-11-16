package com.example.animelib.api;

import com.example.animelib.models.KodikResponse;
import com.google.gson.Gson;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.Assert.*;

public class KodikExtractorUnitTest {

    /**
     * Шифрует исходный URL тем же способом, что и сервер Kodik: Base64 -> сдвиг Цезаря назад (26 - shift).
     */
    private static String encodeKodikPayloadUrl(String plainUrl, int decodeShift) {
        String b64 = Base64.getEncoder().encodeToString(plainUrl.getBytes(StandardCharsets.UTF_8));
        int encodeShift = (26 - (decodeShift % 26)) % 26;
        return KodikLinksExtractor.caesarShift(b64, encodeShift);
    }

    @Test
    public void testDecodeRot18PlainAndDynamicShiftUrls() {
        String rawTarget = "//cloud.kodik-storage.com/useruploads/ep1/720.mp4:hls:manifest.m3u8";

        // 1. Стандартный ROT18
        String rot18Encoded = encodeKodikPayloadUrl(rawTarget, 18);
        assertEquals(
                "https://cloud.kodik-storage.com/useruploads/ep1/720.mp4:hls:manifest.m3u8",
                KodikLinksExtractor.decodeKodikSrc(rot18Encoded)
        );

        // 2. Прямая незашифрованная ссылка не должна ломаться при декодировании
        assertEquals(
                "https://cloud.kodik-storage.com/useruploads/ep1/480.mp4:hls:manifest.m3u8",
                KodikLinksExtractor.decodeKodikSrc("//cloud.kodik-storage.com/useruploads/ep1/480.mp4:hls:manifest.m3u8")
        );

        // 3. Нестандартный сдвиг (например, ROT13 при обновлении скрипта Kodik)
        String rot13Encoded = encodeKodikPayloadUrl("//cloud.kodik-storage.com/useruploads/ep2/1080.mp4:hls:manifest.m3u8", 13);
        assertEquals(
                "https://cloud.kodik-storage.com/useruploads/ep2/1080.mp4:hls:manifest.m3u8",
                KodikLinksExtractor.decodeKodikSrc(rot13Encoded)
        );
    }

    @Test
    public void testIframeJsEndpointDiscoveryAndPostSessionExtraction() throws Exception {
        String encoded720 = encodeKodikPayloadUrl("//cloud.kodik-storage.com/node1/720.mp4:hls:manifest.m3u8", 18);
        String encoded720Mirror = encodeKodikPayloadUrl("//s2.kodik-storage.com/node2/720.mp4:hls:manifest.m3u8", 18);
        String encoded480 = encodeKodikPayloadUrl("//cloud.kodik-storage.com/node1/480.mp4:hls:manifest.m3u8", 18);

        String endpointB64 = Base64.getEncoder().encodeToString("/kor".getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> capturedPostBody = new AtomicReference<>("");
        AtomicReference<String> capturedReferer = new AtomicReference<>("");
        AtomicReference<String> capturedOrigin = new AtomicReference<>("");

        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request req = chain.request();
                    String path = req.url().encodedPath();

                    if ("/ftor".equals(path)) {
                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(404)
                                .message("Not Found")
                                .body(ResponseBody.create("not found", MediaType.get("text/html")))
                                .build();
                    }

                    if (path.startsWith("/seria/")) {
                        String html = "<html><head>"
                                + "<script>var urlParams = '{\"d\":\"animelib.org\",\"d_sign\":\"sig_abc\",\"pd\":\"kodik.info\",\"pd_sign\":\"pdsig_xyz\"}';</script>"
                                + "<script src=\"/assets/js/app.player_single.v42.js\"></script>"
                                + "</head></html>";
                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(html, MediaType.get("text/html")))
                                .build();
                    }

                    if (path.contains("app.player_single")) {
                        String js = "function load(){$.ajax({type:\"POST\",url:atob(\"" + endpointB64 + "\"),dataType:\"json\"});}";
                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(js, MediaType.get("application/javascript")))
                                .build();
                    }

                    if ("/kor".equals(path) && "POST".equalsIgnoreCase(req.method())) {
                        Buffer buf = new Buffer();
                        if (req.body() != null) {
                            req.body().writeTo(buf);
                        }
                        capturedPostBody.set(buf.readUtf8());
                        capturedReferer.set(req.header("Referer"));
                        capturedOrigin.set(req.header("Origin"));

                        String json = "{\"links\":{"
                                + "\"720\":[{\"src\":\"" + encoded720 + "\",\"type\":\"application/x-mpegURL\"},"
                                + "{\"src\":\"" + encoded720Mirror + "\",\"type\":\"application/x-mpegURL\"}],"
                                + "\"480\":[{\"src\":\"" + encoded480 + "\",\"type\":\"application/x-mpegURL\"}]"
                                + "}}";
                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(json, MediaType.get("application/json")))
                                .build();
                    }

                    return new Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(500)
                            .message("Unexpected")
                            .body(ResponseBody.create("", MediaType.get("text/plain")))
                            .build();
                })
                .build();

        KodikLinksExtractor extractor = new KodikLinksExtractor(mockClient, new Gson());
        KodikResponse res = extractor.getLinks("//kodik.info/seria/998877/deadbeef1234/720p", true);

        assertNotNull(res);
        assertTrue(res.isSuccess());
        assertEquals(2, res.getData().get("720").length);
        assertEquals("https://cloud.kodik-storage.com/node1/720.mp4:hls:manifest.m3u8", res.getData().get("720")[0].getSrc());
        assertEquals("https://s2.kodik-storage.com/node2/720.mp4:hls:manifest.m3u8", res.getData().get("720")[1].getSrc());

        assertTrue("POST body must contain signed d_sign from iframe", capturedPostBody.get().contains("d_sign=sig_abc"));
        assertTrue("POST body must contain hash", capturedPostBody.get().contains("hash=deadbeef1234"));
        assertEquals("https://kodik.info", capturedOrigin.get());
        assertTrue(capturedReferer.get().startsWith("https://kodik.info/seria/998877/deadbeef1234/720p"));

        // Проверяем хранение и подстановку сессионных и поддоменных cookies в InMemoryCookieJar
        KodikLinksExtractor.InMemoryCookieJar cookieJar = new KodikLinksExtractor.InMemoryCookieJar();
        okhttp3.HttpUrl iframeUrl = okhttp3.HttpUrl.get("https://kodik.info/seria/998877/deadbeef1234/720p");
        okhttp3.Cookie sessionCookie = okhttp3.Cookie.parse(iframeUrl, "kd_sess=ok123; Domain=kodik.info; Path=/");
        assertNotNull(sessionCookie);
        cookieJar.saveFromResponse(iframeUrl, java.util.Collections.singletonList(sessionCookie));
        List<okhttp3.Cookie> loaded = cookieJar.loadForRequest(okhttp3.HttpUrl.get("https://sub.kodik.info/kor"));
        assertEquals(1, loaded.size());
        assertEquals("ok123", loaded.get(0).value());
    }

    @Test
    public void testAutomaticMirrorFailoverWhenPrimaryHostFails() throws Exception {
        String encoded720 = encodeKodikPayloadUrl("//cloud.kodik-storage.com/failover/720.mp4:hls:manifest.m3u8", 18);
        AtomicBoolean primaryFailed = new AtomicBoolean(false);

        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request req = chain.request();
                    if ("kodik.info".equalsIgnoreCase(req.url().host())) {
                        primaryFailed.set(true);
                        throw new IOException("Connection reset by peer on kodik.info");
                    }
                    String json = "{\"links\":{\"720\":[{\"src\":\"" + encoded720 + "\",\"type\":\"application/x-mpegURL\"}]}}";
                    return new Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(json, MediaType.get("application/json")))
                            .build();
                })
                .build();

        KodikLinksExtractor extractor = new KodikLinksExtractor(mockClient, new Gson());
        KodikResponse res = extractor.getLinks("https://kodik.info/seria/112233/aabbccdd/720p", true);

        assertTrue(primaryFailed.get());
        assertNotNull(res);
        assertTrue(res.isSuccess());
        assertEquals("https://cloud.kodik-storage.com/failover/720.mp4:hls:manifest.m3u8", res.getData().get("720")[0].getSrc());
    }

    @Test
    public void testCandidateUrlsOrderingAnd4KFiltering() {
        KodikResponse response = new Gson().fromJson("{"
                + "\"success\": true,"
                + "\"data\": {"
                + "  \"2160\": [{\"src\": \"https://s1.kodik-storage.com/2160.m3u8\"}],"
                + "  \"1080\": [{\"src\": \"https://s1.kodik-storage.com/1080.m3u8\"}],"
                + "  \"720\": ["
                + "    {\"src\": \"https://s1.kodik-storage.com/720_a.m3u8\"},"
                + "    {\"src\": \"https://s2.kodik-storage.com/720_b.m3u8\"}"
                + "  ],"
                + "  \"480\": [{\"src\": \"https://s1.kodik-storage.com/480.m3u8\"}]"
                + "}"
                + "}", KodikResponse.class);

        List<String> candidates = KodikLinksExtractor.collectCandidateUrls(response, "720p", false);
        assertEquals(4, candidates.size());
        assertEquals("https://s1.kodik-storage.com/720_a.m3u8", candidates.get(0));
        assertEquals("https://s2.kodik-storage.com/720_b.m3u8", candidates.get(1));
        assertEquals("https://s1.kodik-storage.com/480.m3u8", candidates.get(2));
        assertEquals("https://s1.kodik-storage.com/1080.m3u8", candidates.get(3));
    }

    @Test
    public void testKodikStreamHeadersDoNotLeakAnimeLibAuthToken() {
        assertTrue(ApiService.isKodikStreamUrl("https://cloud.kodik-storage.com/useruploads/123/720.mp4:hls:manifest.m3u8"));
        assertTrue(ApiService.isKodikStreamUrl("https://aniqit.com/seria/123/abc/720p"));
        assertFalse(ApiService.isKodikStreamUrl("https://s2.cdnlibs.org/ anime/video/1080.mp4"));

        Map<String, String> kodikHeaders = ApiService.buildKodikStreamHeaders();
        assertFalse(kodikHeaders.containsKey("Authorization"));
        assertEquals("https://kodik.info/", kodikHeaders.get("Referer"));
        assertEquals("https://kodik.info", kodikHeaders.get("Origin"));
    }

    @Test
    public void testUnsignedLinkSkipsBlindFastPostAndSkipsJsBundleWhenFtorWorks() throws Exception {
        KodikLinksExtractor.clearCachesForTesting();
        String encoded720 = encodeKodikPayloadUrl("//cloud.kodik-storage.com/fast/720.mp4:hls:manifest.m3u8", 18);
        java.util.List<String> requestedPaths = new java.util.ArrayList<>();

        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request req = chain.request();
                    String path = req.url().encodedPath();
                    requestedPaths.add(req.method() + " " + path);

                    if (path.startsWith("/seria/")) {
                        String html = "<html><head>"
                                + "<script>var urlParams = '{\"d\":\"animelib.org\",\"d_sign\":\"sig_ok\",\"pd\":\"kodikplayer.com\",\"pd_sign\":\"pdsig_ok\"}';</script>"
                                + "<script src=\"/assets/js/app.player_single.huge.js\"></script>"
                                + "</head></html>";
                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(html, MediaType.get("text/html")))
                                .build();
                    }

                    if (path.contains("app.player_single")) {
                        fail("Heavy player JS bundle must NOT be downloaded when cached /ftor succeeds");
                    }

                    if ("/ftor".equals(path) && "POST".equalsIgnoreCase(req.method())) {
                        String json = "{\"links\":{\"720\":[{\"src\":\"" + encoded720 + "\",\"type\":\"application/x-mpegURL\"}]}}";
                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(json, MediaType.get("application/json")))
                                .build();
                    }

                    return new Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body(ResponseBody.create("", MediaType.get("text/plain")))
                            .build();
                })
                .build();

        KodikLinksExtractor extractor = new KodikLinksExtractor(mockClient, new Gson());
        KodikResponse res = extractor.getLinks("//kodik.info/seria/555666/cafebabe12/720p", false);

        assertNotNull(res);
        assertTrue(res.isSuccess());
        // Exactly 2 RTTs total: GET /seria/... then POST /ftor (no blind /ftor POST before HTML, no JS bundle download)
        assertEquals(2, requestedPaths.size());
        assertTrue(requestedPaths.get(0).startsWith("GET /seria/"));
        assertEquals("POST /ftor", requestedPaths.get(1));
    }

    @Test
    public void testFailedPrimaryHostIsDeprioritizedAfterFailover() throws Exception {
        KodikLinksExtractor.clearCachesForTesting();
        String encoded720 = encodeKodikPayloadUrl("//cloud.kodik-storage.com/mirror/720.mp4:hls:manifest.m3u8", 18);
        java.util.concurrent.atomic.AtomicInteger kodikInfoHits = new java.util.concurrent.atomic.AtomicInteger(0);

        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request req = chain.request();
                    if ("kodik.info".equalsIgnoreCase(req.url().host())) {
                        kodikInfoHits.incrementAndGet();
                        throw new IOException("Blocked by ISP");
                    }
                    if (req.url().encodedPath().startsWith("/seria/")) {
                        String html = "<html><head><script>var urlParams = '{\"d\":\"animelib.org\",\"d_sign\":\"s1\",\"pd\":\"kodikplayer.com\",\"pd_sign\":\"s2\"}';</script></head></html>";
                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(html, MediaType.get("text/html")))
                                .build();
                    }
                    String json = "{\"links\":{\"720\":[{\"src\":\"" + encoded720 + "\",\"type\":\"application/x-mpegURL\"}]}}";
                    return new Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(json, MediaType.get("application/json")))
                            .build();
                })
                .build();

        KodikLinksExtractor extractor = new KodikLinksExtractor(mockClient, new Gson());
        KodikResponse first = extractor.getLinks("//kodik.info/seria/1001/hash1/720p", false);
        assertNotNull(first);
        assertEquals(1, kodikInfoHits.get());

        // Second request must go straight to kodikplayer.com without hitting dead kodik.info again
        KodikResponse second = extractor.getLinks("//kodik.info/seria/1002/hash2/720p", false);
        assertNotNull(second);
        assertEquals(1, kodikInfoHits.get());

        List<String> order = KodikLinksExtractor.buildCandidateHosts("kodik.info", false);
        assertEquals("kodikplayer.com", order.get(0));
        assertEquals("kodik.info", order.get(order.size() - 1));
    }
}
