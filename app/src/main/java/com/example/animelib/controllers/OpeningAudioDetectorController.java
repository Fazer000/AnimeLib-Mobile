package com.example.animelib.controllers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.api.KodikLinksExtractor;
import com.example.animelib.audio.AudioFingerprintEngine;
import com.example.animelib.audio.AudioPcmExtractor;
import com.example.animelib.data.AppDatabase;
import com.example.animelib.data.dao.OpeningFingerprintDao;
import com.example.animelib.data.entity.DetectedOpeningEntity;
import com.example.animelib.data.entity.OpeningFingerprintEntity;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.CustomToast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * Контроллер автоматического запоминания и поиска опенингов по аудиодорожке
 * для эпизодов, в которых таймкод опенинга отсутствует в API.
 *
 * Логика работы:
 * 1. Когда пользователь нажимает ручную кнопку пропуска (skipForwardButton) в начале серии
 *    (первые 8 минут, длительность пропуска 25–150с), контроллер в фоне извлекает компактный
 *    спектральный отпечаток расширенного окна [skipStart - 10с .. skipEnd + 10с] из самого
 *    лёгкого качества (360p / локальный файл) и сохраняет шаблон для этого аниме (до 3 шаблонов,
 *    чтобы поддерживать смену OP1 -> OP2 -> OP3).
 * 2. При запуске следующей серии без API-таймкода опенинга:
 *    - Сначала мгновенно проверяется кэш найденных опенингов в БД (0 мс, 0 байт сети).
 *    - Если кэша нет, после старта воспроизведения запускается прогрессивный скан первых 8 минут
 *      в минимальном качестве (360p), который останавливается сразу после нахождения полного опенинга.
 *    - Найденный сегмент с уточнёнными границами выделяется на таймбаре через TimecodeManager
 *      (и автоматически работает с кнопкой пропуска и автопропуском опенингов).
 */
public class OpeningAudioDetectorController {

    private static final String TAG = "OpeningAudioDetector";

    private static final int MAX_OPENING_START_LEARN_SEC = 480; // Первые 8 минут
    private static final int MIN_SKIP_DURATION_SEC = 25;
    private static final int MAX_SKIP_DURATION_SEC = 150;
    private static final int REFERENCE_PADDING_SEC = 10;
    private static final int MAX_SCAN_SEC = 480; // Ищем опенинг в первых 8 минутах
    private static final int MAX_TEMPLATES_PER_ANIME = 3;
    private static final long SCAN_START_DELAY_MS = 1200L;

    public interface Host {
        @Nullable Context getContext();
        @Nullable String getCurrentAnimeId();
        int getCurrentEpisodeId();
        long getCurrentDurationMs();
        boolean isPlayingDownloadedVideo();
        @Nullable String getCurrentVideoUrl();
        @Nullable String getCurrentVideoDomain();
        @Nullable EpisodeResponse.PlayerData getCurrentPlayerData();
        @Nullable KodikResponse getCurrentKodikResponse();
        @Nullable TimecodeManager getTimecodeManager();
        @Nullable OkHttpClient getStreamingOkHttpClientForUrl(@NonNull String url);
        boolean isActivityFinishingOrDestroyed();
        void safeRunOnUiThread(@NonNull Runnable runnable);
    }

    private final Context appContext;
    private final Host host;
    private final OpeningFingerprintDao dao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Отдельный пул для запоминания (не отменяется при переходе на следующую серию!)
    private final ExecutorService learnExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "OpeningLearnThread");
        t.setDaemon(true);
        return t;
    });

    // Отдельный пул для прогрессивного поиска в активной серии (отменяется при смене серии)
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "OpeningScanThread");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger scanGeneration = new AtomicInteger(0);
    private volatile AtomicBoolean activeScanCancelFlag = new AtomicBoolean(false);
    private volatile AtomicBoolean activeLearnCancelFlag = new AtomicBoolean(false);
    private volatile boolean isCleanedUp = false;
    private volatile long lastLearnTriggerMs = 0L;
    private volatile String lastScheduledScanKey = null;
    private Runnable pendingScanRunnable = null;

    public OpeningAudioDetectorController(@NonNull Context context, @NonNull Host host) {
        this.appContext = context.getApplicationContext();
        this.host = host;
        this.dao = AppDatabase.getDatabase(this.appContext).openingFingerprintDao();
    }

    /**
     * Вызывается при ручном нажатии кнопки пропуска вперёд (skipForwardButton).
     * Запоминает аудио-отпечаток пропущенного опенинга для поиска в следующих сериях.
     */
    public void onManualSkipForward(long fromPositionMs, long toPositionMs, int skipDurationSec) {
        if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;

        final String animeId = normalizeAnimeId(host.getCurrentAnimeId());
        if (animeId == null) return;

        final int fromSec = (int) Math.max(0L, fromPositionMs / 1000L);
        final int toSec = (int) Math.max(fromSec, toPositionMs / 1000L);
        final int actualSpanSec = toSec - fromSec;

        if (fromSec > MAX_OPENING_START_LEARN_SEC) {
            Log.d(TAG, "Skip at " + fromSec + "s is outside opening window, ignoring learn");
            return;
        }
        if (actualSpanSec < MIN_SKIP_DURATION_SEC || actualSpanSec > MAX_SKIP_DURATION_SEC) {
            Log.d(TAG, "Skip span " + actualSpanSec + "s is outside opening duration bounds, ignoring learn");
            return;
        }

        // Анти-дубль, если пользователь случайно нажал дважды подряд
        long now = System.currentTimeMillis();
        if (now - lastLearnTriggerMs < 1500L) {
            return;
        }
        lastLearnTriggerMs = now;

        final int sourceEpisodeId = host.getCurrentEpisodeId();
        final long durationMs = host.getCurrentDurationMs();
        final double durationSec = durationMs > 0 ? (durationMs / 1000.0) : (toSec + REFERENCE_PADDING_SEC + 30.0);
        final String sourceUrl = resolveLowestQualityAudioSourceUrl();
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            return;
        }
        final OkHttpClient client = host.getStreamingOkHttpClientForUrl(sourceUrl);
        if (client == null && !AudioPcmExtractor.isLocalSource(sourceUrl)) {
            return;
        }

        // Если в текущей серии нет опенинга из API, сразу подсветим пропущенный участок на таймбаре
        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && !tm.hasOpeningTimecode()) {
            tm.addDetectedOpeningTimecode(fromSec, toSec);
        }

        // Отменяем предыдущую задачу обучения, если она ещё шла
        activeLearnCancelFlag.set(true);
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        activeLearnCancelFlag = cancelFlag;

        final double extractStartSec = Math.max(0.0, fromSec - REFERENCE_PADDING_SEC);
        final double extractEndSec = Math.min(durationSec, toSec + REFERENCE_PADDING_SEC);
        final int coreStartFrame = (int) Math.round((fromSec - extractStartSec) * AudioFingerprintEngine.FRAMES_PER_SECOND);
        final int coreEndFrame = (int) Math.round((toSec - extractStartSec) * AudioFingerprintEngine.FRAMES_PER_SECOND);

        Log.d(TAG, "Learning opening fingerprint for anime=" + animeId
                + " ep=" + sourceEpisodeId + " skip=[" + fromSec + "s.." + toSec + "s]"
                + " extract=[" + extractStartSec + "s.." + extractEndSec + "s] url=" + sourceUrl);

        learnExecutor.execute(() -> {
            if (isCleanedUp || cancelFlag.get()) return;

            OkHttpClient safeClient = client != null ? client : new OkHttpClient();
            File cacheDir = appContext.getCacheDir();

            AudioPcmExtractor.ExtractedFingerprint extracted = AudioPcmExtractor.extractRangeFingerprint(
                    sourceUrl, extractStartSec, extractEndSec, safeClient, cacheDir, cancelFlag);

            if (isCleanedUp || cancelFlag.get() || extracted == null || extracted.frameCount < 48) {
                Log.w(TAG, "Failed to extract opening audio fingerprint (frames="
                        + (extracted != null ? extracted.frameCount : 0) + ")");
                return;
            }

            int clampedCoreStart = Math.max(0, Math.min(coreStartFrame, extracted.frameCount - 1));
            int clampedCoreEnd = Math.max(clampedCoreStart, Math.min(coreEndFrame, extracted.frameCount - 1));

            AudioFingerprintEngine.Template newTemplate = new AudioFingerprintEngine.Template(
                    0L, extracted.data, extracted.frameCount, clampedCoreStart, clampedCoreEnd, actualSpanSec);

            try {
                // Дедупликация: если похожий опенинг уже сохранён для этого аниме, обновляем его
                List<OpeningFingerprintEntity> existingList = dao.getFingerprintsForAnime(animeId);
                long duplicateIdToReplace = -1L;
                if (existingList != null) {
                    for (OpeningFingerprintEntity existing : existingList) {
                        AudioFingerprintEngine.Template existingTpl = toTemplate(existing);
                        if (existingTpl != null) {
                            float sim = AudioFingerprintEngine.computeTemplateSimilarity(newTemplate, existingTpl);
                            if (sim >= 0.65f) {
                                duplicateIdToReplace = existing.getId();
                                break;
                            }
                        }
                    }
                }

                OpeningFingerprintEntity entity = new OpeningFingerprintEntity();
                if (duplicateIdToReplace > 0) {
                    entity.setId(duplicateIdToReplace);
                }
                entity.setAnimeId(animeId);
                entity.setSourceEpisodeId(sourceEpisodeId);
                entity.setFingerprintBase64(Base64.encodeToString(extracted.data, Base64.NO_WRAP));
                entity.setFrameCount(extracted.frameCount);
                entity.setBandCount(AudioFingerprintEngine.NUM_BANDS);
                entity.setHopMs(AudioFingerprintEngine.HOP_MS);
                entity.setCoreStartFrame(clampedCoreStart);
                entity.setCoreEndFrame(clampedCoreEnd);
                entity.setSkipDurationSec(actualSpanSec);
                entity.setCreatedAt(System.currentTimeMillis());

                long savedId = dao.insertFingerprint(entity);
                dao.pruneOldFingerprintsForAnime(animeId, MAX_TEMPLATES_PER_ANIME);

                // Сбрасываем отрицательный кэш, чтобы эпизоды без совпадения могли провериться новым шаблоном (напр. OP2)
                dao.clearNegativeCacheForAnime(animeId);

                // Сохраняем текущий эпизод в положительный кэш
                if (sourceEpisodeId > 0) {
                    DetectedOpeningEntity selfCache = new DetectedOpeningEntity();
                    selfCache.setCacheKey(DetectedOpeningEntity.buildCacheKey(animeId, sourceEpisodeId));
                    selfCache.setAnimeId(animeId);
                    selfCache.setEpisodeId(sourceEpisodeId);
                    selfCache.setFromSec(fromSec);
                    selfCache.setToSec(toSec);
                    selfCache.setConfidence(1.0f);
                    selfCache.setFingerprintId(savedId);
                    selfCache.setDetectedAt(System.currentTimeMillis());
                    dao.upsertDetectedOpening(selfCache);
                }

                Log.d(TAG, "Saved opening fingerprint id=" + savedId + " frames=" + extracted.frameCount
                        + " for anime=" + animeId);

                host.safeRunOnUiThread(() -> {
                    if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;
                    Context uiCtx = host.getContext();
                    if (uiCtx != null) {
                        CustomToast.showInfo(uiCtx, "Опенинг запомнен — будет искаться в следующих сериях");
                    }
                    // Если пользователь уже успел переключиться на другую серию без опенинга, сразу запускаем поиск!
                    if (host.getCurrentEpisodeId() != sourceEpisodeId) {
                        lastScheduledScanKey = null;
                        scheduleScanForActiveEpisode(200L);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving opening fingerprint", e);
            }
        });
    }

    /**
     * Вызывается сразу при прикреплении плеера к эпизоду / смене источника / бесшовном переключении.
     * Мгновенно применяет кэшированный опенинг (если есть) и отменяет старый фоновый скан.
     */
    public void onEpisodeAttached() {
        if (isCleanedUp) return;

        cancelActiveScan();
        lastScheduledScanKey = null;

        final String animeId = normalizeAnimeId(host.getCurrentAnimeId());
        final int episodeId = host.getCurrentEpisodeId();
        if (animeId == null || episodeId <= 0) return;

        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && tm.hasOpeningTimecode()) {
            return; // В API уже есть опенинг
        }

        // Мгновенная проверка кэша (Room разрешает быстрое чтение, но выполним на scanExecutor, чтобы не грузить UI)
        final int gen = scanGeneration.get();
        scanExecutor.execute(() -> {
            if (isCleanedUp || gen != scanGeneration.get()) return;
            try {
                String cacheKey = DetectedOpeningEntity.buildCacheKey(animeId, episodeId);
                DetectedOpeningEntity cached = dao.getDetectedOpening(cacheKey);
                if (cached != null && cached.isValidDetection()) {
                    host.safeRunOnUiThread(() -> {
                        if (isCleanedUp || gen != scanGeneration.get() || host.isActivityFinishingOrDestroyed()) return;
                        TimecodeManager liveTm = host.getTimecodeManager();
                        if (liveTm != null && !liveTm.hasOpeningTimecode()) {
                            liveTm.addDetectedOpeningTimecode(cached.getFromSec(), cached.getToSec());
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed checking detected opening cache", e);
            }
        });
    }

    /**
     * Вызывается после рендера первого кадра серии, чтобы фоновое сканирование не мешало старту буферизации.
     */
    public void onEpisodeFirstFrameRendered() {
        if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;
        scheduleScanForActiveEpisode(SCAN_START_DELAY_MS);
    }

    private void scheduleScanForActiveEpisode(long delayMs) {
        if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;

        final String animeId = normalizeAnimeId(host.getCurrentAnimeId());
        final int episodeId = host.getCurrentEpisodeId();
        if (animeId == null || episodeId <= 0) return;

        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && tm.hasOpeningTimecode()) {
            return; // Опенинг уже есть (из API или кэша)
        }

        final String scanKey = animeId + ":" + episodeId;
        if (scanKey.equals(lastScheduledScanKey)) {
            return;
        }
        lastScheduledScanKey = scanKey;

        if (pendingScanRunnable != null) {
            mainHandler.removeCallbacks(pendingScanRunnable);
        }

        final int expectedGen = scanGeneration.incrementAndGet();
        activeScanCancelFlag.set(true);
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        activeScanCancelFlag = cancelFlag;

        pendingScanRunnable = () -> startProgressiveScan(animeId, episodeId, expectedGen, cancelFlag);
        mainHandler.postDelayed(pendingScanRunnable, Math.max(0L, delayMs));
    }

    private void startProgressiveScan(@NonNull String animeId,
                                      int episodeId,
                                      int gen,
                                      @NonNull AtomicBoolean cancelFlag) {
        if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get() || host.isActivityFinishingOrDestroyed()) {
            return;
        }

        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && tm.hasOpeningTimecode()) {
            return;
        }

        final String sourceUrl = resolveLowestQualityAudioSourceUrl();
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            return;
        }
        final OkHttpClient client = host.getStreamingOkHttpClientForUrl(sourceUrl);
        if (client == null && !AudioPcmExtractor.isLocalSource(sourceUrl)) {
            return;
        }

        final long durationMs = host.getCurrentDurationMs();
        final int maxScanSec = (durationMs > 0)
                ? (int) Math.min(MAX_SCAN_SEC, Math.max(90L, durationMs / 1000L))
                : MAX_SCAN_SEC;

        scanExecutor.execute(() -> {
            if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) return;

            List<OpeningFingerprintEntity> savedEntities;
            try {
                savedEntities = dao.getFingerprintsForAnime(animeId);
            } catch (Exception e) {
                Log.w(TAG, "Error loading opening fingerprints", e);
                return;
            }
            if (savedEntities == null || savedEntities.isEmpty()) {
                return; // Для этого аниме ещё не запомнен ни один опенинг
            }

            long latestTemplateId = savedEntities.get(0).getId();
            String cacheKey = DetectedOpeningEntity.buildCacheKey(animeId, episodeId);

            try {
                DetectedOpeningEntity cached = dao.getDetectedOpening(cacheKey);
                if (cached != null) {
                    if (cached.isValidDetection()) {
                        applyDetectedOpeningOnUi(gen, cached.getFromSec(), cached.getToSec(), false);
                        return;
                    }
                    // Если серия уже сканировалась тем же (или более новым) шаблоном и опенинга не нашлось — не качаем повторно
                    if (cached.getFingerprintId() == latestTemplateId) {
                        Log.d(TAG, "Skipping scan for ep=" + episodeId + " (negative cache hit for template=" + latestTemplateId + ")");
                        return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error reading detected opening cache", e);
            }

            List<AudioFingerprintEngine.Template> templates = new ArrayList<>();
            for (OpeningFingerprintEntity entity : savedEntities) {
                AudioFingerprintEngine.Template tpl = toTemplate(entity);
                if (tpl != null) {
                    templates.add(tpl);
                }
            }
            if (templates.isEmpty()) {
                return;
            }

            Log.d(TAG, "Starting progressive opening scan for anime=" + animeId
                    + " ep=" + episodeId + " templates=" + templates.size()
                    + " maxScanSec=" + maxScanSec + " url=" + sourceUrl);

            final AudioFingerprintEngine.MatchResult[] foundHolder = new AudioFingerprintEngine.MatchResult[1];
            OkHttpClient safeClient = client != null ? client : new OkHttpClient();
            File cacheDir = appContext.getCacheDir();

            AudioPcmExtractor.scanProgressive(
                    sourceUrl,
                    maxScanSec,
                    safeClient,
                    cacheDir,
                    cancelFlag,
                    (targetData, targetFrames, isFinalPass) -> {
                        if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) {
                            return true;
                        }
                        for (AudioFingerprintEngine.Template tpl : templates) {
                            AudioFingerprintEngine.MatchResult match =
                                    AudioFingerprintEngine.findMatch(tpl, targetData, targetFrames, isFinalPass);
                            if (match != null) {
                                foundHolder[0] = match;
                                Log.d(TAG, "Found opening for ep=" + episodeId
                                        + ": [" + match.fromSec + "s .. " + match.toSec + "s]"
                                        + " conf=" + String.format(Locale.US, "%.2f", match.confidence)
                                        + " z=" + String.format(Locale.US, "%.1f", match.zScore)
                                        + " finalPass=" + isFinalPass);
                                return true; // Мгновенно останавливаем скачивание и декодирование!
                            }
                        }
                        return false;
                    }
            );

            if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) {
                return;
            }

            AudioFingerprintEngine.MatchResult result = foundHolder[0];
            DetectedOpeningEntity cacheEntry = new DetectedOpeningEntity();
            cacheEntry.setCacheKey(cacheKey);
            cacheEntry.setAnimeId(animeId);
            cacheEntry.setEpisodeId(episodeId);
            cacheEntry.setDetectedAt(System.currentTimeMillis());

            if (result != null) {
                cacheEntry.setFromSec(result.fromSec);
                cacheEntry.setToSec(result.toSec);
                cacheEntry.setConfidence(result.confidence);
                cacheEntry.setFingerprintId(result.templateId);
                try {
                    dao.upsertDetectedOpening(cacheEntry);
                } catch (Exception e) {
                    Log.w(TAG, "Failed caching detected opening", e);
                }
                applyDetectedOpeningOnUi(gen, result.fromSec, result.toSec, true);
            } else {
                cacheEntry.setFromSec(-1);
                cacheEntry.setToSec(-1);
                cacheEntry.setConfidence(0f);
                cacheEntry.setFingerprintId(latestTemplateId);
                try {
                    dao.upsertDetectedOpening(cacheEntry);
                } catch (Exception e) {
                    Log.w(TAG, "Failed caching negative opening result", e);
                }
                Log.d(TAG, "No opening match found for ep=" + episodeId);
            }
        });
    }

    private void applyDetectedOpeningOnUi(int expectedGen, int fromSec, int toSec, boolean showToast) {
        host.safeRunOnUiThread(() -> {
            if (isCleanedUp || expectedGen != scanGeneration.get() || host.isActivityFinishingOrDestroyed()) {
                return;
            }
            TimecodeManager tm = host.getTimecodeManager();
            if (tm != null && tm.addDetectedOpeningTimecode(fromSec, toSec)) {
                if (showToast) {
                    Context uiCtx = host.getContext();
                    if (uiCtx != null) {
                        CustomToast.showInfo(uiCtx, "Найден опенинг: "
                                + formatSec(fromSec) + " – " + formatSec(toSec));
                    }
                }
            }
        });
    }

    /**
     * Выбирает URL самого лёгкого качества (360p) для минимального расхода трафика и максимальной скорости,
     * так как аудиодорожка идентична во всех разрешениях.
     */
    @Nullable
    private String resolveLowestQualityAudioSourceUrl() {
        String currentUrl = host.getCurrentVideoUrl();
        if (host.isPlayingDownloadedVideo() || AudioPcmExtractor.isLocalSource(currentUrl)) {
            return currentUrl;
        }

        EpisodeResponse.PlayerData pd = host.getCurrentPlayerData();
        if (pd != null) {
            if ("animelib".equalsIgnoreCase(pd.getPlayer())
                    && pd.getVideo() != null
                    && pd.getVideo().getQuality() != null
                    && !pd.getVideo().getQuality().isEmpty()) {
                EpisodeResponse.QualityData lowest = null;
                for (EpisodeResponse.QualityData q : pd.getVideo().getQuality()) {
                    if (q == null || q.getHref() == null || q.getHref().trim().isEmpty()) continue;
                    if (lowest == null || (q.getQuality() > 0 && q.getQuality() < lowest.getQuality())) {
                        lowest = q;
                    }
                }
                if (lowest != null) {
                    String domain = host.getCurrentVideoDomain();
                    return VideoUrlHelper.toAbsoluteVideoUrl(lowest.getHref(), domain);
                }
            } else if ("kodik".equalsIgnoreCase(pd.getPlayer())) {
                KodikResponse kodikResponse = host.getCurrentKodikResponse();
                if (kodikResponse != null) {
                    List<String> lowCandidates = KodikLinksExtractor.collectCandidateUrls(kodikResponse, "360p", false);
                    if (lowCandidates != null && !lowCandidates.isEmpty()) {
                        return lowCandidates.get(0);
                    }
                }
            }
        }

        return currentUrl;
    }

    @Nullable
    private AudioFingerprintEngine.Template toTemplate(@Nullable OpeningFingerprintEntity entity) {
        if (entity == null || entity.getFingerprintBase64().isEmpty()) return null;
        if (entity.getBandCount() != AudioFingerprintEngine.NUM_BANDS
                || entity.getHopMs() != AudioFingerprintEngine.HOP_MS) {
            return null;
        }
        try {
            byte[] decoded = Base64.decode(entity.getFingerprintBase64(), Base64.DEFAULT);
            AudioFingerprintEngine.Template tpl = new AudioFingerprintEngine.Template(
                    entity.getId(),
                    decoded,
                    entity.getFrameCount(),
                    entity.getCoreStartFrame(),
                    entity.getCoreEndFrame(),
                    entity.getSkipDurationSec()
            );
            return tpl.isValid() ? tpl : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String normalizeAnimeId(@Nullable String animeId) {
        if (animeId == null) return null;
        String trimmed = animeId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String formatSec(int totalSec) {
        int m = totalSec / 60;
        int s = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    public void cancelActiveScan() {
        scanGeneration.incrementAndGet();
        activeScanCancelFlag.set(true);
        if (pendingScanRunnable != null) {
            mainHandler.removeCallbacks(pendingScanRunnable);
            pendingScanRunnable = null;
        }
    }

    public void cleanup() {
        isCleanedUp = true;
        cancelActiveScan();
        activeLearnCancelFlag.set(true);
        try {
            scanExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
        try {
            learnExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
    }
}
