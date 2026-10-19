package com.example.animelib.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Модель ответа API Shikimori для франшизы (/api/animes/{id}/franchise)
 */
public class FranchiseResponse {

    @SerializedName("links")
    private List<LinkItem> links;

    @SerializedName("nodes")
    private List<NodeItem> nodes;

    @SerializedName("current_id")
    private long currentId;

    public List<LinkItem> getLinks() {
        return links;
    }

    public List<NodeItem> getNodes() {
        return nodes;
    }

    public long getCurrentId() {
        return currentId;
    }

    public static class LinkItem {
        @SerializedName("id")
        private long id;

        @SerializedName("source_id")
        private long sourceId;

        @SerializedName("target_id")
        private long targetId;

        @SerializedName("source")
        private int source;

        @SerializedName("target")
        private int target;

        @SerializedName("weight")
        private int weight;

        @SerializedName("relation")
        private String relation;

        public long getId() { return id; }
        public long getSourceId() { return sourceId; }
        public long getTargetId() { return targetId; }
        public int getSource() { return source; }
        public int getTarget() { return target; }
        public int getWeight() { return weight; }
        public String getRelation() { return relation; }
    }

    public static class NodeItem {
        @SerializedName("id")
        private long id;

        @SerializedName("date")
        private long date; // Unix timestamp в секундах

        @SerializedName("name")
        private String name;

        @SerializedName("image_url")
        private String imageUrl;

        @SerializedName("url")
        private String url;

        @SerializedName("year")
        private int year;

        @SerializedName("kind")
        private String kind;

        @SerializedName("weight")
        private int weight;

        public long getId() { return id; }
        public long getDate() { return date; }
        public String getName() { return name != null ? name : ""; }
        public String getImageUrl() { return imageUrl; }
        public String getUrl() { return url; }
        public int getYear() { return year; }
        public String getKind() { return kind != null ? kind : ""; }
        public int getWeight() { return weight; }

        /**
         * Преобразует относительный URL обложки в полный
         */
        public String getFullImageUrl() {
            if (imageUrl == null || imageUrl.trim().isEmpty()) {
                return "";
            }
            if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                return imageUrl;
            }
            if (imageUrl.startsWith("//")) {
                return "https:" + imageUrl;
            }
            if (imageUrl.startsWith("/")) {
                return "https://shikimori.one" + imageUrl;
            }
            return imageUrl;
        }

        /**
         * Получает локализованную метку типа/формата
         */
        public String getFormattedKind() {
            if (kind == null) return "";
            switch (kind.toLowerCase().trim()) {
                case "tv":
                case "tv сериал":
                    return "TV Сериал";
                case "movie":
                case "фильм":
                    return "Фильм";
                case "ova":
                    return "OVA";
                case "ona":
                    return "ONA";
                case "special":
                case "спецвыпуск":
                case "tv спецвыпуск":
                    return "Спешл";
                case "music":
                    return "Клип";
                case "pv":
                case "реклама":
                    return "Промо";
                default:
                    return kind;
            }
        }
    }
}
