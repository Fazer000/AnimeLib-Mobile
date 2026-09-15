package com.example.animelib.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.adapters.CustomSelectAdapter;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;

import java.util.List;

public class CustomSelectBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnOptionSelectedListener {
        void onOptionSelected(String value, String text);
    }

    private final List<String> options;
    private final List<String> values;
    private final String currentValue;
    private final OnOptionSelectedListener listener;

    public CustomSelectBottomSheet(@NonNull Context context,
                                   List<String> options,
                                   List<String> values,
                                   String currentValue,
                                   OnOptionSelectedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.options = options;
        this.values = values;
        this.currentValue = currentValue;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_custom_select, null);
        setContentView(view);

        FloatingBottomSheetUtils.setupFloatingStyle(this);

        RecyclerView recyclerView = view.findViewById(R.id.selectRecyclerView);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            recyclerView.setNestedScrollingEnabled(false);
            CustomSelectAdapter adapter = new CustomSelectAdapter(
                    options,
                    values,
                    currentValue,
                    (value, text) -> {
                        dismiss();
                        if (listener != null) {
                            listener.onOptionSelected(value, text);
                        }
                    }
            );
            recyclerView.setAdapter(adapter);
        }

        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }
}
