package com.example.animelib.audio;

import android.media.MediaDataSource;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * MediaDataSource поверх HTTP Range-запросов OkHttpClient с LRU-кэшем блоков (512 КБ)
 * и фоновой предзагрузкой следующего блока при последовательном чтении.
 * Позволяет Android MediaExtractor быстро демультиплексировать аудиодорожку удалённого MP4
 * с правильными заголовками авторизации/Referer, FastDns и мгновенной отменой.
 */
public final class HttpRangeMediaDataSource extends MediaDataSource {

    private static final String TAG = "HttpRangeDataSource";
    private static final int BLOCK_SIZE = 512 * 1024; // 512 KB — меньше HTTP RTT на последовательном чтении
    private static final int MAX_CACHED_BLOCKS = 6;   // до 3 MB RAM

    private final OkHttpClient client;
    private final String url;
    private final AtomicBoolean cancelled;
    private volatile long totalSize = -1L;
    private volatile boolean closed = false;
    private volatile long lastRequestedBlock = -2L;

    private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Future<byte[]>> inFlightBlocks = new ConcurrentHashMap<>();

    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "Mp4RangePrefetch");
        t.setDaemon(true);
        return t;
    });

    private final LinkedHashMap<Long, byte[]> blockCache = new LinkedHashMap<Long, byte[]>(MAX_CACHED_BLOCKS + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > MAX_CACHED_BLOCKS;
        }
    };

    public HttpRangeMediaDataSource(@NonNull OkHttpClient client,
                                    @NonNull String url,
                                    @NonNull AtomicBoolean cancelled) {
        this.client = client;
        this.url = url;
        this.cancelled = cancelled;
    }

    @Override
    public long getSize() throws IOException {
        if (closed || cancelled.get()) {
            throw new IOException("Cancelled");
        }
        if (totalSize >= 0) {
            return totalSize;
        }

        Request req = new Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .build();
        Call call = client.newCall(req);
        activeCalls.add(call);
        try (Response resp = call.execute()) {
            if (!resp.isSuccessful() && resp.code() != 206) {
                throw new IOException("HTTP " + resp.code() + " while probing content length");
            }
            String contentRange = resp.header("Content-Range");
            if (contentRange != null) {
                int slash = contentRange.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < contentRange.length()) {
                    String totalStr = contentRange.substring(slash + 1).trim();
                    if (!"*".equals(totalStr)) {
                        try {
                            totalSize = Long.parseLong(totalStr);
                            return totalSize;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            String contentLength = resp.header("Content-Length");
            if (contentLength != null) {
                try {
                    long parsed = Long.parseLong(contentLength.trim());
                    if (resp.code() == 200 && parsed > 0) {
                        totalSize = parsed;
                        return totalSize;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return -1L;
        } finally {
            activeCalls.remove(call);
        }
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (size == 0) return 0;
        if (closed || cancelled.get()) {
            throw new IOException("Cancelled");
        }
        long fileLength = getSize();
        if (fileLength > 0 && position >= fileLength) {
            return -1;
        }

        int totalRead = 0;
        long currentPos = position;

        while (totalRead < size) {
            if (closed || cancelled.get()) {
                throw new IOException("Cancelled");
            }
            if (fileLength > 0 && currentPos >= fileLength) {
                break;
            }

            long blockIndex = currentPos / BLOCK_SIZE;
            int offsetInBlock = (int) (currentPos % BLOCK_SIZE);
            boolean sequentialForward = (blockIndex == lastRequestedBlock + 1) || (blockIndex == 0 && lastRequestedBlock < 0);
            lastRequestedBlock = blockIndex;

            byte[] block = getOrFetchBlock(blockIndex, fileLength);
            if (block == null || offsetInBlock >= block.length) {
                break;
            }

            // Предзагружаем следующий блок в фоне, пока MediaCodec декодирует текущий
            if (sequentialForward && block.length == BLOCK_SIZE) {
                schedulePrefetch(blockIndex + 1, fileLength);
            }

            int available = block.length - offsetInBlock;
            int toCopy = Math.min(size - totalRead, available);
            System.arraycopy(block, offsetInBlock, buffer, offset + totalRead, toCopy);

            totalRead += toCopy;
            currentPos += toCopy;

            if (block.length < BLOCK_SIZE) {
                break; // Reached EOF
            }
        }

        return totalRead > 0 ? totalRead : -1;
    }

    private void schedulePrefetch(long nextBlockIndex, long fileLength) {
        if (closed || cancelled.get()) return;
        long nextStart = nextBlockIndex * BLOCK_SIZE;
        if (fileLength > 0 && nextStart >= fileLength) return;

        synchronized (blockCache) {
            if (blockCache.containsKey(nextBlockIndex)) {
                return;
            }
        }
        if (inFlightBlocks.containsKey(nextBlockIndex)) {
            return;
        }

        try {
            inFlightBlocks.computeIfAbsent(nextBlockIndex, idx -> prefetchExecutor.submit(() -> {
                try {
                    if (closed || cancelled.get()) return null;
                    return fetchBlockNetwork(idx, fileLength);
                } finally {
                    inFlightBlocks.remove(idx);
                }
            }));
        } catch (Exception ignored) {
        }
    }

    private byte[] getOrFetchBlock(long blockIndex, long fileLength) throws IOException {
        synchronized (blockCache) {
            byte[] cached = blockCache.get(blockIndex);
            if (cached != null) {
                return cached;
            }
        }

        // Если этот блок уже качается фоновой предзагрузкой — просто дожидаемся его без дублирования запроса
        Future<byte[]> inFlight = inFlightBlocks.get(blockIndex);
        if (inFlight != null) {
            try {
                byte[] prefetched = inFlight.get();
                if (prefetched != null) {
                    return prefetched;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            } catch (ExecutionException e) {
                if (closed || cancelled.get()) {
                    throw new IOException("Cancelled");
                }
                // Если предзагрузка не удалась, пробуем напрямую
            }
        }

        synchronized (blockCache) {
            byte[] cached = blockCache.get(blockIndex);
            if (cached != null) {
                return cached;
            }
        }

        return fetchBlockNetwork(blockIndex, fileLength);
    }

    private byte[] fetchBlockNetwork(long blockIndex, long fileLength) throws IOException {
        if (closed || cancelled.get()) {
            throw new IOException("Cancelled");
        }

        long rangeStart = blockIndex * BLOCK_SIZE;
        long rangeEnd = rangeStart + BLOCK_SIZE - 1L;
        if (fileLength > 0 && rangeEnd >= fileLength) {
            rangeEnd = fileLength - 1L;
        }
        if (rangeStart > rangeEnd) {
            return null;
        }

        Request req = new Request.Builder()
                .url(url)
                .header("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                .build();
        Call call = client.newCall(req);
        activeCalls.add(call);
        try (Response resp = call.execute()) {
            if (!resp.isSuccessful() && resp.code() != 206) {
                throw new IOException("HTTP " + resp.code() + " for range " + rangeStart + "-" + rangeEnd);
            }
            ResponseBody body = resp.body();
            if (body == null) {
                return null;
            }
            byte[] bytes = body.bytes();
            if (resp.code() == 200 && rangeStart > 0) {
                // Сервер не поддерживает Range и вернул файл целиком — вырезаем нужный блок
                if (rangeStart >= bytes.length) return null;
                int len = (int) Math.min(BLOCK_SIZE, bytes.length - rangeStart);
                byte[] slice = new byte[len];
                System.arraycopy(bytes, (int) rangeStart, slice, 0, len);
                bytes = slice;
            }
            synchronized (blockCache) {
                blockCache.put(blockIndex, bytes);
            }
            return bytes;
        } finally {
            activeCalls.remove(call);
        }
    }

    public void cancelActiveRequest() {
        for (Call call : activeCalls) {
            try {
                call.cancel();
            } catch (Exception e) {
                Log.d(TAG, "Cancel error: " + e.getMessage());
            }
        }
        activeCalls.clear();
    }

    @Override
    public void close() {
        closed = true;
        cancelActiveRequest();
        try {
            prefetchExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
        inFlightBlocks.clear();
        synchronized (blockCache) {
            blockCache.clear();
        }
    }
}
