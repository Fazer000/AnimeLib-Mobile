package com.example.animelib.util;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.example.animelib.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public class FlexibleBottomSheetDialog extends BottomSheetDialog {

    public FlexibleBottomSheetDialog(@NonNull Context context) {
        super(context, R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
    }

    public FlexibleBottomSheetDialog(@NonNull Context context, int theme) {
        super(context, theme != 0 ? theme : R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FlexibleBottomSheetUtils.setupFlexibleStyle(this);
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
