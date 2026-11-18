package com.example.animelib.managers;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.VideoPlayerActivity;
import com.example.animelib.adapters.CommentsAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.util.CommentFormattingHelper;
import com.example.animelib.util.CommentHtmlProcessor;
import com.example.animelib.util.CommentKeyboardScrollHelper;
import com.example.animelib.util.CustomToast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Менеджер для работы с комментариями к эпизодам.
 * Поддерживает фоновый парсинг HTML, плавную покадровую отрисовку и бесконечную подгрузку при скролле
 * без пролагов видеоплеера.
 */
public class CommentsManager {

    private static final String TAG = "CommentsManager";
    private static final int LANDSCAPE_PREFETCH_THRESHOLD = 6;
    private static final int PORTRAIT_VIEWPORT_REVEAL_THRESHOLD_DP = 1000;
    private static final int PORTRAIT_SCROLL_THRESHOLD_DP = 650;
    private static final long POST_PLAYBACK_PORTRAIT_LOAD_DELAY_MS = 650L;
    private static final long FALLBACK_PORTRAIT_LOAD_DELAY_MS = 3800L;
    private static final long PAGINATION_ERROR_COOLDOWN_MS = 4000L;

    // UI компоненты (Landscape / Side Panel)
    private View commentsPanel;
    private ImageButton closeCommentsButton;
    private RecyclerView commentsRecyclerView;
    private View commentsLoadingOverlay;
    private ImageButton commentsButton;
    private View commentsOptionsButton;
    private View menuOverlay;
    private TextView emptyCommentsText;

    // Элементы отображения ошибок с кнопкой повтора (Side Panel / Landscape)
    private View commentsErrorLayout;
    private TextView tvCommentsErrorText;
    private View btnRetryComments;

    // Элементы отображения ошибок с кнопкой повтора (Portrait)
    private View portraitCommentsErrorLayout;
    private TextView tvPortraitCommentsErrorText;
    private View btnPortraitRetryComments;

    // Offline mode support
    private boolean isOfflineMode = false;
    private View portraitOfflineCommentsNotice;
    private View portraitCommentsHeader;
    private View portraitCommentInputContainer;

    // Disabled comments in player support
    private View portraitCommentsDisabledNotice;
    private View btnPortraitEnableComments;
    private View landscapeCommentsDisabledNotice;
    private View btnLandscapeEnableComments;

    // Адаптеры и списки
    private final RecyclerView.RecycledViewPool sharedCommentViewPool = new RecyclerView.RecycledViewPool();
    private CommentsAdapter commentsAdapter;
    private RecyclerView portraitCommentsRecyclerView;
    private CommentsAdapter portraitCommentsAdapter;
    private View portraitCommentsLoadingOverlay;
    private TextView portraitEmptyCommentsText;
    private TextView portraitSortText;
    private NestedScrollView portraitScrollView;

    // Компоненты ввода комментария и ответа (Landscape / Panel)
    private View commentInputContainer;
    private EditText commentInputField;
    private ImageButton btnSendComment;
    private View sendCommentLoadingContainer;
    private View replyBar;
    private TextView tvReplyToText;
    private ImageButton btnCancelReply;

    // Компоненты ввода комментария и ответа (Portrait)
    private EditText portraitCommentInputField;
    private ImageButton btnPortraitSendComment;
    private View portraitSendCommentLoadingContainer;
    private View portraitReplyBar;
    private TextView tvPortraitReplyToText;
    private ImageButton btnPortraitCancelReply;

    // Состояние ответа и редактирования
    private Long replyParentCommentId = null;
    private Long replyRootId = null;
    private int replyCommentLevel = 0;
    private String replyUsername = null;
    private CommentsResponse.CommentItem editingComment = null;
    private boolean isSubmittingComment = false;
    private boolean isSubmittingMainComment = false;
    private View activeInlineSendButton = null;
    private View activeInlineLoadingContainer = null;

    // Состояние загрузки и бесконечного скролла
    private boolean isCommentsVisible = false;
    private final int commentsPanelWidth = 360; // dp
    private int commentsCurrentPage = 0;
    private boolean commentsHasNextPage = false;
    private boolean isLoadingComments = false;
    private boolean hasInitialCommentsLoadError = false;
    private long lastPaginationErrorTimeMs = 0L;
    private String commentsSortType = "desc";
    private int requestGeneration = 0;
    private boolean isPortraitCommentsRequested = false;
    private boolean isPlayerReadyForComments = false;
    private float cachedDensity = 0f;
    private boolean landscapeAdapterNeedsSync = false;
    private boolean portraitAdapterNeedsSync = false;
    private boolean isPanelAnimatingOpen = false;

    private final CommentsDataCoordinator dataCoordinator = new CommentsDataCoordinator();
    private final CommentsModerationHelper moderationHelper;

    // Фоновый поток с пониженным приоритетом, чтобы парсинг Jsoup/HTML не конкурировал с декодером ExoPlayer
    private final ExecutorService commentsProcessingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            r.run();
        }, "CommentsHtmlPreParser");
        t.setDaemon(true);
        return t;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable deferredPortraitLoadRunnable = () -> {
        if (!isOfflineMode && !isCommentsDisabledInPlayer() && isPortraitOrientation()) {
            triggerInitialLoadIfNeeded();
        }
    };
    private final Runnable panelOpenFallbackRunnable = () -> {
        if (isCommentsVisible && isPanelAnimatingOpen) {
            isPanelAnimatingOpen = false;
            syncOrLoadLandscapeComments();
        }
    };

    // Контекст и сервисы
    private final Context context;
    private final ApiService apiService;
    private EpisodesListResponse.EpisodeItem currentEpisode;

    // Callback интерфейсы
    public interface CommentsVisibilityCallback {
        void onCommentsVisibilityChanged(boolean isVisible);
    }

    public interface CommentsDataCallback {
        void onCommentsLoaded(List<CommentsResponse.CommentItem> comments);
        void onCommentsError(String error);
    }

    public interface CommentsSettingsCallback {
        void onCommentsInPlayerToggled(boolean disabled);
        void onRepliesLimitChanged(int limit);
    }

    private final CommentKeyboardScrollHelper keyboardScrollHelper;
    private CommentsVisibilityCallback visibilityCallback;
    private CommentsDataCallback dataCallback;
    private CommentsSettingsCallback settingsCallback;

    public CommentsManager(Context context, ApiService apiService) {
        this.context = context;
        this.apiService = apiService;
        this.keyboardScrollHelper = new CommentKeyboardScrollHelper(context);
        if (context instanceof Activity) {
            Activity act = (Activity) context;
            if (act.getWindow() != null) {
                this.keyboardScrollHelper.attachWindowRoot(act.getWindow().getDecorView());
            }
        }
        this.sharedCommentViewPool.setMaxRecycledViews(CommentsAdapter.DisplayItem.TYPE_COMMENT, 20);
        this.sharedCommentViewPool.setMaxRecycledViews(CommentsAdapter.DisplayItem.TYPE_SHOW_MORE, 8);
        this.moderationHelper = new CommentsModerationHelper(context, apiService, new CommentsModerationHelper.Host() {
            @Override
            public void onSortSelected(String sortType) {
                changeCommentsSort(sortType);
            }

            @Override
            public void onEditCommentRequested(CommentsResponse.CommentItem comment) {
                startEditMode(comment);
            }

            @Override
            public void onCommentDeleted(long commentId) {
                removeCommentFromMemoryAndAdapters(commentId);
            }

            @Override
            public void onUserIgnoredSuccess() {
                resetCommentsOnEpisodeChange(false);
                loadCommentsPage(1);
            }

            @Override
            public void onCommentsInPlayerToggledFromSettings(boolean disabled) {
                applyCommentsDisabledState(disabled);
                if (disabled) {
                    if (isCommentsVisible) {
                        hideCommentsPanel();
                    }
                    updateCommentsButtonVisibility(false);
                } else {
                    updateCommentsButtonVisibility(true);
                    if (dataCoordinator.isPagesEmpty() && !isLoadingComments) {
                        hasInitialCommentsLoadError = false;
                        lastPaginationErrorTimeMs = 0L;
                        isPortraitCommentsRequested = true;
                        loadCommentsPage(1);
                    } else {
                        updateEmptyCommentsState();
                    }
                }
                if (settingsCallback != null) {
                    settingsCallback.onCommentsInPlayerToggled(disabled);
                }
            }

            @Override
            public void onRepliesLimitChangedFromSettings(int limit) {
                updateRepliesLimit(limit);
                if (settingsCallback != null) {
                    settingsCallback.onRepliesLimitChanged(limit);
                }
            }

            @Override
            public void runOnUiThread(Runnable runnable) {
                safeRunOnUiThread(runnable);
            }
        });
    }

    public void attachWindowRoot(View decorView) {
        keyboardScrollHelper.attachWindowRoot(decorView);
    }

    public void onWindowInsetsChanged(int imeBottom, int navBarBottom) {
        keyboardScrollHelper.onWindowInsetsChanged(imeBottom, navBarBottom);
    }

    public boolean isImeVisible() {
        return keyboardScrollHelper.isImeVisible();
    }

    private boolean isPortraitOrientation() {
        return context != null
                && context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    public void initializeViews(View commentsPanel, ImageButton closeCommentsButton,
                                RecyclerView commentsRecyclerView, View commentsLoadingOverlay,
                                ImageButton commentsButton, View commentsOptionsButton,
                                View menuOverlay, TextView emptyCommentsText) {
        this.commentsPanel = commentsPanel;
        this.closeCommentsButton = closeCommentsButton;
        this.commentsRecyclerView = commentsRecyclerView;
        this.commentsLoadingOverlay = commentsLoadingOverlay;
        this.commentsButton = commentsButton;
        this.commentsOptionsButton = commentsOptionsButton;
        this.menuOverlay = menuOverlay;
        this.emptyCommentsText = emptyCommentsText;

        setupCommentsViews();
        initializePanelPosition();
        if (isCommentsDisabledInPlayer()) {
            applyCommentsDisabledState(true);
        }
    }

    private void setupCommentsViews() {
        if (closeCommentsButton != null) {
            closeCommentsButton.setOnClickListener(v -> hideCommentsPanel());
        }

        if (commentsButton != null) {
            commentsButton.setOnClickListener(v -> toggleCommentsPanel());
        }

        if (emptyCommentsText != null) {
            emptyCommentsText.setText("Комментариев пока нет");
            emptyCommentsText.setVisibility(View.GONE);
        }

        if (commentsPanel != null) {
            commentsErrorLayout = commentsPanel.findViewById(R.id.commentsErrorLayout);
            tvCommentsErrorText = commentsPanel.findViewById(R.id.tvCommentsErrorText);
            btnRetryComments = commentsPanel.findViewById(R.id.btnRetryComments);
            if (btnRetryComments != null) {
                btnRetryComments.setOnClickListener(v -> {
                    hasInitialCommentsLoadError = false;
                    lastPaginationErrorTimeMs = 0L;
                    if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
                    loadCommentsPage(1);
                });
            }

            commentInputContainer = commentsPanel.findViewById(R.id.commentInputContainer);
            commentInputField = commentsPanel.findViewById(R.id.commentInputField);
            btnSendComment = commentsPanel.findViewById(R.id.btnSendComment);
            sendCommentLoadingContainer = commentsPanel.findViewById(R.id.sendCommentLoadingContainer);
            replyBar = commentsPanel.findViewById(R.id.replyBar);
            tvReplyToText = commentsPanel.findViewById(R.id.tvReplyToText);
            btnCancelReply = commentsPanel.findViewById(R.id.btnCancelReply);
            CommentsAdapter.applySendButtonLoadingState(btnSendComment, sendCommentLoadingContainer, isSubmittingMainComment);

            bindCommentInputAndFormatting(
                    commentInputField,
                    btnSendComment,
                    btnCancelReply,
                    commentsPanel,
                    R.id.btnFormatBold,
                    R.id.btnFormatItalic,
                    R.id.btnFormatUnderline,
                    R.id.btnFormatStrike,
                    R.id.btnFormatSpoiler,
                    R.id.btnFormatQuote
            );

            View btnCommentRules = commentsPanel.findViewById(R.id.btnCommentRules);
            if (btnCommentRules != null) {
                btnCommentRules.setOnClickListener(v -> showRulesDialog());
            }
            View btnCommentSettings = commentsPanel.findViewById(R.id.btnCommentSettings);
            if (btnCommentSettings != null) {
                btnCommentSettings.setOnClickListener(v -> showSettingsDialog());
            }

            landscapeCommentsDisabledNotice = commentsPanel.findViewById(R.id.landscapeCommentsDisabledNotice);
            btnLandscapeEnableComments = commentsPanel.findViewById(R.id.btnLandscapeEnableComments);
            if (btnLandscapeEnableComments != null) {
                btnLandscapeEnableComments.setOnClickListener(v -> enableCommentsInPlayer());
            }
        }

        setupCommentsOptionsButton();
        setupCommentsRecyclerView();
        keyboardScrollHelper.bindLandscapeViews(
                commentsPanel,
                commentsRecyclerView,
                commentInputContainer,
                commentInputField,
                new CommentKeyboardScrollHelper.LandscapeInputStateProvider() {
                    @Override
                    public boolean shouldShowMainInputWhenIdle() {
                        return !isOfflineMode && !isCommentsDisabledInPlayer();
                    }

                    @Override
                    public boolean hasActiveInlineReply() {
                        return commentsAdapter != null && commentsAdapter.getActiveReplyCommentId() != null;
                    }
                }
        );
    }

    private void bindCommentInputAndFormatting(EditText inputField,
                                               ImageButton sendButton,
                                               ImageButton cancelReplyButton,
                                               View formattingRoot,
                                               int boldId,
                                               int italicId,
                                               int underlineId,
                                               int strikeId,
                                               int spoilerId,
                                               int quoteId) {
        if (inputField != null) {
            CommentFormattingHelper.attachFormattingTextWatcher(inputField);
            inputField.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCommentFromInput(inputField);
                    return true;
                }
                return false;
            });
        }

        if (formattingRoot != null && inputField != null) {
            bindFormatButton(formattingRoot, boldId, inputField, CommentFormattingHelper.FormatType.BOLD);
            bindFormatButton(formattingRoot, italicId, inputField, CommentFormattingHelper.FormatType.ITALIC);
            bindFormatButton(formattingRoot, underlineId, inputField, CommentFormattingHelper.FormatType.UNDERLINE);
            bindFormatButton(formattingRoot, strikeId, inputField, CommentFormattingHelper.FormatType.STRIKE);
            bindFormatButton(formattingRoot, spoilerId, inputField, CommentFormattingHelper.FormatType.SPOILER);
            bindFormatButton(formattingRoot, quoteId, inputField, CommentFormattingHelper.FormatType.QUOTE);
        }

        if (sendButton != null) {
            sendButton.setOnClickListener(v -> sendCommentFromInput(inputField));
        }
        if (cancelReplyButton != null) {
            cancelReplyButton.setOnClickListener(v -> {
                if (!isSubmittingComment) {
                    cancelReplyMode();
                }
            });
        }
    }

    private static void bindFormatButton(View root,
                                         int buttonId,
                                         EditText targetInput,
                                         CommentFormattingHelper.FormatType formatType) {
        View btn = root.findViewById(buttonId);
        if (btn != null) {
            btn.setOnClickListener(v -> CommentFormattingHelper.applyFormat(targetInput, formatType));
        }
    }

    private void initializePanelPosition() {
        if (commentsPanel == null || context == null) return;
        int panelWidth = (int) (commentsPanelWidth * context.getResources().getDisplayMetrics().density);
        commentsPanel.setTranslationX(panelWidth);
    }

    public void updateSortButtonText() {
        String label = "asc".equals(commentsSortType) ? "Старые" : ("votes_up".equals(commentsSortType) ? "Популярные" : "Новые");
        if (commentsOptionsButton != null) {
            TextView tvSort = commentsOptionsButton.findViewById(R.id.tvSortComments);
            if (tvSort != null) {
                tvSort.setText(label);
            }
        }
        if (portraitSortText != null) {
            portraitSortText.setText(label);
        }
    }

    private void setupCommentsOptionsButton() {
        if (commentsOptionsButton == null) return;
        updateSortButtonText();
        commentsOptionsButton.setOnClickListener(v -> showSortOptionsDialog());
    }

    public void showSortOptionsDialog() {
        showSortOptionsDialog(commentsOptionsButton);
    }

    public void showSortOptionsDialog(View anchorView) {
        View target = anchorView != null ? anchorView : commentsOptionsButton;
        moderationHelper.showSortOptionsDialog(target, commentsSortType);
    }

    public void showCommentActionsPopup(View anchorView, CommentsResponse.CommentItem comment) {
        moderationHelper.showCommentActionsPopup(anchorView, comment);
    }

    public void showIgnoreUserDialog(CommentsResponse.CommentItem comment) {
        moderationHelper.showIgnoreUserDialog(comment);
    }

    public void confirmAndDeleteComment(CommentsResponse.CommentItem comment) {
        moderationHelper.confirmAndDeleteComment(comment);
    }

    private void removeCommentFromMemoryAndAdapters(long commentId) {
        if (apiService != null) {
            if (currentEpisode != null) {
                apiService.invalidateEpisodeCommentsCache(currentEpisode.getId());
            } else {
                apiService.invalidateAllCommentsCache();
            }
        }
        dataCoordinator.removeCommentById(commentId);
        if (commentsAdapter != null) {
            commentsAdapter.removeComment(commentId);
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.removeComment(commentId);
        }
        updateEmptyCommentsState();
    }

    public boolean isOwnComment(CommentsResponse.CommentItem comment) {
        return moderationHelper.isOwnComment(comment);
    }

    private int dpToPx(int dp) {
        if (context == null) return dp;
        if (cachedDensity <= 0f) {
            cachedDensity = context.getResources().getDisplayMetrics().density;
        }
        return (int) (dp * cachedDensity);
    }

    private CommentsAdapter.CommentActionListener createCommentActionListener(boolean isPortrait) {
        return new CommentsAdapter.CommentActionListener() {
            @Override
            public void onReplyClicked(CommentsResponse.CommentItem comment) {
                Long targetId = comment != null ? comment.getId() : null;
                CommentsAdapter peerAdapter = isPortrait ? commentsAdapter : portraitCommentsAdapter;
                CommentsAdapter selfAdapter = isPortrait ? portraitCommentsAdapter : commentsAdapter;
                if (peerAdapter != null && peerAdapter != selfAdapter) {
                    peerAdapter.setActiveReplyCommentId(targetId);
                }
                cancelMainInputReplyMode();
                keyboardScrollHelper.updateLandscapeMainInputVisibility();
            }

            @Override
            public void onReplyCancelled(CommentsResponse.CommentItem comment) {
                CommentsAdapter peerAdapter = isPortrait ? commentsAdapter : portraitCommentsAdapter;
                CommentsAdapter selfAdapter = isPortrait ? portraitCommentsAdapter : commentsAdapter;
                if (peerAdapter != null && peerAdapter != selfAdapter) {
                    peerAdapter.setActiveReplyCommentId(null);
                }
                cancelMainInputReplyMode();
                keyboardScrollHelper.updateLandscapeMainInputVisibility();
            }

            @Override
            public void onSendInlineReply(CommentsResponse.CommentItem comment, CharSequence rawText, EditText inputField) {
                sendInlineReply(comment, rawText, inputField);
            }

            @Override
            public void onVoteClicked(CommentsResponse.CommentItem comment, int targetVote) {
                voteOnComment(comment, targetVote);
            }

            @Override
            public void onMoreActionsClicked(View anchorView, CommentsResponse.CommentItem comment) {
                showCommentActionsPopup(anchorView, comment);
            }

            @Override
            public void onInlineReplyInputBound(EditText inputField, View inlineReplyContainer) {
                keyboardScrollHelper.attachInlineReplyInput(inputField, inlineReplyContainer, isPortrait);
            }
        };
    }

    private void setupCommentsRecyclerView() {
        if (commentsRecyclerView == null) return;

        commentsAdapter = new CommentsAdapter();
        commentsAdapter.setCommentActionListener(createCommentActionListener(false));

        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setInitialPrefetchItemCount(6);
        commentsRecyclerView.setLayoutManager(layoutManager);
        commentsRecyclerView.setRecycledViewPool(sharedCommentViewPool);
        commentsRecyclerView.setHasFixedSize(true);
        commentsRecyclerView.setItemViewCacheSize(12);
        commentsRecyclerView.setItemAnimator(null);

        commentsAdapter.setRepliesLimit(loadSavedRepliesLimit());
        commentsAdapter.setOnBatchCompletedListener(() -> {
            if (isCommentsVisible && !isPortraitOrientation()) {
                checkLandscapeInfiniteScroll(true);
            }
        });
        commentsRecyclerView.setAdapter(commentsAdapter);

        commentsRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    checkLandscapeInfiniteScroll(false);
                }
            }
        });
    }

    private void checkLandscapeInfiniteScroll(boolean allowFillViewportCheck) {
        if (isOfflineMode || isCommentsDisabledInPlayer() || isLoadingComments || !commentsHasNextPage || currentEpisode == null) {
            return;
        }
        if (commentsRecyclerView == null || commentsAdapter == null || commentsAdapter.isPendingInsertion()) {
            return;
        }
        if (commentsAdapter.getCommentDisplayCount() == 0) {
            return;
        }
        RecyclerView.LayoutManager rawLm = commentsRecyclerView.getLayoutManager();
        if (!(rawLm instanceof LinearLayoutManager)) {
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) rawLm;
        int totalCount = lm.getItemCount();
        int lastVisible = lm.findLastVisibleItemPosition();
        if (lastVisible == RecyclerView.NO_POSITION) {
            return;
        }
        if (lastVisible >= totalCount - LANDSCAPE_PREFETCH_THRESHOLD
                || (allowFillViewportCheck && !commentsRecyclerView.canScrollVertically(1))) {
            loadNextCommentsPageIfAvailable();
        }
    }

    /**
     * Вызывается при прокрутке портретного NestedScrollView для ленивой и постепенной догрузки комментариев.
     */
    public void onPortraitScrollChanged(NestedScrollView scrollView, int scrollY, int oldScrollY) {
        if (isOfflineMode || isCommentsDisabledInPlayer() || !isPortraitOrientation()) {
            return;
        }

        if (scrollY >= dpToPx(60)) {
            if (!hasInitialCommentsLoadError
                    && (!isPortraitCommentsRequested || (dataCoordinator.isPagesEmpty() && !isLoadingComments && currentEpisode != null))) {
                mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
                isPortraitCommentsRequested = true;
                triggerInitialLoadIfNeeded();
                return;
            }
            if (isLoadingComments && commentsCurrentPage == 0
                    && (portraitCommentsAdapter == null || portraitCommentsAdapter.getCommentDisplayCount() == 0)) {
                showLoadingOverlay(portraitCommentsLoadingOverlay);
            }
        }

        if (scrollY <= oldScrollY) {
            return;
        }

        if (portraitCommentsAdapter == null || portraitCommentsAdapter.isPendingInsertion()) {
            return;
        }

        if (scrollView != null && scrollView.getChildCount() > 0) {
            View content = scrollView.getChildAt(0);
            int remainingPx = content.getMeasuredHeight() - (scrollY + scrollView.getMeasuredHeight());

            // Сначала раскрываем уже распарсенные элементы из локального буфера одним быстрым батчем
            if (portraitCommentsAdapter.hasDeferredViewportItems()) {
                if (remainingPx <= dpToPx(PORTRAIT_VIEWPORT_REVEAL_THRESHOLD_DP)) {
                    portraitCommentsAdapter.revealNextViewportBatch(CommentsAdapter.PORTRAIT_SCROLL_BATCH_SIZE);
                    scrollView.post(() -> {
                        if (!isPortraitOrientation() || isOfflineMode || portraitCommentsAdapter == null) return;
                        if (portraitCommentsAdapter.hasDeferredViewportItems() && scrollView.getChildCount() > 0) {
                            View c = scrollView.getChildAt(0);
                            int rem = c.getMeasuredHeight() - (scrollView.getScrollY() + scrollView.getMeasuredHeight());
                            if (rem <= dpToPx(PORTRAIT_VIEWPORT_REVEAL_THRESHOLD_DP) / 2) {
                                portraitCommentsAdapter.revealNextViewportBatch(CommentsAdapter.PORTRAIT_SCROLL_BATCH_SIZE);
                            }
                        }
                    });
                }
                return;
            }

            if (isLoadingComments || !commentsHasNextPage || currentEpisode == null) {
                return;
            }
            if (portraitCommentsAdapter.getCommentDisplayCount() == 0) {
                return;
            }
            if (remainingPx <= dpToPx(PORTRAIT_SCROLL_THRESHOLD_DP)) {
                loadNextCommentsPageIfAvailable();
            }
        }
    }

    public void toggleCommentsPanel() {
        if (isOfflineMode) {
            CustomToast.showInfo(context, "Комментарии недоступны в офлайн режиме");
            return;
        }
        if (isCommentsVisible) {
            hideCommentsPanel();
        } else {
            showCommentsPanel();
        }
    }

    public void showCommentsPanel() {
        if (isOfflineMode) {
            CustomToast.showInfo(context, "Комментарии недоступны в офлайн режиме");
            return;
        }
        if (isCommentsVisible) {
            return;
        }

        isCommentsVisible = true;
        isPanelAnimatingOpen = true;

        // Запускаем анимацию открытия панели ДО вставки View в RecyclerView,
        // чтобы трансформация playerContainer прошла плавно без просадки FPS.
        if (context instanceof VideoPlayerActivity) {
            ((VideoPlayerActivity) context).openCommentsPanel();
            mainHandler.removeCallbacks(panelOpenFallbackRunnable);
            mainHandler.postDelayed(panelOpenFallbackRunnable, 240L);
        } else {
            isPanelAnimatingOpen = false;
            syncOrLoadLandscapeComments();
        }

        if (visibilityCallback != null) {
            visibilityCallback.onCommentsVisibilityChanged(true);
        }
    }

    public void onPanelDragStart() {
        isCommentsVisible = true;
        isPanelAnimatingOpen = true;
    }

    public void onPanelOpened() {
        mainHandler.removeCallbacks(panelOpenFallbackRunnable);
        isCommentsVisible = true;
        isPanelAnimatingOpen = false;
        syncOrLoadLandscapeComments();
        if (visibilityCallback != null) {
            visibilityCallback.onCommentsVisibilityChanged(true);
        }
    }

    private void syncOrLoadLandscapeComments() {
        if (isOfflineMode) {
            applyOfflineViewsState();
            return;
        }
        if (isCommentsDisabledInPlayer()) {
            applyCommentsDisabledState(true);
            return;
        }
        if (commentsAdapter == null) return;

        if (landscapeAdapterNeedsSync && !dataCoordinator.isPagesEmpty()) {
            landscapeAdapterNeedsSync = false;
            dataCoordinator.syncCachedPagesIntoAdapter(commentsAdapter, !"asc".equals(commentsSortType));
            hideLoadingOverlay(commentsLoadingOverlay);
            updateEmptyCommentsState();
            return;
        }

        if (!isLoadingComments && !hasInitialCommentsLoadError && !commentsAdapter.hasAnySourceComments() && dataCoordinator.isPagesEmpty()) {
            loadCommentsPage(1);
        }
    }

    public void forceHideCommentsPanel() {
        mainHandler.removeCallbacks(panelOpenFallbackRunnable);
        isPanelAnimatingOpen = false;
        isCommentsVisible = false;
        if (visibilityCallback != null) {
            visibilityCallback.onCommentsVisibilityChanged(false);
        }
    }

    public void hideCommentsPanel() {
        if (!isCommentsVisible) return;
        mainHandler.removeCallbacks(panelOpenFallbackRunnable);
        isPanelAnimatingOpen = false;
        isCommentsVisible = false;

        if (context instanceof VideoPlayerActivity) {
            ((VideoPlayerActivity) context).closeCommentsPanel();
        }

        if (visibilityCallback != null) {
            visibilityCallback.onCommentsVisibilityChanged(false);
        }
    }

    /**
     * Загрузить страницу комментариев (page == 1 — начальная загрузка; page > 1 — постепенная подгрузка при скролле).
     */
    public void loadCommentsPage(int page) {
        if (isOfflineMode || isCommentsDisabledInPlayer() || currentEpisode == null || apiService == null) {
            return;
        }

        final int targetPage = Math.max(1, page);
        final boolean isInitialPage = (targetPage == 1);
        final boolean isPortrait = isPortraitOrientation();
        if (isPortrait) {
            isPortraitCommentsRequested = true;
        }

        isLoadingComments = true;
        if (isInitialPage) {
            hasInitialCommentsLoadError = false;
            lastPaginationErrorTimeMs = 0L;
        }
        final int gen = isInitialPage ? (++requestGeneration) : requestGeneration;

        if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
        if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);

        if (isInitialPage) {
            if (commentsAdapter != null) commentsAdapter.setLoadingMore(false);
            if (portraitCommentsAdapter != null) portraitCommentsAdapter.setLoadingMore(false);

            boolean landscapeEmpty = (commentsAdapter == null || commentsAdapter.getCommentDisplayCount() == 0);
            boolean portraitEmpty = (portraitCommentsAdapter == null || portraitCommentsAdapter.getCommentDisplayCount() == 0);
            if (!isPortrait && isCommentsVisible && landscapeEmpty) {
                showLoadingOverlay(commentsLoadingOverlay);
            }
            boolean userViewingPortraitComments = isPortrait && (portraitScrollView != null && portraitScrollView.getScrollY() >= dpToPx(40));
            if (userViewingPortraitComments && portraitEmpty) {
                showLoadingOverlay(portraitCommentsLoadingOverlay);
            }
        } else {
            // При бесконечном скролле показываем компактный спиннер внизу списка вместо перекрытия экрана
            if (isPortrait) {
                if (portraitCommentsAdapter != null) portraitCommentsAdapter.setLoadingMore(true);
            } else {
                if (commentsAdapter != null) commentsAdapter.setLoadingMore(true);
            }
        }

        final int primaryTextColor = CommentHtmlProcessor.resolvePrimaryTextColor(context);
        final int purpleColor = context != null ? ContextCompat.getColor(context, R.color.purple_primary) : 0xFF8B5CF6;
        final long episodeId = currentEpisode.getId();

        if (isInitialPage) {
            dataCoordinator.clearAll(false);

            apiService.fetchStickyComments(episodeId, new ApiService.StickyCommentsCallback() {
                @Override
                public void onStickyCommentsReceived(List<CommentsResponse.CommentItem> stickyComments) {
                    if (stickyComments == null || stickyComments.isEmpty()) {
                        return;
                    }
                    commentsProcessingExecutor.execute(() -> {
                        Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedSticky =
                                dataCoordinator.preParseCommentsBatch(stickyComments, primaryTextColor, purpleColor);
                        safeRunOnUiThread(() -> {
                            if (gen != requestGeneration || isOfflineMode) return;
                            dataCoordinator.setStickyComments(stickyComments);
                            putParsedContentOnBothAdapters(parsedSticky);

                            // Если первая страница уже успела отрисоваться — добавим закреплённые сверху;
                            // иначе они войдут единым пакетом вместе с первой страницей без двойной перерисовки!
                            if (!dataCoordinator.isPagesEmpty()) {
                                List<CommentsResponse.CommentItem> stickyList = dataCoordinator.getLoadedStickyComments();
                                if (isPortraitOrientation()) {
                                    if (portraitCommentsAdapter != null) {
                                        portraitCommentsAdapter.setStickyComments(stickyList);
                                    }
                                    landscapeAdapterNeedsSync = true;
                                } else {
                                    if (isCommentsVisible && !isPanelAnimatingOpen && commentsAdapter != null) {
                                        commentsAdapter.setStickyComments(stickyList);
                                    } else {
                                        landscapeAdapterNeedsSync = true;
                                    }
                                    portraitAdapterNeedsSync = true;
                                }
                                updateEmptyCommentsState();
                            }
                        });
                    });
                }

                @Override
                public void onError(String error) {
                    Log.d(TAG, "Sticky comments error or none: " + error);
                }
            });
        }

        apiService.fetchEpisodeComments(episodeId, commentsSortType, targetPage,
                new ApiService.EpisodeCommentsCallback() {
                    @Override
                    public void onCommentsReceived(CommentsResponse response) {
                        commentsProcessingExecutor.execute(() -> {
                            List<CommentsResponse.CommentItem> allItems = CommentsDataCoordinator.extractAllItems(response);
                            Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedMap =
                                    dataCoordinator.preParseCommentsBatch(allItems, primaryTextColor, purpleColor);

                            safeRunOnUiThread(() -> {
                                if (gen != requestGeneration) {
                                    return;
                                }
                                isLoadingComments = false;

                                putParsedContentOnBothAdapters(parsedMap);
                                setLoadingMoreOnBothAdapters(false);

                                if (isOfflineMode) {
                                    applyOfflineViewsState();
                                    return;
                                }

                                hideLoadingOverlaysAndErrors();

                                if (response != null && response.getMeta() != null) {
                                    commentsHasNextPage = response.getMeta().isHas_next_page();
                                    commentsCurrentPage = response.getMeta().getPage() > 0 ? response.getMeta().getPage() : targetPage;
                                } else {
                                    commentsCurrentPage = targetPage;
                                    commentsHasNextPage = false;
                                }

                                // Если сервер вернул пустую страницу при догрузке — останавливаем бесконечный скролл
                                if (!isInitialPage && allItems.isEmpty()) {
                                    commentsHasNextPage = false;
                                }

                                if (response != null) {
                                    hasInitialCommentsLoadError = false;
                                    lastPaginationErrorTimeMs = 0L;
                                    dataCoordinator.recordLoadedPage(response, isInitialPage);

                                    List<CommentsResponse.CommentItem> stickyList = dataCoordinator.getLoadedStickyComments();
                                    if (isPortraitOrientation()) {
                                        if (portraitCommentsAdapter != null) {
                                            if (isInitialPage) {
                                                portraitCommentsAdapter.setInitialComments(stickyList, response);
                                            } else {
                                                portraitCommentsAdapter.appendCommentsPage(response);
                                            }
                                        }
                                        landscapeAdapterNeedsSync = true;
                                    } else {
                                        if (isCommentsVisible && !isPanelAnimatingOpen && commentsAdapter != null) {
                                            if (isInitialPage) {
                                                commentsAdapter.setInitialComments(stickyList, response);
                                            } else {
                                                commentsAdapter.appendCommentsPage(response);
                                            }
                                        } else {
                                            landscapeAdapterNeedsSync = true;
                                        }
                                        portraitAdapterNeedsSync = true;
                                    }

                                    updateEmptyCommentsState();

                                    if (dataCallback != null) {
                                        dataCallback.onCommentsLoaded(allItems);
                                    }
                                } else {
                                    if (isInitialPage) {
                                        dataCoordinator.addSyntheticEmptyPageIfNeeded();
                                    }
                                    updateEmptyCommentsState();
                                }
                            });
                        });
                    }

                    @Override
                    public void onError(String error) {
                        safeRunOnUiThread(() -> {
                            if (gen != requestGeneration) {
                                return;
                            }
                            isLoadingComments = false;
                            hideLoadingOverlay(commentsLoadingOverlay);
                            hideLoadingOverlay(portraitCommentsLoadingOverlay);
                            setLoadingMoreOnBothAdapters(false);

                            boolean hasLoadedData = !dataCoordinator.isPagesEmpty()
                                    || (portraitCommentsAdapter != null && portraitCommentsAdapter.hasAnySourceComments())
                                    || (commentsAdapter != null && commentsAdapter.hasAnySourceComments());

                            if (!hasLoadedData) {
                                hasInitialCommentsLoadError = true;
                                mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
                                if (emptyCommentsText != null) emptyCommentsText.setVisibility(View.GONE);
                                if (portraitEmptyCommentsText != null) portraitEmptyCommentsText.setVisibility(View.GONE);

                                if (commentsErrorLayout != null) {
                                    if (tvCommentsErrorText != null) {
                                        tvCommentsErrorText.setText("Ошибка загрузки комментариев");
                                    }
                                    commentsErrorLayout.setVisibility(View.VISIBLE);
                                }
                                if (portraitCommentsErrorLayout != null) {
                                    if (tvPortraitCommentsErrorText != null) {
                                        tvPortraitCommentsErrorText.setText("Ошибка загрузки комментариев");
                                    }
                                    portraitCommentsErrorLayout.setVisibility(View.VISIBLE);
                                }
                                CustomToast.showWarning(context, "Ошибка загрузки комментариев");
                            } else if (!isInitialPage) {
                                lastPaginationErrorTimeMs = android.os.SystemClock.elapsedRealtime();
                            }

                            if (dataCallback != null) {
                                dataCallback.onCommentsError(error);
                            }
                        });
                    }
                });
    }

    private void putParsedContentOnBothAdapters(Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedMap) {
        if (commentsAdapter != null) {
            commentsAdapter.putParsedContentMap(parsedMap);
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.putParsedContentMap(parsedMap);
        }
    }

    private void setLoadingMoreOnBothAdapters(boolean loading) {
        if (commentsAdapter != null) {
            commentsAdapter.setLoadingMore(loading);
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.setLoadingMore(loading);
        }
    }

    private void hideLoadingOverlaysAndErrors() {
        hideLoadingOverlay(commentsLoadingOverlay);
        hideLoadingOverlay(portraitCommentsLoadingOverlay);
        if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
        if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);
    }

    private void updateEmptyCommentsState() {
        boolean hasComments = dataCoordinator.hasAnyComments();
        if (!hasComments) {
            hasComments = (portraitCommentsAdapter != null && portraitCommentsAdapter.hasAnySourceComments())
                    || (commentsAdapter != null && commentsAdapter.hasAnySourceComments());
        }

        if (!isOfflineMode && !isCommentsDisabledInPlayer() && !isLoadingComments) {
            if (hasInitialCommentsLoadError && !hasComments) {
                if (emptyCommentsText != null) emptyCommentsText.setVisibility(View.GONE);
                if (portraitEmptyCommentsText != null) portraitEmptyCommentsText.setVisibility(View.GONE);
                return;
            }
            if (emptyCommentsText != null) {
                emptyCommentsText.setVisibility(hasComments ? View.GONE : View.VISIBLE);
            }
            if (portraitEmptyCommentsText != null && isPortraitCommentsRequested) {
                portraitEmptyCommentsText.setVisibility(hasComments ? View.GONE : View.VISIBLE);
            }
        }
    }

    public void notifyPlayerReady() {
        if (isPlayerReadyForComments) return;
        isPlayerReadyForComments = true;
        if (!isOfflineMode && !isCommentsDisabledInPlayer() && isPortraitOrientation()
                && isPortraitCommentsRequested && currentEpisode != null
                && !hasInitialCommentsLoadError
                && dataCoordinator.isPagesEmpty() && !isLoadingComments) {
            mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
            mainHandler.postDelayed(deferredPortraitLoadRunnable, POST_PLAYBACK_PORTRAIT_LOAD_DELAY_MS);
        }
    }

    private void scheduleDeferredPortraitLoad() {
        mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
        if (isOfflineMode || isCommentsDisabledInPlayer() || currentEpisode == null) {
            return;
        }
        boolean userAlreadyScrolled = portraitScrollView != null && portraitScrollView.getScrollY() >= dpToPx(60);
        if (userAlreadyScrolled) {
            triggerInitialLoadIfNeeded();
        } else {
            long delay = isPlayerReadyForComments ? POST_PLAYBACK_PORTRAIT_LOAD_DELAY_MS : FALLBACK_PORTRAIT_LOAD_DELAY_MS;
            mainHandler.postDelayed(deferredPortraitLoadRunnable, delay);
        }
    }

    public void resetCommentsOnEpisodeChange(boolean reloadIfVisible) {
        mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
        boolean wasPortraitRequested = isPortraitCommentsRequested;
        boolean wasLandscapeVisible = isCommentsVisible;

        requestGeneration++;
        commentsCurrentPage = 0;
        commentsHasNextPage = false;
        isLoadingComments = false;
        hasInitialCommentsLoadError = false;
        lastPaginationErrorTimeMs = 0L;
        resetAllSubmittingUiStates();
        isPortraitCommentsRequested = false;
        if (reloadIfVisible) {
            isPlayerReadyForComments = false;
        }
        landscapeAdapterNeedsSync = false;
        portraitAdapterNeedsSync = false;
        dataCoordinator.clearAll(true);

        if (commentsAdapter != null) {
            commentsAdapter.clearAll();
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.clearAll();
        }

        hideLoadingOverlaysAndErrors();
        if (emptyCommentsText != null) emptyCommentsText.setVisibility(View.GONE);
        if (portraitEmptyCommentsText != null) portraitEmptyCommentsText.setVisibility(View.GONE);

        if (reloadIfVisible && currentEpisode != null) {
            if (isPortraitOrientation() && wasPortraitRequested) {
                isPortraitCommentsRequested = true;
                scheduleDeferredPortraitLoad();
            } else if (!isPortraitOrientation() && wasLandscapeVisible && !isPanelAnimatingOpen) {
                loadCommentsPage(1);
            }
        }
    }

    public boolean shouldLoadComments() {
        if (isCommentsDisabledInPlayer() || isOfflineMode) {
            return false;
        }
        if (isPortraitOrientation()) {
            return isPortraitCommentsRequested;
        } else {
            return isCommentsVisible;
        }
    }

    public void changeCommentsSort(String sort) {
        if (sort == null) return;

        boolean changed = !sort.equals(commentsSortType);
        commentsSortType = sort;
        updateSortButtonText();

        if (changed) {
            mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
            boolean isPortrait = isPortraitOrientation();
            boolean wasLandscapeVisible = isCommentsVisible;

            requestGeneration++;
            commentsCurrentPage = 0;
            commentsHasNextPage = false;
            isLoadingComments = false;
            hasInitialCommentsLoadError = false;
            lastPaginationErrorTimeMs = 0L;
            landscapeAdapterNeedsSync = false;
            portraitAdapterNeedsSync = false;
            dataCoordinator.clearAll(false);

            if (commentsAdapter != null) {
                commentsAdapter.clearAll();
            }
            if (portraitCommentsAdapter != null) {
                portraitCommentsAdapter.clearAll();
            }
            if (emptyCommentsText != null) emptyCommentsText.setVisibility(View.GONE);
            if (portraitEmptyCommentsText != null) portraitEmptyCommentsText.setVisibility(View.GONE);
            if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
            if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);

            if (isPortrait) {
                isPortraitCommentsRequested = true;
                showLoadingOverlay(portraitCommentsLoadingOverlay);
                loadCommentsPage(1);
            } else if (wasLandscapeVisible) {
                loadCommentsPage(1);
            }
        }
    }

    public void setCurrentEpisode(EpisodesListResponse.EpisodeItem episode) {
        boolean episodeChanged = (this.currentEpisode == null && episode != null)
                || (this.currentEpisode != null && episode != null && this.currentEpisode.getId() != episode.getId());
        this.currentEpisode = episode;
        if (episodeChanged) {
            isPlayerReadyForComments = false;
            hasInitialCommentsLoadError = false;
            lastPaginationErrorTimeMs = 0L;
        }

        if (episode == null) return;

        if (shouldLoadComments() && !hasInitialCommentsLoadError && dataCoordinator.isPagesEmpty() && !isLoadingComments) {
            if (isPortraitOrientation()) {
                scheduleDeferredPortraitLoad();
            } else if (!isPanelAnimatingOpen) {
                loadCommentsPage(1);
            }
        }
    }

    public EpisodesListResponse.EpisodeItem getCurrentEpisode() {
        return currentEpisode;
    }

    public boolean isCommentsVisible() {
        return isCommentsVisible;
    }

    public void onPanelClosedByDrag() {
        mainHandler.removeCallbacks(panelOpenFallbackRunnable);
        isPanelAnimatingOpen = false;
        isCommentsVisible = false;
    }

    public String getCommentsSortType() {
        return commentsSortType;
    }

    public void setPortraitViews(RecyclerView rv, View loadingOverlay, TextView emptyText,
                                 View sortBtn, TextView tvSortText, View rulesBtn) {
        this.portraitCommentsRecyclerView = rv;
        this.portraitCommentsLoadingOverlay = loadingOverlay;
        this.portraitEmptyCommentsText = emptyText;
        this.portraitSortText = tvSortText;

        if (this.portraitCommentsRecyclerView != null) {
            View root = this.portraitCommentsRecyclerView.getRootView();
            if (root != null) {
                portraitCommentsErrorLayout = root.findViewById(R.id.portraitCommentsErrorLayout);
                tvPortraitCommentsErrorText = root.findViewById(R.id.tvPortraitCommentsErrorText);
                btnPortraitRetryComments = root.findViewById(R.id.btnPortraitRetryComments);
                if (btnPortraitRetryComments != null) {
                    btnPortraitRetryComments.setOnClickListener(v -> {
                        hasInitialCommentsLoadError = false;
                        lastPaginationErrorTimeMs = 0L;
                        if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);
                        loadCommentsPage(1);
                    });
                }
            }

            LinearLayoutManager portraitLm = new LinearLayoutManager(context);
            this.portraitCommentsRecyclerView.setLayoutManager(portraitLm);
            this.portraitCommentsRecyclerView.setRecycledViewPool(sharedCommentViewPool);
            this.portraitCommentsRecyclerView.setItemViewCacheSize(10);
            this.portraitCommentsRecyclerView.setItemAnimator(null);
            this.portraitCommentsRecyclerView.setNestedScrollingEnabled(false);

            this.portraitCommentsAdapter = new CommentsAdapter();
            this.portraitCommentsAdapter.setPortraitViewportMode(true);
            this.portraitCommentsAdapter.setCommentActionListener(createCommentActionListener(true));
            this.portraitCommentsAdapter.setRepliesLimit(loadSavedRepliesLimit());
            this.portraitCommentsAdapter.setOnBatchCompletedListener(() -> {
                if (!isPortraitOrientation() || isOfflineMode || isCommentsDisabledInPlayer()) return;
                if (portraitCommentsAdapter != null && portraitCommentsAdapter.hasDeferredViewportItems()) return;
                if (isLoadingComments || !commentsHasNextPage || currentEpisode == null) return;
                if (portraitScrollView != null && portraitScrollView.getScrollY() > 0 && portraitScrollView.getChildCount() > 0) {
                    View content = portraitScrollView.getChildAt(0);
                    int remainingPx = content.getMeasuredHeight() - (portraitScrollView.getScrollY() + portraitScrollView.getMeasuredHeight());
                    if (remainingPx <= dpToPx(PORTRAIT_SCROLL_THRESHOLD_DP)) {
                        loadNextCommentsPageIfAvailable();
                    }
                }
            });
            this.portraitCommentsRecyclerView.setAdapter(this.portraitCommentsAdapter);

            if (portraitAdapterNeedsSync && !dataCoordinator.isPagesEmpty()) {
                portraitAdapterNeedsSync = false;
                dataCoordinator.syncCachedPagesIntoAdapter(this.portraitCommentsAdapter, !"asc".equals(commentsSortType));
            }
        }

        if (context instanceof Activity) {
            Activity act = (Activity) context;
            if (act.getWindow() != null) {
                keyboardScrollHelper.attachWindowRoot(act.getWindow().getDecorView());
            }
            portraitScrollView = act.findViewById(R.id.portraitScrollView);
            portraitOfflineCommentsNotice = act.findViewById(R.id.portraitOfflineCommentsNotice);
            portraitCommentsDisabledNotice = act.findViewById(R.id.portraitCommentsDisabledNotice);
            btnPortraitEnableComments = act.findViewById(R.id.btnPortraitEnableComments);
            if (btnPortraitEnableComments != null) {
                btnPortraitEnableComments.setOnClickListener(v -> enableCommentsInPlayer());
            }
            portraitCommentsHeader = act.findViewById(R.id.portraitCommentsHeader);
            portraitCommentInputContainer = act.findViewById(R.id.portraitCommentInputContainer);
            portraitCommentInputField = act.findViewById(R.id.portraitCommentInputField);
            btnPortraitSendComment = act.findViewById(R.id.btnPortraitSendComment);
            portraitSendCommentLoadingContainer = act.findViewById(R.id.portraitSendCommentLoadingContainer);
            portraitReplyBar = act.findViewById(R.id.portraitReplyBar);
            tvPortraitReplyToText = act.findViewById(R.id.tvPortraitReplyToText);
            btnPortraitCancelReply = act.findViewById(R.id.btnPortraitCancelReply);
            CommentsAdapter.applySendButtonLoadingState(btnPortraitSendComment, portraitSendCommentLoadingContainer, isSubmittingMainComment);

            View decorRoot = act.getWindow() != null ? act.getWindow().getDecorView() : null;
            bindCommentInputAndFormatting(
                    portraitCommentInputField,
                    btnPortraitSendComment,
                    btnPortraitCancelReply,
                    decorRoot,
                    R.id.btnPortraitFormatBold,
                    R.id.btnPortraitFormatItalic,
                    R.id.btnPortraitFormatUnderline,
                    R.id.btnPortraitFormatStrike,
                    R.id.btnPortraitFormatSpoiler,
                    R.id.btnPortraitFormatQuote
            );

            keyboardScrollHelper.bindPortraitViews(
                    portraitScrollView,
                    portraitCommentInputContainer,
                    portraitCommentInputField,
                    act.findViewById(R.id.btnPortraitScrollToTop)
            );
        }

        if (sortBtn != null) {
            sortBtn.setOnClickListener(v -> showSortOptionsDialog(v));
        }

        if (rulesBtn != null) {
            rulesBtn.setOnClickListener(v -> showRulesDialog());
        }

        if (this.portraitCommentsRecyclerView != null) {
            View root = this.portraitCommentsRecyclerView.getRootView();
            if (root != null) {
                View btnPortraitCommentSettings = root.findViewById(R.id.btnPortraitCommentSettings);
                if (btnPortraitCommentSettings != null) {
                    btnPortraitCommentSettings.setOnClickListener(v -> showSettingsDialog());
                }
            }
        }

        updateSortButtonText();
        if (isOfflineMode) {
            applyOfflineViewsState();
            return;
        }
        applyCommentsDisabledState(isCommentsDisabledInPlayer());
    }

    public void setOfflineMode(boolean offline) {
        this.isOfflineMode = offline;
        safeRunOnUiThread(this::applyOfflineViewsState);
    }

    public void applyOfflineViewsState() {
        if (isOfflineMode) {
            mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
            hideLoadingOverlaysAndErrors();
            if (portraitOfflineCommentsNotice != null) portraitOfflineCommentsNotice.setVisibility(View.VISIBLE);
            setCommentsContainerChromeVisibility(View.GONE);
            if (portraitEmptyCommentsText != null) portraitEmptyCommentsText.setVisibility(View.GONE);
            if (emptyCommentsText != null) {
                emptyCommentsText.setText("Недоступно в офлайн режиме");
                emptyCommentsText.setVisibility(View.VISIBLE);
            }
            isLoadingComments = false;
        } else {
            if (portraitOfflineCommentsNotice != null) portraitOfflineCommentsNotice.setVisibility(View.GONE);
            setCommentsContainerChromeVisibility(View.VISIBLE);
            if (emptyCommentsText != null) {
                emptyCommentsText.setText("Комментариев пока нет");
            }
            updateEmptyCommentsState();
        }
    }

    private void setCommentsContainerChromeVisibility(int visibility) {
        if (portraitCommentsHeader != null) portraitCommentsHeader.setVisibility(visibility);
        if (portraitCommentInputContainer != null) portraitCommentInputContainer.setVisibility(visibility);
        if (portraitCommentsRecyclerView != null) portraitCommentsRecyclerView.setVisibility(visibility);
        if (commentsRecyclerView != null) commentsRecyclerView.setVisibility(visibility);
        if (commentsPanel != null) {
            View panelInput = commentsPanel.findViewById(R.id.commentInputContainer);
            if (panelInput != null) panelInput.setVisibility(visibility);
            View sortBtn = commentsPanel.findViewById(R.id.btnSortComments);
            if (sortBtn != null) sortBtn.setVisibility(visibility);
            View rulesBtn = commentsPanel.findViewById(R.id.btnCommentRules);
            if (rulesBtn != null) rulesBtn.setVisibility(visibility);
        }
    }

    public void enableCommentsInPlayer() {
        try {
            DatabaseManager.getInstance(context).saveDisableCommentsInPlayerSetting(false);
        } catch (Exception ignored) {}
        applyCommentsDisabledState(false);
        updateCommentsButtonVisibility(true);
        if (settingsCallback != null) {
            settingsCallback.onCommentsInPlayerToggled(false);
        }
        boolean prependRoot = !"asc".equals(commentsSortType);
        if (dataCoordinator.isPagesEmpty() && !isLoadingComments) {
            hasInitialCommentsLoadError = false;
            lastPaginationErrorTimeMs = 0L;
            isPortraitCommentsRequested = true;
            loadCommentsPage(1);
        } else {
            if (isPortraitOrientation() && portraitCommentsAdapter != null && portraitAdapterNeedsSync) {
                portraitAdapterNeedsSync = false;
                dataCoordinator.syncCachedPagesIntoAdapter(portraitCommentsAdapter, prependRoot);
            } else if (!isPortraitOrientation() && commentsAdapter != null && landscapeAdapterNeedsSync) {
                landscapeAdapterNeedsSync = false;
                dataCoordinator.syncCachedPagesIntoAdapter(commentsAdapter, prependRoot);
            }
            updateEmptyCommentsState();
        }
    }

    public void applyCommentsDisabledState(boolean disabled) {
        safeRunOnUiThread(() -> {
            if (disabled) {
                mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
                if (portraitCommentsDisabledNotice != null) portraitCommentsDisabledNotice.setVisibility(View.VISIBLE);
                if (landscapeCommentsDisabledNotice != null) landscapeCommentsDisabledNotice.setVisibility(View.VISIBLE);
                if (portraitEmptyCommentsText != null) portraitEmptyCommentsText.setVisibility(View.GONE);
                if (emptyCommentsText != null) emptyCommentsText.setVisibility(View.GONE);
                hideLoadingOverlaysAndErrors();
                setCommentsContainerChromeVisibility(View.GONE);
                updateCommentsButtonVisibility(false);
            } else {
                if (portraitCommentsDisabledNotice != null) portraitCommentsDisabledNotice.setVisibility(View.GONE);
                if (landscapeCommentsDisabledNotice != null) landscapeCommentsDisabledNotice.setVisibility(View.GONE);
                if (!isOfflineMode) {
                    setCommentsContainerChromeVisibility(View.VISIBLE);
                    updateEmptyCommentsState();
                }
                updateCommentsButtonVisibility(true);
            }
        });
    }

    private void triggerInitialLoadIfNeeded() {
        if (isOfflineMode || isCommentsDisabledInPlayer() || currentEpisode == null || hasInitialCommentsLoadError) return;
        if (portraitAdapterNeedsSync && portraitCommentsAdapter != null && !dataCoordinator.isPagesEmpty()) {
            portraitAdapterNeedsSync = false;
            dataCoordinator.syncCachedPagesIntoAdapter(portraitCommentsAdapter, !"asc".equals(commentsSortType));
            updateEmptyCommentsState();
            return;
        }
        if (!isLoadingComments && dataCoordinator.isPagesEmpty()
                && (portraitCommentsAdapter == null || !portraitCommentsAdapter.hasAnySourceComments())) {
            loadCommentsPage(1);
        }
    }

    public void loadCommentsForPortraitIfNeeded() {
        if (isOfflineMode) {
            applyOfflineViewsState();
            return;
        }
        if (isCommentsDisabledInPlayer()) {
            applyCommentsDisabledState(true);
            return;
        }
        isPortraitCommentsRequested = true;

        if (portraitAdapterNeedsSync && portraitCommentsAdapter != null && !dataCoordinator.isPagesEmpty()) {
            portraitAdapterNeedsSync = false;
            dataCoordinator.syncCachedPagesIntoAdapter(portraitCommentsAdapter, !"asc".equals(commentsSortType));
            updateEmptyCommentsState();
            return;
        }

        if (currentEpisode != null && !isLoadingComments && !hasInitialCommentsLoadError && dataCoordinator.isPagesEmpty()
                && (portraitCommentsAdapter == null || !portraitCommentsAdapter.hasAnySourceComments())) {
            scheduleDeferredPortraitLoad();
        }
    }

    public void loadNextCommentsPageIfAvailable() {
        if (isOfflineMode || isCommentsDisabledInPlayer() || hasInitialCommentsLoadError) return;
        if (lastPaginationErrorTimeMs > 0L
                && (android.os.SystemClock.elapsedRealtime() - lastPaginationErrorTimeMs) < PAGINATION_ERROR_COOLDOWN_MS) {
            return;
        }
        if (!isLoadingComments && commentsHasNextPage && currentEpisode != null) {
            int nextPage = Math.max(2, commentsCurrentPage + 1);
            loadCommentsPage(nextPage);
        }
    }

    public void startReplyMode(CommentsResponse.CommentItem comment) {
        if (comment == null) return;
        editingComment = null;
        replyParentCommentId = comment.getId();
        replyRootId = comment.getRoot_id() != null ? comment.getRoot_id() : comment.getId();
        replyCommentLevel = comment.getComment_level() + 1;
        replyUsername = (comment.getUser() != null && comment.getUser().getUsername() != null)
                ? comment.getUser().getUsername() : "пользователю";

        String replyText = "Ответ для @" + replyUsername;
        showReplyBarsWithText(replyText);
    }

    public void startEditMode(CommentsResponse.CommentItem comment) {
        if (comment == null) return;
        cancelReplyMode();
        editingComment = comment;

        String cleanText = "";
        if (comment.getComment() != null) {
            cleanText = Html.fromHtml(comment.getComment(), Html.FROM_HTML_MODE_LEGACY).toString().trim();
        }

        showReplyBarsWithText("Редактирование комментария");

        if (commentInputField != null) {
            commentInputField.setText(cleanText);
            try { commentInputField.setSelection(cleanText.length()); } catch (Exception ignored) {}
        }
        if (portraitCommentInputField != null) {
            portraitCommentInputField.setText(cleanText);
            try { portraitCommentInputField.setSelection(cleanText.length()); } catch (Exception ignored) {}
        }

        boolean portraitMode = isPortraitOrientation();
        EditText targetInput = portraitMode ? portraitCommentInputField : commentInputField;
        View targetContainer = portraitMode ? portraitCommentInputContainer : commentInputContainer;
        if (targetInput != null) {
            targetInput.requestFocus();
            keyboardScrollHelper.onCommentInputActivated(targetInput, targetContainer, portraitMode);
            try {
                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(targetInput, InputMethodManager.SHOW_IMPLICIT);
                }
            } catch (Exception ignored) {}
        }
    }

    private void showReplyBarsWithText(String titleText) {
        if (replyBar != null && tvReplyToText != null) {
            tvReplyToText.setText(titleText);
            replyBar.setVisibility(View.VISIBLE);
        }
        if (portraitReplyBar != null && tvPortraitReplyToText != null) {
            tvPortraitReplyToText.setText(titleText);
            portraitReplyBar.setVisibility(View.VISIBLE);
        }
    }

    public void cancelMainInputReplyMode() {
        editingComment = null;
        replyParentCommentId = null;
        replyRootId = null;
        replyCommentLevel = 0;
        replyUsername = null;

        if (replyBar != null) replyBar.setVisibility(View.GONE);
        if (portraitReplyBar != null) portraitReplyBar.setVisibility(View.GONE);
    }

    public void cancelReplyMode() {
        cancelMainInputReplyMode();
        if (commentsAdapter != null) commentsAdapter.setActiveReplyCommentId(null);
        if (portraitCommentsAdapter != null) portraitCommentsAdapter.setActiveReplyCommentId(null);
        keyboardScrollHelper.updateLandscapeMainInputVisibility();
    }

    public void insertSpoilerTag(EditText editText) {
        if (editText == null) return;
        CommentFormattingHelper.applyFormat(editText, CommentFormattingHelper.FormatType.SPOILER);
    }

    private void setMainInputSubmittingState(boolean submitting) {
        isSubmittingComment = submitting;
        isSubmittingMainComment = submitting;
        CommentsAdapter.applySendButtonLoadingState(btnSendComment, sendCommentLoadingContainer, submitting);
        CommentsAdapter.applySendButtonLoadingState(btnPortraitSendComment, portraitSendCommentLoadingContainer, submitting);
    }

    private void setInlineReplySubmittingState(Long commentId, EditText inputField, boolean submitting) {
        isSubmittingComment = submitting;
        Long targetId = submitting ? commentId : null;
        if (commentsAdapter != null) {
            commentsAdapter.setSubmittingReplyCommentId(targetId);
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.setSubmittingReplyCommentId(targetId);
        }

        if (submitting) {
            View container = findAncestorById(inputField, R.id.inlineReplyContainer);
            if (container != null) {
                activeInlineSendButton = container.findViewById(R.id.btnInlineSendComment);
                activeInlineLoadingContainer = container.findViewById(R.id.inlineSendCommentLoadingContainer);
            } else {
                activeInlineSendButton = null;
                activeInlineLoadingContainer = null;
            }
            CommentsAdapter.applySendButtonLoadingState(activeInlineSendButton, activeInlineLoadingContainer, true);
        } else {
            CommentsAdapter.applySendButtonLoadingState(activeInlineSendButton, activeInlineLoadingContainer, false);
            if (inputField != null) {
                View container = findAncestorById(inputField, R.id.inlineReplyContainer);
                if (container != null) {
                    View sendBtn = container.findViewById(R.id.btnInlineSendComment);
                    View loadBox = container.findViewById(R.id.inlineSendCommentLoadingContainer);
                    CommentsAdapter.applySendButtonLoadingState(sendBtn, loadBox, false);
                }
            }
            activeInlineSendButton = null;
            activeInlineLoadingContainer = null;
        }
    }

    private void resetAllSubmittingUiStates() {
        isSubmittingComment = false;
        isSubmittingMainComment = false;
        CommentsAdapter.applySendButtonLoadingState(btnSendComment, sendCommentLoadingContainer, false);
        CommentsAdapter.applySendButtonLoadingState(btnPortraitSendComment, portraitSendCommentLoadingContainer, false);
        if (commentsAdapter != null) {
            commentsAdapter.setSubmittingReplyCommentId(null);
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.setSubmittingReplyCommentId(null);
        }
        CommentsAdapter.applySendButtonLoadingState(activeInlineSendButton, activeInlineLoadingContainer, false);
        activeInlineSendButton = null;
        activeInlineLoadingContainer = null;
    }

    private static View findAncestorById(View startView, int targetId) {
        if (startView == null) return null;
        android.view.ViewParent parent = startView.getParent();
        while (parent instanceof View) {
            View v = (View) parent;
            if (v.getId() == targetId) {
                return v;
            }
            parent = v.getParent();
        }
        return null;
    }

    public void sendCommentFromInput(EditText inputField) {
        if (inputField == null || currentEpisode == null || apiService == null) return;
        if (isSubmittingComment) return;

        CharSequence rawEditable = inputField.getText();
        String text = rawEditable != null ? CommentFormattingHelper.toBBCode(rawEditable).trim() : "";
        if (text.isEmpty()) {
            CustomToast.showWarning(context, "Введите текст комментария");
            return;
        }

        long episodeId = currentEpisode.getId();

        if (editingComment != null) {
            final CommentsResponse.CommentItem originalEditing = editingComment;
            long commentId = originalEditing.getId();
            Long parentId = originalEditing.getParent_comment();
            Long rootId = originalEditing.getRoot_id();
            int level = originalEditing.getComment_level();

            setMainInputSubmittingState(true);

            apiService.editComment(commentId, episodeId, text, parentId, rootId, level, new ApiService.EditCommentCallback() {
                @Override
                public void onSuccess(CommentsResponse.CommentItem editedComment) {
                    safeRunOnUiThread(() -> {
                        setMainInputSubmittingState(false);
                        inputField.setText("");
                        if (commentInputField != null) commentInputField.setText("");
                        if (portraitCommentInputField != null) portraitCommentInputField.setText("");

                        cancelReplyMode();
                        hideKeyboard(inputField);

                        CommentsResponse.CommentItem itemToApply = editedComment != null ? editedComment : originalEditing;
                        if (itemToApply.getId() <= 0) {
                            itemToApply.setId(commentId);
                        }
                        if (editedComment == null || itemToApply.getComment() == null || itemToApply.getComment().trim().isEmpty()) {
                            itemToApply.setComment(CommentsDataCoordinator.buildFallbackHtmlFromBBCode(text));
                        }
                        handleCommentMutationSuccess(episodeId, itemToApply, text, parentId, rootId, level, true);
                    });
                }

                @Override
                public void onError(String error) {
                    safeRunOnUiThread(() -> {
                        setMainInputSubmittingState(false);
                        String msg = (error != null && !error.trim().isEmpty()) ? error : "Ошибка сохранения комментария";
                        CustomToast.showWarning(context, msg);
                    });
                }
            });
            return;
        }

        boolean isReplyActive = (replyBar != null && replyBar.getVisibility() == View.VISIBLE)
                || (portraitReplyBar != null && portraitReplyBar.getVisibility() == View.VISIBLE);

        Long parentId = isReplyActive ? replyParentCommentId : null;
        Long rootId = isReplyActive ? replyRootId : null;
        int level = isReplyActive ? replyCommentLevel : 0;

        setMainInputSubmittingState(true);

        apiService.postComment(episodeId, text, parentId, rootId, level, new ApiService.PostCommentCallback() {
            @Override
            public void onSuccess(CommentsResponse.CommentItem postedComment) {
                safeRunOnUiThread(() -> {
                    setMainInputSubmittingState(false);
                    inputField.setText("");
                    if (commentInputField != null) commentInputField.setText("");
                    if (portraitCommentInputField != null) portraitCommentInputField.setText("");

                    cancelReplyMode();
                    hideKeyboard(inputField);

                    handleCommentMutationSuccess(episodeId, postedComment, text, parentId, rootId, level, false);
                });
            }

            @Override
            public void onError(String error) {
                safeRunOnUiThread(() -> {
                    setMainInputSubmittingState(false);
                    String msg = (error != null && !error.trim().isEmpty()) ? error : "Ошибка публикации комментария";
                    CustomToast.showWarning(context, msg);
                });
            }
        });
    }

    public void sendInlineReply(CommentsResponse.CommentItem comment, CharSequence rawText, EditText inputField) {
        if (comment == null || currentEpisode == null || apiService == null) return;
        if (isSubmittingComment) return;

        String text = rawText != null ? CommentFormattingHelper.toBBCode(rawText).trim() : "";
        if (text.isEmpty()) {
            CustomToast.showWarning(context, "Введите текст ответа");
            return;
        }

        long episodeId = currentEpisode.getId();
        Long parentId = comment.getId();
        Long rootId = comment.getRoot_id() != null ? comment.getRoot_id() : comment.getId();
        int level = comment.getComment_level() + 1;

        setInlineReplySubmittingState(comment.getId(), inputField, true);

        apiService.postComment(episodeId, text, parentId, rootId, level, new ApiService.PostCommentCallback() {
            @Override
            public void onSuccess(CommentsResponse.CommentItem postedComment) {
                safeRunOnUiThread(() -> {
                    setInlineReplySubmittingState(comment.getId(), inputField, false);
                    if (inputField != null) inputField.setText("");

                    cancelReplyMode();
                    hideKeyboard(inputField);

                    handleCommentMutationSuccess(episodeId, postedComment, text, parentId, rootId, level, false);
                });
            }

            @Override
            public void onError(String error) {
                safeRunOnUiThread(() -> {
                    setInlineReplySubmittingState(comment.getId(), inputField, false);
                    String msg = (error != null && !error.trim().isEmpty()) ? error : "Ошибка отправки ответа";
                    CustomToast.showWarning(context, msg);
                });
            }
        });
    }

    private void handleCommentMutationSuccess(long episodeId,
                                              CommentsResponse.CommentItem mutatedComment,
                                              String submittedText,
                                              Long parentId,
                                              Long rootId,
                                              int level,
                                              boolean isEdit) {
        if (apiService != null) {
            apiService.invalidateEpisodeCommentsCache(episodeId);
        }
        if (currentEpisode == null || currentEpisode.getId() != episodeId) {
            return;
        }

        mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
        isPortraitCommentsRequested = true;
        hasInitialCommentsLoadError = false;
        lastPaginationErrorTimeMs = 0L;

        boolean prependRoot = !"asc".equals(commentsSortType);
        if (mutatedComment != null && mutatedComment.getId() > 0) {
            dataCoordinator.normalizeMutatedComment(
                    mutatedComment,
                    episodeId,
                    submittedText,
                    parentId,
                    rootId,
                    level,
                    moderationHelper.resolveDatabaseManager()
            );

            dataCoordinator.removeParsed(mutatedComment.getId());
            int primaryTextColor = CommentHtmlProcessor.resolvePrimaryTextColor(context);
            int purpleColor = context != null ? ContextCompat.getColor(context, R.color.purple_primary) : 0xFF8B5CF6;
            CommentHtmlProcessor.ParsedCommentContent parsed = CommentHtmlProcessor.parseHtmlOffThread(
                    mutatedComment.getComment(), primaryTextColor, purpleColor);
            dataCoordinator.putSingleParsed(mutatedComment.getId(), parsed);

            Map<Long, CommentHtmlProcessor.ParsedCommentContent> singleMap = new HashMap<>();
            singleMap.put(mutatedComment.getId(), parsed);

            dataCoordinator.upsertIntoLoadedPageResponses(mutatedComment, prependRoot);

            if (commentsAdapter != null) {
                commentsAdapter.putParsedContentMap(singleMap);
                commentsAdapter.upsertComment(mutatedComment, prependRoot);
            }
            if (portraitCommentsAdapter != null) {
                portraitCommentsAdapter.putParsedContentMap(singleMap);
                portraitCommentsAdapter.upsertComment(mutatedComment, prependRoot);
            }

            if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
            if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);
            updateEmptyCommentsState();

            boolean isNewRoot = !isEdit && (parentId == null || parentId <= 0);
            if (isNewRoot && prependRoot && !isPortraitOrientation() && isCommentsVisible
                    && commentsRecyclerView != null && commentsAdapter != null) {
                int targetPos = commentsAdapter.findCommentAdapterPosition(mutatedComment.getId());
                if (targetPos >= 0) {
                    commentsRecyclerView.scrollToPosition(targetPos);
                }
            }
        }

        int targetPage = dataCoordinator.findPageForMutationRefresh(mutatedComment, parentId, rootId, isEdit);
        refreshCommentsPageFromServer(episodeId, targetPage, mutatedComment, isEdit, true);
    }

    private void refreshCommentsPageFromServer(long episodeId,
                                               int pageToRefresh,
                                               CommentsResponse.CommentItem mutatedComment,
                                               boolean isEdit,
                                               boolean allowReplicaLagRetry) {
        if (isOfflineMode || isCommentsDisabledInPlayer() || apiService == null) {
            return;
        }
        if (currentEpisode == null || currentEpisode.getId() != episodeId) {
            return;
        }

        final int targetPage = Math.max(1, pageToRefresh);
        final int gen = ++requestGeneration;
        isLoadingComments = true;

        boolean hasAnyDisplayed = (commentsAdapter != null && commentsAdapter.getCommentDisplayCount() > 0)
                || (portraitCommentsAdapter != null && portraitCommentsAdapter.getCommentDisplayCount() > 0);
        if (!hasAnyDisplayed) {
            if (!isPortraitOrientation() && isCommentsVisible) {
                showLoadingOverlay(commentsLoadingOverlay);
            } else if (isPortraitOrientation()) {
                showLoadingOverlay(portraitCommentsLoadingOverlay);
            }
        }

        final int primaryTextColor = CommentHtmlProcessor.resolvePrimaryTextColor(context);
        final int purpleColor = context != null ? ContextCompat.getColor(context, R.color.purple_primary) : 0xFF8B5CF6;

        apiService.fetchEpisodeComments(episodeId, commentsSortType, targetPage, true,
                new ApiService.EpisodeCommentsCallback() {
                    @Override
                    public void onCommentsReceived(CommentsResponse response) {
                        commentsProcessingExecutor.execute(() -> {
                            List<CommentsResponse.CommentItem> allItems = CommentsDataCoordinator.extractAllItems(response);
                            dataCoordinator.invalidateParsedForItems(allItems);
                            Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedMap =
                                    dataCoordinator.preParseCommentsBatch(allItems, primaryTextColor, purpleColor);

                            safeRunOnUiThread(() -> {
                                if (gen != requestGeneration) {
                                    return;
                                }
                                isLoadingComments = false;
                                hideLoadingOverlaysAndErrors();

                                putParsedContentOnBothAdapters(parsedMap);
                                setLoadingMoreOnBothAdapters(false);

                                if (response != null) {
                                    CommentsDataCoordinator.PageMetadata meta = dataCoordinator.applyRefreshedPage(
                                            targetPage, response, commentsCurrentPage, commentsHasNextPage);
                                    commentsCurrentPage = meta.currentPage;
                                    commentsHasNextPage = meta.hasNextPage;

                                    boolean prependRoot = !"asc".equals(commentsSortType);
                                    boolean foundInServer = mutatedComment != null
                                            && CommentsDataCoordinator.responseContainsComment(response, mutatedComment.getId());
                                    if (!isEdit && mutatedComment != null && mutatedComment.getId() > 0 && !foundInServer) {
                                        dataCoordinator.upsertIntoLoadedPageResponses(mutatedComment, prependRoot);
                                    }

                                    CommentsResponse.CommentItem ensureItem = (!isEdit && mutatedComment != null) ? mutatedComment : null;
                                    List<CommentsResponse.CommentItem> stickyList = dataCoordinator.getLoadedStickyComments();
                                    List<CommentsResponse> pages = dataCoordinator.getLoadedPageResponses();
                                    if (isPortraitOrientation()) {
                                        if (portraitCommentsAdapter != null) {
                                            portraitCommentsAdapter.replacePagesOnRefresh(stickyList, pages, ensureItem, prependRoot);
                                        }
                                        landscapeAdapterNeedsSync = true;
                                    } else {
                                        if (isCommentsVisible && !isPanelAnimatingOpen && commentsAdapter != null) {
                                            commentsAdapter.replacePagesOnRefresh(stickyList, pages, ensureItem, prependRoot);
                                        } else {
                                            landscapeAdapterNeedsSync = true;
                                        }
                                        portraitAdapterNeedsSync = true;
                                    }

                                    updateEmptyCommentsState();

                                    if (dataCallback != null) {
                                        dataCallback.onCommentsLoaded(allItems);
                                    }

                                    if (!isEdit && mutatedComment != null && mutatedComment.getId() > 0
                                            && !foundInServer && allowReplicaLagRetry && "desc".equals(commentsSortType)) {
                                        mainHandler.postDelayed(() -> {
                                            if (currentEpisode != null && currentEpisode.getId() == episodeId && !isLoadingComments) {
                                                refreshCommentsPageFromServer(episodeId, targetPage, mutatedComment, false, false);
                                            }
                                        }, 1500L);
                                    }
                                } else {
                                    updateEmptyCommentsState();
                                }
                            });
                        });
                    }

                    @Override
                    public void onError(String error) {
                        safeRunOnUiThread(() -> {
                            if (gen != requestGeneration) {
                                return;
                            }
                            isLoadingComments = false;
                            hideLoadingOverlay(commentsLoadingOverlay);
                            hideLoadingOverlay(portraitCommentsLoadingOverlay);
                            setLoadingMoreOnBothAdapters(false);
                            updateEmptyCommentsState();
                        });
                    }
                });
    }

    private void hideKeyboard(View targetView) {
        if (context == null || targetView == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(targetView.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }

    public void voteOnComment(CommentsResponse.CommentItem comment, int targetVote) {
        if (comment == null || apiService == null) return;
        apiService.voteComment(comment.getId(), targetVote, new ApiService.VoteCommentCallback() {
            @Override
            public void onSuccess(int newVoteValue, CommentsResponse.Votes updatedVotes) {
                safeRunOnUiThread(() -> {
                    CommentsDataCoordinator.applyLocalVoteUpdate(comment, newVoteValue, updatedVotes);

                    // Точечное обновление только оценённого комментария вместо notifyDataSetChanged() всего списка!
                    if (commentsAdapter != null) {
                        commentsAdapter.notifyCommentChanged(comment.getId());
                    }
                    if (portraitCommentsAdapter != null) {
                        portraitCommentsAdapter.notifyCommentChanged(comment.getId());
                    }
                });
            }

            @Override
            public void onError(String error) {
                safeRunOnUiThread(() -> CustomToast.showWarning(context, "Не удалось оценить комментарий"));
            }
        });
    }

    public void showRulesDialog() {
        moderationHelper.showRulesDialog();
    }

    public void setSettingsCallback(CommentsSettingsCallback callback) {
        this.settingsCallback = callback;
    }

    public void showSettingsDialog() {
        moderationHelper.showSettingsDialog();
    }

    public void updateRepliesLimit(int limit) {
        if (commentsAdapter != null) {
            commentsAdapter.setRepliesLimit(limit);
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.setRepliesLimit(limit);
        }
    }

    public boolean isCommentsDisabledInPlayer() {
        try {
            return DatabaseManager.getInstance(context).loadDisableCommentsInPlayerSetting();
        } catch (Exception e) {
            return false;
        }
    }

    private int loadSavedRepliesLimit() {
        try {
            return DatabaseManager.getInstance(context).loadCommentRepliesLimitSetting();
        } catch (Exception e) {
            return 3;
        }
    }

    public void setVisibilityCallback(CommentsVisibilityCallback callback) {
        this.visibilityCallback = callback;
    }

    public void setDataCallback(CommentsDataCallback callback) {
        this.dataCallback = callback;
    }

    public void updateCommentsButtonVisibility(boolean isVisible) {
        if (isCommentsDisabledInPlayer()) {
            isVisible = false;
        }
        if (commentsButton != null) {
            commentsButton.setVisibility(isVisible ? View.VISIBLE : View.GONE);
        }
    }

    public void hideAllCommentsUI() {
        if (isCommentsVisible) {
            hideCommentsPanel();
        }
        updateCommentsButtonVisibility(false);
    }

    public void showAllCommentsUI(boolean wasVisibleBeforePiP) {
        updateCommentsButtonVisibility(true);
        if (wasVisibleBeforePiP && !isCommentsVisible) {
            showCommentsPanel();
        }
    }

    private void safeRunOnUiThread(Runnable runnable) {
        if (runnable == null) return;
        try {
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    activity.runOnUiThread(runnable);
                } else {
                    mainHandler.post(runnable);
                }
            } else {
                mainHandler.post(runnable);
            }
        } catch (Exception e) {
            mainHandler.post(runnable);
        }
    }

    public void cleanup() {
        requestGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        keyboardScrollHelper.cleanup();
        commentsProcessingExecutor.shutdownNow();

        if (commentsAdapter != null) {
            commentsAdapter.clearAll();
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.clearAll();
        }

        dataCoordinator.clearAll(true);

        if (emptyCommentsText != null) {
            emptyCommentsText.setVisibility(View.GONE);
        }

        commentsCurrentPage = 0;
        commentsHasNextPage = false;
        isLoadingComments = false;
        hasInitialCommentsLoadError = false;
        lastPaginationErrorTimeMs = 0L;
        resetAllSubmittingUiStates();
        isCommentsVisible = false;
        isPanelAnimatingOpen = false;

        visibilityCallback = null;
        dataCallback = null;
    }

    public void completeDrag(boolean shouldOpen) {
        if (shouldOpen) {
            if (isCommentsVisible && !isPanelAnimatingOpen) {
                return;
            }
            isCommentsVisible = true;
            isPanelAnimatingOpen = false;
            syncOrLoadLandscapeComments();
            if (visibilityCallback != null) {
                visibilityCallback.onCommentsVisibilityChanged(true);
            }
        } else {
            hideCommentsPanel();
        }
    }

    public void updateDragState(boolean isOpen) {
        mainHandler.removeCallbacks(panelOpenFallbackRunnable);
        isPanelAnimatingOpen = false;
        if (isOpen) {
            isCommentsVisible = true;
            syncOrLoadLandscapeComments();
            if (visibilityCallback != null) {
                visibilityCallback.onCommentsVisibilityChanged(true);
            }
        } else {
            isCommentsVisible = false;
            if (visibilityCallback != null) {
                visibilityCallback.onCommentsVisibilityChanged(false);
            }
        }
    }

    private void showLoadingOverlay(View overlay) {
        if (overlay == null) return;
        overlay.animate().cancel();
        if (overlay.getVisibility() == View.VISIBLE && overlay.getAlpha() >= 0.99f) {
            return;
        }
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(180).start();
    }

    private void hideLoadingOverlay(View overlay) {
        if (overlay == null) return;
        overlay.animate().cancel();
        if (overlay.getVisibility() != View.VISIBLE) {
            return;
        }
        overlay.animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> overlay.setVisibility(View.GONE))
                .start();
    }
}
