package com.example.animelib.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.animelib.R;
import com.example.animelib.adapters.QualityAdapter;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import android.widget.FrameLayout;
import android.view.ViewGroup;
import android.util.TypedValue;
import java.util.List;
import java.util.Objects;

public class QualityBottomSheet extends FlexibleBottomSheetDialog {
    private final QualityAdapter.OnQualitySelectedListener listener;
    private QualityAdapter adapter;
    private OnBackPressedListener onBackPressedListener;
    private String titleText;
    
    public interface OnBackPressedListener {
        void onBackPressed();
    }

    public QualityBottomSheet(Context context, List<String> qualities, String currentQuality, QualityAdapter.OnQualitySelectedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.listener = listener;
        
        // Create adapter immediately with the provided data and dismiss dialog on selection
        this.adapter = new QualityAdapter(qualities, currentQuality, quality -> {
            if (listener != null) {
                listener.onQualitySelected(quality);
            }
            dismiss();
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        @SuppressLint("InflateParams") View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_quality, null);
        setContentView(view);
        com.example.animelib.util.FloatingBottomSheetUtils.setupFloatingStyle(this);

        // Setup back button
        ImageButton backButton = view.findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> {
            // Call back listener to show main settings
            if (onBackPressedListener != null) {
                onBackPressedListener.onBackPressed();
            }
            dismiss();
        });

        // Setup quality list
        RecyclerView recyclerView = view.findViewById(R.id.qualityRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setNestedScrollingEnabled(false);
        recyclerView.setAdapter(adapter);

        applyTitle();
        setCancelable(true);
    }

    /**
     * Задаёт заголовок шита
     */
    public void setTitleText(String titleText) {
        this.titleText = titleText;
        applyTitle();
    }

    /**
     * Применяет заголовок к разметке
     */
    private void applyTitle() {
        if (titleText == null) {
            return;
        }
        TextView titleView = findViewById(R.id.qualityTitle);
        if (titleView != null) {
            titleView.setText(titleText);
        }
    }
    
    public void updateCurrentQuality(String newCurrentQuality) {
        if (adapter != null) {
            adapter.updateCurrentQuality(newCurrentQuality);
        }
    }
    
    /**
     * Обновляет список доступных качеств
     */
    public void updateQualities(List<String> newQualities, String newCurrentQuality) {
        if (adapter != null) {
            adapter.updateQualities(newQualities, newCurrentQuality);
        }
    }
    
    public void setOnBackPressedListener(OnBackPressedListener listener) {
        this.onBackPressedListener = listener;
    }
    
}
