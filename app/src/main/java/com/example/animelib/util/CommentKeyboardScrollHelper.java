package com.example.animelib.util;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Помощник позиционирования полей ввода комментариев над системной клавиатурой (IME)
 * с небольшим визуальным отступом как в портретном режиме (NestedScrollView),
 * так и в боковой панели комментариев (RecyclerView).
 */
public class CommentKeyboardScrollHelper {

    /** Визуальный отступ от нижнего края блока инпута до верхнего края клавиатуры (dp). */
    public static final int KEYBOARD_OFFSET_DP = 10;

    /** Дополнительный запас прокрутки портретного списка при открытой клавиатуре (dp). */
    public static final int PORTRAIT_IME_EXTRA_SCROLL_PADDING_DP = 24;

    /** Отступ боковой панели комментариев над клавиатурой в горизонтальном режиме (dp). */
    public static final int LANDSCAPE_PANEL_IME_OFFSET_DP = 6;

    private static final int MIN_SCROLL_DELTA_DP = 2;
    private static final long HARDWARE_KEYBOARD_FALLBACK_DELAY_MS = 280L;
    private static final long TARGET_SCROLL_LOCK_CLEAR_MS = 280L;

    public interface LandscapeInputStateProvider {
        boolean shouldShowMainInputWhenIdle();
        boolean hasActiveInlineReply();
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Rect tempVisibleFrame = new Rect();
    private final int[] tempDecorLoc = new int[2];
    private final int[] tempTargetLoc = new int[2];
    private final int[] tempContainerLoc = new int[2];
    private final Set<View> boundInlineViews = Collections.newSetFromMap(new WeakHashMap<>());

    private float cachedDensity = 0f;

    // Window / IME state
    private View decorView;
    private ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener;
    private int windowInsetsImeBottomPx = 0;
    private int navBarBottomPx = 0;
    private int effectiveImeBottomPx = 0;
    private int lastAppliedNavBarBottomPx = -1;

    // Portrait views
    private NestedScrollView portraitScrollView;
    private View portraitInputContainer;
    private EditText portraitInputField;
    private View portraitScrollToTopFab;
    private boolean portraitBasePaddingCaptured = false;
    private int portraitBasePadLeft = 0;
    private int portraitBasePadTop = 0;
    private int portraitBasePadRight = 0;
    private int portraitBasePadBottom = 0;
    private int lastRequestedPortraitTargetScrollY = -1;

    // Landscape / side panel views
    private View landscapeCommentsPanel;
    private RecyclerView landscapeRecyclerView;
    private View landscapeInputContainer;
    private EditText landscapeInputField;
    private LandscapeInputStateProvider landscapeStateProvider;
    private boolean landscapeBasePaddingCaptured = false;
    private int landscapeBasePadLeft = 0;
    private int landscapeBasePadTop = 0;
    private int landscapeBasePadRight = 0;
    private int landscapeBasePadBottom = 0;

    // Currently active / focused comment input
    private WeakReference<EditText> activeInputRef;
    private WeakReference<View> activeContainerRef;
    private boolean activeInputIsPortrait = true;

    private final Runnable clearTargetScrollLockRunnable = () -> lastRequestedPortraitTargetScrollY = -1;

    private final Runnable hardwareKeyboardFallbackRunnable = () -> {
        if (effectiveImeBottomPx == 0) {
            scrollActiveInputIntoView(false, 0);
        }
    };

    public CommentKeyboardScrollHelper(Context context) {
        this.context = context;
    }

    /**
     * Вычисляет эффективную высоту клавиатуры на основе WindowInsets и видимого фрейма окна.
     */
    public static int computeEffectiveImeBottom(int windowInsetsImeBottom,
                                                int decorHeight,
                                                int decorBottomScreenY,
                                                int visibleFrameBottom) {
        int safeInsetsIme = Math.max(0, windowInsetsImeBottom);
        if (decorHeight <= 0 || visibleFrameBottom <= 0 || visibleFrameBottom >= decorBottomScreenY) {
            return safeInsetsIme;
        }
        int frameDiff = decorBottomScreenY - visibleFrameBottom;
        int fallbackIme = (frameDiff > (decorHeight * 16) / 100) ? frameDiff : 0;
        return Math.max(safeInsetsIme, fallbackIme);
    }

    /**
     * Вычисляет нижний внутренний отступ портретного NestedScrollView при открытой/закрытой клавиатуре.
     */
    public static int computePortraitScrollBottomPadding(int baseBottomPadding,
                                                         int imeBottomPx,
                                                         int navBarBottomPx,
                                                         int extraImeScrollPaddingPx) {
        int safeBase = Math.max(0, baseBottomPadding);
        if (imeBottomPx > 0) {
            return safeBase + imeBottomPx + Math.max(0, extraImeScrollPaddingPx);
        }
        return safeBase + Math.max(0, navBarBottomPx);
    }

    /**
     * Вычисляет целевую позицию scrollY портретного контейнера так, чтобы нижний край блока инпута
     * располагался ровно на marginPx выше верхнего края клавиатуры (imeTopScreenY),
     * с ограничением в допустимом диапазоне [0..maxScrollY].
     */
    public static int computeTargetScrollY(int currentScrollY,
                                           int maxScrollY,
                                           int targetBottomScreenY,
                                           int imeTopScreenY,
                                           int marginPx) {
        int desiredBottomScreenY = imeTopScreenY - Math.max(0, marginPx);
        int deltaY = targetBottomScreenY - desiredBottomScreenY;
        int unclampedTarget = currentScrollY + deltaY;
        int safeMax = Math.max(0, maxScrollY);
        return Math.max(0, Math.min(safeMax, unclampedTarget));
    }

    public boolean isImeVisible() {
        return effectiveImeBottomPx > 0;
    }

    public int getEffectiveImeBottomPx() {
        return effectiveImeBottomPx;
    }

    public void attachWindowRoot(@Nullable View rootDecorView) {
        if (rootDecorView == null || this.decorView == rootDecorView) {
            return;
        }
        detachWindowRootListener();
        this.decorView = rootDecorView;
        this.globalLayoutListener = this::evaluateImeStateAndApply;
        try {
            ViewTreeObserver vto = rootDecorView.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.addOnGlobalLayoutListener(this.globalLayoutListener);
            }
        } catch (Exception ignored) {}
    }

    public void onWindowInsetsChanged(int imeBottom, int navBarBottom) {
        this.windowInsetsImeBottomPx = Math.max(0, imeBottom);
        this.navBarBottomPx = Math.max(0, navBarBottom);
        evaluateImeStateAndApply();
    }

    public void bindPortraitViews(@Nullable NestedScrollView scrollView,
                                  @Nullable View inputContainer,
                                  @Nullable EditText inputField,
                                  @Nullable View scrollToTopFab) {
        if (this.portraitScrollView != scrollView) {
            portraitBasePaddingCaptured = false;
        }
        this.portraitScrollView = scrollView;
        this.portraitInputContainer = inputContainer;
        this.portraitInputField = inputField;
        this.portraitScrollToTopFab = scrollToTopFab;

        if (scrollView != null && !portraitBasePaddingCaptured) {
            portraitBasePadLeft = scrollView.getPaddingLeft();
            portraitBasePadTop = scrollView.getPaddingTop();
            portraitBasePadRight = scrollView.getPaddingRight();
            portraitBasePadBottom = scrollView.getPaddingBottom();
            portraitBasePaddingCaptured = true;
        }

        if (inputField != null) {
            final View targetBox = inputContainer != null ? inputContainer : inputField;
            attachInputListeners(inputField, targetBox, true);
        }

        applyPortraitPadding();
    }

    public void bindLandscapeViews(@Nullable View commentsPanel,
                                   @Nullable RecyclerView recyclerView,
                                   @Nullable View inputContainer,
                                   @Nullable EditText inputField,
                                   @Nullable LandscapeInputStateProvider stateProvider) {
        if (this.landscapeCommentsPanel != commentsPanel) {
            landscapeBasePaddingCaptured = false;
        }
        this.landscapeCommentsPanel = commentsPanel;
        this.landscapeRecyclerView = recyclerView;
        this.landscapeInputContainer = inputContainer;
        this.landscapeInputField = inputField;
        this.landscapeStateProvider = stateProvider;

        if (commentsPanel != null && !landscapeBasePaddingCaptured) {
            landscapeBasePadLeft = commentsPanel.getPaddingLeft();
            landscapeBasePadTop = commentsPanel.getPaddingTop();
            landscapeBasePadRight = commentsPanel.getPaddingRight();
            landscapeBasePadBottom = commentsPanel.getPaddingBottom();
            landscapeBasePaddingCaptured = true;
        }

        if (inputField != null) {
            final View targetBox = inputContainer != null ? inputContainer : inputField;
            attachInputListeners(inputField, targetBox, false);
        }

        applyLandscapePaddingAndVisibility(0, effectiveImeBottomPx);
    }

    public void attachInlineReplyInput(@Nullable EditText inlineInputField,
                                       @Nullable View inlineReplyContainer,
                                       boolean isPortrait) {
        if (inlineInputField == null) return;
        View targetBox = inlineReplyContainer != null ? inlineReplyContainer : inlineInputField;
        if (!boundInlineViews.contains(inlineInputField)) {
            boundInlineViews.add(inlineInputField);
            attachInputListeners(inlineInputField, targetBox, isPortrait);
        }
        if (inlineInputField.hasFocus()) {
            onCommentInputActivated(inlineInputField, targetBox, isPortrait);
        }
    }

    private void attachInputListeners(EditText inputField, View inputContainer, boolean isPortrait) {
        inputField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                onCommentInputActivated(inputField, inputContainer, isPortrait);
            } else if (isPortrait) {
                updateLandscapeMainInputVisibility();
            } else {
                mainHandler.post(this::updateLandscapeMainInputVisibility);
            }
        });

        inputField.setOnClickListener(v -> onCommentInputActivated(inputField, inputContainer, isPortrait));

        if (inputContainer != null && inputContainer != inputField) {
            inputContainer.setOnClickListener(v -> {
                if (!inputField.isEnabled() || inputField.getVisibility() != View.VISIBLE) return;
                inputField.requestFocus();
                showSoftKeyboard(inputField);
                onCommentInputActivated(inputField, inputContainer, isPortrait);
            });

            inputContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int oldHeight = oldBottom - oldTop;
                int newHeight = bottom - top;
                if (oldHeight > 0 && newHeight != oldHeight && effectiveImeBottomPx > 0
                        && inputField.hasFocus() && v.isShown()) {
                    lastRequestedPortraitTargetScrollY = -1;
                    scrollActiveInputIntoView(true, 0);
                }
            });
        }
    }

    public void onCommentInputActivated(@Nullable EditText inputField,
                                        @Nullable View inputContainer,
                                        boolean isPortrait) {
        if (inputField == null) return;
        View resolvedContainer = inputContainer != null ? inputContainer : inputField;
        this.activeInputRef = new WeakReference<>(inputField);
        this.activeContainerRef = new WeakReference<>(resolvedContainer);
        this.activeInputIsPortrait = isPortrait;
        this.lastRequestedPortraitTargetScrollY = -1;

        updateLandscapeMainInputVisibility();
        mainHandler.removeCallbacks(hardwareKeyboardFallbackRunnable);

        if (effectiveImeBottomPx > 0) {
            scrollActiveInputIntoView(true, 0);
        } else {
            mainHandler.postDelayed(hardwareKeyboardFallbackRunnable, HARDWARE_KEYBOARD_FALLBACK_DELAY_MS);
        }
    }

    public void updateLandscapeMainInputVisibility() {
        if (landscapeInputContainer == null || landscapeStateProvider == null) {
            return;
        }
        if (!landscapeStateProvider.shouldShowMainInputWhenIdle()) {
            return;
        }
        boolean hideForInlineReply = effectiveImeBottomPx > 0 && landscapeStateProvider.hasActiveInlineReply();
        int desiredVisibility = hideForInlineReply ? View.GONE : View.VISIBLE;
        if (landscapeInputContainer.getVisibility() != desiredVisibility) {
            landscapeInputContainer.setVisibility(desiredVisibility);
        }
    }

    private void evaluateImeStateAndApply() {
        int newEffectiveIme = windowInsetsImeBottomPx;
        if (decorView != null && decorView.getHeight() > 0) {
            try {
                decorView.getWindowVisibleDisplayFrame(tempVisibleFrame);
                decorView.getLocationOnScreen(tempDecorLoc);
                int decorHeight = decorView.getHeight();
                int decorBottomY = tempDecorLoc[1] + decorHeight;
                newEffectiveIme = computeEffectiveImeBottom(
                        windowInsetsImeBottomPx,
                        decorHeight,
                        decorBottomY,
                        tempVisibleFrame.bottom
                );
            } catch (Exception ignored) {}
        }

        boolean imeChanged = (newEffectiveIme != effectiveImeBottomPx);
        boolean navChanged = (navBarBottomPx != lastAppliedNavBarBottomPx);
        if (!imeChanged && !navChanged) {
            return;
        }

        int previousIme = effectiveImeBottomPx;
        effectiveImeBottomPx = newEffectiveIme;
        lastAppliedNavBarBottomPx = navBarBottomPx;

        applyPortraitPadding();
        applyLandscapePaddingAndVisibility(previousIme, newEffectiveIme);

        if (effectiveImeBottomPx > 0) {
            mainHandler.removeCallbacks(hardwareKeyboardFallbackRunnable);
            if (imeChanged && syncFocusedCommentInputIfNeeded()) {
                lastRequestedPortraitTargetScrollY = -1;
                scrollActiveInputIntoView(true, 0);
            }
        } else {
            lastRequestedPortraitTargetScrollY = -1;
            mainHandler.removeCallbacks(clearTargetScrollLockRunnable);
        }
    }

    private boolean syncFocusedCommentInputIfNeeded() {
        EditText existing = activeInputRef != null ? activeInputRef.get() : null;
        if (existing != null && existing.hasFocus() && existing.isShown()) {
            return true;
        }
        if (decorView == null) {
            return false;
        }
        View focused = decorView.findFocus();
        if (!(focused instanceof EditText) || !focused.isShown()) {
            return false;
        }
        EditText focusedEdit = (EditText) focused;
        int id = focusedEdit.getId();
        if (id == R.id.portraitCommentInputField) {
            View box = portraitInputContainer != null ? portraitInputContainer : focusedEdit;
            activeInputRef = new WeakReference<>(focusedEdit);
            activeContainerRef = new WeakReference<>(box);
            activeInputIsPortrait = true;
            return true;
        }
        if (id == R.id.commentInputField) {
            View box = landscapeInputContainer != null ? landscapeInputContainer : focusedEdit;
            activeInputRef = new WeakReference<>(focusedEdit);
            activeContainerRef = new WeakReference<>(box);
            activeInputIsPortrait = false;
            return true;
        }
        if (id == R.id.inlineCommentInputField) {
            View inlineBox = findAncestorById(focusedEdit, R.id.inlineReplyContainer);
            boolean inPortrait = findAncestorById(focusedEdit, R.id.portraitScrollView) != null;
            activeInputRef = new WeakReference<>(focusedEdit);
            activeContainerRef = new WeakReference<>(inlineBox != null ? inlineBox : focusedEdit);
            activeInputIsPortrait = inPortrait;
            return true;
        }
        return false;
    }

    private void applyPortraitPadding() {
        if (portraitScrollView == null || !portraitBasePaddingCaptured) {
            return;
        }
        int targetBottomPadding = computePortraitScrollBottomPadding(
                portraitBasePadBottom,
                effectiveImeBottomPx,
                navBarBottomPx,
                dpToPx(PORTRAIT_IME_EXTRA_SCROLL_PADDING_DP)
        );
        if (portraitScrollView.getPaddingBottom() != targetBottomPadding) {
            portraitScrollView.setPadding(
                    portraitBasePadLeft,
                    portraitBasePadTop,
                    portraitBasePadRight,
                    targetBottomPadding
            );
        }
        if (effectiveImeBottomPx > 0 && portraitScrollToTopFab != null
                && portraitScrollToTopFab.getVisibility() == View.VISIBLE) {
            if (portraitScrollToTopFab instanceof FloatingActionButton) {
                ((FloatingActionButton) portraitScrollToTopFab).hide();
            } else {
                portraitScrollToTopFab.setVisibility(View.GONE);
            }
        }
    }

    private void applyLandscapePaddingAndVisibility(int previousIme, int newIme) {
        if (landscapeCommentsPanel != null && landscapeBasePaddingCaptured) {
            int targetBottomPad = newIme > 0
                    ? (landscapeBasePadBottom + newIme + dpToPx(LANDSCAPE_PANEL_IME_OFFSET_DP))
                    : landscapeBasePadBottom;
            if (landscapeCommentsPanel.getPaddingBottom() != targetBottomPad) {
                landscapeCommentsPanel.setPadding(
                        landscapeBasePadLeft,
                        landscapeBasePadTop,
                        landscapeBasePadRight,
                        targetBottomPad
                );
            }
        }

        updateLandscapeMainInputVisibility();

        if (newIme > previousIme && landscapeRecyclerView != null && landscapeRecyclerView.isShown()) {
            final int shrinkDelta = newIme - previousIme;
            landscapeRecyclerView.post(() -> {
                if (landscapeRecyclerView == null || !landscapeRecyclerView.isShown() || effectiveImeBottomPx <= 0) {
                    return;
                }
                boolean inlineActive = landscapeStateProvider != null && landscapeStateProvider.hasActiveInlineReply();
                if (!inlineActive && landscapeInputField != null && landscapeInputField.hasFocus()) {
                    if (landscapeRecyclerView.canScrollVertically(1)) {
                        landscapeRecyclerView.smoothScrollBy(0, shrinkDelta);
                    }
                }
            });
        }
    }

    private void scrollActiveInputIntoView(boolean alignToKeyboard, int retryCount) {
        EditText activeInput = activeInputRef != null ? activeInputRef.get() : null;
        View targetContainer = activeContainerRef != null ? activeContainerRef.get() : activeInput;
        if (activeInput == null || targetContainer == null) {
            return;
        }
        if (!activeInput.isAttachedToWindow() || !targetContainer.isShown()) {
            if (retryCount < 3) {
                mainHandler.postDelayed(() -> scrollActiveInputIntoView(alignToKeyboard, retryCount + 1), 32L);
            }
            return;
        }

        if (activeInputIsPortrait) {
            scrollPortraitContainerIntoView(targetContainer, alignToKeyboard, retryCount);
        } else {
            scrollLandscapeContainerIntoView(targetContainer, retryCount);
        }
    }

    private void scrollPortraitContainerIntoView(View targetContainer, boolean alignToKeyboard, int retryCount) {
        if (portraitScrollView == null || !portraitScrollView.isShown()) {
            return;
        }
        if ((portraitScrollView.isLayoutRequested()
                || targetContainer.isLayoutRequested()
                || targetContainer.getHeight() <= 0) && retryCount < 4) {
            portraitScrollView.post(() -> scrollPortraitContainerIntoView(targetContainer, alignToKeyboard, retryCount + 1));
            return;
        }

        View root = decorView != null ? decorView : portraitScrollView.getRootView();
        if (root == null || root.getHeight() <= 0) {
            return;
        }

        root.getLocationOnScreen(tempDecorLoc);
        int decorBottomScreenY = tempDecorLoc[1] + root.getHeight();
        int visibleBottomLimitY = effectiveImeBottomPx > 0
                ? (decorBottomScreenY - effectiveImeBottomPx)
                : (decorBottomScreenY - navBarBottomPx);

        targetContainer.getLocationOnScreen(tempTargetLoc);
        int targetBottomScreenY = tempTargetLoc[1] + targetContainer.getHeight();
        int marginPx = dpToPx(KEYBOARD_OFFSET_DP);

        // Если экранная клавиатура не открылась (например аппаратная клавиатура), скроллим только если инпут обрезан снизу
        if (!alignToKeyboard && effectiveImeBottomPx == 0) {
            if (targetBottomScreenY <= visibleBottomLimitY - marginPx) {
                return;
            }
        }

        int currentScrollY = portraitScrollView.getScrollY();
        int maxScrollY = computeNestedScrollMaxRange(portraitScrollView);
        int targetScrollY = computeTargetScrollY(
                currentScrollY,
                maxScrollY,
                targetBottomScreenY,
                visibleBottomLimitY,
                marginPx
        );

        int minDeltaPx = dpToPx(MIN_SCROLL_DELTA_DP);
        if (Math.abs(targetScrollY - currentScrollY) <= minDeltaPx) {
            return;
        }
        if (lastRequestedPortraitTargetScrollY >= 0
                && Math.abs(targetScrollY - lastRequestedPortraitTargetScrollY) <= minDeltaPx) {
            return;
        }

        lastRequestedPortraitTargetScrollY = targetScrollY;
        mainHandler.removeCallbacks(clearTargetScrollLockRunnable);
        mainHandler.postDelayed(clearTargetScrollLockRunnable, TARGET_SCROLL_LOCK_CLEAR_MS);
        portraitScrollView.smoothScrollTo(0, targetScrollY);
    }

    private void scrollLandscapeContainerIntoView(View targetContainer, int retryCount) {
        if (landscapeRecyclerView == null || !landscapeRecyclerView.isShown()) {
            return;
        }
        // Главный нижний инпут в боковой панели уже поднят за счёт paddingBottom на commentsPanel;
        // прокрутка внутри RecyclerView нужна для встроенного поля ответа (inlineReplyContainer).
        if (targetContainer == landscapeInputContainer || targetContainer == landscapeInputField) {
            return;
        }
        if ((landscapeRecyclerView.isLayoutRequested()
                || targetContainer.isLayoutRequested()
                || targetContainer.getHeight() <= 0) && retryCount < 4) {
            landscapeRecyclerView.post(() -> scrollLandscapeContainerIntoView(targetContainer, retryCount + 1));
            return;
        }

        landscapeRecyclerView.getLocationOnScreen(tempContainerLoc);
        int rvVisibleBottomY = tempContainerLoc[1]
                + landscapeRecyclerView.getHeight()
                - landscapeRecyclerView.getPaddingBottom();

        targetContainer.getLocationOnScreen(tempTargetLoc);
        int targetBottomScreenY = tempTargetLoc[1] + targetContainer.getHeight();
        int desiredBottomY = rvVisibleBottomY - dpToPx(6);
        int deltaY = targetBottomScreenY - desiredBottomY;

        if (Math.abs(deltaY) > dpToPx(MIN_SCROLL_DELTA_DP)) {
            landscapeRecyclerView.smoothScrollBy(0, deltaY);
        }
    }

    private static int computeNestedScrollMaxRange(NestedScrollView scrollView) {
        if (scrollView == null || scrollView.getChildCount() == 0) {
            return 0;
        }
        View child = scrollView.getChildAt(0);
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        int verticalMargins = 0;
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            verticalMargins = mlp.topMargin + mlp.bottomMargin;
        }
        int childSize = child.getHeight() + verticalMargins;
        int parentSpace = Math.max(0, scrollView.getHeight() - scrollView.getPaddingTop() - scrollView.getPaddingBottom());
        return Math.max(0, childSize - parentSpace);
    }

    private static View findAncestorById(View startView, int targetId) {
        if (startView == null) return null;
        ViewParent parent = startView.getParent();
        while (parent instanceof View) {
            View v = (View) parent;
            if (v.getId() == targetId) {
                return v;
            }
            parent = v.getParent();
        }
        return null;
    }

    private void showSoftKeyboard(EditText editText) {
        if (context == null || editText == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Exception ignored) {}
    }

    private int dpToPx(int dp) {
        if (context == null) return dp;
        if (cachedDensity <= 0f) {
            cachedDensity = context.getResources().getDisplayMetrics().density;
        }
        return Math.round(dp * cachedDensity);
    }

    private void detachWindowRootListener() {
        if (decorView != null && globalLayoutListener != null) {
            try {
                ViewTreeObserver vto = decorView.getViewTreeObserver();
                if (vto != null && vto.isAlive()) {
                    vto.removeOnGlobalLayoutListener(globalLayoutListener);
                }
            } catch (Exception ignored) {}
        }
        globalLayoutListener = null;
        decorView = null;
    }

    public void cleanup() {
        mainHandler.removeCallbacksAndMessages(null);
        detachWindowRootListener();
        boundInlineViews.clear();
        activeInputRef = null;
        activeContainerRef = null;
        portraitScrollView = null;
        portraitInputContainer = null;
        portraitInputField = null;
        portraitScrollToTopFab = null;
        landscapeCommentsPanel = null;
        landscapeRecyclerView = null;
        landscapeInputContainer = null;
        landscapeInputField = null;
        landscapeStateProvider = null;
    }
}
