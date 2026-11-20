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
import com.example.animelib.data.dao.OpeningFingerprintDao;
import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.data.entity.PlayerPreferences;
import com.example.animelib.data.entity.DownloadedAnimeEntity;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.OfflineBookmarkEntity;
import com.example.animelib.data.entity.PendingSyncTaskEntity;
import com.example.animelib.data.entity.OpeningFingerprintEntity;
import com.example.animelib.data.entity.DetectedOpeningEntity;

@Database(entities = {AppSettings.class, TokenEntity.class, PlayerPreferences.class, DownloadedAnimeEntity.class, DownloadedEpisodeEntity.class, OfflineBookmarkEntity.class, PendingSyncTaskEntity.class, OpeningFingerprintEntity.class, DetectedOpeningEntity.class}, version = 32, exportSchema = false)
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

    public static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE app_settings ADD COLUMN autoSkipOpening INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE app_settings ADD COLUMN autoSkipEnding INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE app_settings ADD COLUMN autoSkipSplashscreen INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE app_settings ADD COLUMN autoSkipCompilation INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `opening_fingerprints` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`animeId` TEXT NOT NULL, "
                    + "`sourceEpisodeId` INTEGER NOT NULL, "
                    + "`fingerprintBase64` TEXT NOT NULL, "
                    + "`frameCount` INTEGER NOT NULL, "
                    + "`bandCount` INTEGER NOT NULL, "
                    + "`hopMs` INTEGER NOT NULL, "
                    + "`coreStartFrame` INTEGER NOT NULL, "
                    + "`coreEndFrame` INTEGER NOT NULL, "
                    + "`skipDurationSec` INTEGER NOT NULL, "
                    + "`createdAt` INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_opening_fingerprints_animeId` ON `opening_fingerprints` (`animeId`)");

            db.execSQL("CREATE TABLE IF NOT EXISTS `detected_openings` ("
                    + "`cacheKey` TEXT NOT NULL, "
                    + "`animeId` TEXT NOT NULL, "
                    + "`episodeId` INTEGER NOT NULL, "
                    + "`fromSec` INTEGER NOT NULL, "
                    + "`toSec` INTEGER NOT NULL, "
                    + "`confidence` REAL NOT NULL, "
                    + "`fingerprintId` INTEGER NOT NULL, "
                    + "`detectedAt` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`cacheKey`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_detected_openings_animeId` ON `detected_openings` (`animeId`)");
        }
    };

    public abstract AppSettingsDao appSettingsDao();
    public abstract TokenDao tokenDao();
    public abstract PlayerPreferencesDao playerPreferencesDao();
    public abstract DownloadedAnimeDao downloadedAnimeDao();
    public abstract OfflineBookmarkDao offlineBookmarkDao();
    public abstract PendingSyncTaskDao pendingSyncTaskDao();
    public abstract OpeningFingerprintDao openingFingerprintDao();

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "animelib_database")
                            .addMigrations(MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32)
                            .fallbackToDestructiveMigration()
                            .allowMainThreadQueries()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
