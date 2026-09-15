package com.example.animelib.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.example.animelib.data.dao.TokenDao;
import com.example.animelib.data.dao.PlayerPreferencesDao;
import com.example.animelib.data.dao.DownloadedAnimeDao;
import com.example.animelib.data.dao.OfflineBookmarkDao;
import com.example.animelib.data.dao.PendingSyncTaskDao;
import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.data.entity.PlayerPreferences;
import com.example.animelib.data.entity.DownloadedAnimeEntity;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.OfflineBookmarkEntity;
import com.example.animelib.data.entity.PendingSyncTaskEntity;

@Database(entities = {AppSettings.class, TokenEntity.class, PlayerPreferences.class, DownloadedAnimeEntity.class, DownloadedEpisodeEntity.class, OfflineBookmarkEntity.class, PendingSyncTaskEntity.class}, version = 26, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract AppSettingsDao appSettingsDao();
    public abstract TokenDao tokenDao();
    public abstract PlayerPreferencesDao playerPreferencesDao();
    public abstract DownloadedAnimeDao downloadedAnimeDao();
    public abstract OfflineBookmarkDao offlineBookmarkDao();
    public abstract PendingSyncTaskDao pendingSyncTaskDao();

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "animelib_database")
                            .fallbackToDestructiveMigration()
                            .allowMainThreadQueries()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
