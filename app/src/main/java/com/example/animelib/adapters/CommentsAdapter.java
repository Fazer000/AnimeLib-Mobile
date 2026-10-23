package com.example.animelib.adapters;

import android.annotation.SuppressLint;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.util.ImageLoader;
import com.example.animelib.util.CommentHtmlProcessor;
import com.example.animelib.settings.CommentSettingsBottomSheet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CommentsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface CommentActionListener {
        void onReplyClicked(CommentsResponse.CommentItem comment);
        void onReplyCancelled(CommentsResponse.CommentItem comment);
        void onSendInlineReply(CommentsResponse.CommentItem comment, CharSequence rawText, EditText inputField);
        void onVoteClicked(CommentsResponse.CommentItem comment, int targetVote);
        void onMoreActionsClicked(View anchorView, CommentsResponse.CommentItem comment);
    }

    private CommentActionListener commentActionListener;
    private Long activeReplyCommentId = null;

    private int repliesLimit = 3;
    private final Set<Long> expandedParentIds = new HashSet<>();

    public void setRepliesLimit(int limit) {
        this.repliesLimit = limit;
        rebuildFlat(-1);
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
        this.activeReplyCommentId = commentId;
        try {
            notifyDataSetChanged();
        } catch (Exception ignored) {}
    }

    public Long getActiveReplyCommentId() {
        return activeReplyCommentId;
    }

    public static class DisplayItem {
        public static final int TYPE_COMMENT = 0;
        public static final int TYPE_SHOW_MORE = 1;

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

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());

    // Source storage
    private final List<CommentsResponse.CommentItem> stickyRoots = new ArrayList<>();
    private final Map<Long, CommentsResponse.CommentItem> allById = new HashMap<>();
    private final List<CommentsResponse.CommentItem> roots = new ArrayList<>();
    private final Map<Long, List<CommentsResponse.CommentItem>> childrenByParentId = new HashMap<>();

    // Flattened for RecyclerView
    private final List<DisplayItem> flat = new ArrayList<>();
    private final Map<Long, CharSequence> simpleSpannedCache = new HashMap<>();
    private int currentPage = 1;

    public void clearAll() {
        currentPage = 1;
        expandedParentIds.clear();
        stickyRoots.clear();
        allById.clear();
        roots.clear();
        childrenByParentId.clear();
        flat.clear();
        simpleSpannedCache.clear();
        notifyDataSetChanged();
    }

    public void setStickyComments(List<CommentsResponse.CommentItem> stickyComments) {
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
        rebuildFlat(-1);
    }

    public void setPageResponse(CommentsResponse response, int page) {
        this.currentPage = page;
        allById.clear();
        roots.clear();
        childrenByParentId.clear();
        flat.clear();
        simpleSpannedCache.clear();

        if (page <= 1) {
            for (CommentsResponse.CommentItem s : stickyRoots) {
                if (s != null) {
                    s.setSticky(true);
                    allById.put(s.getId(), s);
                }
            }
        }

        if (response == null || response.getData() == null) {
            rebuildFlat(-1);
            return;
        }

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
        if (replies != null) {
            for (CommentsResponse.CommentItem c : replies) {
                if (c == null || allById.containsKey(c.getId())) continue;
                allById.put(c.getId(), c);
                Long parentId = c.getParent_comment();
                if (parentId == null) parentId = c.getRoot_id();
                if (parentId == null) continue;
                List<CommentsResponse.CommentItem> list = childrenByParentId.get(parentId);
                if (list == null) {
                    list = new ArrayList<>();
                    childrenByParentId.put(parentId, list);
                }
                list.add(c);
            }
        }

        rebuildFlat(-1);
    }

    public void appendResponse(CommentsResponse response, boolean append) {
        if (!append) {
            setPageResponse(response, 1);
            return;
        }

        if (response == null || response.getData() == null) {
            return;
        }

        int previousFlatSize = flat.size();

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
        if (replies != null) {
            for (CommentsResponse.CommentItem c : replies) {
                if (c == null || allById.containsKey(c.getId())) continue;
                allById.put(c.getId(), c);
                Long parentId = c.getParent_comment();
                if (parentId == null) parentId = c.getRoot_id();
                if (parentId == null) continue;
                List<CommentsResponse.CommentItem> list = childrenByParentId.get(parentId);
                if (list == null) {
                    list = new ArrayList<>();
                    childrenByParentId.put(parentId, list);
                }
                list.add(c);
            }
        }

        rebuildFlat(previousFlatSize);
    }

    private void rebuildFlat(int previousFlatSize) {
        flat.clear();
        if (currentPage <= 1) {
            for (CommentsResponse.CommentItem sticky : stickyRoots) {
                addWithChildren(sticky, 0);
            }
        }
        for (CommentsResponse.CommentItem root : roots) {
            addWithChildren(root, 0);
        }

        if (previousFlatSize >= 0) {
            int newItemsCount = flat.size() - previousFlatSize;
            if (newItemsCount > 0) {
                notifyItemRangeInserted(previousFlatSize, newItemsCount);
            }
        } else {
            notifyDataSetChanged();
        }
    }

    private void addWithChildren(CommentsResponse.CommentItem node, int level) {
        flat.add(new DisplayItem(node, level));
        List<CommentsResponse.CommentItem> kids = childrenByParentId.get(node.getId());
        if (kids == null || kids.isEmpty()) return;
        
        // Ограничиваем глубину дерева до 1 уровня (level 0 и 1)
        if (level >= 1) return;
        
        boolean isUnlimited = (repliesLimit == -1 || repliesLimit >= 11 || repliesLimit <= 0);
        boolean isExpanded = expandedParentIds.contains(node.getId());
        boolean showAll = isUnlimited || isExpanded || (kids.size() <= repliesLimit);

        if (showAll) {
            for (CommentsResponse.CommentItem child : kids) {
                addWithChildren(child, level + 1);
            }
        } else {
            int countToShow = Math.min(repliesLimit, kids.size());
            for (int i = 0; i < countToShow; i++) {
                addWithChildren(kids.get(i), level + 1);
            }
            int hiddenCount = kids.size() - countToShow;
            if (hiddenCount > 0) {
                List<CommentsResponse.CommentItem> hidden = new ArrayList<>(kids.subList(countToShow, kids.size()));
                flat.add(new DisplayItem(node.getId(), hiddenCount, hidden));
            }
        }
    }

    public void expandReplies(long parentId, int position, List<CommentsResponse.CommentItem> hiddenReplies) {
        if (position < 0 || position >= flat.size()) return;
        expandedParentIds.add(parentId);

        flat.remove(position);
        notifyItemRemoved(position);

        List<DisplayItem> newItems = new ArrayList<>();
        if (hiddenReplies != null) {
            for (CommentsResponse.CommentItem child : hiddenReplies) {
                newItems.add(new DisplayItem(child, 1));
            }
        }

        if (!newItems.isEmpty()) {
            flat.addAll(position, newItems);
            notifyItemRangeInserted(position, newItems.size());
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position >= 0 && position < flat.size()) {
            return flat.get(position).type;
        }
        return DisplayItem.TYPE_COMMENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == DisplayItem.TYPE_SHOW_MORE) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_comment_show_more, parent, false);
            return new ShowMoreVH(v);
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_comment, parent, false);
        return new CommentVH(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder rawHolder, int position) {
        if (rawHolder instanceof ShowMoreVH) {
            ShowMoreVH holder = (ShowMoreVH) rawHolder;
            DisplayItem di = flat.get(position);
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
                                holder.ivShowMoreIcon.animate().rotation(180f).setDuration(160).withEndAction(() -> {
                                    expandReplies(item.parentId, pos, item.hiddenReplies);
                                }).start();
                            } else {
                                expandReplies(item.parentId, pos, item.hiddenReplies);
                            }
                        }
                    }
                });
            }
            return;
        }

        CommentVH holder = (CommentVH) rawHolder;
        DisplayItem di = flat.get(position);
        CommentsResponse.CommentItem item = di.item;
        if (item == null) return;

        // Показываем имя пользователя
        String username = item.getUser() != null ? item.getUser().getUsername() : "";
        holder.usernameView.setText(username);
        
        // Показываем кому отвечают (если это ответ)
        if (di.level > 0) {
            // Это ответ - нужно найти родительский комментарий
            Long parentId = item.getParent_comment() != null ? item.getParent_comment() : item.getRoot_id();
            if (parentId != null) {
                CommentsResponse.CommentItem parentComment = allById.get(parentId);
                if (parentComment != null && parentComment.getUser() != null) {
                    String parentUsername = parentComment.getUser().getUsername();
                    holder.replyToView.setText(parentUsername);
                    holder.llReply.setVisibility(View.VISIBLE);
                } else {
                    holder.llReply.setVisibility(View.GONE);
                }
            } else {
                holder.llReply.setVisibility(View.GONE);
            }
        } else {
            // Это корневой комментарий - скрываем llReply
            holder.llReply.setVisibility(View.GONE);
        }
        String commentText = item.getComment() != null ? item.getComment() : "";

        boolean hasComplexHtml = commentText.contains("spoiler-node") ||
            commentText.contains("<blockquote>") || 
            commentText.contains("<strong>") || 
            commentText.contains("<em>") || 
            commentText.contains("<u>") || 
            commentText.contains("<strike>");

        if (hasComplexHtml) {
            holder.commentHtmlView.setVisibility(View.GONE);
            holder.spoilerContainer.setVisibility(View.VISIBLE);
            
            Long currentTag = (Long) holder.spoilerContainer.getTag();
            if (currentTag == null || !currentTag.equals(item.getId())) {
                holder.spoilerContainer.setTag(item.getId());
                CommentHtmlProcessor processor = new CommentHtmlProcessor(holder.itemView.getContext());
                String cleanedText = commentText.trim()
                    .replaceAll("\\n\\s*\\n+", "\n")
                    .replaceAll("\\s+$", "")
                    .replaceAll("^\\s+", "");
                processor.processCommentHtml(cleanedText, holder.spoilerContainer);
            }
        } else {
            holder.commentHtmlView.setVisibility(View.VISIBLE);
            holder.spoilerContainer.setVisibility(View.GONE);
            holder.spoilerContainer.setTag(null);

            CharSequence sp = simpleSpannedCache.get(item.getId());
            if (sp == null) {
                String cleanedText = commentText.trim()
                    .replaceAll("\\n\\s*\\n+", "\n")
                    .replaceAll("\\s+$", "")
                    .replaceAll("^\\s+", "")
                    .replaceAll("\\s+\\n", "\n")
                    .replaceAll("\\n\\s+", "\n")
                    .replaceAll("\\s{2,}", " ")
                    .trim();
                CharSequence rawSp = Html.fromHtml(cleanedText, Html.FROM_HTML_MODE_LEGACY);
                sp = trimTrailingNewlines(rawSp);
                simpleSpannedCache.put(item.getId(), sp);
            }
            holder.commentHtmlView.setText(sp);
        }
        
        String dateStr = formatRelativeTime(item.getCreated_at_ts(), item.getCreated_at());
        holder.dateView.setText(dateStr);

        if (item.getVotes() != null) {
            int up = item.getVotes().getUp();
            int down = item.getVotes().getDown();
            int score = up - down;
            if (score > 0) {
                holder.votesView.setText(String.valueOf(score));
                holder.votesView.setTextColor(android.graphics.Color.parseColor("#4ADE80"));
            } else if (score < 0) {
                holder.votesView.setText(String.valueOf(Math.abs(score)));
                holder.votesView.setTextColor(android.graphics.Color.parseColor("#F87171"));
            } else {
                holder.votesView.setText("0");
                holder.votesView.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.accent_text_color));
            }
        } else {
            holder.votesView.setText("0");
            holder.votesView.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.accent_text_color));
        }

        Integer userVote = getUserVoteValue(item.getVotes());
        if (holder.voteUpIcon instanceof ImageView) {
            if (userVote != null && userVote == 1) {
                ((ImageView) holder.voteUpIcon).setColorFilter(android.graphics.Color.parseColor("#4ADE80"));
            } else {
                ((ImageView) holder.voteUpIcon).setColorFilter(holder.itemView.getContext().getResources().getColor(R.color.accent_text_color));
            }
        }
        if (holder.voteDownIcon instanceof ImageView) {
            if (userVote != null && userVote == 0) {
                ((ImageView) holder.voteDownIcon).setColorFilter(android.graphics.Color.parseColor("#F87171"));
            } else {
                ((ImageView) holder.voteDownIcon).setColorFilter(holder.itemView.getContext().getResources().getColor(R.color.accent_text_color));
            }
        }

        boolean isReplyingToThis = (activeReplyCommentId != null && activeReplyCommentId.equals(item.getId()));
        if (holder.inlineReplyContainer != null) {
            if (isReplyingToThis) {
                holder.inlineReplyContainer.setVisibility(View.VISIBLE);
                String replyToUsername = (item.getUser() != null && item.getUser().getUsername() != null)
                        ? item.getUser().getUsername() : "пользователю";
                if (holder.tvInlineReplyToText != null) {
                    holder.tvInlineReplyToText.setText("Ответ для @" + replyToUsername);
                }
                if (holder.btnInlineCancelReply != null) {
                    holder.btnInlineCancelReply.setOnClickListener(v -> {
                        setActiveReplyCommentId(null);
                        if (commentActionListener != null) {
                            commentActionListener.onReplyCancelled(item);
                        }
                    });
                }
                if (holder.inlineCommentInputField != null) {
                    com.example.animelib.util.CommentFormattingHelper.attachFormattingTextWatcher(holder.inlineCommentInputField);
                    holder.inlineCommentInputField.post(() -> {
                        holder.inlineCommentInputField.requestFocus();
                        try {
                            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) holder.itemView.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                            if (imm != null) {
                                imm.showSoftInput(holder.inlineCommentInputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                            }
                        } catch (Exception ignored) {}
                    });
                }
                if (holder.btnInlineFormatBold != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatBold.setOnClickListener(v -> com.example.animelib.util.CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, com.example.animelib.util.CommentFormattingHelper.FormatType.BOLD));
                }
                if (holder.btnInlineFormatItalic != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatItalic.setOnClickListener(v -> com.example.animelib.util.CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, com.example.animelib.util.CommentFormattingHelper.FormatType.ITALIC));
                }
                if (holder.btnInlineFormatUnderline != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatUnderline.setOnClickListener(v -> com.example.animelib.util.CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, com.example.animelib.util.CommentFormattingHelper.FormatType.UNDERLINE));
                }
                if (holder.btnInlineFormatStrike != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatStrike.setOnClickListener(v -> com.example.animelib.util.CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, com.example.animelib.util.CommentFormattingHelper.FormatType.STRIKE));
                }
                if (holder.btnInlineFormatSpoiler != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatSpoiler.setOnClickListener(v -> com.example.animelib.util.CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, com.example.animelib.util.CommentFormattingHelper.FormatType.SPOILER));
                }
                if (holder.btnInlineFormatQuote != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineFormatQuote.setOnClickListener(v -> com.example.animelib.util.CommentFormattingHelper.applyFormat(holder.inlineCommentInputField, com.example.animelib.util.CommentFormattingHelper.FormatType.QUOTE));
                }
                if (holder.btnInlineSendComment != null && holder.inlineCommentInputField != null) {
                    holder.btnInlineSendComment.setOnClickListener(v -> {
                        if (commentActionListener != null) {
                            CharSequence text = holder.inlineCommentInputField.getText();
                            commentActionListener.onSendInlineReply(item, text, holder.inlineCommentInputField);
                        }
                    });
                    holder.inlineCommentInputField.setOnEditorActionListener((v, actionId, event) -> {
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                            if (commentActionListener != null) {
                                CharSequence text = holder.inlineCommentInputField.getText();
                                commentActionListener.onSendInlineReply(item, text, holder.inlineCommentInputField);
                            }
                            return true;
                        }
                        return false;
                    });
                }
            } else {
                holder.inlineReplyContainer.setVisibility(View.GONE);
            }
        }

        if (holder.replyActionView != null) {
            holder.replyActionView.setOnClickListener(v -> {
                if (isReplyingToThis) {
                    setActiveReplyCommentId(null);
                    if (commentActionListener != null) {
                        commentActionListener.onReplyCancelled(item);
                    }
                } else {
                    setActiveReplyCommentId(item.getId());
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

        // Thread line & indent for nested replies
        if (holder.threadGuideLine != null) {
            holder.threadGuideLine.setVisibility(di.level > 0 ? View.VISIBLE : View.GONE);
        }

        float density = holder.itemView.getResources().getDisplayMetrics().density;
        int leftPad = (int) (12 * density + (di.level > 0 ? 12 * density : 0));
        int rightPad = (int) (12 * density);
        int topPad = (int) (8 * density);
        int bottomPad = (int) (8 * density);
        holder.itemView.setPadding(leftPad, topPad, rightPad, bottomPad);

        String avatarUrl = null;
        if (item.getUser() != null && item.getUser().getAvatar() != null) {
            avatarUrl = item.getUser().getAvatar().getUrl();
        }
        ImageLoader.getInstance().loadInto(holder.avatarView, avatarUrl, R.drawable.ic_avatar_placeholder);

        // Sticky comment badge & highlight
        boolean isSticky = item.isSticky();
        if (holder.stickyBadgeContainer != null) {
            holder.stickyBadgeContainer.setVisibility(isSticky ? View.VISIBLE : View.GONE);
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
        if (isSticky) {
            holder.itemView.setBackgroundResource(R.drawable.bg_sticky_comment);
            if (lp != null) {
                int hMargin = (int) (4 * density);
                int vMargin = (int) (4 * density);
                lp.setMargins(hMargin, vMargin, hMargin, vMargin);
                holder.itemView.setLayoutParams(lp);
            }
        } else {
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
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
        return flat.size();
    }

    public void removeComment(long commentId) {
        allById.remove(commentId);

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
        simpleSpannedCache.remove(commentId);

        rebuildFlat(-1);
    }

    private static CharSequence trimTrailingNewlines(CharSequence text) {
        if (text == null) return "";
        int len = text.length();
        while (len > 0 && (text.charAt(len - 1) == '\n' || text.charAt(len - 1) == '\r' || Character.isWhitespace(text.charAt(len - 1)))) {
            len--;
        }
        return text.subSequence(0, len);
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

            inlineReplyContainer = itemView.findViewById(R.id.inlineReplyContainer);
            tvInlineReplyToText = itemView.findViewById(R.id.tvInlineReplyToText);
            btnInlineCancelReply = itemView.findViewById(R.id.btnInlineCancelReply);
            btnInlineFormatBold = itemView.findViewById(R.id.btnInlineFormatBold);
            btnInlineFormatItalic = itemView.findViewById(R.id.btnInlineFormatItalic);
            btnInlineFormatUnderline = itemView.findViewById(R.id.btnInlineFormatUnderline);
            btnInlineFormatStrike = itemView.findViewById(R.id.btnInlineFormatStrike);
            btnInlineFormatSpoiler = itemView.findViewById(R.id.btnInlineFormatSpoiler);
            btnInlineFormatQuote = itemView.findViewById(R.id.btnInlineFormatQuote);
            inlineCommentInputField = itemView.findViewById(R.id.inlineCommentInputField);
            btnInlineSendComment = itemView.findViewById(R.id.btnInlineSendComment);
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
}