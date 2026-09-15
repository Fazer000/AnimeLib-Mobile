package com.example.animelib.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class EpisodeResponse {
    private EpisodeData data;

    public EpisodeResponse() {}

    public EpisodeData getData() {
        return data;
    }

    public void setData(EpisodeData data) {
        this.data = data;
    }

    public static class EpisodeData {
        private int id;
        private List<PlayerData> players;

        public EpisodeData() {}

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public List<PlayerData> getPlayers() {
            return players;
        }

        public void setPlayers(List<PlayerData> players) {
            this.players = players;
        }
    }

    public static String parseImageUrl(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) {
            String str = element.getAsString();
            return formatUrlString(str);
        } else if (element.isJsonObject()) {
            com.google.gson.JsonObject obj = element.getAsJsonObject();
            String[] keys = new String[]{"thumbnail", "default", "md", "url", "href", "src", "filename", "path", "image", "photo"};
            for (String key : keys) {
                if (obj.has(key) && !obj.get(key).isJsonNull()) {
                    String val = obj.get(key).getAsString();
                    String formatted = formatUrlString(val);
                    if (formatted != null) return formatted;
                }
            }
        }
        return null;
    }

    private static String formatUrlString(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        str = str.trim();
        if (str.startsWith("//")) {
            return "https:" + str;
        } else if (str.startsWith("/")) {
            return "https://cover.imglib.info" + str;
        } else if (!str.startsWith("http://") && !str.startsWith("https://")) {
            if (str.contains("/") || str.contains(".")) {
                return "https://cover.imglib.info/" + str;
            }
        }
        return str;
    }

    public static class PlayerData {
        @SerializedName("id")
        private int id;
        private String player;
        @SerializedName("translation_type")
        private TranslationType translationType;
        private Team team;
        @SerializedName("author")
        private Team author;
        private String src;
        private VideoData video;
        private List<TimecodeData> timecode;
        @SerializedName("subtitles")
        private List<SubtitleData> subtitles;
        @SerializedName("video_domain")
        private String videoDomain;

        @SerializedName("cover")
        private com.google.gson.JsonElement cover;
        @SerializedName("picture")
        private com.google.gson.JsonElement picture;
        @SerializedName("avatar")
        private com.google.gson.JsonElement avatar;
        @SerializedName("logo")
        private com.google.gson.JsonElement logo;
        @SerializedName("icon")
        private com.google.gson.JsonElement icon;

        public PlayerData() {}

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getPlayer() {
            return player;
        }

        public void setPlayer(String player) {
            this.player = player;
        }

        public TranslationType getTranslationType() {
            return translationType;
        }

        public void setTranslationType(TranslationType translationType) {
            this.translationType = translationType;
        }

        public Team getTeam() {
            if (team != null) return team;
            return author;
        }

        public void setTeam(Team team) {
            this.team = team;
        }

        public String getSrc() {
            return src;
        }

        public void setSrc(String src) {
            this.src = src;
        }

        public VideoData getVideo() {
            return video;
        }

        public void setVideo(VideoData video) {
            this.video = video;
        }

        public List<TimecodeData> getTimecode() {
            return timecode;
        }

        public void setTimecode(List<TimecodeData> timecode) {
            this.timecode = timecode;
        }

        public List<SubtitleData> getSubtitles() {
            return subtitles;
        }

        public void setSubtitles(List<SubtitleData> subtitles) {
            this.subtitles = subtitles;
        }

        public boolean isSubtitles() {
            if (subtitles != null && !subtitles.isEmpty()) {
                return true;
            }
            if (translationType != null) {
                if (translationType.getId() == 1) {
                    return true;
                }
                String label = translationType.getLabel();
                if (label != null) {
                    String lower = label.toLowerCase();
                    if (lower.contains("субтитр") || lower.contains("sub")) {
                        return true;
                    }
                }
            }
            Team t = getTeam();
            if (t != null && t.getName() != null) {
                String teamLower = t.getName().toLowerCase();
                if (teamLower.contains("субтитр") || teamLower.contains("subtitles") || teamLower.contains("(sub)")) {
                    return true;
                }
            }
            if (player != null && player.toLowerCase().contains("sub")) {
                return true;
            }
            if (src != null && src.toLowerCase().contains("sub")) {
                return true;
            }
            return false;
        }

        public String getVideoDomain() {
            return videoDomain;
        }

        public void setVideoDomain(String videoDomain) {
            this.videoDomain = videoDomain;
        }

        public String getCoverUrl() {
            Team t = getTeam();
            if (t != null) {
                String url = t.getCoverUrl();
                if (url != null && !url.isEmpty()) return url;
            }
            if (translationType != null) {
                String url = translationType.getCoverUrl();
                if (url != null && !url.isEmpty()) return url;
            }
            String url = parseImageUrl(cover);
            if (url != null) return url;
            url = parseImageUrl(picture);
            if (url != null) return url;
            url = parseImageUrl(avatar);
            if (url != null) return url;
            url = parseImageUrl(logo);
            if (url != null) return url;
            url = parseImageUrl(icon);
            return url;
        }
    }

    public static class TranslationType {
        private int id;
        private String label;

        @SerializedName("cover")
        private com.google.gson.JsonElement cover;
        @SerializedName("picture")
        private com.google.gson.JsonElement picture;
        @SerializedName("avatar")
        private com.google.gson.JsonElement avatar;
        @SerializedName("logo")
        private com.google.gson.JsonElement logo;
        @SerializedName("icon")
        private com.google.gson.JsonElement icon;
        @SerializedName("image")
        private com.google.gson.JsonElement image;

        public TranslationType() {}

        public int getId() { return id; }

        public void setId(int id) { this.id = id; }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getCoverUrl() {
            String url = parseImageUrl(cover);
            if (url != null) return url;
            url = parseImageUrl(picture);
            if (url != null) return url;
            url = parseImageUrl(avatar);
            if (url != null) return url;
            url = parseImageUrl(logo);
            if (url != null) return url;
            url = parseImageUrl(icon);
            if (url != null) return url;
            url = parseImageUrl(image);
            return url;
        }
    }

    public static class Team {
        private int id;
        private String name;
        @SerializedName("slug")
        private String slug;

        @SerializedName("cover")
        private com.google.gson.JsonElement cover;
        @SerializedName("picture")
        private com.google.gson.JsonElement picture;
        @SerializedName("avatar")
        private com.google.gson.JsonElement avatar;
        @SerializedName("logo")
        private com.google.gson.JsonElement logo;
        @SerializedName("icon")
        private com.google.gson.JsonElement icon;
        @SerializedName("image")
        private com.google.gson.JsonElement image;
        @SerializedName("photo")
        private com.google.gson.JsonElement photo;
        @SerializedName("poster")
        private com.google.gson.JsonElement poster;

        public Team() {}

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSlug() {
            return slug;
        }

        public void setSlug(String slug) {
            this.slug = slug;
        }

        public String getCoverUrl() {
            String url = parseImageUrl(cover);
            if (url != null) return url;
            url = parseImageUrl(picture);
            if (url != null) return url;
            url = parseImageUrl(avatar);
            if (url != null) return url;
            url = parseImageUrl(logo);
            if (url != null) return url;
            url = parseImageUrl(icon);
            if (url != null) return url;
            url = parseImageUrl(image);
            if (url != null) return url;
            url = parseImageUrl(photo);
            if (url != null) return url;
            url = parseImageUrl(poster);
            return url;
        }
    }

    public static class VideoData {
        private List<QualityData> quality;

        public VideoData() {}

        public List<QualityData> getQuality() {
            return quality;
        }

        public void setQuality(List<QualityData> quality) {
            this.quality = quality;
        }
    }

    public static class QualityData {
        private String href;
        private int quality;

        public QualityData() {}

        public String getHref() {
            return href;
        }

        public void setHref(String href) {
            this.href = href;
        }

        public int getQuality() {
            return quality;
        }

        public void setQuality(int quality) {
            this.quality = quality;
        }
    }

    public static class TimecodeData {
        private String type;
        private int from;
        private int to;

        public TimecodeData() {}

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getFrom() {
            return from;
        }

        public void setFrom(int from) {
            this.from = from;
        }

        public int getTo() {
            return to;
        }

        public void setTo(int to) {
            this.to = to;
        }
    }

    public static class SubtitleData implements java.io.Serializable {
        private int id;
        private String format;
        private String name;
        private String filename;
        private String src;

        public SubtitleData() {}

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public String getSrc() {
            return src;
        }

        public void setSrc(String src) {
            this.src = src;
        }
    }
}
