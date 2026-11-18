package com.example.animelib.managers;

import com.example.animelib.adapters.CommentsAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.util.CommentHtmlProcessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Координатор данных, HTML-предпарсинга и локальной синхронизации страниц комментариев.
 */
public class CommentsDataCoordinator {

    public static final class PageMetadata {
        public final int currentPage;
        public final boolean hasNextPage;

        public PageMetadata(int currentPage, boolean hasNextPage) {
            this.currentPage = currentPage;
            this.hasNextPage = hasNextPage;
        }
    }

    private final List<CommentsResponse.CommentItem> loadedStickyComments = new ArrayList<>();
    private final List<CommentsResponse> loadedPageResponses = new ArrayList<>();
    private final Map<Long, CommentHtmlProcessor.ParsedCommentContent> sharedParsedCache = new ConcurrentHashMap<>();

    public List<CommentsResponse.CommentItem> getLoadedStickyComments() {
        return loadedStickyComments;
    }

    public List<CommentsResponse> getLoadedPageResponses() {
        return loadedPageResponses;
    }

    public Map<Long, CommentHtmlProcessor.ParsedCommentContent> getSharedParsedCache() {
        return sharedParsedCache;
    }

    public void clearAll(boolean clearParsedCache) {
        loadedStickyComments.clear();
        loadedPageResponses.clear();
        if (clearParsedCache) {
            sharedParsedCache.clear();
        }
    }

    public boolean isPagesEmpty() {
        return loadedPageResponses.isEmpty();
    }

    public boolean hasAnyComments() {
        if (!loadedStickyComments.isEmpty()) {
            return true;
        }
        for (CommentsResponse resp : loadedPageResponses) {
            if (resp != null && resp.getData() != null) {
                if ((resp.getData().getRoot() != null && !resp.getData().getRoot().isEmpty())
                        || (resp.getData().getReplies() != null && !resp.getData().getReplies().isEmpty())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void setStickyComments(List<CommentsResponse.CommentItem> stickyComments) {
        loadedStickyComments.clear();
        if (stickyComments == null) return;
        for (CommentsResponse.CommentItem item : stickyComments) {
            if (item != null) {
                item.setSticky(true);
                loadedStickyComments.add(item);
            }
        }
    }

    public void recordLoadedPage(CommentsResponse response, boolean isInitialPage) {
        if (isInitialPage) {
            loadedPageResponses.clear();
        }
        if (response != null) {
            loadedPageResponses.add(response);
        }
    }

    public void addSyntheticEmptyPageIfNeeded() {
        if (loadedPageResponses.isEmpty()) {
            loadedPageResponses.add(new CommentsResponse());
        }
    }

    public Map<Long, CommentHtmlProcessor.ParsedCommentContent> preParseCommentsBatch(
            List<CommentsResponse.CommentItem> items,
            int primaryColor,
            int purpleColor) {
        Map<Long, CommentHtmlProcessor.ParsedCommentContent> result = new HashMap<>();
        if (items == null || items.isEmpty()) return result;

        for (CommentsResponse.CommentItem item : items) {
            if (item == null) continue;
            String html = item.getComment() != null ? item.getComment() : "";
            String key = CommentHtmlProcessor.computeSourceKey(html);
            CommentHtmlProcessor.ParsedCommentContent existing = sharedParsedCache.get(item.getId());
            if (existing != null && key.equals(existing.sourceHashKey)) {
                result.put(item.getId(), existing);
            } else {
                CommentHtmlProcessor.ParsedCommentContent parsed =
                        CommentHtmlProcessor.parseHtmlOffThread(html, primaryColor, purpleColor);
                sharedParsedCache.put(item.getId(), parsed);
                result.put(item.getId(), parsed);
            }
        }
        return result;
    }

    public void invalidateParsedForItems(List<CommentsResponse.CommentItem> items) {
        if (items == null) return;
        for (CommentsResponse.CommentItem item : items) {
            if (item != null) {
                sharedParsedCache.remove(item.getId());
            }
        }
    }

    public void putSingleParsed(long commentId, CommentHtmlProcessor.ParsedCommentContent parsed) {
        sharedParsedCache.put(commentId, parsed);
    }

    public void removeParsed(long commentId) {
        sharedParsedCache.remove(commentId);
    }

    public void syncCachedPagesIntoAdapter(CommentsAdapter targetAdapter, boolean prependRoot) {
        if (targetAdapter == null) return;
        targetAdapter.putParsedContentMap(sharedParsedCache);
        if (loadedPageResponses.isEmpty()) {
            if (!loadedStickyComments.isEmpty()) {
                targetAdapter.setStickyComments(loadedStickyComments);
            }
            return;
        }
        targetAdapter.replacePagesOnRefresh(loadedStickyComments, loadedPageResponses, null, prependRoot);
    }

    public void removeCommentById(long commentId) {
        sharedParsedCache.remove(commentId);
        for (int i = loadedStickyComments.size() - 1; i >= 0; i--) {
            if (loadedStickyComments.get(i).getId() == commentId) {
                loadedStickyComments.remove(i);
            }
        }
        for (CommentsResponse resp : loadedPageResponses) {
            if (resp == null || resp.getData() == null) continue;
            if (resp.getData().getRoot() != null) {
                resp.getData().getRoot().removeIf(item -> item != null && item.getId() == commentId);
            }
            if (resp.getData().getReplies() != null) {
                resp.getData().getReplies().removeIf(item -> item != null && (item.getId() == commentId
                        || (item.getParent_comment() != null && item.getParent_comment() == commentId)
                        || (item.getRoot_id() != null && item.getRoot_id() == commentId)));
            }
        }
    }

    public static List<CommentsResponse.CommentItem> extractAllItems(CommentsResponse response) {
        List<CommentsResponse.CommentItem> allItems = new ArrayList<>();
        if (response != null && response.getData() != null) {
            if (response.getData().getRoot() != null) {
                allItems.addAll(response.getData().getRoot());
            }
            if (response.getData().getReplies() != null) {
                allItems.addAll(response.getData().getReplies());
            }
        }
        return allItems;
    }

    public void normalizeMutatedComment(CommentsResponse.CommentItem item,
                                        long episodeId,
                                        String submittedText,
                                        Long parentId,
                                        Long rootId,
                                        int level,
                                        DatabaseManager db) {
        if (item == null) return;
        if (item.getRelation_id() <= 0) {
            item.setRelation_id(episodeId);
        }
        if (item.getComment() == null || item.getComment().trim().isEmpty()) {
            item.setComment(buildFallbackHtmlFromBBCode(submittedText));
        }
        if (parentId != null && parentId > 0) {
            if (item.getParent_comment() == null || item.getParent_comment() <= 0) {
                item.setParent_comment(parentId);
            }
            Long effectiveRoot = (rootId != null && rootId > 0) ? rootId : parentId;
            if (item.getRoot_id() == null || item.getRoot_id() <= 0) {
                item.setRoot_id(effectiveRoot);
            }
            if (item.getComment_level() <= 0) {
                item.setComment_level(Math.max(1, level));
            }
        }
        if (item.getCreated_at_ts() <= 0 && (item.getCreated_at() == null || item.getCreated_at().isEmpty())) {
            item.setCreated_at_ts(System.currentTimeMillis() / 1000L);
        }
        if (item.getUser() == null) {
            populateFallbackUserIfMissing(item, db);
        }
    }

    public static void populateFallbackUserIfMissing(CommentsResponse.CommentItem item, DatabaseManager db) {
        if (item == null || db == null) return;
        try {
            TokenEntity token = db.getToken();
            if (token == null) return;

            CommentsResponse.User user = new CommentsResponse.User();
            if (token.getUserId() != null && !token.getUserId().trim().isEmpty()) {
                try {
                    user.setId(Long.parseLong(token.getUserId().trim()));
                } catch (Exception ignored) {}
            } else if (token.getAccessToken() != null && !token.getAccessToken().trim().isEmpty()) {
                String extracted = ApiService.extractUserIdFromToken(token.getAccessToken());
                if (extracted != null && !extracted.trim().isEmpty()) {
                    try {
                        user.setId(Long.parseLong(extracted.trim()));
                    } catch (Exception ignored) {}
                }
            }
            if (token.getUsername() != null && !token.getUsername().trim().isEmpty()) {
                user.setUsername(token.getUsername().trim());
            }
            if (user.getUsername() != null || user.getId() > 0) {
                item.setUser(user);
            }
        } catch (Exception ignored) {}
    }

    public static String buildFallbackHtmlFromBBCode(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return "<p></p>";
        }
        String escaped = rawText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        escaped = escaped
                .replaceAll("(?is)\\[b\\]([\\s\\S]*?)\\[/b\\]", "<strong>$1</strong>")
                .replaceAll("(?is)\\[i\\]([\\s\\S]*?)\\[/i\\]", "<em>$1</em>")
                .replaceAll("(?is)\\[u\\]([\\s\\S]*?)\\[/u\\]", "<u>$1</u>")
                .replaceAll("(?is)\\[s\\]([\\s\\S]*?)\\[/s\\]", "<s>$1</s>")
                .replaceAll("(?is)\\[quote\\]([\\s\\S]*?)\\[/quote\\]", "<blockquote>$1</blockquote>")
                .replaceAll("(?is)\\[spoiler(?:=([^\\]]+))?\\]([\\s\\S]*?)\\[/spoiler\\]",
                        "<div class=\"spoiler-node\" data-spoiler-text=\"$1\"><div class=\"spoiler-node__text\">$2</div></div>");
        String[] paragraphs = escaped.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String p : paragraphs) {
            if (!p.trim().isEmpty()) {
                sb.append("<p>").append(p).append("</p>");
            }
        }
        return sb.length() > 0 ? sb.toString() : "<p>" + escaped + "</p>";
    }

    public int findPageForMutationRefresh(CommentsResponse.CommentItem mutatedComment,
                                          Long parentId,
                                          Long rootId,
                                          boolean isEdit) {
        if (!isEdit && (parentId == null || parentId <= 0)) {
            return 1;
        }
        Long lookupRootId = (rootId != null && rootId > 0)
                ? rootId
                : ((parentId != null && parentId > 0) ? parentId
                : (mutatedComment != null ? mutatedComment.getId() : null));
        if (lookupRootId == null || lookupRootId <= 0 || loadedPageResponses.isEmpty()) {
            return 1;
        }
        for (int i = 0; i < loadedPageResponses.size(); i++) {
            CommentsResponse resp = loadedPageResponses.get(i);
            if (responseContainsComment(resp, lookupRootId)) {
                return i + 1;
            }
        }
        return 1;
    }

    public static boolean responseContainsComment(CommentsResponse resp, long commentId) {
        if (resp == null || resp.getData() == null || commentId <= 0) return false;
        if (resp.getData().getRoot() != null) {
            for (CommentsResponse.CommentItem r : resp.getData().getRoot()) {
                if (r != null && r.getId() == commentId) return true;
            }
        }
        if (resp.getData().getReplies() != null) {
            for (CommentsResponse.CommentItem c : resp.getData().getReplies()) {
                if (c != null && c.getId() == commentId) return true;
            }
        }
        return false;
    }

    public void upsertIntoLoadedPageResponses(CommentsResponse.CommentItem item, boolean prependRoot) {
        if (item == null || item.getId() <= 0) return;

        for (CommentsResponse resp : loadedPageResponses) {
            if (resp == null || resp.getData() == null) continue;
            if (resp.getData().getRoot() != null) {
                for (int i = 0; i < resp.getData().getRoot().size(); i++) {
                    CommentsResponse.CommentItem existing = resp.getData().getRoot().get(i);
                    if (existing != null && existing.getId() == item.getId()) {
                        resp.getData().getRoot().set(i, item);
                        return;
                    }
                }
            }
            if (resp.getData().getReplies() != null) {
                for (int i = 0; i < resp.getData().getReplies().size(); i++) {
                    CommentsResponse.CommentItem existing = resp.getData().getReplies().get(i);
                    if (existing != null && existing.getId() == item.getId()) {
                        resp.getData().getReplies().set(i, item);
                        return;
                    }
                }
            }
        }

        if (loadedPageResponses.isEmpty()) {
            CommentsResponse syntheticPage = new CommentsResponse();
            CommentsResponse.Data data = new CommentsResponse.Data();
            data.setRoot(new ArrayList<>());
            data.setReplies(new ArrayList<>());
            syntheticPage.setData(data);
            loadedPageResponses.add(syntheticPage);
        }

        boolean isReply = (item.getParent_comment() != null && item.getParent_comment() > 0)
                || (item.getRoot_id() != null && item.getRoot_id() > 0)
                || item.getComment_level() > 0;

        if (isReply) {
            Long targetRoot = item.getRoot_id() != null && item.getRoot_id() > 0 ? item.getRoot_id() : item.getParent_comment();
            CommentsResponse targetResp = loadedPageResponses.get(0);
            if (targetRoot != null) {
                for (CommentsResponse resp : loadedPageResponses) {
                    if (responseContainsComment(resp, targetRoot)) {
                        targetResp = resp;
                        break;
                    }
                }
            }
            ensureResponseDataInitialized(targetResp);
            targetResp.getData().getReplies().add(item);
        } else {
            CommentsResponse targetResp = prependRoot
                    ? loadedPageResponses.get(0)
                    : loadedPageResponses.get(loadedPageResponses.size() - 1);
            ensureResponseDataInitialized(targetResp);
            if (prependRoot) {
                targetResp.getData().getRoot().add(0, item);
            } else {
                targetResp.getData().getRoot().add(item);
            }
        }
    }

    public PageMetadata applyRefreshedPage(int targetPage,
                                           CommentsResponse response,
                                           int prevCurrentPage,
                                           boolean prevHasNextPage) {
        int currentPage = prevCurrentPage;
        boolean hasNextPage = prevHasNextPage;

        if (targetPage == 1 && loadedPageResponses.size() <= 1) {
            loadedPageResponses.clear();
            loadedPageResponses.add(response);
            if (response.getMeta() != null) {
                hasNextPage = response.getMeta().isHas_next_page();
                currentPage = response.getMeta().getPage() > 0 ? response.getMeta().getPage() : 1;
            } else {
                currentPage = 1;
                hasNextPage = false;
            }
        } else if (targetPage >= 1 && targetPage <= loadedPageResponses.size()) {
            loadedPageResponses.set(targetPage - 1, response);
            if (targetPage == loadedPageResponses.size() && response.getMeta() != null) {
                hasNextPage = response.getMeta().isHas_next_page();
                currentPage = Math.max(currentPage, targetPage);
            }
        } else {
            loadedPageResponses.clear();
            loadedPageResponses.add(response);
            currentPage = targetPage;
            hasNextPage = response.getMeta() != null && response.getMeta().isHas_next_page();
        }

        return new PageMetadata(currentPage, hasNextPage);
    }

    public static void applyLocalVoteUpdate(CommentsResponse.CommentItem comment,
                                            int newVoteValue,
                                            CommentsResponse.Votes updatedVotes) {
        if (comment == null) return;
        if (updatedVotes != null) {
            comment.setVotes(updatedVotes);
            return;
        }
        if (comment.getVotes() == null) {
            comment.setVotes(new CommentsResponse.Votes());
        }
        Integer oldVote = null;
        Object rawUserVote = comment.getVotes().getUser();
        if (rawUserVote != null) {
            try {
                if (rawUserVote instanceof Number) {
                    oldVote = ((Number) rawUserVote).intValue();
                } else if (rawUserVote instanceof Boolean) {
                    oldVote = ((Boolean) rawUserVote) ? 1 : 0;
                } else {
                    String str = rawUserVote.toString().trim();
                    if ("true".equalsIgnoreCase(str)) oldVote = 1;
                    else if ("false".equalsIgnoreCase(str)) oldVote = 0;
                    else oldVote = Integer.parseInt(str);
                }
            } catch (Exception ignored) {}
        }

        int up = comment.getVotes().getUp();
        int down = comment.getVotes().getDown();

        if (oldVote != null) {
            if (oldVote == 1) up = Math.max(0, up - 1);
            if (oldVote == 0) down = Math.max(0, down - 1);
        }

        if (newVoteValue == 1) up++;
        if (newVoteValue == 0) down++;

        comment.getVotes().setUp(up);
        comment.getVotes().setDown(down);
        comment.getVotes().setUser(newVoteValue);
    }

    private static void ensureResponseDataInitialized(CommentsResponse targetResp) {
        if (targetResp.getData() == null) {
            CommentsResponse.Data d = new CommentsResponse.Data();
            d.setRoot(new ArrayList<>());
            d.setReplies(new ArrayList<>());
            targetResp.setData(d);
        } else {
            if (targetResp.getData().getRoot() == null) {
                targetResp.getData().setRoot(new ArrayList<>());
            }
            if (targetResp.getData().getReplies() == null) {
                targetResp.getData().setReplies(new ArrayList<>());
            }
        }
    }
}
