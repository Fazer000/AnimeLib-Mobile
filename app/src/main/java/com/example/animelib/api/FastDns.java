package com.example.animelib.api;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Dispatcher;
import okhttp3.Dns;

/**
 * Быстрый DNS-резолвер для OkHttp:
 * - Кэширует DNS-ответы в памяти на короткий TTL (60 сек), убирая повторные lookup-ы на сегментах видео.
 * - Упорядочивает IPv4-адреса перед IPv6, устраняя многосекундные зависания на сетях с проблемным IPv6,
 *   сохраняя при этом IPv6-адреса как резервные (IPv6-only хосты продолжают работать).
 */
public final class FastDns implements Dns {

    public static final FastDns INSTANCE = new FastDns();

    private static final long DNS_CACHE_TTL_MS = 60_000L;
    private static final int MAX_CACHE_ENTRIES = 128;

    @FunctionalInterface
    interface SystemResolver {
        List<InetAddress> resolve(String hostname) throws UnknownHostException;
    }

    private static final class CacheEntry {
        final List<InetAddress> addresses;
        final long expiresAtMs;

        CacheEntry(List<InetAddress> addresses, long expiresAtMs) {
            this.addresses = addresses;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final SystemResolver systemResolver;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private FastDns() {
        this(Dns.SYSTEM::lookup);
    }

    FastDns(SystemResolver systemResolver) {
        this.systemResolver = systemResolver;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (hostname == null || hostname.isEmpty()) {
            throw new UnknownHostException("hostname is empty");
        }

        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(hostname);
        if (cached != null && now < cached.expiresAtMs && !cached.addresses.isEmpty()) {
            return cached.addresses;
        }

        List<InetAddress> resolved = systemResolver.resolve(hostname);
        List<InetAddress> ordered = orderIpv4First(resolved);
        if (!ordered.isEmpty()) {
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(hostname, new CacheEntry(ordered, now + DNS_CACHE_TTL_MS));
        }
        return ordered;
    }

    public void clearCache() {
        cache.clear();
    }

    static List<InetAddress> orderIpv4First(List<InetAddress> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        List<InetAddress> ipv4 = new ArrayList<>(input.size());
        List<InetAddress> others = new ArrayList<>(input.size());
        for (InetAddress addr : input) {
            if (addr instanceof Inet4Address) {
                ipv4.add(addr);
            } else if (addr != null) {
                others.add(addr);
            }
        }
        if (ipv4.isEmpty() || others.isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<>(input));
        }
        List<InetAddress> result = new ArrayList<>(ipv4.size() + others.size());
        result.addAll(ipv4);
        result.addAll(others);
        return Collections.unmodifiableList(result);
    }

    /**
     * Создает OkHttp Dispatcher с повышенным лимитом параллельных запросов на хост
     * (в OkHttp по умолчанию maxRequestsPerHost = 5, чего недостаточно для HLS-сегментов + API).
     */
    public static Dispatcher createDispatcher(int maxRequests, int maxRequestsPerHost) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(maxRequests);
        dispatcher.setMaxRequestsPerHost(maxRequestsPerHost);
        return dispatcher;
    }
}
