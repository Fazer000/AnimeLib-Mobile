package com.example.animelib.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Модель для ответа API поиска аниме
 */
public class SearchResponse {
    @SerializedName("data")
    private List<AnimeSearchItem> data;
    
    @SerializedName("links")
    private Links links;
    
    @SerializedName("meta")
    private Meta meta;
    
    public List<AnimeSearchItem> getData() {
        return data;
    }
    
    public Links getLinks() {
        return links;
    }
    
    public Meta getMeta() {
        return meta;
    }
    
    public static class AnimeSearchItem {
        @SerializedName("id")
        private int id;
        
        @SerializedName("name")
        private String name;
        
        @SerializedName("rus_name")
        private String rusName;
        
        @SerializedName("eng_name")
        private String engName;
        
        @SerializedName("model")
        private String model;
        
        @SerializedName("slug")
        private String slug;
        
        @SerializedName("slug_url")
        private String slugUrl;
        
        @SerializedName("cover")
        private Cover cover;
        
        @SerializedName("ageRestriction")
        private AgeRestriction ageRestriction;
        
        @SerializedName("site")
        private int site;
        
        @SerializedName("type")
        private Type type;
        
        @SerializedName("releaseDate")
        private String releaseDate;
        
        @SerializedName("rating")
        private Rating rating;
        
        @SerializedName("status")
        private Status status;
        
        @SerializedName("edition")
        private Edition edition;
        
        @SerializedName("releaseDateString")
        private String releaseDateString;
        
        public AnimeSearchItem() {}
        
        public void setData(List<AnimeSearchItem> items) {}
        
        // Getters
        public int getId() { return id; }
        public String getName() { return name; }
        public String getRusName() { return rusName; }
        public String getEngName() { return engName; }
        public String getModel() { return model; }
        public String getSlug() { return slug; }
        public String getSlugUrl() { return slugUrl; }
        public Cover getCover() { return cover; }
        public AgeRestriction getAgeRestriction() { return ageRestriction; }
        public int getSite() { return site; }
        public void setSite(int site) { this.site = site; }
        public Type getType() { return type; }
        public String getReleaseDate() { return releaseDate; }
        public Rating getRating() { return rating; }
        public Status getStatus() { return status; }
        public Edition getEdition() { return edition; }
        public String getReleaseDateString() { return releaseDateString; }
    }
    
    public static class Edition {
        @SerializedName("id")
        private int id;
        
        @SerializedName("slug")
        private String slug;
        
        @SerializedName("name")
        private String name;
        
        @SerializedName("short_name")
        private String shortName;
        
        public int getId() { return id; }
        public String getSlug() { return slug; }
        public String getName() { return name; }
        public String getShortName() { return shortName; }
    }
    
    public static class Cover {
        @SerializedName("filename")
        private String filename;
        
        @SerializedName("thumbnail")
        private String thumbnail;
        
        @SerializedName("default")
        private String defaultUrl;
        
        @SerializedName("md")
        private String md;
        
        public String getFilename() { return filename; }
        public String getThumbnail() { return thumbnail; }
        public String getDefaultUrl() { return defaultUrl; }
        public String getMd() { return md; }
    }
    
    public static class AgeRestriction {
        @SerializedName("id")
        private int id;
        
        @SerializedName("label")
        private String label;
        
        public int getId() { return id; }
        public String getLabel() { return label; }
    }
    
    public static class Type {
        @SerializedName("id")
        private int id;
        
        @SerializedName("label")
        private String label;
        
        public int getId() { return id; }
        public String getLabel() { return label; }
    }
    
    public static class Rating {
        @SerializedName("average")
        private String average;
        
        @SerializedName("averageFormated")
        private String averageFormated;
        
        @SerializedName("votes")
        private int votes;
        
        @SerializedName("votesFormated")
        private String votesFormated;
        
        @SerializedName("user")
        private int user;
        
        public String getAverage() { return average; }
        public String getAverageFormated() { return averageFormated; }
        public int getVotes() { return votes; }
        public String getVotesFormated() { return votesFormated; }
        public int getUser() { return user; }
    }
    
    public static class Status {
        @SerializedName("id")
        private int id;
        
        @SerializedName("label")
        private String label;
        
        public int getId() { return id; }
        public String getLabel() { return label; }
    }
    
    public static class Links {
        @SerializedName("first")
        private String first;
        
        @SerializedName("last")
        private String last;
        
        @SerializedName("prev")
        private String prev;
        
        @SerializedName("next")
        private String next;
        
        public String getFirst() { return first; }
        public String getLast() { return last; }
        public String getPrev() { return prev; }
        public String getNext() { return next; }
    }
    
    public static class Meta {
        @SerializedName("current_page")
        private int currentPage;
        
        @SerializedName("from")
        private Integer from;
        
        @SerializedName("path")
        private String path;
        
        @SerializedName("per_page")
        private int perPage;
        
        @SerializedName("to")
        private Integer to;
        
        @SerializedName("seed")
        private String seed;
        
        public int getCurrentPage() { return currentPage; }
        public Integer getFrom() { return from; }
        public String getPath() { return path; }
        public int getPerPage() { return perPage; }
        public Integer getTo() { return to; }
        public String getSeed() { return seed; }
    }
}

