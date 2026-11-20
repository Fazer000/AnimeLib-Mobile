package com.example.animelib.audio;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.api.ApiService;
import com.example.animelib.util.HlsPlaylistParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Извлекает компактный спектральный аудио-отпечаток из любого поддерживаемого источника:
 * - локальные скачанные файлы (.mp4 / .ts)
 * - удалённые MP4 потоки AnimeLib (через HttpRangeMediaDataSource с предзагрузкой блоков + MediaExtractor)
 * - удалённые HLS (.m3u8) потоки Kodik (параллельная загрузка сегментов 360p + AES-128 + fMP4 init)
 */
public final class AudioPcmExtractor {

    private static final String TAG = "AudioPcmExtractor";
    private static final long CODEC_IDLE_WAIT_US = 1_500L;

    // Быстрые первые чекпоинты (28с -> 56с), чтобы мгновенно ловить опенинг в начале серии,
    // далее шаг 36с для экономии накладных расходов при длинном прологе.
    private static final int FIRST_CHECK_SEC = 28;
    private static final int SECOND_CHECK_SEC = 56;
    private static final int LATER_CHECK_STEP_SEC = 36;
    private static final int HLS_PARALLEL_THREADS = 4;

    private AudioPcmExtractor() {
    }

    public static final class ExtractedFingerprint {
        @NonNull public final byte[] data;
        public final int frameCount;

        public ExtractedFingerprint(@NonNull byte[] data, int frameCount) {
            this.data = data;
            this.frameCount = frameCount;
        }
    }

    public interface ProgressiveScanCallback {
        /**
         * Вызывается по мере декодирования очередного блока целевой серии (28с, 56с, 92с, ...).
         * @return true, если опенинг уже полностью найден и можно немедленно остановить сканирование.
         */
        boolean onProgress(@NonNull byte[] targetData, int targetFrames, boolean isFinalPass);
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
                                                               @NonNull AtomicBoolean cancelled) {
        if (endSec <= startSec || cancelled.get()) return null;

        try {
            if (isLocalSource(sourceUrl)) {
                String path = resolveLocalPath(sourceUrl);
                if (path == null || !new File(path).exists()) return null;
                return decodeExtractorRange(path, null, startSec, endSec, cancelled, null);
            } else if (isHlsSource(sourceUrl)) {
                return extractHlsRange(sourceUrl, startSec, endSec, okHttpClient, cacheDir, cancelled);
            } else {
                HttpRangeMediaDataSource dataSource = new HttpRangeMediaDataSource(okHttpClient, sourceUrl, cancelled);
                try {
                    return decodeExtractorRange(null, dataSource, startSec, endSec, cancelled, null);
                } finally {
                    dataSource.close();
                }
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                Log.w(TAG, "extractRangeFingerprint failed for " + sourceUrl + ": " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Прогрессивно сканирует аудио целевой серии от 0 до maxScanSec,
     * вызывая callback на быстрых контрольных точках (28с, 56с, 92с...) для мгновенного обнаружения.
     */
    public static void scanProgressive(@NonNull String sourceUrl,
                                       int maxScanSec,
                                       @NonNull OkHttpClient okHttpClient,
                                       @NonNull File cacheDir,
                                       @NonNull AtomicBoolean cancelled,
                                       @NonNull ProgressiveScanCallback callback) {
        if (maxScanSec <= 30 || cancelled.get()) return;

        try {
            if (isLocalSource(sourceUrl)) {
                String path = resolveLocalPath(sourceUrl);
                if (path == null || !new File(path).exists()) return;
                decodeExtractorRange(path, null, 0.0, maxScanSec, cancelled, callback);
            } else if (isHlsSource(sourceUrl)) {
                scanHlsProgressive(sourceUrl, maxScanSec, okHttpClient, cacheDir, cancelled, callback);
            } else {
                HttpRangeMediaDataSource dataSource = new HttpRangeMediaDataSource(okHttpClient, sourceUrl, cancelled);
                try {
                    decodeExtractorRange(null, dataSource, 0.0, maxScanSec, cancelled, callback);
                } finally {
                    dataSource.close();
                }
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                Log.w(TAG, "scanProgressive failed for " + sourceUrl + ": " + e.getMessage());
            }
        }
    }

    private static int computeNextCheckFrames(int currentFrames) {
        int firstFrames = FIRST_CHECK_SEC * AudioFingerprintEngine.FRAMES_PER_SECOND;
        int secondFrames = SECOND_CHECK_SEC * AudioFingerprintEngine.FRAMES_PER_SECOND;
        if (currentFrames < firstFrames) {
            return firstFrames;
        }
        if (currentFrames < secondFrames) {
            return secondFrames;
        }
        return currentFrames + (LATER_CHECK_STEP_SEC * AudioFingerprintEngine.FRAMES_PER_SECOND);
    }

    @Nullable
    private static ExtractedFingerprint decodeExtractorRange(@Nullable String localPath,
                                                             @Nullable HttpRangeMediaDataSource remoteSource,
                                                             double startSec,
                                                             double endSec,
                                                             @NonNull AtomicBoolean cancelled,
                                                             @Nullable ProgressiveScanCallback progressiveCallback) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            if (localPath != null) {
                extractor.setDataSource(localPath);
            } else if (remoteSource != null) {
                extractor.setDataSource(remoteSource);
            } else {
                return null;
            }

            int audioTrackIndex = findAudioTrack(extractor);
            if (audioTrackIndex < 0) {
                Log.w(TAG, "No audio track found in media source");
                return null;
            }

            extractor.selectTrack(audioTrackIndex);
            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) return null;

            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            long startUs = Math.max(0L, Math.round(startSec * 1_000_000.0));
            long endUs = Math.round(endSec * 1_000_000.0);

            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int expectedFrames = (int) Math.ceil((endSec - startSec) * AudioFingerprintEngine.FRAMES_PER_SECOND) + 16;
            AudioFingerprintEngine.StreamingFingerprintBuilder builder =
                    new AudioFingerprintEngine.StreamingFingerprintBuilder(sampleRate, expectedFrames);

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            byte[] transferBuf = new byte[16384];
            boolean inputDone = false;
            boolean outputDone = false;
            boolean earlyStopped = false;
            int nextProgressCheckFrames = FIRST_CHECK_SEC * AudioFingerprintEngine.FRAMES_PER_SECOND;
            int idleLoops = 0;

            while (!outputDone && !cancelled.get() && !earlyStopped) {
                boolean madeProgress = false;

                // 1. Неблокирующая подача всех доступных входных пакетов (без 10ms простоев)
                while (!inputDone && !cancelled.get()) {
                    int inIndex = codec.dequeueInputBuffer(0L);
                    if (inIndex < 0) {
                        break;
                    }
                    madeProgress = true;
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    if (inBuf != null) {
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleSize < 0 || sampleTimeUs > endUs + 500_000L) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, Math.max(0L, sampleTimeUs), 0);
                            extractor.advance();
                        }
                    }
                }

                // 2. Неблокирующая выгрузка всех готовых PCM-буферов
                while (!outputDone && !cancelled.get() && !earlyStopped) {
                    int outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L);
                    if (outIndex >= 0) {
                        madeProgress = true;
                        if (bufferInfo.size > 0) {
                            ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                            if (outBuf != null) {
                                long ptsUs = bufferInfo.presentationTimeUs;
                                if (ptsUs >= startUs - 50_000L && ptsUs <= endUs + 50_000L) {
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

                        if (progressiveCallback != null && builder.getFrameCount() >= nextProgressCheckFrames) {
                            byte[] snapshot = builder.copyFramesData();
                            if (progressiveCallback.onProgress(snapshot, builder.getFrameCount(), false)) {
                                earlyStopped = true;
                            } else {
                                nextProgressCheckFrames = computeNextCheckFrames(builder.getFrameCount());
                            }
                        }

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                || bufferInfo.presentationTimeUs >= endUs) {
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
                                builder = new AudioFingerprintEngine.StreamingFingerprintBuilder(sampleRate, expectedFrames);
                            }
                        }
                    } else {
                        break;
                    }
                }

                // 3. Короткое ожидание только когда и вход, и выход временно заняты
                if (!madeProgress && !outputDone && !earlyStopped && !cancelled.get()) {
                    int outIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_IDLE_WAIT_US);
                    if (outIndex >= 0) {
                        idleLoops = 0;
                        if (bufferInfo.size > 0) {
                            ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                            if (outBuf != null) {
                                long ptsUs = bufferInfo.presentationTimeUs;
                                if (ptsUs >= startUs - 50_000L && ptsUs <= endUs + 50_000L) {
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

                        if (progressiveCallback != null && builder.getFrameCount() >= nextProgressCheckFrames) {
                            byte[] snapshot = builder.copyFramesData();
                            if (progressiveCallback.onProgress(snapshot, builder.getFrameCount(), false)) {
                                earlyStopped = true;
                            } else {
                                nextProgressCheckFrames = computeNextCheckFrames(builder.getFrameCount());
                            }
                        }

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                || bufferInfo.presentationTimeUs >= endUs) {
                            outputDone = true;
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        idleLoops = 0;
                        MediaFormat outFormat = codec.getOutputFormat();
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            int actualRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            if (actualRate != sampleRate && builder.getFrameCount() == 0) {
                                sampleRate = actualRate;
                                builder = new AudioFingerprintEngine.StreamingFingerprintBuilder(sampleRate, expectedFrames);
                            }
                        }
                    } else if (inputDone) {
                        idleLoops++;
                        if (idleLoops > 250) {
                            outputDone = true;
                        }
                    }
                } else {
                    idleLoops = 0;
                }
            }

            if (cancelled.get()) {
                return null;
            }

            byte[] finalData = builder.copyFramesData();
            int finalFrames = builder.getFrameCount();
            if (progressiveCallback != null && !earlyStopped && finalFrames > 0) {
                progressiveCallback.onProgress(finalData, finalFrames, true);
            }
            return finalFrames > 0 ? new ExtractedFingerprint(finalData, finalFrames) : null;
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
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    @Nullable
    private static ExtractedFingerprint extractHlsRange(@NonNull String playlistUrl,
                                                        double startSec,
                                                        double endSec,
                                                        @NonNull OkHttpClient okHttpClient,
                                                        @NonNull File cacheDir,
                                                        @NonNull AtomicBoolean cancelled) throws Exception {
        HlsPlaylistParser.ParsedMediaPlaylist playlist = resolveLowestQualityMediaPlaylist(playlistUrl, okHttpClient, cancelled);
        if (playlist == null || playlist.segments.isEmpty() || cancelled.get()) {
            return null;
        }

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
            if (cumulativeSec >= endSec + 1.0) break;
        }

        if (selected.isEmpty() || cancelled.get()) {
            return null;
        }

        byte[] initBytes = fetchOptionalInitSegment(playlist.initSegmentUrl, okHttpClient, cancelled);
        Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
        File tempFile = writeHlsBatchToTempFile(selected, initBytes, keyCache, okHttpClient, cacheDir, cancelled);
        if (tempFile == null) return null;

        try {
            double relStart = Math.max(0.0, startSec - firstSegStartSec);
            double relEnd = Math.max(relStart + 1.0, endSec - firstSegStartSec);
            return decodeExtractorRange(tempFile.getAbsolutePath(), null, relStart, relEnd, cancelled, null);
        } finally {
            safeDelete(tempFile);
        }
    }

    private static void scanHlsProgressive(@NonNull String playlistUrl,
                                           int maxScanSec,
                                           @NonNull OkHttpClient okHttpClient,
                                           @NonNull File cacheDir,
                                           @NonNull AtomicBoolean cancelled,
                                           @NonNull ProgressiveScanCallback callback) throws Exception {
        HlsPlaylistParser.ParsedMediaPlaylist playlist = resolveLowestQualityMediaPlaylist(playlistUrl, okHttpClient, cancelled);
        if (playlist == null || playlist.segments.isEmpty() || cancelled.get()) {
            return;
        }

        byte[] initBytes = fetchOptionalInitSegment(playlist.initSegmentUrl, okHttpClient, cancelled);
        Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
        AudioFingerprintEngine.PositionedFingerprintBuffer targetBuf =
                new AudioFingerprintEngine.PositionedFingerprintBuffer(maxScanSec * AudioFingerprintEngine.FRAMES_PER_SECOND + 64);

        List<HlsPlaylistParser.HlsSegment> batch = new ArrayList<>();
        double cumulativeSec = 0.0;
        double batchStartSec = 0.0;
        double batchDurationSec = 0.0;
        int batchIndex = 0;
        boolean stoppedEarly = false;

        for (int i = 0; i < playlist.segments.size() && !cancelled.get() && !stoppedEarly; i++) {
            HlsPlaylistParser.HlsSegment seg = playlist.segments.get(i);
            double segDur = seg.durationSec > 0.0 ? seg.durationSec : 6.0;

            if (batch.isEmpty()) {
                batchStartSec = cumulativeSec;
            }
            batch.add(seg);
            batchDurationSec += segDur;
            cumulativeSec += segDur;

            boolean reachedMax = cumulativeSec >= maxScanSec;
            boolean isLastSeg = (i == playlist.segments.size() - 1);
            double targetBatchDur = (batchIndex < 2) ? FIRST_CHECK_SEC : LATER_CHECK_STEP_SEC;

            if (batchDurationSec >= targetBatchDur || reachedMax || isLastSeg) {
                File tempFile = writeHlsBatchToTempFile(batch, initBytes, keyCache, okHttpClient, cacheDir, cancelled);
                if (tempFile != null) {
                    try {
                        ExtractedFingerprint chunkFp = decodeExtractorRange(
                                tempFile.getAbsolutePath(), null, 0.0, batchDurationSec + 2.0, cancelled, null);
                        if (chunkFp != null && chunkFp.frameCount > 0) {
                            int targetStartFrame = (int) Math.round(batchStartSec * AudioFingerprintEngine.FRAMES_PER_SECOND);
                            targetBuf.putFramesAt(targetStartFrame, chunkFp.data, chunkFp.frameCount);
                        }
                    } finally {
                        safeDelete(tempFile);
                    }
                }
                batch.clear();
                batchDurationSec = 0.0;
                batchIndex++;

                if (!cancelled.get() && targetBuf.getFrameCount() > 0) {
                    boolean isFinal = reachedMax || isLastSeg;
                    if (callback.onProgress(targetBuf.getBackingArray(), targetBuf.getFrameCount(), isFinal)) {
                        stoppedEarly = true;
                    }
                }
                if (reachedMax) {
                    break;
                }
            }
        }
    }

    @Nullable
    private static HlsPlaylistParser.ParsedMediaPlaylist resolveLowestQualityMediaPlaylist(
            @NonNull String playlistUrl,
            @NonNull OkHttpClient client,
            @NonNull AtomicBoolean cancelled) throws IOException {
        if (cancelled.get()) return null;
        String text = fetchText(client, playlistUrl, cancelled);
        if (text == null || text.isEmpty() || cancelled.get()) return null;

        String mediaUrl = playlistUrl;
        String mediaText = text;
        if (HlsPlaylistParser.isMasterPlaylist(text)) {
            String lowestVariant = HlsPlaylistParser.selectBestVariantUrl(text, playlistUrl, "360p");
            if (lowestVariant != null && !lowestVariant.isEmpty()) {
                mediaUrl = lowestVariant;
                mediaText = fetchText(client, mediaUrl, cancelled);
            }
        }
        if (mediaText == null || mediaText.isEmpty() || cancelled.get()) return null;
        return HlsPlaylistParser.parseMediaPlaylist(mediaText, mediaUrl);
    }

    @Nullable
    private static byte[] fetchOptionalInitSegment(@Nullable String initUrl,
                                                   @NonNull OkHttpClient client,
                                                   @NonNull AtomicBoolean cancelled) throws IOException {
        if (initUrl == null || initUrl.isEmpty() || cancelled.get()) return null;
        return fetchBytes(client, initUrl, null, cancelled);
    }

    /**
     * Загружает пачку HLS-сегментов параллельно (до 4 потоков), сохраняя их строгий порядок при записи во временный файл.
     */
    @Nullable
    private static File writeHlsBatchToTempFile(@NonNull List<HlsPlaylistParser.HlsSegment> segments,
                                                @Nullable byte[] initBytes,
                                                @NonNull Map<String, byte[]> keyCache,
                                                @NonNull OkHttpClient client,
                                                @NonNull File cacheDir,
                                                @NonNull AtomicBoolean cancelled) throws Exception {
        if (segments.isEmpty() || cancelled.get()) return null;

        // Заранее подгружаем уникальные AES-128 ключи (обычно 1 ключ на всю серию)
        for (HlsPlaylistParser.HlsSegment seg : segments) {
            if (cancelled.get()) return null;
            if (seg.isAes128Encrypted() && seg.keyUrl != null && !keyCache.containsKey(seg.keyUrl)) {
                byte[] keyBytes = fetchBytes(client, seg.keyUrl, null, cancelled);
                if (keyBytes != null && keyBytes.length == 16) {
                    keyCache.put(seg.keyUrl, keyBytes);
                }
            }
        }

        int count = segments.size();
        byte[][] downloaded = new byte[count][];
        Set<Call> activeCalls = ConcurrentHashMap.newKeySet();

        if (count == 1) {
            downloaded[0] = fetchAndDecryptSegment(segments.get(0), keyCache, client, activeCalls, cancelled);
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
                    futures.add(pool.submit(() -> fetchAndDecryptSegment(seg, keyCache, client, activeCalls, cancelled)));
                }
                for (int i = 0; i < count; i++) {
                    if (cancelled.get()) {
                        cancelAllCalls(activeCalls);
                        return null;
                    }
                    try {
                        downloaded[i] = futures.get(i).get();
                    } catch (Exception e) {
                        if (cancelled.get()) {
                            cancelAllCalls(activeCalls);
                            return null;
                        }
                        downloaded[i] = null;
                    }
                }
            } finally {
                if (cancelled.get()) {
                    cancelAllCalls(activeCalls);
                }
                pool.shutdownNow();
            }
        }

        if (cancelled.get()) return null;

        String suffix = (initBytes != null && initBytes.length > 0) ? ".mp4" : ".ts";
        File tempFile = File.createTempFile("op_audio_", suffix, cacheDir);
        boolean success = false;
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            if (initBytes != null && initBytes.length > 0) {
                fos.write(initBytes);
            }
            for (int i = 0; i < count; i++) {
                if (cancelled.get()) return null;
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
                                                 @NonNull AtomicBoolean cancelled) throws Exception {
        if (cancelled.get()) return null;
        byte[] segBytes = fetchBytes(client, seg.url, activeCalls, cancelled);
        if (segBytes == null || segBytes.length == 0 || cancelled.get()) {
            return null;
        }
        if (seg.isAes128Encrypted() && seg.keyUrl != null && seg.iv != null) {
            byte[] keyBytes = keyCache.get(seg.keyUrl);
            if (keyBytes == null) {
                keyBytes = fetchBytes(client, seg.keyUrl, activeCalls, cancelled);
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
                                    @NonNull AtomicBoolean cancelled) throws IOException {
        if (cancelled.get()) return null;
        Request req = new Request.Builder().url(url).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            ResponseBody body = resp.body();
            return body != null ? body.string() : null;
        }
    }

    @Nullable
    private static byte[] fetchBytes(@NonNull OkHttpClient client,
                                     @NonNull String url,
                                     @Nullable Set<Call> activeCalls,
                                     @NonNull AtomicBoolean cancelled) throws IOException {
        if (cancelled.get()) return null;
        Request req = new Request.Builder().url(url).build();
        Call call = client.newCall(req);
        if (activeCalls != null) {
            activeCalls.add(call);
        }
        try (Response resp = call.execute()) {
            if (!resp.isSuccessful()) return null;
            ResponseBody body = resp.body();
            return body != null ? body.bytes() : null;
        } finally {
            if (activeCalls != null) {
                activeCalls.remove(call);
            }
        }
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
