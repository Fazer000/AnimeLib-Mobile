package com.example.animelib.controllers;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * Контроллер автоматического запоминания и сверхбыстрого поиска опенингов по аудиодорожке
 * для эпизодов, в которых таймкод опенинга отсутствует в API.
 *
 * Архитектура v2 (Sparse Probing + Dual-Edge Refinement):
 * 1. Запоминание: при ручном нажатии кнопки пропуска (skipForwardButton) в первые 8 минут серии (25–150с)
 *    в фоне извлекается компактный спектральный отпечаток расширенного окна [skipStart - 10с .. skipEnd + 10с]
 *    с автоматическим fallback с 360p на текущий рабочий URL потока (до 3 шаблонов на аниме: OP1/OP2/OP3).
 * 2. Сверхбыстрый поиск в следующей серии (без загрузки 8 минут целиком):
 *    - Мгновенная проверка положительного кэша в БД.
 *    - Выборочные 18-секундные зонды с шагом ~44с (0..18с, 44..62с, 88..106с...):
 *      * Если опенинг начинается с 0:00 — находится на ПЕРВОМ ЖЕ ЗОНДЕ (~1–2 сек, ~3 HLS-сегмента!).
 *      * Если опенинг после пролога — скачиваются только короткие зонды без промежутков (в 3–5x меньше данных!).
 *    - При первом попадании: опенинг СРАЗУ выделяется на таймбаре, показывается зелёный тост и скрывается индикатор.
 *    - Далее тихо в фоне уточняются только левая и правая границы опенинга (два коротких краевых окна).
 */
public class OpeningAudioDetectorController {

    private static final String TAG = "OpeningAudioDetector";
    private static final String PREFS_NAME = "opening_audio_detector_prefs";
    private static final String KEY_ENGINE_VERSION = "engine_version";
    private static final int CURRENT_ENGINE_VERSION = 2;

    private static final int MAX_OPENING_START_LEARN_SEC = 480; // Первые 8 минут
    private static final int MIN_SKIP_DURATION_SEC = 25;
    private static final int MAX_SKIP_DURATION_SEC = 150;
    private static final int REFERENCE_PADDING_SEC = 10;
    private static final int MAX_SCAN_SEC = 460; // Ищем опенинг в первых ~7.5 минутах
    private static final int MAX_TEMPLATES_PER_ANIME = 3;
    private static final double PROBE_DURATION_SEC = 18.0;

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
        default void onAnalysisRunningChanged(boolean running) {}
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

    // Отдельный пул для поиска в активной серии (отменяется при смене серии)
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "OpeningScanThread");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger scanGeneration = new AtomicInteger(0);
    private final AtomicInteger activeLearnOps = new AtomicInteger(0);
    private final AtomicInteger activeScanOps = new AtomicInteger(0);
    private final AtomicBoolean lastReportedRunning = new AtomicBoolean(false);
    private final AtomicBoolean cacheMigrationChecked = new AtomicBoolean(false);

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

    public boolean isAnalysisRunning() {
        return activeLearnOps.get() > 0 || activeScanOps.get() > 0;
    }

    private void notifyAnalysisStateChanged() {
        boolean running = !isCleanedUp && (activeLearnOps.get() > 0 || activeScanOps.get() > 0);
        if (lastReportedRunning.getAndSet(running) != running) {
            host.safeRunOnUiThread(() -> {
                if (!host.isActivityFinishingOrDestroyed()) {
                    host.onAnalysisRunningChanged(running);
                }
            });
        }
    }

    private void ensureNegativeCacheMigrated() {
        if (cacheMigrationChecked.compareAndSet(false, true)) {
            try {
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                int savedVer = prefs.getInt(KEY_ENGINE_VERSION, 1);
                if (savedVer < CURRENT_ENGINE_VERSION) {
                    dao.clearAllNegativeCache();
                    prefs.edit().putInt(KEY_ENGINE_VERSION, CURRENT_ENGINE_VERSION).apply();
                    Log.d(TAG, "Cleared stale negative opening cache on upgrade to engine v" + CURRENT_ENGINE_VERSION);
                }
            } catch (Exception e) {
                Log.w(TAG, "Engine cache migration check failed", e);
            }
        }
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
        final List<String> sourceCandidates = resolveAudioSourceCandidates();
        if (sourceCandidates.isEmpty()) {
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
                + " extract=[" + extractStartSec + "s.." + extractEndSec + "s]"
                + " candidates=" + sourceCandidates.size());

        activeLearnOps.incrementAndGet();
        notifyAnalysisStateChanged();

        learnExecutor.execute(() -> {
            try {
                if (isCleanedUp || cancelFlag.get()) return;
                ensureNegativeCacheMigrated();

                File cacheDir = appContext.getCacheDir();
                AudioPcmExtractor.ExtractedFingerprint extracted = null;

                // Пробуем источники по порядку: сначала лёгкий 360p, затем fallback на текущий рабочий URL плеера
                for (String candidateUrl : sourceCandidates) {
                    if (isCleanedUp || cancelFlag.get()) return;
                    OkHttpClient client = host.getStreamingOkHttpClientForUrl(candidateUrl);
                    if (client == null && !AudioPcmExtractor.isLocalSource(candidateUrl)) {
                        continue;
                    }
                    OkHttpClient safeClient = client != null ? client : new OkHttpClient();
                    AudioPcmExtractor.ExtractedFingerprint fp = AudioPcmExtractor.extractRangeFingerprint(
                            candidateUrl, extractStartSec, extractEndSec, safeClient, cacheDir, cancelFlag);
                    if (fp != null && fp.frameCount >= 48) {
                        extracted = fp;
                        break;
                    }
                    Log.w(TAG, "Learn candidate failed or too short (frames="
                            + (fp != null ? fp.frameCount : 0) + "), trying fallback: " + candidateUrl);
                }

                if (isCleanedUp || cancelFlag.get() || extracted == null || extracted.frameCount < 48) {
                    Log.w(TAG, "Failed to extract opening audio fingerprint from all candidates");
                    return;
                }

                int clampedCoreStart = Math.max(0, Math.min(coreStartFrame, extracted.frameCount - 1));
                int clampedCoreEnd = Math.max(clampedCoreStart, Math.min(coreEndFrame, extracted.frameCount - 1));

                AudioFingerprintEngine.Template newTemplate = new AudioFingerprintEngine.Template(
                        0L, extracted.data, extracted.frameCount, clampedCoreStart, clampedCoreEnd, actualSpanSec);

                // Дедупликация: если похожий опенинг уже сохранён для этого аниме, обновляем его
                List<OpeningFingerprintEntity> existingList = dao.getFingerprintsForAnime(animeId);
                long duplicateIdToReplace = -1L;
                if (existingList != null) {
                    for (OpeningFingerprintEntity existing : existingList) {
                        AudioFingerprintEngine.Template existingTpl = toTemplate(existing);
                        if (existingTpl != null) {
                            float sim = AudioFingerprintEngine.computeTemplateSimilarity(newTemplate, existingTpl);
                            if (sim >= 0.62f) {
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
                    saveDetectedOpeningCache(
                            DetectedOpeningEntity.buildCacheKey(animeId, sourceEpisodeId),
                            animeId, sourceEpisodeId, fromSec, toSec, 1.0f, savedId);
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
            } finally {
                activeLearnOps.decrementAndGet();
                notifyAnalysisStateChanged();
            }
        });
    }

    /**
     * Вызывается сразу при прикреплении плеера к эпизоду / смене источника / бесшовном переключении.
     * Мгновенно отменяет старый скан, проверяет кэш и БЕЗ ЗАДЕРЖКИ запускает быстрый зондовый поиск.
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

        final List<String> candidates = resolveAudioSourceCandidates();
        if (candidates.isEmpty()) {
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
            startFastProbeScan(animeId, episodeId, scanKey, candidates, expectedGen, cancelFlag);
        } else {
            pendingScanRunnable = () -> startFastProbeScan(animeId, episodeId, scanKey, candidates, expectedGen, cancelFlag);
            mainHandler.postDelayed(pendingScanRunnable, delayMs);
        }
    }

    private void startFastProbeScan(@NonNull String animeId,
                                    int episodeId,
                                    @NonNull String episodeKey,
                                    @NonNull List<String> sourceCandidates,
                                    int gen,
                                    @NonNull AtomicBoolean cancelFlag) {
        if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get() || host.isActivityFinishingOrDestroyed()) {
            return;
        }

        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && tm.hasOfficialOpeningTimecode()) {
            return;
        }

        final long durationMs = host.getCurrentDurationMs();
        final int initialEpisodeDurationSec = durationMs > 0 ? (int) (durationMs / 1000L) : -1;

        scanExecutor.execute(() -> {
            if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) return;
            ensureNegativeCacheMigrated();

            String cacheKey = DetectedOpeningEntity.buildCacheKey(animeId, episodeId);

            // 1. Мгновенная проверка положительного кэша в БД (0 байт сети, индикатор не мигает)
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
            int minTemplateSkipSec = 85;
            for (OpeningFingerprintEntity entity : savedEntities) {
                AudioFingerprintEngine.Template tpl = toTemplate(entity);
                if (tpl != null) {
                    templates.add(tpl);
                    if (tpl.skipDurationSec < minTemplateSkipSec) {
                        minTemplateSkipSec = tpl.skipDurationSec;
                    }
                }
            }
            if (templates.isEmpty()) {
                return;
            }

            // Включаем индикатор поиска рядом с таймкодом
            final AtomicBoolean indicatorReleased = new AtomicBoolean(false);
            activeScanOps.incrementAndGet();
            notifyAnalysisStateChanged();

            Runnable releaseIndicator = () -> {
                if (indicatorReleased.compareAndSet(false, true)) {
                    activeScanOps.decrementAndGet();
                    notifyAnalysisStateChanged();
                }
            };

            long scanStartMs = System.currentTimeMillis();
            File cacheDir = appContext.getCacheDir();
            AudioPcmExtractor.AudioProbeSession session = null;

            try {
                // Открываем переиспользуемую сессию (360p -> fallback на текущий URL плеера)
                // и сразу проверяем Probe 0 (0..18с): если 360p-зеркало отдало битые сегменты, переходим к следующему URL!
                AudioPcmExtractor.ExtractedFingerprint probe0Window = null;
                for (String candidateUrl : sourceCandidates) {
                    if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) return;
                    OkHttpClient client = host.getStreamingOkHttpClientForUrl(candidateUrl);
                    if (client == null && !AudioPcmExtractor.isLocalSource(candidateUrl)) {
                        continue;
                    }
                    OkHttpClient safeClient = client != null ? client : new OkHttpClient();
                    AudioPcmExtractor.AudioProbeSession opened =
                            AudioPcmExtractor.AudioProbeSession.open(candidateUrl, safeClient, cacheDir, cancelFlag);
                    if (opened == null) {
                        continue;
                    }
                    AudioPcmExtractor.ExtractedFingerprint firstWin = opened.extractWindow(0.0, PROBE_DURATION_SEC);
                    if (firstWin != null && firstWin.frameCount >= 44) {
                        session = opened;
                        probe0Window = firstWin;
                        Log.d(TAG, "Opened verified probe session for ep=" + episodeId + " url=" + candidateUrl);
                        break;
                    }
                    Log.w(TAG, "Probe 0 failed on candidate, trying fallback: " + candidateUrl);
                    try {
                        opened.close();
                    } catch (Exception ignored) {
                    }
                }

                if (session == null || isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) {
                    Log.w(TAG, "Could not open any working audio probe session for ep=" + episodeId);
                    return;
                }

                int sessionDurSec = (int) Math.round(session.getKnownDurationSec());
                int episodeDurationSec = initialEpisodeDurationSec > 0
                        ? initialEpisodeDurationSec
                        : (sessionDurSec > 0 ? sessionDurSec : -1);
                int maxScanSec = episodeDurationSec > 0
                        ? Math.min(MAX_SCAN_SEC, Math.max(90, episodeDurationSec))
                        : MAX_SCAN_SEC;

                // Выбираем шаг между 18с зондами так, чтобы ЛЮБОЙ опенинг гарантированно полностью накрыл хотя бы 1 зонд
                int probeStepSec = Math.max(24, Math.min(44, minTemplateSkipSec - 26));

                AudioFingerprintEngine.MatchResult probeHit = null;
                AudioFingerprintEngine.Template matchedTemplate = null;
                int decodedProbesCount = 0;

                for (double probeStart = 0.0; probeStart < maxScanSec - 10.0; probeStart += probeStepSec) {
                    if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) {
                        return;
                    }

                    double probeEnd = Math.min(maxScanSec, probeStart + PROBE_DURATION_SEC);
                    if (probeEnd - probeStart < 12.0) {
                        break;
                    }

                    AudioPcmExtractor.ExtractedFingerprint win = (probeStart == 0.0 && probe0Window != null)
                            ? probe0Window
                            : session.extractWindow(probeStart, probeEnd);
                    if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) {
                        return;
                    }
                    if (win == null || win.frameCount < 44) {
                        continue;
                    }
                    decodedProbesCount++;

                    for (AudioFingerprintEngine.Template tpl : templates) {
                        AudioFingerprintEngine.MatchResult match = AudioFingerprintEngine.findProbeMatch(
                                tpl, win.data, win.frameCount, win.actualStartSec);
                        if (match != null) {
                            probeHit = match;
                            matchedTemplate = tpl;
                            break;
                        }
                    }
                    if (probeHit != null) {
                        break;
                    }
                }

                if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) {
                    return;
                }

                if (probeHit != null && matchedTemplate != null) {
                    int estFrom = Math.max(0, probeHit.fromSec);
                    int estTo = clampToEpisodeDuration(probeHit.toSec, episodeDurationSec, estFrom);

                    Log.d(TAG, "Fast probe HIT for ep=" + episodeId
                            + " in " + (System.currentTimeMillis() - scanStartMs) + "ms"
                            + " (probes=" + decodedProbesCount + ")"
                            + ": [" + estFrom + "s .. " + estTo + "s]"
                            + " conf=" + String.format(Locale.US, "%.2f", probeHit.confidence)
                            + " z=" + String.format(Locale.US, "%.1f", probeHit.zScore));

                    // Сразу скрываем индикатор, подсвечиваем опенинг на таймбаре, показываем тост и пишем в кэш!
                    releaseIndicator.run();
                    applyDetectedOpeningOnUi(gen, episodeKey, estFrom, estTo, true);
                    saveDetectedOpeningCache(cacheKey, animeId, episodeId,
                            estFrom, estTo, probeHit.confidence, matchedTemplate.id);

                    // Этап 2 (тихо в фоне): уточняем точную левую и правую границу всего по двум коротким краевым окнам
                    refineEdgesSilently(session, matchedTemplate, probeHit, estFrom, estTo,
                            episodeDurationSec, probe0Window, gen, episodeKey, cacheKey, animeId, episodeId, cancelFlag);
                } else {
                    // Записываем отрицательный кэш ТОЛЬКО если реально удалось декодировать аудио (>= 2 зондов),
                    // чтобы временный сбой сети никогда не блокировал повторный поиск.
                    if (decodedProbesCount >= 2) {
                        saveDetectedOpeningCache(cacheKey, animeId, episodeId, -1, -1, 0f, latestTemplateId);
                    }
                    Log.d(TAG, "No opening match found for ep=" + episodeId
                            + " (probes=" + decodedProbesCount + ", " + (System.currentTimeMillis() - scanStartMs) + "ms)");
                }
            } catch (Exception e) {
                if (!cancelFlag.get()) {
                    Log.w(TAG, "Fast opening scan error for ep=" + episodeId + ": " + e.getMessage());
                }
            } finally {
                releaseIndicator.run();
                if (session != null) {
                    try {
                        session.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void refineEdgesSilently(@NonNull AudioPcmExtractor.AudioProbeSession session,
                                     @NonNull AudioFingerprintEngine.Template template,
                                     @NonNull AudioFingerprintEngine.MatchResult probeHit,
                                     int estFromSec,
                                     int estToSec,
                                     int episodeDurationSec,
                                     @Nullable AudioPcmExtractor.ExtractedFingerprint probe0Window,
                                     int gen,
                                     @NonNull String episodeKey,
                                     @NonNull String cacheKey,
                                     @NonNull String animeId,
                                     int episodeId,
                                     @NonNull AtomicBoolean cancelFlag) {
        if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) return;

        // 1. Левый край: если старт уже ~0:00 и есть Probe 0 — переиспользуем его бесплатно!
        AudioPcmExtractor.ExtractedFingerprint startWin;
        if (estFromSec <= 3 && probe0Window != null && probe0Window.frameCount >= 40) {
            startWin = probe0Window;
        } else {
            double sFrom = Math.max(0.0, estFromSec - 12.0);
            double sTo = estFromSec + 12.0;
            startWin = session.extractWindow(sFrom, sTo);
        }

        if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) return;

        // 2. Правый край: короткое окно вокруг конца опенинга
        double eFrom = Math.max(0.0, estToSec - 14.0);
        double eTo = episodeDurationSec > 0 ? Math.min(episodeDurationSec, estToSec + 14.0) : (estToSec + 14.0);
        AudioPcmExtractor.ExtractedFingerprint endWin = (eTo - eFrom >= 8.0)
                ? session.extractWindow(eFrom, eTo) : null;

        if (isCleanedUp || gen != scanGeneration.get() || cancelFlag.get()) return;

        int startOriginFrame = startWin != null
                ? (int) Math.round(startWin.actualStartSec * AudioFingerprintEngine.FRAMES_PER_SECOND) : 0;
        int endOriginFrame = endWin != null
                ? (int) Math.round(endWin.actualStartSec * AudioFingerprintEngine.FRAMES_PER_SECOND) : 0;

        int[] refined = AudioFingerprintEngine.refineEdgesAtLag(
                template,
                probeHit.bestLagFrames,
                estFromSec,
                estToSec,
                startWin != null ? startWin.data : null,
                startOriginFrame,
                startWin != null ? startWin.frameCount : 0,
                endWin != null ? endWin.data : null,
                endOriginFrame,
                endWin != null ? endWin.frameCount : 0
        );

        int refinedFrom = Math.max(0, refined[0]);
        int refinedTo = clampToEpisodeDuration(refined[1], episodeDurationSec, refinedFrom);

        if (refinedFrom != estFromSec || refinedTo != estToSec) {
            Log.d(TAG, "Refined opening edges for ep=" + episodeId
                    + ": [" + estFromSec + ".." + estToSec + "] -> [" + refinedFrom + ".." + refinedTo + "]");
            saveDetectedOpeningCache(cacheKey, animeId, episodeId,
                    refinedFrom, refinedTo, probeHit.confidence, template.id);
            // Тихо уточняем границы на таймбаре без повторного тоста
            applyDetectedOpeningOnUi(gen, episodeKey, refinedFrom, refinedTo, false);
        }
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
     * Формирует упорядоченный список URL-кандидатов для аудио-анализа:
     * сначала самые лёгкие качества (360p) для минимального трафика и максимальной скорости,
     * а в конце гарантированно добавляется текущий рабочий URL плеера (fallback на случай битого зеркала 360p).
     */
    @NonNull
    private List<String> resolveAudioSourceCandidates() {
        Set<String> ordered = new LinkedHashSet<>();
        String currentUrl = host.getCurrentVideoUrl();

        if (host.isPlayingDownloadedVideo() || AudioPcmExtractor.isLocalSource(currentUrl)) {
            if (currentUrl != null && !currentUrl.trim().isEmpty()) {
                ordered.add(currentUrl.trim());
            }
            return new ArrayList<>(ordered);
        }

        EpisodeResponse.PlayerData pd = host.getCurrentPlayerData();
        if (pd != null) {
            if ("animelib".equalsIgnoreCase(pd.getPlayer())
                    && pd.getVideo() != null
                    && pd.getVideo().getQuality() != null
                    && !pd.getVideo().getQuality().isEmpty()) {
                List<EpisodeResponse.QualityData> sorted = new ArrayList<>();
                for (EpisodeResponse.QualityData q : pd.getVideo().getQuality()) {
                    if (q != null && q.getHref() != null && !q.getHref().trim().isEmpty()) {
                        sorted.add(q);
                    }
                }
                sorted.sort(Comparator.comparingInt(q -> q.getQuality() > 0 ? q.getQuality() : Integer.MAX_VALUE));
                String domain = host.getCurrentVideoDomain();
                for (EpisodeResponse.QualityData q : sorted) {
                    String abs = VideoUrlHelper.toAbsoluteVideoUrl(q.getHref(), domain);
                    if (abs != null && !abs.trim().isEmpty()) {
                        ordered.add(abs.trim());
                        if (ordered.size() >= 2) break; // Берём 2 самых лёгких качества + currentUrl
                    }
                }
            } else if ("kodik".equalsIgnoreCase(pd.getPlayer())) {
                KodikResponse kodikResponse = host.getCurrentKodikResponse();
                if (kodikResponse != null) {
                    List<String> lowCandidates = KodikLinksExtractor.collectCandidateUrls(kodikResponse, "360p", false);
                    if (lowCandidates != null) {
                        for (String url : lowCandidates) {
                            if (url != null && !url.trim().isEmpty()) {
                                ordered.add(url.trim());
                                if (ordered.size() >= 3) break;
                            }
                        }
                    }
                }
            }
        }

        if (currentUrl != null && !currentUrl.trim().isEmpty()) {
            ordered.add(currentUrl.trim());
        }
        return new ArrayList<>(ordered);
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
        activeLearnOps.set(0);
        activeScanOps.set(0);
        lastReportedRunning.set(false);
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
