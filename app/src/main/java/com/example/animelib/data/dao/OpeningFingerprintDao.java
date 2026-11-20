package com.example.animelib.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.animelib.data.entity.DetectedOpeningEntity;
import com.example.animelib.data.entity.OpeningFingerprintEntity;

import java.util.List;

@Dao
public interface OpeningFingerprintDao {

    @Query("SELECT * FROM opening_fingerprints WHERE animeId = :animeId ORDER BY createdAt DESC")
    List<OpeningFingerprintEntity> getFingerprintsForAnime(String animeId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertFingerprint(OpeningFingerprintEntity entity);

    @Query("DELETE FROM opening_fingerprints WHERE id = :id")
    void deleteFingerprintById(long id);

    @Query("DELETE FROM opening_fingerprints WHERE animeId = :animeId AND id NOT IN "
            + "(SELECT id FROM opening_fingerprints WHERE animeId = :animeId ORDER BY createdAt DESC LIMIT :keepCount)")
    void pruneOldFingerprintsForAnime(String animeId, int keepCount);

    @Query("SELECT * FROM detected_openings WHERE cacheKey = :cacheKey LIMIT 1")
    DetectedOpeningEntity getDetectedOpening(String cacheKey);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertDetectedOpening(DetectedOpeningEntity entity);

    @Query("DELETE FROM detected_openings WHERE animeId = :animeId AND fromSec < 0")
    void clearNegativeCacheForAnime(String animeId);

    @Query("DELETE FROM detected_openings WHERE fromSec < 0")
    void clearAllNegativeCache();
}
