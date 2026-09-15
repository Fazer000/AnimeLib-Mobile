package com.example.animelib.models;

public class SiteOption {
    private final String key;
    private final String name;
    private final String url;
    private final int iconResId;

    public SiteOption(String key, String name, String url, int iconResId) {
        this.key = key;
        this.name = name;
        this.url = url;
        this.iconResId = iconResId;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public int getIconResId() {
        return iconResId;
    }
}
