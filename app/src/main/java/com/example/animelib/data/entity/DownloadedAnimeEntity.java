package com.example.animelib.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "downloaded_anime")
public class DownloadedAnimeEntity {
    @PrimaryKey
    @NonNull
    private String animeId;
    private String title;
    private String posterUrl;
    private long savedAt;
    private String description;
    private String releaseYear;
    private String animeType;
    private String rating;
    private int totalEpisodes;

    public DownloadedAnimeEntity(@NonNull String animeId, String title, String posterUrl, long savedAt,
                                 String description, String releaseYear, String animeType,
                                 String rating, int totalEpisodes) {
        this.animeId = animeId;
        this.title = title;
        this.posterUrl = posterUrl;
        this.savedAt = savedAt;
        this.description = description;
        this.releaseYear = releaseYear;
        this.animeType = animeType;
        this.rating = rating;
        this.totalEpisodes = totalEpisodes;
    }

    @androidx.room.Ignore
    public DownloadedAnimeEntity(@NonNull String animeId, String title, String posterUrl, long savedAt) {
        this(animeId, title, posterUrl, savedAt, null, null, null, null, 0);
    }

    @NonNull
    public String getAnimeId() { return animeId; }
    public void setAnimeId(@NonNull String animeId) { this.animeId = animeId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPosterUrl() { return posterUrl; }
    public void setPosterUrl(String posterUrl) { this.posterUrl = posterUrl; }

    public long getSavedAt() { return savedAt; }
    public void setSavedAt(long savedAt) { this.savedAt = savedAt; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getReleaseYear() { return releaseYear; }
    public void setReleaseYear(String releaseYear) { this.releaseYear = releaseYear; }

    public String getAnimeType() { return animeType; }
    public void setAnimeType(String animeType) { this.animeType = animeType; }

    public String getRating() { return rating; }
    public void setRating(String rating) { this.rating = rating; }

    public int getTotalEpisodes() { return totalEpisodes; }
    public void setTotalEpisodes(int totalEpisodes) { this.totalEpisodes = totalEpisodes; }
}
