package com.example.animelib.data;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

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

@Database(entities = {AppSettings.class, TokenEntity.class, PlayerPreferences.class, DownloadedAnimeEntity.class, DownloadedEpisodeEntity.class, OfflineBookmarkEntity.class, PendingSyncTaskEntity.class}, version = 30, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public static final Migration MIGRATION_28_29 = new Migration(28, 29) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE downloaded_episodes ADD COLUMN subtitlesJson TEXT");
            db.execSQL("ALTER TABLE downloaded_episodes ADD COLUMN timecodesJson TEXT");
            db.execSQL("ALTER TABLE downloaded_episodes ADD COLUMN season TEXT");
            db.execSQL("ALTER TABLE downloaded_episodes ADD COLUMN translationType TEXT");
            db.execSQL("ALTER TABLE downloaded_episodes ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0");

            db.execSQL("ALTER TABLE downloaded_anime ADD COLUMN description TEXT");
            db.execSQL("ALTER TABLE downloaded_anime ADD COLUMN releaseYear TEXT");
            db.execSQL("ALTER TABLE downloaded_anime ADD COLUMN animeType TEXT");
            db.execSQL("ALTER TABLE downloaded_anime ADD COLUMN rating TEXT");
            db.execSQL("ALTER TABLE downloaded_anime ADD COLUMN totalEpisodes INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_29_30 = new Migration(29, 30) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE app_settings ADD COLUMN videoResizeMode INTEGER NOT NULL DEFAULT 0");
        }
    };

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
                            .addMigrations(MIGRATION_28_29, MIGRATION_29_30)
                            .fallbackToDestructiveMigration()
                            .allowMainThreadQueries()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
