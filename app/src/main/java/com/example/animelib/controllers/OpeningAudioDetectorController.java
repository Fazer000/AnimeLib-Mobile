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
 * Контроллер автоматического запоминания и быстрого поиска опенингов по аудиодорожке
 * для эпизодов, в которых таймкод опенинга отсутствует в API.
 *
 * Логика работы:
 * 1. При ручном нажатии кнопки перемотки (skipForwardButton) в первые 8 минут серии (25–150с)
 *    в фоне извлекается компактный спектральный отпечаток расширенного окна [skipStart - 10с .. skipEnd + 10с]
 *    из самого лёгкого качества (360p / локальный файл) и сохраняется шаблон (до 3 на аниме: OP1/OP2/OP3).
 * 2. При открытии следующей серии без официального API-опенинга:
 *    - Поиск стартует сразу на onEpisodeAttached() без ожидания первого кадра и искусственной паузы.
 *    - Сначала мгновенно проверяется кэш найденных опенингов в БД.
 *    - Если кэша нет, запускается двухэтапное прогрессивное сканирование (параллельный HLS / 512KB prefetch MP4):
 *      * Этап 1 (первые ~28с): как только найден уверенный префикс опенинга (например, прямо с 0:00),
 *        опенинг МГНОВЕННО выделяется на таймбаре с расчётным концом и показывается тост.
 *      * Этап 2 (в фоне): догружается остаток опенинга для тихого уточнения точной секунды конца и кэширования.
 */
public class OpeningAudioDetectorController {

    private static final String TAG = "OpeningAudioDetector";

    private static final int MAX_OPENING_START_LEARN_SEC = 480; // Первые 8 минут
    private static final int MIN_SKIP_DURATION_SEC = 25;
    private static final int MAX_SKIP_DURATION_SEC = 150;
    private static final int REFERENCE_PADDING_SEC = 10;
    private static final int MAX_SCAN_SEC = 480; // Ищем опенинг в первых 8 минутах
    private static final int MAX_TEMPLATES_PER_ANIME = 3;

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
    private volatile String lastToastShownEpisodeKey = null;
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

        // Если в текущей серии нет официального опенинга из API, сразу подсветим пропущенный участок на таймбаре
        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && !tm.hasOfficialOpeningTimecode()) {
            tm.addDetectedOpeningTimecode(fromSec, toSec);
            if (sourceEpisodeId > 0) {
                lastToastShownEpisodeKey = animeId + ":" + sourceEpisodeId;
            }
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
                        scheduleScanForActiveEpisode(0L);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving opening fingerprint", e);
            }
        });
    }

    /**
     * Вызывается сразу при прикреплении плеера к эпизоду / смене источника / бесшовном переключении.
     * Мгновенно отменяет старый скан, проверяет кэш и БЕЗ ЗАДЕРЖКИ запускает быстрый поиск опенинга.
     */
    public void onEpisodeAttached() {
        if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;

        cancelActiveScan();
        lastScheduledScanKey = null;

        scheduleScanForActiveEpisode(0L);
    }

    /**
     * Страховочный вызов после рендера первого кадра: если на момент onEpisodeAttached URL ещё не был готов,
     * запускает поиск немедленно. Если поиск уже идёт — ничего не дублирует.
     */
    public void onEpisodeFirstFrameRendered() {
        if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;
        scheduleScanForActiveEpisode(0L);
    }

    private void scheduleScanForActiveEpisode(long delayMs) {
        if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;

        final String animeId = normalizeAnimeId(host.getCurrentAnimeId());
        final int episodeId = host.getCurrentEpisodeId();
        if (animeId == null || episodeId <= 0) return;

        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && tm.hasOfficialOpeningTimecode()) {
            return; // Официальный опенинг уже есть в API
        }

        final String sourceUrl = resolveLowestQualityAudioSourceUrl();
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            return; // URL ещё не разрешён — дождёмся onEpisodeFirstFrameRendered
        }

        final String scanKey = animeId + ":" + episodeId;
        if (scanKey.equals(lastScheduledScanKey)) {
            return; // Для этого эпизода скан уже запущен или завершён
        }
        lastScheduledScanKey = scanKey;

        if (pendingScanRunnable != null) {
            mainHandler.removeCallbacks(pendingScanRunnable);
            pendingScanRunnable = null;
        }

        final int expectedGen = scanGeneration.incrementAndGet();
        activeScanCancelFlag.set(true);
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        activeScanCancelFlag = cancelFlag;

        if (delayMs <= 0L) {
            startProgressiveScan(animeId, episodeId, scanKey, sourceUrl, expectedGen, cancelFlag);
        } else {
            pendingScanRunnable = () -> startProgressiveScan(animeId, episodeId, scanKey, sourceUrl, expectedGen, cancelFlag);
            mainHandler.postDelayed(pendingScanRunnable, delayMs);
        }
    }

    private void startProgressiveScan(@NonNull String animeId,
                                      int episodeId,
                                      @NonNull String episodeKey,
                                      @NonNull String sourceUrl,
                                      int gen,
                                      @NonNull AtomicBoolean cancelFlag) {
        if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get() || host.isActivityFinishingOrDestroyed()) {
            return;
        }

        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && tm.hasOfficialOpeningTimecode()) {
            return;
        }

        final OkHttpClient client = host.getStreamingOkHttpClientForUrl(sourceUrl);
        if (client == null && !AudioPcmExtractor.isLocalSource(sourceUrl)) {
            return;
        }

        final long durationMs = host.getCurrentDurationMs();
        final int episodeDurationSec = durationMs > 0 ? (int) (durationMs / 1000L) : -1;
        final int maxScanSec = (episodeDurationSec > 0)
                ? Math.min(MAX_SCAN_SEC, Math.max(90, episodeDurationSec))
                : MAX_SCAN_SEC;

        scanExecutor.execute(() -> {
            if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) return;

            String cacheKey = DetectedOpeningEntity.buildCacheKey(animeId, episodeId);

            // 1. Мгновенная проверка положительного кэша в БД (0 байт сети)
            DetectedOpeningEntity cached = null;
            try {
                cached = dao.getDetectedOpening(cacheKey);
                if (cached != null && cached.isValidDetection()) {
                    applyDetectedOpeningOnUi(gen, episodeKey, cached.getFromSec(), cached.getToSec(), true);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error reading detected opening cache", e);
            }

            // 2. Загружаем запомненные шаблоны опенингов для этого аниме
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

            // Если серия уже сканировалась последним шаблоном и опенинга не нашлось — не качаем повторно
            if (cached != null && !cached.isValidDetection() && cached.getFingerprintId() == latestTemplateId) {
                Log.d(TAG, "Skipping scan for ep=" + episodeId + " (negative cache hit for template=" + latestTemplateId + ")");
                return;
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

            long scanStartMs = System.currentTimeMillis();
            Log.d(TAG, "Starting fast progressive opening scan for anime=" + animeId
                    + " ep=" + episodeId + " templates=" + templates.size()
                    + " maxScanSec=" + maxScanSec + " url=" + sourceUrl);

            final AudioFingerprintEngine.MatchResult[] finalHolder = new AudioFingerprintEngine.MatchResult[1];
            final AudioFingerprintEngine.MatchResult[] provisionalHolder = new AudioFingerprintEngine.MatchResult[1];
            final boolean[] provisionalApplied = new boolean[]{false};

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

                        AudioFingerprintEngine.MatchResult bestProvisional = null;
                        for (AudioFingerprintEngine.Template tpl : templates) {
                            AudioFingerprintEngine.MatchResult match =
                                    AudioFingerprintEngine.findMatch(tpl, targetData, targetFrames, isFinalPass);
                            if (match != null) {
                                if (!match.isProvisional) {
                                    finalHolder[0] = match;
                                    Log.d(TAG, "Found exact opening for ep=" + episodeId
                                            + " in " + (System.currentTimeMillis() - scanStartMs) + "ms"
                                            + ": [" + match.fromSec + "s .. " + match.toSec + "s]"
                                            + " conf=" + String.format(Locale.US, "%.2f", match.confidence)
                                            + " z=" + String.format(Locale.US, "%.1f", match.zScore));
                                    return true; // Точные границы получены — немедленно останавливаем загрузку!
                                } else if (bestProvisional == null || match.confidence > bestProvisional.confidence) {
                                    bestProvisional = match;
                                }
                            }
                        }

                        if (bestProvisional != null) {
                            int clampedTo = clampToEpisodeDuration(bestProvisional.toSec, episodeDurationSec, bestProvisional.fromSec);
                            provisionalHolder[0] = bestProvisional;
                            if (!provisionalApplied[0]) {
                                provisionalApplied[0] = true;
                                Log.d(TAG, "Early-detected opening for ep=" + episodeId
                                        + " in " + (System.currentTimeMillis() - scanStartMs) + "ms"
                                        + " (decoded " + (targetFrames / AudioFingerprintEngine.FRAMES_PER_SECOND) + "s)"
                                        + ": [" + bestProvisional.fromSec + "s .. ~" + clampedTo + "s]"
                                        + " conf=" + String.format(Locale.US, "%.2f", bestProvisional.confidence));
                                // Мгновенно подсвечиваем на таймбаре и показываем тост!
                                applyDetectedOpeningOnUi(gen, episodeKey, bestProvisional.fromSec, clampedTo, true);
                                // Сразу сохраняем в БД, чтобы даже при раннем выходе результат не потерялся
                                saveDetectedOpeningCache(cacheKey, animeId, episodeId,
                                        bestProvisional.fromSec, clampedTo, bestProvisional.confidence, bestProvisional.templateId);
                            }
                        }

                        // Если ранний опенинг уже показан и мы просканировали далеко за его расчётный конец,
                        // не тратим лишний трафик на остальные 5–6 минут серии.
                        if (provisionalHolder[0] != null) {
                            int decodedSec = targetFrames / AudioFingerprintEngine.FRAMES_PER_SECOND;
                            if (decodedSec >= provisionalHolder[0].toSec + 24) {
                                return true;
                            }
                        }
                        return false;
                    }
            );

            if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) {
                return;
            }

            AudioFingerprintEngine.MatchResult exactResult = finalHolder[0];
            if (exactResult != null) {
                int clampedTo = clampToEpisodeDuration(exactResult.toSec, episodeDurationSec, exactResult.fromSec);
                saveDetectedOpeningCache(cacheKey, animeId, episodeId,
                        exactResult.fromSec, clampedTo, exactResult.confidence, exactResult.templateId);
                // Если ранний этап уже показал тост — тихо уточняем точный край; если нет — показываем тост!
                applyDetectedOpeningOnUi(gen, episodeKey, exactResult.fromSec, clampedTo, !provisionalApplied[0]);
            } else if (provisionalHolder[0] != null) {
                // Оставляем уже применённый ранний результат (и гарантируем запись в кэш)
                AudioFingerprintEngine.MatchResult prov = provisionalHolder[0];
                int clampedTo = clampToEpisodeDuration(prov.toSec, episodeDurationSec, prov.fromSec);
                saveDetectedOpeningCache(cacheKey, animeId, episodeId,
                        prov.fromSec, clampedTo, prov.confidence, prov.templateId);
                if (!provisionalApplied[0]) {
                    applyDetectedOpeningOnUi(gen, episodeKey, prov.fromSec, clampedTo, true);
                }
            } else {
                saveDetectedOpeningCache(cacheKey, animeId, episodeId, -1, -1, 0f, latestTemplateId);
                Log.d(TAG, "No opening match found for ep=" + episodeId
                        + " (" + (System.currentTimeMillis() - scanStartMs) + "ms)");
            }
        });
    }

    private void saveDetectedOpeningCache(@NonNull String cacheKey,
                                          @NonNull String animeId,
                                          int episodeId,
                                          int fromSec,
                                          int toSec,
                                          float confidence,
                                          long fingerprintId) {
        try {
            DetectedOpeningEntity cacheEntry = new DetectedOpeningEntity();
            cacheEntry.setCacheKey(cacheKey);
            cacheEntry.setAnimeId(animeId);
            cacheEntry.setEpisodeId(episodeId);
            cacheEntry.setFromSec(fromSec);
            cacheEntry.setToSec(toSec);
            cacheEntry.setConfidence(confidence);
            cacheEntry.setFingerprintId(fingerprintId);
            cacheEntry.setDetectedAt(System.currentTimeMillis());
            dao.upsertDetectedOpening(cacheEntry);
        } catch (Exception e) {
            Log.w(TAG, "Failed caching detected opening", e);
        }
    }

    private static int clampToEpisodeDuration(int toSec, int episodeDurationSec, int fromSec) {
        if (episodeDurationSec > 0 && toSec > episodeDurationSec) {
            return Math.max(fromSec + 10, episodeDurationSec);
        }
        return toSec;
    }

    private void applyDetectedOpeningOnUi(int expectedGen,
                                          @NonNull String episodeKey,
                                          int fromSec,
                                          int toSec,
                                          boolean allowToast) {
        host.safeRunOnUiThread(() -> {
            if (isCleanedUp || expectedGen != scanGeneration.get() || host.isActivityFinishingOrDestroyed()) {
                return;
            }
            TimecodeManager tm = host.getTimecodeManager();
            if (tm != null && tm.addDetectedOpeningTimecode(fromSec, toSec)) {
                if (allowToast && !episodeKey.equals(lastToastShownEpisodeKey)) {
                    lastToastShownEpisodeKey = episodeKey;
                    Context uiCtx = host.getContext();
                    if (uiCtx != null) {
                        CustomToast.showSuccess(uiCtx, "Опенинг найден: "
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
