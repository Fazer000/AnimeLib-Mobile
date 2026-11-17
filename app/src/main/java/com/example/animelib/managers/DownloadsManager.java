package com.example.animelib.managers;

import android.content.Context;
import android.util.Log;

import com.example.animelib.api.ApiClientCore;
import com.example.animelib.api.ApiService;
import com.example.animelib.api.KodikLinksExtractor;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.DownloadedAnimeEntity;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.DownloadTask;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.SiteUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Менеджер скачивания серий в локальный кэш приложения
 * Оптимизирован для снижения нагрузки на сервер и устранения ошибки 416.
 */
public class DownloadsManager {
    private static final String TAG = "DownloadsManager";
    private static final int BUFFER_SIZE = 128 * 1024; // 128 KB буфер
    private static final int MAX_HLS_THREADS = 3;      // До 3 параллельных потоков для сегментов

    public interface DownloadCallback {
        void onProgress(int percent);
        void onFinished(String localPath);
        void onError(String message);
    }

    private final Context context;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final ApiService apiService;
    private final DatabaseManager databaseManager;
    private final KodikLinksExtractor kodikExtractor;

    // Кэш разрешенных ссылок на видео для избежания лишних API-запросов
    private final Map<String, String> resolvedUrlCache = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile boolean cancelled;

    public DownloadsManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.client = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        this.apiService = new ApiService(this.context);
        this.databaseManager = new DatabaseManager(this.context);
        this.kodikExtractor = new KodikLinksExtractor(this.client, new Gson());
    }

    public boolean isRunning() {
        return running;
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * Скачивает указанную задачу скачивания эпизода
     */
    public void downloadTask(DownloadTask task, DownloadCallback callback) {
        if (running) {
            callback.onError("Скачивание уже идёт");
            return;
        }
        running = true;
        cancelled = false;

        executor.execute(() -> {
            resolveTaskTitleAndPosterIfNeeded(task);
            File outputFile = null;
            int maxRetries = 5;
            int attempt = 0;
            String lastError = null;

            String cacheKey = task.getEpisodeId() + "_" + task.getPlayerType() + "_" + task.getTeamName() + "_" + task.getQuality();

            while (attempt < maxRetries && !cancelled) {
                attempt++;
                try {
                    // 1. Извлекаем или берем из кэша ссылку на видео
                    List<EpisodeResponse.SubtitleData> subtitles = new ArrayList<>();
                    String videoUrl = resolvedUrlCache.get(cacheKey);

                    if (videoUrl == null || videoUrl.isEmpty()) {
                        videoUrl = resolveVideoUrl(task, subtitles);
                        if (videoUrl != null && !videoUrl.isEmpty()) {
                            resolvedUrlCache.put(cacheKey, videoUrl);
                        }
                    }

                    if (videoUrl == null || videoUrl.isEmpty()) {
                        finish(callback, null, "Не удалось получить ссылку на видео для серии " + task.getEpisodeNumber());
                        return;
                    }

                    // Нормализация схемы URL
                    if (videoUrl.startsWith("//")) {
                        videoUrl = "https:" + videoUrl;
                    } else if (videoUrl.startsWith("/")) {
                        videoUrl = "https://kodik.info" + videoUrl;
                    } else if (!videoUrl.startsWith("http://") && !videoUrl.startsWith("https://")) {
                        videoUrl = "https://" + videoUrl;
                    }

                    // 2. Создаем файл в кэше приложения
                    File dir = new File(context.getExternalFilesDir("cached_episodes"), task.getAnimeId());
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    String sanitizeEpNum = task.getEpisodeNumber() != null ? task.getEpisodeNumber().replaceAll("[^a-zA-Z0-9_.]", "_") : "0";
                    String sanitizeTeam = task.getTeamName() != null ? task.getTeamName().replaceAll("[^a-zA-Z0-9А-Яа-я_]", "_") : "team";
                    String fileName = "ep_" + task.getEpisodeId() + "_" + sanitizeEpNum + "_" + sanitizeTeam + ".mp4";
                    outputFile = new File(dir, fileName);

                    Log.d(TAG, "Download attempt " + attempt + "/" + maxRetries + " to: " + outputFile.getAbsolutePath() + " url: " + videoUrl);

                    // 3. Выполняем скачивание (HLS .m3u8 или прямой MP4)
                    if (videoUrl.contains(".m3u8")) {
                        downloadHlsStream(videoUrl, outputFile, callback);
                    } else {
                        downloadDirectFile(videoUrl, outputFile, "https://animelib.me/", callback);
                    }

                    if (cancelled) {
                        if (outputFile.exists()) outputFile.delete();
                        finish(callback, null, "Скачивание отменено");
                        return;
                    }

                    // 3b. Скачиваем субтитры если они доступны
                    downloadSubtitlesForTask(task, outputFile, subtitles);

                    // 4. Сохраняем информацию в базу данных Room
                    long now = System.currentTimeMillis();
                    String localPosterPath = downloadAndSavePoster(task.getAnimeId(), task.getPosterUrl());
                    DownloadedAnimeEntity animeEntity = new DownloadedAnimeEntity(
                            task.getAnimeId(),
                            task.getAnimeTitle() != null ? task.getAnimeTitle() : "Аниме #" + task.getAnimeId(),
                            localPosterPath != null ? localPosterPath : task.getPosterUrl(),
                            now
                    );
                    databaseManager.saveDownloadedAnime(animeEntity);

                    String epId = task.getAnimeId() + "_" + (task.getEpisodeId() != 0 ? task.getEpisodeId() : task.getEpisodeNumber()) + "_" + (task.getTeamName() != null ? task.getTeamName() : "");
                    String cleanEpName = com.example.animelib.VideoPlayerActivity.cleanEpisodeName(task.getEpisodeName(), task.getEpisodeNumber());
                    DownloadedEpisodeEntity episodeEntity = new DownloadedEpisodeEntity(
                            epId,
                            task.getAnimeId(),
                            task.getAnimeTitle(),
                            task.getEpisodeId(),
                            task.getEpisodeNumber(),
                            cleanEpName,
                            task.getTeamName(),
                            task.getPlayerType(),
                            outputFile.getAbsolutePath(),
                            outputFile.length(),
                            now,
                            task.getQuality()
                    );
                    databaseManager.saveDownloadedEpisode(episodeEntity);

                    Log.d(TAG, "Successfully downloaded and stored episode: " + epId);
                    finish(callback, outputFile.getAbsolutePath(), null);
                    return;

                } catch (Exception e) {
                    Log.w(TAG, "Download attempt " + attempt + " failed: " + e.getMessage(), e);
                    lastError = e.getMessage() != null ? e.getMessage() : "Ошибка скачивания";

                    // Если ссылка устарела (403/410), сбрасываем кэш ссылки
                    if (lastError.contains("403") || lastError.contains("410")) {
                        resolvedUrlCache.remove(cacheKey);
                    }

                    if (cancelled) {
                        if (outputFile != null && outputFile.exists()) outputFile.delete();
                        finish(callback, null, "Скачивание отменено");
                        return;
                    }

                    if (attempt < maxRetries) {
                        try {
                            if (lastError.contains("429") || lastError.contains("503")) {
                                Thread.sleep(5000);
                            } else {
                                Thread.sleep(Math.min(1000L * (1 << attempt), 8000L));
                            }
                        } catch (InterruptedException ignored) {}
                    }
                }
            }

            if (outputFile != null && outputFile.exists() && outputFile.length() == 0) {
                outputFile.delete();
            }
            finish(callback, null, lastError != null ? lastError : "Не удалось скачать после " + maxRetries + " попыток");
        });
    }

    private String resolveVideoUrl(DownloadTask task, List<EpisodeResponse.SubtitleData> subtitlesOut) throws Exception {
        final String[] resolvedUrl = new String[1];
        final Exception[] error = new Exception[1];
        CountDownLatch latch = new CountDownLatch(1);

        apiService.fetchEpisodeData(task.getEpisodeId(), new ApiService.EpisodeDataCallback() {
            @Override
            public void onEpisodeDataReceived(EpisodeResponse response) {
                try {
                    if (response != null && response.getData() != null && response.getData().getPlayers() != null) {
                        List<EpisodeResponse.PlayerData> players = response.getData().getPlayers();
                        for (EpisodeResponse.PlayerData p : players) {
                            boolean matchPlayer = task.getPlayerType().equalsIgnoreCase(p.getPlayer());
                            boolean matchTeam = p.getTeam() != null &&
                                    (p.getTeam().getId() == task.getTeamId() || task.getTeamName().equalsIgnoreCase(p.getTeam().getName()));

                            if (matchPlayer && matchTeam) {
                                if (p.getSubtitles() != null && !p.getSubtitles().isEmpty() && subtitlesOut != null) {
                                    subtitlesOut.addAll(p.getSubtitles());
                                }
                                if ("kodik".equalsIgnoreCase(task.getPlayerType())) {
                                    if (p.getSrc() != null && !p.getSrc().isEmpty()) {
                                        KodikResponse kodikRes = kodikExtractor.getLinks(p.getSrc());
                                        if (kodikRes != null && kodikRes.getData() != null) {
                                            String qualKey = task.getQuality();
                                            KodikResponse.VideoQuality[] vq = kodikRes.getData().get(qualKey);
                                            if (vq == null || vq.length == 0) {
                                                // Fallback to highest available quality
                                                for (String k : new String[]{"1080", "720", "480", "360"}) {
                                                    if (kodikRes.getData().containsKey(k)) {
                                                        vq = kodikRes.getData().get(k);
                                                        break;
                                                    }
                                                }
                                            }
                                            if (vq != null && vq.length > 0 && vq[0].getSrc() != null) {
                                                resolvedUrl[0] = vq[0].getSrc();
                                            }
                                        }
                                    }
                                } else {
                                    // AnimeLib player
                                    if (p.getVideo() != null && p.getVideo().getQuality() != null) {
                                        String domain = p.getVideoDomain();
                                        for (EpisodeResponse.QualityData qd : p.getVideo().getQuality()) {
                                            if (String.valueOf(qd.getQuality()).contains(task.getQuality())) {
                                                resolvedUrl[0] = VideoUrlHelper.toAbsoluteVideoUrl(qd.getHref(), domain);
                                                break;
                                            }
                                        }
                                        if (resolvedUrl[0] == null && !p.getVideo().getQuality().isEmpty()) {
                                            resolvedUrl[0] = VideoUrlHelper.toAbsoluteVideoUrl(p.getVideo().getQuality().get(0).getHref(), domain);
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onError(String err) {
                error[0] = new Exception(err);
                latch.countDown();
            }
        });

        latch.await(20, TimeUnit.SECONDS);
        if (error[0] != null) throw error[0];

        if (resolvedUrl[0] != null && !resolvedUrl[0].isEmpty()) {
            if (resolvedUrl[0].startsWith("//")) {
                resolvedUrl[0] = "https:" + resolvedUrl[0];
            } else if (resolvedUrl[0].startsWith("/")) {
                resolvedUrl[0] = "https://kodik.info" + resolvedUrl[0];
            } else if (!resolvedUrl[0].startsWith("http://") && !resolvedUrl[0].startsWith("https://")) {
                resolvedUrl[0] = "https://" + resolvedUrl[0];
            }
        }

        return resolvedUrl[0];
    }

    private void downloadDirectFile(String url, File outputFile, String referer, DownloadCallback callback) throws IOException {
        long existingLength = outputFile.exists() ? outputFile.length() : 0;
        Request.Builder requestBuilder = new Request.Builder().url(url);

        for (Map.Entry<String, String> header : VideoUrlHelper.getVideoHeaders(referer).entrySet()) {
            requestBuilder.header(header.getKey(), header.getValue());
        }

        if (existingLength > 0) {
            requestBuilder.header("Range", "bytes=" + existingLength + "-");
            Log.d(TAG, "Requesting range bytes=" + existingLength + "- for resume");
        }

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            int statusCode = response.code();

            // Обработка ошибки 416 (Range Not Satisfiable)
            if (statusCode == 416) {
                String contentRange = response.header("Content-Range");
                if (contentRange != null && contentRange.contains("/")) {
                    try {
                        long totalSize = Long.parseLong(contentRange.substring(contentRange.lastIndexOf("/") + 1).trim());
                        if (totalSize > 0 && existingLength >= totalSize) {
                            Log.d(TAG, "HTTP 416: File already completely downloaded (" + existingLength + "/" + totalSize + ")");
                            mainHandler.post(() -> callback.onProgress(100));
                            return;
                        }
                    } catch (Exception ignored) {}
                }

                Log.w(TAG, "HTTP 416 received with Range header. Deleting partial file and downloading from scratch.");
                if (outputFile.exists()) {
                    outputFile.delete();
                }
                downloadDirectFileFromStart(url, outputFile, referer, callback);
                return;
            }

            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + statusCode);
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Пустой ответ сервера");
            }

            boolean isPartial = (statusCode == 206);

            // Если запрашивали диапазон, а сервер вернул 200 OK — перезаписываем с начала
            if (existingLength > 0 && !isPartial) {
                Log.d(TAG, "Server returned 200 OK instead of 206 Partial. Overwriting file from start.");
                existingLength = 0;
            }

            long contentLength = body.contentLength();
            long total = isPartial ? existingLength + contentLength : contentLength;
            long written = isPartial ? existingLength : 0;
            boolean append = isPartial;

            int lastReportedPercent = -1;
            long lastReportedTime = 0;
            byte[] buffer = new byte[BUFFER_SIZE];

            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(outputFile, append)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled) return;
                    out.write(buffer, 0, read);
                    written += read;

                    if (total > 0) {
                        int percent = (int) (written * 100 / total);
                        long now = System.currentTimeMillis();
                        if (percent != lastReportedPercent && (now - lastReportedTime > 300 || percent == 100)) {
                            lastReportedPercent = percent;
                            lastReportedTime = now;
                            int p = percent;
                            mainHandler.post(() -> callback.onProgress(p));
                        }
                    }
                }
                out.flush();
                mainHandler.post(() -> callback.onProgress(100));
            }
        }
    }

    private void downloadDirectFileFromStart(String url, File outputFile, String referer, DownloadCallback callback) throws IOException {
        Request.Builder requestBuilder = new Request.Builder().url(url);
        for (Map.Entry<String, String> header : VideoUrlHelper.getVideoHeaders(referer).entrySet()) {
            requestBuilder.header(header.getKey(), header.getValue());
        }

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Пустой ответ сервера");
            }

            long total = body.contentLength();
            long written = 0;
            int lastReportedPercent = -1;
            long lastReportedTime = 0;
            byte[] buffer = new byte[BUFFER_SIZE];

            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(outputFile, false)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled) return;
                    out.write(buffer, 0, read);
                    written += read;

                    if (total > 0) {
                        int percent = (int) (written * 100 / total);
                        long now = System.currentTimeMillis();
                        if (percent != lastReportedPercent && (now - lastReportedTime > 300 || percent == 100)) {
                            lastReportedPercent = percent;
                            lastReportedTime = now;
                            int p = percent;
                            mainHandler.post(() -> callback.onProgress(p));
                        }
                    }
                }
                out.flush();
                mainHandler.post(() -> callback.onProgress(100));
            }
        }
    }

    private void downloadHlsStream(String m3u8Url, File outputFile, DownloadCallback callback) throws IOException {
        Log.d(TAG, "Fetching HLS master playlist: " + m3u8Url);
        Request request = new Request.Builder().url(m3u8Url).build();
        String playlistContent;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " loading M3U8");
            }
            playlistContent = response.body().string();
        }

        String mediaPlaylistUrl = m3u8Url;
        if (playlistContent.contains("#EXT-X-STREAM-INF")) {
            // Master playlist: выбираем поток с наибольшим разрешением
            String bestSubUrl = null;
            String[] lines = playlistContent.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.startsWith("#") && !line.isEmpty()) {
                    bestSubUrl = resolveUrl(m3u8Url, line);
                }
            }
            if (bestSubUrl != null) {
                mediaPlaylistUrl = bestSubUrl;
                Log.d(TAG, "Selected sub-playlist URL: " + mediaPlaylistUrl);
                Request subReq = new Request.Builder().url(mediaPlaylistUrl).build();
                try (Response response = client.newCall(subReq).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        playlistContent = response.body().string();
                    }
                }
            }
        }

        // Извлекаем сегменты TS
        List<String> segmentUrls = new ArrayList<>();
        for (String line : playlistContent.split("\n")) {
            line = line.trim();
            if (!line.startsWith("#") && !line.isEmpty()) {
                segmentUrls.add(resolveUrl(mediaPlaylistUrl, line));
            }
        }

        if (segmentUrls.isEmpty()) {
            throw new IOException("Не найдено сегментов в M3U8 плейлисте");
        }

        int totalSegments = segmentUrls.size();
        Log.d(TAG, "Total TS segments to download: " + totalSegments);

        File segsDir = new File(outputFile.getParentFile(), outputFile.getName() + "_segs");
        if (!segsDir.exists()) {
            segsDir.mkdirs();
        }

        // Проверяем актуальность папки с сегментами
        File infoFile = new File(segsDir, "playlist.info");
        boolean isSamePlaylist = false;
        if (infoFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(infoFile)))) {
                String savedUrl = reader.readLine();
                String savedCount = reader.readLine();
                if (mediaPlaylistUrl.equals(savedUrl) && String.valueOf(totalSegments).equals(savedCount)) {
                    isSamePlaylist = true;
                }
            } catch (Exception ignored) {}
        }

        if (!isSamePlaylist) {
            deleteDirContents(segsDir);
            try (FileOutputStream fos = new FileOutputStream(infoFile)) {
                fos.write((mediaPlaylistUrl + "\n" + totalSegments + "\n").getBytes());
            } catch (Exception ignored) {}
        }

        ExecutorService segmentExecutor = Executors.newFixedThreadPool(MAX_HLS_THREADS);
        AtomicInteger completedCount = new AtomicInteger(0);
        final IOException[] segError = new IOException[1];
        CountDownLatch latch = new CountDownLatch(totalSegments);

        long[] lastReportedTime = new long[1];
        int[] lastReportedPercent = new int[]{-1};

        for (int i = 0; i < totalSegments; i++) {
            final int segIndex = i;
            final String segUrl = segmentUrls.get(i);

            segmentExecutor.execute(() -> {
                try {
                    if (!cancelled && segError[0] == null) {
                        File segFile = new File(segsDir, String.format(java.util.Locale.US, "seg_%05d.ts", segIndex));
                        File tempSegFile = new File(segsDir, String.format(java.util.Locale.US, "seg_%05d.ts.tmp", segIndex));

                        if (!segFile.exists() || segFile.length() == 0) {
                            downloadSingleSegmentWithRetry(segUrl, segFile, tempSegFile);
                        }

                        int currentCompleted = completedCount.incrementAndGet();
                        int percent = (int) (currentCompleted * 100L / totalSegments);
                        long now = System.currentTimeMillis();

                        synchronized (lastReportedPercent) {
                            if (percent != lastReportedPercent[0] && (now - lastReportedTime[0] > 300 || percent == 100)) {
                                lastReportedPercent[0] = percent;
                                lastReportedTime[0] = now;
                                int p = percent;
                                mainHandler.post(() -> callback.onProgress(p));
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Failed downloading segment " + segIndex + ": " + e.getMessage());
                    segError[0] = e;
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            cancelled = true;
        } finally {
            segmentExecutor.shutdownNow();
        }

        if (cancelled) return;
        if (segError[0] != null) throw segError[0];

        // Объединяем сегменты в итоговый outputFile
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileOutputStream out = new FileOutputStream(outputFile, false)) {
            for (int i = 0; i < totalSegments; i++) {
                if (cancelled) {
                    if (outputFile.exists()) outputFile.delete();
                    return;
                }
                File segFile = new File(segsDir, String.format(java.util.Locale.US, "seg_%05d.ts", i));
                if (segFile.exists() && segFile.length() > 0) {
                    try (FileInputStream in = new FileInputStream(segFile)) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                } else {
                    if (outputFile.exists()) outputFile.delete();
                    throw new IOException("Отсутствует или поврежден сегмент #" + i + " при сборке видеофайла");
                }
            }
            out.flush();
        } catch (Exception e) {
            if (outputFile.exists()) outputFile.delete();
            throw e;
        }

        deleteDir(segsDir);
        mainHandler.post(() -> callback.onProgress(100));
    }

    private void downloadSingleSegmentWithRetry(String segUrl, File segFile, File tempSegFile) throws IOException {
        int maxSegRetries = 3;
        IOException lastExc = null;
        byte[] buffer = new byte[BUFFER_SIZE];

        for (int attempt = 1; attempt <= maxSegRetries; attempt++) {
            if (cancelled) return;
            Request segReq = new Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", VideoUrlHelper.getRandomUserAgent())
                    .build();

            try (Response response = client.newCall(segReq).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    try (InputStream in = response.body().byteStream();
                         FileOutputStream out = new FileOutputStream(tempSegFile)) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            if (cancelled) {
                                tempSegFile.delete();
                                return;
                            }
                            out.write(buffer, 0, read);
                        }
                        out.flush();
                    }
                    if (tempSegFile.exists()) {
                        if (segFile.exists()) segFile.delete();
                        tempSegFile.renameTo(segFile);
                    }
                    return; // Успешно скачали сегмент
                } else {
                    lastExc = new IOException("HTTP " + response.code() + " for segment " + segUrl);
                }
            } catch (IOException e) {
                lastExc = e;
            }

            if (tempSegFile.exists()) {
                tempSegFile.delete();
            }

            if (attempt < maxSegRetries && !cancelled) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {}
            }
        }

        throw lastExc != null ? lastExc : new IOException("Failed segment download after retries");
    }

    private void deleteDirContents(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) deleteDir(f);
                    else f.delete();
                }
            }
        }
    }

    private void deleteDir(File dir) {
        if (dir != null && dir.exists()) {
            deleteDirContents(dir);
            dir.delete();
        }
    }

    private String resolveUrl(String baseUrl, String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isEmpty()) {
            return baseUrl;
        }
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }
        if (relativeUrl.startsWith("//")) {
            return "https:" + relativeUrl;
        }
        if (relativeUrl.startsWith("/")) {
            try {
                java.net.URL base = new java.net.URL(baseUrl);
                return base.getProtocol() + "://" + base.getHost() + relativeUrl;
            } catch (Exception e) {
                return "https://kodik.info" + relativeUrl;
            }
        }
        int lastSlash = baseUrl.lastIndexOf('/');
        if (lastSlash != -1) {
            return baseUrl.substring(0, lastSlash + 1) + relativeUrl;
        }
        return relativeUrl;
    }

    private void finish(DownloadCallback callback, String localPath, String error) {
        running = false;
        mainHandler.post(() -> {
            if (error != null) {
                callback.onError(error);
            } else {
                callback.onFinished(localPath);
            }
        });
    }

    private void downloadSubtitlesForTask(DownloadTask task, File videoFile, List<EpisodeResponse.SubtitleData> subtitles) {
        if (subtitles == null || subtitles.isEmpty() || videoFile == null) return;
        String baseName = videoFile.getAbsolutePath();
        int dotIdx = baseName.lastIndexOf('.');
        if (dotIdx > 0) {
            baseName = baseName.substring(0, dotIdx);
        }

        for (int i = 0; i < subtitles.size(); i++) {
            EpisodeResponse.SubtitleData sub = subtitles.get(i);
            if (sub == null || sub.getSrc() == null || sub.getSrc().trim().isEmpty()) continue;

            String format = sub.getFormat() != null ? sub.getFormat().trim().toLowerCase() : "vtt";
            File subFile = new File(baseName + "_sub_" + i + "." + format);

            if (subFile.exists() && subFile.length() > 0) {
                continue; // Субтитры уже скачаны
            }

            String url = sub.getSrc().trim();
            if (url.startsWith("//")) {
                url = "https:" + url;
            } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://animelib.me" + (url.startsWith("/") ? "" : "/") + url;
            }

            try {
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        try (InputStream is = response.body().byteStream();
                             FileOutputStream fos = new FileOutputStream(subFile)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = is.read(buffer)) != -1) {
                                fos.write(buffer, 0, read);
                            }
                            fos.flush();
                        }
                        Log.d(TAG, "Downloaded subtitle: " + subFile.getAbsolutePath());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to download subtitle " + url + ": " + e.getMessage());
            }
        }
    }

    public String downloadAndSavePoster(String animeId, String posterUrl) {
        if (animeId == null || animeId.isEmpty()) return posterUrl;
        File internalDir = new File(context.getFilesDir(), "cached_posters");
        File internalPosterFile = new File(internalDir, animeId + ".jpg");

        File externalDir = context.getExternalFilesDir("cached_posters");
        File externalPosterFile = externalDir != null ? new File(externalDir, animeId + ".jpg") : null;

        if (internalPosterFile.exists() && internalPosterFile.length() > 0) {
            return internalPosterFile.getAbsolutePath();
        }
        if (externalPosterFile != null && externalPosterFile.exists() && externalPosterFile.length() > 0) {
            return externalPosterFile.getAbsolutePath();
        }

        if (posterUrl == null || posterUrl.trim().isEmpty()) return posterUrl;
        if (posterUrl.startsWith("/")) {
            File f = new File(posterUrl);
            if (f.exists() && f.length() > 0) return posterUrl;
        }

        String url = posterUrl;
        if (url.startsWith("//")) {
            url = "https:" + url;
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://animelib.me" + (url.startsWith("/") ? "" : "/") + url;
        }

        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    byte[] bytes = response.body().bytes();
                    if (bytes != null && bytes.length > 0) {
                        if (!internalDir.exists()) internalDir.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(internalPosterFile)) {
                            fos.write(bytes);
                            fos.flush();
                        }
                        if (externalPosterFile != null) {
                            if (externalDir != null && !externalDir.exists()) externalDir.mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(externalPosterFile)) {
                                fos.write(bytes);
                                fos.flush();
                            } catch (Exception ignored) {}
                        }
                        return internalPosterFile.getAbsolutePath();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to download poster image: " + e.getMessage());
        }
        return posterUrl;
    }

    private void resolveTaskTitleAndPosterIfNeeded(DownloadTask task) {
        if (task == null || task.getAnimeId() == null) return;

        boolean titleIsPlaceholder = isPlaceholderTitle(task.getAnimeTitle());
        boolean posterIsPlaceholder = isPlaceholderUrl(task.getPosterUrl());

        if (!titleIsPlaceholder && !posterIsPlaceholder) {
            return;
        }

        // 1. Try Room Database first
        try {
            DownloadedAnimeEntity existing = databaseManager.getDownloadedAnimeSync(task.getAnimeId());
            if (existing != null) {
                if (titleIsPlaceholder && !isPlaceholderTitle(existing.getTitle())) {
                    task.setAnimeTitle(existing.getTitle());
                    titleIsPlaceholder = false;
                }
                if (posterIsPlaceholder && !isPlaceholderUrl(existing.getPosterUrl())) {
                    task.setPosterUrl(existing.getPosterUrl());
                    posterIsPlaceholder = false;
                }
            }
        } catch (Exception ignored) {}

        if (!titleIsPlaceholder && !posterIsPlaceholder) {
            return;
        }

        // 2. Try ApiService HTTP request synchronously
        try {
            String rawSiteUrl = databaseManager != null ? databaseManager.getSiteUrl() : null;
            boolean isOtherRegion = SiteUtils.isOtherRegion(rawSiteUrl, context);
            String siteOrigin = isOtherRegion ? "https://animelib.org" : "https://v5.animelib.org";
            String url = ApiClientCore.API_BASE_HAPI + "/anime/" + task.getAnimeId() + "?fields[]=rate";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Accept", "*/*")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                    .addHeader("Origin", siteOrigin)
                    .addHeader("Referer", siteOrigin + "/")
                    .addHeader("Site-Id", "5")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonStr = response.body().string();
                    JsonObject json = new Gson().fromJson(jsonStr, JsonObject.class);
                    if (json != null && json.has("data")) {
                        JsonObject data = json.getAsJsonObject("data");
                        if (titleIsPlaceholder) {
                            String realTitle = null;
                            if (data.has("rus_name") && !data.get("rus_name").isJsonNull()) {
                                realTitle = data.get("rus_name").getAsString();
                            } else if (data.has("name") && !data.get("name").isJsonNull()) {
                                realTitle = data.get("name").getAsString();
                            }
                            if (realTitle != null && !realTitle.trim().isEmpty()) {
                                task.setAnimeTitle(realTitle.trim());
                            }
                        }
                        if (posterIsPlaceholder) {
                            String realPoster = null;
                            if (data.has("poster") && !data.get("poster").isJsonNull()) {
                                JsonObject posterObj = data.getAsJsonObject("poster");
                                if (posterObj.has("url") && !posterObj.get("url").isJsonNull()) {
                                    realPoster = posterObj.get("url").getAsString();
                                } else if (posterObj.has("main") && !posterObj.get("main").isJsonNull()) {
                                    realPoster = posterObj.get("main").getAsString();
                                }
                            }
                            if (realPoster != null && !realPoster.trim().isEmpty()) {
                                task.setPosterUrl(realPoster.trim());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve real anime title/poster for ID " + task.getAnimeId() + ": " + e.getMessage());
        }

        if (!isPlaceholderTitle(task.getAnimeTitle()) || !isPlaceholderUrl(task.getPosterUrl())) {
            try {
                DownloadedAnimeEntity existing = databaseManager.getDownloadedAnimeSync(task.getAnimeId());
                if (existing != null) {
                    if (!isPlaceholderTitle(task.getAnimeTitle())) existing.setTitle(task.getAnimeTitle());
                    if (!isPlaceholderUrl(task.getPosterUrl())) existing.setPosterUrl(task.getPosterUrl());
                    databaseManager.saveDownloadedAnime(existing);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isPlaceholderTitle(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty()) return true;
        if (t.equalsIgnoreCase("Аниме")) return true;
        if (t.equalsIgnoreCase("Загрузка")) return true;
        if (t.equalsIgnoreCase("Загрузка...")) return true;
        if (t.startsWith("Аниме #")) return true;
        if (t.contains("Маг Целитель")) return true;
        return false;
    }

    private boolean isPlaceholderUrl(String url) {
        if (url == null) return true;
        String u = url.trim();
        if (u.isEmpty()) return true;
        if (u.contains("placeholder")) return true;
        return false;
    }

    public void cleanup() {
        cancel();
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
