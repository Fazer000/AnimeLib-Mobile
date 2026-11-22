package com.example.animelib.audio;

import android.media.MediaDataSource;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * MediaDataSource поверх HTTP Range-запросов OkHttpClient с LRU-кэшем блоков (256 КБ),
 * жёсткими таймаутами, мгновенной отменой через CancelSignal и защитой от зависаний при плохом интернете.
 */
public final class HttpRangeMediaDataSource extends MediaDataSource {

    private static final String TAG = "HttpRangeDataSource";
    private static final int BLOCK_SIZE = 256 * 1024; // 256 KB — оптимально для быстрых seek-зондов
    private static final int MAX_CACHED_BLOCKS = 12;  // до 3 MB RAM
    private static final int MAX_CONSECUTIVE_ERRORS = 2;
    private static final long MAX_PREFETCH_WAIT_MS = 8_000L;
    private static final long MAX_NON_RANGE_SKIP_BYTES = 4L * 1024L * 1024L;

    private final OkHttpClient client;
    private final String url;
    private final AudioPcmExtractor.CancelSignal cancelSignal;
    private volatile long totalSize = -1L;
    private volatile boolean closed = false;
    private volatile long lastRequestedBlock = -2L;

    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
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
                                    @NonNull AudioPcmExtractor.CancelSignal cancelSignal) {
        this.client = client;
        this.url = url;
        this.cancelSignal = cancelSignal;
        this.cancelSignal.registerCloseable(this);
    }

    private void checkNotCancelledOrFailed() throws IOException {
        if (closed || cancelSignal.isCancelled()) {
            throw new IOException("Cancelled");
        }
        if (consecutiveErrors.get() >= MAX_CONSECUTIVE_ERRORS) {
            cancelSignal.recordFailure(AudioPcmExtractor.FailureReason.NETWORK);
            throw new IOException("Too many consecutive network errors");
        }
    }

    @Override
    public long getSize() throws IOException {
        checkNotCancelledOrFailed();
        if (totalSize >= 0) {
            return totalSize;
        }

        Request req = new Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .build();
        Call call = client.newCall(req);
        activeCalls.add(call);
        cancelSignal.registerCall(call);
        try (Response resp = call.execute()) {
            if (closed || cancelSignal.isCancelled()) {
                throw new IOException("Cancelled");
            }
            if (!resp.isSuccessful() && resp.code() != 206) {
                consecutiveErrors.incrementAndGet();
                cancelSignal.recordFailure(AudioPcmExtractor.FailureReason.NETWORK);
                throw new IOException("HTTP " + resp.code() + " while probing content length");
            }
            consecutiveErrors.set(0);
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
        } catch (IOException e) {
            if (!closed && !cancelSignal.isCancelled()) {
                consecutiveErrors.incrementAndGet();
                cancelSignal.recordFailure(e instanceof SocketTimeoutException
                        ? AudioPcmExtractor.FailureReason.TIMEOUT
                        : AudioPcmExtractor.FailureReason.NETWORK);
            }
            throw e;
        } finally {
            activeCalls.remove(call);
            cancelSignal.unregisterCall(call);
        }
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (size == 0) return 0;
        checkNotCancelledOrFailed();
        long fileLength = getSize();
        if (fileLength > 0 && position >= fileLength) {
            return -1;
        }

        int totalRead = 0;
        long currentPos = position;

        while (totalRead < size) {
            checkNotCancelledOrFailed();
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

            // Предзагружаем следующий блок в фоне, только если сеть сейчас стабильна (нет ошибок и достаточно времени)
            if (sequentialForward && block.length == BLOCK_SIZE
                    && consecutiveErrors.get() == 0
                    && cancelSignal.remainingMs() > 5_000L) {
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
        if (closed || cancelSignal.isCancelled()) return;
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
                    if (closed || cancelSignal.isCancelled()) return null;
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

        // Если этот блок уже качается фоновой предзагрузкой — ждём его с жёстким таймаутом
        Future<byte[]> inFlight = inFlightBlocks.get(blockIndex);
        if (inFlight != null) {
            long waitMs = Math.max(250L, Math.min(MAX_PREFETCH_WAIT_MS, cancelSignal.remainingMs()));
            try {
                byte[] prefetched = inFlight.get(waitMs, TimeUnit.MILLISECONDS);
                if (prefetched != null) {
                    return prefetched;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            } catch (TimeoutException e) {
                inFlight.cancel(true);
                inFlightBlocks.remove(blockIndex);
                if (closed || cancelSignal.isCancelled()) {
                    throw new IOException("Cancelled");
                }
                consecutiveErrors.incrementAndGet();
                cancelSignal.recordFailure(AudioPcmExtractor.FailureReason.TIMEOUT);
                throw new SocketTimeoutException("Prefetch timed out");
            } catch (ExecutionException e) {
                if (closed || cancelSignal.isCancelled()) {
                    throw new IOException("Cancelled");
                }
                // Если предзагрузка не удалась, проверим лимит ошибок и попробуем напрямую
                checkNotCancelledOrFailed();
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
        checkNotCancelledOrFailed();

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
        cancelSignal.registerCall(call);
        try (Response resp = call.execute()) {
            if (closed || cancelSignal.isCancelled()) {
                throw new IOException("Cancelled");
            }
            if (!resp.isSuccessful() && resp.code() != 206) {
                consecutiveErrors.incrementAndGet();
                cancelSignal.recordFailure(AudioPcmExtractor.FailureReason.NETWORK);
                throw new IOException("HTTP " + resp.code() + " for range " + rangeStart + "-" + rangeEnd);
            }
            ResponseBody body = resp.body();
            if (body == null) {
                return null;
            }

            byte[] bytes;
            if (resp.code() == 200 && rangeStart > 0) {
                // Защита: если сервер игнорирует Range и шлёт весь MP4 целиком, читаем строго ограниченный префикс
                if (rangeStart > MAX_NON_RANGE_SKIP_BYTES) {
                    throw new IOException("Server does not support HTTP Range for deep offset " + rangeStart);
                }
                bytes = readBoundedRangeFromFullStream(body.byteStream(), rangeStart, BLOCK_SIZE);
            } else {
                bytes = readStreamWithCancelCheck(body.byteStream(), BLOCK_SIZE);
            }

            if (bytes != null) {
                consecutiveErrors.set(0);
                synchronized (blockCache) {
                    blockCache.put(blockIndex, bytes);
                }
            }
            return bytes;
        } catch (IOException e) {
            if (!closed && !cancelSignal.isCancelled()) {
                consecutiveErrors.incrementAndGet();
                cancelSignal.recordFailure(e instanceof SocketTimeoutException
                        ? AudioPcmExtractor.FailureReason.TIMEOUT
                        : AudioPcmExtractor.FailureReason.NETWORK);
            }
            throw e;
        } finally {
            activeCalls.remove(call);
            cancelSignal.unregisterCall(call);
        }
    }

    private byte[] readStreamWithCancelCheck(@NonNull InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxBytes, 65536));
        byte[] buf = new byte[16384];
        int remaining = maxBytes;
        int read;
        while (remaining > 0 && (read = is.read(buf, 0, Math.min(buf.length, remaining))) != -1) {
            if (closed || cancelSignal.isCancelled()) {
                throw new IOException("Cancelled");
            }
            baos.write(buf, 0, read);
            remaining -= read;
        }
        return baos.toByteArray();
    }

    private byte[] readBoundedRangeFromFullStream(@NonNull InputStream is, long skipBytes, int maxRead) throws IOException {
        byte[] discard = new byte[16384];
        long skipped = 0L;
        while (skipped < skipBytes) {
            if (closed || cancelSignal.isCancelled()) {
                throw new IOException("Cancelled");
            }
            int toSkip = (int) Math.min(discard.length, skipBytes - skipped);
            int r = is.read(discard, 0, toSkip);
            if (r < 0) return null;
            skipped += r;
        }
        return readStreamWithCancelCheck(is, maxRead);
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
        cancelSignal.unregisterCloseable(this);
        cancelActiveRequest();
        for (Future<byte[]> f : inFlightBlocks.values()) {
            try {
                f.cancel(true);
            } catch (Exception ignored) {
            }
        }
        inFlightBlocks.clear();
        try {
            prefetchExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
        synchronized (blockCache) {
            blockCache.clear();
        }
    }
}
