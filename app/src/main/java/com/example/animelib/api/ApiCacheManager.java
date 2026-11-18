package com.example.animelib.api;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Потокобезопасный менеджер кэширования ответов API с поддержкой TTL,
 * дедупликации параллельных in-flight запросов и fallback на устаревший кэш при HTTP 429.
 */
public class ApiCacheManager {
    private static final String TAG = "ApiCacheManager";

    public interface CallbackNotifier<T> {
        void onSuccess(T data);
        void onError(String error);
    }

    public static class CachedEntry<T> {
        public final T data;
        public final long timestamp;

        public CachedEntry(T data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isValid(long maxAgeMs) {
            return (System.currentTimeMillis() - timestamp) < maxAgeMs;
        }
    }

    /**
     * Типизированное хранилище кэша + очередь ожидающих колбэков для одного типа запросов.
     */
    public static class RequestCache<K, V> {
        private final Map<K, CachedEntry<V>> cache = new ConcurrentHashMap<>();
        private final Map<K, List<CallbackNotifier<V>>> pending = new ConcurrentHashMap<>();
        private final int maxEntries;

        public RequestCache() {
            this(128);
        }

        public RequestCache(int maxEntries) {
            this.maxEntries = Math.max(16, maxEntries);
        }

        public Map<K, CachedEntry<V>> getRawCacheMap() {
            return cache;
        }

        public V getValid(K key, long ttlMs) {
            if (key == null) return null;
            CachedEntry<V> entry = cache.get(key);
            if (entry != null && entry.isValid(ttlMs)) {
                return entry.data;
            }
            return null;
        }

        public V getStale(K key) {
            if (key == null) return null;
            CachedEntry<V> entry = cache.get(key);
            return entry != null ? entry.data : null;
        }

        public void put(K key, V value) {
            if (key == null || value == null) return;
            if (cache.size() >= maxEntries) {
                evictOldest();
            }
            cache.put(key, new CachedEntry<>(value));
        }

        private void evictOldest() {
            K oldestKey = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<K, CachedEntry<V>> e : cache.entrySet()) {
                if (e.getValue() != null && e.getValue().timestamp < oldestTs) {
                    oldestTs = e.getValue().timestamp;
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey != null) {
                cache.remove(oldestKey);
            }
        }

        public void remove(K key) {
            if (key != null) {
                cache.remove(key);
            }
        }

        public void clear() {
            cache.clear();
        }

        /**
         * Регистрирует слушатель для ключа запроса.
         * @return true, если этот вызов — первый и должен запустить реальный сетевой запрос;
         *         false, если запрос уже выполняется и слушатель прикреплён к очереди ожидания.
         */
        public boolean registerOrAttach(K requestKey, CallbackNotifier<V> notifier) {
            synchronized (pending) {
                List<CallbackNotifier<V>> list = pending.get(requestKey);
                if (list != null) {
                    if (notifier != null) {
                        list.add(notifier);
                    }
                    return false;
                }
                list = new ArrayList<>(2);
                if (notifier != null) {
                    list.add(notifier);
                }
                pending.put(requestKey, list);
                return true;
            }
        }

        /**
         * Удаляет отменённый колбэк из очереди ожидания.
         * @return true, если после удаления по ключу не осталось ни одного активного слушателя
         */
        public boolean detachPendingNotifier(K requestKey, CallbackNotifier<V> notifier) {
            if (requestKey == null || notifier == null) return false;
            synchronized (pending) {
                List<CallbackNotifier<V>> list = pending.get(requestKey);
                if (list == null) {
                    return true;
                }
                list.remove(notifier);
                if (list.isEmpty()) {
                    pending.remove(requestKey);
                    return true;
                }
                return false;
            }
        }

        public void dispatchSuccess(K requestKey, V result) {
            List<CallbackNotifier<V>> callbacks;
            synchronized (pending) {
                callbacks = pending.remove(requestKey);
            }
            if (callbacks != null) {
                for (CallbackNotifier<V> cb : callbacks) {
                    try {
                        cb.onSuccess(result);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in callback onSuccess for key=" + requestKey, e);
                    }
                }
            }
        }

        public void dispatchError(K requestKey, String error) {
            List<CallbackNotifier<V>> callbacks;
            synchronized (pending) {
                callbacks = pending.remove(requestKey);
            }
            if (callbacks != null) {
                for (CallbackNotifier<V> cb : callbacks) {
                    try {
                        cb.onError(error);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in callback onError for key=" + requestKey, e);
                    }
                }
            }
        }
    }

    // Глобальные in-memory кэши для всех доменных сервисов
    public static final RequestCache<String, com.example.animelib.models.AnimeInfoResponse> ANIME_INFO = new RequestCache<>(128);
    public static final RequestCache<String, com.example.animelib.models.EpisodesListResponse> EPISODES_LIST = new RequestCache<>(128);
    public static final RequestCache<Integer, com.example.animelib.models.EpisodeResponse> EPISODE_DATA = new RequestCache<>(256);
    public static final RequestCache<String, com.example.animelib.models.AnimeBookmarkResponse> ANIME_BOOKMARK = new RequestCache<>(128);
    public static final RequestCache<String, com.example.animelib.models.RelatedTitlesResponse> RELATED_TITLES = new RequestCache<>(128);
    public static final RequestCache<String, com.example.animelib.models.KodikResponse> KODIK_LINKS = new RequestCache<>(64);
    public static final RequestCache<String, com.example.animelib.models.CommentsResponse> COMMENTS = new RequestCache<>(128);
    public static final RequestCache<Long, List<com.example.animelib.models.CommentsResponse.CommentItem>> STICKY_COMMENTS = new RequestCache<>(128);
    public static final RequestCache<String, com.example.animelib.models.SearchResponse> SEARCH = new RequestCache<>(64);
    public static final Map<Long, com.example.animelib.models.FranchiseResponse> FRANCHISE = new ConcurrentHashMap<>();
}
