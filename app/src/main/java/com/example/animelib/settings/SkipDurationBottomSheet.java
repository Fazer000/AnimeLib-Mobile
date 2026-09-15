package com.example.animelib.settings;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.example.animelib.R;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.slider.Slider;

public class SkipDurationBottomSheet extends FlexibleBottomSheetDialog {
    private int currentDuration; // in seconds
    private final OnDurationChangedListener listener;
    private OnBackPressedListener onBackPressedListener;

    private TextView digitalTimeText;
    private TextView digitalTimeSubtext;
    private Slider timeSlider;

    private TextView preset30, preset60, preset85, preset90, preset110, preset120;

    public SkipDurationBottomSheet(Context context, int currentDuration, OnDurationChangedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.currentDuration = currentDuration;
        this.listener = listener;
    }

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    public interface OnDurationChangedListener {
        void onDurationChanged(int durationInSeconds);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_skip_duration, null);
        setContentView(view);
        com.example.animelib.util.FloatingBottomSheetUtils.setupFloatingStyle(this);

        digitalTimeText = view.findViewById(R.id.digitalTimeText);
        digitalTimeSubtext = view.findViewById(R.id.digitalTimeSubtext);
        timeSlider = view.findViewById(R.id.timeSlider);

        ImageButton backButton = view.findViewById(R.id.backButton);
        ImageButton confirmButton = view.findViewById(R.id.confirmButton);

        TextView btnMinus10 = view.findViewById(R.id.btnMinus10);
        TextView btnMinus5 = view.findViewById(R.id.btnMinus5);
        TextView btnPlus5 = view.findViewById(R.id.btnPlus5);
        TextView btnPlus10 = view.findViewById(R.id.btnPlus10);

        preset30 = view.findViewById(R.id.preset30);
        preset60 = view.findViewById(R.id.preset60);
        preset85 = view.findViewById(R.id.preset85);
        preset90 = view.findViewById(R.id.preset90);
        preset110 = view.findViewById(R.id.preset110);
        preset120 = view.findViewById(R.id.preset120);

        if (currentDuration < 10) currentDuration = 10;
        if (currentDuration > 240) currentDuration = 240;

        timeSlider.setValue((float) currentDuration);
        updateUI(currentDuration);

        timeSlider.addOnChangeListener((slider, value, fromUser) -> {
            int newDuration = Math.round(value);
            currentDuration = newDuration;
            updateUI(newDuration);
        });

        btnMinus10.setOnClickListener(v -> adjustDuration(-10));
        btnMinus5.setOnClickListener(v -> adjustDuration(-5));
        btnPlus5.setOnClickListener(v -> adjustDuration(5));
        btnPlus10.setOnClickListener(v -> adjustDuration(10));

        preset30.setOnClickListener(v -> setDuration(30));
        preset60.setOnClickListener(v -> setDuration(60));
        preset85.setOnClickListener(v -> setDuration(85));
        preset90.setOnClickListener(v -> setDuration(90));
        preset110.setOnClickListener(v -> setDuration(110));
        preset120.setOnClickListener(v -> setDuration(120));

        backButton.setOnClickListener(v -> {
            if (onBackPressedListener != null) {
                onBackPressedListener.onBackPressed();
            }
            dismiss();
        });

        confirmButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDurationChanged(currentDuration);
            }
            dismiss();
        });

        setCancelable(true);
    }

    private void adjustDuration(int delta) {
        setDuration(currentDuration + delta);
    }

    private void setDuration(int seconds) {
        if (seconds < 10) seconds = 10;
        if (seconds > 240) seconds = 240;
        currentDuration = seconds;
        timeSlider.setValue((float) seconds);
        updateUI(seconds);
    }

    private void updateUI(int seconds) {
        int minutes = seconds / 60;
        int remSeconds = seconds % 60;
        String timeStr = String.format("%d:%02d", minutes, remSeconds);
        digitalTimeText.setText(timeStr);

        String subtext;
        if (minutes > 0) {
            subtext = String.format("%d мин %02d сек (%d сек)", minutes, remSeconds, seconds);
        } else {
            subtext = String.format("%d сек", seconds);
        }
        digitalTimeSubtext.setText(subtext);

        highlightPresetIfMatching(preset30, 30, seconds);
        highlightPresetIfMatching(preset60, 60, seconds);
        highlightPresetIfMatching(preset85, 85, seconds);
        highlightPresetIfMatching(preset90, 90, seconds);
        highlightPresetIfMatching(preset110, 110, seconds);
        highlightPresetIfMatching(preset120, 120, seconds);
    }

    private void highlightPresetIfMatching(TextView presetView, int presetVal, int currentVal) {
        if (presetView == null) return;
        if (presetVal == currentVal) {
            presetView.setBackgroundResource(R.drawable.chip_selected);
            presetView.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.secondary_text_color));
        } else {
            presetView.setBackgroundResource(R.drawable.chip_unselected);
            presetView.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.primary_text_color));
        }
    }

    public void setOnBackPressedListener(OnBackPressedListener listener) {
        this.onBackPressedListener = listener;
    }
}
