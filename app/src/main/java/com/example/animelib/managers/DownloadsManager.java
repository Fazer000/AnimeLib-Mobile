package com.example.animelib.managers;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.api.ApiClientCore;
import com.example.animelib.api.ApiService;
import com.example.animelib.api.KodikLinksExtractor;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.DownloadedAnimeEntity;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.models.DownloadTask;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.models.OfflineEpisodeMetadata;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.AutoQualityHelper;
import com.example.animelib.util.FileUtils;
import com.example.animelib.util.HlsPlaylistParser;
import com.example.animelib.util.SiteUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Менеджер скачивания серий в локальный кэш приложения.
 * Поддерживает:
 * - атомарную запись через .part-файлы и честную паузу/докачку без повреждения готовых MP4;
 * - проверку свободного места на диске до и во время загрузки;
 * - скачивание всех дорожек субтитров (ASS/SSA/VTT/SRT) с нужными заголовками, ретраями и метаданными;
 * - сохранение сегментов (таймкодов: опенинг/эндинг/заставка/компиляция) и метаданных тайтла/серии;
 * - HLS-потоки с выбором качества, #EXT-X-MAP (fMP4) и дешифрованием AES-128 (#EXT-X-KEY);
 * - фолбэк по CDN-доменам AnimeLib и зеркалам Kodik при сетевых ошибках.
 */
public class DownloadsManager {
    private static final String TAG = "DownloadsManager";
    private static final int BUFFER_SIZE = 128 * 1024; // 128 KB буфер
    private static final int MAX_HLS_THREADS = 3;      // До 3 параллельных потоков для сегментов
    private static final long SOURCE_CACHE_TTL_MS = 10L * 60L * 1000L; // 10 минут

    public interface DownloadCallback {
        void onProgress(int percent);
        void onFinished(String localPath);
        void onError(String message);
    }

    /** Исключение, при котором бессмысленно повторять попытки (например, нет места на диске). */
    private static final class NonRetryableDownloadException extends IOException {
        NonRetryableDownloadException(String message) {
            super(message);
        }
    }

    /**
     * Полный снимок разрешённых данных эпизода (URL-ы видео + зеркала, субтитры, таймкоды, метаданные).
     * Кэшируется целиком, чтобы при ретраях не терялись субтитры и таймкоды.
     */
    private static final class ResolvedDownloadSource {
        final List<String> candidateVideoUrls;
        final String videoDomain;
        final String actualQuality;
        final List<EpisodeResponse.SubtitleData> subtitles;
        final List<EpisodeResponse.TimecodeData> timecodes;
        final String season;
        final String translationType;
        final long resolvedAtMs;

        ResolvedDownloadSource(List<String> candidateVideoUrls,
                               String videoDomain,
                               String actualQuality,
                               List<EpisodeResponse.SubtitleData> subtitles,
                               List<EpisodeResponse.TimecodeData> timecodes,
                               String season,
                               String translationType) {
            this.candidateVideoUrls = candidateVideoUrls != null ? candidateVideoUrls : Collections.emptyList();
            this.videoDomain = videoDomain;
            this.actualQuality = actualQuality;
            this.subtitles = subtitles != null ? subtitles : Collections.emptyList();
            this.timecodes = timecodes != null ? timecodes : Collections.emptyList();
            this.season = season;
            this.translationType = translationType;
            this.resolvedAtMs = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - resolvedAtMs) > SOURCE_CACHE_TTL_MS;
        }
    }

    /** Внутренний контейнер богатых метаданных аниме для офлайн-просмотра. */
    private static final class AnimeOfflineMeta {
        String title;
        String posterUrl;
        String description;
        String releaseYear;
        String animeType;
        String rating;
        int totalEpisodes;
    }

    private final Context context;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final ApiService apiService;
    private final DatabaseManager databaseManager;
    private final KodikLinksExtractor kodikExtractor;

    private final Map<String, ResolvedDownloadSource> resolvedSourceCache = new ConcurrentHashMap<>();
    private final Object taskStateLock = new Object();
    private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private volatile AtomicBoolean activeCancelFlag = new AtomicBoolean(false);
    private volatile AtomicBoolean activePreserveTempFlag = new AtomicBoolean(false);

    public DownloadsManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "downloads-manager-worker");
            t.setDaemon(true);
            return t;
        });
        this.client = ApiClientCore.getSharedHttpClient().newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.apiService = new ApiService(this.context);
        this.databaseManager = DatabaseManager.getInstance(this.context);
        this.kodikExtractor = new KodikLinksExtractor(this.client, ApiClientCore.getSharedGson());
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Пауза: останавливает текущую сетевую передачу, но СОХРАНЯЕТ временные файлы (.part и _segs),
     * чтобы при возобновлении докачка продолжилась с того же места.
     */
    public void pause() {
        activePreserveTempFlag.set(true);
        activeCancelFlag.set(true);
        cancelActiveHttpCalls();
    }

    /**
     * Полная отмена: останавливает сетевую передачу и удаляет временные файлы (.part и _segs).
     */
    public void cancel() {
        activePreserveTempFlag.set(false);
        activeCancelFlag.set(true);
        cancelActiveHttpCalls();
    }

    private void cancelActiveHttpCalls() {
        for (Call call : activeCalls) {
            try {
                call.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    private Call newTrackedCall(Request request, AtomicBoolean cancelFlag) throws IOException {
        if (cancelFlag != null && cancelFlag.get()) {
            throw new IOException("Скачивание остановлено");
        }
        Call call = client.newCall(request);
        activeCalls.add(call);
        if (cancelFlag != null && cancelFlag.get()) {
            activeCalls.remove(call);
            call.cancel();
            throw new IOException("Скачивание остановлено");
        }
        return call;
    }

    private void sleepWithCancel(long millis, AtomicBoolean cancelFlag) {
        long waited = 0L;
        final long step = 100L;
        try {
            while (waited < millis && (cancelFlag == null || !cancelFlag.get())) {
                Thread.sleep(Math.min(step, millis - waited));
                waited += step;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Вычисляет целевой путь локального MP4-файла для задачи (используется также при отмене/очистке).
     */
    public File resolveOutputFileForTask(@NonNull DownloadTask task) {
        File baseDir = context.getExternalFilesDir("cached_episodes");
        if (baseDir == null) {
            baseDir = new File(context.getFilesDir(), "cached_episodes");
        }
        File dir = new File(baseDir, task.getAnimeId() != null ? task.getAnimeId() : "unknown");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String sanitizeEpNum = task.getEpisodeNumber() != null
                ? task.getEpisodeNumber().replaceAll("[^a-zA-Z0-9_.]", "_") : "0";
        String sanitizeTeam = task.getTeamName() != null
                ? task.getTeamName().replaceAll("[^a-zA-Z0-9А-Яа-я_]", "_") : "team";
        String fileName = "ep_" + task.getEpisodeId() + "_" + sanitizeEpNum + "_" + sanitizeTeam + ".mp4";
        return new File(dir, fileName);
    }

    /**
     * Скачивает указанную задачу скачивания эпизода (видео + субтитры + сегменты/таймкоды + метаданные).
     */
    public void downloadTask(DownloadTask task, DownloadCallback callback) {
        if (task == null) {
            if (callback != null) callback.onError("Пустая задача скачивания");
            return;
        }
        final AtomicBoolean taskCancelFlag;
        final AtomicBoolean taskPreserveTempFlag;
        synchronized (taskStateLock) {
            if (running && !activeCancelFlag.get()) {
                if (callback != null) {
                    callback.onError("Скачивание уже идёт");
                }
                return;
            }
            taskCancelFlag = new AtomicBoolean(false);
            taskPreserveTempFlag = new AtomicBoolean(false);
            activeCancelFlag = taskCancelFlag;
            activePreserveTempFlag = taskPreserveTempFlag;
            running = true;
        }

        executor.execute(() -> {
            if (taskCancelFlag.get()) {
                finish(callback, taskCancelFlag, null, taskPreserveTempFlag.get() ? "Пауза" : "Скачивание отменено");
                return;
            }

            AnimeOfflineMeta animeMeta = resolveTaskMetadataIfNeeded(task, taskCancelFlag);
            File outputFile = resolveOutputFileForTask(task);
            File dir = outputFile.getParentFile();

            // Предварительная проверка, что на диске есть хотя бы минимальный резерв места
            if (!FileUtils.hasEnoughStorageSpace(dir, 0L, FileUtils.DEFAULT_STORAGE_SAFETY_BUFFER_BYTES)) {
                finish(callback, taskCancelFlag, null, "Недостаточно свободного места на устройстве");
                return;
            }

            int maxRetries = 5;
            int attempt = 0;
            String lastError = null;
            String cacheKey = task.getEpisodeId() + "_" + task.getPlayerType() + "_" + task.getTeamName() + "_" + task.getQuality();
            boolean forceRefreshSource = false;

            while (attempt < maxRetries && !taskCancelFlag.get()) {
                attempt++;
                try {
                    // 1. Получаем полный источник (ссылки + зеркала + субтитры + таймкоды + метаданные)
                    ResolvedDownloadSource source = forceRefreshSource ? null : resolvedSourceCache.get(cacheKey);
                    if (source == null || source.isExpired() || source.candidateVideoUrls.isEmpty()) {
                        source = resolveDownloadSource(task, forceRefreshSource, taskCancelFlag);
                        forceRefreshSource = false;
                        if (taskCancelFlag.get()) {
                            handleStoppedDownload(outputFile, taskPreserveTempFlag.get());
                            finish(callback, taskCancelFlag, null, taskPreserveTempFlag.get() ? "Пауза" : "Скачивание отменено");
                            return;
                        }
                        if (source != null && !source.candidateVideoUrls.isEmpty()) {
                            resolvedSourceCache.put(cacheKey, source);
                        }
                    }

                    if (source == null || source.candidateVideoUrls.isEmpty()) {
                        finish(callback, taskCancelFlag, null,
                                "Не удалось получить ссылку на видео для серии " + task.getEpisodeNumber());
                        return;
                    }

                    // Выбираем URL-кандидат с перебором CDN/зеркал на повторных попытках
                    int candidateIdx = (attempt - 1) % source.candidateVideoUrls.size();
                    String videoUrl = normalizeHttpUrl(source.candidateVideoUrls.get(candidateIdx));

                    Log.d(TAG, "Download attempt " + attempt + "/" + maxRetries
                            + " (candidate " + (candidateIdx + 1) + "/" + source.candidateVideoUrls.size() + ")"
                            + " to: " + outputFile.getAbsolutePath() + " url: " + videoUrl);

                    // Прогресс видео масштабируется в 0..95%, если есть субтитры, чтобы 95..100% отвести на субтитры и финализацию
                    final boolean hasSubs = !source.subtitles.isEmpty();
                    DownloadCallback videoProgressProxy = new DownloadCallback() {
                        @Override
                        public void onProgress(int percent) {
                            if (callback == null) return;
                            int scaled = hasSubs ? Math.min(95, (int) Math.round(percent * 0.95)) : Math.min(99, percent);
                            callback.onProgress(scaled);
                        }

                        @Override
                        public void onFinished(String localPath) {}

                        @Override
                        public void onError(String message) {}
                    };

                    // 2. Скачиваем видео (атомарно через .part -> rename в outputFile)
                    if (videoUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) {
                        downloadHlsStream(videoUrl, outputFile, task.getQuality(), videoProgressProxy, taskCancelFlag, taskPreserveTempFlag);
                    } else {
                        downloadDirectFile(videoUrl, outputFile, videoProgressProxy, taskCancelFlag);
                    }

                    if (taskCancelFlag.get()) {
                        handleStoppedDownload(outputFile, taskPreserveTempFlag.get());
                        finish(callback, taskCancelFlag, null, taskPreserveTempFlag.get() ? "Пауза" : "Скачивание отменено");
                        return;
                    }

                    if (!outputFile.exists() || outputFile.length() == 0) {
                        throw new IOException("Скачанный видеофайл пуст или отсутствует");
                    }

                    // 3. Скачиваем все доступные дорожки субтитров с заголовками, ретраями и атомарной записью
                    List<OfflineEpisodeMetadata.DownloadedSubtitleInfo> downloadedSubtitles =
                            downloadSubtitlesForTask(outputFile, source.subtitles, source.videoDomain, taskCancelFlag);

                    if (taskCancelFlag.get()) {
                        // Видео уже готово, чистим только .tmp субтитров
                        FileUtils.deleteDownloadTempFiles(outputFile.getAbsolutePath());
                        finish(callback, taskCancelFlag, null, taskPreserveTempFlag.get() ? "Пауза" : "Скачивание отменено");
                        return;
                    }

                    if (callback != null) {
                        mainHandler.post(() -> callback.onProgress(98));
                    }

                    // 4. Извлекаем длительность видео, сохраняем постер и полные метаданные в Room
                    long durationMs = extractVideoDurationMs(outputFile);
                    long totalDiskBytes = FileUtils.getEpisodeTotalDiskSize(outputFile.getAbsolutePath());
                    if (totalDiskBytes <= 0) {
                        totalDiskBytes = outputFile.length();
                    }

                    long now = System.currentTimeMillis();
                    String posterSource = !isPlaceholderUrl(task.getPosterUrl()) ? task.getPosterUrl() : animeMeta.posterUrl;
                    String localPosterPath = downloadAndSavePoster(task.getAnimeId(), posterSource, taskCancelFlag);

                    String finalAnimeTitle = !isPlaceholderTitle(task.getAnimeTitle())
                            ? task.getAnimeTitle()
                            : (!isPlaceholderTitle(animeMeta.title) ? animeMeta.title : "Аниме #" + task.getAnimeId());

                    DownloadedAnimeEntity animeEntity = new DownloadedAnimeEntity(
                            task.getAnimeId(),
                            finalAnimeTitle,
                            localPosterPath != null ? localPosterPath : posterSource,
                            now,
                            animeMeta.description,
                            animeMeta.releaseYear,
                            animeMeta.animeType,
                            animeMeta.rating,
                            animeMeta.totalEpisodes
                    );
                    databaseManager.saveDownloadedAnime(animeEntity);

                    String epId = task.getAnimeId() + "_"
                            + (task.getEpisodeId() != 0 ? task.getEpisodeId() : task.getEpisodeNumber())
                            + "_" + (task.getTeamName() != null ? task.getTeamName() : "");
                    String cleanEpName = com.example.animelib.VideoPlayerActivity.cleanEpisodeName(
                            task.getEpisodeName(), task.getEpisodeNumber());

                    String effectiveQuality = (source.actualQuality != null && !source.actualQuality.isEmpty())
                            ? source.actualQuality
                            : AutoQualityHelper.normalizeResolutionLabel(task.getQuality());
                    String subtitlesJson = OfflineEpisodeMetadata.toSubtitlesJson(downloadedSubtitles);
                    String timecodesJson = OfflineEpisodeMetadata.toTimecodesJson(source.timecodes);

                    DownloadedEpisodeEntity episodeEntity = new DownloadedEpisodeEntity(
                            epId,
                            task.getAnimeId(),
                            finalAnimeTitle,
                            task.getEpisodeId(),
                            task.getEpisodeNumber(),
                            cleanEpName,
                            task.getTeamName(),
                            task.getPlayerType(),
                            outputFile.getAbsolutePath(),
                            totalDiskBytes,
                            now,
                            effectiveQuality,
                            subtitlesJson,
                            timecodesJson,
                            source.season,
                            source.translationType,
                            durationMs
                    );
                    databaseManager.saveDownloadedEpisode(episodeEntity);

                    if (callback != null) {
                        mainHandler.post(() -> callback.onProgress(100));
                    }

                    Log.d(TAG, "Successfully downloaded episode: " + epId
                            + " quality=" + effectiveQuality
                            + " subs=" + downloadedSubtitles.size()
                            + " segments=" + source.timecodes.size()
                            + " durationMs=" + durationMs);
                    finish(callback, taskCancelFlag, outputFile.getAbsolutePath(), null);
                    return;

                } catch (NonRetryableDownloadException e) {
                    Log.e(TAG, "Non-retryable download error: " + e.getMessage());
                    FileUtils.deleteDownloadTempFiles(outputFile.getAbsolutePath());
                    finish(callback, taskCancelFlag, null, e.getMessage());
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Download attempt " + attempt + " failed: " + e.getMessage(), e);
                    lastError = e.getMessage() != null ? e.getMessage() : "Ошибка скачивания";

                    if (taskCancelFlag.get()) {
                        handleStoppedDownload(outputFile, taskPreserveTempFlag.get());
                        finish(callback, taskCancelFlag, null, taskPreserveTempFlag.get() ? "Пауза" : "Скачивание отменено");
                        return;
                    }

                    // При 401/403/404/410 обновляем ссылки с сервера (могли устареть токены CDN)
                    if (lastError.contains("401") || lastError.contains("403")
                            || lastError.contains("404") || lastError.contains("410")) {
                        resolvedSourceCache.remove(cacheKey);
                        forceRefreshSource = true;
                    }

                    if (attempt < maxRetries) {
                        if (lastError.contains("429") || lastError.contains("503")) {
                            sleepWithCancel(5000L, taskCancelFlag);
                        } else {
                            sleepWithCancel(Math.min(1000L * (1 << attempt), 8000L), taskCancelFlag);
                        }
                    }
                }
            }

            if (taskCancelFlag.get()) {
                handleStoppedDownload(outputFile, taskPreserveTempFlag.get());
                finish(callback, taskCancelFlag, null, taskPreserveTempFlag.get() ? "Пауза" : "Скачивание отменено");
            } else {
                // После исчерпания попыток оставляем .part для возможного ручного продолжения, но не битый .mp4
                if (outputFile.exists() && outputFile.length() == 0) {
                    outputFile.delete();
                }
                finish(callback, taskCancelFlag, null,
                        lastError != null ? lastError : "Не удалось скачать после " + maxRetries + " попыток");
            }
        });
    }

    private void handleStoppedDownload(@Nullable File outputFile, boolean preserveTempForResume) {
        if (outputFile == null) return;
        if (!preserveTempForResume) {
            FileUtils.deleteDownloadTempFiles(outputFile.getAbsolutePath());
        }
    }

    private ResolvedDownloadSource resolveDownloadSource(DownloadTask task,
                                                         boolean forceKodikRefresh,
                                                         AtomicBoolean cancelFlag) throws Exception {
        if (cancelFlag != null && cancelFlag.get()) {
            return null;
        }
        final AtomicReference<ResolvedDownloadSource> resolvedRef = new AtomicReference<>();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        final String taskPlayerType = task.getPlayerType();
        final String taskTeamName = task.getTeamName();

        apiService.fetchEpisodeData(task.getEpisodeId(), new ApiService.EpisodeDataCallback() {
            @Override
            public void onEpisodeDataReceived(EpisodeResponse response) {
                try {
                    if (response == null || response.getData() == null || response.getData().getPlayers() == null) {
                        return;
                    }
                    List<EpisodeResponse.PlayerData> players = response.getData().getPlayers();
                    EpisodeResponse.PlayerData matched = findMatchingPlayer(players, taskPlayerType, task.getTeamId(), taskTeamName);
                    if (matched == null) {
                        return;
                    }

                    // Субтитры: берём у выбранного плеера; если пусто и озвучка субтитрами — ищем у той же команды
                    List<EpisodeResponse.SubtitleData> subtitles = new ArrayList<>();
                    if (matched.getSubtitles() != null && !matched.getSubtitles().isEmpty()) {
                        subtitles.addAll(matched.getSubtitles());
                    } else if (matched.isSubtitles()) {
                        EpisodeResponse.PlayerData sameTeamSubPlayer = findSameTeamPlayerWithSubtitles(players, matched);
                        if (sameTeamSubPlayer != null && sameTeamSubPlayer.getSubtitles() != null) {
                            subtitles.addAll(sameTeamSubPlayer.getSubtitles());
                        }
                    }

                    // Сегменты (таймкоды): берём у выбранного плеера; если пусто — у того же студийного плеера (например AnimeLib той же команды)
                    List<EpisodeResponse.TimecodeData> timecodes = new ArrayList<>();
                    if (matched.getTimecode() != null && !matched.getTimecode().isEmpty()) {
                        timecodes.addAll(matched.getTimecode());
                    } else {
                        EpisodeResponse.PlayerData sameTeamTcPlayer = findSameTeamPlayerWithTimecodes(players, matched);
                        if (sameTeamTcPlayer != null && sameTeamTcPlayer.getTimecode() != null) {
                            timecodes.addAll(sameTeamTcPlayer.getTimecode());
                        }
                    }

                    String season = response.getData().getSeason();
                    String translationType = matched.getTranslationType() != null
                            ? matched.getTranslationType().getLabel()
                            : (matched.isSubtitles() ? "Субтитры" : "Озвучка");
                    String videoDomain = matched.getVideoDomain() != null
                            ? matched.getVideoDomain()
                            : VideoUrlHelper.DOMAIN_MAIN;

                    List<String> candidateUrls = new ArrayList<>();
                    String actualQuality = AutoQualityHelper.normalizeResolutionLabel(task.getQuality());

                    if ("kodik".equalsIgnoreCase(matched.getPlayer())) {
                        if (matched.getSrc() != null && !matched.getSrc().trim().isEmpty()) {
                            KodikResponse kodikRes = kodikExtractor.getLinks(matched.getSrc(), forceKodikRefresh);
                            if (kodikRes != null && kodikRes.getData() != null) {
                                String targetQualityLabel = AutoQualityHelper.normalizeResolutionLabel(task.getQuality());
                                List<String> extracted = KodikLinksExtractor.collectCandidateUrls(kodikRes, targetQualityLabel, true);
                                if (extracted != null && !extracted.isEmpty()) {
                                    candidateUrls.addAll(extracted);
                                    actualQuality = resolveKodikActualQuality(kodikRes, task.getQuality());
                                }
                            }
                        }
                    } else {
                        // AnimeLib player: подбираем точное или ближайшее доступное качество + резервные CDN-домены
                        if (matched.getVideo() != null && matched.getVideo().getQuality() != null
                                && !matched.getVideo().getQuality().isEmpty()) {
                            EpisodeResponse.QualityData selectedQ = selectBestAnimelibQuality(
                                    matched.getVideo().getQuality(), task.getQuality());
                            if (selectedQ != null && selectedQ.getHref() != null && !selectedQ.getHref().trim().isEmpty()) {
                                actualQuality = selectedQ.getQuality() > 0
                                        ? (selectedQ.getQuality() + "p")
                                        : actualQuality;
                                Set<String> uniqueUrls = new LinkedHashSet<>();
                                String primary = VideoUrlHelper.toAbsoluteVideoUrl(selectedQ.getHref(), videoDomain);
                                if (primary != null) uniqueUrls.add(primary);
                                for (String fallbackDomain : new String[]{
                                        VideoUrlHelper.DOMAIN_MAIN,
                                        VideoUrlHelper.DOMAIN_SECONDARY_1,
                                        VideoUrlHelper.DOMAIN_SECONDARY_2}) {
                                    String alt = VideoUrlHelper.toAbsoluteVideoUrl(selectedQ.getHref(), fallbackDomain);
                                    if (alt != null) uniqueUrls.add(alt);
                                }
                                candidateUrls.addAll(uniqueUrls);
                            }
                        }
                    }

                    resolvedRef.set(new ResolvedDownloadSource(
                            candidateUrls,
                            videoDomain,
                            actualQuality,
                            subtitles,
                            timecodes,
                            season,
                            translationType
                    ));
                } catch (Exception e) {
                    errorRef.compareAndSet(null, e);
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onError(String err) {
                errorRef.compareAndSet(null, new Exception(err));
                latch.countDown();
            }
        });

        boolean completed = latch.await(25, TimeUnit.SECONDS);
        if (!completed) {
            throw new IOException("Таймаут получения данных серии");
        }
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
        return resolvedRef.get();
    }

    @Nullable
    private EpisodeResponse.PlayerData findMatchingPlayer(List<EpisodeResponse.PlayerData> players,
                                                          @Nullable String taskPlayerType,
                                                          int taskTeamId,
                                                          @Nullable String taskTeamName) {
        if (players == null || players.isEmpty()) return null;
        String cleanTeam = taskTeamName != null ? taskTeamName.trim() : "";

        // 1. Точное совпадение playerType + (teamId > 0 || teamName)
        for (EpisodeResponse.PlayerData p : players) {
            if (p == null) continue;
            boolean matchPlayer = taskPlayerType != null && p.getPlayer() != null
                    && taskPlayerType.equalsIgnoreCase(p.getPlayer().trim());
            if (!matchPlayer) continue;
            if (isTeamMatch(p, taskTeamId, cleanTeam)) {
                return p;
            }
        }

        // 2. Совпадение по команде независимо от playerType (на случай если в серии команда переехала)
        for (EpisodeResponse.PlayerData p : players) {
            if (p != null && isTeamMatch(p, taskTeamId, cleanTeam)) {
                return p;
            }
        }

        // 3. Fallback для "Основная озвучка (...)" / teamId == 0 — первый плеер нужного типа
        if (taskTeamId <= 0 || cleanTeam.isEmpty() || cleanTeam.startsWith("Основная озвучка")) {
            for (EpisodeResponse.PlayerData p : players) {
                if (p != null && taskPlayerType != null && p.getPlayer() != null
                        && taskPlayerType.equalsIgnoreCase(p.getPlayer().trim())) {
                    return p;
                }
            }
        }
        return null;
    }

    private boolean isTeamMatch(@NonNull EpisodeResponse.PlayerData p, int taskTeamId, @NonNull String cleanTeamName) {
        if (p.getTeam() == null) return false;
        if (taskTeamId > 0 && p.getTeam().getId() == taskTeamId) {
            return true;
        }
        if (!cleanTeamName.isEmpty() && p.getTeam().getName() != null
                && cleanTeamName.equalsIgnoreCase(p.getTeam().getName().trim())) {
            return true;
        }
        return false;
    }

    @Nullable
    private EpisodeResponse.PlayerData findSameTeamPlayerWithSubtitles(List<EpisodeResponse.PlayerData> players,
                                                                       EpisodeResponse.PlayerData base) {
        if (players == null || base == null || base.getTeam() == null) return null;
        int teamId = base.getTeam().getId();
        String teamName = base.getTeam().getName() != null ? base.getTeam().getName().trim() : "";
        for (EpisodeResponse.PlayerData p : players) {
            if (p != null && p != base && p.getSubtitles() != null && !p.getSubtitles().isEmpty()) {
                if (isTeamMatch(p, teamId, teamName)) {
                    return p;
                }
            }
        }
        return null;
    }

    @Nullable
    private EpisodeResponse.PlayerData findSameTeamPlayerWithTimecodes(List<EpisodeResponse.PlayerData> players,
                                                                       EpisodeResponse.PlayerData base) {
        if (players == null || base == null || base.getTeam() == null) return null;
        int teamId = base.getTeam().getId();
        String teamName = base.getTeam().getName() != null ? base.getTeam().getName().trim() : "";
        for (EpisodeResponse.PlayerData p : players) {
            if (p != null && p != base && p.getTimecode() != null && !p.getTimecode().isEmpty()) {
                if (isTeamMatch(p, teamId, teamName)) {
                    return p;
                }
            }
        }
        return null;
    }

    @Nullable
    public static EpisodeResponse.QualityData selectBestAnimelibQuality(List<EpisodeResponse.QualityData> qualities,
                                                                        @Nullable String requestedQuality) {
        if (qualities == null || qualities.isEmpty()) return null;
        int targetRes = AutoQualityHelper.extractResolution(requestedQuality);
        if (targetRes > 0) {
            EpisodeResponse.QualityData exact = null;
            EpisodeResponse.QualityData bestLower = null;
            EpisodeResponse.QualityData bestHigher = null;

            for (EpisodeResponse.QualityData qd : qualities) {
                if (qd == null || qd.getHref() == null || qd.getHref().trim().isEmpty()) continue;
                int res = qd.getQuality();
                if (res == targetRes) {
                    exact = qd;
                    break;
                } else if (res > 0 && res < targetRes) {
                    if (bestLower == null || res > bestLower.getQuality()) {
                        bestLower = qd;
                    }
                } else if (res > targetRes) {
                    if (bestHigher == null || res < bestHigher.getQuality()) {
                        bestHigher = qd;
                    }
                }
            }
            if (exact != null) return exact;
            if (bestLower != null) return bestLower;
            if (bestHigher != null) return bestHigher;
        }

        // Fallback: максимальное доступное качество
        EpisodeResponse.QualityData highest = null;
        for (EpisodeResponse.QualityData qd : qualities) {
            if (qd == null || qd.getHref() == null || qd.getHref().trim().isEmpty()) continue;
            if (highest == null || qd.getQuality() > highest.getQuality()) {
                highest = qd;
            }
        }
        return highest;
    }

    private String resolveKodikActualQuality(KodikResponse kodikRes, String requestedQuality) {
        int targetRes = AutoQualityHelper.extractResolution(requestedQuality);
        if (kodikRes == null || kodikRes.getData() == null) {
            return AutoQualityHelper.normalizeResolutionLabel(requestedQuality);
        }
        if (targetRes > 0 && kodikRes.getData().containsKey(String.valueOf(targetRes))) {
            return targetRes + "p";
        }
        for (String k : new String[]{"2160", "1440", "1080", "720", "480", "360"}) {
            KodikResponse.VideoQuality[] vq = kodikRes.getData().get(k);
            if (vq != null && vq.length > 0) {
                return k + "p";
            }
        }
        return AutoQualityHelper.normalizeResolutionLabel(requestedQuality);
    }

    private Request.Builder applyVideoHeaders(Request.Builder builder, String url) {
        Map<String, String> headers = apiService.getVideoRequestHeadersForUrl(url);
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        } else {
            String siteUrl = apiService.getDatabaseManager() != null ? apiService.getDatabaseManager().getSiteUrl() : null;
            String referer = SiteUtils.isOtherRegion(siteUrl, context) ? "https://animelib.org/" : "https://v5.animelib.org/";
            for (Map.Entry<String, String> header : VideoUrlHelper.getVideoHeaders(referer).entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        return builder;
    }

    private void downloadDirectFile(String url, File outputFile, DownloadCallback callback, AtomicBoolean cancelFlag) throws IOException {
        File partFile = new File(outputFile.getAbsolutePath() + ".part");
        long existingLength = partFile.exists() ? partFile.length() : 0L;

        Request.Builder requestBuilder = applyVideoHeaders(new Request.Builder().url(url), url);
        if (existingLength > 0) {
            requestBuilder.header("Range", "bytes=" + existingLength + "-");
            Log.d(TAG, "Requesting range bytes=" + existingLength + "- for resume");
        }

        Call call = newTrackedCall(requestBuilder.build(), cancelFlag);
        try (Response response = call.execute()) {
            int statusCode = response.code();

            if (statusCode == 416) {
                String contentRange = response.header("Content-Range");
                if (contentRange != null && contentRange.contains("/")) {
                    try {
                        long totalSize = Long.parseLong(contentRange.substring(contentRange.lastIndexOf("/") + 1).trim());
                        if (totalSize > 0 && existingLength >= totalSize) {
                            Log.d(TAG, "HTTP 416: Part file already completely downloaded (" + existingLength + "/" + totalSize + ")");
                            promotePartFileToFinal(partFile, outputFile);
                            if (callback != null) mainHandler.post(() -> callback.onProgress(100));
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
                Log.w(TAG, "HTTP 416 received with Range header. Deleting stale .part and downloading from scratch.");
                if (partFile.exists()) partFile.delete();
                downloadDirectFileFromStart(url, partFile, outputFile, callback, cancelFlag);
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
            if (existingLength > 0 && !isPartial) {
                Log.d(TAG, "Server returned 200 OK instead of 206 Partial. Restarting .part from byte 0.");
                existingLength = 0L;
            }

            long contentLength = body.contentLength();
            long remainingBytes = contentLength > 0 ? contentLength : 0L;
            if (remainingBytes > 0 && !FileUtils.hasEnoughStorageSpace(
                    outputFile.getParentFile(), remainingBytes, FileUtils.DEFAULT_STORAGE_SAFETY_BUFFER_BYTES)) {
                throw new NonRetryableDownloadException("Недостаточно свободного места на устройстве");
            }

            long total = (contentLength > 0) ? (isPartial ? existingLength + contentLength : contentLength) : -1L;
            long written = isPartial ? existingLength : 0L;

            streamToPartFile(body, partFile, isPartial, written, total, callback, cancelFlag);
            if (cancelFlag.get()) return;

            if (total > 0 && partFile.length() < total) {
                throw new IOException("Неполная загрузка файла (" + partFile.length() + "/" + total + ")");
            }

            promotePartFileToFinal(partFile, outputFile);
            if (callback != null) mainHandler.post(() -> callback.onProgress(100));
        } finally {
            activeCalls.remove(call);
        }
    }

    private void downloadDirectFileFromStart(String url, File partFile, File outputFile,
                                             DownloadCallback callback, AtomicBoolean cancelFlag) throws IOException {
        Request.Builder requestBuilder = applyVideoHeaders(new Request.Builder().url(url), url);
        Call call = newTrackedCall(requestBuilder.build(), cancelFlag);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Пустой ответ сервера");
            }

            long total = body.contentLength();
            if (total > 0 && !FileUtils.hasEnoughStorageSpace(
                    outputFile.getParentFile(), total, FileUtils.DEFAULT_STORAGE_SAFETY_BUFFER_BYTES)) {
                throw new NonRetryableDownloadException("Недостаточно свободного места на устройстве");
            }

            streamToPartFile(body, partFile, false, 0L, total, callback, cancelFlag);
            if (cancelFlag.get()) return;

            if (total > 0 && partFile.length() < total) {
                throw new IOException("Неполная загрузка файла (" + partFile.length() + "/" + total + ")");
            }

            promotePartFileToFinal(partFile, outputFile);
            if (callback != null) mainHandler.post(() -> callback.onProgress(100));
        } finally {
            activeCalls.remove(call);
        }
    }

    private void streamToPartFile(ResponseBody body, File partFile, boolean append,
                                  long initialWritten, long total,
                                  DownloadCallback callback, AtomicBoolean cancelFlag) throws IOException {
        long written = initialWritten;
        int lastReportedPercent = -1;
        long lastReportedTime = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream in = body.byteStream();
             FileOutputStream out = new FileOutputStream(partFile, append)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelFlag.get()) return;
                out.write(buffer, 0, read);
                written += read;

                if (total > 0 && callback != null) {
                    int percent = (int) Math.min(100L, written * 100L / total);
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
        }
    }

    private void promotePartFileToFinal(File partFile, File outputFile) throws IOException {
        if (!partFile.exists() || partFile.length() == 0) {
            throw new IOException("Временный файл загрузки пуст");
        }
        if (outputFile.exists() && !outputFile.delete()) {
            Log.w(TAG, "Failed to delete previous outputFile before rename: " + outputFile.getAbsolutePath());
        }
        if (!partFile.renameTo(outputFile)) {
            // Fallback copy + delete if filesystem rename fails
            byte[] buffer = new byte[BUFFER_SIZE];
            try (FileInputStream in = new FileInputStream(partFile);
                 FileOutputStream out = new FileOutputStream(outputFile, false)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            partFile.delete();
        }
    }

    private void downloadHlsStream(String m3u8Url, File outputFile, @Nullable String preferredQuality,
                                   DownloadCallback callback, AtomicBoolean cancelFlag,
                                   AtomicBoolean preserveTempFlag) throws IOException {
        Log.d(TAG, "Fetching HLS playlist: " + m3u8Url);
        String playlistContent = fetchTextWithVideoHeaders(m3u8Url, cancelFlag);
        String mediaPlaylistUrl = m3u8Url;

        if (HlsPlaylistParser.isMasterPlaylist(playlistContent)) {
            String bestSubUrl = HlsPlaylistParser.selectBestVariantUrl(playlistContent, m3u8Url, preferredQuality);
            if (bestSubUrl != null && !bestSubUrl.isEmpty()) {
                mediaPlaylistUrl = bestSubUrl;
                Log.d(TAG, "Selected HLS media playlist for quality=" + preferredQuality + ": " + mediaPlaylistUrl);
                playlistContent = fetchTextWithVideoHeaders(mediaPlaylistUrl, cancelFlag);
            }
        }

        HlsPlaylistParser.ParsedMediaPlaylist parsed = HlsPlaylistParser.parseMediaPlaylist(playlistContent, mediaPlaylistUrl);
        List<HlsPlaylistParser.HlsSegment> segments = parsed.segments;
        if (segments.isEmpty()) {
            throw new IOException("Не найдено сегментов в M3U8 плейлисте");
        }

        int totalSegments = segments.size();
        Log.d(TAG, "Total HLS segments to download: " + totalSegments
                + (parsed.initSegmentUrl != null ? " (+ fMP4 init segment)" : ""));

        File segsDir = new File(outputFile.getParentFile(), outputFile.getName() + "_segs");
        if (!segsDir.exists()) {
            segsDir.mkdirs();
        }

        // Проверяем актуальность папки с сегментами для корректной докачки после паузы
        File infoFile = new File(segsDir, "playlist.info");
        boolean isSamePlaylist = false;
        if (infoFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(infoFile), StandardCharsets.UTF_8))) {
                String savedUrl = reader.readLine();
                String savedCount = reader.readLine();
                if (mediaPlaylistUrl.equals(savedUrl) && String.valueOf(totalSegments).equals(savedCount)) {
                    isSamePlaylist = true;
                }
            } catch (Exception ignored) {
            }
        }

        if (!isSamePlaylist) {
            FileUtils.deleteDirectoryContents(segsDir);
            try (FileOutputStream fos = new FileOutputStream(infoFile)) {
                fos.write((mediaPlaylistUrl + "\n" + totalSegments + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }

        // Скачиваем init-сегмент (#EXT-X-MAP), если это fMP4 HLS
        File initSegFile = null;
        if (parsed.initSegmentUrl != null) {
            initSegFile = new File(segsDir, "init.mp4");
            File tempInit = new File(segsDir, "init.mp4.tmp");
            if (!initSegFile.exists() || initSegFile.length() == 0) {
                downloadBinaryWithRetry(parsed.initSegmentUrl, initSegFile, tempInit, null, null, cancelFlag);
            }
        }

        // Кэш ключей шифрования AES-128 (обычно один ключ на весь плейлист)
        Map<String, byte[]> keyCache = new ConcurrentHashMap<>();

        ExecutorService segmentExecutor = Executors.newFixedThreadPool(MAX_HLS_THREADS, r -> {
            Thread t = new Thread(r, "hls-segment-worker");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicReference<IOException> segError = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(totalSegments);

        long[] lastReportedTime = new long[1];
        int[] lastReportedPercent = new int[]{-1};

        for (HlsPlaylistParser.HlsSegment seg : segments) {
            segmentExecutor.execute(() -> {
                try {
                    if (!cancelFlag.get() && segError.get() == null) {
                        File segFile = new File(segsDir, String.format(Locale.US, "seg_%05d.ts", seg.index));
                        File tempSegFile = new File(segsDir, String.format(Locale.US, "seg_%05d.ts.tmp", seg.index));

                        if (!segFile.exists() || segFile.length() == 0) {
                            byte[] keyBytes = null;
                            if (seg.isAes128Encrypted() && seg.keyUrl != null) {
                                keyBytes = getOrFetchAesKey(seg.keyUrl, keyCache, cancelFlag);
                            }
                            downloadBinaryWithRetry(seg.url, segFile, tempSegFile, keyBytes, seg.iv, cancelFlag);
                        }

                        int currentCompleted = completedCount.incrementAndGet();
                        int percent = (int) (currentCompleted * 100L / totalSegments);
                        long now = System.currentTimeMillis();

                        if (callback != null) {
                            synchronized (lastReportedPercent) {
                                if (percent != lastReportedPercent[0] && (now - lastReportedTime[0] > 300 || percent == 100)) {
                                    lastReportedPercent[0] = percent;
                                    lastReportedTime[0] = now;
                                    int p = percent;
                                    mainHandler.post(() -> callback.onProgress(p));
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Failed downloading segment " + seg.index + ": " + e.getMessage());
                    segError.compareAndSet(null, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelFlag.set(true);
        } finally {
            segmentExecutor.shutdownNow();
        }

        if (cancelFlag.get()) {
            if (!preserveTempFlag.get()) {
                FileUtils.deleteRecursively(segsDir);
            }
            return;
        }
        if (segError.get() != null) throw segError.get();

        // Проверяем, хватит ли места для сборки итогового файла
        long estimatedTotalBytes = (initSegFile != null && initSegFile.exists()) ? initSegFile.length() : 0L;
        for (int i = 0; i < totalSegments; i++) {
            File segFile = new File(segsDir, String.format(Locale.US, "seg_%05d.ts", i));
            estimatedTotalBytes += segFile.length();
        }
        if (!FileUtils.hasEnoughStorageSpace(
                outputFile.getParentFile(), estimatedTotalBytes, FileUtils.DEFAULT_STORAGE_SAFETY_BUFFER_BYTES)) {
            throw new NonRetryableDownloadException("Недостаточно свободного места для сборки видеофайла");
        }

        // Собираем сегменты атомарно в .part, затем переименовываем в outputFile
        File partFile = new File(outputFile.getAbsolutePath() + ".part");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileOutputStream out = new FileOutputStream(partFile, false)) {
            if (initSegFile != null && initSegFile.exists() && initSegFile.length() > 0) {
                copyFileToStream(initSegFile, out, buffer);
            }
            for (int i = 0; i < totalSegments; i++) {
                if (cancelFlag.get()) {
                    if (partFile.exists()) partFile.delete();
                    if (!preserveTempFlag.get()) FileUtils.deleteRecursively(segsDir);
                    return;
                }
                File segFile = new File(segsDir, String.format(Locale.US, "seg_%05d.ts", i));
                if (segFile.exists() && segFile.length() > 0) {
                    copyFileToStream(segFile, out, buffer);
                } else {
                    if (partFile.exists()) partFile.delete();
                    throw new IOException("Отсутствует или поврежден сегмент #" + i + " при сборке видеофайла");
                }
            }
            out.flush();
        } catch (IOException e) {
            if (partFile.exists()) partFile.delete();
            throw e;
        }

        promotePartFileToFinal(partFile, outputFile);
        FileUtils.deleteRecursively(segsDir);
        if (callback != null) mainHandler.post(() -> callback.onProgress(100));
    }

    private void copyFileToStream(File file, FileOutputStream out, byte[] buffer) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private String fetchTextWithVideoHeaders(String url, AtomicBoolean cancelFlag) throws IOException {
        Request request = applyVideoHeaders(new Request.Builder().url(url), url).build();
        Call call = newTrackedCall(request, cancelFlag);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " loading " + url);
            }
            return response.body().string();
        } finally {
            activeCalls.remove(call);
        }
    }

    private byte[] getOrFetchAesKey(String keyUrl, Map<String, byte[]> keyCache, AtomicBoolean cancelFlag) throws IOException {
        byte[] cached = keyCache.get(keyUrl);
        if (cached != null) return cached;

        synchronized (keyCache) {
            cached = keyCache.get(keyUrl);
            if (cached != null) return cached;

            Request req = applyVideoHeaders(new Request.Builder().url(keyUrl), keyUrl).build();
            Call call = newTrackedCall(req, cancelFlag);
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("HTTP " + response.code() + " fetching AES-128 key");
                }
                byte[] key = response.body().bytes();
                if (key.length != 16) {
                    throw new IOException("Некорректная длина ключа AES-128: " + key.length);
                }
                keyCache.put(keyUrl, key);
                return key;
            } finally {
                activeCalls.remove(call);
            }
        }
    }

    private void downloadBinaryWithRetry(String segUrl, File segFile, File tempSegFile,
                                         @Nullable byte[] aesKey, @Nullable byte[] aesIv,
                                         AtomicBoolean cancelFlag) throws IOException {
        int maxSegRetries = 3;
        IOException lastExc = null;
        byte[] buffer = new byte[BUFFER_SIZE];

        for (int attempt = 1; attempt <= maxSegRetries; attempt++) {
            if (cancelFlag.get()) return;
            Request segReq = applyVideoHeaders(new Request.Builder().url(segUrl), segUrl).build();

            Call call = newTrackedCall(segReq, cancelFlag);
            try (Response response = call.execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    if (aesKey != null && aesIv != null) {
                        byte[] encrypted = response.body().bytes();
                        if (cancelFlag.get()) return;
                        byte[] decrypted;
                        try {
                            decrypted = HlsPlaylistParser.decryptAes128(encrypted, aesKey, aesIv);
                        } catch (Exception decErr) {
                            throw new IOException("Ошибка расшифровки AES-128 сегмента", decErr);
                        }
                        try (FileOutputStream out = new FileOutputStream(tempSegFile)) {
                            out.write(decrypted);
                            out.flush();
                        }
                    } else {
                        try (InputStream in = response.body().byteStream();
                             FileOutputStream out = new FileOutputStream(tempSegFile)) {
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                if (cancelFlag.get()) {
                                    tempSegFile.delete();
                                    return;
                                }
                                out.write(buffer, 0, read);
                            }
                            out.flush();
                        }
                    }

                    if (tempSegFile.exists() && tempSegFile.length() > 0) {
                        if (segFile.exists()) segFile.delete();
                        if (!tempSegFile.renameTo(segFile)) {
                            promotePartFileToFinal(tempSegFile, segFile);
                        }
                        return;
                    } else {
                        lastExc = new IOException("Пустой сегмент " + segUrl);
                    }
                } else {
                    lastExc = new IOException("HTTP " + response.code() + " for segment " + segUrl);
                }
            } catch (IOException e) {
                lastExc = e;
            } finally {
                activeCalls.remove(call);
            }

            if (tempSegFile.exists()) {
                tempSegFile.delete();
            }

            if (attempt < maxSegRetries && !cancelFlag.get()) {
                sleepWithCancel(300L * attempt, cancelFlag);
            }
        }

        throw lastExc != null ? lastExc : new IOException("Failed segment download after retries");
    }

    /**
     * Скачивает все дорожки субтитров эпизода атомарно (через .tmp -> rename) с нужными заголовками и ретраями.
     * Возвращает список метаданных успешно сохранённых дорожек.
     */
    @NonNull
    private List<OfflineEpisodeMetadata.DownloadedSubtitleInfo> downloadSubtitlesForTask(
            File videoFile,
            List<EpisodeResponse.SubtitleData> subtitles,
            @Nullable String videoDomain,
            AtomicBoolean cancelFlag) {
        List<OfflineEpisodeMetadata.DownloadedSubtitleInfo> savedList = new ArrayList<>();
        if (subtitles == null || subtitles.isEmpty() || videoFile == null) {
            return savedList;
        }

        String baseName = videoFile.getAbsolutePath();
        int dotIdx = baseName.lastIndexOf('.');
        if (dotIdx > 0) {
            baseName = baseName.substring(0, dotIdx);
        }
        String effectiveDomain = (videoDomain != null && !videoDomain.trim().isEmpty())
                ? videoDomain
                : VideoUrlHelper.DOMAIN_MAIN;

        for (int i = 0; i < subtitles.size(); i++) {
            if (cancelFlag.get()) return savedList;
            EpisodeResponse.SubtitleData sub = subtitles.get(i);
            if (sub == null || sub.getSrc() == null || sub.getSrc().trim().isEmpty()) continue;

            String format = resolveSubtitleExtension(sub.getFormat(), sub.getSrc());
            File subFile = new File(baseName + "_sub_" + i + "." + format);
            File tmpFile = new File(subFile.getAbsolutePath() + ".tmp");

            if (subFile.exists() && subFile.length() > 0) {
                savedList.add(new OfflineEpisodeMetadata.DownloadedSubtitleInfo(
                        sub.getId(), format, sub.getName(), sub.getFilename(),
                        subFile.getAbsolutePath(), subFile.length()));
                continue;
            }

            List<String> subCandidateUrls = buildSubtitleCandidateUrls(sub.getSrc().trim(), effectiveDomain);
            boolean downloaded = false;

            for (String candidateUrl : subCandidateUrls) {
                if (cancelFlag.get()) return savedList;
                for (int attempt = 1; attempt <= 2; attempt++) {
                    if (cancelFlag.get()) return savedList;
                    Call subCall = null;
                    try {
                        Request.Builder reqBuilder = applyVideoHeaders(new Request.Builder().url(candidateUrl), candidateUrl)
                                .header("Accept", "*/*");
                        subCall = newTrackedCall(reqBuilder.build(), cancelFlag);
                        try (Response response = subCall.execute()) {
                            if (response.isSuccessful() && response.body() != null) {
                                byte[] bytes = readBytesWithCancel(response.body(), cancelFlag);
                                if (cancelFlag.get()) {
                                    if (tmpFile.exists()) tmpFile.delete();
                                    return savedList;
                                }
                                if (bytes != null && bytes.length > 0 && !looksLikeHtmlErrorPage(bytes)) {
                                    try (FileOutputStream fos = new FileOutputStream(tmpFile, false)) {
                                        fos.write(bytes);
                                        fos.flush();
                                    }
                                    promotePartFileToFinal(tmpFile, subFile);
                                    savedList.add(new OfflineEpisodeMetadata.DownloadedSubtitleInfo(
                                            sub.getId(), format, sub.getName(), sub.getFilename(),
                                            subFile.getAbsolutePath(), subFile.length()));
                                    Log.d(TAG, "Downloaded subtitle #" + i + " (" + format + ", "
                                            + subFile.length() + " bytes): " + subFile.getAbsolutePath());
                                    downloaded = true;
                                    break;
                                } else {
                                    Log.w(TAG, "Subtitle response was empty or HTML error page for " + candidateUrl);
                                }
                            } else {
                                Log.w(TAG, "Subtitle HTTP " + response.code() + " for " + candidateUrl);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to download subtitle " + candidateUrl + " (attempt " + attempt + "): " + e.getMessage());
                    } finally {
                        if (subCall != null) activeCalls.remove(subCall);
                        if (!downloaded && tmpFile.exists()) tmpFile.delete();
                    }
                    if (!downloaded && attempt < 2 && !cancelFlag.get()) {
                        sleepWithCancel(400L, cancelFlag);
                    }
                }
                if (downloaded) break;
            }
        }
        return savedList;
    }

    private byte[] readBytesWithCancel(ResponseBody body, AtomicBoolean cancelFlag) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (InputStream is = body.byteStream()) {
            int read;
            while ((read = is.read(buf)) != -1) {
                if (cancelFlag.get()) return null;
                baos.write(buf, 0, read);
            }
        }
        return baos.toByteArray();
    }

    private static boolean looksLikeHtmlErrorPage(byte[] bytes) {
        int len = Math.min(bytes.length, 256);
        String head = new String(bytes, 0, len, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<title>403") || head.contains("<title>404");
    }

    private List<String> buildSubtitleCandidateUrls(String rawSrc, String preferredDomain) {
        Set<String> candidates = new LinkedHashSet<>();
        if (rawSrc.startsWith("http://") || rawSrc.startsWith("https://")) {
            candidates.add(rawSrc);
            String rel = VideoUrlHelper.extractRelativePathFromCdnUrl(rawSrc);
            if (rel != null) {
                for (String domain : new String[]{
                        preferredDomain,
                        VideoUrlHelper.DOMAIN_MAIN,
                        VideoUrlHelper.DOMAIN_SECONDARY_1,
                        VideoUrlHelper.DOMAIN_SECONDARY_2}) {
                    String alt = VideoUrlHelper.toAbsoluteVideoUrl(rel, domain);
                    if (alt != null) candidates.add(alt);
                }
            }
        } else if (rawSrc.startsWith("//")) {
            candidates.add("https:" + rawSrc);
        } else {
            // Сначала пробуем CDN-домены (как делает плеер в PlayerSubtitlesController)
            for (String domain : new String[]{
                    preferredDomain,
                    VideoUrlHelper.DOMAIN_MAIN,
                    VideoUrlHelper.DOMAIN_SECONDARY_1,
                    VideoUrlHelper.DOMAIN_SECONDARY_2}) {
                String alt = VideoUrlHelper.toAbsoluteVideoUrl(rawSrc, domain);
                if (alt != null) candidates.add(alt);
            }
            // Затем резервный домен сайта AnimeLib
            String rawSiteUrl = databaseManager != null ? databaseManager.getSiteUrl() : null;
            String siteOrigin = SiteUtils.isOtherRegion(rawSiteUrl, context)
                    ? "https://animelib.org" : "https://v5.animelib.org";
            candidates.add(siteOrigin + (rawSrc.startsWith("/") ? "" : "/") + rawSrc);
        }
        return new ArrayList<>(candidates);
    }

    private static String resolveSubtitleExtension(@Nullable String format, @Nullable String src) {
        if (format != null && !format.trim().isEmpty()) {
            String fmt = format.trim().toLowerCase(Locale.ROOT);
            if ("webvtt".equals(fmt)) return "vtt";
            if ("subrip".equals(fmt)) return "srt";
            if (fmt.matches("[a-z0-9]{2,5}")) return fmt;
        }
        if (src != null) {
            String lower = src.toLowerCase(Locale.ROOT);
            if (lower.contains(".ass")) return "ass";
            if (lower.contains(".ssa")) return "ssa";
            if (lower.contains(".srt")) return "srt";
            if (lower.contains(".vtt")) return "vtt";
        }
        return "vtt";
    }

    private static String normalizeHttpUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        } else if (trimmed.startsWith("/")) {
            return "https://kodik.info" + trimmed;
        } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "https://" + trimmed;
        }
        return trimmed;
    }

    private long extractVideoDurationMs(@Nullable File videoFile) {
        if (videoFile == null || !videoFile.exists()) return 0L;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());
            String durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null && !durStr.trim().isEmpty()) {
                return Long.parseLong(durStr.trim());
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not extract video duration for " + videoFile.getName() + ": " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private void finish(DownloadCallback callback, AtomicBoolean taskCancelFlag, String localPath, String error) {
        synchronized (taskStateLock) {
            if (activeCancelFlag == taskCancelFlag) {
                running = false;
            }
        }
        if (callback == null) return;
        mainHandler.post(() -> {
            if (error != null) {
                callback.onError(error);
            } else {
                callback.onFinished(localPath);
            }
        });
    }

    public String downloadAndSavePoster(String animeId, String posterUrl) {
        return downloadAndSavePoster(animeId, posterUrl, null);
    }

    private String downloadAndSavePoster(String animeId, String posterUrl, AtomicBoolean cancelFlag) {
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

        String url = posterUrl.trim();
        if (url.startsWith("//")) {
            url = "https:" + url;
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://cover.imglib.info" + (url.startsWith("/") ? "" : "/") + url;
        }

        Call posterCall = null;
        try {
            Request request = applyVideoHeaders(new Request.Builder().url(url), url)
                    .header("Accept", "image/*,*/*;q=0.8")
                    .build();
            posterCall = newTrackedCall(request, cancelFlag);
            try (Response response = posterCall.execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    byte[] bytes = response.body().bytes();
                    if (bytes != null && bytes.length > 0 && !looksLikeHtmlErrorPage(bytes)) {
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
                            } catch (Exception ignored) {
                            }
                        }
                        return internalPosterFile.getAbsolutePath();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to download poster image: " + e.getMessage());
        } finally {
            if (posterCall != null) activeCalls.remove(posterCall);
        }
        return posterUrl;
    }

    /**
     * Загружает и кеширует расширенные офлайн-метаданные тайтла (название, постер, описание, год, тип, рейтинг, кол-во серий).
     */
    @NonNull
    private AnimeOfflineMeta resolveTaskMetadataIfNeeded(DownloadTask task, AtomicBoolean cancelFlag) {
        AnimeOfflineMeta meta = new AnimeOfflineMeta();
        if (task == null || task.getAnimeId() == null) return meta;
        meta.title = task.getAnimeTitle();
        meta.posterUrl = task.getPosterUrl();

        if (cancelFlag != null && cancelFlag.get()) return meta;

        boolean titleIsPlaceholder = isPlaceholderTitle(task.getAnimeTitle());
        boolean posterIsPlaceholder = isPlaceholderUrl(task.getPosterUrl());
        boolean richMetaMissing = true;

        try {
            DownloadedAnimeEntity existing = databaseManager.getDownloadedAnimeSync(task.getAnimeId());
            if (existing != null) {
                if (titleIsPlaceholder && !isPlaceholderTitle(existing.getTitle())) {
                    task.setAnimeTitle(existing.getTitle());
                    meta.title = existing.getTitle();
                    titleIsPlaceholder = false;
                }
                if (posterIsPlaceholder && !isPlaceholderUrl(existing.getPosterUrl())) {
                    task.setPosterUrl(existing.getPosterUrl());
                    meta.posterUrl = existing.getPosterUrl();
                    posterIsPlaceholder = false;
                }
                meta.description = existing.getDescription();
                meta.releaseYear = existing.getReleaseYear();
                meta.animeType = existing.getAnimeType();
                meta.rating = existing.getRating();
                meta.totalEpisodes = existing.getTotalEpisodes();

                if (meta.animeType != null && !meta.animeType.trim().isEmpty()
                        && meta.description != null && !meta.description.trim().isEmpty()) {
                    richMetaMissing = false;
                }
            }
        } catch (Exception ignored) {
        }

        if (!titleIsPlaceholder && !posterIsPlaceholder && !richMetaMissing) {
            return meta;
        }

        Call metadataCall = null;
        try {
            String rawSiteUrl = databaseManager != null ? databaseManager.getSiteUrl() : null;
            boolean isOtherRegion = SiteUtils.isOtherRegion(rawSiteUrl, context);
            String siteOrigin = isOtherRegion ? "https://animelib.org" : "https://v5.animelib.org";
            String url = ApiClientCore.API_BASE_HAPI + "/anime/" + task.getAnimeId()
                    + "?fields[]=summary&fields[]=rate&fields[]=releaseDate&fields[]=episodes_count";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Accept", "*/*")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
                    .addHeader("Origin", siteOrigin)
                    .addHeader("Referer", siteOrigin + "/")
                    .addHeader("Site-Id", "5")
                    .build();
            metadataCall = newTrackedCall(request, cancelFlag);
            try (Response response = metadataCall.execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonStr = response.body().string();
                    JsonObject json = ApiClientCore.getSharedGson().fromJson(jsonStr, JsonObject.class);
                    if (json != null && json.has("data") && json.get("data").isJsonObject()) {
                        JsonObject data = json.getAsJsonObject("data");
                        if (titleIsPlaceholder) {
                            String realTitle = readJsonString(data, "rus_name");
                            if (realTitle == null) realTitle = readJsonString(data, "name");
                            if (realTitle != null) {
                                task.setAnimeTitle(realTitle);
                                meta.title = realTitle;
                            }
                        }
                        if (posterIsPlaceholder) {
                            String realPoster = extractPosterFromAnimeJson(data);
                            if (realPoster != null) {
                                task.setPosterUrl(realPoster);
                                meta.posterUrl = realPoster;
                            }
                        }
                        String summary = extractSummaryText(data.get("summary"));
                        if (summary != null && !summary.isEmpty()) {
                            meta.description = summary;
                        }
                        String relDate = readJsonString(data, "releaseDate");
                        if (relDate == null) relDate = readJsonString(data, "releaseDateString");
                        if (relDate != null) {
                            java.util.regex.Matcher ym = java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(relDate);
                            if (ym.find()) {
                                meta.releaseYear = ym.group();
                            }
                        }
                        if (data.has("type") && data.get("type").isJsonObject()) {
                            String typeLabel = readJsonString(data.getAsJsonObject("type"), "label");
                            if (typeLabel != null) meta.animeType = typeLabel;
                        }
                        if (data.has("rating") && data.get("rating").isJsonObject()) {
                            JsonObject rObj = data.getAsJsonObject("rating");
                            String avg = readJsonString(rObj, "averageFormated");
                            if (avg == null) avg = readJsonString(rObj, "average");
                            if (avg != null) meta.rating = avg;
                        }
                        if (data.has("items_count") && data.get("items_count").isJsonObject()) {
                            JsonObject ic = data.getAsJsonObject("items_count");
                            if (ic.has("total") && ic.get("total").isJsonPrimitive()) {
                                meta.totalEpisodes = ic.get("total").getAsInt();
                            } else if (ic.has("uploaded") && ic.get("uploaded").isJsonPrimitive()) {
                                meta.totalEpisodes = ic.get("uploaded").getAsInt();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve anime metadata for ID " + task.getAnimeId() + ": " + e.getMessage());
        } finally {
            if (metadataCall != null) activeCalls.remove(metadataCall);
        }

        return meta;
    }

    @Nullable
    private static String readJsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive()) return null;
        String val = el.getAsString();
        return (val != null && !val.trim().isEmpty()) ? val.trim() : null;
    }

    @Nullable
    private static String extractPosterFromAnimeJson(JsonObject data) {
        if (data == null) return null;
        for (String field : new String[]{"cover", "poster"}) {
            if (data.has(field) && data.get(field).isJsonObject()) {
                JsonObject obj = data.getAsJsonObject(field);
                for (String key : new String[]{"default", "md", "url", "main", "thumbnail"}) {
                    String val = readJsonString(obj, key);
                    if (val != null) return val;
                }
            }
        }
        return null;
    }

    @Nullable
    private static String extractSummaryText(@Nullable JsonElement summary) {
        if (summary == null || summary.isJsonNull()) return null;
        if (summary.isJsonPrimitive()) {
            return summary.getAsString().trim();
        }
        if (summary.isJsonObject()) {
            StringBuilder sb = new StringBuilder();
            extractRichText(summary, sb);
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    private static void extractRichText(JsonElement el, StringBuilder sb) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            String type = readJsonString(obj, "type");
            if ("text".equals(type)) {
                String text = readJsonString(obj, "text");
                if (text != null) sb.append(text);
            }
            if (obj.has("content") && obj.get("content").isJsonArray()) {
                for (JsonElement child : obj.getAsJsonArray("content")) {
                    extractRichText(child, sb);
                }
                if ("paragraph".equals(type)) sb.append("\n\n");
            }
        } else if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                extractRichText(item, sb);
            }
        }
    }

    private boolean isPlaceholderTitle(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty()) return true;
        if (t.equalsIgnoreCase("Аниме")) return true;
        if (t.equalsIgnoreCase("Загрузка")) return true;
        if (t.equalsIgnoreCase("Загрузка...")) return true;
        return t.startsWith("Аниме #");
    }

    private boolean isPlaceholderUrl(String url) {
        if (url == null) return true;
        String u = url.trim();
        if (u.isEmpty()) return true;
        return u.contains("placeholder");
    }

    public void cleanup() {
        pause();
        if (!executor.isShutdown()) {
            executor.shutdownNow();
        }
        apiService.shutdown();
    }
}
