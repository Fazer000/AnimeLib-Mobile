package com.example.animelib.audio;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.api.ApiService;
import com.example.animelib.util.HlsPlaylistParser;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Извлекает компактный спектральный аудио-отпечаток из любого поддерживаемого источника:
 * - локальные скачанные файлы (.mp4 / .ts)
 * - удалённые MP4 потоки AnimeLib (через HttpRangeMediaDataSource с разовым парсингом moov + прямые seek-прыжки)
 * - удалённые HLS (.m3u8) потоки Kodik (выборочная загрузка нужных 360p сегментов + AES-128 + fMP4 init)
 *
 * Защита от плохого интернета:
 * - Единый CancelSignal с жёстким дедлайном и мгновенной отменой всех активных OkHttp Call и DataSource.
 * - Ограниченный параллелизм HLS (2 потока), чтобы не душить буферизацию основного видеоплеера.
 * - Ограниченное ожидание сегментов и защита цикла MediaCodec от зависания на битых кадрах.
 */
public final class AudioPcmExtractor {

    private static final String TAG = "AudioPcmExtractor";
    private static final long CODEC_IDLE_WAIT_US = 2_000L;
    private static final int MAX_CODEC_IDLE_LOOPS = 250;
    private static final long MAX_WINDOW_DECODE_MS = 12_000L;
    private static final long MAX_HLS_SEGMENT_WAIT_MS = 11_000L;
    private static final int HLS_PARALLEL_THREADS = 2;

    private AudioPcmExtractor() {
    }

    public enum FailureReason {
        NONE,
        TIMEOUT,
        NETWORK,
        DECODE
    }

    /**
     * Токен отмены и жёсткого таймаута операции:
     * при отмене или истечении дедлайна немедленно обрывает все открытые сетевые запросы и ресурсы.
     */
    public static final class CancelSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean timedOut = new AtomicBoolean(false);
        private final long deadlineElapsedMs;
        private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();
        private final Set<Closeable> activeResources = ConcurrentHashMap.newKeySet();
        private final AtomicReference<FailureReason> lastFailure = new AtomicReference<>(FailureReason.NONE);

        public CancelSignal(long timeoutMs) {
            this.deadlineElapsedMs = timeoutMs > 0L
                    ? (SystemClock.elapsedRealtime() + timeoutMs)
                    : 0L;
        }

        public boolean isCancelled() {
            if (cancelled.get()) {
                return true;
            }
            if (deadlineElapsedMs > 0L && SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
                markTimedOut();
                return true;
            }
            return false;
        }

        public boolean isUserOrSystemCancelled() {
            return cancelled.get() && !timedOut.get();
        }

        public boolean isTimedOut() {
            isCancelled();
            return timedOut.get();
        }

        public long remainingMs() {
            if (deadlineElapsedMs <= 0L) return Long.MAX_VALUE;
            return Math.max(0L, deadlineElapsedMs - SystemClock.elapsedRealtime());
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                abortAllActiveResources();
            }
        }

        public void markTimedOut() {
            timedOut.set(true);
            recordFailure(FailureReason.TIMEOUT);
            if (cancelled.compareAndSet(false, true)) {
                abortAllActiveResources();
            }
        }

        public void recordFailure(@NonNull FailureReason reason) {
            if (reason == FailureReason.NONE) return;
            lastFailure.updateAndGet(prev -> {
                if (prev == FailureReason.TIMEOUT) return prev;
                if (reason == FailureReason.TIMEOUT) return reason;
                if (prev == FailureReason.NETWORK) return prev;
                return reason;
            });
        }

        @NonNull
        public FailureReason getLastFailureReason() {
            if (timedOut.get()) return FailureReason.TIMEOUT;
            return lastFailure.get();
        }

        public void registerCall(@NonNull Call call) {
            if (isCancelled()) {
                try {
                    call.cancel();
                } catch (Exception ignored) {
                }
                return;
            }
            activeCalls.add(call);
            if (isCancelled()) {
                activeCalls.remove(call);
                try {
                    call.cancel();
                } catch (Exception ignored) {
                }
            }
        }

        public void unregisterCall(@NonNull Call call) {
            activeCalls.remove(call);
        }

        public void registerCloseable(@NonNull Closeable closeable) {
            if (isCancelled()) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
                return;
            }
            activeResources.add(closeable);
            if (isCancelled()) {
                activeResources.remove(closeable);
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }

        public void unregisterCloseable(@NonNull Closeable closeable) {
            activeResources.remove(closeable);
        }

        private void abortAllActiveResources() {
            for (Call call : activeCalls) {
                try {
                    call.cancel();
                } catch (Exception ignored) {
                }
            }
            activeCalls.clear();

            for (Closeable res : activeResources) {
                try {
                    res.close();
                } catch (Exception ignored) {
                }
            }
            activeResources.clear();
        }
    }

    public static final class ExtractedFingerprint {
        @NonNull public final byte[] data;
        public final int frameCount;
        public final double actualStartSec;

        public ExtractedFingerprint(@NonNull byte[] data, int frameCount) {
            this(data, frameCount, 0.0);
        }

        public ExtractedFingerprint(@NonNull byte[] data, int frameCount, double actualStartSec) {
            this.data = data;
            this.frameCount = frameCount;
            this.actualStartSec = actualStartSec;
        }
    }

    /**
     * Переиспользуемая сессия быстрого выборочного чтения (Sparse Probing):
     * - для HLS один раз парсит плейлист минимального качества, кэширует init-сегмент и AES-ключи;
     * - для MP4 и локальных файлов один раз открывает MediaExtractor (moov парсится один раз!),
     *   а затем мгновенно прыгает на нужные 18с зонды без скачивания промежутков.
     */
    public static final class AudioProbeSession implements Closeable {
        private final boolean isHls;
        private final File cacheDir;
        private final OkHttpClient okHttpClient;
        private final CancelSignal cancelSignal;

        // HLS state
        @Nullable private final HlsPlaylistParser.ParsedMediaPlaylist hlsPlaylist;
        @Nullable private final byte[] hlsInitBytes;
        @Nullable private final Map<String, byte[]> hlsKeyCache;
        private final double totalHlsDurationSec;

        // Local / MP4 reusable extractor state
        @Nullable private final HttpRangeMediaDataSource remoteDataSource;
        @Nullable private final MediaExtractor sharedExtractor;
        @Nullable private final MediaFormat audioFormat;
        @Nullable private final String audioMime;
        private final int sampleRate;
        private final int channelCount;
        private final double totalMediaDurationSec;

        private AudioProbeSession(@NonNull HlsPlaylistParser.ParsedMediaPlaylist playlist,
                                  @Nullable byte[] initBytes,
                                  @NonNull OkHttpClient okHttpClient,
                                  @NonNull File cacheDir,
                                  @NonNull CancelSignal cancelSignal) {
            this.isHls = true;
            this.hlsPlaylist = playlist;
            this.hlsInitBytes = initBytes;
            this.hlsKeyCache = new ConcurrentHashMap<>();
            this.okHttpClient = okHttpClient;
            this.cacheDir = cacheDir;
            this.cancelSignal = cancelSignal;

            double sum = 0.0;
            for (HlsPlaylistParser.HlsSegment seg : playlist.segments) {
                sum += (seg.durationSec > 0.0 ? seg.durationSec : 6.0);
            }
            this.totalHlsDurationSec = sum;

            this.remoteDataSource = null;
            this.sharedExtractor = null;
            this.audioFormat = null;
            this.audioMime = null;
            this.sampleRate = 48000;
            this.channelCount = 2;
            this.totalMediaDurationSec = sum;
        }

        private AudioProbeSession(@Nullable HttpRangeMediaDataSource remoteDataSource,
                                  @NonNull MediaExtractor extractor,
                                  @NonNull MediaFormat audioFormat,
                                  @NonNull String audioMime,
                                  int sampleRate,
                                  int channelCount,
                                  double totalMediaDurationSec,
                                  @NonNull OkHttpClient okHttpClient,
                                  @NonNull File cacheDir,
                                  @NonNull CancelSignal cancelSignal) {
            this.isHls = false;
            this.hlsPlaylist = null;
            this.hlsInitBytes = null;
            this.hlsKeyCache = null;
            this.totalHlsDurationSec = 0.0;

            this.remoteDataSource = remoteDataSource;
            this.sharedExtractor = extractor;
            this.audioFormat = audioFormat;
            this.audioMime = audioMime;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.totalMediaDurationSec = totalMediaDurationSec;
            this.okHttpClient = okHttpClient;
            this.cacheDir = cacheDir;
            this.cancelSignal = cancelSignal;
        }

        public double getKnownDurationSec() {
            return isHls ? totalHlsDurationSec : totalMediaDurationSec;
        }

        @Nullable
        public static AudioProbeSession open(@NonNull String sourceUrl,
                                             @NonNull OkHttpClient okHttpClient,
                                             @NonNull File cacheDir,
                                             @NonNull CancelSignal cancelSignal) {
            if (cancelSignal.isCancelled()) return null;
            try {
                if (isLocalSource(sourceUrl)) {
                    String path = resolveLocalPath(sourceUrl);
                    if (path == null || !new File(path).exists()) {
                        cancelSignal.recordFailure(FailureReason.DECODE);
                        return null;
                    }
                    return openExtractorSession(path, null, okHttpClient, cacheDir, cancelSignal);
                } else if (isHlsSource(sourceUrl)) {
                    HlsPlaylistParser.ParsedMediaPlaylist playlist =
                            resolveLowestQualityMediaPlaylist(sourceUrl, okHttpClient, cancelSignal);
                    if (playlist == null || playlist.segments.isEmpty() || cancelSignal.isCancelled()) {
                        if (!cancelSignal.isCancelled()) {
                            cancelSignal.recordFailure(FailureReason.NETWORK);
                        }
                        return null;
                    }
                    byte[] initBytes = fetchOptionalInitSegment(playlist.initSegmentUrl, okHttpClient, cancelSignal);
                    if (cancelSignal.isCancelled()) return null;
                    return new AudioProbeSession(playlist, initBytes, okHttpClient, cacheDir, cancelSignal);
                } else {
                    HttpRangeMediaDataSource dataSource = new HttpRangeMediaDataSource(okHttpClient, sourceUrl, cancelSignal);
                    AudioProbeSession session = openExtractorSession(null, dataSource, okHttpClient, cacheDir, cancelSignal);
                    if (session == null) {
                        dataSource.close();
                    }
                    return session;
                }
            } catch (Exception e) {
                if (!cancelSignal.isCancelled()) {
                    Log.w(TAG, "AudioProbeSession.open failed for " + sourceUrl + ": " + e.getMessage());
                    cancelSignal.recordFailure(classifyException(e));
                }
                return null;
            }
        }

        @Nullable
        private static AudioProbeSession openExtractorSession(@Nullable String localPath,
                                                              @Nullable HttpRangeMediaDataSource remoteSource,
                                                              @NonNull OkHttpClient okHttpClient,
                                                              @NonNull File cacheDir,
                                                              @NonNull CancelSignal cancelSignal) throws IOException {
            MediaExtractor extractor = new MediaExtractor();
            boolean ok = false;
            try {
                if (localPath != null) {
                    extractor.setDataSource(localPath);
                } else if (remoteSource != null) {
                    extractor.setDataSource(remoteSource);
                } else {
                    return null;
                }
                if (cancelSignal.isCancelled()) return null;

                int audioTrack = findAudioTrack(extractor);
                if (audioTrack < 0) {
                    Log.w(TAG, "No audio track found in media source");
                    cancelSignal.recordFailure(FailureReason.DECODE);
                    return null;
                }
                extractor.selectTrack(audioTrack);
                MediaFormat format = extractor.getTrackFormat(audioTrack);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    cancelSignal.recordFailure(FailureReason.DECODE);
                    return null;
                }

                int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
                int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
                double durSec = format.containsKey(MediaFormat.KEY_DURATION)
                        ? (format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0) : 0.0;

                ok = true;
                return new AudioProbeSession(remoteSource, extractor, format, mime, rate, channels,
                        durSec, okHttpClient, cacheDir, cancelSignal);
            } finally {
                if (!ok) {
                    try {
                        extractor.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        /**
         * Извлекает аудио-отпечаток для точного окна [startSec .. endSec].
         */
        @Nullable
        public ExtractedFingerprint extractWindow(double startSec, double endSec) {
            if (cancelSignal.isCancelled() || endSec <= startSec) return null;
            double safeStart = Math.max(0.0, startSec);
            try {
                if (isHls) {
                    if (hlsPlaylist == null || hlsKeyCache == null) return null;
                    return extractHlsWindowFromPlaylist(hlsPlaylist, hlsInitBytes, hlsKeyCache,
                            safeStart, endSec, okHttpClient, cacheDir, cancelSignal);
                } else {
                    if (sharedExtractor == null || audioFormat == null || audioMime == null) return null;
                    return decodeFromOpenExtractor(sharedExtractor, audioFormat, audioMime,
                            sampleRate, channelCount, safeStart, endSec, false, cancelSignal);
                }
            } catch (Exception e) {
                if (!cancelSignal.isCancelled()) {
                    Log.w(TAG, "extractWindow [" + safeStart + ".." + endSec + "] failed: " + e.getMessage());
                    cancelSignal.recordFailure(classifyException(e));
                }
                return null;
            }
        }

        @Override
        public void close() {
            if (sharedExtractor != null) {
                try {
                    sharedExtractor.release();
                } catch (Exception ignored) {
                }
            }
            if (remoteDataSource != null) {
                try {
                    remoteDataSource.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static boolean isLocalSource(@Nullable String sourceUrl) {
        if (sourceUrl == null) return false;
        return sourceUrl.startsWith("file://") || sourceUrl.startsWith("/");
    }

    public static boolean isHlsSource(@Nullable String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isEmpty() || isLocalSource(sourceUrl)) return false;
        String lower = sourceUrl.toLowerCase(Locale.ROOT);
        return lower.contains(".m3u8")
                || lower.contains(":hls:")
                || (ApiService.isKodikStreamUrl(sourceUrl) && !lower.endsWith(".mp4"));
    }

    @Nullable
    public static String resolveLocalPath(@NonNull String sourceUrl) {
        if (sourceUrl.startsWith("file://")) {
            Uri uri = Uri.parse(sourceUrl);
            return uri.getPath();
        }
        return sourceUrl;
    }

    /**
     * Извлекает аудио-отпечаток строго для указанного интервала [startSec, endSec] (для запоминания опенинга).
     */
    @Nullable
    public static ExtractedFingerprint extractRangeFingerprint(@NonNull String sourceUrl,
                                                               double startSec,
                                                               double endSec,
                                                               @NonNull OkHttpClient okHttpClient,
                                                               @NonNull File cacheDir,
                                                               @NonNull CancelSignal cancelSignal) {
        if (endSec <= startSec || cancelSignal.isCancelled()) return null;

        try (AudioProbeSession session = AudioProbeSession.open(sourceUrl, okHttpClient, cacheDir, cancelSignal)) {
            if (session == null || cancelSignal.isCancelled()) return null;
            return session.extractWindow(startSec, endSec);
        } catch (Exception e) {
            if (!cancelSignal.isCancelled()) {
                Log.w(TAG, "extractRangeFingerprint failed for " + sourceUrl + ": " + e.getMessage());
                cancelSignal.recordFailure(classifyException(e));
            }
            return null;
        }
    }

    @Nullable
    private static ExtractedFingerprint extractHlsWindowFromPlaylist(@NonNull HlsPlaylistParser.ParsedMediaPlaylist playlist,
                                                                     @Nullable byte[] initBytes,
                                                                     @NonNull Map<String, byte[]> keyCache,
                                                                     double startSec,
                                                                     double endSec,
                                                                     @NonNull OkHttpClient okHttpClient,
                                                                     @NonNull File cacheDir,
                                                                     @NonNull CancelSignal cancelSignal) throws Exception {
        List<HlsPlaylistParser.HlsSegment> selected = new ArrayList<>();
        double cumulativeSec = 0.0;
        double firstSegStartSec = 0.0;
        boolean foundFirst = false;

        for (HlsPlaylistParser.HlsSegment seg : playlist.segments) {
            double segDur = seg.durationSec > 0.0 ? seg.durationSec : 6.0;
            double segEnd = cumulativeSec + segDur;
            if (segEnd > startSec && cumulativeSec < endSec) {
                if (!foundFirst) {
                    firstSegStartSec = cumulativeSec;
                    foundFirst = true;
                }
                selected.add(seg);
            }
            cumulativeSec = segEnd;
            if (cumulativeSec >= endSec + 0.5) break;
        }

        if (selected.isEmpty() || cancelSignal.isCancelled()) {
            return null;
        }

        File tempFile = writeHlsBatchToTempFile(selected, initBytes, keyCache, okHttpClient, cacheDir, cancelSignal);
        if (tempFile == null) return null;

        try {
            double relStart = Math.max(0.0, startSec - firstSegStartSec);
            double relEnd = Math.max(relStart + 1.0, endSec - firstSegStartSec);
            ExtractedFingerprint fp = decodeTempFileRelative(tempFile.getAbsolutePath(), relStart, relEnd, cancelSignal);
            if (fp == null) return null;
            double actualStart = firstSegStartSec + relStart;
            return new ExtractedFingerprint(fp.data, fp.frameCount, actualStart);
        } finally {
            safeDelete(tempFile);
        }
    }

    @Nullable
    private static ExtractedFingerprint decodeTempFileRelative(@NonNull String localPath,
                                                               double relStartSec,
                                                               double relEndSec,
                                                               @NonNull CancelSignal cancelSignal) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(localPath);
            int audioTrack = findAudioTrack(extractor);
            if (audioTrack < 0) {
                cancelSignal.recordFailure(FailureReason.DECODE);
                return null;
            }
            extractor.selectTrack(audioTrack);
            MediaFormat format = extractor.getTrackFormat(audioTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                cancelSignal.recordFailure(FailureReason.DECODE);
                return null;
            }
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            return decodeFromOpenExtractor(extractor, format, mime, sampleRate, channelCount,
                    relStartSec, relEndSec, true, cancelSignal);
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Декодирует аудио-окно из открытого MediaExtractor через неблокирующую прокачку MediaCodec.
     * Имеет жёсткую защиту от зависания декодера (totalIdleLoops + MAX_WINDOW_DECODE_MS + cancelSignal).
     */
    @Nullable
    private static ExtractedFingerprint decodeFromOpenExtractor(@NonNull MediaExtractor extractor,
                                                                @NonNull MediaFormat format,
                                                                @NonNull String mime,
                                                                int initialSampleRate,
                                                                int initialChannelCount,
                                                                double startSec,
                                                                double endSec,
                                                                boolean relativeToFirstSample,
                                                                @NonNull CancelSignal cancelSignal) throws IOException {
        if (cancelSignal.isCancelled() || endSec <= startSec) return null;

        long startUs;
        long endUs;

        if (relativeToFirstSample) {
            long firstPtsUs = extractor.getSampleTime();
            long basePtsUs = firstPtsUs > 0L ? firstPtsUs : 0L;
            startUs = basePtsUs + Math.max(0L, Math.round(startSec * 1_000_000.0));
            endUs = Double.isInfinite(endSec)
                    ? Long.MAX_VALUE
                    : (basePtsUs + Math.round(endSec * 1_000_000.0));
        } else {
            startUs = Math.max(0L, Math.round(startSec * 1_000_000.0));
            endUs = Math.round(endSec * 1_000_000.0);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }

        int sampleRate = initialSampleRate;
        int channelCount = initialChannelCount;
        MediaCodec codec = null;
        long decodeStartMs = SystemClock.elapsedRealtime();

        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int expectedFrames = (int) Math.ceil((endSec - startSec) * AudioFingerprintEngine.FRAMES_PER_SECOND) + 16;
            AudioFingerprintEngine.StreamingFingerprintBuilder builder =
                    new AudioFingerprintEngine.StreamingFingerprintBuilder(sampleRate, Math.min(expectedFrames, 2048));

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            byte[] transferBuf = new byte[16384];
            boolean inputDone = false;
            boolean outputDone = false;
            int totalIdleLoops = 0;
            long firstEmittedPtsUs = -1L;

            while (!outputDone && !cancelSignal.isCancelled()) {
                if (SystemClock.elapsedRealtime() - decodeStartMs > MAX_WINDOW_DECODE_MS) {
                    Log.w(TAG, "MediaCodec decode window timed out after " + MAX_WINDOW_DECODE_MS + "ms");
                    cancelSignal.recordFailure(FailureReason.TIMEOUT);
                    break;
                }

                boolean madeProgress = false;

                // 1. Неблокирующая подача всех доступных входных пакетов
                while (!inputDone && !cancelSignal.isCancelled()) {
                    int inIndex = codec.dequeueInputBuffer(0L);
                    if (inIndex < 0) {
                        break;
                    }
                    madeProgress = true;
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    if (inBuf != null) {
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleSize < 0 || (endUs != Long.MAX_VALUE && sampleTimeUs > endUs + 500_000L)) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, Math.max(0L, sampleTimeUs), 0);
                            extractor.advance();
                        }
                    }
                }

                // 2. Неблокирующая выгрузка всех готовых PCM-буферов
                while (!outputDone && !cancelSignal.isCancelled()) {
                    int outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L);
                    if (outIndex >= 0) {
                        madeProgress = true;
                        if (bufferInfo.size > 0) {
                            ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                            if (outBuf != null) {
                                long ptsUs = bufferInfo.presentationTimeUs;
                                if (ptsUs >= startUs - 50_000L && (endUs == Long.MAX_VALUE || ptsUs <= endUs + 50_000L)) {
                                    if (firstEmittedPtsUs < 0L) {
                                        firstEmittedPtsUs = ptsUs;
                                    }
                                    outBuf.position(bufferInfo.offset);
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size);
                                    if (transferBuf.length < bufferInfo.size) {
                                        transferBuf = new byte[bufferInfo.size];
                                    }
                                    outBuf.get(transferBuf, 0, bufferInfo.size);
                                    builder.feedPcm16(transferBuf, 0, bufferInfo.size, channelCount);
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false);

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                || (endUs != Long.MAX_VALUE && bufferInfo.presentationTimeUs >= endUs)) {
                            outputDone = true;
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        madeProgress = true;
                        MediaFormat outFormat = codec.getOutputFormat();
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            int actualRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            if (actualRate != sampleRate && builder.getFrameCount() == 0) {
                                sampleRate = actualRate;
                                builder = new AudioFingerprintEngine.StreamingFingerprintBuilder(sampleRate, Math.min(expectedFrames, 2048));
                            }
                        }
                    } else {
                        break;
                    }
                }

                // 3. Короткое ожидание при простое обоих буферов (с общим счётчиком для защиты от зависания)
                if (!madeProgress && !outputDone && !cancelSignal.isCancelled()) {
                    int outIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_IDLE_WAIT_US);
                    if (outIndex >= 0) {
                        totalIdleLoops = 0;
                        if (bufferInfo.size > 0) {
                            ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                            if (outBuf != null) {
                                long ptsUs = bufferInfo.presentationTimeUs;
                                if (ptsUs >= startUs - 50_000L && (endUs == Long.MAX_VALUE || ptsUs <= endUs + 50_000L)) {
                                    if (firstEmittedPtsUs < 0L) {
                                        firstEmittedPtsUs = ptsUs;
                                    }
                                    outBuf.position(bufferInfo.offset);
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size);
                                    if (transferBuf.length < bufferInfo.size) {
                                        transferBuf = new byte[bufferInfo.size];
                                    }
                                    outBuf.get(transferBuf, 0, bufferInfo.size);
                                    builder.feedPcm16(transferBuf, 0, bufferInfo.size, channelCount);
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false);
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                || (endUs != Long.MAX_VALUE && bufferInfo.presentationTimeUs >= endUs)) {
                            outputDone = true;
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        totalIdleLoops = 0;
                        MediaFormat outFormat = codec.getOutputFormat();
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            int actualRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            if (actualRate != sampleRate && builder.getFrameCount() == 0) {
                                sampleRate = actualRate;
                                builder = new AudioFingerprintEngine.StreamingFingerprintBuilder(sampleRate, Math.min(expectedFrames, 2048));
                            }
                        }
                    } else {
                        totalIdleLoops++;
                        if (totalIdleLoops > MAX_CODEC_IDLE_LOOPS) {
                            Log.w(TAG, "MediaCodec stalled (idleLoops=" + totalIdleLoops + ", inputDone=" + inputDone + ")");
                            outputDone = true;
                        }
                    }
                } else {
                    totalIdleLoops = 0;
                }
            }

            if (cancelSignal.isCancelled()) {
                return null;
            }
            int frames = builder.getFrameCount();
            double actualStartSec = (!relativeToFirstSample && firstEmittedPtsUs >= 0L)
                    ? (firstEmittedPtsUs / 1_000_000.0)
                    : startSec;
            if (frames <= 0) {
                cancelSignal.recordFailure(FailureReason.DECODE);
                return null;
            }
            return new ExtractedFingerprint(builder.copyFramesData(), frames, actualStartSec);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                try {
                    codec.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Nullable
    private static HlsPlaylistParser.ParsedMediaPlaylist resolveLowestQualityMediaPlaylist(
            @NonNull String playlistUrl,
            @NonNull OkHttpClient client,
            @NonNull CancelSignal cancelSignal) throws IOException {
        if (cancelSignal.isCancelled()) return null;
        String text = fetchText(client, playlistUrl, cancelSignal);
        if (text == null || text.isEmpty() || cancelSignal.isCancelled()) return null;

        String mediaUrl = playlistUrl;
        String mediaText = text;
        if (HlsPlaylistParser.isMasterPlaylist(text)) {
            String lowestVariant = HlsPlaylistParser.selectLowestBandwidthVariantUrl(text, playlistUrl);
            if (lowestVariant != null && !lowestVariant.isEmpty()) {
                mediaUrl = lowestVariant;
                mediaText = fetchText(client, mediaUrl, cancelSignal);
            }
        }
        if (mediaText == null || mediaText.isEmpty() || cancelSignal.isCancelled()) return null;
        return HlsPlaylistParser.parseMediaPlaylist(mediaText, mediaUrl);
    }

    @Nullable
    private static byte[] fetchOptionalInitSegment(@Nullable String initUrl,
                                                   @NonNull OkHttpClient client,
                                                   @NonNull CancelSignal cancelSignal) throws IOException {
        if (initUrl == null || initUrl.isEmpty() || cancelSignal.isCancelled()) return null;
        return fetchBytes(client, initUrl, null, cancelSignal);
    }

    /**
     * Загружает пачку HLS-сегментов (до 2 потоков, чтобы не мешать основному видеопотоку)
     * с жёсткими таймаутами и проверкой полноты пачки.
     */
    @Nullable
    private static File writeHlsBatchToTempFile(@NonNull List<HlsPlaylistParser.HlsSegment> segments,
                                                @Nullable byte[] initBytes,
                                                @NonNull Map<String, byte[]> keyCache,
                                                @NonNull OkHttpClient client,
                                                @NonNull File cacheDir,
                                                @NonNull CancelSignal cancelSignal) throws Exception {
        if (segments.isEmpty() || cancelSignal.isCancelled()) return null;

        for (HlsPlaylistParser.HlsSegment seg : segments) {
            if (cancelSignal.isCancelled()) return null;
            if (seg.isAes128Encrypted() && seg.keyUrl != null && !keyCache.containsKey(seg.keyUrl)) {
                byte[] keyBytes = fetchBytes(client, seg.keyUrl, null, cancelSignal);
                if (keyBytes != null && keyBytes.length == 16) {
                    keyCache.put(seg.keyUrl, keyBytes);
                } else if (!cancelSignal.isCancelled()) {
                    cancelSignal.recordFailure(FailureReason.NETWORK);
                    return null;
                }
            }
        }

        int count = segments.size();
        byte[][] downloaded = new byte[count][];
        Set<Call> activeCalls = ConcurrentHashMap.newKeySet();
        int succeededCount = 0;

        if (count == 1) {
            downloaded[0] = fetchAndDecryptSegment(segments.get(0), keyCache, client, activeCalls, cancelSignal);
            if (downloaded[0] != null && downloaded[0].length > 0) {
                succeededCount = 1;
            }
        } else {
            int threads = Math.min(HLS_PARALLEL_THREADS, count);
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "HlsSegFetch");
                t.setDaemon(true);
                return t;
            });
            List<Future<byte[]>> futures = new ArrayList<>(count);
            try {
                for (int i = 0; i < count; i++) {
                    final HlsPlaylistParser.HlsSegment seg = segments.get(i);
                    futures.add(pool.submit(() -> fetchAndDecryptSegment(seg, keyCache, client, activeCalls, cancelSignal)));
                }
                int consecutiveFailures = 0;
                for (int i = 0; i < count; i++) {
                    if (cancelSignal.isCancelled()) {
                        cancelAllCalls(activeCalls);
                        return null;
                    }
                    long waitMs = Math.min(MAX_HLS_SEGMENT_WAIT_MS, cancelSignal.remainingMs());
                    if (waitMs <= 0L) {
                        cancelSignal.markTimedOut();
                        cancelAllCalls(activeCalls);
                        return null;
                    }
                    try {
                        downloaded[i] = futures.get(i).get(waitMs, TimeUnit.MILLISECONDS);
                        if (downloaded[i] != null && downloaded[i].length > 0) {
                            succeededCount++;
                            consecutiveFailures = 0;
                        } else {
                            consecutiveFailures++;
                        }
                    } catch (TimeoutException te) {
                        futures.get(i).cancel(true);
                        cancelSignal.recordFailure(FailureReason.TIMEOUT);
                        consecutiveFailures++;
                        downloaded[i] = null;
                    } catch (Exception e) {
                        if (cancelSignal.isCancelled()) {
                            cancelAllCalls(activeCalls);
                            return null;
                        }
                        cancelSignal.recordFailure(classifyException(e));
                        consecutiveFailures++;
                        downloaded[i] = null;
                    }

                    // Если 2 сегмента подряд не загрузились — рвём пачку сразу, не ждём остальные на мёртвом инете
                    if (consecutiveFailures >= 2 && succeededCount == 0) {
                        cancelAllCalls(activeCalls);
                        for (int j = i + 1; j < count; j++) {
                            futures.get(j).cancel(true);
                        }
                        cancelSignal.recordFailure(FailureReason.NETWORK);
                        return null;
                    }
                }
            } finally {
                cancelAllCalls(activeCalls);
                pool.shutdownNow();
            }
        }

        if (cancelSignal.isCancelled()) return null;

        // Если загружено меньше половины сегментов (или 0) — не кормим битый обрывок декодеру
        int minRequired = count <= 2 ? count : Math.max(2, (int) Math.ceil(count * 0.6));
        if (succeededCount < minRequired) {
            cancelSignal.recordFailure(FailureReason.NETWORK);
            return null;
        }

        String suffix = (initBytes != null && initBytes.length > 0) ? ".mp4" : ".ts";
        File tempFile = File.createTempFile("op_audio_", suffix, cacheDir);
        boolean success = false;
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            if (initBytes != null && initBytes.length > 0) {
                fos.write(initBytes);
            }
            for (int i = 0; i < count; i++) {
                if (cancelSignal.isCancelled()) return null;
                byte[] segBytes = downloaded[i];
                if (segBytes != null && segBytes.length > 0) {
                    fos.write(segBytes);
                }
            }
            fos.flush();
            success = tempFile.length() > 0;
            return success ? tempFile : null;
        } finally {
            if (!success) {
                safeDelete(tempFile);
            }
        }
    }

    @Nullable
    private static byte[] fetchAndDecryptSegment(@NonNull HlsPlaylistParser.HlsSegment seg,
                                                 @NonNull Map<String, byte[]> keyCache,
                                                 @NonNull OkHttpClient client,
                                                 @NonNull Set<Call> activeCalls,
                                                 @NonNull CancelSignal cancelSignal) throws Exception {
        if (cancelSignal.isCancelled()) return null;
        byte[] segBytes = fetchBytes(client, seg.url, activeCalls, cancelSignal);
        if (segBytes == null || segBytes.length == 0 || cancelSignal.isCancelled()) {
            return null;
        }
        if (seg.isAes128Encrypted() && seg.keyUrl != null && seg.iv != null) {
            byte[] keyBytes = keyCache.get(seg.keyUrl);
            if (keyBytes == null) {
                keyBytes = fetchBytes(client, seg.keyUrl, activeCalls, cancelSignal);
                if (keyBytes != null && keyBytes.length == 16) {
                    keyCache.put(seg.keyUrl, keyBytes);
                }
            }
            if (keyBytes != null && keyBytes.length == 16) {
                segBytes = HlsPlaylistParser.decryptAes128(segBytes, keyBytes, seg.iv);
            }
        }
        return segBytes;
    }

    private static void cancelAllCalls(@NonNull Set<Call> calls) {
        for (Call c : calls) {
            try {
                c.cancel();
            } catch (Exception ignored) {
            }
        }
        calls.clear();
    }

    @Nullable
    private static String fetchText(@NonNull OkHttpClient client,
                                    @NonNull String url,
                                    @NonNull CancelSignal cancelSignal) throws IOException {
        if (cancelSignal.isCancelled()) return null;
        Request req = new Request.Builder().url(url).build();
        Call call = client.newCall(req);
        cancelSignal.registerCall(call);
        try (Response resp = call.execute()) {
            if (cancelSignal.isCancelled()) return null;
            if (!resp.isSuccessful()) {
                cancelSignal.recordFailure(FailureReason.NETWORK);
                return null;
            }
            ResponseBody body = resp.body();
            return body != null ? body.string() : null;
        } catch (IOException e) {
            if (!cancelSignal.isCancelled()) {
                cancelSignal.recordFailure(classifyException(e));
            }
            throw e;
        } finally {
            cancelSignal.unregisterCall(call);
        }
    }

    @Nullable
    private static byte[] fetchBytes(@NonNull OkHttpClient client,
                                     @NonNull String url,
                                     @Nullable Set<Call> activeCalls,
                                     @NonNull CancelSignal cancelSignal) throws IOException {
        if (cancelSignal.isCancelled()) return null;
        Request req = new Request.Builder().url(url).build();
        Call call = client.newCall(req);
        if (activeCalls != null) {
            activeCalls.add(call);
        }
        cancelSignal.registerCall(call);
        try (Response resp = call.execute()) {
            if (cancelSignal.isCancelled()) return null;
            if (!resp.isSuccessful()) {
                cancelSignal.recordFailure(FailureReason.NETWORK);
                return null;
            }
            ResponseBody body = resp.body();
            if (body == null) return null;
            // Читаем потоково с проверкой отмены на каждом чанке
            try (InputStream is = body.byteStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
                byte[] buf = new byte[16384];
                int read;
                while ((read = is.read(buf)) != -1) {
                    if (cancelSignal.isCancelled()) {
                        return null;
                    }
                    baos.write(buf, 0, read);
                }
                return baos.toByteArray();
            }
        } catch (IOException e) {
            if (!cancelSignal.isCancelled()) {
                cancelSignal.recordFailure(classifyException(e));
            }
            throw e;
        } finally {
            if (activeCalls != null) {
                activeCalls.remove(call);
            }
            cancelSignal.unregisterCall(call);
        }
    }

    @NonNull
    private static FailureReason classifyException(@Nullable Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SocketTimeoutException || cur instanceof TimeoutException) {
                return FailureReason.TIMEOUT;
            }
            if (cur instanceof IOException) {
                return FailureReason.NETWORK;
            }
            cur = cur.getCause();
        }
        return FailureReason.DECODE;
    }

    private static int findAudioTrack(@NonNull MediaExtractor extractor) {
        int trackCount = extractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static void safeDelete(@Nullable File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
