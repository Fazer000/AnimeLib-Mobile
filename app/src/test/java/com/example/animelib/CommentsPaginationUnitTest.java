package com.example.animelib;

import com.example.animelib.adapters.CommentsAdapter;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.util.CommentHtmlProcessor;
import com.google.gson.Gson;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class CommentsPaginationUnitTest {

    @Test
    public void testMetaPaginationCalculations() {
        String json = "{"
                + "\"meta\": {"
                + "\"page\": 2,"
                + "\"per_page\": 20,"
                + "\"total\": 95,"
                + "\"has_next_page\": true"
                + "}"
                + "}";

        Gson gson = new Gson();
        CommentsResponse response = gson.fromJson(json, CommentsResponse.class);

        assertNotNull(response);
        assertNotNull(response.getMeta());
        assertEquals(2, response.getMeta().getPage());
        assertTrue(response.getMeta().isHas_next_page());
        assertEquals(Integer.valueOf(95), response.getMeta().getTotal());
        assertEquals(Integer.valueOf(5), response.getMeta().getTotal_pages());
    }

    @Test
    public void testMetaWithoutTotal() {
        String json = "{"
                + "\"meta\": {"
                + "\"page\": 1,"
                + "\"per_page\": 20,"
                + "\"has_next_page\": false"
                + "}"
                + "}";

        Gson gson = new Gson();
        CommentsResponse response = gson.fromJson(json, CommentsResponse.class);

        assertNotNull(response);
        assertNotNull(response.getMeta());
        assertEquals(1, response.getMeta().getPage());
        assertFalse(response.getMeta().isHas_next_page());
        assertNull(response.getMeta().getTotal_pages());
    }

    @Test
    public void testProgressiveInfiniteScrollAppendingAndDeduplication() {
        Gson gson = new Gson();
        CommentsResponse page1 = gson.fromJson("{"
                + "\"data\": {"
                + "  \"root\": ["
                + "    {\"id\": 10, \"comment\": \"<p>First</p>\"},"
                + "    {\"id\": 11, \"comment\": \"<p>Second</p>\"}"
                + "  ],"
                + "  \"replies\": ["
                + "    {\"id\": 101, \"parent_comment\": 10, \"root_id\": 10, \"comment\": \"Reply 1\"}"
                + "  ]"
                + "},"
                + "\"meta\": {\"page\": 1, \"has_next_page\": true}"
                + "}", CommentsResponse.class);

        CommentsResponse page2 = gson.fromJson("{"
                + "\"data\": {"
                + "  \"root\": ["
                + "    {\"id\": 11, \"comment\": \"<p>Duplicate root</p>\"},"
                + "    {\"id\": 12, \"comment\": \"<p>Third</p>\"}"
                + "  ],"
                + "  \"replies\": ["
                + "    {\"id\": 102, \"parent_comment\": 12, \"root_id\": 12, \"comment\": \"Reply to third\"}"
                + "  ]"
                + "},"
                + "\"meta\": {\"page\": 2, \"has_next_page\": false}"
                + "}", CommentsResponse.class);

        CommentsAdapter adapter = new CommentsAdapter();
        adapter.setInitialComments(Collections.emptyList(), page1);
        assertEquals(3, adapter.getCommentDisplayCount());

        adapter.setLoadingMore(true);
        assertEquals(4, adapter.getItemCount());
        assertEquals(CommentsAdapter.DisplayItem.TYPE_LOADING_FOOTER, adapter.getItemViewType(3));

        adapter.setLoadingMore(false);
        adapter.appendCommentsPage(page2);
        // Duplicate id=11 is skipped; id=12 and its reply id=102 are appended -> 3 + 2 = 5
        assertEquals(5, adapter.getCommentDisplayCount());
        assertEquals(5, adapter.getItemCount());
    }

    @Test
    public void testHtmlBlockStructureDetection() {
        assertFalse(CommentHtmlProcessor.containsBlockStructure("<p>Обычный <strong>жирный</strong> текст</p>"));
        assertTrue(CommentHtmlProcessor.containsBlockStructure("<div class=\"spoiler-node\" data-spoiler-text=\"Спойлер\">Секрет</div>"));
        assertTrue(CommentHtmlProcessor.containsBlockStructure("<blockquote>Цитата</blockquote>"));
        assertEquals("Привет\nмир", CommentHtmlProcessor.normalizeHtmlWhitespace("  Привет   \n\n\n   мир  "));
    }

    @Test
    public void testPortraitViewportModeLimitsInitialInflationAndRevealsBatches() {
        StringBuilder rootsJson = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            if (i > 1) rootsJson.append(",");
            rootsJson.append("{\"id\": ").append(i).append(", \"comment\": \"<p>Comment ").append(i).append("</p>\"}");
        }
        Gson gson = new Gson();
        CommentsResponse page1 = gson.fromJson("{"
                + "\"data\": {\"root\": [" + rootsJson + "], \"replies\": []},"
                + "\"meta\": {\"page\": 1, \"has_next_page\": true}"
                + "}", CommentsResponse.class);

        CommentsAdapter portraitAdapter = new CommentsAdapter();
        portraitAdapter.setPortraitViewportMode(true);
        portraitAdapter.setInitialComments(Collections.emptyList(), page1);

        assertEquals(CommentsAdapter.INITIAL_PORTRAIT_RENDER_COUNT, portraitAdapter.getCommentDisplayCount());
        assertTrue(portraitAdapter.hasDeferredViewportItems());
        assertEquals(20, portraitAdapter.getTotalTargetCount());

        int revealed1 = portraitAdapter.revealNextViewportBatch(CommentsAdapter.PORTRAIT_SCROLL_BATCH_SIZE);
        assertEquals(12, revealed1);
        assertEquals(18, portraitAdapter.getCommentDisplayCount());
        assertTrue(portraitAdapter.hasDeferredViewportItems());

        int revealed2 = portraitAdapter.revealNextViewportBatch(CommentsAdapter.PORTRAIT_SCROLL_BATCH_SIZE);
        assertEquals(2, revealed2);
        assertEquals(20, portraitAdapter.getCommentDisplayCount());
        assertFalse(portraitAdapter.hasDeferredViewportItems());
    }

    @Test
    public void testEpisodeCommentsCacheInvalidationClearsAllSortsAndPagesForEpisode() {
        java.util.Map<String, Object> commentsCache = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<Long, Object> stickyCache = new java.util.concurrent.ConcurrentHashMap<>();

        commentsCache.put("500_desc_1", "page1_desc");
        commentsCache.put("500_desc_2", "page2_desc");
        commentsCache.put("500_votes_up_1", "page1_popular");
        commentsCache.put("501_desc_1", "other_episode_page1");
        stickyCache.put(500L, "sticky_500");
        stickyCache.put(501L, "sticky_501");

        com.example.animelib.api.ApiService.invalidateCommentsCacheMapForEpisode(commentsCache, stickyCache, 500L);

        assertFalse(commentsCache.containsKey("500_desc_1"));
        assertFalse(commentsCache.containsKey("500_desc_2"));
        assertFalse(commentsCache.containsKey("500_votes_up_1"));
        assertTrue(commentsCache.containsKey("501_desc_1"));
        assertFalse(stickyCache.containsKey(500L));
        assertTrue(stickyCache.containsKey(501L));
    }

    @Test
    public void testUpsertNewRootCommentAndReplyAndRefreshFromServer() {
        Gson gson = new Gson();
        CommentsResponse initialPage = gson.fromJson("{"
                + "\"data\": {"
                + "  \"root\": ["
                + "    {\"id\": 10, \"comment\": \"<p>Existing root</p>\"}"
                + "  ],"
                + "  \"replies\": ["
                + "    {\"id\": 101, \"parent_comment\": 10, \"root_id\": 10, \"comment_level\": 1, \"comment\": \"<p>R1</p>\"},"
                + "    {\"id\": 102, \"parent_comment\": 10, \"root_id\": 10, \"comment_level\": 1, \"comment\": \"<p>R2</p>\"},"
                + "    {\"id\": 103, \"parent_comment\": 10, \"root_id\": 10, \"comment_level\": 1, \"comment\": \"<p>R3</p>\"}"
                + "  ]"
                + "},"
                + "\"meta\": {\"page\": 1, \"has_next_page\": false}"
                + "}", CommentsResponse.class);

        CommentsAdapter adapter = new CommentsAdapter();
        adapter.setRepliesLimit(3);
        adapter.setInitialComments(Collections.emptyList(), initialPage);
        assertEquals(4, adapter.getCommentDisplayCount());

        // 1. User posts a new root comment -> appears at position 0 immediately
        CommentsResponse.CommentItem postedRoot = new CommentsResponse.CommentItem();
        postedRoot.setId(20);
        postedRoot.setComment("<p>My new comment</p>");
        adapter.upsertComment(postedRoot, true);

        assertTrue(adapter.containsComment(20));
        assertEquals(0, adapter.findCommentAdapterPosition(20));
        assertEquals(5, adapter.getCommentDisplayCount());

        // 2. User posts a reply to a reply (level 2, 4th reply in thread > repliesLimit=3) -> auto-expands thread and renders
        CommentsResponse.CommentItem nestedReply = new CommentsResponse.CommentItem();
        nestedReply.setId(104);
        nestedReply.setParent_comment(103L);
        nestedReply.setRoot_id(10L);
        nestedReply.setComment_level(2);
        nestedReply.setComment("<p>Nested reply</p>");
        adapter.upsertComment(nestedReply, true);

        assertTrue(adapter.containsComment(104));
        assertTrue(adapter.findCommentAdapterPosition(104) > adapter.findCommentAdapterPosition(103));
        assertEquals(6, adapter.getCommentDisplayCount());

        // 3. Server refresh arrives WITH replica lag (id=104 not indexed yet) -> ensurePresentComment keeps id=104 visible
        CommentsResponse laggingRefresh = gson.fromJson("{"
                + "\"data\": {"
                + "  \"root\": ["
                + "    {\"id\": 20, \"comment\": \"<p>My new comment</p>\"},"
                + "    {\"id\": 10, \"comment\": \"<p>Existing root</p>\"}"
                + "  ],"
                + "  \"replies\": ["
                + "    {\"id\": 101, \"parent_comment\": 10, \"root_id\": 10, \"comment_level\": 1, \"comment\": \"<p>R1</p>\"},"
                + "    {\"id\": 102, \"parent_comment\": 10, \"root_id\": 10, \"comment_level\": 1, \"comment\": \"<p>R2</p>\"},"
                + "    {\"id\": 103, \"parent_comment\": 10, \"root_id\": 10, \"comment_level\": 1, \"comment\": \"<p>R3</p>\"}"
                + "  ]"
                + "},"
                + "\"meta\": {\"page\": 1, \"has_next_page\": false}"
                + "}", CommentsResponse.class);

        adapter.replacePagesOnRefresh(Collections.emptyList(), Collections.singletonList(laggingRefresh), nestedReply, true);
        assertTrue(adapter.containsComment(20));
        assertTrue(adapter.containsComment(104));
        assertEquals(6, adapter.getCommentDisplayCount());

        // 4. Server refresh arrives WITH id=104 included -> no duplicate, canonical item displayed
        CommentsResponse fullRefresh = gson.fromJson("{"
                + "\"data\": {"
                + "  \"root\": ["
                + "    {\"id\": 20, \"comment\": \"<p>My new comment</p>\"},"
                + "    {\"id\": 10, \"comment\": \"<p>Existing root</p>\"}"
                + "  ],"
                + "  \"replies\": ["
                + "    {\"id\": 101, \"parent_comment\": 10, \"root_id\": 10, \"comment_level\": 1, \"comment\": \"<p>R1</p>\"},"
                + "    {\"id\": 102, \"parent_comment\": 10, \"root_id\": 10, \"comment_level\": 1, \"comment\": \"<p>R2</p>\"},"
                + "    {\"id\": 103, \"parent_comment\": 10, \"root_id\": 10, \"comment_level\": 1, \"comment\": \"<p>R3</p>\"},"
                + "    {\"id\": 104, \"parent_comment\": 103, \"root_id\": 10, \"comment_level\": 2, \"comment\": \"<p>Nested reply</p>\"}"
                + "  ]"
                + "},"
                + "\"meta\": {\"page\": 1, \"has_next_page\": false}"
                + "}", CommentsResponse.class);

        adapter.replacePagesOnRefresh(Collections.emptyList(), Collections.singletonList(fullRefresh), nestedReply, true);
        assertEquals(6, adapter.getCommentDisplayCount());
        assertTrue(adapter.findCommentAdapterPosition(104) > 0);
    }
}
