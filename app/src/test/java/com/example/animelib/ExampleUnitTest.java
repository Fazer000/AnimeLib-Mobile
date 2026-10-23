package com.example.animelib;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void testIsIgnoredColorDomain() {
        assertTrue(com.example.animelib.ui.JSInjectionsHandler.isIgnoredColorDomain("https://auth.hentaicdn.org/auth/login"));
        assertTrue(com.example.animelib.ui.JSInjectionsHandler.isIgnoredColorDomain("https://auth.hentaicdn.org/"));
        assertTrue(com.example.animelib.ui.JSInjectionsHandler.isIgnoredColorDomain("https://hentaicdn.org/auth/login"));
        assertFalse(com.example.animelib.ui.JSInjectionsHandler.isIgnoredColorDomain("https://animelib.me/ru/anime/1"));
        assertFalse(com.example.animelib.ui.JSInjectionsHandler.isIgnoredColorDomain("https://mangalib.me"));
        assertFalse(com.example.animelib.ui.JSInjectionsHandler.isIgnoredColorDomain(null));
        assertFalse(com.example.animelib.ui.JSInjectionsHandler.isIgnoredColorDomain("about:blank"));
    }

    @Test
    public void testCommentsAdapterActiveReplyId() {
        com.example.animelib.adapters.CommentsAdapter adapter = new com.example.animelib.adapters.CommentsAdapter();
        assertNull(adapter.getActiveReplyCommentId());
        
        final boolean[] replyClicked = {false};
        final boolean[] replyCancelled = {false};
        
        adapter.setCommentActionListener(new com.example.animelib.adapters.CommentsAdapter.CommentActionListener() {
            @Override
            public void onReplyClicked(com.example.animelib.models.CommentsResponse.CommentItem comment) {
                replyClicked[0] = true;
            }

            @Override
            public void onReplyCancelled(com.example.animelib.models.CommentsResponse.CommentItem comment) {
                replyCancelled[0] = true;
            }

            @Override
            public void onSendInlineReply(com.example.animelib.models.CommentsResponse.CommentItem comment, CharSequence rawText, android.widget.EditText inputField) {}

            @Override
            public void onVoteClicked(com.example.animelib.models.CommentsResponse.CommentItem comment, int targetVote) {}

            @Override
            public void onMoreActionsClicked(android.view.View anchorView, com.example.animelib.models.CommentsResponse.CommentItem comment) {}
        });

        adapter.setActiveReplyCommentId(12345L);
        assertEquals(Long.valueOf(12345L), adapter.getActiveReplyCommentId());

        adapter.setActiveReplyCommentId(null);
        assertNull(adapter.getActiveReplyCommentId());
    }

    @Test
    public void testMapLinkCreation() {
        com.example.animelib.views.FranchiseMapGraphView.MapLink link =
                new com.example.animelib.views.FranchiseMapGraphView.MapLink(101L, 1L, 2L, "sequel");
        assertEquals(101L, link.getId());
        assertEquals(1L, link.getSourceId());
        assertEquals(2L, link.getTargetId());
        assertEquals("sequel", link.getRelation());
    }
}