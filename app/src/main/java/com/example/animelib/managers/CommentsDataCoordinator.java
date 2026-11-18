package com.example.animelib.managers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.example.animelib.adapters.CommentsAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.util.CommentHtmlProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Координатор данных, фонового построения дерева комментариев, предпарсинга HTML
 * и локальной синхронизации страниц без нагрузки на главный UI-поток.
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

    /**
     * Полностью подготовленный в фоне снимок дерева и плоского списка комментариев.
     * Все поля неизменяемо собраны вне главного потока.
     */
    public static final class PreparedTreeSnapshot {
        public final List<CommentsResponse.CommentItem> stickyRoots;
        public final List<CommentsResponse.CommentItem> roots;
        public final Map<Long, CommentsResponse.CommentItem> allById;
        public final Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId;
        public final List<CommentsAdapter.DisplayItem> flatItems;
        public final Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedContent;

        public PreparedTreeSnapshot(List<CommentsResponse.CommentItem> stickyRoots,
                                    List<CommentsResponse.CommentItem> roots,
                                    Map<Long, CommentsResponse.CommentItem> allById,
                                    Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId,
                                    List<CommentsAdapter.DisplayItem> flatItems,
                                    Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedContent) {
            this.stickyRoots = stickyRoots != null ? stickyRoots : Collections.emptyList();
            this.roots = roots != null ? roots : Collections.emptyList();
            this.allById = allById != null ? allById : Collections.emptyMap();
            this.childrenByParentId = childrenByParentId != null ? childrenByParentId : Collections.emptyMap();
            this.flatItems = flatItems != null ? flatItems : Collections.emptyList();
            this.parsedContent = parsedContent != null ? parsedContent : Collections.emptyMap();
        }

        public boolean isEmpty() {
            return stickyRoots.isEmpty() && roots.isEmpty();
        }
    }

    /**
     * Предподготовленный в фоне результат догрузки следующей страницы комментариев.
     */
    public static final class PreparedAppendBatch {
        public final List<CommentsResponse.CommentItem> addedRoots;
        public final List<CommentsResponse.CommentItem> addedReplies;
        public final List<CommentsAdapter.DisplayItem> newDisplayItems;
        public final Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedContent;
        public final boolean requiresFullRebuild;
        @Nullable
        public final PreparedTreeSnapshot fullRebuildSnapshot;

        public PreparedAppendBatch(List<CommentsResponse.CommentItem> addedRoots,
                                   List<CommentsResponse.CommentItem> addedReplies,
                                   List<CommentsAdapter.DisplayItem> newDisplayItems,
                                   Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedContent,
                                   boolean requiresFullRebuild,
                                   @Nullable PreparedTreeSnapshot fullRebuildSnapshot) {
            this.addedRoots = addedRoots != null ? addedRoots : Collections.emptyList();
            this.addedReplies = addedReplies != null ? addedReplies : Collections.emptyList();
            this.newDisplayItems = newDisplayItems != null ? newDisplayItems : Collections.emptyList();
            this.parsedContent = parsedContent != null ? parsedContent : Collections.emptyMap();
            this.requiresFullRebuild = requiresFullRebuild;
            this.fullRebuildSnapshot = fullRebuildSnapshot;
        }
    }

    private final List<CommentsResponse.CommentItem> loadedStickyComments = new ArrayList<>();
    private final List<CommentsResponse> loadedPageResponses = new ArrayList<>();
    private final Map<Long, CommentHtmlProcessor.ParsedCommentContent> sharedParsedCache = new ConcurrentHashMap<>();
    private volatile PreparedTreeSnapshot latestPreparedSnapshot;

    public List<CommentsResponse.CommentItem> getLoadedStickyComments() {
        return loadedStickyComments;
    }

    public List<CommentsResponse> getLoadedPageResponses() {
        return loadedPageResponses;
    }

    public Map<Long, CommentHtmlProcessor.ParsedCommentContent> getSharedParsedCache() {
        return sharedParsedCache;
    }

    @Nullable
    public PreparedTreeSnapshot getLatestPreparedSnapshot() {
        return latestPreparedSnapshot;
    }

    public void setLatestPreparedSnapshot(@Nullable PreparedTreeSnapshot snapshot) {
        this.latestPreparedSnapshot = snapshot;
    }

    public boolean hasPreparedSnapshot() {
        return latestPreparedSnapshot != null;
    }

    public void clearAll(boolean clearParsedCache) {
        loadedStickyComments.clear();
        loadedPageResponses.clear();
        latestPreparedSnapshot = null;
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
        return preParseCommentsBatchIntoCache(items, sharedParsedCache, primaryColor, purpleColor);
    }

    public static Map<Long, CommentHtmlProcessor.ParsedCommentContent> preParseCommentsBatchIntoCache(
            List<CommentsResponse.CommentItem> items,
            Map<Long, CommentHtmlProcessor.ParsedCommentContent> cache,
            int primaryColor,
            int purpleColor) {
        Map<Long, CommentHtmlProcessor.ParsedCommentContent> result = new HashMap<>();
        if (items == null || items.isEmpty()) return result;

        for (CommentsResponse.CommentItem item : items) {
            if (item == null) continue;
            String html = item.getComment() != null ? item.getComment() : "";
            String key = CommentHtmlProcessor.computeSourceKey(html);
            CommentHtmlProcessor.ParsedCommentContent existing = cache != null ? cache.get(item.getId()) : null;
            if (existing != null && key.equals(existing.sourceHashKey)) {
                result.put(item.getId(), existing);
            } else {
                CommentHtmlProcessor.ParsedCommentContent parsed =
                        CommentHtmlProcessor.parseHtmlOffThread(html, primaryColor, purpleColor);
                if (cache != null) {
                    cache.put(item.getId(), parsed);
                }
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
        PreparedTreeSnapshot snapshot = latestPreparedSnapshot;
        if (snapshot != null && loadedPageResponses.size() <= 1) {
            targetAdapter.applyPreparedSnapshot(snapshot, null);
            return;
        }
        if (loadedPageResponses.isEmpty()) {
            if (!loadedStickyComments.isEmpty()) {
                targetAdapter.setStickyComments(loadedStickyComments);
            }
            return;
        }
        targetAdapter.replacePagesOnRefresh(loadedStickyComments, loadedPageResponses, null, prependRoot);
    }

    /**
     * Полностью собирает и предпарсивает дерево комментариев в фоновом потоке.
     */
    public PreparedTreeSnapshot buildSnapshotOffThread(
            List<CommentsResponse.CommentItem> stickyComments,
            List<CommentsResponse> pages,
            @Nullable CommentsResponse.CommentItem ensurePresentComment,
            boolean prependRootIfMissing,
            int repliesLimit,
            Set<Long> expandedParentIds,
            int primaryColor,
            int purpleColor) {
        List<CommentsResponse.CommentItem> allItemsToParse = new ArrayList<>();
        if (stickyComments != null) {
            for (CommentsResponse.CommentItem s : stickyComments) {
                if (s != null) allItemsToParse.add(s);
            }
        }
        if (pages != null) {
            for (CommentsResponse page : pages) {
                allItemsToParse.addAll(extractAllItems(page));
            }
        }
        if (ensurePresentComment != null) {
            allItemsToParse.add(ensurePresentComment);
        }

        Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedMap =
                preParseCommentsBatchIntoCache(allItemsToParse, sharedParsedCache, primaryColor, purpleColor);

        return compileSnapshot(
                stickyComments,
                pages,
                ensurePresentComment,
                prependRootIfMissing,
                repliesLimit,
                expandedParentIds,
                parsedMap,
                System.currentTimeMillis()
        );
    }

    /**
     * Предподготавливает в фоне следующую страницу для плавного добавления в конец адаптера.
     */
    public PreparedAppendBatch prepareAppendBatchOffThread(
            CommentsResponse response,
            Set<Long> existingCommentIds,
            Map<Long, CommentsResponse.CommentItem> existingAllByIdSnapshot,
            int repliesLimit,
            Set<Long> expandedParentIds,
            int primaryColor,
            int purpleColor) {
        List<CommentsResponse.CommentItem> allItems = extractAllItems(response);
        Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedMap =
                preParseCommentsBatchIntoCache(allItems, sharedParsedCache, primaryColor, purpleColor);

        if (response == null || response.getData() == null) {
            return new PreparedAppendBatch(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    parsedMap,
                    false,
                    null
            );
        }

        long nowMs = System.currentTimeMillis();
        Map<Long, CommentsResponse.CommentItem> workingAllById = existingAllByIdSnapshot != null
                ? new HashMap<>(existingAllByIdSnapshot) : new HashMap<>();
        Map<Long, List<CommentsResponse.CommentItem>> newChildrenMap = new HashMap<>();

        List<CommentsResponse.CommentItem> newRoots = response.getData().getRoot();
        List<CommentsResponse.CommentItem> addedRoots = new ArrayList<>();
        Set<Long> addedRootIds = new HashSet<>();
        if (newRoots != null) {
            for (CommentsResponse.CommentItem r : newRoots) {
                if (r == null || r.getId() <= 0) continue;
                if ((existingCommentIds != null && existingCommentIds.contains(r.getId()))
                        || workingAllById.containsKey(r.getId())) {
                    continue;
                }
                workingAllById.put(r.getId(), r);
                addedRoots.add(r);
                addedRootIds.add(r.getId());
            }
        }

        List<CommentsResponse.CommentItem> rawReplies = response.getData().getReplies();
        List<CommentsResponse.CommentItem> addedReplies = new ArrayList<>();
        boolean attachesToEarlierRoot = false;
        if (rawReplies != null) {
            for (CommentsResponse.CommentItem c : rawReplies) {
                if (c == null || c.getId() <= 0) continue;
                if ((existingCommentIds != null && existingCommentIds.contains(c.getId()))
                        || workingAllById.containsKey(c.getId())) {
                    continue;
                }
                workingAllById.put(c.getId(), c);
                addedReplies.add(c);
            }
            for (CommentsResponse.CommentItem c : addedReplies) {
                Long bucketKey = resolveBucketKey(c, workingAllById);
                if (bucketKey != null) {
                    if (!addedRootIds.contains(bucketKey) && !addedRepliesContainId(addedReplies, bucketKey)) {
                        attachesToEarlierRoot = true;
                    }
                    List<CommentsResponse.CommentItem> list = newChildrenMap.get(bucketKey);
                    if (list == null) {
                        list = new ArrayList<>();
                        newChildrenMap.put(bucketKey, list);
                    }
                    list.add(c);
                }
            }
        }

        if (addedRoots.isEmpty()) {
            return new PreparedAppendBatch(
                    addedRoots,
                    addedReplies,
                    Collections.emptyList(),
                    parsedMap,
                    !addedReplies.isEmpty() || attachesToEarlierRoot,
                    null
            );
        }

        List<CommentsAdapter.DisplayItem> newDisplayItems = new ArrayList<>(addedRoots.size() * 2);
        for (CommentsResponse.CommentItem addedRoot : addedRoots) {
            collectWithChildrenStatic(
                    addedRoot,
                    0,
                    workingAllById,
                    newChildrenMap,
                    repliesLimit,
                    expandedParentIds,
                    nowMs,
                    newDisplayItems
            );
        }

        return new PreparedAppendBatch(
                addedRoots,
                addedReplies,
                newDisplayItems,
                parsedMap,
                attachesToEarlierRoot,
                null
        );
    }

    private static boolean addedRepliesContainId(List<CommentsResponse.CommentItem> replies, long id) {
        for (CommentsResponse.CommentItem item : replies) {
            if (item != null && item.getId() == id) return true;
        }
        return false;
    }

    /**
     * Чистая сборка дерева и плоского списка DisplayItem (без побочных эффектов, безопасно для фонового потока).
     */
    public static PreparedTreeSnapshot compileSnapshot(
            List<CommentsResponse.CommentItem> stickyComments,
            List<CommentsResponse> pages,
            @Nullable CommentsResponse.CommentItem ensurePresentComment,
            boolean prependRootIfMissing,
            int repliesLimit,
            Set<Long> expandedParentIds,
            Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedMap,
            long nowMs) {
        Map<Long, CommentsResponse.CommentItem> allById = new HashMap<>();
        List<CommentsResponse.CommentItem> stickyRoots = new ArrayList<>();
        List<CommentsResponse.CommentItem> roots = new ArrayList<>();
        Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId = new HashMap<>();

        if (stickyComments != null) {
            for (CommentsResponse.CommentItem s : stickyComments) {
                if (s != null && s.getId() > 0 && !allById.containsKey(s.getId())) {
                    s.setSticky(true);
                    allById.put(s.getId(), s);
                    stickyRoots.add(s);
                }
            }
        }

        if (pages != null) {
            for (CommentsResponse response : pages) {
                if (response == null || response.getData() == null) continue;
                List<CommentsResponse.CommentItem> pageRoots = response.getData().getRoot();
                if (pageRoots != null) {
                    for (CommentsResponse.CommentItem r : pageRoots) {
                        if (r == null || r.getId() <= 0) continue;
                        if (allById.containsKey(r.getId())) {
                            continue;
                        }
                        allById.put(r.getId(), r);
                        roots.add(r);
                    }
                }
                registerRepliesBatchStatic(response.getData().getReplies(), allById, childrenByParentId);
            }
        }

        if (ensurePresentComment != null && ensurePresentComment.getId() > 0) {
            boolean isReply = isReplyComment(ensurePresentComment);
            if (!allById.containsKey(ensurePresentComment.getId())) {
                if (!isReply
                        || (ensurePresentComment.getRoot_id() != null && allById.containsKey(ensurePresentComment.getRoot_id()))
                        || (ensurePresentComment.getParent_comment() != null && allById.containsKey(ensurePresentComment.getParent_comment()))) {
                    allById.put(ensurePresentComment.getId(), ensurePresentComment);
                    if (isReply) {
                        attachReplyToParentMapStatic(ensurePresentComment, allById, childrenByParentId);
                    } else if (prependRootIfMissing) {
                        roots.add(0, ensurePresentComment);
                    } else {
                        roots.add(ensurePresentComment);
                    }
                }
            }
        }

        List<CommentsAdapter.DisplayItem> flatItems = buildFlatListStatic(
                stickyRoots,
                roots,
                allById,
                childrenByParentId,
                repliesLimit,
                expandedParentIds,
                nowMs
        );

        return new PreparedTreeSnapshot(
                stickyRoots,
                roots,
                allById,
                childrenByParentId,
                flatItems,
                parsedMap
        );
    }

    public static List<CommentsAdapter.DisplayItem> buildFlatListStatic(
            List<CommentsResponse.CommentItem> stickyRoots,
            List<CommentsResponse.CommentItem> roots,
            Map<Long, CommentsResponse.CommentItem> allById,
            Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId,
            int repliesLimit,
            Set<Long> expandedParentIds,
            long nowMs) {
        int estimatedSize = (stickyRoots != null ? stickyRoots.size() : 0)
                + (roots != null ? roots.size() * 2 : 16);
        List<CommentsAdapter.DisplayItem> out = new ArrayList<>(Math.max(16, estimatedSize));
        if (stickyRoots != null) {
            for (CommentsResponse.CommentItem sticky : stickyRoots) {
                if (sticky != null) {
                    collectWithChildrenStatic(sticky, 0, allById, childrenByParentId, repliesLimit, expandedParentIds, nowMs, out);
                }
            }
        }
        if (roots != null) {
            for (CommentsResponse.CommentItem root : roots) {
                if (root != null) {
                    collectWithChildrenStatic(root, 0, allById, childrenByParentId, repliesLimit, expandedParentIds, nowMs, out);
                }
            }
        }
        return out;
    }

    public static void collectWithChildrenStatic(
            CommentsResponse.CommentItem node,
            int level,
            Map<Long, CommentsResponse.CommentItem> allById,
            Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId,
            int repliesLimit,
            Set<Long> expandedParentIds,
            long nowMs,
            List<CommentsAdapter.DisplayItem> out) {
        out.add(createEnrichedDisplayItem(node, level, allById, nowMs));
        if (level >= 1) return;

        List<CommentsResponse.CommentItem> allThreadReplies = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(node.getId());
        collectDescendantsFlatStatic(node.getId(), childrenByParentId, allThreadReplies, visited);

        if (allThreadReplies.isEmpty()) return;

        boolean isUnlimited = (repliesLimit == -1 || repliesLimit >= 11 || repliesLimit <= 0);
        boolean isExpanded = expandedParentIds != null && expandedParentIds.contains(node.getId());
        boolean showAll = isUnlimited || isExpanded || (allThreadReplies.size() <= repliesLimit);

        if (showAll) {
            for (CommentsResponse.CommentItem child : allThreadReplies) {
                out.add(createEnrichedDisplayItem(child, 1, allById, nowMs));
            }
        } else {
            int countToShow = Math.min(repliesLimit, allThreadReplies.size());
            for (int i = 0; i < countToShow; i++) {
                out.add(createEnrichedDisplayItem(allThreadReplies.get(i), 1, allById, nowMs));
            }
            int hiddenCount = allThreadReplies.size() - countToShow;
            if (hiddenCount > 0) {
                List<CommentsResponse.CommentItem> hidden = new ArrayList<>(
                        allThreadReplies.subList(countToShow, allThreadReplies.size()));
                out.add(new CommentsAdapter.DisplayItem(node.getId(), hiddenCount, hidden));
            }
        }
    }

    public static CommentsAdapter.DisplayItem createEnrichedDisplayItem(
            CommentsResponse.CommentItem item,
            int level,
            Map<Long, CommentsResponse.CommentItem> allById,
            long nowMs) {
        String replyToUsername = null;
        if (level > 0 && item != null && allById != null) {
            Long parentId = item.getParent_comment() != null ? item.getParent_comment() : item.getRoot_id();
            CommentsResponse.CommentItem parentComment = parentId != null ? allById.get(parentId) : null;
            if (parentComment != null && parentComment.getUser() != null) {
                replyToUsername = parentComment.getUser().getUsername();
            }
        }
        String formattedDate = item != null
                ? formatRelativeTime(item.getCreated_at_ts(), item.getCreated_at(), nowMs)
                : "";
        String sourceKey = item != null
                ? CommentHtmlProcessor.computeSourceKey(item.getComment() != null ? item.getComment() : "")
                : "0:0";
        return new CommentsAdapter.DisplayItem(item, level, replyToUsername, formattedDate, sourceKey);
    }

    private static void collectDescendantsFlatStatic(
            long parentId,
            Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId,
            List<CommentsResponse.CommentItem> out,
            Set<Long> visited) {
        if (childrenByParentId == null) return;
        List<CommentsResponse.CommentItem> kids = childrenByParentId.get(parentId);
        if (kids == null || kids.isEmpty()) return;
        for (CommentsResponse.CommentItem child : kids) {
            if (child == null || !visited.add(child.getId())) continue;
            out.add(child);
            collectDescendantsFlatStatic(child.getId(), childrenByParentId, out, visited);
        }
    }

    public static int registerRepliesBatchStatic(
            List<CommentsResponse.CommentItem> replies,
            Map<Long, CommentsResponse.CommentItem> allById,
            Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId) {
        if (replies == null || replies.isEmpty()) return 0;
        List<CommentsResponse.CommentItem> newlyAdded = new ArrayList<>();
        for (CommentsResponse.CommentItem c : replies) {
            if (c == null || c.getId() <= 0 || allById.containsKey(c.getId())) continue;
            allById.put(c.getId(), c);
            newlyAdded.add(c);
        }
        for (CommentsResponse.CommentItem c : newlyAdded) {
            attachReplyToParentMapStatic(c, allById, childrenByParentId);
        }
        return newlyAdded.size();
    }

    public static void attachReplyToParentMapStatic(
            CommentsResponse.CommentItem c,
            Map<Long, CommentsResponse.CommentItem> allById,
            Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId) {
        if (c == null) return;
        Long bucketKey = resolveBucketKey(c, allById);
        if (bucketKey == null) return;
        List<CommentsResponse.CommentItem> list = childrenByParentId.get(bucketKey);
        if (list == null) {
            list = new ArrayList<>();
            childrenByParentId.put(bucketKey, list);
        }
        for (CommentsResponse.CommentItem existing : list) {
            if (existing != null && existing.getId() == c.getId()) {
                return;
            }
        }
        list.add(c);
    }

    @Nullable
    private static Long resolveBucketKey(CommentsResponse.CommentItem c,
                                        Map<Long, CommentsResponse.CommentItem> allById) {
        Long parentId = c.getParent_comment();
        Long rootId = c.getRoot_id();
        if (parentId != null && parentId > 0 && allById.containsKey(parentId)) {
            return parentId;
        } else if (rootId != null && rootId > 0) {
            return rootId;
        } else if (parentId != null && parentId > 0) {
            return parentId;
        }
        return null;
    }

    public static boolean isReplyComment(CommentsResponse.CommentItem comment) {
        if (comment == null) return false;
        return (comment.getParent_comment() != null && comment.getParent_comment() > 0)
                || (comment.getRoot_id() != null && comment.getRoot_id() > 0)
                || comment.getComment_level() > 0;
    }

    public static DiffUtil.DiffResult calculateDisplayDiff(
            List<CommentsAdapter.DisplayItem> oldList,
            List<CommentsAdapter.DisplayItem> newList) {
        final List<CommentsAdapter.DisplayItem> safeOld = oldList != null ? oldList : Collections.emptyList();
        final List<CommentsAdapter.DisplayItem> safeNew = newList != null ? newList : Collections.emptyList();
        return DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return safeOld.size();
            }

            @Override
            public int getNewListSize() {
                return safeNew.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                CommentsAdapter.DisplayItem o = safeOld.get(oldItemPosition);
                CommentsAdapter.DisplayItem n = safeNew.get(newItemPosition);
                if (o.getType() != n.getType()) return false;
                if (o.getType() == CommentsAdapter.DisplayItem.TYPE_SHOW_MORE) {
                    return o.getParentId() == n.getParentId();
                }
                return o.getItem() != null && n.getItem() != null
                        && o.getItem().getId() == n.getItem().getId();
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                CommentsAdapter.DisplayItem o = safeOld.get(oldItemPosition);
                CommentsAdapter.DisplayItem n = safeNew.get(newItemPosition);
                if (o.getType() == CommentsAdapter.DisplayItem.TYPE_SHOW_MORE) {
                    return o.getHiddenCount() == n.getHiddenCount();
                }
                CommentsResponse.CommentItem oi = o.getItem();
                CommentsResponse.CommentItem ni = n.getItem();
                if (oi == null || ni == null) return oi == ni;
                if (o.getLevel() != n.getLevel()) return false;
                if (oi.isSticky() != ni.isSticky()) return false;
                if (!Objects.equals(o.getSourceHashKey(), n.getSourceHashKey())) return false;
                int oldScore = oi.getVotes() != null ? (oi.getVotes().getUp() - oi.getVotes().getDown()) : 0;
                int newScore = ni.getVotes() != null ? (ni.getVotes().getUp() - ni.getVotes().getDown()) : 0;
                Object oldUserVote = oi.getVotes() != null ? oi.getVotes().getUser() : null;
                Object newUserVote = ni.getVotes() != null ? ni.getVotes().getUser() : null;
                return oldScore == newScore && Objects.equals(oldUserVote, newUserVote);
            }
        }, false);
    }

    public static String formatRelativeTime(long timestamp, String rawCreatedAt, long nowMs) {
        if (timestamp > 0) {
            if (timestamp < 10000000000L) {
                timestamp *= 1000L;
            }
            long diff = nowMs - timestamp;
            if (diff < 0) diff = 0;

            long seconds = diff / 1000L;
            long minutes = seconds / 60L;
            long hours = minutes / 60L;
            long days = hours / 24L;

            if (days > 0) {
                return days + " " + getDaysPlural((int) days) + " назад";
            } else if (hours > 0) {
                return hours + " " + getHoursPlural((int) hours) + " назад";
            } else if (minutes > 0) {
                return minutes + " " + getMinutesPlural((int) minutes) + " назад";
            } else {
                return "только что";
            }
        }
        if (rawCreatedAt != null && !rawCreatedAt.isEmpty()) {
            return rawCreatedAt;
        }
        return "";
    }

    private static String getDaysPlural(int n) {
        int rem10 = n % 10;
        int rem100 = n % 100;
        if (rem100 >= 11 && rem100 <= 19) return "дней";
        if (rem10 == 1) return "день";
        if (rem10 >= 2 && rem10 <= 4) return "дня";
        return "дней";
    }

    private static String getHoursPlural(int n) {
        int rem10 = n % 10;
        int rem100 = n % 100;
        if (rem100 >= 11 && rem100 <= 19) return "часов";
        if (rem10 == 1) return "час";
        if (rem10 >= 2 && rem10 <= 4) return "часа";
        return "часов";
    }

    private static String getMinutesPlural(int n) {
        int rem10 = n % 10;
        int rem100 = n % 100;
        if (rem100 >= 11 && rem100 <= 19) return "минут";
        if (rem10 == 1) return "минуту";
        if (rem10 >= 2 && rem10 <= 4) return "минуты";
        return "минут";
    }

    public void removeCommentById(long commentId) {
        sharedParsedCache.remove(commentId);
        latestPreparedSnapshot = null;
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
        latestPreparedSnapshot = null;

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

        boolean isReply = isReplyComment(item);

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
        latestPreparedSnapshot = null;

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
