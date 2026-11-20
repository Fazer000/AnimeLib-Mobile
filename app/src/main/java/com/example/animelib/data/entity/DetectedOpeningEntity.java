package com.example.animelib.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Кэш найденного (или не найденного, fromSec = -1) аудио-опенинга для конкретного эпизода.
 */
@Entity(
        tableName = "detected_openings",
        indices = {@Index("animeId")}
)
public class DetectedOpeningEntity {

    @PrimaryKey
    @NonNull
    private String cacheKey = "";

    @NonNull
    private String animeId = "";

    private int episodeId;
    private int fromSec;
    private int toSec;
    private float confidence;
    private long fingerprintId;
    private long detectedAt;

    public DetectedOpeningEntity() {
    }

    @NonNull
    public static String buildCacheKey(@NonNull String animeId, int episodeId) {
        return animeId + "_" + episodeId;
    }

    @NonNull
    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(@NonNull String cacheKey) {
        this.cacheKey = cacheKey;
    }

    @NonNull
    public String getAnimeId() {
        return animeId;
    }

    public void setAnimeId(@NonNull String animeId) {
        this.animeId = animeId;
    }

    public int getEpisodeId() {
        return episodeId;
    }

    public void setEpisodeId(int episodeId) {
        this.episodeId = episodeId;
    }

    public int getFromSec() {
        return fromSec;
    }

    public void setFromSec(int fromSec) {
        this.fromSec = fromSec;
    }

    public int getToSec() {
        return toSec;
    }

    public void setToSec(int toSec) {
        this.toSec = toSec;
    }

    public float getConfidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    public long getFingerprintId() {
        return fingerprintId;
    }

    public void setFingerprintId(long fingerprintId) {
        this.fingerprintId = fingerprintId;
    }

    public long getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(long detectedAt) {
        this.detectedAt = detectedAt;
    }

    public boolean isValidDetection() {
        return fromSec >= 0 && toSec > fromSec;
    }
}
