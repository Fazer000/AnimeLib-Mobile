package com.example.animelib.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewStub;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.settings.CommentSettingsBottomSheet;
import com.example.animelib.util.CommentFormattingHelper;
import com.example.animelib.util.CommentHtmlProcessor;
import com.example.animelib.util.ImageLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CommentsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int CHUNK_INSERT_SIZE = 15;
    private static final long CHUNK_FRAME_DELAY_MS = 24L;
    public static final int INITIAL_PORTRAIT_RENDER_COUNT = 6;
    public static final int PORTRAIT_SCROLL_BATCH_SIZE = 12;

    private static final int COLOR_VOTE_UP = 0xFF4ADE80;
    private static final int COLOR_VOTE_DOWN = 0xFFF87171;

    public interface CommentActionListener {
        void onReplyClicked(CommentsResponse.CommentItem comment);
        void onReplyCancelled(CommentsResponse.CommentItem comment);
        void onSendInlineReply(CommentsResponse.CommentItem comment, CharSequence rawText, EditText inputField);
        void onVoteClicked(CommentsResponse.CommentItem comment, int targetVote);
        void onMoreActionsClicked(View anchorView, CommentsResponse.CommentItem comment);
        default void onInlineReplyInputBound(EditText inputField, View inlineReplyContainer) {}
    }

    private CommentActionListener commentActionListener;
    private Long activeReplyCommentId = null;
    private Long pendingAutoFocusReplyCommentId = null;
    private Long submittingReplyCommentId = null;

    public static void applySendButtonLoadingState(View sendButton, View loadingContainer, boolean isLoading) {
        if (sendButton != null) {
            sendButton.setEnabled(!isLoading);
            sendButton.setVisibility(isLoading ? View.INVISIBLE : View.VISIBLE);
        }
        if (loadingContainer != null) {
            loadingContainer.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        }
    }

    private int repliesLimit = 3;
    private final Set<Long> expandedParentIds = new HashSet<>();

    // Cached theme/metrics values for zero-allocation binding
    private CommentHtmlProcessor htmlProcessor;
    private Integer cachedNeutralVoteColor;
    private int cachedPrimaryTextColor = Color.WHITE;
    private int cachedPurpleColor = 0xFF8B5CF6;
    private float cachedDensity = 0f;
    private int padBaseH;
    private int padReplyIndent;
    private int padBaseV;
    private int stickyMarginPx;
    private int avatarTargetPx;
    private float avatarCornerRadiusPx;
    private ViewOutlineProvider avatarOutlineProvider;

    // Portrait NestedScrollView virtualization: avoids inflating/measuring all 60+ items offscreen at once
    private boolean portraitViewportMode = false;
    private final List<DisplayItem> deferredViewportQueue = new ArrayList<>();

    // Progressive batch insertion queue for fixed-size RecyclerViews (Landscape)
    private Handler mainHandler;
    private boolean handlerUnavailable = false;
    private final List<DisplayItem> pendingQueue = new ArrayList<>();
    private Runnable onBatchCompletedListener;
    private final Runnable chunkInsertRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingQueue.isEmpty()) {
                if (onBatchCompletedListener != null) {
                    onBatchCompletedListener.run();
                }
                return;
            }
            int count = Math.min(CHUNK_INSERT_SIZE, pendingQueue.size());
            int insertPos = flat.size();
            List<DisplayItem> slice = pendingQueue.subList(0, count);
            flat.addAll(slice);
            slice.clear();
            safeNotifyItemRangeInserted(insertPos, count);
            if (!pendingQueue.isEmpty()) {
                postToMainDelayed(this, CHUNK_FRAME_DELAY_MS);
            } else if (onBatchCompletedListener != null) {
                onBatchCompletedListener.run();
            }
        }
    };

    private Handler getSafeMainHandler() {
        if (handlerUnavailable) return null;
        if (mainHandler == null) {
            try {
                Looper looper = Looper.getMainLooper();
                if (looper != null) {
                    mainHandler = new Handler(looper);
                } else {
                    handlerUnavailable = true;
                }
            } catch (Throwable t) {
                handlerUnavailable = true;
            }
        }
        return mainHandler;
    }

    private void postToMain(Runnable r) {
        Handler h = getSafeMainHandler();
        if (h != null) {
            try {
                h.post(r);
                return;
            } catch (Throwable ignored) {}
        }
        r.run();
    }

    private void postToMainDelayed(Runnable r, long delayMs) {
        Handler h = getSafeMainHandler();
        if (h != null) {
            try {
                h.postDelayed(r, delayMs);
                return;
            } catch (Throwable ignored) {}
        }
        r.run();
    }

    private void removeFromMain(Runnable r) {
        Handler h = getSafeMainHandler();
        if (h != null) {
            try {
                h.removeCallbacks(r);
            } catch (Throwable ignored) {}
        }
    }

    private void safeNotifyDataSetChanged() {
        try {
            notifyDataSetChanged();
        } catch (Throwable ignored) {}
    }

    private void safeNotifyItemRangeInserted(int start, int count) {
        try {
            notifyItemRangeInserted(start, count);
        } catch (Throwable t) {
            safeNotifyDataSetChanged();
        }
    }

    // Infinite scroll bottom loading indicator
    private boolean isLoadingMore = false;

    public static class DisplayItem {
        public static final int TYPE_COMMENT = 0;
        public static final int TYPE_SHOW_MORE = 1;
        public static final int TYPE_LOADING_FOOTER = 2;

        final int type;
        final CommentsResponse.CommentItem item;
        final int level;
        final long parentId;
        final int hiddenCount;
        final List<CommentsResponse.CommentItem> hiddenReplies;

        DisplayItem(CommentsResponse.CommentItem item, int level) {
            this.type = TYPE_COMMENT;
            this.item = item;
            this.level = level;
            this.parentId = 0;
            this.hiddenCount = 0;
            this.hiddenReplies = null;
        }

        DisplayItem(long parentId, int hiddenCount, List<CommentsResponse.CommentItem> hiddenReplies) {
            this.type = TYPE_SHOW_MORE;
            this.item = null;
            this.level = 1;
            this.parentId = parentId;
            this.hiddenCount = hiddenCount;
            this.hiddenReplies = hiddenReplies;
        }
    }

    // Source storage
    private final List<CommentsResponse.CommentItem> stickyRoots = new ArrayList<>();
    private final Map<Long, CommentsResponse.CommentItem> allById = new HashMap<>();
    private final List<CommentsResponse.CommentItem> roots = new ArrayList<>();
    private final Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId = new HashMap<>();

    // Flattened visible items for RecyclerView (excluding loading footer)
    private final List<DisplayItem> flat = new ArrayList<>();
    private final Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedContentCache = new ConcurrentHashMap<>();

    public void setOnBatchCompletedListener(Runnable listener) {
        this.onBatchCompletedListener = listener;
    }

    public void putParsedContentMap(Map<Long, CommentHtmlProcessor.ParsedCommentContent> map) {
        if (map != null && !map.isEmpty()) {
            parsedContentCache.putAll(map);
        }
    }

    public void setPortraitViewportMode(boolean enabled) {
        this.portraitViewportMode = enabled;
    }

    public boolean isPortraitViewportMode() {
        return portraitViewportMode;
    }

    public boolean hasDeferredViewportItems() {
        return !deferredViewportQueue.isEmpty();
    }

    public int getDeferredViewportCount() {
        return deferredViewportQueue.size();
    }

    /**
     * Подгружает следующую порцию уже распарсенных комментариев в портретный список одним проходом разметки.
     */
    public int revealNextViewportBatch(int maxCount) {
        if (deferredViewportQueue.isEmpty()) {
            return 0;
        }
        int count = Math.min(Math.max(1, maxCount), deferredViewportQueue.size());
        int insertPos = flat.size();
        List<DisplayItem> slice = deferredViewportQueue.subList(0, count);
        flat.addAll(slice);
        slice.clear();
        safeNotifyItemRangeInserted(insertPos, count);
        if (deferredViewportQueue.isEmpty() && onBatchCompletedListener != null) {
            onBatchCompletedListener.run();
        }
        return count;
    }

    public boolean isPendingInsertion() {
        return !pendingQueue.isEmpty();
    }

    public boolean hasAnySourceComments() {
        return !stickyRoots.isEmpty() || !roots.isEmpty();
    }

    public int getTotalTargetCount() {
        return flat.size() + pendingQueue.size() + deferredViewportQueue.size();
    }

    public void cancelPendingInsertions() {
        removeFromMain(chunkInsertRunnable);
        pendingQueue.clear();
        deferredViewportQueue.clear();
    }

    private void flushPendingQueueImmediate() {
        removeFromMain(chunkInsertRunnable);
        if (!pendingQueue.isEmpty()) {
            int insertPos = flat.size();
            int count = pendingQueue.size();
            flat.addAll(pendingQueue);
            pendingQueue.clear();
            safeNotifyItemRangeInserted(insertPos, count);
        }
    }

    public void setLoadingMore(boolean loadingMore) {
        if (this.isLoadingMore == loadingMore) return;
        this.isLoadingMore = loadingMore;
        int footerPos = flat.size();
        try {
            if (loadingMore) {
                notifyItemInserted(footerPos);
            } else {
                notifyItemRemoved(footerPos);
            }
        } catch (Throwable ignored) {
            safeNotifyDataSetChanged();
        }
    }

    public boolean isLoadingMore() {
        return isLoadingMore;
    }

    public void setRepliesLimit(int limit) {
        if (this.repliesLimit == limit) return;
        this.repliesLimit = limit;
        flushPendingQueueImmediate();
        rebuildFlatImmediate();
    }

    public int getRepliesLimit() {
        return repliesLimit;
    }

    public void markParentExpanded(long parentId) {
        expandedParentIds.add(parentId);
    }

    public void setCommentActionListener(CommentActionListener listener) {
        this.commentActionListener = listener;
    }

    public void setActiveReplyCommentId(Long commentId) {
        setActiveReplyCommentId(commentId, false);
    }

    public void setActiveReplyCommentId(Long commentId, boolean requestAutoFocus) {
        if (commentId == null) {
            this.submittingReplyCommentId = null;
            this.pendingAutoFocusReplyCommentId = null;
        } else if (requestAutoFocus) {
            this.pendingAutoFocusReplyCommentId = commentId;
        }
        if (Objects.equals(this.activeReplyCommentId, commentId)) {
            if (requestAutoFocus && commentId != null) {
                notifyCommentChanged(commentId);
            }
            return;
        }
        Long oldId = this.activeReplyCommentId;
        this.activeReplyCommentId = commentId;
        if (oldId != null) {
            notifyCommentChanged(oldId);
        }
        if (commentId != null) {
            notifyCommentChanged(commentId);
        }
    }

    public Long getActiveReplyCommentId() {
        return activeReplyCommentId;
    }

    public void setSubmittingReplyCommentId(Long commentId) {
        this.submittingReplyCommentId = commentId;
    }

    public Long getSubmittingReplyCommentId() {
        return submittingReplyCommentId;
    }

    public void notifyCommentChanged(long commentId) {
        for (int i = 0, size = flat.size(); i < size; i++) {
            DisplayItem di = flat.get(i);
            if (di.type == DisplayItem.TYPE_COMMENT && di.item != null && di.item.getId() == commentId) {
                try {
                    notifyItemChanged(i);
                } catch (Throwable ignored) {}
                return;
            }
        }
    }

    public void clearAll() {
        cancelPendingInsertions();
        isLoadingMore = false;
        activeReplyCommentId = null;
        pendingAutoFocusReplyCommentId = null;
        submittingReplyCommentId = null;
        expandedParentIds.clear();
        stickyRoots.clear();
        allById.clear();
        roots.clear();
        childrenByParentId.clear();
        flat.clear();
        parsedContentCache.clear();
        safeNotifyDataSetChanged();
    }

    /**
     * Установить закреплённые комментарии (если они пришли отдельно).
     */
    public void setStickyComments(List<CommentsResponse.CommentItem> stickyComments) {
        flushPendingQueueImmediate();
        stickyRoots.clear();
        if (stickyComments != null) {
            for (CommentsResponse.CommentItem item : stickyComments) {
                if (item != null) {
                    item.setSticky(true);
                    if (!allById.containsKey(item.getId())) {
                        allById.put(item.getId(), item);
                    } else {
                        CommentsResponse.CommentItem existing = allById.get(item.getId());
                        if (existing != null) existing.setSticky(true);
                    }
                    stickyRoots.add(item);
                }
            }
        }
        // Удаляем дубликаты закреплённых из обычных roots
        if (!stickyRoots.isEmpty() && !roots.isEmpty()) {
            Set<Long> stickyIds = new HashSet<>();
            for (CommentsResponse.CommentItem s : stickyRoots) {
                stickyIds.add(s.getId());
            }
            for (int i = roots.size() - 1; i >= 0; i--) {
                if (stickyIds.contains(roots.get(i).getId())) {
                    roots.remove(i);
                }
            }
        }
        rebuildFlatImmediate();
    }

    /**
     * Задать первую порцию комментариев (закреплённые + 1-я страница) с плавной покадровой вставкой.
     */
    public void setInitialComments(List<CommentsResponse.CommentItem> stickyComments, CommentsResponse response) {
        cancelPendingInsertions();
        isLoadingMore = false;
        allById.clear();
        roots.clear();
        childrenByParentId.clear();
        stickyRoots.clear();

        if (!flat.isEmpty()) {
            flat.clear();
            safeNotifyDataSetChanged();
        }

        if (stickyComments != null) {
            for (CommentsResponse.CommentItem s : stickyComments) {
                if (s != null) {
                    s.setSticky(true);
                    if (!allById.containsKey(s.getId())) {
                        allById.put(s.getId(), s);
                        stickyRoots.add(s);
                    }
                }
            }
        }

        if (response != null && response.getData() != null) {
            List<CommentsResponse.CommentItem> newRoots = response.getData().getRoot();
            List<CommentsResponse.CommentItem> replies = response.getData().getReplies();

            if (newRoots != null) {
                for (CommentsResponse.CommentItem r : newRoots) {
                    if (r == null) continue;
                    if (allById.containsKey(r.getId())) {
                        CommentsResponse.CommentItem existing = allById.get(r.getId());
                        if (existing != null && existing.isSticky()) {
                            continue;
                        }
                    } else {
                        allById.put(r.getId(), r);
                    }
                    roots.add(r);
                }
            }
            registerRepliesBatch(replies);
        }

        List<DisplayItem> targetItems = new ArrayList<>();
        for (CommentsResponse.CommentItem sticky : stickyRoots) {
            collectWithChildren(sticky, 0, targetItems);
        }
        for (CommentsResponse.CommentItem root : roots) {
            collectWithChildren(root, 0, targetItems);
        }

        enqueueProgressiveInsertion(targetItems, true);
    }

    private int registerRepliesBatch(List<CommentsResponse.CommentItem> replies) {
        if (replies == null || replies.isEmpty()) return 0;
        List<CommentsResponse.CommentItem> newlyAdded = new ArrayList<>();
        for (CommentsResponse.CommentItem c : replies) {
            if (c == null || c.getId() <= 0 || allById.containsKey(c.getId())) continue;
            allById.put(c.getId(), c);
            newlyAdded.add(c);
        }
        for (CommentsResponse.CommentItem c : newlyAdded) {
            attachReplyToParentMap(c);
        }
        return newlyAdded.size();
    }

    private void attachReplyToParentMap(CommentsResponse.CommentItem c) {
        if (c == null) return;
        Long parentId = c.getParent_comment();
        Long rootId = c.getRoot_id();
        Long bucketKey;
        if (parentId != null && parentId > 0 && allById.containsKey(parentId)) {
            bucketKey = parentId;
        } else if (rootId != null && rootId > 0) {
            bucketKey = rootId;
        } else if (parentId != null && parentId > 0) {
            bucketKey = parentId;
        } else {
            return;
        }
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

    private Long resolveRootIdForComment(CommentsResponse.CommentItem comment) {
        if (comment == null) return null;
        if (comment.getRoot_id() != null && comment.getRoot_id() > 0) {
            return comment.getRoot_id();
        }
        Long cur = comment.getParent_comment();
        Set<Long> visited = new HashSet<>();
        while (cur != null && cur > 0 && visited.add(cur)) {
            CommentsResponse.CommentItem parent = allById.get(cur);
            if (parent == null) {
                return cur;
            }
            if (parent.getRoot_id() != null && parent.getRoot_id() > 0) {
                return parent.getRoot_id();
            }
            if (parent.getParent_comment() == null || parent.getParent_comment() <= 0) {
                return parent.getId();
            }
            cur = parent.getParent_comment();
        }
        return comment.getParent_comment();
    }

    public boolean containsComment(long commentId) {
        return allById.containsKey(commentId);
    }

    public CommentsResponse.CommentItem getCommentById(long commentId) {
        return allById.get(commentId);
    }

    public int findCommentAdapterPosition(long commentId) {
        for (int i = 0, size = flat.size(); i < size; i++) {
            DisplayItem di = flat.get(i);
            if (di.type == DisplayItem.TYPE_COMMENT && di.item != null && di.item.getId() == commentId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Мгновенно добавить новый комментарий/ответ или обновить отредактированный комментарий в списке.
     */
    public void upsertComment(CommentsResponse.CommentItem comment, boolean prependRoot) {
        if (comment == null || comment.getId() <= 0) return;
        flushPendingQueueImmediate();
        parsedContentCache.remove(comment.getId());

        if (allById.containsKey(comment.getId())) {
            replaceExistingCommentInPlace(comment);
            rebuildFlatInternal(flat.size(), comment.getId());
            return;
        }

        insertCommentIntoSourceStructures(comment, prependRoot);
        rebuildFlatInternal(flat.size() + 1, comment.getId());
    }

    private void replaceExistingCommentInPlace(CommentsResponse.CommentItem updated) {
        CommentsResponse.CommentItem existing = allById.get(updated.getId());
        if (existing != null && existing != updated) {
            if (updated.getComment() != null) existing.setComment(updated.getComment());
            if (updated.getUser() != null) existing.setUser(updated.getUser());
            if (updated.getVotes() != null) existing.setVotes(updated.getVotes());
            if (updated.getCreated_at() != null) existing.setCreated_at(updated.getCreated_at());
            if (updated.getCreated_at_ts() > 0) existing.setCreated_at_ts(updated.getCreated_at_ts());
        } else {
            allById.put(updated.getId(), updated);
        }
    }

    private void insertCommentIntoSourceStructures(CommentsResponse.CommentItem comment, boolean prependRoot) {
        allById.put(comment.getId(), comment);
        boolean isReply = (comment.getParent_comment() != null && comment.getParent_comment() > 0)
                || (comment.getRoot_id() != null && comment.getRoot_id() > 0)
                || comment.getComment_level() > 0;
        if (isReply) {
            Long rootId = resolveRootIdForComment(comment);
            if (rootId != null && rootId > 0) {
                expandedParentIds.add(rootId);
            }
            if (comment.getParent_comment() != null && comment.getParent_comment() > 0) {
                expandedParentIds.add(comment.getParent_comment());
            }
            attachReplyToParentMap(comment);
        } else {
            if (prependRoot) {
                roots.add(0, comment);
            } else {
                roots.add(comment);
            }
        }
    }

    /**
     * Обновить страницы комментариев после перезагрузки с сервера без сброса раскрытых веток и позиции портретного вьюпорта.
     */
    public void replacePagesOnRefresh(List<CommentsResponse.CommentItem> stickyComments,
                                      List<CommentsResponse> pages,
                                      CommentsResponse.CommentItem ensurePresentComment,
                                      boolean prependRootIfMissing) {
        flushPendingQueueImmediate();
        int previousVisibleCount = flat.size();
        cancelPendingInsertions();
        isLoadingMore = false;

        allById.clear();
        roots.clear();
        childrenByParentId.clear();
        stickyRoots.clear();

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
                registerRepliesBatch(response.getData().getReplies());
            }
        }

        Long ensureVisibleId = null;
        if (ensurePresentComment != null && ensurePresentComment.getId() > 0) {
            ensureVisibleId = ensurePresentComment.getId();
            boolean isReply = (ensurePresentComment.getParent_comment() != null && ensurePresentComment.getParent_comment() > 0)
                    || (ensurePresentComment.getRoot_id() != null && ensurePresentComment.getRoot_id() > 0)
                    || ensurePresentComment.getComment_level() > 0;
            if (isReply) {
                Long rootId = resolveRootIdForComment(ensurePresentComment);
                if (rootId != null && rootId > 0) {
                    expandedParentIds.add(rootId);
                }
            }
            if (!allById.containsKey(ensurePresentComment.getId())) {
                if (!isReply || (ensurePresentComment.getRoot_id() != null && allById.containsKey(ensurePresentComment.getRoot_id()))
                        || (ensurePresentComment.getParent_comment() != null && allById.containsKey(ensurePresentComment.getParent_comment()))) {
                    insertCommentIntoSourceStructures(ensurePresentComment, prependRootIfMissing);
                }
            }
        }

        rebuildFlatInternal(previousVisibleCount, ensureVisibleId);
    }

    /**
     * Совместимый метод для полной замены данных одной страницы.
     */
    public void setPageResponse(CommentsResponse response, int page) {
        setInitialComments(new ArrayList<>(stickyRoots), response);
    }

    /**
     * Плавно догрузить следующую страницу комментариев в конец списка без перерисовки существующих элементов.
     */
    public void appendCommentsPage(CommentsResponse response) {
        if (response == null || response.getData() == null) {
            return;
        }

        List<CommentsResponse.CommentItem> newRoots = response.getData().getRoot();
        List<CommentsResponse.CommentItem> replies = response.getData().getReplies();

        List<CommentsResponse.CommentItem> addedRoots = new ArrayList<>();
        if (newRoots != null) {
            for (CommentsResponse.CommentItem r : newRoots) {
                if (r == null) continue;
                if (allById.containsKey(r.getId())) {
                    continue;
                }
                allById.put(r.getId(), r);
                roots.add(r);
                addedRoots.add(r);
            }
        }

        int addedReplies = registerRepliesBatch(replies);

        if (addedRoots.isEmpty()) {
            if (addedReplies > 0) {
                rebuildFlatImmediate();
            }
            return;
        }

        List<DisplayItem> newDisplayItems = new ArrayList<>();
        for (CommentsResponse.CommentItem addedRoot : addedRoots) {
            collectWithChildren(addedRoot, 0, newDisplayItems);
        }

        enqueueProgressiveInsertion(newDisplayItems, false);
    }

    public void appendResponse(CommentsResponse response, boolean append) {
        if (!append) {
            setInitialComments(new ArrayList<>(stickyRoots), response);
        } else {
            appendCommentsPage(response);
        }
    }

    private void enqueueProgressiveInsertion(List<DisplayItem> itemsToInsert, boolean isInitialBatch) {
        if (itemsToInsert == null || itemsToInsert.isEmpty()) {
            if (onBatchCompletedListener != null) {
                onBatchCompletedListener.run();
            }
            return;
        }

        if (portraitViewportMode) {
            if (isInitialBatch) {
                int initialCount = Math.min(INITIAL_PORTRAIT_RENDER_COUNT, itemsToInsert.size());
                if (initialCount > 0) {
                    int insertPos = flat.size();
                    flat.addAll(itemsToInsert.subList(0, initialCount));
                    safeNotifyItemRangeInserted(insertPos, initialCount);
                }
                if (itemsToInsert.size() > initialCount) {
                    deferredViewportQueue.addAll(itemsToInsert.subList(initialCount, itemsToInsert.size()));
                } else if (onBatchCompletedListener != null) {
                    onBatchCompletedListener.run();
                }
            } else {
                boolean hadDeferred = !deferredViewportQueue.isEmpty();
                deferredViewportQueue.addAll(itemsToInsert);
                if (!hadDeferred) {
                    revealNextViewportBatch(PORTRAIT_SCROLL_BATCH_SIZE);
                }
            }
            return;
        }

        boolean wasQueueRunning = !pendingQueue.isEmpty();
        pendingQueue.addAll(itemsToInsert);

        if (!wasQueueRunning) {
            removeFromMain(chunkInsertRunnable);
            if (isInitialBatch) {
                chunkInsertRunnable.run();
            } else {
                postToMain(chunkInsertRunnable);
            }
        }
    }

    private void rebuildFlatImmediate() {
        rebuildFlatInternal(flat.size(), null);
    }

    private void rebuildFlatInternal(int previousVisibleCount, Long ensureVisibleCommentId) {
        cancelPendingInsertions();
        List<DisplayItem> allItems = new ArrayList<>();
        for (CommentsResponse.CommentItem sticky : stickyRoots) {
            collectWithChildren(sticky, 0, allItems);
        }
        for (CommentsResponse.CommentItem root : roots) {
            collectWithChildren(root, 0, allItems);
        }
        flat.clear();
        if (portraitViewportMode) {
            int minRequired = Math.max(INITIAL_PORTRAIT_RENDER_COUNT, previousVisibleCount);
            if (ensureVisibleCommentId != null) {
                for (int i = 0; i < allItems.size(); i++) {
                    DisplayItem di = allItems.get(i);
                    if (di.type == DisplayItem.TYPE_COMMENT && di.item != null
                            && di.item.getId() == ensureVisibleCommentId) {
                        minRequired = Math.max(minRequired, i + 1);
                        break;
                    }
                }
            }
            int keepCount = Math.min(allItems.size(), minRequired);
            if (keepCount > 0) {
                flat.addAll(allItems.subList(0, keepCount));
            }
            if (allItems.size() > keepCount) {
                deferredViewportQueue.addAll(allItems.subList(keepCount, allItems.size()));
            }
        } else {
            flat.addAll(allItems);
        }
        safeNotifyDataSetChanged();
    }

    private void collectWithChildren(CommentsResponse.CommentItem node, int level, List<DisplayItem> out) {
        out.add(new DisplayItem(node, level));
        if (level >= 1) return;

        List<CommentsResponse.CommentItem> allThreadReplies = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(node.getId());
        collectDescendantsFlat(node.getId(), allThreadReplies, visited);

        if (allThreadReplies.isEmpty()) return;

        boolean isUnlimited = (repliesLimit == -1 || repliesLimit >= 11 || repliesLimit <= 0);
        boolean isExpanded = expandedParentIds.contains(node.getId());
        boolean showAll = isUnlimited || isExpanded || (allThreadReplies.size() <= repliesLimit);

        if (showAll) {
            for (CommentsResponse.CommentItem child : allThreadReplies) {
                out.add(new DisplayItem(child, 1));
            }
        } else {
            int countToShow = Math.min(repliesLimit, allThreadReplies.size());
            for (int i = 0; i < countToShow; i++) {
                out.add(new DisplayItem(allThreadReplies.get(i), 1));
            }
            int hiddenCount = allThreadReplies.size() - countToShow;
            if (hiddenCount > 0) {
                List<CommentsResponse.CommentItem> hidden = new ArrayList<>(allThreadReplies.subList(countToShow, allThreadReplies.size()));
                out.add(new DisplayItem(node.getId(), hiddenCount, hidden));
            }
        }
    }

    private void collectDescendantsFlat(long parentId, List<CommentsResponse.CommentItem> out, Set<Long> visited) {
        List<CommentsResponse.CommentItem> kids = childrenByParentId.get(parentId);
        if (kids == null || kids.isEmpty()) return;
        for (CommentsResponse.CommentItem child : kids) {
            if (child == null || !visited.add(child.getId())) continue;
            out.add(child);
            collectDescendantsFlat(child.getId(), out, visited);
        }
    }

    public void expandReplies(long parentId, int position, List<CommentsResponse.CommentItem> hiddenReplies) {
        if (position < 0 || position >= flat.size()) return;
        expandedParentIds.add(parentId);

        flat.remove(position);
        try {
            notifyItemRemoved(position);
        } catch (Throwable ignored) {}

        List<DisplayItem> newItems = new ArrayList<>();
        if (hiddenReplies != null) {
            for (CommentsResponse.CommentItem child : hiddenReplies) {
                newItems.add(new DisplayItem(child, 1));
            }
        }

        if (!newItems.isEmpty()) {
            // Раскрываем ответы порционно если их очень много, иначе сразу
            if (newItems.size() <= 8) {
                flat.addAll(position, newItems);
                safeNotifyItemRangeInserted(position, newItems.size());
            } else {
                List<DisplayItem> firstBatch = new ArrayList<>(newItems.subList(0, 8));
                flat.addAll(position, firstBatch);
                safeNotifyItemRangeInserted(position, firstBatch.size());
                expandRemainingRepliesChunked(parentId, position + firstBatch.size(), new ArrayList<>(newItems.subList(8, newItems.size())));
            }
        }
    }

    private void expandRemainingRepliesChunked(long parentId, int nextPos, List<DisplayItem> remaining) {
        if (remaining.isEmpty()) return;
        postToMainDelayed(() -> {
            if (!expandedParentIds.contains(parentId) || remaining.isEmpty()) return;
            int safePos = Math.min(nextPos, flat.size());
            int count = Math.min(CHUNK_INSERT_SIZE, remaining.size());
            List<DisplayItem> sub = new ArrayList<>(remaining.subList(0, count));
            flat.addAll(safePos, sub);
            safeNotifyItemRangeInserted(safePos, count);
            if (remaining.size() > count) {
                expandRemainingRepliesChunked(parentId, safePos + count, new ArrayList<>(remaining.subList(count, remaining.size())));
            }
        }, CHUNK_FRAME_DELAY_MS);
    }

    private void ensureThemeMetricsInitialized(Context context) {
        if (cachedDensity > 0f && htmlProcessor != null && cachedNeutralVoteColor != null) {
            return;
        }
        cachedDensity = context.getResources().getDisplayMetrics().density;
        padBaseH = (int) (12 * cachedDensity);
        padReplyIndent = (int) (12 * cachedDensity);
        padBaseV = (int) (8 * cachedDensity);
        stickyMarginPx = (int) (4 * cachedDensity);
        avatarTargetPx = Math.max(72, (int) (36 * cachedDensity));
        avatarCornerRadiusPx = 8f * cachedDensity;
        avatarOutlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), avatarCornerRadiusPx);
            }
        };
        cachedNeutralVoteColor = ContextCompat.getColor(context, R.color.accent_text_color);
        cachedPrimaryTextColor = CommentHtmlProcessor.resolvePrimaryTextColor(context);
        cachedPurpleColor = ContextCompat.getColor(context, R.color.purple_primary);
        htmlProcessor = new CommentHtmlProcessor(context);
    }

    @Override
    public int getItemViewType(int position) {
        if (position >= 0 && position < flat.size()) {
            return flat.get(position).type;
        }
        if (isLoadingMore && position == flat.size()) {
            return DisplayItem.TYPE_LOADING_FOOTER;
        }
        return DisplayItem.TYPE_COMMENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ensureThemeMetricsInitialized(parent.getContext());
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == DisplayItem.TYPE_LOADING_FOOTER) {
            View v = inflater.inflate(R.layout.item_comment_loading, parent, false);
            return new LoadingFooterVH(v);
        }
        if (viewType == DisplayItem.TYPE_SHOW_MORE) {
            View v = inflater.inflate(R.layout.item_comment_show_more, parent, false);
            return new ShowMoreVH(v);
        }
        View v = inflater.inflate(R.layout.item_comment, parent, false);
        CommentVH vh = new CommentVH(v);
        if (vh.avatarView != null && avatarOutlineProvider != null) {
            vh.avatarView.setOutlineProvider(avatarOutlineProvider);
            vh.avatarView.setClipToOutline(true);
        }
        return vh;
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder rawHolder, int position) {
        if (rawHolder instanceof LoadingFooterVH) {
            return;
        }

        if (position < 0 || position >= flat.size()) return;

        if (rawHolder instanceof ShowMoreVH) {
            ShowMoreVH holder = (ShowMoreVH) rawHolder;
            DisplayItem di = flat.get(position);
            if (holder.ivShowMoreIcon != null) {
                holder.ivShowMoreIcon.setRotation(0f);
            }
            if (holder.threadGuideLine != null) {
                holder.threadGuideLine.setVisibility(View.VISIBLE);
            }
            if (holder.tvShowMoreText != null) {
                holder.tvShowMoreText.setText("Показать ещё " + CommentSettingsBottomSheet.formatRepliesPlural(di.hiddenCount));
            }
            if (holder.btnShowMore != null) {
                holder.btnShowMore.setOnClickListener(v -> {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && pos < flat.size()) {
                        DisplayItem item = flat.get(pos);
                        if (item.type == DisplayItem.TYPE_SHOW_MORE) {
                            if (holder.ivShowMoreIcon != null) {
                                holder.ivShowMoreIcon.animate().rotation(180f).setDuration(140).withEndAction(() ->
                                        expandReplies(item.parentId, pos, item.hiddenReplies)
                                ).start();
                            } else {
                                expandReplies(item.parentId, pos, item.hiddenReplies);
                            }
                        }
                    }
                });
            }
            return;
        }

        ensureThemeMetricsInitialized(rawHolder.itemView.getContext());

        CommentVH holder = (CommentVH) rawHolder;
        DisplayItem di = flat.get(position);
        CommentsResponse.CommentItem item = di.item;
        if (item == null) return;

        String username = item.getUser() != null ? item.getUser().getUsername() : "";
        holder.usernameView.setText(username);

        if (di.level > 0) {
            Long parentId = item.getParent_comment() != null ? item.getParent_comment() : item.getRoot_id();
            CommentsResponse.CommentItem parentComment = parentId != null ? allById.get(parentId) : null;
            if (parentComment != null && parentComment.getUser() != null) {
                holder.replyToView.setText(parentComment.getUser().getUsername());
                holder.llReply.setVisibility(View.VISIBLE);
            } else {
                holder.llReply.setVisibility(View.GONE);
            }
        } else {
            holder.llReply.setVisibility(View.GONE);
        }

        String commentText = item.getComment() != null ? item.getComment() : "";
        String expectedSourceKey = CommentHtmlProcessor.computeSourceKey(commentText);
        CommentHtmlProcessor.ParsedCommentContent parsed = parsedContentCache.get(item.getId());
        if (parsed == null || !expectedSourceKey.equals(parsed.sourceHashKey)) {
            parsed = CommentHtmlProcessor.parseHtmlOffThread(commentText, cachedPrimaryTextColor, cachedPurpleColor);
            parsedContentCache.put(item.getId(), parsed);
        }

        if (parsed.isSimple()) {
            holder.commentHtmlView.setVisibility(View.VISIBLE);
            if (holder.spoilerContainer.getVisibility() != View.GONE) {
                holder.spoilerContainer.removeAllViews();
                holder.spoilerContainer.setVisibility(View.GONE);
            }
            holder.spoilerContainer.setTag(null);
            holder.commentHtmlView.setText(parsed.simpleText);
        } else {
            holder.commentHtmlView.setVisibility(View.GONE);
            holder.spoilerContainer.setVisibility(View.VISIBLE);
            String renderTag = item.getId() + ":" + parsed.sourceHashKey;
            Object currentTag = holder.spoilerContainer.getTag();
            if (!renderTag.equals(currentTag)) {
                holder.spoilerContainer.setTag(renderTag);
                htmlProcessor.renderParsedBlocks(parsed, holder.spoilerContainer);
            }
        }

        String dateStr = formatRelativeTime(item.getCreated_at_ts(), item.getCreated_at());
        holder.dateView.setText(dateStr);

        int neutralColor = cachedNeutralVoteColor != null ? cachedNeutralVoteColor : Color.GRAY;
        if (item.getVotes() != null) {
            int score = item.getVotes().getUp() - item.getVotes().getDown();
            if (score > 0) {
                holder.votesView.setText(String.valueOf(score));
                holder.votesView.setTextColor(COLOR_VOTE_UP);
            } else if (score < 0) {
                holder.votesView.setText(String.valueOf(Math.abs(score)));
                holder.votesView.setTextColor(COLOR_VOTE_DOWN);
            } else {
                holder.votesView.setText("0");
                holder.votesView.setTextColor(neutralColor);
            }
        } else {
            holder.votesView.setText("0");
            holder.votesView.setTextColor(neutralColor);
        }

        Integer userVote = getUserVoteValue(item.getVotes());
        if (holder.voteUpIcon instanceof ImageView) {
            ((ImageView) holder.voteUpIcon).setColorFilter(
                    (userVote != null && userVote == 1) ? COLOR_VOTE_UP : neutralColor
            );
        }
        if (holder.voteDownIcon instanceof ImageView) {
            ((ImageView) holder.voteDownIcon).setColorFilter(
                    (userVote != null && userVote == 0) ? COLOR_VOTE_DOWN : neutralColor
            );
        }

        boolean isReplyingToThis = (activeReplyCommentId != null && activeReplyCommentId.equals(item.getId()));
        if (isReplyingToThis) {
            holder.ensureInlineReplyInflated();
            if (holder.inlineReplyContainer != null) {
                holder.inlineReplyContainer.setVisibility(View.VISIBLE);
                boolean isSubmittingThisReply = (submittingReplyCommentId != null && submittingReplyCommentId.equals(item.getId()));
                applySendButtonLoadingState(holder.btnInlineSendComment, holder.inlineSendCommentLoadingContainer, isSubmittingThisReply);

                String replyToUsername = (item.getUser() != null && item.getUser().getUsername() != null)
                        ? item.getUser().getUsername() : "пользователю";
                if (holder.tvInlineReplyToText != null) {
                    holder.tvInlineReplyToText.setText("Ответ для @" + replyToUsername);
                }
                if (holder.btnInlineCancelReply != null) {
                    holder.btnInlineCancelReply.setOnClickListener(v -> {
                        if (submittingReplyCommentId != null && submittingReplyCommentId.equals(item.getId())) {
                            return;
                        }
                        setActiveReplyCommentId(null);
                        if (commentActionListener != null) {
                            commentActionListener.onReplyCancelled(item);
                        }
                    });
                }
                if (holder.inlineCommentInputField != null) {
                    CommentFormattingHelper.attachFormattingTextWatcher(holder.inlineCommentInputField);
                    if (commentActionListener != null) {
                        commentActionListener.onInlineReplyInputBound(holder.inlineCommentInputField, holder.inlineReplyContainer);
                    }
                    boolean shouldAutoFocus = !isSubmittingThisReply
                            && pendingAutoFocusReplyCommentId != null
                            && pendingAutoFocusReplyCommentId.equals(item.getId());
                    if (shouldAutoFocus) {
                        pendingAutoFocusReplyCommentId = null;
                        holder.inlineCommentInputField.post(() -> {
                            if (activeReplyCommentId == null || !activeReplyCommentId.equals(item.getId())) {
                                return;
                            }
                            holder.inlineCommentInputField.requestFocus();
                            try {
                                InputMethodManager imm = (InputMethodManager) holder.itemView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                                if (imm != null) {
                                    imm.showSoftInput(holder.inlineCommentInputField, InputMethodManager.SHOW_IMPLICIT);
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                }
                if (holder.btnInlineFormatBold != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatBold.setOnClickListener(v -> CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, CommentFormattingHelper.FormatType.BOLD));
                }
                if (holder.btnInlineFormatItalic != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatItalic.setOnClickListener(v -> CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, CommentFormattingHelper.FormatType.ITALIC));
                }
                if (holder.btnInlineFormatUnderline != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatUnderline.setOnClickListener(v -> CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, CommentFormattingHelper.FormatType.UNDERLINE));
                }
                if (holder.btnInlineFormatStrike != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatStrike.setOnClickListener(v -> CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, CommentFormattingHelper.FormatType.STRIKE));
                }
                if (holder.btnInlineFormatSpoiler != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatSpoiler.setOnClickListener(v -> CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, CommentFormattingHelper.FormatType.SPOILER));
                }
                if (holder.btnInlineFormatQuote != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatQuote.setOnClickListener(v -> CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, CommentFormattingHelper.FormatType.QUOTE));
                }
                if (holder.btnInlineSendComment != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineSendComment.setOnClickListener(v -> {
                        if (commentActionListener != null) {
                            CharSequence text = holder.inlineCommentInputField.getText();
                            commentActionListener.onSendInlineReply(item, text, holder.inlineCommentInputField);
                        }
                    });
                    holder.inlineCommentInputField.setOnEditorActionListener((v, actionId, event) -> {
                        if (actionId == EditorInfo.IME_ACTION_SEND) {
                            if (commentActionListener != null) {
                                CharSequence text = holder.inlineCommentInputField.getText();
                                commentActionListener.onSendInlineReply(item, text, holder.inlineCommentInputField);
                            }
                            return true;
                        }
                        return false;
                    });
                }
            }
        } else if (holder.inlineReplyContainer != null && holder.inlineReplyContainer.getVisibility() != View.GONE) {
            applySendButtonLoadingState(holder.btnInlineSendComment, holder.inlineSendCommentLoadingContainer, false);
            holder.inlineReplyContainer.setVisibility(View.GONE);
        }

        if (holder.replyActionView != null) {
            holder.replyActionView.setOnClickListener(v -> {
                if (submittingReplyCommentId != null) {
                    return;
                }
                if (isReplyingToThis) {
                    setActiveReplyCommentId(null, false);
                    if (commentActionListener != null) {
                        commentActionListener.onReplyCancelled(item);
                    }
                } else {
                    setActiveReplyCommentId(item.getId(), true);
                    if (commentActionListener != null) {
                        commentActionListener.onReplyClicked(item);
                    }
                }
            });
        }

        if (holder.voteUpIcon != null) {
            holder.voteUpIcon.setOnClickListener(v -> {
                if (commentActionListener != null) {
                    commentActionListener.onVoteClicked(item, 1);
                }
            });
        }

        if (holder.voteDownIcon != null) {
            holder.voteDownIcon.setOnClickListener(v -> {
                if (commentActionListener != null) {
                    commentActionListener.onVoteClicked(item, 0);
                }
            });
        }

        if (holder.moreActionsView != null) {
            holder.moreActionsView.setOnClickListener(v -> {
                if (commentActionListener != null) {
                    commentActionListener.onMoreActionsClicked(v, item);
                }
            });
        }

        if (holder.threadGuideLine != null) {
            holder.threadGuideLine.setVisibility(di.level > 0 ? View.VISIBLE : View.GONE);
        }

        int leftPad = padBaseH + (di.level > 0 ? padReplyIndent : 0);
        if (holder.itemView.getPaddingLeft() != leftPad
                || holder.itemView.getPaddingTop() != padBaseV
                || holder.itemView.getPaddingRight() != padBaseH
                || holder.itemView.getPaddingBottom() != padBaseV) {
            holder.itemView.setPadding(leftPad, padBaseV, padBaseH, padBaseV);
        }

        String avatarUrl = null;
        if (item.getUser() != null && item.getUser().getAvatar() != null) {
            avatarUrl = item.getUser().getAvatar().getUrl();
        }
        ImageLoader.getInstance().loadAvatarInto(holder.avatarView, avatarUrl, R.drawable.ic_avatar_placeholder, avatarTargetPx);

        boolean isSticky = item.isSticky();
        if (holder.stickyBadgeContainer != null) {
            holder.stickyBadgeContainer.setVisibility(isSticky ? View.VISIBLE : View.GONE);
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
        if (isSticky) {
            holder.itemView.setBackgroundResource(R.drawable.bg_sticky_comment);
            if (lp != null && (lp.leftMargin != stickyMarginPx || lp.topMargin != stickyMarginPx
                    || lp.rightMargin != stickyMarginPx || lp.bottomMargin != stickyMarginPx)) {
                lp.setMargins(stickyMarginPx, stickyMarginPx, stickyMarginPx, stickyMarginPx);
                holder.itemView.setLayoutParams(lp);
            }
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            if (lp != null && (lp.leftMargin != 0 || lp.topMargin != 0 || lp.rightMargin != 0 || lp.bottomMargin != 0)) {
                lp.setMargins(0, 0, 0, 0);
                holder.itemView.setLayoutParams(lp);
            }
        }
    }

    private Integer getUserVoteValue(CommentsResponse.Votes votes) {
        if (votes == null || votes.getUser() == null) return null;
        try {
            if (votes.getUser() instanceof Number) {
                return ((Number) votes.getUser()).intValue();
            } else if (votes.getUser() instanceof Boolean) {
                return ((Boolean) votes.getUser()) ? 1 : 0;
            } else {
                String str = votes.getUser().toString().trim();
                if ("true".equalsIgnoreCase(str)) return 1;
                if ("false".equalsIgnoreCase(str)) return 0;
                return Integer.parseInt(str);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String formatRelativeTime(long timestamp, String rawCreatedAt) {
        if (timestamp > 0) {
            long now = System.currentTimeMillis();
            if (timestamp < 10000000000L) {
                timestamp *= 1000L;
            }
            long diff = now - timestamp;
            if (diff < 0) diff = 0;

            long seconds = diff / 1000;
            long minutes = seconds / 60;
            long hours = minutes / 60;
            long days = hours / 24;

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

    private String getDaysPlural(int n) {
        int rem10 = n % 10;
        int rem100 = n % 100;
        if (rem100 >= 11 && rem100 <= 19) return "дней";
        if (rem10 == 1) return "день";
        if (rem10 >= 2 && rem10 <= 4) return "дня";
        return "дней";
    }

    private String getHoursPlural(int n) {
        int rem10 = n % 10;
        int rem100 = n % 100;
        if (rem100 >= 11 && rem100 <= 19) return "часов";
        if (rem10 == 1) return "час";
        if (rem10 >= 2 && rem10 <= 4) return "часа";
        return "часов";
    }

    private String getMinutesPlural(int n) {
        int rem10 = n % 10;
        int rem100 = n % 100;
        if (rem100 >= 11 && rem100 <= 19) return "минут";
        if (rem10 == 1) return "минуту";
        if (rem10 >= 2 && rem10 <= 4) return "минуты";
        return "минут";
    }

    @Override
    public int getItemCount() {
        return flat.size() + (isLoadingMore ? 1 : 0);
    }

    public int getCommentDisplayCount() {
        return flat.size();
    }

    public void removeComment(long commentId) {
        flushPendingQueueImmediate();
        allById.remove(commentId);

        for (int i = 0; i < stickyRoots.size(); i++) {
            if (stickyRoots.get(i).getId() == commentId) {
                stickyRoots.remove(i);
                break;
            }
        }

        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).getId() == commentId) {
                roots.remove(i);
                break;
            }
        }

        for (Map.Entry<Long, List<CommentsResponse.CommentItem>> entry : childrenByParentId.entrySet()) {
            List<CommentsResponse.CommentItem> list = entry.getValue();
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).getId() == commentId) {
                        list.remove(i);
                        break;
                    }
                }
            }
        }

        childrenByParentId.remove(commentId);
        parsedContentCache.remove(commentId);

        rebuildFlatImmediate();
    }

    public static class CommentVH extends RecyclerView.ViewHolder {
        View stickyBadgeContainer;
        ImageView avatarView;
        TextView usernameView;
        TextView replyToView;
        TextView commentHtmlView;
        LinearLayout spoilerContainer;
        TextView dateView;
        TextView votesView;
        LinearLayout llReply;
        View threadGuideLine;
        TextView replyActionView;
        TextView reportActionView;
        View moreActionsView;
        View voteUpIcon;
        View voteDownIcon;

        ViewStub inlineReplyStub;
        View inlineReplyContainer;
        TextView tvInlineReplyToText;
        ImageButton btnInlineCancelReply;
        TextView btnInlineFormatBold;
        TextView btnInlineFormatItalic;
        TextView btnInlineFormatUnderline;
        TextView btnInlineFormatStrike;
        TextView btnInlineFormatSpoiler;
        TextView btnInlineFormatQuote;
        EditText inlineCommentInputField;
        ImageButton btnInlineSendComment;
        View inlineSendCommentLoadingContainer;

        public CommentVH(@NonNull View itemView) {
            super(itemView);
            stickyBadgeContainer = itemView.findViewById(R.id.stickyBadgeContainer);
            avatarView = itemView.findViewById(R.id.avatarView);
            usernameView = itemView.findViewById(R.id.usernameView);
            replyToView = itemView.findViewById(R.id.replyToView);
            commentHtmlView = itemView.findViewById(R.id.commentHtmlView);
            spoilerContainer = itemView.findViewById(R.id.spoilerContainer);
            dateView = itemView.findViewById(R.id.dateView);
            votesView = itemView.findViewById(R.id.votesView);
            llReply = itemView.findViewById(R.id.llReply);
            threadGuideLine = itemView.findViewById(R.id.threadGuideLine);
            replyActionView = itemView.findViewById(R.id.replyActionView);
            reportActionView = itemView.findViewById(R.id.reportActionView);
            moreActionsView = itemView.findViewById(R.id.moreActionsView);
            voteUpIcon = itemView.findViewById(R.id.voteUpIcon);
            voteDownIcon = itemView.findViewById(R.id.voteDownIcon);

            inlineReplyStub = itemView.findViewById(R.id.inlineReplyStub);
            inlineReplyContainer = itemView.findViewById(R.id.inlineReplyContainer);
        }

        void ensureInlineReplyInflated() {
            if (inlineReplyContainer == null && inlineReplyStub != null) {
                inlineReplyContainer = inlineReplyStub.inflate();
                inlineReplyStub = null;
            }
            if (inlineReplyContainer != null && inlineCommentInputField == null) {
                tvInlineReplyToText = inlineReplyContainer.findViewById(R.id.tvInlineReplyToText);
                btnInlineCancelReply = inlineReplyContainer.findViewById(R.id.btnInlineCancelReply);
                btnInlineFormatBold = inlineReplyContainer.findViewById(R.id.btnInlineFormatBold);
                btnInlineFormatItalic = inlineReplyContainer.findViewById(R.id.btnInlineFormatItalic);
                btnInlineFormatUnderline = inlineReplyContainer.findViewById(R.id.btnInlineFormatUnderline);
                btnInlineFormatStrike = inlineReplyContainer.findViewById(R.id.btnInlineFormatStrike);
                btnInlineFormatSpoiler = inlineReplyContainer.findViewById(R.id.btnInlineFormatSpoiler);
                btnInlineFormatQuote = inlineReplyContainer.findViewById(R.id.btnInlineFormatQuote);
                inlineCommentInputField = inlineReplyContainer.findViewById(R.id.inlineCommentInputField);
                btnInlineSendComment = inlineReplyContainer.findViewById(R.id.btnInlineSendComment);
                inlineSendCommentLoadingContainer = inlineReplyContainer.findViewById(R.id.inlineSendCommentLoadingContainer);
            }
        }
    }

    public static class ShowMoreVH extends RecyclerView.ViewHolder {
        public final View threadGuideLine;
        public final View btnShowMore;
        public final ImageView ivShowMoreIcon;
        public final TextView tvShowMoreText;

        public ShowMoreVH(@NonNull View itemView) {
            super(itemView);
            threadGuideLine = itemView.findViewById(R.id.threadGuideLine);
            btnShowMore = itemView.findViewById(R.id.btnShowMore);
            ivShowMoreIcon = itemView.findViewById(R.id.ivShowMoreIcon);
            tvShowMoreText = itemView.findViewById(R.id.tvShowMoreText);
        }
    }

    public static class LoadingFooterVH extends RecyclerView.ViewHolder {
        public LoadingFooterVH(@NonNull View itemView) {
            super(itemView);
        }
    }
}