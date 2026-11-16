package com.example.animelib.controllers;

import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.ui.VideoUrlHelper;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PlayerMetadataHelperTest {

    @Test
    public void cleanEpisodeName_stripsEpisodePrefixesAndNumbers() {
        assertEquals("", PlayerMetadataHelper.cleanEpisodeName(null, "1"));
        assertEquals("", PlayerMetadataHelper.cleanEpisodeName("   ", "1"));
        assertEquals("Начало пути", PlayerMetadataHelper.cleanEpisodeName("Серия 1 - Начало пути", "1"));
        assertEquals("Начало пути", PlayerMetadataHelper.cleanEpisodeName("1 серия: Начало пути", "1"));
        assertEquals("Решающий бой", PlayerMetadataHelper.cleanEpisodeName("Серия 12.   Решающий бой", "12"));
        assertEquals("", PlayerMetadataHelper.cleanEpisodeName("Серия 5", "5"));
        assertEquals("", PlayerMetadataHelper.cleanEpisodeName("серия", null));
        assertEquals("", PlayerMetadataHelper.cleanEpisodeName("42", null));
        assertEquals("Особый выпуск", PlayerMetadataHelper.cleanEpisodeName("Серия 3 — Особый выпуск", null));
    }

    @Test
    public void resolveMediaSlug_prioritizesCanonicalSlugThenUrlThenId() {
        assertEquals(
                "24653--sakamoto-days",
                PlayerMetadataHelper.resolveMediaSlug(
                        "24653--sakamoto-days",
                        "https://animelib.org/ru/anime/999--other",
                        null,
                        null,
                        "999"
                )
        );
        assertEquals(
                "1234--test-anime",
                PlayerMetadataHelper.resolveMediaSlug(
                        "short-slug",
                        "https://v5.animelib.org/ru/anime/1234--test-anime/watch?episode=2",
                        null,
                        null,
                        "1234"
                )
        );
        assertEquals(
                "555--from-intent",
                PlayerMetadataHelper.resolveMediaSlug(
                        null,
                        null,
                        "https://animelib.org/ru/anime/555--from-intent",
                        null,
                        "555"
                )
        );
        assertEquals(
                "777",
                PlayerMetadataHelper.resolveMediaSlug(null, null, null, null, "777")
        );
    }

    @Test
    public void findMatchingOnlinePlayerForDownloaded_matchesExactTeamAndTypeThenFallback() {
        EpisodeResponse.PlayerData kodikAniLibria = createPlayer("kodik", "AniLibria", "https://kodik.info/x", 0);
        EpisodeResponse.PlayerData animelibAniLibria = createPlayer("animelib", "AniLibria", null, 1080);
        EpisodeResponse.PlayerData animelibStudioBand = createPlayer("animelib", "StudioBand", null, 720);

        List<EpisodeResponse.PlayerData> players = Arrays.asList(kodikAniLibria, animelibAniLibria, animelibStudioBand);

        assertSame(
                animelibAniLibria,
                PlayerMetadataHelper.findMatchingOnlinePlayerForDownloaded(players, "anilibria", "animelib")
        );
        assertSame(
                kodikAniLibria,
                PlayerMetadataHelper.findMatchingOnlinePlayerForDownloaded(players, "AniLibria", "other")
        );
        assertSame(
                animelibAniLibria,
                PlayerMetadataHelper.findMatchingOnlinePlayerForDownloaded(players, "UnknownTeam", "animelib")
        );
    }

    @Test
    public void syntheticPlayerData_detectsMissingStreamsProperly() {
        EpisodeResponse.PlayerData synthetic = PlayerMetadataHelper.createSyntheticPlayerData("animelib", "TeamA");
        assertTrue(PlayerMetadataHelper.isSyntheticPlayerData(synthetic));
        assertTrue(PlayerMetadataHelper.isSyntheticPlayerData(null));

        EpisodeResponse.PlayerData realAnimelib = createPlayer("animelib", "TeamA", null, 1080);
        assertFalse(PlayerMetadataHelper.isSyntheticPlayerData(realAnimelib));

        EpisodeResponse.PlayerData realKodik = createPlayer("kodik", "TeamB", "https://kodik.cc/serial/1", 0);
        assertFalse(PlayerMetadataHelper.isSyntheticPlayerData(realKodik));
    }

    @Test
    public void findBestSavedPlayerMatch_prefersExactPlayerAndTeamThenCrossPlayerTeam() {
        EpisodeResponse.PlayerData p1 = createPlayerWithId("kodik", "Team1", 10);
        EpisodeResponse.PlayerData p2 = createPlayerWithId("animelib", "Team1", 10);
        EpisodeResponse.PlayerData p3 = createPlayerWithId("animelib", "Team2", 20);
        List<EpisodeResponse.PlayerData> list = Arrays.asList(p1, p2, p3);

        assertSame(p2, PlayerMetadataHelper.findBestSavedPlayerMatch(list, "animelib", 10));
        assertSame(p1, PlayerMetadataHelper.findBestSavedPlayerMatch(list, "other_player", 10));
        assertNull(PlayerMetadataHelper.findBestSavedPlayerMatch(list, "animelib", 999));
    }

    @Test
    public void findNextCdnFallbackDomain_walksFallbackChainOnlyForCdnUrls() {
        String cdnUrl = "https://video1.cdnlibs.org/video.mp4";
        assertEquals(
                VideoUrlHelper.DOMAIN_SECONDARY_1,
                PlayerMetadataHelper.findNextCdnFallbackDomain(cdnUrl, VideoUrlHelper.DOMAIN_MAIN)
        );
        assertEquals(
                VideoUrlHelper.DOMAIN_SECONDARY_2,
                PlayerMetadataHelper.findNextCdnFallbackDomain(cdnUrl, VideoUrlHelper.DOMAIN_SECONDARY_1)
        );
        assertNull(PlayerMetadataHelper.findNextCdnFallbackDomain(cdnUrl, VideoUrlHelper.DOMAIN_SECONDARY_2));
        assertNull(PlayerMetadataHelper.findNextCdnFallbackDomain("https://kodik.info/stream.m3u8", VideoUrlHelper.DOMAIN_MAIN));
    }

    @Test
    public void findLowerQualityForStall_picksImmediateLowerValidResolution() {
        List<String> qualities = Arrays.asList("Скачанный файл (1080p)", "Авто", "2160p", "1080p", "720p", "480p", "360p");
        assertEquals("1080p", PlayerMetadataHelper.findLowerQualityForStall(qualities, "2160p"));
        assertEquals("720p", PlayerMetadataHelper.findLowerQualityForStall(qualities, "1080p"));
        assertEquals("480p", PlayerMetadataHelper.findLowerQualityForStall(qualities, "720p"));
        assertEquals("360p", PlayerMetadataHelper.findLowerQualityForStall(qualities, "480p"));
        assertNull(PlayerMetadataHelper.findLowerQualityForStall(qualities, "360p"));
        assertNull(PlayerMetadataHelper.findLowerQualityForStall(Collections.emptyList(), "1080p"));
    }

    @Test
    public void formatPlaybackErrorDiagnostic_includesAllProvidedSections() {
        String log = PlayerMetadataHelper.formatPlaybackErrorDiagnostic(
                "2026-11-16 12:00:00",
                "https://video1.cdnlibs.org/test.mp4",
                "24653",
                "5",
                "animelib",
                "AniLibria",
                "1080p",
                "main",
                "Google Pixel (Android 14, API 34)",
                "ERROR_CODE_IO_BAD_HTTP_STATUS",
                2004,
                "Response code: 403",
                "HttpDataSource$InvalidResponseCodeException",
                "Forbidden",
                "https://video1.cdnlibs.org/test.mp4",
                403,
                "Forbidden",
                "stack line"
        );
        assertNotNull(log);
        assertTrue(log.contains("Anime ID: 24653"));
        assertTrue(log.contains("Episode: 5"));
        assertTrue(log.contains("Player: animelib"));
        assertTrue(log.contains("Team: AniLibria"));
        assertTrue(log.contains("HTTP Response Code: 403"));
        assertTrue(log.contains("Stacktrace:\nstack line"));
    }

    @Test
    public void extractResolutionFromUrl_recognizesStandardLabels() {
        assertEquals("2160p", PlayerMetadataHelper.extractResolutionFromUrl("https://cdn/2160.mp4"));
        assertEquals("1080p", PlayerMetadataHelper.extractResolutionFromUrl("https://cdn/fhd_stream.mp4"));
        assertEquals("720p", PlayerMetadataHelper.extractResolutionFromUrl("https://cdn/720.m3u8"));
        assertEquals("480p", PlayerMetadataHelper.extractResolutionFromUrl("https://cdn/480.mp4"));
        assertNull(PlayerMetadataHelper.extractResolutionFromUrl("https://cdn/unknown.mp4"));
    }

    @Test
    public void episodeData_parsesNameAndNumber() {
        EpisodeResponse.EpisodeData data = new EpisodeResponse.EpisodeData();
        data.setId(101);
        data.setName("Пилотная серия");
        data.setNumber("1");
        data.setSeason("1");

        assertEquals(101, data.getId());
        assertEquals("Пилотная серия", data.getName());
        assertEquals("1", data.getNumber());
        assertEquals("1", data.getSeason());
    }

    private static EpisodeResponse.PlayerData createPlayer(String playerType, String teamName, String src, int quality) {
        EpisodeResponse.PlayerData pd = new EpisodeResponse.PlayerData();
        pd.setPlayer(playerType);
        EpisodeResponse.Team team = new EpisodeResponse.Team();
        team.setName(teamName);
        pd.setTeam(team);
        pd.setSrc(src);
        if (quality > 0) {
            EpisodeResponse.QualityData qd = new EpisodeResponse.QualityData();
            qd.setQuality(quality);
            qd.setHref("/v/" + quality + ".mp4");
            EpisodeResponse.VideoData vd = new EpisodeResponse.VideoData();
            vd.setQuality(Collections.singletonList(qd));
            pd.setVideo(vd);
        }
        return pd;
    }

    private static EpisodeResponse.PlayerData createPlayerWithId(String playerType, String teamName, int teamId) {
        EpisodeResponse.PlayerData pd = createPlayer(playerType, teamName, null, 720);
        pd.getTeam().setId(teamId);
        return pd;
    }
}
