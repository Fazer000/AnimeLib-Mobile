package com.example.animelib.util;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public class FlexibleBottomSheetDialog extends BottomSheetDialog {

    private Runnable backPressedAction;
    private boolean isCancelableFlag = true;

    public FlexibleBottomSheetDialog(@NonNull Context context) {
        super(context, R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
    }

    public FlexibleBottomSheetDialog(@NonNull Context context, int theme) {
        super(context, theme != 0 ? theme : R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
    }

    /**
     * Устанавливает действие возврата к предыдущему BottomSheet при нажатии системной кнопки Назад.
     * Если {@code null}, то этот BottomSheet считается корневым и при нажатии Назад закрывается.
     */
    public void setOnBackPressedAction(@Nullable Runnable action) {
        this.backPressedAction = action;
    }

    @Nullable
    public Runnable getOnBackPressedAction() {
        return this.backPressedAction;
    }

    /**
     * Переопределяется в подклассах, если внутри самого BottomSheet есть вложенные шаги
     * или внутренняя история (например, Шаг 2 -> Шаг 1, или WebView.canGoBack()).
     *
     * @return {@code true}, если нажатие Назад обработано внутри текущего BottomSheet;
     *         {@code false}, если текущий BottomSheet находится в своем корневом состоянии.
     */
    protected boolean onHandleInternalBack() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FlexibleBottomSheetUtils.setupFlexibleStyle(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackPressed();
            }
        });
    }

    @Override
    public void setCancelable(boolean flag) {
        super.setCancelable(flag);
        this.isCancelableFlag = flag;
    }

    @Override
    public void onBackPressed() {
        if (onHandleInternalBack()) {
            return;
        }
        if (backPressedAction != null) {
            Runnable action = backPressedAction;
            dismiss();
            action.run();
        } else if (isCancelableFlag) {
            dismiss();
        }
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        FlexibleBottomSheetUtils.applyFlexibleToView(this);
    }

    @Override
    public void setContentView(int layoutResId) {
        super.setContentView(layoutResId);
        FlexibleBottomSheetUtils.applyFlexibleToView(this);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        FlexibleBottomSheetUtils.applyFlexibleToView(this);
    }

    @Override
    public void show() {
        super.show();
        FlexibleBottomSheetUtils.applyFlexibleToView(this);
    }
}

