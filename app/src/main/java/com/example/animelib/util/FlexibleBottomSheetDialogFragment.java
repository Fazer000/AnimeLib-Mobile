package com.example.animelib.util;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class FlexibleBottomSheetDialogFragment extends BottomSheetDialogFragment {

    private Runnable backPressedAction;

    public void setOnBackPressedAction(@Nullable Runnable action) {
        this.backPressedAction = action;
        Dialog dialog = getDialog();
        if (dialog instanceof FlexibleBottomSheetDialog) {
            ((FlexibleBottomSheetDialog) dialog).setOnBackPressedAction(action);
        }
    }

    @Nullable
    public Runnable getOnBackPressedAction() {
        return this.backPressedAction;
    }

    protected boolean onHandleInternalBack() {
        return false;
    }

    public void performBackPress() {
        if (onHandleInternalBack()) {
            return;
        }
        if (backPressedAction != null) {
            Runnable action = backPressedAction;
            dismiss();
            action.run();
        } else if (isCancelable()) {
            dismiss();
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        FlexibleBottomSheetDialog dialog = new FlexibleBottomSheetDialog(requireContext(), getTheme()) {
            @Override
            protected boolean onHandleInternalBack() {
                return FlexibleBottomSheetDialogFragment.this.onHandleInternalBack();
            }
        };
        dialog.setOnBackPressedAction(backPressedAction);
        FlexibleBottomSheetUtils.setupFlexibleStyle(dialog);
        return dialog;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            FlexibleBottomSheetUtils.applyFlexibleToView((BottomSheetDialog) dialog);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            FlexibleBottomSheetUtils.applyFlexibleToView((BottomSheetDialog) dialog);
        }
    }
}

