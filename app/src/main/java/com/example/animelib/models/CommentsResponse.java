package com.example.animelib.models;

import java.util.List;

public class CommentsResponse {
    private Data data;
    private Meta meta;

    public Data getData() { return data; }
    public void setData(Data data) { this.data = data; }
    public Meta getMeta() { return meta; }
    public void setMeta(Meta meta) { this.meta = meta; }

    public static class Data {
        private List<CommentItem> replies;
        private List<CommentItem> root;

        public List<CommentItem> getReplies() { return replies; }
        public void setReplies(List<CommentItem> replies) { this.replies = replies; }
        public List<CommentItem> getRoot() { return root; }
        public void setRoot(List<CommentItem> root) { this.root = root; }
    }

    public static class Meta {
        private boolean has_next_page;
        private int page;
        private int current_page;
        private int per_page;
        private Integer total;
        private Integer total_pages;
        private Integer last_page;

        public boolean isHas_next_page() { return has_next_page; }
        public int getPage() { return page > 0 ? page : (current_page > 0 ? current_page : 1); }
        public int getCurrent_page() { return getPage(); }
        public int getPer_page() { return per_page; }
        public Integer getTotal() { return total; }
        public Integer getTotal_pages() {
            if (total_pages != null && total_pages > 0) return total_pages;
            if (last_page != null && last_page > 0) return last_page;
            if (total != null && total > 0 && per_page > 0) {
                return (int) Math.ceil((double) total / per_page);
            }
            return null;
        }
        public Integer getLast_page() { return getTotal_pages(); }
    }

    public static class CommentItem {
        private long id;
        private Long root_id;
        private Long parent_comment;
        private int comment_level;
        private String post_page;
        private String comment; // HTML строка
        private String created_at;
        private long created_at_ts;
        private String relation_type;
        private long relation_id;
        private User user;
        private Votes votes;
        private StickyInfo sticky_info;
        private boolean isSticky;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public Long getRoot_id() { return root_id; }
        public void setRoot_id(Long root_id) { this.root_id = root_id; }
        public Long getParent_comment() { return parent_comment; }
        public void setParent_comment(Long parent_comment) { this.parent_comment = parent_comment; }
        public int getComment_level() { return comment_level; }
        public void setComment_level(int comment_level) { this.comment_level = comment_level; }
        public String getPost_page() { return post_page; }
        public void setPost_page(String post_page) { this.post_page = post_page; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
        public String getCreated_at() { return created_at; }
        public void setCreated_at(String created_at) { this.created_at = created_at; }
        public long getCreated_at_ts() { return created_at_ts; }
        public void setCreated_at_ts(long created_at_ts) { this.created_at_ts = created_at_ts; }
        public String getRelation_type() { return relation_type; }
        public void setRelation_type(String relation_type) { this.relation_type = relation_type; }
        public long getRelation_id() { return relation_id; }
        public void setRelation_id(long relation_id) { this.relation_id = relation_id; }
        public User getUser() { return user; }
        public void setUser(User user) { this.user = user; }
        public Votes getVotes() { return votes; }
        public void setVotes(Votes votes) { this.votes = votes; }
        public StickyInfo getSticky_info() { return sticky_info; }
        public void setSticky_info(StickyInfo sticky_info) { this.sticky_info = sticky_info; }
        public boolean isSticky() { return isSticky || sticky_info != null; }
        public void setSticky(boolean sticky) { isSticky = sticky; }
    }

    public static class StickyInfo {
        private Object team;
        private String sticky_at;
        private boolean without_limit;

        public Object getTeam() { return team; }
        public String getSticky_at() { return sticky_at; }
        public boolean isWithout_limit() { return without_limit; }
    }

    public static class User {
        private String username;
        private long id;
        private Avatar avatar;
        private Premium premium;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public Avatar getAvatar() { return avatar; }
        public void setAvatar(Avatar avatar) { this.avatar = avatar; }
        public Premium getPremium() { return premium; }
        public void setPremium(Premium premium) { this.premium = premium; }
    }

    public static class Avatar {
        private String filename;
        private String url;

        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public static class Premium {
        private boolean enabled;

        public boolean isEnabled() { return enabled; }
    }

    public static class Votes {
        private int up;
        private int down;
        private Object user;

        public int getUp() { return up; }
        public int getDown() { return down; }
        public Object getUser() { return user; }

        public void setUp(int up) { this.up = up; }
        public void setDown(int down) { this.down = down; }
        public void setUser(Object user) { this.user = user; }
    }
}


