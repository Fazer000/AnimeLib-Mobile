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
}
