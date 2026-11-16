package com.example.animelib.util;

import android.content.Context;
import android.util.Log;

import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/**
 * Менеджер общего кэша медиапотоков для предотвращения повторных сетевых запросов
 * при одновременном воспроизведении основного плеера и плеера подсветки (Ambilight).
 */
public class MediaCacheManager {
    private static final String TAG = "MediaCacheManager";
    private static final long MAX_CACHE_SIZE = 300 * 1024 * 1024L; // 300 МБ кэша
    private static volatile SimpleCache simpleCache;
    private static final java.util.concurrent.atomic.AtomicBoolean warmUpStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void warmUpAsync(Context context) {
        if (context == null || simpleCache != null || !warmUpStarted.compareAndSet(false, true)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        Thread t = new Thread(() -> getCache(appContext), "media-cache-init");
        t.setDaemon(true);
        t.start();
    }

    public static SimpleCache getCacheIfInitialized() {
        return simpleCache;
    }

    public static synchronized SimpleCache getCache(Context context) {
        if (simpleCache == null && context != null) {
            try {
                Context appContext = context.getApplicationContext();
                File cacheDir = new File(appContext.getCacheDir(), "media_player_stream_cache");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }
                LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE);
                StandaloneDatabaseProvider databaseProvider = new StandaloneDatabaseProvider(appContext);
                simpleCache = new SimpleCache(cacheDir, evictor, databaseProvider);
                Log.d(TAG, "Media stream cache initialized at " + cacheDir.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize MediaCacheManager", e);
            }
        }
        return simpleCache;
    }

    public static DataSource.Factory createNonBlockingCacheDataSourceFactory(
            Context context,
            DataSource.Factory upstreamDefaultFactory
    ) {
        SimpleCache cache = simpleCache;
        if (cache == null) {
            warmUpAsync(context);
            return upstreamDefaultFactory;
        }
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamDefaultFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public static DataSource.Factory createCacheDataSourceFactory(Context context, DataSource.Factory upstreamFactory) {
        SimpleCache cache = getCache(context);
        DataSource.Factory defaultFactory = new androidx.media3.datasource.DefaultDataSource.Factory(context, upstreamFactory);
        if (cache == null) {
            return defaultFactory;
        }
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(defaultFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public static synchronized void releaseCache() {
        if (simpleCache != null) {
            try {
                simpleCache.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing SimpleCache", e);
            }
            simpleCache = null;
        }
        warmUpStarted.set(false);
    }
}
