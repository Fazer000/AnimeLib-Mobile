package com.example.animelib.data;

import android.content.Context;
import android.util.Log;

import com.example.animelib.data.entity.DownloadedAnimeEntity;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.PlayerPreferences;
import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.util.AutoQualityHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Менеджер для всех операций с базой данных Room.
 * Обеспечивает in-memory кэширование настроек, токена, предпочтений плеера и метаданных загрузок,
 * а также безопасную запись через общий фоновый worker-поток.
 */
public class DatabaseManager {
    private static final String TAG = "DatabaseManager";
    private static final Object tokenLock = new Object();
    private static volatile TokenEntity cachedToken = null;
    private static volatile boolean tokenLoaded = false;

    private static volatile String cachedSiteUrl = null;

    private static final Object appSettingsLock = new Object();
    private static volatile AppSettings cachedAppSettings = null;
    private static volatile boolean appSettingsLoaded = false;

    private static final Object playerPrefsLock = new Object();
    private static volatile PlayerPreferences cachedPlayerPreferences = null;
    private static volatile boolean playerPreferencesLoaded = false;

    private static final Object downloadsCacheLock = new Object();
    private static volatile boolean downloadsCacheLoaded = false;
    private static final Map<String, DownloadedAnimeEntity> cachedDownloadedAnimeById = new LinkedHashMap<>();
    private static final Map<String, DownloadedEpisodeEntity> cachedEpisodesById = new LinkedHashMap<>();
    private static final Map<String, DownloadedEpisodeEntity> cachedEpisodesByPath = new HashMap<>();

    @FunctionalInterface
    private interface AppSettingsMutator {
        void mutate(AppSettings settings);
    }

    @FunctionalInterface
    private interface PlayerPrefsMutator {
        void mutate(PlayerPreferences preferences);
    }

    private static final ExecutorService SHARED_DB_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "animelib-db-worker");
        t.setDaemon(true);
        return t;
    });

    private final AppDatabase db;
    private final ExecutorService executor;
    private final Context context;

    private static volatile DatabaseManager instance;

    public static DatabaseManager getInstance(Context context) {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public DatabaseManager(Context context) {
        this.context = context.getApplicationContext();
        this.db = AppDatabase.getDatabase(this.context);
        this.executor = SHARED_DB_EXECUTOR;
    }

    // ========== AppSettings внутренний кэш и помощники ==========

    private static String normalizeSiteUrlValue(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return null;
        }
        String url = rawUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    private static AppSettings copyAppSettings(AppSettings src) {
        AppSettings copy = new AppSettings();
        if (src == null) {
            copy.setId(1);
            return copy;
        }
        copy.setId(src.getId() > 0 ? src.getId() : 1);
        copy.setSiteUrl(src.getSiteUrl());
        copy.setEnable4K(src.isEnable4K());
        copy.setEnableAmbientLight(src.isEnableAmbientLight());
        copy.setEnableSurroundSound(src.isEnableSurroundSound());
        copy.setSurroundMode(src.getSurroundMode());
        copy.setSurroundSpatialWidth(src.getSurroundSpatialWidth());
        copy.setSurroundDialogueBoost(src.getSurroundDialogueBoost());
        copy.setSurroundBassBoost(src.getSurroundBassBoost());
        copy.setSurroundMidBoost(src.getSurroundMidBoost());
        copy.setSurroundTrebleBoost(src.getSurroundTrebleBoost());
        copy.setAutoPlay(src.isAutoPlay());
        copy.setLongSkipDuration(src.getLongSkipDuration());
        copy.setThemeMode(src.getThemeMode());
        copy.setSubtitlesEnabled(src.isSubtitlesEnabled());
        copy.setSubtitleFormat(src.getSubtitleFormat());
        copy.setSubtitleTextSize(src.getSubtitleTextSize());
        copy.setSubtitleTextColor(src.getSubtitleTextColor());
        copy.setSubtitleBackgroundColor(src.getSubtitleBackgroundColor());
        copy.setSubtitleEdgeType(src.getSubtitleEdgeType());
        copy.setSubtitleEdgeColor(src.getSubtitleEdgeColor());
        copy.setFilterBrightness(src.getFilterBrightness());
        copy.setFilterContrast(src.getFilterContrast());
        copy.setFilterSaturation(src.getFilterSaturation());
        copy.setFilterGamma(src.getFilterGamma());
        copy.setFilterHue(src.getFilterHue());
        copy.setDisableCommentsInPlayer(src.isDisableCommentsInPlayer());
        copy.setCommentRepliesLimit(src.getCommentRepliesLimit());
        return copy;
    }

    private static PlayerPreferences copyPlayerPreferences(PlayerPreferences src) {
        if (src == null) return null;
        PlayerPreferences copy = new PlayerPreferences();
        copy.setId(src.getId() > 0 ? src.getId() : 1);
        copy.setPlayer(src.getPlayer());
        copy.setTeamId(src.getTeamId());
        copy.setPreferredQuality(src.getPreferredQuality());
        return copy;
    }

    private AppSettings getOrLoadAppSettings() {
        AppSettings cached = cachedAppSettings;
        if (appSettingsLoaded && cached != null) {
            return cached;
        }
        synchronized (appSettingsLock) {
            if (appSettingsLoaded && cachedAppSettings != null) {
                return cachedAppSettings;
            }
            AppSettings fromDb = null;
            try {
                fromDb = db.appSettingsDao().getSettingsSync();
            } catch (Exception e) {
                Log.e(TAG, "Failed to load AppSettings from DB", e);
            }
            if (fromDb == null) {
                fromDb = new AppSettings();
                fromDb.setId(1);
            } else if (fromDb.getId() <= 0) {
                fromDb.setId(1);
            }
            cachedAppSettings = fromDb;
            appSettingsLoaded = true;
            String normalized = normalizeSiteUrlValue(fromDb.getSiteUrl());
            if (normalized != null) {
                cachedSiteUrl = normalized;
            }
            return fromDb;
        }
    }

    private void updateAppSettings(AppSettingsMutator mutator, String logDesc) {
        final AppSettings snapshot;
        synchronized (appSettingsLock) {
            AppSettings next = copyAppSettings(getOrLoadAppSettings());
            mutator.mutate(next);
            cachedAppSettings = next;
            appSettingsLoaded = true;
            snapshot = next;
        }
        executor.execute(() -> {
            try {
                db.appSettingsDao().upsert(snapshot);
                Log.d(TAG, "Saved " + logDesc);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save " + logDesc, e);
            }
        });
    }

    // ========== AppSettings операции ==========

    /**
     * Возвращает закэшированный в памяти URL сайта без обращения к SQLite (O(1), безопасно для UI-потока).
     */
    public String getCachedSiteUrlOrNull() {
        return cachedSiteUrl;
    }

    /**
     * Получает URL сайта из памяти или базы данных.
     */
    public String getSiteUrl() {
        String cached = cachedSiteUrl;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        AppSettings settings = getOrLoadAppSettings();
        String normalized = normalizeSiteUrlValue(settings.getSiteUrl());
        if (normalized != null) {
            cachedSiteUrl = normalized;
            return normalized;
        }
        return null;
    }

    /**
     * Сохраняет URL сайта в кэш памяти и базу данных
     */
    public void saveSiteUrl(String siteUrl) {
        String normalized = normalizeSiteUrlValue(siteUrl);
        if (normalized != null) {
            cachedSiteUrl = normalized;
        }
        String valueToPersist = normalized != null ? normalized : siteUrl;
        updateAppSettings(settings -> settings.setSiteUrl(valueToPersist), "site URL: " + siteUrl);
    }

    /**
     * Сохраняет настройку 4K
     */
    public void save4KSetting(boolean enable4K) {
        updateAppSettings(settings -> settings.setEnable4K(enable4K), "4K setting: " + enable4K);
    }

    /**
     * Загружает настройку 4K
     */
    public boolean load4KSetting() {
        return getOrLoadAppSettings().isEnable4K();
    }

    /**
     * Сохраняет настройку ambient light
     */
    public void saveAmbientLightSetting(boolean enableAmbientLight) {
        updateAppSettings(settings -> settings.setEnableAmbientLight(enableAmbientLight),
                "ambient light setting: " + enableAmbientLight);
    }

    /**
     * Загружает настройку ambient light
     */
    public boolean loadAmbientLightSetting() {
        return getOrLoadAppSettings().isEnableAmbientLight();
    }

    public void saveVideoFilters(float brightness, float contrast, float saturation, float gamma, float hue) {
        updateAppSettings(settings -> {
            settings.setFilterBrightness(brightness);
            settings.setFilterContrast(contrast);
            settings.setFilterSaturation(saturation);
            settings.setFilterGamma(gamma);
            settings.setFilterHue(hue);
        }, "video filters settings");
    }

    public float[] loadVideoFilters() {
        AppSettings settings = getOrLoadAppSettings();
        return new float[] {
                settings.getFilterBrightness(),
                settings.getFilterContrast(),
                settings.getFilterSaturation(),
                settings.getFilterGamma(),
                settings.getFilterHue()
        };
    }

    /**
     * Сохраняет настройку объемного звука 5.1
     */
    public void saveSurroundSoundSetting(boolean enableSurroundSound) {
        updateAppSettings(settings -> settings.setEnableSurroundSound(enableSurroundSound),
                "surround sound setting: " + enableSurroundSound);
    }

    /**
     * Сохраняет детальные параметры 3D пространственного звука
     */
    public void saveSurround3DSettings(int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost) {
        saveSurround3DSettings(mode, spatialWidth, dialogueBoost, bassBoost, 1.0f, trebleBoost);
    }

    public void saveSurround3DSettings(int mode, float spatialWidth, float dialogueBoost, float bassBoost, float midBoost, float trebleBoost) {
        updateAppSettings(settings -> {
            settings.setSurroundMode(mode);
            settings.setSurroundSpatialWidth(spatialWidth);
            settings.setSurroundDialogueBoost(dialogueBoost);
            settings.setSurroundBassBoost(bassBoost);
            settings.setSurroundMidBoost(midBoost);
            settings.setSurroundTrebleBoost(trebleBoost);
        }, "3D surround settings: mode=" + mode + ", width=" + spatialWidth + ", dialogue=" + dialogueBoost
                + ", bass=" + bassBoost + ", mid=" + midBoost + ", treble=" + trebleBoost);
    }

    /**
     * Загружает настройку объемного звука 5.1
     */
    public boolean loadSurroundSoundSetting() {
        return getOrLoadAppSettings().isEnableSurroundSound();
    }

    public int loadSurround3DMode() {
        return getOrLoadAppSettings().getSurroundMode();
    }

    public float loadSurroundSpatialWidth() {
        return getOrLoadAppSettings().getSurroundSpatialWidth();
    }

    public float loadSurroundDialogueBoost() {
        return getOrLoadAppSettings().getSurroundDialogueBoost();
    }

    public float loadSurroundBassBoost() {
        return getOrLoadAppSettings().getSurroundBassBoost();
    }

    public float loadSurroundMidBoost() {
        return getOrLoadAppSettings().getSurroundMidBoost();
    }

    public float loadSurroundTrebleBoost() {
        return getOrLoadAppSettings().getSurroundTrebleBoost();
    }

    /**
     * Сохраняет настройку автовоспроизведения
     */
    public void saveAutoPlaySetting(boolean autoPlay) {
        updateAppSettings(settings -> settings.setAutoPlay(autoPlay), "autoPlay setting: " + autoPlay);
    }

    /**
     * Загружает настройку автовоспроизведения
     */
    public boolean loadAutoPlaySetting() {
        return getOrLoadAppSettings().isAutoPlay();
    }

    /**
     * Сохраняет настройку длительности длинного пропуска
     */
    public void saveLongSkipDurationSetting(int duration) {
        updateAppSettings(settings -> settings.setLongSkipDuration(duration),
                "longSkipDuration setting: " + duration);
    }

    /**
     * Загружает настройку длительности длинного пропуска
     */
    public int loadLongSkipDurationSetting() {
        return getOrLoadAppSettings().getLongSkipDuration();
    }

    /**
     * Сохраняет настройку темы
     */
    public void saveThemeSetting(int themeMode) {
        updateAppSettings(settings -> settings.setThemeMode(themeMode), "theme setting: " + themeMode);
    }

    /**
     * Загружает настройку темы
     */
    public int loadThemeSetting() {
        return getOrLoadAppSettings().getThemeMode();
    }

    /**
     * Сохраняет настройку включения субтитров
     */
    public void saveSubtitlesEnabledSetting(boolean subtitlesEnabled) {
        updateAppSettings(settings -> settings.setSubtitlesEnabled(subtitlesEnabled),
                "subtitlesEnabled setting: " + subtitlesEnabled);
    }

    /**
     * Загружает настройку включения субтитров
     */
    public boolean loadSubtitlesEnabledSetting() {
        return getOrLoadAppSettings().isSubtitlesEnabled();
    }

    /**
     * Сохраняет формат субтитров ("ass", "vtt", "auto")
     */
    public void saveSubtitleFormatSetting(String subtitleFormat) {
        updateAppSettings(settings -> settings.setSubtitleFormat(subtitleFormat),
                "subtitleFormat setting: " + subtitleFormat);
    }

    /**
     * Загружает формат субтитров
     */
    public String loadSubtitleFormatSetting() {
        return getOrLoadAppSettings().getSubtitleFormat();
    }

    public void saveSubtitleStyleSettings(float textSize, int textColor, int bgColor, int edgeType, int edgeColor) {
        updateAppSettings(settings -> {
            settings.setSubtitleTextSize(textSize);
            settings.setSubtitleTextColor(textColor);
            settings.setSubtitleBackgroundColor(bgColor);
            settings.setSubtitleEdgeType(edgeType);
            settings.setSubtitleEdgeColor(edgeColor);
        }, "subtitle style settings: size=" + textSize + ", textColor=" + textColor
                + ", bgColor=" + bgColor + ", edgeType=" + edgeType);
    }

    public float loadSubtitleTextSizeSetting() {
        return getOrLoadAppSettings().getSubtitleTextSize();
    }

    public int loadSubtitleTextColorSetting() {
        return getOrLoadAppSettings().getSubtitleTextColor();
    }

    public int loadSubtitleBackgroundColorSetting() {
        return getOrLoadAppSettings().getSubtitleBackgroundColor();
    }

    public int loadSubtitleEdgeTypeSetting() {
        return getOrLoadAppSettings().getSubtitleEdgeType();
    }

    public int loadSubtitleEdgeColorSetting() {
        return getOrLoadAppSettings().getSubtitleEdgeColor();
    }

    public void saveDisableCommentsInPlayerSetting(boolean disable) {
        updateAppSettings(settings -> settings.setDisableCommentsInPlayer(disable),
                "disable comments in player setting: " + disable);
    }

    public boolean loadDisableCommentsInPlayerSetting() {
        return getOrLoadAppSettings().isDisableCommentsInPlayer();
    }

    public void saveCommentRepliesLimitSetting(int limit) {
        updateAppSettings(settings -> settings.setCommentRepliesLimit(limit),
                "comment replies limit setting: " + limit);
    }

    public int loadCommentRepliesLimitSetting() {
        return getOrLoadAppSettings().getCommentRepliesLimit();
    }

    // ========== Token операции ==========

    private static TokenEntity copyToken(TokenEntity src) {
        if (src == null) return null;
        TokenEntity copy = new TokenEntity();
        copy.setId(src.getId() > 0 ? src.getId() : 1);
        copy.setTokenType(src.getTokenType());
        copy.setExpiresIn(src.getExpiresIn());
        copy.setAccessToken(src.getAccessToken());
        copy.setRefreshToken(src.getRefreshToken());
        copy.setTimestamp(src.getTimestamp());
        copy.setUserId(src.getUserId());
        copy.setUsername(src.getUsername());
        copy.setAuthJson(src.getAuthJson());
        return copy;
    }

    private TokenEntity getOrLoadTokenLocked() {
        if (tokenLoaded) {
            return cachedToken;
        }
        try {
            TokenEntity token = db.tokenDao().getToken();
            cachedToken = copyToken(token);
            tokenLoaded = true;
            return cachedToken;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get token from database", e);
            return null;
        }
    }

    /**
     * Сохраняет токен в кэш и базу данных
     */
    public void saveToken(TokenEntity token) {
        final TokenEntity snapshot = copyToken(token);
        synchronized (tokenLock) {
            cachedToken = copyToken(snapshot);
            tokenLoaded = true;
        }
        executor.execute(() -> {
            try {
                if (snapshot != null) {
                    db.tokenDao().insertOrUpdateToken(snapshot);
                    Log.d(TAG, "Saved token to database successfully");
                } else {
                    db.tokenDao().deleteToken();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to save token", e);
            }
        });
    }

    /**
     * Обновляет сырой authJson в текущем токене или создаёт токен с authJson
     */
    public void updateAuthJson(String authJson) {
        if (authJson == null || authJson.trim().isEmpty() || "null".equals(authJson)) return;
        final TokenEntity snapshot;
        synchronized (tokenLock) {
            TokenEntity existing = copyToken(getOrLoadTokenLocked());
            if (existing != null) {
                existing.setAuthJson(authJson);
                snapshot = existing;
            } else {
                TokenEntity newToken = new TokenEntity();
                newToken.setAuthJson(authJson);
                newToken.setTokenType("Bearer");
                newToken.setExpiresIn(2678399L);
                newToken.setTimestamp(System.currentTimeMillis());
                snapshot = newToken;
            }
            cachedToken = copyToken(snapshot);
            tokenLoaded = true;
        }
        executor.execute(() -> {
            try {
                db.tokenDao().insertOrUpdateToken(snapshot);
                Log.d(TAG, "Updated token entity with authJson in database");
            } catch (Exception e) {
                Log.e(TAG, "Failed to update authJson in database", e);
            }
        });
    }

    /**
     * Получает токен из памяти или базы данных
     */
    public TokenEntity getToken() {
        if (tokenLoaded) {
            return copyToken(cachedToken);
        }
        synchronized (tokenLock) {
            return copyToken(getOrLoadTokenLocked());
        }
    }

    /**
     * Проверяет наличие токена в памяти или базе данных
     */
    public boolean hasToken() {
        if (tokenLoaded) {
            return cachedToken != null;
        }
        synchronized (tokenLock) {
            return getOrLoadTokenLocked() != null;
        }
    }

    /**
     * Удаляет токен из кэша и базы данных
     */
    public void deleteToken() {
        synchronized (tokenLock) {
            cachedToken = null;
            tokenLoaded = true;
        }
        executor.execute(() -> {
            try {
                db.tokenDao().deleteToken();
                Log.d(TAG, "Deleted token from database");
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete token", e);
            }
        });
    }

    // ========== PlayerPreferences операции ==========

    private PlayerPreferences getOrLoadPlayerPreferencesLocked() {
        if (playerPreferencesLoaded) {
            return cachedPlayerPreferences;
        }
        PlayerPreferences prefs = null;
        try {
            prefs = db.playerPreferencesDao().getPreferencesSync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load player preferences from DB", e);
        }
        if (prefs != null && prefs.getPreferredQuality() != null) {
            String raw = prefs.getPreferredQuality();
            String sanitized = AutoQualityHelper.sanitizeSavedOnlineQuality(raw);
            prefs.setPreferredQuality(sanitized);
            if (!sanitized.equals(raw)) {
                final PlayerPreferences fixSnapshot = copyPlayerPreferences(prefs);
                executor.execute(() -> {
                    try {
                        db.playerPreferencesDao().upsert(fixSnapshot);
                        if (fixSnapshot.getId() > 0) {
                            db.playerPreferencesDao().deleteDuplicatesExcept(fixSnapshot.getId());
                        }
                    } catch (Exception ignored) {}
                });
            }
        }
        cachedPlayerPreferences = prefs;
        playerPreferencesLoaded = true;
        return prefs;
    }

    private void updatePlayerPreferences(PlayerPrefsMutator mutator, String logDesc) {
        final PlayerPreferences snapshot;
        synchronized (playerPrefsLock) {
            PlayerPreferences existing = getOrLoadPlayerPreferencesLocked();
            PlayerPreferences next = existing != null ? copyPlayerPreferences(existing) : new PlayerPreferences();
            if (next.getId() <= 0) {
                next.setId(1);
            } else if (next.getPreferredQuality() != null) {
                next.setPreferredQuality(AutoQualityHelper.sanitizeSavedOnlineQuality(next.getPreferredQuality()));
            }
            mutator.mutate(next);
            cachedPlayerPreferences = next;
            playerPreferencesLoaded = true;
            snapshot = next;
        }
        executor.execute(() -> {
            try {
                db.playerPreferencesDao().upsert(snapshot);
                if (snapshot.getId() > 0) {
                    db.playerPreferencesDao().deleteDuplicatesExcept(snapshot.getId());
                }
                Log.d(TAG, "Saved " + logDesc + ", quality=" + snapshot.getPreferredQuality());
            } catch (Exception e) {
                Log.e(TAG, "Failed to save " + logDesc, e);
            }
        });
    }

    /**
     * Сохраняет предпочтения по выбору плеера и озвучки, не перезаписывая сохранённое качество
     */
    public void savePlayerPreferences(String player, Integer teamId) {
        updatePlayerPreferences(prefs -> {
            prefs.setPlayer(player);
            prefs.setTeamId(teamId);
        }, "player preferences: player=" + player + ", teamId=" + teamId);
    }

    /**
     * Сохраняет глобальное предпочтение качества ("Авто", "1080p", "720p" и т.д.) независимо от озвучки/команды
     */
    public void savePreferredQuality(String preferredQuality) {
        if (AutoQualityHelper.isDownloadedQuality(preferredQuality)) {
            Log.d(TAG, "Ignoring downloaded quality in savePreferredQuality: " + preferredQuality);
            return;
        }
        final String sanitized = AutoQualityHelper.sanitizeSavedOnlineQuality(preferredQuality);
        updatePlayerPreferences(prefs -> prefs.setPreferredQuality(sanitized),
                "global preferredQuality: " + sanitized);
    }

    /**
     * Сохраняет предпочтения по выбору плеера, озвучки и качества
     */
    public void savePlayerPreferences(String player, Integer teamId, String preferredQuality) {
        updatePlayerPreferences(prefs -> {
            prefs.setPlayer(player);
            prefs.setTeamId(teamId);
            if (!AutoQualityHelper.isDownloadedQuality(preferredQuality)) {
                prefs.setPreferredQuality(AutoQualityHelper.sanitizeSavedOnlineQuality(preferredQuality));
            }
        }, "player preferences: player=" + player + ", teamId=" + teamId);
    }

    /**
     * Загружает предпочтения по выбору плеера, озвучки и качества
     */
    public PlayerPreferences loadPlayerPreferences() {
        PlayerPreferences cached = cachedPlayerPreferences;
        if (playerPreferencesLoaded) {
            return copyPlayerPreferences(cached);
        }
        synchronized (playerPrefsLock) {
            return copyPlayerPreferences(getOrLoadPlayerPreferencesLocked());
        }
    }

    // ========== Downloaded Anime & Episode операции + RAM кэш ==========

    private static DownloadedAnimeEntity copyDownloadedAnime(DownloadedAnimeEntity src) {
        if (src == null) return null;
        return new DownloadedAnimeEntity(
                src.getAnimeId(),
                src.getTitle(),
                src.getPosterUrl(),
                src.getSavedAt(),
                src.getDescription(),
                src.getReleaseYear(),
                src.getAnimeType(),
                src.getRating(),
                src.getTotalEpisodes()
        );
    }

    private static DownloadedEpisodeEntity copyDownloadedEpisode(DownloadedEpisodeEntity src) {
        if (src == null) return null;
        return new DownloadedEpisodeEntity(
                src.getId(),
                src.getAnimeId(),
                src.getAnimeTitle(),
                src.getEpisodeId(),
                src.getEpisodeNumber(),
                src.getEpisodeName(),
                src.getTeamName(),
                src.getPlayerType(),
                src.getLocalFilePath(),
                src.getFileSize(),
                src.getDownloadedAt(),
                src.getQuality(),
                src.getSubtitlesJson(),
                src.getTimecodesJson(),
                src.getSeason(),
                src.getTranslationType(),
                src.getDurationMs()
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static DownloadedAnimeEntity mergeDownloadedAnime(DownloadedAnimeEntity existing, DownloadedAnimeEntity incoming) {
        if (existing == null) return copyDownloadedAnime(incoming);
        if (incoming == null) return copyDownloadedAnime(existing);
        String title = !isBlank(incoming.getTitle()) && !"Аниме".equals(incoming.getTitle())
                ? incoming.getTitle()
                : (!isBlank(existing.getTitle()) ? existing.getTitle() : incoming.getTitle());
        String poster = !isBlank(incoming.getPosterUrl()) ? incoming.getPosterUrl() : existing.getPosterUrl();
        String description = !isBlank(incoming.getDescription()) ? incoming.getDescription() : existing.getDescription();
        String releaseYear = !isBlank(incoming.getReleaseYear()) ? incoming.getReleaseYear() : existing.getReleaseYear();
        String animeType = !isBlank(incoming.getAnimeType()) ? incoming.getAnimeType() : existing.getAnimeType();
        String rating = !isBlank(incoming.getRating()) ? incoming.getRating() : existing.getRating();
        int totalEpisodes = incoming.getTotalEpisodes() > 0 ? incoming.getTotalEpisodes() : existing.getTotalEpisodes();
        long savedAt = Math.max(incoming.getSavedAt(), existing.getSavedAt());
        return new DownloadedAnimeEntity(
                incoming.getAnimeId(),
                title,
                poster,
                savedAt,
                description,
                releaseYear,
                animeType,
                rating,
                totalEpisodes
        );
    }

    private static void rebuildEpisodesByPathLocked() {
        cachedEpisodesByPath.clear();
        for (DownloadedEpisodeEntity ep : cachedEpisodesById.values()) {
            if (ep != null && ep.getLocalFilePath() != null && !cachedEpisodesByPath.containsKey(ep.getLocalFilePath())) {
                cachedEpisodesByPath.put(ep.getLocalFilePath(), ep);
            }
        }
    }

    private void ensureDownloadsCacheLoadedLocked() {
        if (downloadsCacheLoaded) {
            return;
        }
        cachedDownloadedAnimeById.clear();
        cachedEpisodesById.clear();
        cachedEpisodesByPath.clear();
        try {
            List<DownloadedAnimeEntity> animes = db.downloadedAnimeDao().getAllDownloadedAnimeSync();
            if (animes != null) {
                for (DownloadedAnimeEntity anime : animes) {
                    if (anime != null) {
                        cachedDownloadedAnimeById.put(anime.getAnimeId(), copyDownloadedAnime(anime));
                    }
                }
            }
            List<DownloadedEpisodeEntity> episodes = db.downloadedAnimeDao().getAllEpisodesSync();
            if (episodes != null) {
                for (DownloadedEpisodeEntity ep : episodes) {
                    if (ep != null) {
                        cachedEpisodesById.put(ep.getId(), copyDownloadedEpisode(ep));
                    }
                }
            }
            rebuildEpisodesByPathLocked();
            downloadsCacheLoaded = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to warm downloads metadata cache", e);
        }
    }

    public void saveDownloadedAnime(DownloadedAnimeEntity anime) {
        if (anime == null) return;
        final DownloadedAnimeEntity snapshot;
        synchronized (downloadsCacheLock) {
            DownloadedAnimeEntity existing = downloadsCacheLoaded
                    ? cachedDownloadedAnimeById.get(anime.getAnimeId())
                    : db.downloadedAnimeDao().getAnimeByIdSync(anime.getAnimeId());
            snapshot = mergeDownloadedAnime(existing, anime);
            if (downloadsCacheLoaded) {
                cachedDownloadedAnimeById.put(snapshot.getAnimeId(), copyDownloadedAnime(snapshot));
            }
        }
        executor.execute(() -> {
            try {
                db.downloadedAnimeDao().insertAnime(snapshot);
                Log.d(TAG, "Saved downloaded anime: " + snapshot.getTitle());
            } catch (Exception e) {
                Log.e(TAG, "Failed to save downloaded anime", e);
            }
        });
    }

    public void saveDownloadedEpisode(DownloadedEpisodeEntity episode) {
        if (episode == null) return;
        final DownloadedEpisodeEntity snapshot = copyDownloadedEpisode(episode);
        synchronized (downloadsCacheLock) {
            if (downloadsCacheLoaded) {
                cachedEpisodesById.put(snapshot.getId(), copyDownloadedEpisode(snapshot));
                rebuildEpisodesByPathLocked();
            }
        }
        executor.execute(() -> {
            try {
                db.downloadedAnimeDao().insertEpisode(snapshot);
                Log.d(TAG, "Saved downloaded episode: " + snapshot.getEpisodeNumber() + " for anime " + snapshot.getAnimeTitle());
            } catch (Exception e) {
                Log.e(TAG, "Failed to save downloaded episode", e);
            }
        });
    }

    public androidx.lifecycle.LiveData<List<DownloadedAnimeEntity>> getAllDownloadedAnimeLiveData() {
        return db.downloadedAnimeDao().getAllDownloadedAnimeLiveData();
    }

    public DownloadedAnimeEntity getDownloadedAnimeSync(String animeId) {
        if (animeId == null) return null;
        synchronized (downloadsCacheLock) {
            ensureDownloadsCacheLoadedLocked();
            return copyDownloadedAnime(cachedDownloadedAnimeById.get(animeId));
        }
    }

    public androidx.lifecycle.LiveData<List<DownloadedEpisodeEntity>> getEpisodesForAnimeLiveData(String animeId) {
        return db.downloadedAnimeDao().getEpisodesForAnimeLiveData(animeId);
    }

    public List<DownloadedEpisodeEntity> getEpisodesForAnimeSync(String animeId) {
        if (animeId == null) return new ArrayList<>();
        synchronized (downloadsCacheLock) {
            ensureDownloadsCacheLoadedLocked();
            List<DownloadedEpisodeEntity> result = new ArrayList<>();
            for (DownloadedEpisodeEntity ep : cachedEpisodesById.values()) {
                if (ep != null && animeId.equals(ep.getAnimeId())) {
                    result.add(copyDownloadedEpisode(ep));
                }
            }
            result.sort(Comparator
                    .comparingDouble((DownloadedEpisodeEntity ep) ->
                            com.example.animelib.models.OfflineEpisodeMetadata.parseEpisodeOrder(ep.getEpisodeNumber()))
                    .thenComparingInt(DownloadedEpisodeEntity::getEpisodeId)
                    .thenComparing(ep -> ep.getTeamName() != null ? ep.getTeamName() : ""));
            return result;
        }
    }

    public DownloadedEpisodeEntity findDownloadedEpisode(String animeId, int episodeId, String episodeNumber, String teamName) {
        if (animeId == null) return null;
        String cleanTeam = teamName != null ? teamName.trim() : null;
        boolean hasTeamFilter = cleanTeam != null && !cleanTeam.isEmpty();
        synchronized (downloadsCacheLock) {
            ensureDownloadsCacheLoadedLocked();
            if (episodeId != 0) {
                for (DownloadedEpisodeEntity ep : cachedEpisodesById.values()) {
                    if (ep == null || !animeId.equals(ep.getAnimeId()) || ep.getEpisodeId() != episodeId) {
                        continue;
                    }
                    if (!hasTeamFilter) {
                        return copyDownloadedEpisode(ep);
                    }
                    String epTeam = ep.getTeamName() != null ? ep.getTeamName().trim() : null;
                    if (epTeam != null && cleanTeam.equalsIgnoreCase(epTeam)) {
                        return copyDownloadedEpisode(ep);
                    }
                }
            }
            if (episodeNumber != null && !episodeNumber.isEmpty()) {
                for (DownloadedEpisodeEntity ep : cachedEpisodesById.values()) {
                    if (ep == null || !animeId.equals(ep.getAnimeId()) || !episodeNumber.equals(ep.getEpisodeNumber())) {
                        continue;
                    }
                    if (!hasTeamFilter) {
                        return copyDownloadedEpisode(ep);
                    }
                    String epTeam = ep.getTeamName() != null ? ep.getTeamName().trim() : null;
                    if (epTeam != null && cleanTeam.equalsIgnoreCase(epTeam)) {
                        return copyDownloadedEpisode(ep);
                    }
                }
            }
            return null;
        }
    }

    public DownloadedEpisodeEntity findDownloadedEpisode(String animeId, int episodeId, String teamName) {
        return findDownloadedEpisode(animeId, episodeId, null, teamName);
    }

    public DownloadedEpisodeEntity findDownloadedEpisode(String animeId, String episodeNumber, String teamName) {
        if (animeId == null || episodeNumber == null || teamName == null) return null;
        String cleanTeam = teamName.trim();
        if (cleanTeam.isEmpty()) return null;
        synchronized (downloadsCacheLock) {
            ensureDownloadsCacheLoadedLocked();
            for (DownloadedEpisodeEntity ep : cachedEpisodesById.values()) {
                if (ep != null
                        && animeId.equals(ep.getAnimeId())
                        && episodeNumber.equals(ep.getEpisodeNumber())
                        && ep.getTeamName() != null
                        && cleanTeam.equalsIgnoreCase(ep.getTeamName().trim())) {
                    return copyDownloadedEpisode(ep);
                }
            }
            return null;
        }
    }

    public DownloadedEpisodeEntity findEpisodeByPath(String path) {
        if (path == null) return null;
        String normalized = com.example.animelib.models.OfflineEpisodeMetadata.normalizeLocalFilePath(path);
        String lookupKey = normalized != null ? normalized : path;
        synchronized (downloadsCacheLock) {
            ensureDownloadsCacheLoadedLocked();
            DownloadedEpisodeEntity exact = cachedEpisodesByPath.get(lookupKey);
            if (exact != null) {
                return copyDownloadedEpisode(exact);
            }
            for (DownloadedEpisodeEntity ep : cachedEpisodesById.values()) {
                if (ep != null && ep.getLocalFilePath() != null) {
                    String epNorm = com.example.animelib.models.OfflineEpisodeMetadata.normalizeLocalFilePath(ep.getLocalFilePath());
                    if (lookupKey.equals(epNorm)) {
                        return copyDownloadedEpisode(ep);
                    }
                }
            }
            return null;
        }
    }

    public void deleteDownloadedEpisode(String episodeId, String animeId) {
        if (episodeId == null) return;
        synchronized (downloadsCacheLock) {
            if (downloadsCacheLoaded) {
                cachedEpisodesById.remove(episodeId);
                rebuildEpisodesByPathLocked();
                if (animeId != null) {
                    boolean hasRemaining = false;
                    for (DownloadedEpisodeEntity ep : cachedEpisodesById.values()) {
                        if (ep != null && Objects.equals(animeId, ep.getAnimeId())) {
                            hasRemaining = true;
                            break;
                        }
                    }
                    if (!hasRemaining) {
                        cachedDownloadedAnimeById.remove(animeId);
                    }
                }
            }
        }
        executor.execute(() -> {
            try {
                DownloadedEpisodeEntity ep = db.downloadedAnimeDao().getEpisodeById(episodeId);
                if (ep != null) {
                    com.example.animelib.util.FileUtils.deleteDownloadedEpisodeFiles(ep.getLocalFilePath());
                }
                db.downloadedAnimeDao().deleteEpisodeById(episodeId);
                if (animeId != null) {
                    int count = db.downloadedAnimeDao().getEpisodeCountForAnime(animeId);
                    if (count == 0) {
                        DownloadedAnimeEntity anime = db.downloadedAnimeDao().getAnimeByIdSync(animeId);
                        if (anime != null) {
                            com.example.animelib.util.FileUtils.deleteLocalPosterAndEmptyDir(anime.getPosterUrl());
                        }
                        db.downloadedAnimeDao().deleteAnimeById(animeId);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete downloaded episode: " + episodeId, e);
            }
        });
    }

    public void deleteDownloadedAnime(String animeId) {
        if (animeId == null) return;
        synchronized (downloadsCacheLock) {
            if (downloadsCacheLoaded) {
                cachedDownloadedAnimeById.remove(animeId);
                cachedEpisodesById.entrySet().removeIf(entry ->
                        entry.getValue() != null && animeId.equals(entry.getValue().getAnimeId()));
                rebuildEpisodesByPathLocked();
            }
        }
        executor.execute(() -> {
            try {
                DownloadedAnimeEntity anime = db.downloadedAnimeDao().getAnimeByIdSync(animeId);
                List<DownloadedEpisodeEntity> episodes = db.downloadedAnimeDao().getEpisodesForAnimeSync(animeId);
                for (DownloadedEpisodeEntity ep : episodes) {
                    if (ep != null) {
                        com.example.animelib.util.FileUtils.deleteDownloadedEpisodeFiles(ep.getLocalFilePath());
                    }
                }
                if (anime != null) {
                    com.example.animelib.util.FileUtils.deleteLocalPosterAndEmptyDir(anime.getPosterUrl());
                }
                db.downloadedAnimeDao().deleteEpisodesForAnime(animeId);
                db.downloadedAnimeDao().deleteAnimeById(animeId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete downloaded anime: " + animeId, e);
            }
        });
    }

    // ========== Offline Bookmarks операции ==========

    public boolean saveOfflineBookmarkSync(String animeId, int episodeId, String episodeNumber, String timecode, long positionMs) {
        if (animeId == null || animeId.trim().isEmpty()) {
            Log.e(TAG, "Cannot save offline bookmark: animeId is empty");
            return false;
        }
        try {
            com.example.animelib.data.entity.OfflineBookmarkEntity entity =
                    new com.example.animelib.data.entity.OfflineBookmarkEntity(
                            animeId, episodeId, episodeNumber, timecode, positionMs, System.currentTimeMillis()
                    );
            db.offlineBookmarkDao().saveBookmark(entity);
            Log.d(TAG, "Saved offline bookmark for anime " + animeId + ", episode " + episodeNumber + " (" + timecode + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save offline bookmark", e);
            return false;
        }
    }

    public void saveOfflineBookmark(String animeId, int episodeId, String episodeNumber, String timecode, long positionMs) {
        saveOfflineBookmark(animeId, episodeId, episodeNumber, timecode, positionMs, null, null);
    }

    public void saveOfflineBookmark(String animeId, int episodeId, String episodeNumber, String timecode, long positionMs,
                                    Runnable onSuccess, Runnable onError) {
        Runnable task = () -> {
            boolean ok = saveOfflineBookmarkSync(animeId, episodeId, episodeNumber, timecode, positionMs);
            if (ok) {
                if (onSuccess != null) onSuccess.run();
            } else {
                if (onError != null) onError.run();
            }
        };
        if (executor != null && !executor.isShutdown()) {
            executor.execute(task);
        } else {
            new Thread(task, "offline-bookmark-save").start();
        }
    }

    public com.example.animelib.data.entity.OfflineBookmarkEntity getOfflineBookmarkSync(String animeId) {
        try {
            return db.offlineBookmarkDao().getBookmarkSync(animeId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get offline bookmark for " + animeId, e);
            return null;
        }
    }

    public void deleteOfflineBookmark(String animeId) {
        executor.execute(() -> {
            try {
                db.offlineBookmarkDao().deleteBookmark(animeId);
                Log.d(TAG, "Deleted offline bookmark for anime " + animeId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete offline bookmark", e);
            }
        });
    }

    // ========== Pending Sync Tasks операции ==========

    public void savePendingSyncTask(com.example.animelib.data.entity.PendingSyncTaskEntity task) {
        try {
            db.pendingSyncTaskDao().insertTask(task);
            Log.d(TAG, "Saved pending sync task: " + task.getTaskType());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save pending sync task", e);
        }
    }

    public java.util.List<com.example.animelib.data.entity.PendingSyncTaskEntity> getAllPendingSyncTasksSync() {
        try {
            return db.pendingSyncTaskDao().getAllTasksSync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get pending sync tasks", e);
            return new java.util.ArrayList<>();
        }
    }

    public com.example.animelib.data.entity.PendingSyncTaskEntity findPendingSyncTaskByIdSync(long id) {
        try {
            return db.pendingSyncTaskDao().findTaskByIdSync(id);
        } catch (Exception e) {
            Log.e(TAG, "Failed to find pending sync task id " + id, e);
            return null;
        }
    }

    public void deletePendingSyncTaskById(long id) {
        try {
            db.pendingSyncTaskDao().deleteTaskById(id);
            Log.d(TAG, "Deleted pending sync task id: " + id);
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete pending sync task id " + id, e);
        }
    }

    public void updatePendingSyncTask(com.example.animelib.data.entity.PendingSyncTaskEntity task) {
        try {
            db.pendingSyncTaskDao().updateTask(task);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update pending sync task", e);
        }
    }

    /**
     * Прогревает in-memory кэши (AppSettings, PlayerPreferences, Token, метаданные загрузок) в фоновом потоке,
     * чтобы последующие синхронные вызовы с UI-потока работали за O(1) без обращений к SQLite.
     */
    public void preloadCachesAsync() {
        executor.execute(() -> {
            try {
                getOrLoadAppSettings();
            } catch (Exception e) {
                Log.w(TAG, "Warmup AppSettings failed", e);
            }
            try {
                loadPlayerPreferences();
            } catch (Exception e) {
                Log.w(TAG, "Warmup PlayerPreferences failed", e);
            }
            try {
                getToken();
            } catch (Exception e) {
                Log.w(TAG, "Warmup Token failed", e);
            }
            try {
                synchronized (downloadsCacheLock) {
                    ensureDownloadsCacheLoadedLocked();
                }
            } catch (Exception e) {
                Log.w(TAG, "Warmup downloads cache failed", e);
            }
        });
    }

    /**
     * Закрывает executor при завершении работы.
     * Используется общий фоновый поток БД на уровне процесса, поэтому shutdown безопасен для параллельных вызовов.
     */
    public void shutdown() {
        // Shared daemon executor stays alive across short-lived callers to prevent rejected tasks and thread leaks.
    }
}
