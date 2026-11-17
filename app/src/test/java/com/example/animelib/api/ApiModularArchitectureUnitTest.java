package com.example.animelib.api;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ApiModularArchitectureUnitTest {

    @Test
    public void requestCache_deduplicatesInFlightRequestsAndNotifiesAllListeners() {
        ApiCacheManager.RequestCache<String, String> cache = new ApiCacheManager.RequestCache<>(4);
        List<String> results = new ArrayList<>();

        boolean firstStarted = cache.registerOrAttach("key1", new ApiCacheManager.CallbackNotifier<String>() {
            @Override
            public void onSuccess(String data) {
                results.add("cb1:" + data);
            }

            @Override
            public void onError(String error) {
                fail("Should not error");
            }
        });

        boolean secondStarted = cache.registerOrAttach("key1", new ApiCacheManager.CallbackNotifier<String>() {
            @Override
            public void onSuccess(String data) {
                results.add("cb2:" + data);
            }

            @Override
            public void onError(String error) {
                fail("Should not error");
            }
        });

        assertTrue("First call must start network request", firstStarted);
        assertFalse("Second concurrent call must attach to existing request", secondStarted);

        cache.put("key1", "payload");
        cache.dispatchSuccess("key1", "payload");

        assertEquals(2, results.size());
        assertTrue(results.contains("cb1:payload"));
        assertTrue(results.contains("cb2:payload"));
        assertEquals("payload", cache.getValid("key1", 60_000L));
        assertEquals("payload", cache.getStale("key1"));
    }

    @Test
    public void requestCache_dispatchesErrorsAndAllowsRetryAfterCompletion() {
        ApiCacheManager.RequestCache<String, String> cache = new ApiCacheManager.RequestCache<>(4);
        AtomicInteger errorCount = new AtomicInteger(0);

        assertTrue(cache.registerOrAttach("k_err", new ApiCacheManager.CallbackNotifier<String>() {
            @Override
            public void onSuccess(String data) {}

            @Override
            public void onError(String error) {
                errorCount.incrementAndGet();
            }
        }));
        assertFalse(cache.registerOrAttach("k_err", new ApiCacheManager.CallbackNotifier<String>() {
            @Override
            public void onSuccess(String data) {}

            @Override
            public void onError(String error) {
                errorCount.incrementAndGet();
            }
        }));

        cache.dispatchError("k_err", "HTTP 500");
        assertEquals(2, errorCount.get());

        // Subsequent request with same key should start fresh
        assertTrue(cache.registerOrAttach("k_err", null));
        cache.dispatchError("k_err", "done");
    }

    @Test
    public void apiClientCore_extractsSlugsIdsAndTimecodesCorrectly() {
        assertEquals("24653--sakamoto-days-part-2-anime",
                ApiService.extractMediaSlugFromUrl("https://animelib.org/ru/anime/24653--sakamoto-days-part-2-anime/watch?ep=1#comments"));
        assertEquals("24653",
                ApiClientCore.extractAnimeId("https://animelib.org/ru/anime/24653--sakamoto-days-part-2-anime/watch"));
        assertEquals("24653--sakamoto-days-part-2-anime",
                ApiClientCore.extractAnimeSlug("https://animelib.org/ru/anime/24653--sakamoto-days-part-2-anime?tab=info"));
        assertEquals(52991L,
                ApiService.extractShikimoriId("https://shikimori.io/animes/z52991-sousou-no-frieren"));
        assertEquals("12:05", ApiService.formatTimecode(725_000L));
        assertEquals("00:00", ApiService.formatTimecode(-100L));
    }

    @Test
    public void cacheManager_supportsAliasKeysBetweenSlugAndNumericId() {
        ApiCacheManager.RequestCache<String, String> cache = new ApiCacheManager.RequestCache<>(8);
        String slugKey = "24653--sakamoto-days-part-2-anime";
        String idKey = ApiClientCore.extractAnimeId(slugKey);
        assertEquals("24653", idKey);

        cache.put(slugKey, "AnimeDataPayload");
        cache.put(idKey, "AnimeDataPayload");

        assertEquals("AnimeDataPayload", cache.getValid(slugKey, 60_000L));
        assertEquals("AnimeDataPayload", cache.getValid(idKey, 60_000L));

        cache.remove(slugKey);
        cache.remove(idKey);
        assertNull(cache.getValid(slugKey, 60_000L));
        assertNull(cache.getValid(idKey, 60_000L));
    }
}
