package com.example.animelib.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.example.animelib.R;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.slider.Slider;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class SpeedBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnSpeedChangedListener {
        void onSpeedChanged(float speed);
    }

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    private float currentSpeed;
    private final OnSpeedChangedListener listener;
    private OnBackPressedListener onBackPressedListener;

    private Slider speedSlider;
    private TextView currentSpeedText;
    private TextView speedStateLabel;

    private final Map<Float, TextView> presetButtons = new HashMap<>();

    public SpeedBottomSheet(@NonNull Context context, float currentSpeed, OnSpeedChangedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.currentSpeed = currentSpeed;
        this.listener = listener;
    }

    public void setOnBackPressedListener(OnBackPressedListener listener) {
        this.onBackPressedListener = listener;
    }

    @SuppressLint({"DefaultLocale", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_speed, null);
        setContentView(view);
        FloatingBottomSheetUtils.setupFloatingStyle(this);

        // Header back button
        ImageButton backButton = view.findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                dismiss();
                if (onBackPressedListener != null) {
                    onBackPressedListener.onBackPressed();
                }
            });
        }

        // Reset speed button
        View btnResetSpeed = view.findViewById(R.id.btnResetSpeed);
        if (btnResetSpeed != null) {
            btnResetSpeed.setOnClickListener(v -> setSpeed(1.0f));
        }

        // Main speed display controls
        currentSpeedText = view.findViewById(R.id.currentSpeedText);
        speedStateLabel = view.findViewById(R.id.speedStateLabel);
        speedSlider = view.findViewById(R.id.speedSlider);

        // Steppers
        View btnDecrease = view.findViewById(R.id.btnDecreaseSpeed);
        if (btnDecrease != null) {
            btnDecrease.setOnClickListener(v -> {
                float next = Math.max(0.25f, Math.round((currentSpeed - 0.1f) * 100f) / 100f);
                setSpeed(next);
            });
        }

        View btnIncrease = view.findViewById(R.id.btnIncreaseSpeed);
        if (btnIncrease != null) {
            btnIncrease.setOnClickListener(v -> {
                float next = Math.min(3.0f, Math.round((currentSpeed + 0.1f) * 100f) / 100f);
                setSpeed(next);
            });
        }

        // Material Slider
        if (speedSlider != null) {
            speedSlider.addOnChangeListener((slider, value, fromUser) -> {
                if (fromUser) {
                    currentSpeed = Math.round(value * 100f) / 100f;
                    updateUi(currentSpeed, false);
                    if (listener != null) {
                        listener.onSpeedChanged(currentSpeed);
                    }
                }
            });
        }

        // Register preset chips
        registerPreset(view, R.id.speed05x, 0.5f);
        registerPreset(view, R.id.speed075x, 0.75f);
        registerPreset(view, R.id.speed1x, 1.0f);
        registerPreset(view, R.id.speed125x, 1.25f);
        registerPreset(view, R.id.speed15x, 1.5f);
        registerPreset(view, R.id.speed175x, 1.75f);
        registerPreset(view, R.id.speed2x, 2.0f);
        registerPreset(view, R.id.speed25x, 2.5f);
        registerPreset(view, R.id.speed3x, 3.0f);
        registerPreset(view, R.id.speedSkip, 10.0f);

        updateUi(currentSpeed, true);
        setCancelable(true);
    }

    private void registerPreset(View rootView, int viewId, float speed) {
        TextView textView = rootView.findViewById(viewId);
        if (textView != null) {
            presetButtons.put(speed, textView);
            textView.setOnClickListener(v -> setSpeed(speed));
        }
    }

    public void updateCurrentSpeed(float speed) {
        this.currentSpeed = speed;
        updateUi(speed, true);
    }

    public float getCurrentSpeed() {
        return currentSpeed;
    }

    private void setSpeed(float speed) {
        this.currentSpeed = speed;
        updateUi(speed, true);
        if (listener != null) {
            listener.onSpeedChanged(speed);
        }
    }

    @SuppressLint("DefaultLocale")
    private void updateUi(float speed, boolean updateSlider) {
        if (currentSpeedText != null) {
            String formatted;
            float rounded = Math.round(speed * 100f) / 100f;
            if (Math.abs(rounded - Math.round(rounded)) < 0.001f) {
                formatted = String.format(Locale.US, "%.1fx", rounded);
            } else {
                formatted = String.format(Locale.US, "%.2fx", rounded);
                if (formatted.endsWith("0x")) {
                    formatted = formatted.substring(0, formatted.length() - 2) + "x";
                }
            }
            currentSpeedText.setText(formatted);
        }

        if (speedStateLabel != null) {
            if (Math.abs(speed - 1.0f) < 0.01f) {
                speedStateLabel.setText("Обычная скорость");
            } else if (speed < 1.0f) {
                speedStateLabel.setText("Замедленное воспроизведение");
            } else if (speed <= 2.0f) {
                speedStateLabel.setText("Ускоренное воспроизведение");
            } else if (speed < 10.0f) {
                speedStateLabel.setText("Быстрый просмотр");
            } else {
                speedStateLabel.setText("Режим пропуска опенинга");
            }
        }

        if (updateSlider && speedSlider != null) {
            float clamped = Math.max(0.25f, Math.min(3.0f, speed));
            speedSlider.setValue(clamped);
        }

        int secondaryColor = ContextCompat.getColor(getContext(), R.color.secondary_text_color);
        int primaryColor = ContextCompat.getColor(getContext(), R.color.primary_text_color);

        for (Map.Entry<Float, TextView> entry : presetButtons.entrySet()) {
            float presetSpeed = entry.getKey();
            TextView chip = entry.getValue();
            if (chip == null) continue;

            boolean isSelected = Math.abs(presetSpeed - speed) < 0.01f;

            if (isSelected) {
                chip.setBackgroundResource(R.drawable.chip_selected);
                chip.setTextColor(secondaryColor);
            } else {
                chip.setBackgroundResource(R.drawable.chip_unselected);
                if (presetSpeed == 10.0f) {
                    chip.setTextColor(secondaryColor);
                } else {
                    chip.setTextColor(primaryColor);
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (onBackPressedListener != null) {
            dismiss();
            onBackPressedListener.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }
}

