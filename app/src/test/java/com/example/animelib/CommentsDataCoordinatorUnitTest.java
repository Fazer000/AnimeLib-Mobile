package com.example.animelib;

import com.example.animelib.managers.CommentsDataCoordinator;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.models.EpisodeResponse;
import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.*;

public class CommentsDataCoordinatorUnitTest {

    @Test
    public void testBuildFallbackHtmlFromBBCode() {
        String html = CommentsDataCoordinator.buildFallbackHtmlFromBBCode(
                "[b]Жирный[/b] и [spoiler=Заголовок]Секрет[/spoiler]\n[quote]Цитата[/quote]");
        assertEquals(
                "<p><strong>Жирный</strong> и <div class=\"spoiler-node\" data-spoiler-text=\"Заголовок\"><div class=\"spoiler-node__text\">Секрет</div></div></p>"
                        + "<p><blockquote>Цитата</blockquote></p>",
                html
        );

        assertEquals("<p></p>", CommentsDataCoordinator.buildFallbackHtmlFromBBCode("   "));
        assertEquals("<p>&lt;script&gt;</p>", CommentsDataCoordinator.buildFallbackHtmlFromBBCode("<script>"));
    }

    @Test
    public void testApplyLocalVoteUpdateTransitions() {
        CommentsResponse.CommentItem item = new CommentsResponse.CommentItem();
        CommentsResponse.Votes votes = new CommentsResponse.Votes();
        votes.setUp(5);
        votes.setDown(2);
        votes.setUser(null);
        item.setVotes(votes);

        // Initial upvote
        CommentsDataCoordinator.applyLocalVoteUpdate(item, 1, null);
        assertEquals(6, item.getVotes().getUp());
        assertEquals(2, item.getVotes().getDown());
        assertEquals(1, item.getVotes().getUser());

        // Switch upvote -> downvote
        CommentsDataCoordinator.applyLocalVoteUpdate(item, 0, null);
        assertEquals(5, item.getVotes().getUp());
        assertEquals(3, item.getVotes().getDown());
        assertEquals(0, item.getVotes().getUser());

        // Clear vote (e.g. -1)
        CommentsDataCoordinator.applyLocalVoteUpdate(item, -1, null);
        assertEquals(5, item.getVotes().getUp());
        assertEquals(2, item.getVotes().getDown());
        assertEquals(-1, item.getVotes().getUser());
    }

    @Test
    public void testMutationPageLookupAndUpsert() {
        Gson gson = new Gson();
        CommentsResponse page1 = gson.fromJson("{\"data\":{\"root\":[{\"id\":10}],\"replies\":[]}}", CommentsResponse.class);
        CommentsResponse page2 = gson.fromJson("{\"data\":{\"root\":[{\"id\":20}],\"replies\":[{\"id\":201,\"root_id\":20,\"parent_comment\":20}]}}", CommentsResponse.class);

        CommentsDataCoordinator coordinator = new CommentsDataCoordinator();
        coordinator.recordLoadedPage(page1, true);
        coordinator.recordLoadedPage(page2, false);

        assertEquals(2, coordinator.findPageForMutationRefresh(null, 20L, 20L, false));
        assertEquals(1, coordinator.findPageForMutationRefresh(null, null, null, false));

        CommentsResponse.CommentItem newReply = new CommentsResponse.CommentItem();
        newReply.setId(202);
        coordinator.normalizeMutatedComment(newReply, 999L, "[i]Тест[/i]", 201L, 20L, 2, null);

        assertEquals(999L, newReply.getRelation_id());
        assertEquals("<p><em>Тест</em></p>", newReply.getComment());
        assertEquals(Long.valueOf(20L), newReply.getRoot_id());
        assertEquals(2, newReply.getComment_level());

        coordinator.upsertIntoLoadedPageResponses(newReply, true);
        assertTrue(CommentsDataCoordinator.responseContainsComment(page2, 202L));

        coordinator.removeCommentById(20L);
        assertFalse(CommentsDataCoordinator.responseContainsComment(page2, 20L));
        assertFalse(CommentsDataCoordinator.responseContainsComment(page2, 202L));
    }

    @Test
    public void testEpisodeResponseCoverExtractionAcrossShapes() {
        Gson gson = new Gson();
        EpisodeResponse.PlayerData playerWithObjCover = gson.fromJson(
                "{\"team\":{\"cover\":{\"default\":\"https://cdn.test/team.jpg\"}},\"translation_type\":{\"id\":1}}",
                EpisodeResponse.PlayerData.class
        );
        assertEquals("https://cdn.test/team.jpg", playerWithObjCover.getCoverUrl());

        EpisodeResponse.Team teamWithStringCover = gson.fromJson(
                "{\"cover\":\"https://cdn.test/direct.jpg\"}",
                EpisodeResponse.Team.class
        );
        assertEquals("https://cdn.test/direct.jpg", teamWithStringCover.getCoverUrl());
    }

    @Test
    public void testCoalescedSnapshotCompilationAndDeduplication() {
        Gson gson = new Gson();
        CommentsResponse page1 = gson.fromJson("{"
                + "\"data\": {"
                + "  \"root\": ["
                + "    {\"id\": 10, \"comment\": \"Sticky duplicate\", \"user\": {\"id\": 1, \"username\": \"Alice\"}},"
                + "    {\"id\": 20, \"comment\": \"<p>Normal root</p>\", \"user\": {\"id\": 2, \"username\": \"Bob\"}}"
                + "  ],"
                + "  \"replies\": ["
                + "    {\"id\": 201, \"parent_comment\": 20, \"root_id\": 20, \"comment_level\": 1, \"comment\": \"Nested reply\", \"user\": {\"id\": 3, \"username\": \"Eve\"}}"
                + "  ]"
                + "}"
                + "}", CommentsResponse.class);

        CommentsResponse.CommentItem sticky = new CommentsResponse.CommentItem();
        sticky.setId(10);
        sticky.setComment("Sticky duplicate");

        CommentsDataCoordinator.PreparedTreeSnapshot snapshot = CommentsDataCoordinator.compileSnapshot(
                java.util.Collections.singletonList(sticky),
                java.util.Collections.singletonList(page1),
                null,
                true,
                3,
                java.util.Collections.emptySet(),
                java.util.Collections.emptyMap(),
                System.currentTimeMillis()
        );

        assertEquals(1, snapshot.stickyRoots.size());
        assertEquals(1, snapshot.roots.size());
        assertEquals(20L, snapshot.roots.get(0).getId());
        // 1 sticky + 1 normal root + 1 reply = 3
        assertEquals(3, snapshot.flatItems.size());
        assertTrue(snapshot.stickyRoots.get(0).isSticky());
        assertEquals(0, snapshot.flatItems.get(0).getLevel());
        assertEquals(1, snapshot.flatItems.get(2).getLevel());
    }

    @Test
    public void testRequestHandleCancellationAndDetach() {
        com.example.animelib.api.ApiClientCore.RequestHandle handle =
                new com.example.animelib.api.ApiClientCore.RequestHandle();
        final boolean[] detached = {false};
        handle.setDetachAction(() -> detached[0] = true);
        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
        assertTrue(detached[0]);
    }

    @Test
    public void testHtmlPlainTextAndSingleParagraphFastPaths() {
        assertFalse(com.example.animelib.util.CommentHtmlProcessor.containsHtmlTagOrEntity("Просто обычный текст"));
        assertTrue(com.example.animelib.util.CommentHtmlProcessor.containsHtmlTagOrEntity("<p>Текст</p>"));
        assertTrue(com.example.animelib.util.CommentHtmlProcessor.containsHtmlTagOrEntity("A &amp; B"));

        com.example.animelib.util.CommentHtmlProcessor.ParsedCommentContent plain =
                com.example.animelib.util.CommentHtmlProcessor.parseHtmlOffThread("   Чистый   текст  ", 0xFFFFFFFF, 0xFF8B5CF6);
        assertTrue(plain.isSimple());
        assertEquals("Чистый текст", plain.simpleText.toString());

        com.example.animelib.util.CommentHtmlProcessor.ParsedCommentContent singleParagraph =
                com.example.animelib.util.CommentHtmlProcessor.parseHtmlOffThread("<p>Привет</p>", 0xFFFFFFFF, 0xFF8B5CF6);
        assertTrue(singleParagraph.isSimple());
        assertEquals("Привет", singleParagraph.simpleText.toString());
    }
}
