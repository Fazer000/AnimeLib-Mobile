package com.example.animelib.util;

import android.content.Context;
import android.content.SharedPreferences;

public class SiteUtils {

    private static Boolean sIsOtherRegion = null;
    private static final String PREF_NAME = "site_utils_prefs";
    private static final String KEY_IS_OTHER_REGION = "is_other_region";

    public static void setSavedRegionOther(Context context, boolean isOther) {
        sIsOtherRegion = isOther;
        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_IS_OTHER_REGION, isOther).apply();
            } catch (Exception ignored) {}
        }
    }

    public static boolean isSavedRegionOther(Context context) {
        if (sIsOtherRegion != null) {
            return sIsOtherRegion;
        }
        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                sIsOtherRegion = prefs.getBoolean(KEY_IS_OTHER_REGION, false);
                return sIsOtherRegion;
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static boolean isOtherRegion(String url, Context context) {
        if (url == null || url.trim().isEmpty()) {
            return isSavedRegionOther(context);
        }
        String lower = url.toLowerCase();

        // RanobeLib only uses ranobelib.me in production.
        // Do not let ranobelib.me override/reset user region preference to CIS.
        if (lower.contains("ranobelib")) {
            return isSavedRegionOther(context);
        }

        if (lower.contains("v5.")) {
            setSavedRegionOther(context, false);
            return false;
        }
        if (lower.contains(".me")) {
            setSavedRegionOther(context, false);
            return false;
        }
        if (lower.contains(".org")) {
            setSavedRegionOther(context, true);
            return true;
        }

        return isSavedRegionOther(context);
    }

    public static boolean isOtherRegion(String url) {
        return isOtherRegion(url, null);
    }

    public static String getSiteKey(String url) {
        if (url == null) return "animelib";
        String lower = url.toLowerCase();
        if (lower.contains("mangalib")) return "mangalib";
        if (lower.contains("ranobelib")) return "ranobelib";
        if (lower.contains("hentailib")) return "hentailib";
        if (lower.contains("slashlib")) return "slashlib";
        return "animelib";
    }

    public static String getSiteName(String siteKey) {
        if (siteKey == null) return "AnimeLib";
        switch (siteKey.toLowerCase()) {
            case "mangalib": return "MangaLib";
            case "ranobelib": return "RanobeLib";
            case "hentailib": return "HentaiLib";
            case "slashlib": return "SlashLib";
            case "animelib":
            default: return "AnimeLib";
        }
    }

    public static String getUrlForSiteAndRegion(String siteKey, boolean isOtherRegion) {
        if (siteKey == null) siteKey = "animelib";
        switch (siteKey.toLowerCase()) {
            case "mangalib":
                return isOtherRegion ? "https://mangalib.org" : "https://mangalib.me";
            case "ranobelib":
                return "https://ranobelib.me";
            case "hentailib":
                return isOtherRegion ? "https://hentailib.org" : "https://hentailib.me";
            case "slashlib":
                return isOtherRegion ? "https://slashlib.org" : "https://slashlib.me";
            case "animelib":
            default:
                return isOtherRegion ? "https://animelib.org" : "https://v5.animelib.org";
        }
    }

    public static String getDomainHost(String url) {
        if (url == null) return "";
        String clean = url.replace("https://", "").replace("http://", "");
        int slashIdx = clean.indexOf('/');
        if (slashIdx != -1) {
            clean = clean.substring(0, slashIdx);
        }
        return clean;
    }
}
