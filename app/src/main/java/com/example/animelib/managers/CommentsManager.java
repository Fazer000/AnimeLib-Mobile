package com.example.animelib.managers;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.VideoPlayerActivity;
import com.example.animelib.adapters.CommentsAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.settings.CommentSettingsBottomSheet;
import com.example.animelib.ui.RulesBottomSheet;
import com.example.animelib.util.CommentFormattingHelper;
import com.example.animelib.util.CommentHtmlProcessor;
import com.example.animelib.util.CommentKeyboardScrollHelper;
import com.example.animelib.util.CustomToast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private String commentsSortType = "desc";
    private int requestGeneration = 0;
    private boolean isPortraitCommentsRequested = false;
    private boolean isPlayerReadyForComments = false;
    private float cachedDensity = 0f;

    // Кэш загруженных страниц и предварительно распарсенного HTML для мгновенной синхронизации между ориентациями
    private final List<CommentsResponse.CommentItem> loadedStickyComments = new ArrayList<>();
    private final List<CommentsResponse> loadedPageResponses = new ArrayList<>();
    private final Map<Long, CommentHtmlProcessor.ParsedCommentContent> sharedParsedCache = new ConcurrentHashMap<>();
    private boolean landscapeAdapterNeedsSync = false;
    private boolean portraitAdapterNeedsSync = false;
    private boolean isPanelAnimatingOpen = false;

    // Фоновый поток с пониженным приоритетом, чтобы парсинг Jsoup/HTML не конкурировал с декодером ExoPlayer
    private final ExecutorService commentsProcessingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch ( Throwable ignored) {}
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

            if (commentInputField != null) {
                CommentFormattingHelper.attachFormattingTextWatcher(commentInputField);
            }

            View btnBold = commentsPanel.findViewById(R.id.btnFormatBold);
            View btnItalic = commentsPanel.findViewById(R.id.btnFormatItalic);
            View btnUnderline = commentsPanel.findViewById(R.id.btnFormatUnderline);
            View btnStrike = commentsPanel.findViewById(R.id.btnFormatStrike);
            View btnSpoiler = commentsPanel.findViewById(R.id.btnFormatSpoiler);
            View btnQuote = commentsPanel.findViewById(R.id.btnFormatQuote);

            if (btnBold != null) btnBold.setOnClickListener(v -> CommentFormattingHelper.applyFormat(commentInputField, CommentFormattingHelper.FormatType.BOLD));
            if (btnItalic != null) btnItalic.setOnClickListener(v -> CommentFormattingHelper.applyFormat(commentInputField, CommentFormattingHelper.FormatType.ITALIC));
            if (btnUnderline != null) btnUnderline.setOnClickListener(v -> CommentFormattingHelper.applyFormat(commentInputField, CommentFormattingHelper.FormatType.UNDERLINE));
            if (btnStrike != null) btnStrike.setOnClickListener(v -> CommentFormattingHelper.applyFormat(commentInputField, CommentFormattingHelper.FormatType.STRIKE));
            if (btnSpoiler != null) btnSpoiler.setOnClickListener(v -> CommentFormattingHelper.applyFormat(commentInputField, CommentFormattingHelper.FormatType.SPOILER));
            if (btnQuote != null) btnQuote.setOnClickListener(v -> CommentFormattingHelper.applyFormat(commentInputField, CommentFormattingHelper.FormatType.QUOTE));

            if (btnSendComment != null) {
                btnSendComment.setOnClickListener(v -> sendCommentFromInput(commentInputField));
            }
            if (btnCancelReply != null) {
                btnCancelReply.setOnClickListener(v -> {
                    if (!isSubmittingComment) {
                        cancelReplyMode();
                    }
                });
            }
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

            if (commentInputField != null) {
                commentInputField.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEND) {
                        sendCommentFromInput(commentInputField);
                        return true;
                    }
                    return false;
                });
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
        if (target == null || context == null) return;
        if (!target.isAttachedToWindow()) return;
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) return;
        }

        try {
            View popupView = LayoutInflater.from(context).inflate(R.layout.popup_comments_sort, null);

            PopupWindow popupWindow = new PopupWindow(
                    popupView,
                    dpToPx(160),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
            );

            popupWindow.setOutsideTouchable(true);
            popupWindow.setFocusable(true);
            popupWindow.setElevation(dpToPx(12));
            popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            TextView itemNew = popupView.findViewById(R.id.item_sort_new);
            TextView itemOld = popupView.findViewById(R.id.item_sort_old);
            TextView itemPopular = popupView.findViewById(R.id.item_sort_popular);

            if (itemNew != null) resetPopupItemStyle(itemNew);
            if (itemOld != null) resetPopupItemStyle(itemOld);
            if (itemPopular != null) resetPopupItemStyle(itemPopular);

            if ("asc".equals(commentsSortType)) {
                if (itemOld != null) setPopupItemSelectedStyle(itemOld);
            } else if ("votes_up".equals(commentsSortType)) {
                if (itemPopular != null) setPopupItemSelectedStyle(itemPopular);
            } else {
                if (itemNew != null) setPopupItemSelectedStyle(itemNew);
            }

            if (itemNew != null) {
                itemNew.setOnClickListener(v -> {
                    try { popupWindow.dismiss(); } catch (Exception ignored) {}
                    changeCommentsSort("desc");
                });
            }

            if (itemOld != null) {
                itemOld.setOnClickListener(v -> {
                    try { popupWindow.dismiss(); } catch (Exception ignored) {}
                    changeCommentsSort("asc");
                });
            }

            if (itemPopular != null) {
                itemPopular.setOnClickListener(v -> {
                    try { popupWindow.dismiss(); } catch (Exception ignored) {}
                    changeCommentsSort("votes_up");
                });
            }

            popupWindow.showAsDropDown(target, 0, dpToPx(6));
        } catch (Exception e) {
            Log.e(TAG, "Error showing sort options popup", e);
        }
    }

    public void showCommentActionsPopup(View anchorView, CommentsResponse.CommentItem comment) {
        if (anchorView == null || comment == null || context == null) return;
        if (!anchorView.isAttachedToWindow()) return;
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) return;
        }

        try {
            View popupView = LayoutInflater.from(context).inflate(R.layout.popup_comment_actions, null);

            PopupWindow popupWindow = new PopupWindow(
                    popupView,
                    dpToPx(200),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
            );

            popupWindow.setOutsideTouchable(true);
            popupWindow.setFocusable(true);
            popupWindow.setElevation(dpToPx(12));
            popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            View itemEdit = popupView.findViewById(R.id.item_comment_edit);
            View itemDelete = popupView.findViewById(R.id.item_comment_delete);
            View itemCopy = popupView.findViewById(R.id.item_comment_copy);
            View itemReport = popupView.findViewById(R.id.item_comment_report);
            View itemIgnore = popupView.findViewById(R.id.item_comment_ignore);

            boolean isAuth = apiService != null && apiService.isAuthorized();
            boolean isOwn = isOwnComment(comment);

            if (itemCopy != null) {
                itemCopy.setOnClickListener(v -> {
                    try { popupWindow.dismiss(); } catch (Exception ignored) {}
                    try {
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        String rawComment = comment.getComment() != null ? comment.getComment() : "";
                        String plainText = Html.fromHtml(rawComment, Html.FROM_HTML_MODE_LEGACY).toString().trim();
                        ClipData clip = ClipData.newPlainText("Комментарий", plainText);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(clip);
                            CustomToast.showSuccess(context, "Комментарий скопирован в буфер обмена");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error copying comment", e);
                    }
                });
            }

            if (itemEdit != null) {
                itemEdit.setVisibility(isOwn ? View.VISIBLE : View.GONE);
                itemEdit.setOnClickListener(v -> {
                    try { popupWindow.dismiss(); } catch (Exception ignored) {}
                    startEditMode(comment);
                });
            }

            if (itemDelete != null) {
                itemDelete.setVisibility(isOwn ? View.VISIBLE : View.GONE);
                itemDelete.setOnClickListener(v -> {
                    try { popupWindow.dismiss(); } catch (Exception ignored) {}
                    confirmAndDeleteComment(comment);
                });
            }

            if (itemReport != null) {
                itemReport.setOnClickListener(v -> {
                    try { popupWindow.dismiss(); } catch (Exception ignored) {}
                    CustomToast.showSuccess(context, "Жалоба отправлена");
                });
            }

            if (itemIgnore != null) {
                boolean canIgnore = isAuth && !isOwn && comment.getUser() != null;
                itemIgnore.setVisibility(canIgnore ? View.VISIBLE : View.GONE);
                itemIgnore.setOnClickListener(v -> {
                    try { popupWindow.dismiss(); } catch (Exception ignored) {}
                    showIgnoreUserDialog(comment);
                });
            }

            popupWindow.showAsDropDown(anchorView, 0, dpToPx(4));
        } catch (Exception e) {
            Log.e(TAG, "Error showing comment actions popup", e);
        }
    }

    public void showIgnoreUserDialog(CommentsResponse.CommentItem comment) {
        if (comment == null || comment.getUser() == null || context == null) return;

        try {
            View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_ignore_user, null);
            ImageButton btnClose = dialogView.findViewById(R.id.btnCloseIgnoreDialog);
            EditText etComment = dialogView.findViewById(R.id.etIgnoreComment);
            View btnToggleInfo = dialogView.findViewById(R.id.btnToggleIgnoreInfo);
            ImageView ivChevron = dialogView.findViewById(R.id.ivIgnoreInfoChevron);
            View infoContent = dialogView.findViewById(R.id.ignoreInfoContentContainer);
            View btnCancel = dialogView.findViewById(R.id.btnCancelIgnore);
            View btnConfirm = dialogView.findViewById(R.id.btnConfirmIgnore);

            if (btnToggleInfo != null && infoContent != null) {
                btnToggleInfo.setOnClickListener(v -> {
                    if (infoContent.getVisibility() == View.VISIBLE) {
                        infoContent.setVisibility(View.GONE);
                        if (ivChevron != null) ivChevron.setImageResource(R.drawable.ic_chevron_down);
                    } else {
                        infoContent.setVisibility(View.VISIBLE);
                        if (ivChevron != null) ivChevron.setImageResource(R.drawable.ic_chevron_up);
                    }
                });
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setView(dialogView);
            AlertDialog dialog = builder.create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }

            if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
            if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

            if (btnConfirm != null) {
                btnConfirm.setOnClickListener(v -> {
                    String commentText = etComment != null && etComment.getText() != null
                            ? etComment.getText().toString().trim() : "";

                    long targetUserId = comment.getUser().getId();
                    long sourceableId = 0;

                    if (apiService != null) {
                        String currentUserIdStr = apiService.getCurrentUserId();
                        if (currentUserIdStr != null && !currentUserIdStr.trim().isEmpty()) {
                            try {
                                sourceableId = Long.parseLong(currentUserIdStr.trim());
                            } catch (Exception ignored) {}
                        }
                    }

                    if (sourceableId <= 0) {
                        try {
                            DatabaseManager db = apiService != null ? apiService.getDatabaseManager() : new DatabaseManager(context);
                            if (db != null && db.getToken() != null) {
                                String uId = db.getToken().getUserId();
                                if (uId != null && !uId.trim().isEmpty()) {
                                    sourceableId = Long.parseLong(uId.trim());
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    if (sourceableId <= 0) {
                        CustomToast.showWarning(context, "Не удалось определить ID пользователя");
                        return;
                    }

                    dialog.dismiss();
                    CustomToast.showInfo(context, "Добавление в игнор-лист...");

                    if (apiService != null) {
                        apiService.ignoreUser(sourceableId, targetUserId, commentText, new ApiService.IgnoreUserCallback() {
                            @Override
                            public void onSuccess(String message) {
                                safeRunOnUiThread(() -> {
                                    CustomToast.showSuccess(context, "Пользователь добавлен в игнор-лист");
                                    if (apiService != null) {
                                        apiService.invalidateAllCommentsCache();
                                    }
                                    resetCommentsOnEpisodeChange(false);
                                    loadCommentsPage(1);
                                });
                            }

                            @Override
                            public void onError(String errorMsg) {
                                safeRunOnUiThread(() -> CustomToast.showWarning(context, errorMsg));
                            }
                        });
                    }
                });
            }

            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing ignore user dialog", e);
        }
    }

    public void confirmAndDeleteComment(CommentsResponse.CommentItem comment) {
        if (comment == null || context == null) return;

        try {
            View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_delete_comment, null);
            ImageButton btnClose = dialogView.findViewById(R.id.btnClose);
            TextView tvCommentAuthor = dialogView.findViewById(R.id.tvCommentAuthor);
            TextView tvCommentSnippet = dialogView.findViewById(R.id.tvCommentSnippet);
            View commentPreviewContainer = dialogView.findViewById(R.id.commentPreviewContainer);
            View btnCancel = dialogView.findViewById(R.id.btnCancel);
            View btnDelete = dialogView.findViewById(R.id.btnDelete);

            if (commentPreviewContainer != null && comment.getComment() != null && !comment.getComment().trim().isEmpty()) {
                commentPreviewContainer.setVisibility(View.VISIBLE);
                if (comment.getUser() != null && comment.getUser().getUsername() != null && tvCommentAuthor != null) {
                    tvCommentAuthor.setText(comment.getUser().getUsername());
                    tvCommentAuthor.setVisibility(View.VISIBLE);
                }
                if (tvCommentSnippet != null) {
                    CharSequence cleanText = Html.fromHtml(comment.getComment(), Html.FROM_HTML_MODE_LEGACY);
                    tvCommentSnippet.setText(cleanText.toString().trim());
                }
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setView(dialogView);
            AlertDialog dialog = builder.create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }

            if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
            if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
            if (btnDelete != null) {
                btnDelete.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (apiService != null) {
                        apiService.deleteComment(comment.getId(), new ApiService.DeleteCommentCallback() {
                            @Override
                            public void onSuccess() {
                                safeRunOnUiThread(() -> {
                                    CustomToast.showSuccess(context, "Комментарий удален");
                                    removeCommentFromMemoryAndAdapters(comment.getId());
                                });
                            }

                            @Override
                            public void onError(String errorMsg) {
                                safeRunOnUiThread(() -> CustomToast.showWarning(context, errorMsg));
                            }
                        });
                    }
                });
            }

            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing delete comment dialog", e);
        }
    }

    private void removeCommentFromMemoryAndAdapters(long commentId) {
        if (apiService != null) {
            if (currentEpisode != null) {
                apiService.invalidateEpisodeCommentsCache(currentEpisode.getId());
            } else {
                apiService.invalidateAllCommentsCache();
            }
        }
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
        if (commentsAdapter != null) {
            commentsAdapter.removeComment(commentId);
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.removeComment(commentId);
        }
        updateEmptyCommentsState();
    }

    public boolean isOwnComment(CommentsResponse.CommentItem comment) {
        if (comment == null || comment.getUser() == null) return false;
        long commentUserId = comment.getUser().getId();
        String commentUsername = comment.getUser().getUsername();

        try {
            DatabaseManager dbManager = null;
            if (apiService != null && apiService.getDatabaseManager() != null) {
                dbManager = apiService.getDatabaseManager();
            } else if (context != null) {
                dbManager = new DatabaseManager(context);
            }

            if (dbManager != null) {
                TokenEntity token = dbManager.getToken();
                if (token != null) {
                    String savedUserId = token.getUserId();
                    if (savedUserId != null && !savedUserId.trim().isEmpty()) {
                        try {
                            if (Long.parseLong(savedUserId.trim()) == commentUserId) {
                                return true;
                            }
                        } catch (Exception ignored) {}
                        if (savedUserId.trim().equalsIgnoreCase(String.valueOf(commentUserId))) {
                            return true;
                        }
                    }

                    if (token.getAccessToken() != null && !token.getAccessToken().trim().isEmpty()) {
                        String extractedId = ApiService.extractUserIdFromToken(token.getAccessToken());
                        if (extractedId != null && !extractedId.trim().isEmpty()) {
                            try {
                                if (Long.parseLong(extractedId.trim()) == commentUserId) {
                                    return true;
                                }
                            } catch (Exception ignored) {}
                            if (extractedId.trim().equalsIgnoreCase(String.valueOf(commentUserId))) {
                                return true;
                            }
                        }
                    }

                    String savedUsername = token.getUsername();
                    if (savedUsername != null && !savedUsername.trim().isEmpty()
                            && commentUsername != null && !commentUsername.trim().isEmpty()) {
                        if (savedUsername.trim().equalsIgnoreCase(commentUsername.trim())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking if own comment", e);
        }

        return false;
    }

    private void resetPopupItemStyle(TextView textView) {
        if (textView == null) return;
        textView.setBackgroundColor(Color.TRANSPARENT);
        textView.setTextColor(ContextCompat.getColor(context, R.color.accent_text_color));
    }

    private void setPopupItemSelectedStyle(TextView textView) {
        if (textView == null) return;
        textView.setBackgroundResource(R.drawable.bg_sort_popup_item_selected);
        textView.setTextColor(ContextCompat.getColor(context, R.color.sort_popup_item_selected_text));
    }

    private int dpToPx(int dp) {
        if (context == null) return dp;
        if (cachedDensity <= 0f) {
            cachedDensity = context.getResources().getDisplayMetrics().density;
        }
        return (int) (dp * cachedDensity);
    }

    private void setupCommentsRecyclerView() {
        if (commentsRecyclerView == null) return;

        commentsAdapter = new CommentsAdapter();
        commentsAdapter.setCommentActionListener(new CommentsAdapter.CommentActionListener() {
            @Override
            public void onReplyClicked(CommentsResponse.CommentItem comment) {
                Long targetId = comment != null ? comment.getId() : null;
                if (portraitCommentsAdapter != null && portraitCommentsAdapter != commentsAdapter) {
                    portraitCommentsAdapter.setActiveReplyCommentId(targetId);
                }
                cancelMainInputReplyMode();
                keyboardScrollHelper.updateLandscapeMainInputVisibility();
            }

            @Override
            public void onReplyCancelled(CommentsResponse.CommentItem comment) {
                if (portraitCommentsAdapter != null && portraitCommentsAdapter != commentsAdapter) {
                    portraitCommentsAdapter.setActiveReplyCommentId(null);
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
                keyboardScrollHelper.attachInlineReplyInput(inputField, inlineReplyContainer, false);
            }
        });

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
            if (!isPortraitCommentsRequested || (loadedPageResponses.isEmpty() && !isLoadingComments && currentEpisode != null)) {
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

        if (landscapeAdapterNeedsSync && !loadedPageResponses.isEmpty()) {
            landscapeAdapterNeedsSync = false;
            syncCachedPagesIntoAdapter(commentsAdapter);
            hideLoadingOverlay(commentsLoadingOverlay);
            updateEmptyCommentsState();
            return;
        }

        if (!isLoadingComments && !commentsAdapter.hasAnySourceComments() && loadedPageResponses.isEmpty()) {
            loadCommentsPage(1);
        }
    }

    private void syncCachedPagesIntoAdapter(CommentsAdapter targetAdapter) {
        if (targetAdapter == null) return;
        targetAdapter.putParsedContentMap(sharedParsedCache);
        if (loadedPageResponses.isEmpty()) {
            if (!loadedStickyComments.isEmpty()) {
                targetAdapter.setStickyComments(loadedStickyComments);
            }
            return;
        }
        targetAdapter.replacePagesOnRefresh(loadedStickyComments, loadedPageResponses, null, !"asc".equals(commentsSortType));
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

    private Map<Long, CommentHtmlProcessor.ParsedCommentContent> preParseCommentsBatch(
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
            loadedStickyComments.clear();
            loadedPageResponses.clear();

            apiService.fetchStickyComments(episodeId, new ApiService.StickyCommentsCallback() {
                @Override
                public void onStickyCommentsReceived(List<CommentsResponse.CommentItem> stickyComments) {
                    if (stickyComments == null || stickyComments.isEmpty()) {
                        return;
                    }
                    commentsProcessingExecutor.execute(() -> {
                        Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedSticky =
                                preParseCommentsBatch(stickyComments, primaryTextColor, purpleColor);
                        safeRunOnUiThread(() -> {
                            if (gen != requestGeneration || isOfflineMode) return;
                            loadedStickyComments.clear();
                            for (CommentsResponse.CommentItem item : stickyComments) {
                                if (item != null) {
                                    item.setSticky(true);
                                    loadedStickyComments.add(item);
                                }
                            }
                            if (commentsAdapter != null) commentsAdapter.putParsedContentMap(parsedSticky);
                            if (portraitCommentsAdapter != null) portraitCommentsAdapter.putParsedContentMap(parsedSticky);

                            // Если первая страница уже успела отрисоваться — добавим закреплённые сверху;
                            // иначе они войдут единым пакетом вместе с первой страницей без двойной перерисовки!
                            if (!loadedPageResponses.isEmpty()) {
                                if (isPortraitOrientation()) {
                                    if (portraitCommentsAdapter != null) {
                                        portraitCommentsAdapter.setStickyComments(loadedStickyComments);
                                    }
                                    landscapeAdapterNeedsSync = true;
                                } else {
                                    if (isCommentsVisible && !isPanelAnimatingOpen && commentsAdapter != null) {
                                        commentsAdapter.setStickyComments(loadedStickyComments);
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
                            List<CommentsResponse.CommentItem> allItems = new ArrayList<>();
                            if (response != null && response.getData() != null) {
                                if (response.getData().getRoot() != null) {
                                    allItems.addAll(response.getData().getRoot());
                                }
                                if (response.getData().getReplies() != null) {
                                    allItems.addAll(response.getData().getReplies());
                                }
                            }
                            Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedMap =
                                    preParseCommentsBatch(allItems, primaryTextColor, purpleColor);

                            safeRunOnUiThread(() -> {
                                if (gen != requestGeneration) {
                                    return;
                                }
                                isLoadingComments = false;

                                if (commentsAdapter != null) {
                                    commentsAdapter.putParsedContentMap(parsedMap);
                                    commentsAdapter.setLoadingMore(false);
                                }
                                if (portraitCommentsAdapter != null) {
                                    portraitCommentsAdapter.putParsedContentMap(parsedMap);
                                    portraitCommentsAdapter.setLoadingMore(false);
                                }

                                if (isOfflineMode) {
                                    applyOfflineViewsState();
                                    return;
                                }

                                hideLoadingOverlay(commentsLoadingOverlay);
                                hideLoadingOverlay(portraitCommentsLoadingOverlay);
                                if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
                                if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);

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
                                    if (isInitialPage) {
                                        loadedPageResponses.clear();
                                    }
                                    loadedPageResponses.add(response);

                                    boolean portraitNow = isPortraitOrientation();
                                    if (portraitNow) {
                                        if (portraitCommentsAdapter != null) {
                                            if (isInitialPage) {
                                                portraitCommentsAdapter.setInitialComments(loadedStickyComments, response);
                                            } else {
                                                portraitCommentsAdapter.appendCommentsPage(response);
                                            }
                                        }
                                        landscapeAdapterNeedsSync = true;
                                    } else {
                                        if (isCommentsVisible && !isPanelAnimatingOpen && commentsAdapter != null) {
                                            if (isInitialPage) {
                                                commentsAdapter.setInitialComments(loadedStickyComments, response);
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
                            if (commentsAdapter != null) commentsAdapter.setLoadingMore(false);
                            if (portraitCommentsAdapter != null) portraitCommentsAdapter.setLoadingMore(false);

                            boolean hasLoadedData = !loadedPageResponses.isEmpty()
                                    || (portraitCommentsAdapter != null && portraitCommentsAdapter.hasAnySourceComments())
                                    || (commentsAdapter != null && commentsAdapter.hasAnySourceComments());

                            if (!hasLoadedData) {
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
                            }

                            if (dataCallback != null) {
                                dataCallback.onCommentsError(error);
                            }
                        });
                    }
                });
    }

    private void updateEmptyCommentsState() {
        boolean hasComments = !loadedStickyComments.isEmpty();
        if (!hasComments) {
            for (CommentsResponse resp : loadedPageResponses) {
                if (resp != null && resp.getData() != null) {
                    if ((resp.getData().getRoot() != null && !resp.getData().getRoot().isEmpty())
                            || (resp.getData().getReplies() != null && !resp.getData().getReplies().isEmpty())) {
                        hasComments = true;
                        break;
                    }
                }
            }
        }
        if (!hasComments) {
            hasComments = (portraitCommentsAdapter != null && portraitCommentsAdapter.hasAnySourceComments())
                    || (commentsAdapter != null && commentsAdapter.hasAnySourceComments());
        }

        if (!isOfflineMode && !isCommentsDisabledInPlayer() && !isLoadingComments) {
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
                && loadedPageResponses.isEmpty() && !isLoadingComments) {
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
        resetAllSubmittingUiStates();
        isPortraitCommentsRequested = false;
        if (reloadIfVisible) {
            isPlayerReadyForComments = false;
        }
        landscapeAdapterNeedsSync = false;
        portraitAdapterNeedsSync = false;
        loadedStickyComments.clear();
        loadedPageResponses.clear();
        sharedParsedCache.clear();

        if (commentsAdapter != null) {
            commentsAdapter.clearAll();
        }
        if (portraitCommentsAdapter != null) {
            portraitCommentsAdapter.clearAll();
        }

        hideLoadingOverlay(commentsLoadingOverlay);
        hideLoadingOverlay(portraitCommentsLoadingOverlay);
        if (emptyCommentsText != null) emptyCommentsText.setVisibility(View.GONE);
        if (portraitEmptyCommentsText != null) portraitEmptyCommentsText.setVisibility(View.GONE);
        if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
        if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);

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
            landscapeAdapterNeedsSync = false;
            portraitAdapterNeedsSync = false;
            loadedStickyComments.clear();
            loadedPageResponses.clear();

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
        }

        if (episode == null) return;

        if (shouldLoadComments() && loadedPageResponses.isEmpty() && !isLoadingComments) {
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
            this.portraitCommentsAdapter.setCommentActionListener(new CommentsAdapter.CommentActionListener() {
                @Override
                public void onReplyClicked(CommentsResponse.CommentItem comment) {
                    Long targetId = comment != null ? comment.getId() : null;
                    if (commentsAdapter != null && commentsAdapter != portraitCommentsAdapter) {
                        commentsAdapter.setActiveReplyCommentId(targetId);
                    }
                    cancelMainInputReplyMode();
                    keyboardScrollHelper.updateLandscapeMainInputVisibility();
                }

                @Override
                public void onReplyCancelled(CommentsResponse.CommentItem comment) {
                    if (commentsAdapter != null && commentsAdapter != portraitCommentsAdapter) {
                        commentsAdapter.setActiveReplyCommentId(null);
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
                    keyboardScrollHelper.attachInlineReplyInput(inputField, inlineReplyContainer, true);
                }
            });
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

            if (portraitAdapterNeedsSync && !loadedPageResponses.isEmpty()) {
                portraitAdapterNeedsSync = false;
                syncCachedPagesIntoAdapter(this.portraitCommentsAdapter);
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

            if (portraitCommentInputField != null) {
                CommentFormattingHelper.attachFormattingTextWatcher(portraitCommentInputField);
            }

            View btnPBold = act.findViewById(R.id.btnPortraitFormatBold);
            View btnPItalic = act.findViewById(R.id.btnPortraitFormatItalic);
            View btnPUnderline = act.findViewById(R.id.btnPortraitFormatUnderline);
            View btnPStrike = act.findViewById(R.id.btnPortraitFormatStrike);
            View btnPSpoiler = act.findViewById(R.id.btnPortraitFormatSpoiler);
            View btnPQuote = act.findViewById(R.id.btnPortraitFormatQuote);

            if (btnPBold != null) btnPBold.setOnClickListener(v -> CommentFormattingHelper.applyFormat(portraitCommentInputField, CommentFormattingHelper.FormatType.BOLD));
            if (btnPItalic != null) btnPItalic.setOnClickListener(v -> CommentFormattingHelper.applyFormat(portraitCommentInputField, CommentFormattingHelper.FormatType.ITALIC));
            if (btnPUnderline != null) btnPUnderline.setOnClickListener(v -> CommentFormattingHelper.applyFormat(portraitCommentInputField, CommentFormattingHelper.FormatType.UNDERLINE));
            if (btnPStrike != null) btnPStrike.setOnClickListener(v -> CommentFormattingHelper.applyFormat(portraitCommentInputField, CommentFormattingHelper.FormatType.STRIKE));
            if (btnPSpoiler != null) btnPSpoiler.setOnClickListener(v -> CommentFormattingHelper.applyFormat(portraitCommentInputField, CommentFormattingHelper.FormatType.SPOILER));
            if (btnPQuote != null) btnPQuote.setOnClickListener(v -> CommentFormattingHelper.applyFormat(portraitCommentInputField, CommentFormattingHelper.FormatType.QUOTE));

            if (btnPortraitSendComment != null) {
                btnPortraitSendComment.setOnClickListener(v -> sendCommentFromInput(portraitCommentInputField));
            }
            if (btnPortraitCancelReply != null) {
                btnPortraitCancelReply.setOnClickListener(v -> {
                    if (!isSubmittingComment) {
                        cancelReplyMode();
                    }
                });
            }
            if (portraitCommentInputField != null) {
                portraitCommentInputField.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEND) {
                        sendCommentFromInput(portraitCommentInputField);
                        return true;
                    }
                    return false;
                });
            }

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
            if (commentsLoadingOverlay != null) commentsLoadingOverlay.setVisibility(View.GONE);
            if (portraitCommentsLoadingOverlay != null) portraitCommentsLoadingOverlay.setVisibility(View.GONE);
            if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
            if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);
            if (portraitOfflineCommentsNotice != null) portraitOfflineCommentsNotice.setVisibility(View.VISIBLE);
            if (portraitCommentsHeader != null) portraitCommentsHeader.setVisibility(View.GONE);
            if (portraitCommentInputContainer != null) portraitCommentInputContainer.setVisibility(View.GONE);
            if (portraitCommentsRecyclerView != null) portraitCommentsRecyclerView.setVisibility(View.GONE);
            if (portraitEmptyCommentsText != null) portraitEmptyCommentsText.setVisibility(View.GONE);
            if (commentsRecyclerView != null) commentsRecyclerView.setVisibility(View.GONE);
            if (commentsPanel != null) {
                View panelInputContainer = commentsPanel.findViewById(R.id.commentInputContainer);
                if (panelInputContainer != null) panelInputContainer.setVisibility(View.GONE);
                View sortBtn = commentsPanel.findViewById(R.id.btnSortComments);
                if (sortBtn != null) sortBtn.setVisibility(View.GONE);
                View rulesBtn = commentsPanel.findViewById(R.id.btnCommentRules);
                if (rulesBtn != null) rulesBtn.setVisibility(View.GONE);
            }
            if (emptyCommentsText != null) {
                emptyCommentsText.setText("Недоступно в офлайн режиме");
                emptyCommentsText.setVisibility(View.VISIBLE);
            }
            isLoadingComments = false;
        } else {
            if (portraitOfflineCommentsNotice != null) portraitOfflineCommentsNotice.setVisibility(View.GONE);
            if (portraitCommentsHeader != null) portraitCommentsHeader.setVisibility(View.VISIBLE);
            if (portraitCommentInputContainer != null) portraitCommentInputContainer.setVisibility(View.VISIBLE);
            if (portraitCommentsRecyclerView != null) portraitCommentsRecyclerView.setVisibility(View.VISIBLE);
            if (commentsRecyclerView != null) commentsRecyclerView.setVisibility(View.VISIBLE);
            if (commentsPanel != null) {
                View panelInputContainer = commentsPanel.findViewById(R.id.commentInputContainer);
                if (panelInputContainer != null) panelInputContainer.setVisibility(View.VISIBLE);
                View sortBtn = commentsPanel.findViewById(R.id.btnSortComments);
                if (sortBtn != null) sortBtn.setVisibility(View.VISIBLE);
                View rulesBtn = commentsPanel.findViewById(R.id.btnCommentRules);
                if (rulesBtn != null) rulesBtn.setVisibility(View.VISIBLE);
            }
            if (emptyCommentsText != null) {
                emptyCommentsText.setText("Комментариев пока нет");
            }
            updateEmptyCommentsState();
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
        if (loadedPageResponses.isEmpty() && !isLoadingComments) {
            isPortraitCommentsRequested = true;
            loadCommentsPage(1);
        } else {
            if (isPortraitOrientation() && portraitCommentsAdapter != null && portraitAdapterNeedsSync) {
                portraitAdapterNeedsSync = false;
                syncCachedPagesIntoAdapter(portraitCommentsAdapter);
            } else if (!isPortraitOrientation() && commentsAdapter != null && landscapeAdapterNeedsSync) {
                landscapeAdapterNeedsSync = false;
                syncCachedPagesIntoAdapter(commentsAdapter);
            }
            updateEmptyCommentsState();
        }
    }

    public void applyCommentsDisabledState(boolean disabled) {
        safeRunOnUiThread(() -> {
            if (disabled) {
                mainHandler.removeCallbacks(deferredPortraitLoadRunnable);
                if (portraitCommentsDisabledNotice != null) portraitCommentsDisabledNotice.setVisibility(View.VISIBLE);
                if (portraitCommentsHeader != null) portraitCommentsHeader.setVisibility(View.GONE);
                if (portraitCommentInputContainer != null) portraitCommentInputContainer.setVisibility(View.GONE);
                if (portraitCommentsRecyclerView != null) portraitCommentsRecyclerView.setVisibility(View.GONE);
                if (portraitEmptyCommentsText != null) portraitEmptyCommentsText.setVisibility(View.GONE);
                if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);
                if (portraitCommentsLoadingOverlay != null) portraitCommentsLoadingOverlay.setVisibility(View.GONE);

                if (landscapeCommentsDisabledNotice != null) landscapeCommentsDisabledNotice.setVisibility(View.VISIBLE);
                if (commentsRecyclerView != null) commentsRecyclerView.setVisibility(View.GONE);
                if (emptyCommentsText != null) emptyCommentsText.setVisibility(View.GONE);
                if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
                if (commentsLoadingOverlay != null) commentsLoadingOverlay.setVisibility(View.GONE);
                if (commentsPanel != null) {
                    View panelInput = commentsPanel.findViewById(R.id.commentInputContainer);
                    if (panelInput != null) panelInput.setVisibility(View.GONE);
                    View sortBtn = commentsPanel.findViewById(R.id.btnSortComments);
                    if (sortBtn != null) sortBtn.setVisibility(View.GONE);
                    View rulesBtn = commentsPanel.findViewById(R.id.btnCommentRules);
                    if (rulesBtn != null) rulesBtn.setVisibility(View.GONE);
                }
                updateCommentsButtonVisibility(false);
            } else {
                if (portraitCommentsDisabledNotice != null) portraitCommentsDisabledNotice.setVisibility(View.GONE);
                if (landscapeCommentsDisabledNotice != null) landscapeCommentsDisabledNotice.setVisibility(View.GONE);
                if (!isOfflineMode) {
                    if (portraitCommentsHeader != null) portraitCommentsHeader.setVisibility(View.VISIBLE);
                    if (portraitCommentInputContainer != null) portraitCommentInputContainer.setVisibility(View.VISIBLE);
                    if (portraitCommentsRecyclerView != null) portraitCommentsRecyclerView.setVisibility(View.VISIBLE);
                    if (commentsRecyclerView != null) commentsRecyclerView.setVisibility(View.VISIBLE);
                    if (commentsPanel != null) {
                        View panelInput = commentsPanel.findViewById(R.id.commentInputContainer);
                        if (panelInput != null) panelInput.setVisibility(View.VISIBLE);
                        View sortBtn = commentsPanel.findViewById(R.id.btnSortComments);
                        if (sortBtn != null) sortBtn.setVisibility(View.VISIBLE);
                        View rulesBtn = commentsPanel.findViewById(R.id.btnCommentRules);
                        if (rulesBtn != null) rulesBtn.setVisibility(View.VISIBLE);
                    }
                    updateEmptyCommentsState();
                }
                updateCommentsButtonVisibility(true);
            }
        });
    }

    private void triggerInitialLoadIfNeeded() {
        if (isOfflineMode || isCommentsDisabledInPlayer() || currentEpisode == null) return;
        if (portraitAdapterNeedsSync && portraitCommentsAdapter != null && !loadedPageResponses.isEmpty()) {
            portraitAdapterNeedsSync = false;
            syncCachedPagesIntoAdapter(portraitCommentsAdapter);
            updateEmptyCommentsState();
            return;
        }
        if (!isLoadingComments && loadedPageResponses.isEmpty()
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

        if (portraitAdapterNeedsSync && portraitCommentsAdapter != null && !loadedPageResponses.isEmpty()) {
            portraitAdapterNeedsSync = false;
            syncCachedPagesIntoAdapter(portraitCommentsAdapter);
            updateEmptyCommentsState();
            return;
        }

        if (currentEpisode != null && !isLoadingComments && loadedPageResponses.isEmpty()
                && (portraitCommentsAdapter == null || !portraitCommentsAdapter.hasAnySourceComments())) {
            scheduleDeferredPortraitLoad();
        }
    }

    public void loadNextCommentsPageIfAvailable() {
        if (isOfflineMode || isCommentsDisabledInPlayer()) return;
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

        if (replyBar != null && tvReplyToText != null) {
            tvReplyToText.setText(replyText);
            replyBar.setVisibility(View.VISIBLE);
        }
        if (portraitReplyBar != null && tvPortraitReplyToText != null) {
            tvPortraitReplyToText.setText(replyText);
            portraitReplyBar.setVisibility(View.VISIBLE);
        }
    }

    public void startEditMode(CommentsResponse.CommentItem comment) {
        if (comment == null) return;
        cancelReplyMode();
        editingComment = comment;

        String cleanText = "";
        if (comment.getComment() != null) {
            cleanText = Html.fromHtml(comment.getComment(), Html.FROM_HTML_MODE_LEGACY).toString().trim();
        }

        String titleText = "Редактирование комментария";

        if (replyBar != null && tvReplyToText != null) {
            tvReplyToText.setText(titleText);
            replyBar.setVisibility(View.VISIBLE);
        }
        if (portraitReplyBar != null && tvPortraitReplyToText != null) {
            tvPortraitReplyToText.setText(titleText);
            portraitReplyBar.setVisibility(View.VISIBLE);
        }

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
                            itemToApply.setComment(buildFallbackHtmlFromBBCode(text));
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

        boolean prependRoot = !"asc".equals(commentsSortType);
        if (mutatedComment != null && mutatedComment.getId() > 0) {
            normalizeMutatedComment(mutatedComment, episodeId, submittedText, parentId, rootId, level);

            sharedParsedCache.remove(mutatedComment.getId());
            int primaryTextColor = CommentHtmlProcessor.resolvePrimaryTextColor(context);
            int purpleColor = context != null ? ContextCompat.getColor(context, R.color.purple_primary) : 0xFF8B5CF6;
            CommentHtmlProcessor.ParsedCommentContent parsed = CommentHtmlProcessor.parseHtmlOffThread(
                    mutatedComment.getComment(), primaryTextColor, purpleColor);
            sharedParsedCache.put(mutatedComment.getId(), parsed);

            Map<Long, CommentHtmlProcessor.ParsedCommentContent> singleMap = new HashMap<>();
            singleMap.put(mutatedComment.getId(), parsed);

            upsertIntoLoadedPageResponses(mutatedComment, prependRoot);

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

        int targetPage = findPageForMutationRefresh(mutatedComment, parentId, rootId, isEdit);
        refreshCommentsPageFromServer(episodeId, targetPage, mutatedComment, isEdit, true);
    }

    private void normalizeMutatedComment(CommentsResponse.CommentItem item,
                                         long episodeId,
                                         String submittedText,
                                         Long parentId,
                                         Long rootId,
                                         int level) {
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
            populateFallbackUserIfMissing(item);
        }
    }

    private void populateFallbackUserIfMissing(CommentsResponse.CommentItem item) {
        try {
            DatabaseManager db = (apiService != null && apiService.getDatabaseManager() != null)
                    ? apiService.getDatabaseManager()
                    : (context != null ? new DatabaseManager(context) : null);
            if (db == null) return;
            TokenEntity token = db.getToken();
            if (token == null) return;

            CommentsResponse.User user = new CommentsResponse.User();
            if (token.getUserId() != null && !token.getUserId().trim().isEmpty()) {
                try {
                    user.setId(Long.parseLong(token.getUserId().trim()));
                } catch (Exception ignored) {}
            }
            if (token.getUsername() != null && !token.getUsername().trim().isEmpty()) {
                user.setUsername(token.getUsername().trim());
            }
            if (user.getUsername() != null || user.getId() > 0) {
                item.setUser(user);
            }
        } catch (Exception ignored) {}
    }

    private static String buildFallbackHtmlFromBBCode(String rawText) {
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

    private int findPageForMutationRefresh(CommentsResponse.CommentItem mutatedComment,
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

    private static boolean responseContainsComment(CommentsResponse resp, long commentId) {
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

    private void upsertIntoLoadedPageResponses(CommentsResponse.CommentItem item, boolean prependRoot) {
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
            if (targetResp.getData() == null) {
                CommentsResponse.Data d = new CommentsResponse.Data();
                d.setRoot(new ArrayList<>());
                d.setReplies(new ArrayList<>());
                targetResp.setData(d);
            }
            if (targetResp.getData().getReplies() == null) {
                targetResp.getData().setReplies(new ArrayList<>());
            }
            targetResp.getData().getReplies().add(item);
        } else {
            CommentsResponse targetResp = prependRoot
                    ? loadedPageResponses.get(0)
                    : loadedPageResponses.get(loadedPageResponses.size() - 1);
            if (targetResp.getData() == null) {
                CommentsResponse.Data d = new CommentsResponse.Data();
                d.setRoot(new ArrayList<>());
                d.setReplies(new ArrayList<>());
                targetResp.setData(d);
            }
            if (targetResp.getData().getRoot() == null) {
                targetResp.getData().setRoot(new ArrayList<>());
            }
            if (prependRoot) {
                targetResp.getData().getRoot().add(0, item);
            } else {
                targetResp.getData().getRoot().add(item);
            }
        }
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
                            List<CommentsResponse.CommentItem> allItems = new ArrayList<>();
                            if (response != null && response.getData() != null) {
                                if (response.getData().getRoot() != null) {
                                    allItems.addAll(response.getData().getRoot());
                                }
                                if (response.getData().getReplies() != null) {
                                    allItems.addAll(response.getData().getReplies());
                                }
                            }
                            for (CommentsResponse.CommentItem serverItem : allItems) {
                                if (serverItem != null) {
                                    sharedParsedCache.remove(serverItem.getId());
                                }
                            }
                            Map<Long, CommentHtmlProcessor.ParsedCommentContent> parsedMap =
                                    preParseCommentsBatch(allItems, primaryTextColor, purpleColor);

                            safeRunOnUiThread(() -> {
                                if (gen != requestGeneration) {
                                    return;
                                }
                                isLoadingComments = false;
                                hideLoadingOverlay(commentsLoadingOverlay);
                                hideLoadingOverlay(portraitCommentsLoadingOverlay);
                                if (commentsErrorLayout != null) commentsErrorLayout.setVisibility(View.GONE);
                                if (portraitCommentsErrorLayout != null) portraitCommentsErrorLayout.setVisibility(View.GONE);

                                if (commentsAdapter != null) {
                                    commentsAdapter.putParsedContentMap(parsedMap);
                                    commentsAdapter.setLoadingMore(false);
                                }
                                if (portraitCommentsAdapter != null) {
                                    portraitCommentsAdapter.putParsedContentMap(parsedMap);
                                    portraitCommentsAdapter.setLoadingMore(false);
                                }

                                if (response != null) {
                                    if (targetPage == 1 && loadedPageResponses.size() <= 1) {
                                        loadedPageResponses.clear();
                                        loadedPageResponses.add(response);
                                        if (response.getMeta() != null) {
                                            commentsHasNextPage = response.getMeta().isHas_next_page();
                                            commentsCurrentPage = response.getMeta().getPage() > 0 ? response.getMeta().getPage() : 1;
                                        } else {
                                            commentsCurrentPage = 1;
                                            commentsHasNextPage = false;
                                        }
                                    } else if (targetPage >= 1 && targetPage <= loadedPageResponses.size()) {
                                        loadedPageResponses.set(targetPage - 1, response);
                                        if (targetPage == loadedPageResponses.size() && response.getMeta() != null) {
                                            commentsHasNextPage = response.getMeta().isHas_next_page();
                                            commentsCurrentPage = Math.max(commentsCurrentPage, targetPage);
                                        }
                                    } else {
                                        loadedPageResponses.clear();
                                        loadedPageResponses.add(response);
                                        commentsCurrentPage = targetPage;
                                        commentsHasNextPage = response.getMeta() != null && response.getMeta().isHas_next_page();
                                    }

                                    boolean prependRoot = !"asc".equals(commentsSortType);
                                    boolean foundInServer = mutatedComment != null && responseContainsComment(response, mutatedComment.getId());
                                    if (!isEdit && mutatedComment != null && mutatedComment.getId() > 0 && !foundInServer) {
                                        upsertIntoLoadedPageResponses(mutatedComment, prependRoot);
                                    }

                                    CommentsResponse.CommentItem ensureItem = (!isEdit && mutatedComment != null) ? mutatedComment : null;
                                    boolean portraitNow = isPortraitOrientation();
                                    if (portraitNow) {
                                        if (portraitCommentsAdapter != null) {
                                            portraitCommentsAdapter.replacePagesOnRefresh(loadedStickyComments, loadedPageResponses, ensureItem, prependRoot);
                                        }
                                        landscapeAdapterNeedsSync = true;
                                    } else {
                                        if (isCommentsVisible && !isPanelAnimatingOpen && commentsAdapter != null) {
                                            commentsAdapter.replacePagesOnRefresh(loadedStickyComments, loadedPageResponses, ensureItem, prependRoot);
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
                            if (commentsAdapter != null) commentsAdapter.setLoadingMore(false);
                            if (portraitCommentsAdapter != null) portraitCommentsAdapter.setLoadingMore(false);
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
        if (comment == null) return;
        apiService.voteComment(comment.getId(), targetVote, new ApiService.VoteCommentCallback() {
            @Override
            public void onSuccess(int newVoteValue, CommentsResponse.Votes updatedVotes) {
                safeRunOnUiThread(() -> {
                    if (updatedVotes != null) {
                        comment.setVotes(updatedVotes);
                    } else {
                        if (comment.getVotes() == null) {
                            comment.setVotes(new CommentsResponse.Votes());
                        }
                        Integer oldVote = null;
                        if (comment.getVotes().getUser() != null) {
                            try {
                                if (comment.getVotes().getUser() instanceof Number) {
                                    oldVote = ((Number) comment.getVotes().getUser()).intValue();
                                } else if (comment.getVotes().getUser() instanceof Boolean) {
                                    oldVote = ((Boolean) comment.getVotes().getUser()) ? 1 : 0;
                                } else {
                                    String str = comment.getVotes().getUser().toString().trim();
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
        if (context == null) return;
        try {
            RulesBottomSheet rulesBottomSheet = new RulesBottomSheet(context, apiService);
            rulesBottomSheet.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing rules bottom sheet", e);
            CustomToast.showInfo(context, "Правила комментариев");
        }
    }

    public void setSettingsCallback(CommentsSettingsCallback callback) {
        this.settingsCallback = callback;
    }

    public void showSettingsDialog() {
        if (context == null) return;
        try {
            CommentSettingsBottomSheet bs =
                    new CommentSettingsBottomSheet(context, new CommentSettingsBottomSheet.OnCommentSettingsListener() {
                        @Override
                        public void onCommentsInPlayerToggled(boolean disabled) {
                            applyCommentsDisabledState(disabled);
                            if (disabled) {
                                if (isCommentsVisible) {
                                    hideCommentsPanel();
                                }
                                updateCommentsButtonVisibility(false);
                            } else {
                                updateCommentsButtonVisibility(true);
                                if (loadedPageResponses.isEmpty() && !isLoadingComments) {
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
                        public void onRepliesLimitChanged(int limit) {
                            updateRepliesLimit(limit);
                            if (settingsCallback != null) {
                                settingsCallback.onRepliesLimitChanged(limit);
                            }
                        }
                    });
            bs.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing comments settings bottom sheet", e);
        }
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

        loadedStickyComments.clear();
        loadedPageResponses.clear();
        sharedParsedCache.clear();

        if (emptyCommentsText != null) {
            emptyCommentsText.setVisibility(View.GONE);
        }

        commentsCurrentPage = 0;
        commentsHasNextPage = false;
        isLoadingComments = false;
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
