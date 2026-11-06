package com.example.animelib.models;

import java.io.Serializable;

public class UpdateInfo implements Serializable {
    private String tagName;
    private String releaseName;
    private String changelog;
    private String publishedAt;
    private String htmlUrl;
    private String apkUrl;
    private long apkSize;
    private boolean isBeta;
    private UpdateInfo stableUpdateInfo;

    public UpdateInfo() {}

    public UpdateInfo(String tagName, String releaseName, String changelog, String publishedAt, String htmlUrl, String apkUrl, long apkSize) {
        this(tagName, releaseName, changelog, publishedAt, htmlUrl, apkUrl, apkSize, false);
    }

    public UpdateInfo(String tagName, String releaseName, String changelog, String publishedAt, String htmlUrl, String apkUrl, long apkSize, boolean isBeta) {
        this.tagName = tagName;
        this.releaseName = releaseName;
        this.changelog = changelog;
        this.publishedAt = publishedAt;
        this.htmlUrl = htmlUrl;
        this.apkUrl = apkUrl;
        this.apkSize = apkSize;
        this.isBeta = isBeta;
    }

    public String getTagName() {
        return tagName;
    }

    public void setTagName(String tagName) {
        this.tagName = tagName;
    }

    public String getReleaseName() {
        return releaseName;
    }

    public void setReleaseName(String releaseName) {
        this.releaseName = releaseName;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public String getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(String publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }

    public String getApkUrl() {
        return apkUrl;
    }

    public void setApkUrl(String apkUrl) {
        this.apkUrl = apkUrl;
    }

    public long getApkSize() {
        return apkSize;
    }

    public void setApkSize(long apkSize) {
        this.apkSize = apkSize;
    }

    public boolean isBeta() {
        return isBeta;
    }

    public void setBeta(boolean beta) {
        isBeta = beta;
    }

    public UpdateInfo getStableUpdateInfo() {
        return stableUpdateInfo;
    }

    public void setStableUpdateInfo(UpdateInfo stableUpdateInfo) {
        this.stableUpdateInfo = stableUpdateInfo;
    }
}
