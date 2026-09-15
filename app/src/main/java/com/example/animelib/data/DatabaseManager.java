package com.example.animelib.data;

import android.content.Context;
import android.util.Log;

import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Менеджер для всех операций с базой данных Room
 * Выделен из ApiService для разделения ответственности
 */
public class DatabaseManager {
    private static final String TAG = "DatabaseManager";
    private static volatile TokenEntity cachedToken = null;

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
        this.executor = Executors.newSingleThreadExecutor();
        instance = this;
    }
    
    // ========== AppSettings операции ==========

    /**
     * Получает URL сайта из базы данных. Вызывать вне главного потока
     */
    public String getSiteUrl() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            if (settings != null && settings.getSiteUrl() != null && !settings.getSiteUrl().trim().isEmpty()) {
                String url = settings.getSiteUrl().trim();
                if (url.endsWith("/")) {
                    url = url.substring(0, url.length() - 1);
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                return url;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get site URL from DB", e);
        }
        return null;
    }

    /**
     * Сохраняет URL сайта в базу данных
     */
    public void saveSiteUrl(String siteUrl) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setSiteUrl(siteUrl);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved site URL: " + siteUrl);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save site URL", e);
            }
        });
    }
    
    /**
     * Сохраняет настройку 4K
     */
    public void save4KSetting(boolean enable4K) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setEnable4K(enable4K);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved 4K setting: " + enable4K);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save 4K setting", e);
            }
        });
    }
    
    /**
     * Загружает настройку 4K
     */
    public boolean load4KSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings != null && settings.isEnable4K();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load 4K setting", e);
            return false;
        }
    }
    
    /**
     * Сохраняет настройку ambient light
     */
    public void saveAmbientLightSetting(boolean enableAmbientLight) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setEnableAmbientLight(enableAmbientLight);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved ambient light setting: " + enableAmbientLight);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save ambient light setting", e);
            }
        });
    }
    
    /**
     * Загружает настройку ambient light
     */
    public boolean loadAmbientLightSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings == null ? true : settings.isEnableAmbientLight();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ambient light setting", e);
            return true;
        }
    }

    public void saveVideoFilters(float brightness, float contrast, float saturation, float gamma, float hue) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setFilterBrightness(brightness);
                settings.setFilterContrast(contrast);
                settings.setFilterSaturation(saturation);
                settings.setFilterGamma(gamma);
                settings.setFilterHue(hue);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved video filters settings");
            } catch (Exception e) {
                Log.e(TAG, "Failed to save video filters settings", e);
            }
        });
    }

    public float[] loadVideoFilters() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            if (settings != null) {
                return new float[] {
                    settings.getFilterBrightness(),
                    settings.getFilterContrast(),
                    settings.getFilterSaturation(),
                    settings.getFilterGamma(),
                    settings.getFilterHue()
                };
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load video filters settings", e);
        }
        return new float[] { 0f, 100f, 100f, 1.0f, 0f };
    }

    /**
     * Сохраняет настройку объемного звука 5.1
     */
    public void saveSurroundSoundSetting(boolean enableSurroundSound) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setEnableSurroundSound(enableSurroundSound);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved surround sound setting: " + enableSurroundSound);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save surround sound setting", e);
            }
        });
    }

    /**
     * Сохраняет детальные параметры 3D пространственного звука
     */
    public void saveSurround3DSettings(int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setSurroundMode(mode);
                settings.setSurroundSpatialWidth(spatialWidth);
                settings.setSurroundDialogueBoost(dialogueBoost);
                settings.setSurroundBassBoost(bassBoost);
                settings.setSurroundTrebleBoost(trebleBoost);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved 3D surround settings: mode=" + mode + ", width=" + spatialWidth + ", dialogue=" + dialogueBoost + ", bass=" + bassBoost + ", treble=" + trebleBoost);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save 3D surround settings", e);
            }
        });
    }

    /**
     * Загружает настройку объемного звука 5.1
     */
    public boolean loadSurroundSoundSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings == null || settings.isEnableSurroundSound(); // Default true
        } catch (Exception e) {
            Log.e(TAG, "Failed to load surround sound setting", e);
            return true;
        }
    }

    public int loadSurround3DMode() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings == null ? 0 : settings.getSurroundMode();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load 3D surround mode", e);
            return 0;
        }
    }

    public float loadSurroundSpatialWidth() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings == null ? 1.0f : settings.getSurroundSpatialWidth();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load 3D spatial width", e);
            return 1.0f;
        }
    }

    public float loadSurroundDialogueBoost() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings == null ? 1.0f : settings.getSurroundDialogueBoost();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load 3D dialogue boost", e);
            return 1.0f;
        }
    }

    public float loadSurroundBassBoost() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings == null ? 1.0f : settings.getSurroundBassBoost();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load 3D bass boost", e);
            return 1.0f;
        }
    }

    public float loadSurroundTrebleBoost() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings == null ? 1.0f : settings.getSurroundTrebleBoost();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load 3D treble boost", e);
            return 1.0f;
        }
    }
    
    /**
     * Сохраняет настройку автовоспроизведения
     */
    public void saveAutoPlaySetting(boolean autoPlay) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setAutoPlay(autoPlay);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved autoPlay setting: " + autoPlay);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save autoPlay setting", e);
            }
        });
    }
    
    /**
     * Загружает настройку автовоспроизведения
     */
    public boolean loadAutoPlaySetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings != null && settings.isAutoPlay();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load autoPlay setting", e);
            return true; // Default to true
        }
    }
    
    /**
     * Сохраняет настройку длительности длинного пропуска
     */
    public void saveLongSkipDurationSetting(int duration) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setLongSkipDuration(duration);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved longSkipDuration setting: " + duration);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save longSkipDuration setting", e);
            }
        });
    }
    
    /**
     * Загружает настройку длительности длинного пропуска
     */
    public int loadLongSkipDurationSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings != null ? settings.getLongSkipDuration() : 85; // Default to 85 seconds
        } catch (Exception e) {
            Log.e(TAG, "Failed to load longSkipDuration setting", e);
            return 85; // Default to 85 seconds
        }
    }
    
    /**
     * Сохраняет настройку темы
     */
    public void saveThemeSetting(int themeMode) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setThemeMode(themeMode);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved theme setting: " + themeMode);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save theme setting", e);
            }
        });
    }
    
    /**
     * Загружает настройку темы
     */
    public int loadThemeSetting() {

        AppSettings settings = db.appSettingsDao().getSettingsSync();
        return settings != null ? settings.getThemeMode() : 0; // Default to light theme (0)
    }

    /**
     * Сохраняет настройку включения субтитров
     */
    public void saveSubtitlesEnabledSetting(boolean subtitlesEnabled) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setSubtitlesEnabled(subtitlesEnabled);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved subtitlesEnabled setting: " + subtitlesEnabled);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save subtitlesEnabled setting", e);
            }
        });
    }

    /**
     * Загружает настройку включения субтитров
     */
    public boolean loadSubtitlesEnabledSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings == null || settings.isSubtitlesEnabled(); // Default to true
        } catch (Exception e) {
            Log.e(TAG, "Failed to load subtitlesEnabled setting", e);
            return true;
        }
    }

    /**
     * Сохраняет формат субтитров ("ass", "vtt", "auto")
     */
    public void saveSubtitleFormatSetting(String subtitleFormat) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setSubtitleFormat(subtitleFormat);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved subtitleFormat setting: " + subtitleFormat);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save subtitleFormat setting", e);
            }
        });
    }

    /**
     * Загружает формат субтитров
     */
    public String loadSubtitleFormatSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings != null ? settings.getSubtitleFormat() : "ass"; // Default to "ass"
        } catch (Exception e) {
            Log.e(TAG, "Failed to load subtitleFormat setting", e);
            return "ass";
        }
    }

    public void saveSubtitleStyleSettings(float textSize, int textColor, int bgColor, int edgeType, int edgeColor) {
        executor.execute(() -> {
            try {
                AppSettings settings = db.appSettingsDao().getSettingsSync();
                if (settings == null) {
                    settings = new AppSettings();
                }
                settings.setSubtitleTextSize(textSize);
                settings.setSubtitleTextColor(textColor);
                settings.setSubtitleBackgroundColor(bgColor);
                settings.setSubtitleEdgeType(edgeType);
                settings.setSubtitleEdgeColor(edgeColor);
                db.appSettingsDao().upsert(settings);
                Log.d(TAG, "Saved subtitle style settings: size=" + textSize + ", textColor=" + textColor + ", bgColor=" + bgColor + ", edgeType=" + edgeType);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save subtitle style settings", e);
            }
        });
    }

    public float loadSubtitleTextSizeSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings != null ? settings.getSubtitleTextSize() : 18f;
        } catch (Exception e) {
            return 18f;
        }
    }

    public int loadSubtitleTextColorSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings != null ? settings.getSubtitleTextColor() : 0xFFFFFFFF;
        } catch (Exception e) {
            return 0xFFFFFFFF;
        }
    }

    public int loadSubtitleBackgroundColorSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings != null ? settings.getSubtitleBackgroundColor() : 0x00000000;
        } catch (Exception e) {
            return 0x00000000;
        }
    }

    public int loadSubtitleEdgeTypeSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings != null ? settings.getSubtitleEdgeType() : 1; // CaptionStyleCompat.EDGE_TYPE_OUTLINE
        } catch (Exception e) {
            return 1;
        }
    }

    public int loadSubtitleEdgeColorSetting() {
        try {
            AppSettings settings = db.appSettingsDao().getSettingsSync();
            return settings != null ? settings.getSubtitleEdgeColor() : 0xFF000000;
        } catch (Exception e) {
            return 0xFF000000;
        }
    }
    
    // ========== Token операции ==========
    
    /**
     * Сохраняет токен в базу данных
     */
    public void saveToken(TokenEntity token) {
        cachedToken = token;
        executor.execute(() -> {
            try {
                db.tokenDao().insertOrUpdateToken(token);
                Log.d(TAG, "Saved token to database successfully");
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
        executor.execute(() -> {
            try {
                TokenEntity token = db.tokenDao().getToken();
                if (token != null) {
                    token.setAuthJson(authJson);
                    db.tokenDao().insertOrUpdateToken(token);
                    cachedToken = token;
                    Log.d(TAG, "Updated token entity with authJson in database");
                } else {
                    TokenEntity newToken = new TokenEntity();
                    newToken.setAuthJson(authJson);
                    newToken.setTokenType("Bearer");
                    newToken.setExpiresIn(2678399L);
                    newToken.setTimestamp(System.currentTimeMillis());
                    db.tokenDao().insertOrUpdateToken(newToken);
                    cachedToken = newToken;
                    Log.d(TAG, "Saved new token entity with authJson in database");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to update authJson in database", e);
            }
        });
    }
    
    /**
     * Получает токен из базы данных
     */
    public TokenEntity getToken() {
        if (cachedToken != null) {
            return cachedToken;
        }
        try {
            TokenEntity token = db.tokenDao().getToken();
            if (token != null) {
                cachedToken = token;
            }
            return token;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get token from database", e);
            return null;
        }
    }
    
    /**
     * Проверяет есть ли токен в базе данных
     */
    public boolean hasToken() {
        if (cachedToken != null) {
            return true;
        }
        try {
            return db.tokenDao().getTokenCount() > 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to check token existence", e);
            return false;
        }
    }
    
    /**
     * Удаляет токен из базы данных
     */
    public void deleteToken() {
        cachedToken = null;
        executor.execute(() -> {
            try {
                db.tokenDao().deleteToken();
                Log.d(TAG, "Deleted token from database");
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete token", e);
            }
        });
    }
    
    // ========== CurrentEpisode операции ==========
    
    // ========== PlayerPreferences операции ==========
    
    /**
     * Сохраняет предпочтения по выбору плеера и озвучки
     */
    public void savePlayerPreferences(String player, Integer teamId) {
        executor.execute(() -> {
            try {
                // Загружаем существующую запись или создаем новую
                com.example.animelib.data.entity.PlayerPreferences preferences = 
                    db.playerPreferencesDao().getPreferencesSync();
                
                if (preferences == null) {
                    preferences = new com.example.animelib.data.entity.PlayerPreferences();
                }
                
                // Обновляем данные
                preferences.setPlayer(player);
                preferences.setTeamId(teamId);
                
                db.playerPreferencesDao().upsert(preferences);
                Log.d(TAG, "Saved player preferences: player=" + player + ", teamId=" + teamId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save player preferences", e);
            }
        });
    }
    
    /**
     * Сохраняет предпочтения по выбору плеера, озвучки и качества
     */
    public void savePlayerPreferences(String player, Integer teamId, String preferredQuality) {
        executor.execute(() -> {
            try {
                // Загружаем существующую запись или создаем новую
                com.example.animelib.data.entity.PlayerPreferences preferences = 
                    db.playerPreferencesDao().getPreferencesSync();
                
                if (preferences == null) {
                    preferences = new com.example.animelib.data.entity.PlayerPreferences();
                }
                
                // Обновляем данные
                preferences.setPlayer(player);
                preferences.setTeamId(teamId);
                preferences.setPreferredQuality(preferredQuality);
                
                db.playerPreferencesDao().upsert(preferences);
                Log.d(TAG, "Saved player preferences: player=" + player + ", teamId=" + teamId + 
                      ", quality=" + preferredQuality);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save player preferences", e);
            }
        });
    }
    
    /**
     * Загружает предпочтения по выбору плеера и озвучки
     */
    public com.example.animelib.data.entity.PlayerPreferences loadPlayerPreferences() {
        try {
            com.example.animelib.data.entity.PlayerPreferences prefs = db.playerPreferencesDao().getPreferencesSync();
            if (prefs != null) {
                Log.d(TAG, "Loaded player preferences: player=" + prefs.getPlayer() + ", teamId=" + prefs.getTeamId());
            } else {
                Log.d(TAG, "No player preferences found in database");
            }
            return prefs;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load player preferences", e);
            return null;
        }
    }

    // ========== Downloaded Anime & Episode операции ==========

    public void saveDownloadedAnime(com.example.animelib.data.entity.DownloadedAnimeEntity anime) {
        executor.execute(() -> {
            try {
                db.downloadedAnimeDao().insertAnime(anime);
                Log.d(TAG, "Saved downloaded anime: " + anime.getTitle());
            } catch (Exception e) {
                Log.e(TAG, "Failed to save downloaded anime", e);
            }
        });
    }

    public void saveDownloadedEpisode(com.example.animelib.data.entity.DownloadedEpisodeEntity episode) {
        executor.execute(() -> {
            try {
                db.downloadedAnimeDao().insertEpisode(episode);
                Log.d(TAG, "Saved downloaded episode: " + episode.getEpisodeNumber() + " for anime " + episode.getAnimeTitle());
            } catch (Exception e) {
                Log.e(TAG, "Failed to save downloaded episode", e);
            }
        });
    }

    public androidx.lifecycle.LiveData<java.util.List<com.example.animelib.data.entity.DownloadedAnimeEntity>> getAllDownloadedAnimeLiveData() {
        return db.downloadedAnimeDao().getAllDownloadedAnimeLiveData();
    }

    public com.example.animelib.data.entity.DownloadedAnimeEntity getDownloadedAnimeSync(String animeId) {
        return db.downloadedAnimeDao().getAnimeByIdSync(animeId);
    }

    public androidx.lifecycle.LiveData<java.util.List<com.example.animelib.data.entity.DownloadedEpisodeEntity>> getEpisodesForAnimeLiveData(String animeId) {
        return db.downloadedAnimeDao().getEpisodesForAnimeLiveData(animeId);
    }

    public java.util.List<com.example.animelib.data.entity.DownloadedEpisodeEntity> getEpisodesForAnimeSync(String animeId) {
        return db.downloadedAnimeDao().getEpisodesForAnimeSync(animeId);
    }

    public com.example.animelib.data.entity.DownloadedEpisodeEntity findDownloadedEpisode(String animeId, int episodeId, String episodeNumber, String teamName) {
        if (episodeId != 0) {
            com.example.animelib.data.entity.DownloadedEpisodeEntity ep = null;
            if (teamName != null && !teamName.isEmpty()) {
                ep = db.downloadedAnimeDao().findDownloadedEpisodeByEpisodeId(animeId, episodeId, teamName);
            }
            if (ep == null) {
                ep = db.downloadedAnimeDao().findDownloadedEpisodeByEpisodeIdOnly(animeId, episodeId);
            }
            if (ep != null) return ep;
        }
        if (episodeNumber != null && !episodeNumber.isEmpty() && teamName != null) {
            return db.downloadedAnimeDao().findDownloadedEpisode(animeId, episodeNumber, teamName);
        }
        return null;
    }

    public com.example.animelib.data.entity.DownloadedEpisodeEntity findDownloadedEpisode(String animeId, int episodeId, String teamName) {
        return findDownloadedEpisode(animeId, episodeId, null, teamName);
    }

    public com.example.animelib.data.entity.DownloadedEpisodeEntity findDownloadedEpisode(String animeId, String episodeNumber, String teamName) {
        return db.downloadedAnimeDao().findDownloadedEpisode(animeId, episodeNumber, teamName);
    }

    public com.example.animelib.data.entity.DownloadedEpisodeEntity findEpisodeByPath(String path) {
        return db.downloadedAnimeDao().findEpisodeByPath(path);
    }

    public void deleteDownloadedEpisode(String episodeId, String animeId) {
        executor.execute(() -> {
            try {
                com.example.animelib.data.entity.DownloadedEpisodeEntity ep = db.downloadedAnimeDao().getEpisodeById(episodeId);
                if (ep != null && ep.getLocalFilePath() != null) {
                    java.io.File file = new java.io.File(ep.getLocalFilePath());
                    if (file.exists()) {
                        file.delete();
                    }
                    java.io.File segsDir = new java.io.File(file.getParentFile(), file.getName() + "_segs");
                    if (segsDir.exists()) {
                        deleteDir(segsDir);
                    }
                }
                db.downloadedAnimeDao().deleteEpisodeById(episodeId);
                int count = db.downloadedAnimeDao().getEpisodeCountForAnime(animeId);
                if (count == 0) {
                    db.downloadedAnimeDao().deleteAnimeById(animeId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete downloaded episode: " + episodeId, e);
            }
        });
    }

    public void deleteDownloadedAnime(String animeId) {
        executor.execute(() -> {
            try {
                java.util.List<com.example.animelib.data.entity.DownloadedEpisodeEntity> episodes = db.downloadedAnimeDao().getEpisodesForAnimeSync(animeId);
                for (com.example.animelib.data.entity.DownloadedEpisodeEntity ep : episodes) {
                    if (ep.getLocalFilePath() != null) {
                        java.io.File file = new java.io.File(ep.getLocalFilePath());
                        if (file.exists()) {
                            file.delete();
                        }
                        java.io.File segsDir = new java.io.File(file.getParentFile(), file.getName() + "_segs");
                        if (segsDir.exists()) {
                            deleteDir(segsDir);
                        }
                    }
                }
                db.downloadedAnimeDao().deleteEpisodesForAnime(animeId);
                db.downloadedAnimeDao().deleteAnimeById(animeId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete downloaded anime: " + animeId, e);
            }
        });
    }

    // ========== Offline Bookmarks операции ==========

    public void saveOfflineBookmark(String animeId, int episodeId, String episodeNumber, String timecode, long positionMs) {
        executor.execute(() -> {
            try {
                com.example.animelib.data.entity.OfflineBookmarkEntity entity =
                        new com.example.animelib.data.entity.OfflineBookmarkEntity(
                                animeId, episodeId, episodeNumber, timecode, positionMs, System.currentTimeMillis()
                        );
                db.offlineBookmarkDao().saveBookmark(entity);
                Log.d(TAG, "Saved offline bookmark for anime " + animeId + ", episode " + episodeNumber + " (" + timecode + ")");
            } catch (Exception e) {
                Log.e(TAG, "Failed to save offline bookmark", e);
            }
        });
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

    private void deleteDir(java.io.File dir) {
        if (dir != null && dir.exists()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.isDirectory()) deleteDir(f);
                    else f.delete();
                }
            }
            dir.delete();
        }
    }
    
    /**
     * Закрывает executor при завершении работы
     */
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
