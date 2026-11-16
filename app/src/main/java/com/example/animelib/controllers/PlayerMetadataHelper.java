package com.example.animelib.controllers;

import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.AutoQualityHelper;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, stateless Java helper for episode naming, media slug resolution,
 * player matching, buffering-stall quality step-down, CDN fallback selection,
 * and diagnostic error formatting.
 */
public final class PlayerMetadataHelper {

    private static final Pattern ANIME_SLUG_PATTERN = Pattern.compile("/anime/([^/\\?#]+)");

    private PlayerMetadataHelper() {}

    public static String cleanEpisodeName(String name, String epNumber) {
        if (name == null) return "";
        String clean = name.trim();
        if (clean.isEmpty()) return "";

        clean = clean.replaceAll("\\s+", " ");

        if (epNumber != null && !epNumber.trim().isEmpty()) {
            String num = epNumber.trim();
            clean = clean.replaceAll(
                    "^(?iu)(?:серия\\s*" + Pattern.quote(num) + "|" + Pattern.quote(num) + "\\s*серия)\\s*[-:–—.]*\\s*",
                    ""
            );
        } else {
            clean = clean.replaceAll("^(?iu)(?:серия\\s*\\d+|\\d+\\s*серия)\\s*[-:–—.]*\\s*", "");
        }

        clean = clean.trim();
        if (clean.equalsIgnoreCase("серия") || clean.matches("^(?iu)серия\\s*\\d*$") || clean.matches("^\\d+$")) {
            return "";
        }
        return clean;
    }

    public static String capitalizeFirst(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return fallback;
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + trimmed.substring(1);
    }

    public static String extractMediaSlugFromUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        Matcher matcher = ANIME_SLUG_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }

        if (!trimmed.contains("/")) {
            String cleanSlug = trimmed;
            int qIdx = cleanSlug.indexOf('?');
            if (qIdx >= 0) cleanSlug = cleanSlug.substring(0, qIdx);
            int hIdx = cleanSlug.indexOf('#');
            if (hIdx >= 0) cleanSlug = cleanSlug.substring(0, hIdx);
            String res = cleanSlug.trim();
            return res.isEmpty() ? null : res;
        }

        String[] parts = trimmed.split("/");
        for (String part : parts) {
            if (part.contains("--")) {
                int qIdx = part.indexOf('?');
                if (qIdx >= 0) part = part.substring(0, qIdx);
                int hIdx = part.indexOf('#');
                if (hIdx >= 0) part = part.substring(0, hIdx);
                String res = part.trim();
                if (!res.isEmpty()) return res;
            }
        }
        return null;
    }

    public static String resolveMediaSlug(
            String infoSlugUrl,
            String animeUrl,
            String intentExtraAnimeUrl,
            String intentLegacyAnimeUrl,
            String currentAnimeId
    ) {
        // 1. Canonical slug_url from AnimeInfo if it contains full ID--slug
        if (infoSlugUrl != null && !infoSlugUrl.trim().isEmpty()) {
            String trimmed = infoSlugUrl.trim();
            String extracted = extractMediaSlugFromUrl(trimmed);
            String candidate = extracted != null ? extracted : trimmed;
            if (candidate.contains("--")) {
                return candidate;
            }
        }

        // 2. From animeUrl or intent extras
        String rawUrl = animeUrl;
        if (rawUrl == null || rawUrl.isEmpty()) {
            rawUrl = intentExtraAnimeUrl;
        }
        if (rawUrl == null || rawUrl.isEmpty()) {
            rawUrl = intentLegacyAnimeUrl;
        }

        String slug = extractMediaSlugFromUrl(rawUrl);

        // 3. Fallback to non-canonical slug_url from AnimeInfo
        if (slug == null && infoSlugUrl != null && !infoSlugUrl.trim().isEmpty()) {
            String extracted = extractMediaSlugFromUrl(infoSlugUrl);
            slug = extracted != null ? extracted : infoSlugUrl.trim();
        }

        // 4. Fallback to currentAnimeId
        if (slug == null && currentAnimeId != null && !currentAnimeId.trim().isEmpty()) {
            slug = currentAnimeId.trim();
        }

        return slug;
    }

    public static String extractResolutionFromUrl(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) {
            return null;
        }
        String lowerUrl = videoUrl.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains("2160") || lowerUrl.contains("4k")) return "2160p";
        if (lowerUrl.contains("1440") || lowerUrl.contains("2k")) return "1440p";
        if (lowerUrl.contains("1080") || lowerUrl.contains("fhd")) return "1080p";
        if (lowerUrl.contains("720") || lowerUrl.contains("hd")) return "720p";
        if (lowerUrl.contains("480")) return "480p";
        if (lowerUrl.contains("360") || lowerUrl.contains("240")) return "360p";
        return null;
    }

    public static EpisodeResponse.PlayerData createSyntheticPlayerData(String playerType, String teamName) {
        EpisodeResponse.PlayerData pd = new EpisodeResponse.PlayerData();
        pd.setPlayer(playerType != null ? playerType : "animelib");
        EpisodeResponse.Team team = new EpisodeResponse.Team();
        team.setName(teamName != null ? teamName : "Офлайн");
        pd.setTeam(team);
        return pd;
    }

    public static boolean isSyntheticPlayerData(EpisodeResponse.PlayerData pd) {
        if (pd == null) return true;
        if ("animelib".equalsIgnoreCase(pd.getPlayer())) {
            return pd.getVideo() == null
                    || pd.getVideo().getQuality() == null
                    || pd.getVideo().getQuality().isEmpty();
        }
        if ("kodik".equalsIgnoreCase(pd.getPlayer())) {
            return pd.getSrc() == null || pd.getSrc().trim().isEmpty();
        }
        return true;
    }

    public static EpisodeResponse.PlayerData findMatchingOnlinePlayerForDownloaded(
            List<EpisodeResponse.PlayerData> allPlayers,
            String targetTeamName,
            String targetPlayerType
    ) {
        if (allPlayers == null || allPlayers.isEmpty()) return null;
        String cleanTargetName = targetTeamName != null ? targetTeamName.trim() : "";

        if (!cleanTargetName.isEmpty()) {
            for (EpisodeResponse.PlayerData p : allPlayers) {
                if (p == null) continue;
                String teamName = (p.getTeam() != null && p.getTeam().getName() != null)
                        ? p.getTeam().getName().trim() : null;
                if (teamName != null && cleanTargetName.equalsIgnoreCase(teamName)) {
                    if (targetPlayerType == null || p.getPlayer() == null
                            || targetPlayerType.equalsIgnoreCase(p.getPlayer())) {
                        return p;
                    }
                }
            }
            for (EpisodeResponse.PlayerData p : allPlayers) {
                if (p == null) continue;
                String teamName = (p.getTeam() != null && p.getTeam().getName() != null)
                        ? p.getTeam().getName().trim() : null;
                if (teamName != null && cleanTargetName.equalsIgnoreCase(teamName)) {
                    return p;
                }
            }
        }

        for (EpisodeResponse.PlayerData p : allPlayers) {
            if (p != null && "animelib".equalsIgnoreCase(p.getPlayer()) && !isSyntheticPlayerData(p)) {
                return p;
            }
        }
        return allPlayers.get(0);
    }

    public static EpisodeResponse.PlayerData findBestSavedPlayerMatch(
            List<EpisodeResponse.PlayerData> players,
            String savedPlayer,
            Integer savedTeamId
    ) {
        if (players == null || players.isEmpty() || savedPlayer == null || savedTeamId == null) {
            return null;
        }
        for (EpisodeResponse.PlayerData player : players) {
            if (player != null
                    && player.getPlayer() != null
                    && player.getPlayer().equals(savedPlayer)
                    && player.getTeam() != null
                    && player.getTeam().getId() == savedTeamId) {
                return player;
            }
        }
        for (EpisodeResponse.PlayerData player : players) {
            if (player != null
                    && player.getTeam() != null
                    && player.getTeam().getId() == savedTeamId) {
                return player;
            }
        }
        return null;
    }

    public static String findNextCdnFallbackDomain(String currentVideoUrl, String currentVideoDomain) {
        if (currentVideoUrl == null
                || (!currentVideoUrl.contains("cdnlibs.org") && !currentVideoUrl.contains("imglib.info"))) {
            return null;
        }
        if (VideoUrlHelper.DOMAIN_MAIN.equals(currentVideoDomain)) {
            return VideoUrlHelper.DOMAIN_SECONDARY_1;
        } else if (VideoUrlHelper.DOMAIN_SECONDARY_1.equals(currentVideoDomain)) {
            return VideoUrlHelper.DOMAIN_SECONDARY_2;
        }
        return null;
    }

    public static String findLowerQualityForStall(List<String> availableQualities, String effectiveActiveQuality) {
        if (availableQualities == null || availableQualities.isEmpty()) {
            return null;
        }
        int currentRes = AutoQualityHelper.extractResolution(effectiveActiveQuality);
        if (currentRes <= 0) {
            currentRes = 1080;
        }

        int targetRes;
        if (currentRes > 1080) targetRes = 1080;
        else if (currentRes > 720) targetRes = 720;
        else if (currentRes > 480) targetRes = 480;
        else targetRes = 360;

        String lowerQuality = null;
        for (String q : availableQualities) {
            if (AutoQualityHelper.isAutoQuality(q) || AutoQualityHelper.isDownloadedQuality(q)) {
                continue;
            }
            int res = AutoQualityHelper.extractResolution(q);
            if (res > 0 && res <= targetRes && res < currentRes) {
                if (lowerQuality == null || res > AutoQualityHelper.extractResolution(lowerQuality)) {
                    lowerQuality = q;
                }
            }
        }
        return lowerQuality;
    }

    public static String formatPlaybackErrorDiagnostic(
            String timestamp,
            String currentVideoUrl,
            String currentAnimeId,
            String episodeNumber,
            String playerName,
            String teamName,
            String preferredQuality,
            String currentVideoDomain,
            String deviceSummary,
            String errorCodeName,
            Integer errorCode,
            String errorMessage,
            String causeClassName,
            String causeMessage,
            String httpRequestUri,
            Integer httpResponseCode,
            String httpResponseMessage,
            String stackTrace
    ) {
        StringBuilder log = new StringBuilder();
        log.append("Time: ").append(timestamp != null ? timestamp : "").append("\n");
        log.append("Current Video URL: ").append(currentVideoUrl != null ? currentVideoUrl : "null").append("\n");
        log.append("Anime ID: ").append(currentAnimeId != null ? currentAnimeId : "null").append("\n");
        if (episodeNumber != null) {
            log.append("Episode: ").append(episodeNumber).append("\n");
        }
        if (playerName != null) {
            log.append("Player: ").append(playerName).append("\n");
            if (teamName != null) {
                log.append("Team: ").append(teamName).append("\n");
            }
        }
        log.append("Preferred Quality: ").append(preferredQuality).append("\n");
        log.append("CDN Server Domain: ").append(currentVideoDomain).append("\n");
        if (deviceSummary != null) {
            log.append("Device: ").append(deviceSummary).append("\n");
        }

        if (errorCodeName != null || errorCode != null || errorMessage != null) {
            log.append("\nExoPlayer Error Code: ").append(errorCodeName)
                    .append(" (").append(errorCode != null ? errorCode : 0).append(")\n");
            log.append("Exception Message: ").append(errorMessage).append("\n");

            if (causeClassName != null) {
                log.append("Cause: ").append(causeClassName).append(": ").append(causeMessage).append("\n");
                if (httpRequestUri != null) {
                    log.append("HTTP Request URI: ").append(httpRequestUri).append("\n");
                }
                if (httpResponseCode != null) {
                    log.append("HTTP Response Code: ").append(httpResponseCode).append("\n");
                    log.append("HTTP Response Message: ").append(httpResponseMessage).append("\n");
                }
            }
            if (stackTrace != null && !stackTrace.isEmpty()) {
                log.append("\nStacktrace:\n").append(stackTrace);
            }
        }
        return log.toString();
    }
}
