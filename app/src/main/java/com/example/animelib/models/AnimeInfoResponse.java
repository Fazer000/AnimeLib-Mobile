package com.example.animelib.models;

import com.google.gson.annotations.SerializedName;

public class AnimeInfoResponse {
    private Data data;

    public Data getData() { return data; }

    public static class Data {
        private int id;
        private String name;
        private String rus_name;
        private String eng_name;
        private String slug_url;
        private Cover cover;
        private Type type;
        private Status status;
        private String releaseDate;
        private String releaseDateString;
        private Rating rating;
        private AgeRestriction ageRestriction;
        private ItemsCount items_count;
        private String shikimori_href;
        private double shiki_rate;
        private com.google.gson.JsonElement summary;
        private java.util.List<String> otherNames;
        private java.util.List<TagOrGenre> genres;
        private java.util.List<TagOrGenre> tags;
        private java.util.List<TagOrGenre> authors;
        private java.util.List<TagOrGenre> publisher;
        private com.google.gson.JsonElement views;
        private Background background;

        public int getId() { return id; }
        public String getName() { return name; }
        public String getRus_name() { return rus_name; }
        public String getEng_name() { return eng_name; }
        public String getSlug_url() { return slug_url; }
        public Cover getCover() { return cover; }
        public Type getType() { return type; }
        public Status getStatus() { return status; }
        public String getReleaseDate() { return releaseDate; }
        
        public String getReleaseDateString() {
            String res = releaseDateString;
            if (res == null || res.trim().isEmpty()) {
                if (releaseDate != null && !releaseDate.trim().isEmpty()) {
                    res = parseIsoDate(releaseDate);
                }
            }
            if (res == null || res.trim().isEmpty()) {
                return "—";
            }

            res = res.replaceAll("(?i)\\s*г\\.?$", "");

            return res.replace("января", "янв")
                      .replace("январь", "янв")
                      .replace("февраля", "фев")
                      .replace("февраль", "фев")
                      .replace("марта", "мар")
                      .replace("март", "мар")
                      .replace("апреля", "апр")
                      .replace("апрель", "апр")
                      .replace("июня", "июн")
                      .replace("июнь", "июн")
                      .replace("июля", "июл")
                      .replace("июль", "июл")
                      .replace("августа", "авг")
                      .replace("август", "авг")
                      .replace("сентября", "сент")
                      .replace("сентябрь", "сент")
                      .replace("октября", "окт")
                      .replace("октябрь", "окт")
                      .replace("ноября", "нояб")
                      .replace("ноябрь", "нояб")
                      .replace("декабря", "дек")
                      .replace("декабрь", "дек");
        }

        private String parseIsoDate(String raw) {
            if (raw == null) return "";
            String[] parts = raw.split("-");
            if (parts.length == 3) {
                String year = parts[0];
                String month = getShortMonthName(parts[1]);
                String day = parts[2];
                try {
                    int d = Integer.parseInt(day);
                    day = String.valueOf(d);
                } catch (Exception ignored) {}
                return day + " " + month + " " + year;
            } else if (parts.length == 2) {
                String year = parts[0];
                String month = getShortMonthName(parts[1]);
                return month + " " + year;
            } else if (parts.length == 1) {
                return parts[0];
            }
            return raw;
        }

        private String getShortMonthName(String mStr) {
            switch (mStr) {
                case "01": return "янв";
                case "02": return "фев";
                case "03": return "мар";
                case "04": return "апр";
                case "05": return "мая";
                case "06": return "июн";
                case "07": return "июл";
                case "08": return "авг";
                case "09": return "сент";
                case "10": return "окт";
                case "11": return "нояб";
                case "12": return "дек";
                default: return "";
            }
        }
        public Rating getRating() { return rating; }
        public AgeRestriction getAgeRestriction() { return ageRestriction; }
        public ItemsCount getItems_count() { return items_count; }
        public String getShikimori_href() { return shikimori_href; }
        public double getShiki_rate() { return shiki_rate; }
        public com.google.gson.JsonElement getSummaryElement() { return summary; }
        
        public String getSummaryText() {
            if (summary == null || summary.isJsonNull()) return "";
            if (summary.isJsonPrimitive()) {
                return summary.getAsString();
            }
            if (summary.isJsonObject()) {
                StringBuilder sb = new StringBuilder();
                extractContentText(summary.getAsJsonObject(), sb);
                return sb.toString().trim();
            }
            return "";
        }

        private void extractContentText(com.google.gson.JsonElement element, StringBuilder sb) {
            if (element == null || element.isJsonNull()) return;
            if (element.isJsonObject()) {
                com.google.gson.JsonObject obj = element.getAsJsonObject();
                String type = obj.has("type") && obj.get("type").isJsonPrimitive() ? obj.get("type").getAsString() : "";
                if ("text".equals(type) && obj.has("text") && obj.get("text").isJsonPrimitive()) {
                    sb.append(obj.get("text").getAsString());
                }
                if (obj.has("content") && obj.get("content").isJsonArray()) {
                    for (com.google.gson.JsonElement child : obj.getAsJsonArray("content")) {
                        extractContentText(child, sb);
                    }
                    if ("paragraph".equals(type)) {
                        sb.append("\n\n");
                    }
                }
            } else if (element.isJsonArray()) {
                for (com.google.gson.JsonElement item : element.getAsJsonArray()) {
                    extractContentText(item, sb);
                }
            }
        }

        public java.util.List<String> getOtherNames() { return otherNames; }
        public java.util.List<TagOrGenre> getGenres() { return genres; }
        public java.util.List<TagOrGenre> getTags() { return tags; }
        public java.util.List<TagOrGenre> getAuthors() { return authors; }
        public java.util.List<TagOrGenre> getPublisher() { return publisher; }
        public Background getBackground() { return background; }

        public String getFormattedViews() {
            if (views == null || views.isJsonNull()) return "";
            if (views.isJsonPrimitive()) {
                try {
                    long v = views.getAsLong();
                    if (v <= 0) return "";
                    if (v >= 1000000) {
                        return String.format(java.util.Locale.US, "%.1fM просмотров", v / 1000000.0);
                    } else if (v >= 1000) {
                        return String.format(java.util.Locale.US, "%.1fK просмотров", v / 1000.0);
                    }
                    return v + " просмотров";
                } catch (Exception ignored) {
                    return views.getAsString();
                }
            }
            if (views.isJsonObject()) {
                com.google.gson.JsonObject obj = views.getAsJsonObject();
                if (obj.has("short") && obj.get("short").isJsonPrimitive()) {
                    return obj.get("short").getAsString() + " просмотров";
                }
                if (obj.has("formated") && obj.get("formated").isJsonPrimitive()) {
                    return obj.get("formated").getAsString() + " просмотров";
                }
                if (obj.has("total") && obj.get("total").isJsonPrimitive()) {
                    try {
                        long v = obj.get("total").getAsLong();
                        if (v >= 1000000) {
                            return String.format(java.util.Locale.US, "%.1fM просмотров", v / 1000000.0);
                        } else if (v >= 1000) {
                            return String.format(java.util.Locale.US, "%.1fK просмотров", v / 1000.0);
                        }
                        return v + " просмотров";
                    } catch (Exception ignored) {}
                }
            }
            return "";
        }
    }

    public static class Background {
        private String filename;
        private String url;

        public String getFilename() { return filename; }
        public String getUrl() { return url; }
    }

    public static class TagOrGenre {
        private int id;
        private String name;
        private String russian;
        private Cover cover;
        private com.google.gson.JsonElement avatar;
        private com.google.gson.JsonElement image;
        private com.google.gson.JsonElement picture;

        public int getId() { return id; }
        public String getName() { return name != null ? name : (russian != null ? russian : ""); }
        public String getRussian() { return russian != null ? russian : name; }

        public String getAvatarUrl() {
            if (cover != null && cover.getDefaultUrl() != null && !cover.getDefaultUrl().isEmpty()) {
                return cover.getDefaultUrl();
            }
            if (cover != null && cover.getThumbnail() != null && !cover.getThumbnail().isEmpty()) {
                return cover.getThumbnail();
            }
            if (cover != null && cover.getMd() != null && !cover.getMd().isEmpty()) {
                return cover.getMd();
            }
            String url = parseJsonUrl(avatar);
            if (url != null && !url.isEmpty()) return url;
            url = parseJsonUrl(image);
            if (url != null && !url.isEmpty()) return url;
            url = parseJsonUrl(picture);
            if (url != null && !url.isEmpty()) return url;
            return "";
        }

        private String parseJsonUrl(com.google.gson.JsonElement elem) {
            if (elem == null || elem.isJsonNull()) return "";
            if (elem.isJsonPrimitive()) return elem.getAsString();
            if (elem.isJsonObject()) {
                com.google.gson.JsonObject obj = elem.getAsJsonObject();
                if (obj.has("default") && obj.get("default").isJsonPrimitive()) {
                    return obj.get("default").getAsString();
                }
                if (obj.has("thumbnail") && obj.get("thumbnail").isJsonPrimitive()) {
                    return obj.get("thumbnail").getAsString();
                }
                if (obj.has("url") && obj.get("url").isJsonPrimitive()) {
                    return obj.get("url").getAsString();
                }
                if (obj.has("md") && obj.get("md").isJsonPrimitive()) {
                    return obj.get("md").getAsString();
                }
            }
            return "";
        }
    }

    public static class Cover {
        private String filename;
        private String thumbnail;
        
        @SerializedName("default")
        private String defaultUrl; // "default" is a keyword in Java
        
        private String md;

        public String getFilename() { return filename; }
        public String getThumbnail() { return thumbnail; }
        public String getDefaultUrl() { return defaultUrl; }
        public String getMd() { return md; }
    }

    public static class Type {
        private int id;
        private String label;

        public int getId() { return id; }
        public String getLabel() { return label; }
    }

    public static class Status {
        private int id;
        private String label;

        public int getId() { return id; }
        public String getLabel() { return label; }
    }

    public static class Rating {
        private String average;
        
        @SerializedName("averageFormated")
        private String averageFormated;
        
        private int votes;
        
        @SerializedName("votesFormated")
        private String votesFormated;

        public String getAverage() { return average; }
        public String getAverageFormated() { return averageFormated; }
        public int getVotes() { return votes; }
        public String getVotesFormated() { return votesFormated; }
    }

    public static class AgeRestriction {
        private int id;
        private String label;

        public int getId() { return id; }
        public String getLabel() { return label; }
    }

    public static class ItemsCount {
        private int uploaded;
        private int total;

        public int getUploaded() { return uploaded; }
        public int getTotal() { return total; }
    }
}


