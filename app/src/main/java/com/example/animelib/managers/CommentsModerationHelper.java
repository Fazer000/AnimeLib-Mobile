package com.example.animelib.managers;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.models.CommentsResponse;
import com.example.animelib.settings.CommentSettingsBottomSheet;
import com.example.animelib.ui.RulesBottomSheet;
import com.example.animelib.util.CustomToast;

/**
 * Помощник модерации, попапов действий, сортировки и диалогов настроек/правил комментариев.
 */
public class CommentsModerationHelper {

    public interface Host {
        void onSortSelected(String sortType);
        void onEditCommentRequested(CommentsResponse.CommentItem comment);
        void onCommentDeleted(long commentId);
        void onUserIgnoredSuccess();
        void onCommentsInPlayerToggledFromSettings(boolean disabled);
        void onRepliesLimitChangedFromSettings(int limit);
        void runOnUiThread(Runnable runnable);
    }

    private static final String TAG = "CommentsModeration";

    private final Context context;
    private final ApiService apiService;
    private final Host host;
    private float cachedDensity = 0f;

    public CommentsModerationHelper(Context context, ApiService apiService, Host host) {
        this.context = context;
        this.apiService = apiService;
        this.host = host;
    }

    public void showSortOptionsDialog(View anchorView, String currentSortType) {
        if (!canShowAnchoredPopup(anchorView)) return;

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

            resetPopupItemStyle(itemNew);
            resetPopupItemStyle(itemOld);
            resetPopupItemStyle(itemPopular);

            if ("asc".equals(currentSortType)) {
                setPopupItemSelectedStyle(itemOld);
            } else if ("votes_up".equals(currentSortType)) {
                setPopupItemSelectedStyle(itemPopular);
            } else {
                setPopupItemSelectedStyle(itemNew);
            }

            bindSortItem(itemNew, popupWindow, "desc");
            bindSortItem(itemOld, popupWindow, "asc");
            bindSortItem(itemPopular, popupWindow, "votes_up");

            popupWindow.showAsDropDown(anchorView, 0, dpToPx(6));
        } catch (Exception e) {
            Log.e(TAG, "Error showing sort options popup", e);
        }
    }

    public void showCommentActionsPopup(View anchorView, CommentsResponse.CommentItem comment) {
        if (comment == null || !canShowAnchoredPopup(anchorView)) return;

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
                    dismissPopupSafely(popupWindow);
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
                    dismissPopupSafely(popupWindow);
                    if (host != null) {
                        host.onEditCommentRequested(comment);
                    }
                });
            }

            if (itemDelete != null) {
                itemDelete.setVisibility(isOwn ? View.VISIBLE : View.GONE);
                itemDelete.setOnClickListener(v -> {
                    dismissPopupSafely(popupWindow);
                    confirmAndDeleteComment(comment);
                });
            }

            if (itemReport != null) {
                itemReport.setOnClickListener(v -> {
                    dismissPopupSafely(popupWindow);
                    CustomToast.showSuccess(context, "Жалоба отправлена");
                });
            }

            if (itemIgnore != null) {
                boolean canIgnore = isAuth && !isOwn && comment.getUser() != null;
                itemIgnore.setVisibility(canIgnore ? View.VISIBLE : View.GONE);
                itemIgnore.setOnClickListener(v -> {
                    dismissPopupSafely(popupWindow);
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

            AlertDialog dialog = createTransparentDialog(dialogView);

            if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
            if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

            if (btnConfirm != null) {
                btnConfirm.setOnClickListener(v -> {
                    String commentText = etComment != null && etComment.getText() != null
                            ? etComment.getText().toString().trim() : "";

                    long targetUserId = comment.getUser().getId();
                    long sourceableId = resolveCurrentUserId();

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
                                runOnHostUiThread(() -> {
                                    CustomToast.showSuccess(context, "Пользователь добавлен в игнор-лист");
                                    if (apiService != null) {
                                        apiService.invalidateAllCommentsCache();
                                    }
                                    if (host != null) {
                                        host.onUserIgnoredSuccess();
                                    }
                                });
                            }

                            @Override
                            public void onError(String errorMsg) {
                                runOnHostUiThread(() -> CustomToast.showWarning(context, errorMsg));
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

            AlertDialog dialog = createTransparentDialog(dialogView);

            if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
            if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
            if (btnDelete != null) {
                btnDelete.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (apiService != null) {
                        apiService.deleteComment(comment.getId(), new ApiService.DeleteCommentCallback() {
                            @Override
                            public void onSuccess() {
                                runOnHostUiThread(() -> {
                                    CustomToast.showSuccess(context, "Комментарий удален");
                                    if (host != null) {
                                        host.onCommentDeleted(comment.getId());
                                    }
                                });
                            }

                            @Override
                            public void onError(String errorMsg) {
                                runOnHostUiThread(() -> CustomToast.showWarning(context, errorMsg));
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

    public boolean isOwnComment(CommentsResponse.CommentItem comment) {
        if (comment == null || comment.getUser() == null) return false;
        long commentUserId = comment.getUser().getId();
        String commentUsername = comment.getUser().getUsername();

        try {
            DatabaseManager dbManager = resolveDatabaseManager();
            if (dbManager != null) {
                TokenEntity token = dbManager.getToken();
                if (token != null) {
                    if (matchesUserIdString(token.getUserId(), commentUserId)) {
                        return true;
                    }
                    if (token.getAccessToken() != null && !token.getAccessToken().trim().isEmpty()) {
                        String extractedId = ApiService.extractUserIdFromToken(token.getAccessToken());
                        if (matchesUserIdString(extractedId, commentUserId)) {
                            return true;
                        }
                    }
                    String savedUsername = token.getUsername();
                    if (savedUsername != null && !savedUsername.trim().isEmpty()
                            && commentUsername != null && !commentUsername.trim().isEmpty()
                            && savedUsername.trim().equalsIgnoreCase(commentUsername.trim())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking if own comment", e);
        }

        return false;
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

    public void showSettingsDialog() {
        if (context == null) return;
        try {
            CommentSettingsBottomSheet bs =
                    new CommentSettingsBottomSheet(context, new CommentSettingsBottomSheet.OnCommentSettingsListener() {
                        @Override
                        public void onCommentsInPlayerToggled(boolean disabled) {
                            if (host != null) {
                                host.onCommentsInPlayerToggledFromSettings(disabled);
                            }
                        }

                        @Override
                        public void onRepliesLimitChanged(int limit) {
                            if (host != null) {
                                host.onRepliesLimitChangedFromSettings(limit);
                            }
                        }
                    });
            bs.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing comments settings bottom sheet", e);
        }
    }

    public DatabaseManager resolveDatabaseManager() {
        if (apiService != null && apiService.getDatabaseManager() != null) {
            return apiService.getDatabaseManager();
        }
        return context != null ? DatabaseManager.getInstance(context) : null;
    }

    private long resolveCurrentUserId() {
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
                DatabaseManager db = resolveDatabaseManager();
                if (db != null && db.getToken() != null) {
                    String uId = db.getToken().getUserId();
                    if (uId != null && !uId.trim().isEmpty()) {
                        sourceableId = Long.parseLong(uId.trim());
                    }
                }
            } catch (Exception ignored) {}
        }
        return sourceableId;
    }

    private static boolean matchesUserIdString(String candidate, long targetUserId) {
        if (candidate == null) return false;
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) return false;
        try {
            if (Long.parseLong(trimmed) == targetUserId) {
                return true;
            }
        } catch (Exception ignored) {}
        return trimmed.equalsIgnoreCase(String.valueOf(targetUserId));
    }

    private boolean canShowAnchoredPopup(View anchorView) {
        if (anchorView == null || context == null || !anchorView.isAttachedToWindow()) {
            return false;
        }
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            return !activity.isFinishing() && !activity.isDestroyed();
        }
        return true;
    }

    private AlertDialog createTransparentDialog(View dialogView) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    private void bindSortItem(TextView item, PopupWindow popupWindow, String sortType) {
        if (item == null) return;
        item.setOnClickListener(v -> {
            dismissPopupSafely(popupWindow);
            if (host != null) {
                host.onSortSelected(sortType);
            }
        });
    }

    private static void dismissPopupSafely(PopupWindow popupWindow) {
        try {
            popupWindow.dismiss();
        } catch (Exception ignored) {}
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

    private void runOnHostUiThread(Runnable runnable) {
        if (host != null) {
            host.runOnUiThread(runnable);
        } else {
            runnable.run();
        }
    }

    private int dpToPx(int dp) {
        if (context == null) return dp;
        if (cachedDensity <= 0f) {
            cachedDensity = context.getResources().getDisplayMetrics().density;
        }
        return (int) (dp * cachedDensity);
    }
}
