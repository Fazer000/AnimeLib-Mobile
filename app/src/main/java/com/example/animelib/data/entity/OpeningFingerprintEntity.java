package com.example.animelib.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Хранит компактный спектральный аудио-отпечаток опенинга,
 * запомненный после ручного пропуска пользователем.
 */
@Entity(
        tableName = "opening_fingerprints",
        indices = {@Index("animeId")}
)
public class OpeningFingerprintEntity {

    @PrimaryKey(autoGenerate = true)
    private long id;

    @NonNull
    private String animeId = "";

    private int sourceEpisodeId;

    @NonNull
    private String fingerprintBase64 = "";

    private int frameCount;
    private int bandCount;
    private int hopMs;
    private int coreStartFrame;
    private int coreEndFrame;
    private int skipDurationSec;
    private long createdAt;

    public OpeningFingerprintEntity() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @NonNull
    public String getAnimeId() {
        return animeId;
    }

    public void setAnimeId(@NonNull String animeId) {
        this.animeId = animeId;
    }

    public int getSourceEpisodeId() {
        return sourceEpisodeId;
    }

    public void setSourceEpisodeId(int sourceEpisodeId) {
        this.sourceEpisodeId = sourceEpisodeId;
    }

    @NonNull
    public String getFingerprintBase64() {
        return fingerprintBase64;
    }

    public void setFingerprintBase64(@NonNull String fingerprintBase64) {
        this.fingerprintBase64 = fingerprintBase64;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public void setFrameCount(int frameCount) {
        this.frameCount = frameCount;
    }

    public int getBandCount() {
        return bandCount;
    }

    public void setBandCount(int bandCount) {
        this.bandCount = bandCount;
    }

    public int getHopMs() {
        return hopMs;
    }

    public void setHopMs(int hopMs) {
        this.hopMs = hopMs;
    }

    public int getCoreStartFrame() {
        return coreStartFrame;
    }

    public void setCoreStartFrame(int coreStartFrame) {
        this.coreStartFrame = coreStartFrame;
    }

    public int getCoreEndFrame() {
        return coreEndFrame;
    }

    public void setCoreEndFrame(int coreEndFrame) {
        this.coreEndFrame = coreEndFrame;
    }

    public int getSkipDurationSec() {
        return skipDurationSec;
    }

    public void setSkipDurationSec(int skipDurationSec) {
        this.skipDurationSec = skipDurationSec;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
