package com.example.animelib;

import com.example.animelib.data.entity.OfflineBookmarkEntity;
import com.example.animelib.managers.BookmarkManager;
import com.example.animelib.models.AnimeBookmarkResponse;
import com.example.animelib.models.EpisodesListResponse;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class BookmarkEpisodeResolutionUnitTest {

    private static EpisodesListResponse.EpisodeItem ep(int id, String number) {
        EpisodesListResponse.EpisodeItem item = new EpisodesListResponse.EpisodeItem();
        item.setId(id);
        item.setNumber(number);
        return item;
    }

    @Test
    public void testStatusOnlyBookmarkWithDefaultMetaDoesNotMatchEpisode1() {
        List<EpisodesListResponse.EpisodeItem> episodes = Arrays.asList(
                ep(100, "0"),
                ep(101, "1"),
                ep(102, "2")
        );

        AnimeBookmarkResponse.BookmarkData statusOnly = new AnimeBookmarkResponse.BookmarkData();
        statusOnly.setStatus(2); // "В планах"
        statusOnly.setItemId(0);
        statusOnly.setProgress(null);
        AnimeBookmarkResponse.MetaData meta = new AnimeBookmarkResponse.MetaData();
        meta.setItemNumber(1);
        statusOnly.setMeta(meta);

        assertFalse(BookmarkManager.hasExplicitEpisodeReference(statusOnly));
        assertNull(BookmarkManager.resolveBookmarkedEpisode(statusOnly, episodes));
        assertFalse(BookmarkManager.isBookmarkForEpisode(statusOnly, episodes.get(1)));

        // Even if server sends "00:00" with no item_id and no item, it must still not override episode 0
        statusOnly.setProgress("00:00");
        assertFalse(BookmarkManager.hasExplicitEpisodeReference(statusOnly));
        assertNull(BookmarkManager.resolveBookmarkedEpisode(statusOnly, episodes));
    }

    @Test
    public void testOnlineBookmarkMatchesByItemIdFirst() {
        List<EpisodesListResponse.EpisodeItem> episodes = Arrays.asList(
                ep(100, "0"),
                ep(101, "1"),
                ep(102, "2")
        );

        AnimeBookmarkResponse.BookmarkData bookmark = new AnimeBookmarkResponse.BookmarkData();
        bookmark.setItemId(102);
        bookmark.setProgress("05:10");

        EpisodesListResponse.EpisodeItem resolved = BookmarkManager.resolveBookmarkedEpisode(bookmark, episodes);
        assertNotNull(resolved);
        assertEquals(102, resolved.getId());
        assertTrue(BookmarkManager.isBookmarkForEpisode(bookmark, resolved));
        assertFalse(BookmarkManager.isBookmarkForEpisode(bookmark, episodes.get(0)));
    }

    @Test
    public void testOnlineBookmarkMatchesByNestedItemIdWhenItemIdZero() {
        List<EpisodesListResponse.EpisodeItem> episodes = Arrays.asList(
                ep(100, "0"),
                ep(101, "1")
        );

        AnimeBookmarkResponse.BookmarkData bookmark = new AnimeBookmarkResponse.BookmarkData();
        bookmark.setItemId(0);
        AnimeBookmarkResponse.ItemData itemData = new AnimeBookmarkResponse.ItemData();
        itemData.setId(100);
        itemData.setNumber("0");
        bookmark.setItem(itemData);

        EpisodesListResponse.EpisodeItem resolved = BookmarkManager.resolveBookmarkedEpisode(bookmark, episodes);
        assertNotNull(resolved);
        assertEquals(100, resolved.getId());
        assertEquals("0", resolved.getNumber());
    }

    @Test
    public void testOnlineBookmarkWithPositiveProgressAndMetaNumberMatches() {
        List<EpisodesListResponse.EpisodeItem> episodes = Arrays.asList(
                ep(100, "0"),
                ep(101, "1"),
                ep(102, "2")
        );

        AnimeBookmarkResponse.BookmarkData bookmark = new AnimeBookmarkResponse.BookmarkData();
        bookmark.setItemId(0);
        bookmark.setProgress("03:45");
        AnimeBookmarkResponse.MetaData meta = new AnimeBookmarkResponse.MetaData();
        meta.setItemNumber(2);
        bookmark.setMeta(meta);

        EpisodesListResponse.EpisodeItem resolved = BookmarkManager.resolveBookmarkedEpisode(bookmark, episodes);
        assertNotNull(resolved);
        assertEquals(102, resolved.getId());
    }

    @Test
    public void testOfflineBookmarkMatchesByIdOrNumberAndIgnoresEmptyBookmark() {
        List<EpisodesListResponse.EpisodeItem> episodes = Arrays.asList(
                ep(200, "0"),
                ep(201, "1"),
                ep(202, "2")
        );

        OfflineBookmarkEntity byId = new OfflineBookmarkEntity("anime1", 202, "2", "10:00", 600_000L, 1L);
        EpisodesListResponse.EpisodeItem resolvedById = BookmarkManager.resolveOfflineBookmarkedEpisode(byId, episodes);
        assertNotNull(resolvedById);
        assertEquals(202, resolvedById.getId());

        OfflineBookmarkEntity byNumber = new OfflineBookmarkEntity("anime1", 0, "1", "02:30", 150_000L, 1L);
        EpisodesListResponse.EpisodeItem resolvedByNumber = BookmarkManager.resolveOfflineBookmarkedEpisode(byNumber, episodes);
        assertNotNull(resolvedByNumber);
        assertEquals(201, resolvedByNumber.getId());

        OfflineBookmarkEntity empty = new OfflineBookmarkEntity("anime1", 0, "", "00:00", 0L, 1L);
        assertNull(BookmarkManager.resolveOfflineBookmarkedEpisode(empty, episodes));
    }

    @Test
    public void testInitialEpisodeOnlineUsesApiOnlyAndIgnoresStaleDbBookmark() {
        List<EpisodesListResponse.EpisodeItem> episodes = Arrays.asList(
                ep(300, "1"),
                ep(301, "2"),
                ep(302, "11"),
                ep(303, "12")
        );

        OfflineBookmarkEntity staleDb = new OfflineBookmarkEntity("anime1", 303, "12", "15:00", 900_000L, 1L);

        // Online + API bookmark deleted (null) + stale DB bookmark -> MUST open first episode, NOT DB episode 12
        EpisodesListResponse.EpisodeItem resolvedDeletedOnApi =
                BookmarkManager.resolveInitialEpisode(true, null, staleDb, episodes);
        assertNotNull(resolvedDeletedOnApi);
        assertEquals(300, resolvedDeletedOnApi.getId());
        assertEquals("1", resolvedDeletedOnApi.getNumber());

        // Online + status-only API bookmark + stale DB bookmark -> MUST open first episode, NOT DB episode 12
        AnimeBookmarkResponse.BookmarkData statusOnly = new AnimeBookmarkResponse.BookmarkData();
        statusOnly.setStatus(21);
        statusOnly.setItemId(0);
        EpisodesListResponse.EpisodeItem resolvedStatusOnly =
                BookmarkManager.resolveInitialEpisode(true, statusOnly, staleDb, episodes);
        assertNotNull(resolvedStatusOnly);
        assertEquals(300, resolvedStatusOnly.getId());

        // Online + valid API bookmark + different DB bookmark -> API bookmark wins
        AnimeBookmarkResponse.BookmarkData online = new AnimeBookmarkResponse.BookmarkData();
        online.setItemId(301);
        online.setProgress("04:00");
        EpisodesListResponse.EpisodeItem resolvedFromApi =
                BookmarkManager.resolveInitialEpisode(true, online, staleDb, episodes);
        assertNotNull(resolvedFromApi);
        assertEquals(301, resolvedFromApi.getId());
    }

    @Test
    public void testInitialEpisodeOfflineUsesDbBookmarkOrFirstEpisode() {
        List<EpisodesListResponse.EpisodeItem> episodes = Arrays.asList(
                ep(400, "1"),
                ep(401, "2"),
                ep(402, "3")
        );

        OfflineBookmarkEntity offline = new OfflineBookmarkEntity("anime1", 401, "2", "01:30", 90_000L, 1L);

        // Offline + valid DB bookmark -> DB bookmark wins
        EpisodesListResponse.EpisodeItem fromDb =
                BookmarkManager.resolveInitialEpisode(false, null, offline, episodes);
        assertNotNull(fromDb);
        assertEquals(401, fromDb.getId());

        // Offline + no DB bookmark -> first episode
        EpisodesListResponse.EpisodeItem noDb =
                BookmarkManager.resolveInitialEpisode(false, null, null, episodes);
        assertNotNull(noDb);
        assertEquals(400, noDb.getId());

        // Offline + empty DB bookmark -> first episode
        OfflineBookmarkEntity emptyDb = new OfflineBookmarkEntity("anime1", 0, "", "00:00", 0L, 1L);
        EpisodesListResponse.EpisodeItem emptyDbResolved =
                BookmarkManager.resolveInitialEpisode(false, null, emptyDb, episodes);
        assertNotNull(emptyDbResolved);
        assertEquals(400, emptyDbResolved.getId());
    }

    @Test
    public void testIsNetworkErrorDistinguishesTransportFailuresFromHttp404() {
        assertTrue(BookmarkManager.isNetworkError("Ошибка сети: Unable to resolve host"));
        assertTrue(BookmarkManager.isNetworkError("Ошибка при получении закладки: 503"));
        assertTrue(BookmarkManager.isNetworkError("timeout"));
        assertFalse(BookmarkManager.isNetworkError("Ошибка при получении закладки: 404"));
        assertFalse(BookmarkManager.isNetworkError("Не авторизован"));
        assertFalse(BookmarkManager.isNetworkError(null));
    }
}

