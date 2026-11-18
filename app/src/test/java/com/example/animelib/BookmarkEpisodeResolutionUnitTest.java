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
}
