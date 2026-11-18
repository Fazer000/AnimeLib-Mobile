package com.example.animelib.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.animelib.data.entity.DownloadedAnimeEntity;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;

import java.util.List;

@Dao
public interface DownloadedAnimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAnime(DownloadedAnimeEntity anime);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertEpisode(DownloadedEpisodeEntity episode);

    @Query("SELECT * FROM downloaded_anime ORDER BY savedAt DESC")
    LiveData<List<DownloadedAnimeEntity>> getAllDownloadedAnimeLiveData();

    @Query("SELECT * FROM downloaded_anime ORDER BY savedAt DESC")
    List<DownloadedAnimeEntity> getAllDownloadedAnimeSync();

    @Query("SELECT * FROM downloaded_anime WHERE animeId = :animeId LIMIT 1")
    DownloadedAnimeEntity getAnimeByIdSync(String animeId);

    @Query("SELECT * FROM downloaded_episodes WHERE animeId = :animeId ORDER BY CAST(episodeNumber AS INTEGER) ASC")
    LiveData<List<DownloadedEpisodeEntity>> getEpisodesForAnimeLiveData(String animeId);

    @Query("SELECT * FROM downloaded_episodes WHERE animeId = :animeId ORDER BY CAST(episodeNumber AS INTEGER) ASC")
    List<DownloadedEpisodeEntity> getEpisodesForAnimeSync(String animeId);

    @Query("SELECT * FROM downloaded_episodes ORDER BY CAST(episodeNumber AS INTEGER) ASC")
    List<DownloadedEpisodeEntity> getAllEpisodesSync();

    @Query("SELECT * FROM downloaded_episodes WHERE id = :id LIMIT 1")
    DownloadedEpisodeEntity getEpisodeById(String id);

    @Query("DELETE FROM downloaded_episodes WHERE id = :id")
    void deleteEpisodeById(String id);

    @Query("DELETE FROM downloaded_episodes WHERE animeId = :animeId")
    void deleteEpisodesForAnime(String animeId);

    @Query("DELETE FROM downloaded_anime WHERE animeId = :animeId")
    void deleteAnimeById(String animeId);

    @Query("SELECT COUNT(*) FROM downloaded_episodes WHERE animeId = :animeId")
    int getEpisodeCountForAnime(String animeId);

    @Query("SELECT * FROM downloaded_episodes WHERE animeId = :animeId AND episodeNumber = :episodeNumber AND teamName = :teamName LIMIT 1")
    DownloadedEpisodeEntity findDownloadedEpisode(String animeId, String episodeNumber, String teamName);

    @Query("SELECT * FROM downloaded_episodes WHERE animeId = :animeId AND episodeId = :episodeId AND teamName = :teamName LIMIT 1")
    DownloadedEpisodeEntity findDownloadedEpisodeByEpisodeId(String animeId, int episodeId, String teamName);

    @Query("SELECT * FROM downloaded_episodes WHERE animeId = :animeId AND episodeId = :episodeId LIMIT 1")
    DownloadedEpisodeEntity findDownloadedEpisodeByEpisodeIdOnly(String animeId, int episodeId);

    @Query("SELECT * FROM downloaded_episodes WHERE localFilePath = :path LIMIT 1")
    DownloadedEpisodeEntity findEpisodeByPath(String path);
}
