package com.example.animelib.models;

public class WatchStatusItem {
    private final String label;
    private final Object id; // Integer (e.g. 21..27) or String ("other")
    private final String colorHex;

    public WatchStatusItem(String label, Object id, String colorHex) {
        this.label = label;
        this.id = id;
        this.colorHex = colorHex;
    }

    public String getLabel() {
        return label;
    }

    public Object getId() {
        return id;
    }

    public String getColorHex() {
        return colorHex;
    }
}
