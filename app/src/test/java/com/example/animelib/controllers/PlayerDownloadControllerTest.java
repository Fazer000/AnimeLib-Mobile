package com.example.animelib.controllers;

import com.example.animelib.adapters.PlayerOptionsAdapter;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PlayerDownloadControllerTest {

    @Test
    public void findDownloadedEpisodeForVoiceover_returnsNullWhenSelectedTeamNotDownloaded() {
        EpisodesListResponse.EpisodeItem ep1 = createEpisode(101, "1");
        DownloadedEpisodeEntity dlAnilib = createDownloaded("1_101_AniLiberty", 101, "1", "AniLiberty", "animelib");
        List<DownloadedEpisodeEntity> downloads = Collections.singletonList(dlAnilib);

        EpisodeResponse.PlayerData shiza = createPlayer("animelib", "SHIZA Project");
        assertNull(PlayerDownloadController.findDownloadedEpisodeForVoiceover(downloads, ep1, shiza));
    }

    @Test
    public void findDownloadedEpisodeForVoiceover_prefersExactTeamAndPlayerType() {
        EpisodesListResponse.EpisodeItem ep1 = createEpisode(101, "1");
        DownloadedEpisodeEntity dlKodik = createDownloaded("1_101_kodik", 101, "1", "AniLibria", "kodik");
        DownloadedEpisodeEntity dlAnimelib = createDownloaded("1_101_animelib", 101, "1", "AniLibria", "animelib");
        List<DownloadedEpisodeEntity> downloads = Arrays.asList(dlKodik, dlAnimelib);

        EpisodeResponse.PlayerData animelibPlayer = createPlayer("animelib", " anilibria ");
        EpisodeResponse.PlayerData kodikPlayer = createPlayer("kodik", "AniLibria");

        assertSame(dlAnimelib, PlayerDownloadController.findDownloadedEpisodeForVoiceover(downloads, ep1, animelibPlayer));
        assertSame(dlKodik, PlayerDownloadController.findDownloadedEpisodeForVoiceover(downloads, ep1, kodikPlayer));

        List<DownloadedEpisodeEntity> animelibOnly = Collections.singletonList(dlAnimelib);
        assertNull(PlayerDownloadController.findDownloadedEpisodeForVoiceover(animelibOnly, ep1, kodikPlayer));
    }

    @Test
    public void findDownloadedEpisodeForVoiceover_returnsFirstMatchWhenPlayerDataIsNull() {
        EpisodesListResponse.EpisodeItem ep1 = createEpisode(101, "1");
        EpisodesListResponse.EpisodeItem ep2 = createEpisode(102, "2");
        DownloadedEpisodeEntity dlEp1 = createDownloaded("1_101_AniLiberty", 101, "1", "AniLiberty", "animelib");
        List<DownloadedEpisodeEntity> downloads = Collections.singletonList(dlEp1);

        assertSame(dlEp1, PlayerDownloadController.findDownloadedEpisodeForVoiceover(downloads, ep1, null));
        assertNull(PlayerDownloadController.findDownloadedEpisodeForVoiceover(downloads, ep2, null));
    }

    @Test
    public void isVoiceoverDownloaded_matchesSpecificEpisodeTeamAndPlayerType() {
        EpisodesListResponse.EpisodeItem ep1 = createEpisode(101, "1");
        EpisodesListResponse.EpisodeItem ep2 = createEpisode(102, "2");
        DownloadedEpisodeEntity dlAnimelib = createDownloaded("1_101_animelib", 101, "1", "AniLibria", "animelib");
        List<DownloadedEpisodeEntity> downloads = Collections.singletonList(dlAnimelib);

        EpisodeResponse.PlayerData animelibAnilibria = createPlayer("animelib", "AniLibria");
        EpisodeResponse.PlayerData kodikAnilibria = createPlayer("kodik", "AniLibria");
        EpisodeResponse.PlayerData animelibShiza = createPlayer("animelib", "SHIZA Project");

        assertTrue(PlayerOptionsAdapter.isVoiceoverDownloaded(animelibAnilibria, ep1, downloads));
        assertFalse(PlayerOptionsAdapter.isVoiceoverDownloaded(kodikAnilibria, ep1, downloads));
        assertFalse(PlayerOptionsAdapter.isVoiceoverDownloaded(animelibShiza, ep1, downloads));
        assertFalse(PlayerOptionsAdapter.isVoiceoverDownloaded(animelibAnilibria, ep2, downloads));
        assertFalse(PlayerOptionsAdapter.isVoiceoverDownloaded(null, ep1, downloads));
        assertFalse(PlayerOptionsAdapter.isVoiceoverDownloaded(animelibAnilibria, null, downloads));
        assertFalse(PlayerOptionsAdapter.isVoiceoverDownloaded(animelibAnilibria, ep1, null));
    }

    private static EpisodesListResponse.EpisodeItem createEpisode(int id, String number) {
        EpisodesListResponse.EpisodeItem item = new EpisodesListResponse.EpisodeItem();
        item.setId(id);
        item.setNumber(number);
        return item;
    }

    private static DownloadedEpisodeEntity createDownloaded(
            String id, int epId, String epNum, String teamName, String playerType) {
        return new DownloadedEpisodeEntity(
                id, "1", "Anime", epId, epNum, "Episode " + epNum,
                teamName, playerType, "/tmp/" + id + ".mp4", 1024L, 1000L, "1080p"
        );
    }

    private static EpisodeResponse.PlayerData createPlayer(String playerType, String teamName) {
        EpisodeResponse.PlayerData pd = new EpisodeResponse.PlayerData();
        pd.setPlayer(playerType);
        EpisodeResponse.Team team = new EpisodeResponse.Team();
        team.setName(teamName);
        pd.setTeam(team);
        return pd;
    }
}
