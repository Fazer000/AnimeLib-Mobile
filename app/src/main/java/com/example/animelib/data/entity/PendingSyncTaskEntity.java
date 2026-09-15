package com.example.animelib.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "pending_sync_tasks")
public class PendingSyncTaskEntity {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private String taskType; // "VIEW" or "BOOKMARK"
    private String animeId;
    private int playerId;
    private String mediaSlug;
    private int episodeId;
    private int teamId;
    private int episodeNumber;
    private String timecode;
    private String statusId;
    private long createdAt;
    private int retryCount;

    public PendingSyncTaskEntity() {
    }

    public static PendingSyncTaskEntity createViewTask(String animeId, int playerId) {
        PendingSyncTaskEntity task = new PendingSyncTaskEntity();
        task.setTaskType("VIEW");
        task.setAnimeId(animeId);
        task.setPlayerId(playerId);
        task.setCreatedAt(System.currentTimeMillis());
        task.setRetryCount(0);
        return task;
    }

    public static PendingSyncTaskEntity createBookmarkTask(String mediaSlug, int episodeId, int teamId, int episodeNumber, String timecode) {
        return createBookmarkTask(mediaSlug, episodeId, teamId, episodeNumber, timecode, null);
    }

    public static PendingSyncTaskEntity createBookmarkTask(String mediaSlug, int episodeId, int teamId, int episodeNumber, String timecode, String statusId) {
        PendingSyncTaskEntity task = new PendingSyncTaskEntity();
        task.setTaskType("BOOKMARK");
        task.setMediaSlug(mediaSlug);
        task.setEpisodeId(episodeId);
        task.setTeamId(teamId);
        task.setEpisodeNumber(episodeNumber);
        task.setTimecode(timecode);
        task.setStatusId(statusId);
        task.setCreatedAt(System.currentTimeMillis());
        task.setRetryCount(0);
        return task;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getAnimeId() {
        return animeId;
    }

    public void setAnimeId(String animeId) {
        this.animeId = animeId;
    }

    public int getPlayerId() {
        return playerId;
    }

    public void setPlayerId(int playerId) {
        this.playerId = playerId;
    }

    public String getMediaSlug() {
        return mediaSlug;
    }

    public void setMediaSlug(String mediaSlug) {
        this.mediaSlug = mediaSlug;
    }

    public int getEpisodeId() {
        return episodeId;
    }

    public void setEpisodeId(int episodeId) {
        this.episodeId = episodeId;
    }

    public int getTeamId() {
        return teamId;
    }

    public void setTeamId(int teamId) {
        this.teamId = teamId;
    }

    public int getEpisodeNumber() {
        return episodeNumber;
    }

    public void setEpisodeNumber(int episodeNumber) {
        this.episodeNumber = episodeNumber;
    }

    public String getTimecode() {
        return timecode;
    }

    public void setTimecode(String timecode) {
        this.timecode = timecode;
    }

    public String getStatusId() {
        return statusId;
    }

    public void setStatusId(String statusId) {
        this.statusId = statusId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
}
