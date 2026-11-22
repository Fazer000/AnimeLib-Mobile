package com.example.animelib.ui;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.animelib.R;

/**
 * Компактная кастомная числовая клавиатура для поиска серий в ландшафтном режиме плеера.
 */
public class LandscapeEpisodeNumpadView {

    public interface Listener {
        void onDigitPressed(char digit);
        void onBackspacePressed();
        void onClearPressed();
        void onConfirmPressed();
        void onDismissRequested();
    }

    private final View rootView;
    private final TextView tvPreview;
    private final TextView tvRange;
    private final View keyBackspace;
    private final View keyConfirm;
    private final ImageButton btnClose;
    @Nullable
    private Listener listener;
    private boolean isVisible = false;
    private boolean isHiding = false;

    public LandscapeEpisodeNumpadView(@NonNull View rootView) {
        this.rootView = rootView;
        this.tvPreview = rootView.findViewById(R.id.tvNumpadPreview);
        this.tvRange = rootView.findViewById(R.id.tvNumpadRange);
        this.keyBackspace = rootView.findViewById(R.id.numpadKeyBackspace);
        this.keyConfirm = rootView.findViewById(R.id.numpadKeyConfirm);
        this.btnClose = rootView.findViewById(R.id.btnNumpadClose);
        setupKeys();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    private void setupKeys() {
        int[] digitIds = {
                R.id.numpadKey0, R.id.numpadKey1, R.id.numpadKey2,
                R.id.numpadKey3, R.id.numpadKey4, R.id.numpadKey5,
                R.id.numpadKey6, R.id.numpadKey7, R.id.numpadKey8,
                R.id.numpadKey9
        };

        for (int i = 0; i < digitIds.length; i++) {
            final char digit = (char) ('0' + i);
            View key = rootView.findViewById(digitIds[i]);
            if (key != null) {
                key.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (listener != null) {
                        listener.onDigitPressed(digit);
                    }
                });
            }
        }

        if (keyBackspace != null) {
            keyBackspace.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) {
                    listener.onBackspacePressed();
                }
            });
            keyBackspace.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (listener != null) {
                    listener.onClearPressed();
                }
                return true;
            });
        }

        if (keyConfirm != null) {
            keyConfirm.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) {
                    listener.onConfirmPressed();
                }
            });
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) {
                    listener.onDismissRequested();
                }
            });
        }
    }

    public boolean isVisible() {
        return isVisible;
    }

    public boolean isPointInside(float rawX, float rawY) {
        if (!isVisible || rootView.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        rootView.getLocationOnScreen(location);
        float left = location[0];
        float top = location[1];
        float right = left + rootView.getWidth() * rootView.getScaleX();
        float bottom = top + rootView.getHeight() * rootView.getScaleY();
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
    }

    public void show() {
        if (isVisible && rootView.getVisibility() == View.VISIBLE && !isHiding) {
            return;
        }
        isVisible = true;
        isHiding = false;
        rootView.animate().setListener(null).cancel();
        rootView.setVisibility(View.VISIBLE);
        rootView.setAlpha(0f);
        rootView.setScaleX(0.94f);
        rootView.setScaleY(0.94f);
        rootView.setTranslationY(dpToPx(rootView.getContext(), 14f));
        rootView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(160L)
                .setInterpolator(new DecelerateInterpolator())
                .withLayer()
                .start();
    }

    public void hide(boolean immediate) {
        if (!isVisible && (rootView.getVisibility() == View.GONE || (!immediate && isHiding))) {
            return;
        }
        isVisible = false;
        rootView.animate().setListener(null).cancel();
        if (immediate) {
            isHiding = false;
            rootView.setAlpha(0f);
            rootView.setScaleX(1f);
            rootView.setScaleY(1f);
            rootView.setTranslationY(0f);
            rootView.setVisibility(View.GONE);
            return;
        }
        isHiding = true;
        rootView.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .translationY(dpToPx(rootView.getContext(), 12f))
                .setDuration(110L)
                .setInterpolator(new AccelerateInterpolator())
                .withLayer()
                .withEndAction(() -> {
                    isHiding = false;
                    if (!isVisible) {
                        rootView.setVisibility(View.GONE);
                        rootView.setScaleX(1f);
                        rootView.setScaleY(1f);
                        rootView.setTranslationY(0f);
                    }
                })
                .start();
    }

    public void updatePreview(@Nullable String query,
                              boolean hasMatch,
                              @Nullable String matchedEpisodeNumber,
                              @Nullable String rangeLabel) {
        Context ctx = rootView.getContext();
        String trimmed = query != null ? query.trim() : "";

        if (tvRange != null) {
            if (rangeLabel != null && !rangeLabel.isEmpty()) {
                tvRange.setText(rangeLabel);
                tvRange.setVisibility(View.VISIBLE);
            } else {
                tvRange.setVisibility(View.GONE);
            }
        }

        if (tvPreview != null) {
            if (trimmed.isEmpty()) {
                tvPreview.setText("Поиск серии");
                tvPreview.setTextColor(ContextCompat.getColor(ctx, R.color.player_item_inactive_text));
            } else if (hasMatch) {
                String labelNum = (matchedEpisodeNumber != null && !matchedEpisodeNumber.isEmpty())
                        ? matchedEpisodeNumber
                        : trimmed;
                tvPreview.setText("Серия " + labelNum);
                tvPreview.setTextColor(ContextCompat.getColor(ctx, R.color.white_color));
            } else {
                tvPreview.setText("Нет серии №" + trimmed);
                tvPreview.setTextColor(ContextCompat.getColor(ctx, R.color.bookmark_color));
            }
        }

        if (keyConfirm != null) {
            keyConfirm.setAlpha((!trimmed.isEmpty() && hasMatch) ? 1.0f : 0.45f);
        }

        if (keyBackspace != null) {
            keyBackspace.setAlpha(trimmed.isEmpty() ? 0.5f : 1.0f);
        }
    }

    private static int dpToPx(@NonNull Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
