package com.example.animelib.api;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Dispatcher;
import okhttp3.Dns;

/**
 * Быстрый DNS-резолвер для OkHttp:
 * - Кэширует DNS-ответы в памяти на короткий TTL (60 сек), убирая повторные lookup-ы на сегментах видео.
 * - Упорядочивает IPv4-адреса перед IPv6, устраняя многосекундные зависания на сетях с проблемным IPv6,
 *   сохраняя при этом IPv6-адреса как резервные (IPv6-only хосты продолжают работать).
 * - При сбое системного/VPN DNS автоматически разрешает домены через DNS-over-HTTPS (1.1.1.1 / 8.8.8.8 по прямому IP).
 */
public final class FastDns implements Dns {

    public static final FastDns INSTANCE = new FastDns();

    private static final long DNS_CACHE_TTL_MS = 60_000L;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final Pattern DOH_IPV4_DATA_PATTERN =
            Pattern.compile("\"type\"\\s*:\\s*1\\b[^}]*?\"data\"\\s*:\\s*\"((?:\\d{1,3}\\.){3}\\d{1,3})\"");

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
    private final boolean enableDohFallback;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private FastDns() {
        this.systemResolver = Dns.SYSTEM::lookup;
        this.enableDohFallback = true;
    }

    FastDns(SystemResolver systemResolver) {
        this.systemResolver = systemResolver;
        this.enableDohFallback = false;
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

        UnknownHostException primaryError = null;
        List<InetAddress> resolved = null;
        try {
            resolved = systemResolver.resolve(hostname);
        } catch (UnknownHostException e) {
            primaryError = e;
        }

        if ((resolved == null || resolved.isEmpty()) && enableDohFallback) {
            resolved = resolveViaDohFallback(hostname);
        }

        if (resolved == null || resolved.isEmpty()) {
            if (primaryError != null) {
                throw primaryError;
            }
            throw new UnknownHostException("Unable to resolve host " + hostname);
        }

        List<InetAddress> ordered = orderIpv4First(resolved);
        if (!ordered.isEmpty()) {
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(hostname, new CacheEntry(ordered, now + DNS_CACHE_TTL_MS));
        }
        return ordered;
    }

    private static List<InetAddress> resolveViaDohFallback(String hostname) {
        String[] dohEndpoints = {
                "https://1.1.1.1/dns-query?name=",
                "https://8.8.8.8/resolve?name="
        };
        for (String endpointPrefix : dohEndpoints) {
            HttpURLConnection conn = null;
            try {
                String encodedHost = URLEncoder.encode(hostname, "UTF-8");
                URL url = new URL(endpointPrefix + encodedHost + "&type=A");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/dns-json");
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    List<InetAddress> parsed = parseDohJsonAddresses(hostname, sb.toString());
                    if (!parsed.isEmpty()) {
                        return parsed;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return Collections.emptyList();
    }

    static List<InetAddress> parseDohJsonAddresses(String hostname, String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        List<InetAddress> list = new ArrayList<>(4);
        Matcher matcher = DOH_IPV4_DATA_PATTERN.matcher(json);
        while (matcher.find()) {
            String ip = matcher.group(1);
            if (ip != null && !ip.isEmpty()) {
                try {
                    String[] parts = ip.split("\\.");
                    if (parts.length == 4) {
                        byte[] raw = new byte[4];
                        for (int i = 0; i < 4; i++) {
                            raw[i] = (byte) (Integer.parseInt(parts[i]) & 0xFF);
                        }
                        list.add(InetAddress.getByAddress(hostname, raw));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return list;
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
