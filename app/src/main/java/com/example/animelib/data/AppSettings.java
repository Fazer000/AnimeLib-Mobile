package com.example.animelib.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_settings")
public class AppSettings {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String siteUrl;
    private boolean enable4K = false;
    private boolean enableAmbientLight = true;
    private boolean enableSurroundSound = true;
    private int surroundMode = 0; // 0 = Cinema 3D, 1 = Concert 3D, 2 = Voice 3D, 3 = Classic 5.1, 4 = Extreme 3D
    private float surroundSpatialWidth = 1.0f;
    private float surroundDialogueBoost = 1.0f;
    private float surroundBassBoost = 1.0f;
    private float surroundTrebleBoost = 1.0f;
    private boolean autoPlay = true;
    private int longSkipDuration = 85; // seconds
    private int themeMode = 0; // 0 = light, 1 = dark, 2 = system
    private boolean subtitlesEnabled = true;
    private String subtitleFormat = "ass"; // "ass", "vtt", "auto"
    private float subtitleTextSize = 18f;
    private int subtitleTextColor = 0xFFFFFFFF; // White
    private int subtitleBackgroundColor = 0x00000000; // Transparent
    private int subtitleEdgeType = 1; // 1 = OUTLINE, 2 = DROP_SHADOW, 0 = NONE
    private int subtitleEdgeColor = 0xFF000000; // Black

    private float filterBrightness = 0f;
    private float filterContrast = 100f;
    private float filterSaturation = 100f;
    private float filterGamma = 1.0f;
    private float filterHue = 0f;

    public static final int REPLIES_UNLIMITED = -1;
    private boolean disableCommentsInPlayer = false;
    private int commentRepliesLimit = 3;

    public AppSettings() {
    }


    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getSiteUrl() {
        return siteUrl;
    }

    public void setSiteUrl(String siteUrl) {
        this.siteUrl = siteUrl;
    }

    public boolean isEnable4K() {
        return enable4K;
    }

    public void setEnable4K(boolean enable4K) {
        this.enable4K = enable4K;
    }

    public boolean isEnableAmbientLight() {
        return enableAmbientLight;
    }

    public void setEnableAmbientLight(boolean enableAmbientLight) {
        this.enableAmbientLight = enableAmbientLight;
    }

    public boolean isEnableSurroundSound() {
        return enableSurroundSound;
    }

    public void setEnableSurroundSound(boolean enableSurroundSound) {
        this.enableSurroundSound = enableSurroundSound;
    }

    public int getSurroundMode() {
        return surroundMode;
    }

    public void setSurroundMode(int surroundMode) {
        this.surroundMode = surroundMode;
    }

    public float getSurroundSpatialWidth() {
        return surroundSpatialWidth;
    }

    public void setSurroundSpatialWidth(float surroundSpatialWidth) {
        this.surroundSpatialWidth = surroundSpatialWidth;
    }

    public float getSurroundDialogueBoost() {
        return surroundDialogueBoost;
    }

    public void setSurroundDialogueBoost(float surroundDialogueBoost) {
        this.surroundDialogueBoost = surroundDialogueBoost;
    }

    public float getSurroundBassBoost() {
        return surroundBassBoost;
    }

    public void setSurroundBassBoost(float surroundBassBoost) {
        this.surroundBassBoost = surroundBassBoost;
    }

    public float getSurroundTrebleBoost() {
        return surroundTrebleBoost;
    }

    public void setSurroundTrebleBoost(float surroundTrebleBoost) {
        this.surroundTrebleBoost = surroundTrebleBoost;
    }

    public boolean isAutoPlay() {
        return autoPlay;
    }

    public void setAutoPlay(boolean autoPlay) {
        this.autoPlay = autoPlay;
    }

    public int getLongSkipDuration() {
        return longSkipDuration;
    }

    public void setLongSkipDuration(int longSkipDuration) {
        this.longSkipDuration = longSkipDuration;
    }

    public int getThemeMode() {
        return themeMode;
    }

    public void setThemeMode(int themeMode) {
        this.themeMode = themeMode;
    }

    public boolean isSubtitlesEnabled() {
        return subtitlesEnabled;
    }

    public void setSubtitlesEnabled(boolean subtitlesEnabled) {
        this.subtitlesEnabled = subtitlesEnabled;
    }

    public String getSubtitleFormat() {
        return subtitleFormat != null ? subtitleFormat : "ass";
    }

    public void setSubtitleFormat(String subtitleFormat) {
        this.subtitleFormat = subtitleFormat;
    }

    public float getSubtitleTextSize() {
        return subtitleTextSize > 0 ? subtitleTextSize : 18f;
    }

    public void setSubtitleTextSize(float subtitleTextSize) {
        this.subtitleTextSize = subtitleTextSize;
    }

    public int getSubtitleTextColor() {
        return subtitleTextColor;
    }

    public void setSubtitleTextColor(int subtitleTextColor) {
        this.subtitleTextColor = subtitleTextColor;
    }

    public int getSubtitleBackgroundColor() {
        return subtitleBackgroundColor;
    }

    public void setSubtitleBackgroundColor(int subtitleBackgroundColor) {
        this.subtitleBackgroundColor = subtitleBackgroundColor;
    }

    public int getSubtitleEdgeType() {
        return subtitleEdgeType;
    }

    public void setSubtitleEdgeType(int subtitleEdgeType) {
        this.subtitleEdgeType = subtitleEdgeType;
    }

    public int getSubtitleEdgeColor() {
        return subtitleEdgeColor;
    }

    public void setSubtitleEdgeColor(int subtitleEdgeColor) {
        this.subtitleEdgeColor = subtitleEdgeColor;
    }

    public float getFilterBrightness() { return filterBrightness; }
    public void setFilterBrightness(float filterBrightness) { this.filterBrightness = filterBrightness; }

    public float getFilterContrast() { return filterContrast > 0 ? filterContrast : 100f; }
    public void setFilterContrast(float filterContrast) { this.filterContrast = filterContrast; }

    public float getFilterSaturation() { return filterSaturation >= 0 ? filterSaturation : 100f; }
    public void setFilterSaturation(float filterSaturation) { this.filterSaturation = filterSaturation; }

    public float getFilterGamma() { return filterGamma > 0 ? filterGamma : 1.0f; }
    public void setFilterGamma(float filterGamma) { this.filterGamma = filterGamma; }

    public float getFilterHue() { return filterHue; }
    public void setFilterHue(float filterHue) { this.filterHue = filterHue; }

    public boolean isDisableCommentsInPlayer() {
        return disableCommentsInPlayer;
    }

    public void setDisableCommentsInPlayer(boolean disableCommentsInPlayer) {
        this.disableCommentsInPlayer = disableCommentsInPlayer;
    }

    public int getCommentRepliesLimit() {
        return commentRepliesLimit != 0 ? commentRepliesLimit : 3;
    }

    public void setCommentRepliesLimit(int commentRepliesLimit) {
        this.commentRepliesLimit = commentRepliesLimit;
    }
}
