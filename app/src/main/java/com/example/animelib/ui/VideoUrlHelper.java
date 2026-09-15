package com.example.animelib.ui;

import android.util.Log;
import android.webkit.WebView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class for extracting video URLs from anime pages
 */
public class VideoUrlHelper {
    private static final String TAG = "VideoUrlHelper";

    public static final String DOMAIN_MAIN = "main";
    public static final String DOMAIN_SECONDARY_1 = "secondary_1";
    public static final String DOMAIN_SECONDARY_2 = "secondary_2";

    public static final String URL_MAIN = "https://video1.cdnlibs.org/.%D0%B0s";
    public static final String URL_SECONDARY_1 = "https://video2.cdnlibs.org";
    public static final String URL_SECONDARY_2 = "https://video1.imglib.info";

    public static String getBaseUrlForDomain(String domain) {
        if (domain == null) return URL_MAIN;
        switch (domain) {
            case DOMAIN_SECONDARY_1:
                return URL_SECONDARY_1;
            case DOMAIN_SECONDARY_2:
                return URL_SECONDARY_2;
            case DOMAIN_MAIN:
            default:
                return URL_MAIN;
        }
    }

    public static String getDomainDisplayName(String domain) {
        if (domain == null) return "Основной";
        switch (domain) {
            case DOMAIN_SECONDARY_1:
                return "Резервный 1";
            case DOMAIN_SECONDARY_2:
                return "Резервный 2";
            case DOMAIN_MAIN:
            default:
                return "Основной";
        }
    }

    public static String getDomainFullDescription(String domain) {
        if (domain == null) return "Основной (video1.cdnlibs.org/.%D0%B0s)";
        switch (domain) {
            case DOMAIN_SECONDARY_1:
                return "Резервный 1 (video2.cdnlibs.org)";
            case DOMAIN_SECONDARY_2:
                return "Резервный 2 (video1.imglib.info)";
            case DOMAIN_MAIN:
            default:
                return "Основной (video1.cdnlibs.org/.%D0%B0s)";
        }
    }

    /**
     * Достраивает относительную ссылку на видео доменом CDN
     */
    public static String toAbsoluteVideoUrl(String href) {
        return toAbsoluteVideoUrl(href, DOMAIN_MAIN);
    }

    /**
     * Достраивает относительную или меняет домен абсолютной ссылки в зависимости от сервера
     */
    public static String toAbsoluteVideoUrl(String href, String domain) {
        if (href == null || href.isEmpty()) {
            return null;
        }
        String baseUrl = getBaseUrlForDomain(domain);

        if (href.startsWith("http://") || href.startsWith("https://")) {
            String relativePath = extractRelativePathFromCdnUrl(href);
            if (relativePath != null) {
                if (!relativePath.startsWith("/")) {
                    relativePath = "/" + relativePath;
                }
                return baseUrl + relativePath;
            }
            return href;
        }

        if (href.startsWith("/")) {
            return baseUrl + href;
        } else {
            return baseUrl + "/" + href;
        }
    }

    /**
     * Извлекает относительный путь из известного URL видеосервера AnimeLib
     */
    public static String extractRelativePathFromCdnUrl(String fullUrl) {
        if (fullUrl == null) return null;

        String[] prefixes = new String[] {
            "https://video1.cdnlibs.org/.%D0%B0s",
            "https://video1.cdnlibs.org/.\u0430s",
            "http://video1.cdnlibs.org/.%D0%B0s",
            "http://video1.cdnlibs.org/.\u0430s",
            "https://video1.cdnlibs.org",
            "http://video1.cdnlibs.org",
            "https://video2.cdnlibs.org",
            "http://video2.cdnlibs.org",
            "https://video1.imglib.info",
            "http://video1.imglib.info"
        };

        for (String prefix : prefixes) {
            if (fullUrl.startsWith(prefix)) {
                String path = fullUrl.substring(prefix.length());
                if (path.startsWith("/.%D0%B0s")) {
                    path = path.substring("/.%D0%B0s".length());
                } else if (path.startsWith("/.\u0430s")) {
                    path = path.substring("/.\u0430s".length());
                } else if (path.startsWith("/.%D0%B0")) {
                    path = path.substring("/.%D0%B0".length());
                }
                return path;
            }
        }
        return null;
    }

    /**
     * Extract anime identifier from href (including slug)
     * @param href The href attribute from the anime button
     * @return Anime identifier like "24653--sakamoto-days-part-2-anime" or null
     */
    public static String extractAnimeIdentifier(String href) {
        if (href == null || href.isEmpty()) {
            return null;
        }

        Pattern pattern = Pattern.compile("(?:/ru)?/anime/([^/?#]+)");
        Matcher matcher = pattern.matcher(href);

        if (matcher.find()) {
            String animeIdentifier = matcher.group(1);
            Log.d(TAG, "Found anime identifier: " + animeIdentifier);
            return animeIdentifier;
        }

        if (href.contains("--")) {
            String[] parts = href.split("/");
            for (String part : parts) {
                if (part.contains("--")) {
                    if (part.contains("?")) part = part.substring(0, part.indexOf("?"));
                    if (part.contains("#")) part = part.substring(0, part.indexOf("#"));
                    return part;
                }
            }
        }

        Log.w(TAG, "Could not extract anime identifier from href: " + href);
        return null;
    }

    /**
     * Extract numeric anime ID from full identifier
     * @param animeIdentifier Full identifier like "24653--sakamoto-days-part-2-anime"
     * @return Numeric ID like "24653" or null
     */
    public static String extractAnimeId(String animeIdentifier) {
        if (animeIdentifier == null || animeIdentifier.isEmpty()) {
            return null;
        }

        Pattern pattern = Pattern.compile("^(\\d+)");
        Matcher matcher = pattern.matcher(animeIdentifier);

        if (matcher.find()) {
            String animeId = matcher.group(1);
            Log.d(TAG, "Extracted numeric anime ID: " + animeId);
            return animeId;
        }

        return null;
    }

    private static String getBaseDomainFromContext(String currentUrl) {
        if (currentUrl != null && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) {
            try {
                java.net.URI uri = new java.net.URI(currentUrl);
                return uri.getScheme() + "://" + uri.getHost();
            } catch (Exception ignored) {}
        }
        return "https://v5.animelib.org";
    }

    /**
     * Resolves an anime URL from a clicked link/href and current page context.
     * Guarantees that the resulting URL contains the anime slug/ID if available in currentUrl.
     */
    public static String resolveAnimeUrl(String href, String currentUrl) {
        if (href == null || href.isEmpty()) {
            return currentUrl != null ? currentUrl : "";
        }

        String cleanHref = href.trim();
        String fallbackDomain = getBaseDomainFromContext(currentUrl);

        // 1. If href itself contains an anime identifier (e.g. contains "--" or "/anime/")
        String hrefIdentifier = extractAnimeIdentifier(cleanHref);
        if (hrefIdentifier != null) {
            if (cleanHref.startsWith("http://") || cleanHref.startsWith("https://")) {
                return cleanHref;
            } else if (cleanHref.startsWith("/")) {
                return fallbackDomain + cleanHref;
            } else {
                return fallbackDomain + "/" + cleanHref;
            }
        }

        // 2. If href does NOT contain anime identifier, try to get base anime URL from currentUrl
        String currentIdentifier = currentUrl != null ? extractAnimeIdentifier(currentUrl) : null;
        if (currentIdentifier != null && currentUrl != null) {
            String cleanCurrent = currentUrl.trim();
            if (cleanCurrent.contains("?")) {
                cleanCurrent = cleanCurrent.substring(0, cleanCurrent.indexOf("?"));
            }
            if (cleanCurrent.contains("#")) {
                cleanCurrent = cleanCurrent.substring(0, cleanCurrent.indexOf("#"));
            }
            if (cleanCurrent.endsWith("/watch")) {
                cleanCurrent = cleanCurrent.substring(0, cleanCurrent.length() - "/watch".length());
            } else if (cleanCurrent.endsWith("/watch/")) {
                cleanCurrent = cleanCurrent.substring(0, cleanCurrent.length() - "/watch/".length());
            }
            if (cleanCurrent.endsWith("/")) {
                cleanCurrent = cleanCurrent.substring(0, cleanCurrent.length() - 1);
            }

            String baseAnimeUrl;
            if (cleanCurrent.startsWith("http://") || cleanCurrent.startsWith("https://")) {
                baseAnimeUrl = cleanCurrent;
            } else if (cleanCurrent.startsWith("/")) {
                baseAnimeUrl = fallbackDomain + cleanCurrent;
            } else {
                baseAnimeUrl = fallbackDomain + "/" + cleanCurrent;
            }

            String targetWatchUrl = baseAnimeUrl.endsWith("/watch") ? baseAnimeUrl : baseAnimeUrl + "/watch";
            if (cleanHref.contains("?")) {
                String query = cleanHref.substring(cleanHref.indexOf("?"));
                return targetWatchUrl + query;
            }
            return targetWatchUrl;
        }

        // Fallback: make href absolute
        if (cleanHref.startsWith("http://") || cleanHref.startsWith("https://")) {
            return cleanHref;
        } else if (cleanHref.startsWith("/")) {
            return fallbackDomain + cleanHref;
        } else {
            return fallbackDomain + "/" + cleanHref;
        }
    }

    /**
     * Extract video URL from anime watch page href
     * @param href The href attribute from the anime button
     * @param currentUrl Current page URL for context
     * @return Video URL or null if not found
     */
    public static String extractVideoUrl(String href, String currentUrl) {
        String animeIdentifier = extractAnimeIdentifier(href);
        if (animeIdentifier != null) {
            String animeId = extractAnimeId(animeIdentifier);
            Log.d(TAG, "Anime identifier: " + animeIdentifier + ", Numeric ID: " + animeId);
            // Return null for now - actual video URL will be fetched via API
            return null;
        }
        return null;
    }

    /**
     * Get video headers for the request
     * @param referer The referer URL
     * @return Map of headers
     */
    public static java.util.Map<String, String> getVideoHeaders(String referer) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5");
        headers.put("Accept-Language", "en-US,en;q=0.5");
        headers.put("Connection", "keep-alive");
        if (referer != null) {
            headers.put("Referer", referer);
        }
        headers.put("Sec-Fetch-Dest", "video");
        headers.put("Sec-Fetch-Mode", "no-cors");
        headers.put("Sec-Fetch-Site", "cross-site");
        headers.put("User-Agent", getRandomUserAgent());
        return headers;
    }

    /**
     * Get a random user agent for video requests
     * @return Random user agent string
     */
    public static String getRandomUserAgent() {
        String[] userAgents = {
            "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.159 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-A525F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/93.0.4577.62 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-N986B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.71 Mobile Safari/537.36"
        };
        return userAgents[(int) (Math.random() * userAgents.length)];
    }

    /**
     * Validate if URL is a supported video format
     * @param url Video URL
     * @return true if supported
     */
    public static boolean isSupportedVideoFormat(String url) {
        if (url == null) return false;

        String lowerUrl = url.toLowerCase();
        return lowerUrl.endsWith(".mp4") ||
               lowerUrl.endsWith(".webm") ||
               lowerUrl.endsWith(".m3u8") ||
               lowerUrl.endsWith(".mpd") ||
               lowerUrl.contains(".m3u8") ||
               lowerUrl.contains(".mpd");
    }
}
