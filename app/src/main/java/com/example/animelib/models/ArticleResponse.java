package com.example.animelib.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class ArticleResponse {

    @SerializedName("post")
    private Post post;

    @SerializedName("data")
    private Post data;

    public Post getPost() {
        return post != null ? post : data;
    }

    public static class Post {
        @SerializedName("id")
        private Object id;

        @SerializedName("title")
        private String title;

        @SerializedName("slug_url")
        private String slugUrl;

        @SerializedName("content")
        private DocContent content;

        @SerializedName("updated_at")
        private String updatedAt;

        public String getTitle() { return title; }
        public String getSlugUrl() { return slugUrl; }
        public DocContent getContent() { return content; }
        public String getUpdatedAt() { return updatedAt; }
    }

    public static class DocContent {
        @SerializedName("type")
        private String type;

        @SerializedName("content")
        private List<Node> content;

        public String getType() { return type; }
        public List<Node> getContent() { return content; }
    }

    public static class Node {
        @SerializedName("type")
        private String type;

        @SerializedName("text")
        private String text;

        @SerializedName("attrs")
        private NodeAttrs attrs;

        @SerializedName("content")
        private List<Node> content;

        @SerializedName("marks")
        private List<Mark> marks;

        public String getType() { return type; }
        public String getText() { return text; }
        public NodeAttrs getAttrs() { return attrs; }
        public List<Node> getContent() { return content; }
        public List<Mark> getMarks() { return marks; }
    }

    public static class NodeAttrs {
        @SerializedName("level")
        private Integer level;

        @SerializedName("start")
        private Integer start;

        public int getLevel() { return level != null ? level : 2; }
        public int getStart() { return start != null ? start : 1; }
    }

    public static class Mark {
        @SerializedName("type")
        private String type;

        @SerializedName("attrs")
        private Attrs attrs;

        public String getType() { return type; }
        public Attrs getAttrs() { return attrs; }
    }

    public static class Attrs {
        @SerializedName("href")
        private String href;

        @SerializedName("target")
        private String target;

        @SerializedName("rel")
        private String rel;

        public String getHref() { return href; }
        public String getTarget() { return target; }
        public String getRel() { return rel; }
    }
}
