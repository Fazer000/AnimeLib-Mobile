package com.example.animelib.controllers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * Контроллер автоматического запоминания и быстрого поиска опенингов по аудиодорожке
 * для эпизодов, в которых таймкод опенинга отсутствует в API.
 *
 * Устойчивость к плохому интернету (v3):
 * - Жёсткие таймауты HTTP-вызовов (callTimeout = 10с, connect = 5с, read = 7с) и мгновенный обрыв
 *   сокетов через CancelSignal при отмене или истечении общего дедлайна (watchdog).
 * - Нет накопления дублей при частых подгрузках / переподключениях плеера на той же серии.
 * - Отложенный старт сетевого скана (~900мс) с мгновенным кэш-хитом из БД, чтобы не душить старт видео.
 * - Ранний выход при 2 подряд сетевых ошибках зондов и информативные тосты об ошибках анализа и поиска.
 */
public class OpeningAudioDetectorController {

    private static final String TAG = "OpeningAudioDetector";
    private static final String PREFS_NAME = "opening_audio_detector_prefs";
    private static final String KEY_ENGINE_VERSION = "engine_version";
    private static final String KEY_OPENING_DETECTION_ENABLED = "opening_detection_enabled";
    private static final int CURRENT_ENGINE_VERSION = 2;

    private static final int MAX_OPENING_START_LEARN_SEC = 480; // Первые 8 минут
    private static final int MIN_SKIP_DURATION_SEC = 25;
    private static final int MAX_SKIP_DURATION_SEC = 150;
    private static final int REFERENCE_PADDING_SEC = 10;
    private static final int MAX_SCAN_SEC = 460; // Ищем опенинг в первых ~7.5 минутах
    private static final int MAX_TEMPLATES_PER_ANIME = 3;
    private static final double PROBE_DURATION_SEC = 18.0;

    // Жёсткие лимиты для защиты от бесконечной загрузки при плохом интернете
    private static final long LEARN_HARD_TIMEOUT_MS = 28_000L;
    private static final long SCAN_HARD_TIMEOUT_MS = 25_000L;
    private static final long REFINE_HARD_TIMEOUT_MS = 10_000L;
    private static final long ATTACH_DEBOUNCE_DELAY_MS = 900L;
    private static final long ERROR_TOAST_COOLDOWN_MS = 5_000L;
    private static final long FAILED_SCAN_COOLDOWN_MS = 25_000L;
    private static final int MAX_CONSECUTIVE_PROBE_FAILURES = 2;
    private static final int MAX_SOURCE_CANDIDATES = 2;

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
    private final AtomicInteger learnGeneration = new AtomicInteger(0);
    private final AtomicBoolean lastReportedRunning = new AtomicBoolean(false);
    private final AtomicBoolean cacheMigrationChecked = new AtomicBoolean(false);

    @Nullable private volatile AudioPcmExtractor.CancelSignal activeScanSignal = null;
    @Nullable private volatile AudioPcmExtractor.CancelSignal activeLearnSignal = null;
    @Nullable private volatile String activeScanEpisodeKey = null;

    private volatile boolean isScanIndicatorActive = false;
    private volatile boolean isLearnIndicatorActive = false;
    private volatile boolean isCleanedUp = false;
    private volatile long lastLearnTriggerMs = 0L;
    private volatile long lastErrorToastElapsedMs = 0L;
    private volatile long lastFailedScanElapsedMs = 0L;
    @Nullable private volatile String lastFailedScanKey = null;
    @Nullable private volatile String lastScheduledScanKey = null;

    @Nullable private Runnable pendingScanRunnable = null;
    @Nullable private Runnable scanWatchdogRunnable = null;
    @Nullable private Runnable learnWatchdogRunnable = null;

    public OpeningAudioDetectorController(@NonNull Context context, @NonNull Host host) {
        this.appContext = context.getApplicationContext();
        this.host = host;
        this.dao = AppDatabase.getDatabase(this.appContext).openingFingerprintDao();
    }

    public static boolean isOpeningDetectionEnabled(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_OPENING_DETECTION_ENABLED, true);
    }

    public static void setOpeningDetectionEnabled(@NonNull Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_OPENING_DETECTION_ENABLED, enabled)
                .apply();
    }

    public boolean isDetectionEnabled() {
        return isOpeningDetectionEnabled(appContext);
    }

    public void setDetectionEnabled(boolean enabled) {
        setOpeningDetectionEnabled(appContext, enabled);
        if (!enabled) {
            cancelActiveScan();
            cancelActiveLearn();
            lastScheduledScanKey = null;
            notifyAnalysisStateChanged();
            host.safeRunOnUiThread(() -> {
                if (!host.isActivityFinishingOrDestroyed()) {
                    TimecodeManager tm = host.getTimecodeManager();
                    if (tm != null) {
                        tm.removeDetectedOpeningTimecode();
                    }
                }
            });
        } else {
            lastScheduledScanKey = null;
            lastFailedScanKey = null;
            scheduleScanForActiveEpisode(0L);
        }
    }

    public boolean isAnalysisRunning() {
        if (isCleanedUp || !isDetectionEnabled()) return false;
        boolean scanAlive = isScanIndicatorActive && activeScanSignal != null && !activeScanSignal.isCancelled();
        boolean learnAlive = isLearnIndicatorActive && activeLearnSignal != null && !activeLearnSignal.isCancelled();
        return scanAlive || learnAlive;
    }

    private void notifyAnalysisStateChanged() {
        boolean running = isAnalysisRunning();
        if (lastReportedRunning.getAndSet(running) != running) {
            host.safeRunOnUiThread(() -> {
                if (!host.isActivityFinishingOrDestroyed()) {
                    host.onAnalysisRunningChanged(running);
                }
            });
        }
    }

    private void showErrorToast(@NonNull String message) {
        if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastErrorToastElapsedMs < ERROR_TOAST_COOLDOWN_MS) {
            return;
        }
        lastErrorToastElapsedMs = now;
        host.safeRunOnUiThread(() -> {
            if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;
            Context ctx = host.getContext();
            if (ctx != null) {
                CustomToast.showWarning(ctx, message);
            }
        });
    }

    @NonNull
    private OkHttpClient createAnalysisHttpClient(@Nullable OkHttpClient baseClient) {
        OkHttpClient source = baseClient != null ? baseClient : new OkHttpClient();
        return source.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(7, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
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
        if (isCleanedUp || !isDetectionEnabled() || host.isActivityFinishingOrDestroyed()) return;

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
            showErrorToast("Не удалось проанализировать опенинг: источник видео недоступен");
            return;
        }

        // Если в текущей серии нет официального опенинга из API, сразу подсветим пропущенный участок на таймбаре
        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && !tm.hasOfficialOpeningTimecode()) {
            tm.addDetectedOpeningTimecode(fromSec, toSec);
        }

        // Мгновенно отменяем предыдущую задачу обучения вместе со всеми её сокетами
        cancelActiveLearn();

        final int myLearnGen = learnGeneration.incrementAndGet();
        final AudioPcmExtractor.CancelSignal cancelSignal = new AudioPcmExtractor.CancelSignal(LEARN_HARD_TIMEOUT_MS);
        activeLearnSignal = cancelSignal;
        isLearnIndicatorActive = true;
        notifyAnalysisStateChanged();

        // Жёсткий watchdog: гарантированно прервёт зависшее обучение на плохом интернете
        Runnable watchdog = () -> {
            if (myLearnGen == learnGeneration.get() && !cancelSignal.isCancelled()) {
                Log.w(TAG, "Opening learn watchdog triggered after " + LEARN_HARD_TIMEOUT_MS + "ms");
                cancelSignal.markTimedOut();
                isLearnIndicatorActive = false;
                notifyAnalysisStateChanged();
                showErrorToast("Не удалось запомнить опенинг: превышено время ожидания сети");
            }
        };
        learnWatchdogRunnable = watchdog;
        mainHandler.postDelayed(watchdog, LEARN_HARD_TIMEOUT_MS);

        final double extractStartSec = Math.max(0.0, fromSec - REFERENCE_PADDING_SEC);
        final double extractEndSec = Math.min(durationSec, toSec + REFERENCE_PADDING_SEC);
        final int coreStartFrame = (int) Math.round((fromSec - extractStartSec) * AudioFingerprintEngine.FRAMES_PER_SECOND);
        final int coreEndFrame = (int) Math.round((toSec - extractStartSec) * AudioFingerprintEngine.FRAMES_PER_SECOND);

        Log.d(TAG, "Learning opening fingerprint for anime=" + animeId
                + " ep=" + sourceEpisodeId + " skip=[" + fromSec + "s.." + toSec + "s]"
                + " extract=[" + extractStartSec + "s.." + extractEndSec + "s]"
                + " candidates=" + sourceCandidates.size());

        learnExecutor.execute(() -> {
            try {
                if (isCleanedUp || myLearnGen != learnGeneration.get() || cancelSignal.isCancelled()) return;
                ensureNegativeCacheMigrated();

                File cacheDir = appContext.getCacheDir();
                AudioPcmExtractor.ExtractedFingerprint extracted = null;

                // Пробуем не более MAX_SOURCE_CANDIDATES источников (лёгкий 360p -> текущий поток)
                for (String candidateUrl : sourceCandidates) {
                    if (isCleanedUp || myLearnGen != learnGeneration.get() || cancelSignal.isCancelled()) return;
                    OkHttpClient baseClient = host.getStreamingOkHttpClientForUrl(candidateUrl);
                    if (baseClient == null && !AudioPcmExtractor.isLocalSource(candidateUrl)) {
                        continue;
                    }
                    OkHttpClient analysisClient = createAnalysisHttpClient(baseClient);
                    AudioPcmExtractor.ExtractedFingerprint fp = AudioPcmExtractor.extractRangeFingerprint(
                            candidateUrl, extractStartSec, extractEndSec, analysisClient, cacheDir, cancelSignal);
                    if (fp != null && fp.frameCount >= 48) {
                        extracted = fp;
                        break;
                    }
                    Log.w(TAG, "Learn candidate failed or too short (frames="
                            + (fp != null ? fp.frameCount : 0) + "), trying fallback: " + candidateUrl);
                }

                if (isCleanedUp || myLearnGen != learnGeneration.get() || cancelSignal.isUserOrSystemCancelled()) {
                    return;
                }

                if (cancelSignal.isTimedOut()) {
                    showErrorToast("Не удалось запомнить опенинг: превышено время ожидания сети");
                    return;
                }

                if (extracted == null || extracted.frameCount < 48) {
                    Log.w(TAG, "Failed to extract opening audio fingerprint from all candidates");
                    AudioPcmExtractor.FailureReason reason = cancelSignal.getLastFailureReason();
                    if (reason == AudioPcmExtractor.FailureReason.TIMEOUT) {
                        showErrorToast("Не удалось запомнить опенинг: превышено время ожидания сети");
                    } else if (reason == AudioPcmExtractor.FailureReason.NETWORK) {
                        showErrorToast("Не удалось проанализировать опенинг: ошибка сети");
                    } else {
                        showErrorToast("Не удалось проанализировать аудиодорожку опенинга");
                    }
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

                if (sourceEpisodeId > 0) {
                    saveDetectedOpeningCache(
                            DetectedOpeningEntity.buildCacheKey(animeId, sourceEpisodeId),
                            animeId, sourceEpisodeId, fromSec, toSec, 1.0f, savedId);
                }

                Log.d(TAG, "Saved opening fingerprint id=" + savedId + " frames=" + extracted.frameCount
                        + " for anime=" + animeId);

                host.safeRunOnUiThread(() -> {
                    if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;
                    if (host.getCurrentEpisodeId() != sourceEpisodeId) {
                        lastScheduledScanKey = null;
                        scheduleScanForActiveEpisode(0L);
                    }
                });
            } catch (Exception e) {
                if (!cancelSignal.isUserOrSystemCancelled()) {
                    Log.e(TAG, "Error saving opening fingerprint", e);
                    showErrorToast("Ошибка анализа опенинга");
                }
            } finally {
                mainHandler.removeCallbacks(watchdog);
                if (myLearnGen == learnGeneration.get()) {
                    isLearnIndicatorActive = false;
                    activeLearnSignal = null;
                    notifyAnalysisStateChanged();
                }
            }
        });
    }

    /**
     * Вызывается при прикреплении плеера к эпизоду / смене источника / бесшовном переключении.
     * Не перезапускает скан впустую при частых ребуферизациях или смене качества на той же серии.
     */
    public void onEpisodeAttached() {
        if (isCleanedUp || host.isActivityFinishingOrDestroyed()) return;

        if (!isDetectionEnabled()) {
            cancelActiveScan();
            lastScheduledScanKey = null;
            return;
        }

        final String animeId = normalizeAnimeId(host.getCurrentAnimeId());
        final int episodeId = host.getCurrentEpisodeId();
        final String newKey = (animeId != null && episodeId > 0) ? (animeId + ":" + episodeId) : null;

        // Если это та же самая серия (например, реконнект или автопонижение качества при плохом инете):
        if (newKey != null && newKey.equals(activeScanEpisodeKey)) {
            TimecodeManager tm = host.getTimecodeManager();
            if (tm != null && (tm.hasOfficialOpeningTimecode() || tm.hasDetectedOpeningTimecode())) {
                return; // Опенинг уже есть на таймбаре
            }
            AudioPcmExtractor.CancelSignal currentSignal = activeScanSignal;
            if (currentSignal != null && !currentSignal.isCancelled() && isScanIndicatorActive) {
                return; // Живой скан для этой же серии уже идёт — не обрываем его по кругу!
            }
        }

        // После сетевой ошибки на этой же серии выдерживаем паузу, чтобы частые подгрузки не крутили скан бесконечно
        if (newKey != null && newKey.equals(lastFailedScanKey)
                && (SystemClock.elapsedRealtime() - lastFailedScanElapsedMs) < FAILED_SCAN_COOLDOWN_MS) {
            return;
        }

        cancelActiveScan();
        lastScheduledScanKey = null;

        // Небольшая задержка (900мс) защищает от серии быстрых реконнектов и не душит буфер старта видео
        scheduleScanForActiveEpisode(ATTACH_DEBOUNCE_DELAY_MS);
    }

    /**
     * Страховочный вызов после рендера первого кадра: если на момент onEpisodeAttached URL ещё не был готов
     * или скан ещё ждёт debounce, запускает поиск немедленно. Если поиск уже идёт — ничего не дублирует.
     */
    public void onEpisodeFirstFrameRendered() {
        if (isCleanedUp || !isDetectionEnabled() || host.isActivityFinishingOrDestroyed()) return;

        final String animeId = normalizeAnimeId(host.getCurrentAnimeId());
        final int episodeId = host.getCurrentEpisodeId();
        if (animeId == null || episodeId <= 0) return;

        final String key = animeId + ":" + episodeId;
        if (pendingScanRunnable != null && key.equals(lastScheduledScanKey)) {
            // Первый кадр уже отрисован — можно не ждать остаток debounce, запускаем сразу!
            Runnable r = pendingScanRunnable;
            pendingScanRunnable = null;
            mainHandler.removeCallbacks(r);
            r.run();
            return;
        }

        scheduleScanForActiveEpisode(0L);
    }

    private void scheduleScanForActiveEpisode(long delayMs) {
        if (isCleanedUp || !isDetectionEnabled() || host.isActivityFinishingOrDestroyed()) return;

        final String animeId = normalizeAnimeId(host.getCurrentAnimeId());
        final int episodeId = host.getCurrentEpisodeId();
        if (animeId == null || episodeId <= 0) return;

        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && (tm.hasOfficialOpeningTimecode() || tm.hasDetectedOpeningTimecode())) {
            return; // Опенинг уже известен для этой серии
        }

        final List<String> candidates = resolveAudioSourceCandidates();
        if (candidates.isEmpty()) {
            return; // URL ещё не разрешён — дождёмся onEpisodeFirstFrameRendered
        }

        final String scanKey = animeId + ":" + episodeId;
        if (scanKey.equals(lastScheduledScanKey)) {
            return; // Для этого эпизода скан уже запланирован, идёт или завершён
        }
        lastScheduledScanKey = scanKey;
        activeScanEpisodeKey = scanKey;

        if (pendingScanRunnable != null) {
            mainHandler.removeCallbacks(pendingScanRunnable);
            pendingScanRunnable = null;
        }

        final int expectedGen = scanGeneration.incrementAndGet();
        AudioPcmExtractor.CancelSignal prevSignal = activeScanSignal;
        if (prevSignal != null) {
            prevSignal.cancel();
        }
        final AudioPcmExtractor.CancelSignal cancelSignal = new AudioPcmExtractor.CancelSignal(SCAN_HARD_TIMEOUT_MS);
        activeScanSignal = cancelSignal;

        if (delayMs <= 0L) {
            startFastProbeScan(animeId, episodeId, scanKey, candidates, expectedGen, cancelSignal);
        } else {
            pendingScanRunnable = () -> {
                pendingScanRunnable = null;
                startFastProbeScan(animeId, episodeId, scanKey, candidates, expectedGen, cancelSignal);
            };
            mainHandler.postDelayed(pendingScanRunnable, delayMs);
        }
    }

    private void startFastProbeScan(@NonNull String animeId,
                                    int episodeId,
                                    @NonNull String episodeKey,
                                    @NonNull List<String> sourceCandidates,
                                    int gen,
                                    @NonNull AudioPcmExtractor.CancelSignal cancelSignal) {
        if (isCleanedUp || gen != scanGeneration.get() || cancelSignal.isCancelled() || host.isActivityFinishingOrDestroyed()) {
            return;
        }

        TimecodeManager tm = host.getTimecodeManager();
        if (tm != null && (tm.hasOfficialOpeningTimecode() || tm.hasDetectedOpeningTimecode())) {
            return;
        }

        final long durationMs = host.getCurrentDurationMs();
        final int initialEpisodeDurationSec = durationMs > 0 ? (int) (durationMs / 1000L) : -1;

        scanExecutor.execute(() -> {
            if (isCleanedUp || gen != scanGeneration.get() || cancelSignal.isCancelled()) return;
            ensureNegativeCacheMigrated();

            String cacheKey = DetectedOpeningEntity.buildCacheKey(animeId, episodeId);

            // 1. Мгновенная проверка положительного кэша в БД (0 байт сети, индикатор не мигает)
            DetectedOpeningEntity cached = null;
            try {
                cached = dao.getDetectedOpening(cacheKey);
                if (cached != null && cached.isValidDetection()) {
                    applyDetectedOpeningOnUi(gen, cached.getFromSec(), cached.getToSec());
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

            // Создаём свежий CancelSignal прямо в момент старта сетевого сканирования,
            // чтобы отсчёт 25с не тратился на очередь executor или чтение БД
            final AudioPcmExtractor.CancelSignal netSignal = new AudioPcmExtractor.CancelSignal(SCAN_HARD_TIMEOUT_MS);
            if (cancelSignal.isCancelled() || gen != scanGeneration.get()) {
                return;
            }
            activeScanSignal = netSignal;
            if (gen != scanGeneration.get() || isCleanedUp || !isDetectionEnabled()) {
                netSignal.cancel();
                return;
            }

            // Включаем индикатор поиска рядом с таймкодом + ставим жёсткий watchdog
            final AtomicBoolean indicatorReleased = new AtomicBoolean(false);
            isScanIndicatorActive = true;
            notifyAnalysisStateChanged();

            Runnable watchdog = () -> {
                if (gen == scanGeneration.get() && !netSignal.isCancelled() && !indicatorReleased.get()) {
                    Log.w(TAG, "Opening scan watchdog triggered after " + SCAN_HARD_TIMEOUT_MS + "ms for ep=" + episodeId);
                    lastFailedScanKey = episodeKey;
                    lastFailedScanElapsedMs = SystemClock.elapsedRealtime();
                    netSignal.markTimedOut();
                    if (indicatorReleased.compareAndSet(false, true)) {
                        isScanIndicatorActive = false;
                        notifyAnalysisStateChanged();
                    }
                    showErrorToast("Поиск опенинга остановлен: слишком медленное соединение");
                }
            };
            clearScanWatchdog();
            scanWatchdogRunnable = watchdog;
            mainHandler.postDelayed(watchdog, SCAN_HARD_TIMEOUT_MS);

            Runnable releaseIndicator = () -> {
                mainHandler.removeCallbacks(watchdog);
                if (indicatorReleased.compareAndSet(false, true)) {
                    if (gen == scanGeneration.get()) {
                        isScanIndicatorActive = false;
                        notifyAnalysisStateChanged();
                    }
                }
            };

            long scanStartMs = SystemClock.elapsedRealtime();
            File cacheDir = appContext.getCacheDir();
            AudioPcmExtractor.AudioProbeSession session = null;

            try {
                // Открываем переиспользуемую сессию (макс. 2 кандидата: лёгкий 360p + текущий рабочий поток)
                AudioPcmExtractor.ExtractedFingerprint probe0Window = null;
                for (String candidateUrl : sourceCandidates) {
                    if (isCleanedUp || gen != scanGeneration.get() || netSignal.isCancelled()) return;
                    OkHttpClient baseClient = host.getStreamingOkHttpClientForUrl(candidateUrl);
                    if (baseClient == null && !AudioPcmExtractor.isLocalSource(candidateUrl)) {
                        continue;
                    }
                    OkHttpClient analysisClient = createAnalysisHttpClient(baseClient);
                    AudioPcmExtractor.AudioProbeSession opened =
                            AudioPcmExtractor.AudioProbeSession.open(candidateUrl, analysisClient, cacheDir, netSignal);
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

                if (isCleanedUp || gen != scanGeneration.get() || netSignal.isUserOrSystemCancelled()) {
                    return;
                }

                if (netSignal.isTimedOut()) {
                    lastFailedScanKey = episodeKey;
                    lastFailedScanElapsedMs = SystemClock.elapsedRealtime();
                    releaseIndicator.run();
                    showErrorToast("Поиск опенинга остановлен: слишком медленное соединение");
                    return;
                }

                if (session == null) {
                    Log.w(TAG, "Could not open any working audio probe session for ep=" + episodeId);
                    lastFailedScanKey = episodeKey;
                    lastFailedScanElapsedMs = SystemClock.elapsedRealtime();
                    releaseIndicator.run();
                    reportScanFailureToast(netSignal.getLastFailureReason());
                    return;
                }

                int sessionDurSec = (int) Math.round(session.getKnownDurationSec());
                int episodeDurationSec = initialEpisodeDurationSec > 0
                        ? initialEpisodeDurationSec
                        : (sessionDurSec > 0 ? sessionDurSec : -1);
                int maxScanSec = episodeDurationSec > 0
                        ? Math.min(MAX_SCAN_SEC, Math.max(90, episodeDurationSec))
                        : MAX_SCAN_SEC;

                int probeStepSec = Math.max(24, Math.min(44, minTemplateSkipSec - 26));

                AudioFingerprintEngine.MatchResult probeHit = null;
                AudioFingerprintEngine.Template matchedTemplate = null;
                int decodedProbesCount = 0;
                int consecutiveProbeFailures = 0;
                boolean abortedByNetworkError = false;

                for (double probeStart = 0.0; probeStart < maxScanSec - 10.0; probeStart += probeStepSec) {
                    if (isCleanedUp || gen != scanGeneration.get() || netSignal.isCancelled()) {
                        break;
                    }

                    double probeEnd = Math.min(maxScanSec, probeStart + PROBE_DURATION_SEC);
                    if (probeEnd - probeStart < 12.0) {
                        break;
                    }

                    AudioPcmExtractor.ExtractedFingerprint win = (probeStart == 0.0 && probe0Window != null)
                            ? probe0Window
                            : session.extractWindow(probeStart, probeEnd);
                    if (isCleanedUp || gen != scanGeneration.get() || netSignal.isCancelled()) {
                        break;
                    }
                    if (win == null || win.frameCount < 44) {
                        consecutiveProbeFailures++;
                        if (consecutiveProbeFailures >= MAX_CONSECUTIVE_PROBE_FAILURES) {
                            Log.w(TAG, "Aborting opening scan for ep=" + episodeId
                                    + " after " + consecutiveProbeFailures + " consecutive probe failures");
                            abortedByNetworkError = true;
                            break;
                        }
                        continue;
                    }

                    consecutiveProbeFailures = 0;
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

                if (isCleanedUp || gen != scanGeneration.get() || netSignal.isUserOrSystemCancelled()) {
                    return;
                }

                if (netSignal.isTimedOut()) {
                    lastFailedScanKey = episodeKey;
                    lastFailedScanElapsedMs = SystemClock.elapsedRealtime();
                    releaseIndicator.run();
                    showErrorToast("Поиск опенинга остановлен: слишком медленное соединение");
                    return;
                }

                if (probeHit != null && matchedTemplate != null) {
                    int estFrom = Math.max(0, probeHit.fromSec);
                    int estTo = clampToEpisodeDuration(probeHit.toSec, episodeDurationSec, estFrom);

                    Log.d(TAG, "Fast probe HIT for ep=" + episodeId
                            + " in " + (SystemClock.elapsedRealtime() - scanStartMs) + "ms"
                            + " (probes=" + decodedProbesCount + ")"
                            + ": [" + estFrom + "s .. " + estTo + "s]"
                            + " conf=" + String.format(Locale.US, "%.2f", probeHit.confidence)
                            + " z=" + String.format(Locale.US, "%.1f", probeHit.zScore));

                    // Сразу скрываем индикатор, подсвечиваем опенинг на таймбаре и пишем в кэш
                    releaseIndicator.run();
                    applyDetectedOpeningOnUi(gen, estFrom, estTo);
                    saveDetectedOpeningCache(cacheKey, animeId, episodeId,
                            estFrom, estTo, probeHit.confidence, matchedTemplate.id);

                    // Этап 2 (тихо в фоне с коротким лимитом 10с): уточняем точную левую и правую границу
                    refineEdgesSilently(session, matchedTemplate, probeHit, estFrom, estTo,
                            episodeDurationSec, probe0Window, gen, cacheKey, animeId, episodeId, netSignal);
                } else if (abortedByNetworkError) {
                    lastFailedScanKey = episodeKey;
                    lastFailedScanElapsedMs = SystemClock.elapsedRealtime();
                    releaseIndicator.run();
                    reportScanFailureToast(netSignal.getLastFailureReason());
                } else {
                    // Записываем отрицательный кэш ТОЛЬКО если реально удалось декодировать аудио (>= 2 зондов)
                    if (decodedProbesCount >= 2) {
                        saveDetectedOpeningCache(cacheKey, animeId, episodeId, -1, -1, 0f, latestTemplateId);
                    }
                    Log.d(TAG, "No opening match found for ep=" + episodeId
                            + " (probes=" + decodedProbesCount + ", " + (SystemClock.elapsedRealtime() - scanStartMs) + "ms)");
                }
            } catch (Exception e) {
                if (!netSignal.isUserOrSystemCancelled() && gen == scanGeneration.get()) {
                    Log.w(TAG, "Fast opening scan error for ep=" + episodeId + ": " + e.getMessage());
                    lastFailedScanKey = episodeKey;
                    lastFailedScanElapsedMs = SystemClock.elapsedRealtime();
                    releaseIndicator.run();
                    if (netSignal.isTimedOut()) {
                        showErrorToast("Поиск опенинга остановлен: слишком медленное соединение");
                    } else {
                        showErrorToast("Ошибка при поиске опенинга");
                    }
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

    private void reportScanFailureToast(@NonNull AudioPcmExtractor.FailureReason reason) {
        if (reason == AudioPcmExtractor.FailureReason.TIMEOUT) {
            showErrorToast("Поиск опенинга остановлен: слишком медленное соединение");
        } else if (reason == AudioPcmExtractor.FailureReason.NETWORK) {
            showErrorToast("Ошибка поиска опенинга: проблемы с соединением");
        } else {
            showErrorToast("Не удалось выполнить поиск опенинга");
        }
    }

    private void refineEdgesSilently(@NonNull AudioPcmExtractor.AudioProbeSession session,
                                     @NonNull AudioFingerprintEngine.Template template,
                                     @NonNull AudioFingerprintEngine.MatchResult probeHit,
                                     int estFromSec,
                                     int estToSec,
                                     int episodeDurationSec,
                                     @Nullable AudioPcmExtractor.ExtractedFingerprint probe0Window,
                                     int gen,
                                     @NonNull String cacheKey,
                                     @NonNull String animeId,
                                     int episodeId,
                                     @NonNull AudioPcmExtractor.CancelSignal parentSignal) {
        if (isCleanedUp || gen != scanGeneration.get() || parentSignal.isCancelled()) return;
        // Если сеть уже еле дышит (осталось мало времени от общего бюджета) — пропускаем необязательное уточнение
        if (parentSignal.remainingMs() < 4_000L) return;

        long refineStartMs = SystemClock.elapsedRealtime();

        AudioPcmExtractor.ExtractedFingerprint startWin;
        if (estFromSec <= 3 && probe0Window != null && probe0Window.frameCount >= 40) {
            startWin = probe0Window;
        } else {
            double sFrom = Math.max(0.0, estFromSec - 12.0);
            double sTo = estFromSec + 12.0;
            startWin = session.extractWindow(sFrom, sTo);
        }

        if (isCleanedUp || gen != scanGeneration.get() || parentSignal.isCancelled()) return;
        if (SystemClock.elapsedRealtime() - refineStartMs > REFINE_HARD_TIMEOUT_MS) return;

        double eFrom = Math.max(0.0, estToSec - 14.0);
        double eTo = episodeDurationSec > 0 ? Math.min(episodeDurationSec, estToSec + 14.0) : (estToSec + 14.0);
        AudioPcmExtractor.ExtractedFingerprint endWin = (eTo - eFrom >= 8.0)
                ? session.extractWindow(eFrom, eTo) : null;

        if (isCleanedUp || gen != scanGeneration.get() || parentSignal.isCancelled()) return;

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
            applyDetectedOpeningOnUi(gen, refinedFrom, refinedTo);
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
                                          int fromSec,
                                          int toSec) {
        host.safeRunOnUiThread(() -> {
            if (isCleanedUp || !isDetectionEnabled() || expectedGen != scanGeneration.get() || host.isActivityFinishingOrDestroyed()) {
                return;
            }
            TimecodeManager tm = host.getTimecodeManager();
            if (tm != null) {
                tm.addDetectedOpeningTimecode(fromSec, toSec);
            }
        });
    }

    /**
     * Формирует компактный список (до 2 URL-кандидатов):
     * 1) самый лёгкий поток 360p для минимального трафика,
     * 2) текущий рабочий URL плеера (гарантированный fallback).
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
                        break; // Берём 1 самый лёгкий поток + fallback на currentUrl
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
                                break; // 1 самый лёгкий 360p-кандидат + fallback на currentUrl
                            }
                        }
                    }
                }
            }
        }

        if (currentUrl != null && !currentUrl.trim().isEmpty() && ordered.size() < MAX_SOURCE_CANDIDATES) {
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

    private void clearScanWatchdog() {
        Runnable r = scanWatchdogRunnable;
        if (r != null) {
            mainHandler.removeCallbacks(r);
            scanWatchdogRunnable = null;
        }
    }

    private void clearLearnWatchdog() {
        Runnable r = learnWatchdogRunnable;
        if (r != null) {
            mainHandler.removeCallbacks(r);
            learnWatchdogRunnable = null;
        }
    }

    public void cancelActiveScan() {
        scanGeneration.incrementAndGet();
        clearScanWatchdog();
        if (pendingScanRunnable != null) {
            mainHandler.removeCallbacks(pendingScanRunnable);
            pendingScanRunnable = null;
        }
        AudioPcmExtractor.CancelSignal sig = activeScanSignal;
        activeScanSignal = null;
        if (sig != null) {
            sig.cancel();
        }
        activeScanEpisodeKey = null;
        isScanIndicatorActive = false;
        notifyAnalysisStateChanged();
    }

    private void cancelActiveLearn() {
        learnGeneration.incrementAndGet();
        clearLearnWatchdog();
        AudioPcmExtractor.CancelSignal sig = activeLearnSignal;
        activeLearnSignal = null;
        if (sig != null) {
            sig.cancel();
        }
        isLearnIndicatorActive = false;
        notifyAnalysisStateChanged();
    }

    public void cleanup() {
        isCleanedUp = true;
        cancelActiveScan();
        cancelActiveLearn();
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
