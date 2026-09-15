package com.example.animelib.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.example.animelib.R;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.google.android.material.slider.Slider;

import java.util.Locale;

public class VideoFiltersBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnFiltersChangedListener {
        void onFiltersChanged(float brightness, float contrast, float saturation, float gamma, float hue);
    }

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    private float brightness = 0f;
    private float contrast = 100f;
    private float saturation = 100f;
    private float gamma = 1.0f;
    private float hue = 0f;

    private final OnFiltersChangedListener filtersListener;
    private OnBackPressedListener onBackPressedListener;

    private TextView tvBrightnessValue;
    private TextView tvContrastValue;
    private TextView tvSaturationValue;
    private TextView tvGammaValue;
    private TextView tvHueValue;

    private Slider sliderBrightness;
    private Slider sliderContrast;
    private Slider sliderSaturation;
    private Slider sliderGamma;
    private Slider sliderHue;

    public VideoFiltersBottomSheet(Context context, float brightness, float contrast, float saturation, float gamma, float hue, OnFiltersChangedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.brightness = brightness;
        this.contrast = contrast;
        this.saturation = saturation;
        this.gamma = gamma;
        this.hue = hue;
        this.filtersListener = listener;
    }

    public void setOnBackPressedListener(OnBackPressedListener listener) {
        this.onBackPressedListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_video_filters, null);
        setContentView(view);
        FloatingBottomSheetUtils.setupFloatingStyle(this);

        ImageButton backButton = view.findViewById(R.id.backButton);
        TextView btnResetFilters = view.findViewById(R.id.btnResetFilters);

        tvBrightnessValue = view.findViewById(R.id.tvBrightnessValue);
        tvContrastValue = view.findViewById(R.id.tvContrastValue);
        tvSaturationValue = view.findViewById(R.id.tvSaturationValue);
        tvGammaValue = view.findViewById(R.id.tvGammaValue);
        tvHueValue = view.findViewById(R.id.tvHueValue);

        sliderBrightness = view.findViewById(R.id.sliderBrightness);
        sliderContrast = view.findViewById(R.id.sliderContrast);
        sliderSaturation = view.findViewById(R.id.sliderSaturation);
        sliderGamma = view.findViewById(R.id.sliderGamma);
        sliderHue = view.findViewById(R.id.sliderHue);

        // Set initial slider values
        sliderBrightness.setValue(clamp(brightness, -100f, 100f));
        sliderContrast.setValue(clamp(contrast, 50f, 150f));
        sliderSaturation.setValue(clamp(saturation, 0f, 200f));
        sliderGamma.setValue(clamp(gamma, 0.5f, 2.0f));
        sliderHue.setValue(clamp(hue, -180f, 180f));

        updateLabels();

        // Touch listeners to make bottom sheet transparent while dragging sliders
        Slider.OnSliderTouchListener touchListener = new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                setSheetAlpha(view, 0.30f);
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                setSheetAlpha(view, 1.0f);
            }
        };

        sliderBrightness.addOnSliderTouchListener(touchListener);
        sliderContrast.addOnSliderTouchListener(touchListener);
        sliderSaturation.addOnSliderTouchListener(touchListener);
        sliderGamma.addOnSliderTouchListener(touchListener);
        sliderHue.addOnSliderTouchListener(touchListener);

        // Listeners
        sliderBrightness.addOnChangeListener((slider, value, fromUser) -> {
            brightness = value;
            updateLabels();
            notifyChange();
        });

        sliderContrast.addOnChangeListener((slider, value, fromUser) -> {
            contrast = value;
            updateLabels();
            notifyChange();
        });

        sliderSaturation.addOnChangeListener((slider, value, fromUser) -> {
            saturation = value;
            updateLabels();
            notifyChange();
        });

        sliderGamma.addOnChangeListener((slider, value, fromUser) -> {
            gamma = value;
            updateLabels();
            notifyChange();
        });

        sliderHue.addOnChangeListener((slider, value, fromUser) -> {
            hue = value;
            updateLabels();
            notifyChange();
        });

        backButton.setOnClickListener(v -> {
            dismiss();
            if (onBackPressedListener != null) {
                onBackPressedListener.onBackPressed();
            }
        });

        btnResetFilters.setOnClickListener(v -> {
            brightness = 0f;
            contrast = 100f;
            saturation = 100f;
            gamma = 1.0f;
            hue = 0f;

            sliderBrightness.setValue(0f);
            sliderContrast.setValue(100f);
            sliderSaturation.setValue(100f);
            sliderGamma.setValue(1.0f);
            sliderHue.setValue(0f);

            updateLabels();
            notifyChange();
        });
    }

    private void updateLabels() {
        if (tvBrightnessValue != null) {
            tvBrightnessValue.setText(brightness > 0 ? String.format(Locale.US, "+%.0f%%", brightness) : String.format(Locale.US, "%.0f%%", brightness));
        }
        if (tvContrastValue != null) {
            tvContrastValue.setText(String.format(Locale.US, "%.0f%%", contrast));
        }
        if (tvSaturationValue != null) {
            tvSaturationValue.setText(String.format(Locale.US, "%.0f%%", saturation));
        }
        if (tvGammaValue != null) {
            tvGammaValue.setText(String.format(Locale.US, "%.2f", gamma));
        }
        if (tvHueValue != null) {
            tvHueValue.setText(hue > 0 ? String.format(Locale.US, "+%.0f°", hue) : String.format(Locale.US, "%.0f°", hue));
        }
    }

    private void notifyChange() {
        if (filtersListener != null) {
            filtersListener.onFiltersChanged(brightness, contrast, saturation, gamma, hue);
        }
    }

    private float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private android.animation.ValueAnimator dimAnimator;

    private void setSheetAlpha(View rootView, float targetAlpha) {
        View target = findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (target == null) {
            target = rootView;
        }
        if (target != null) {
            target.animate()
                    .alpha(targetAlpha)
                    .setDuration(180)
                    .start();
        }

        // Animate dimming overlay / touch_outside view
        View touchOutside = findViewById(com.google.android.material.R.id.touch_outside);
        if (touchOutside != null) {
            float targetDimAlpha = targetAlpha < 0.9f ? 0.0f : 1.0f;
            touchOutside.animate()
                    .alpha(targetDimAlpha)
                    .setDuration(180)
                    .start();
        }

        // Animate window dim amount
        if (getWindow() != null) {
            boolean isTransparent = targetAlpha < 0.9f;
            float targetDim = isTransparent ? 0.0f : 0.5f;
            if (dimAnimator != null) {
                dimAnimator.cancel();
            }
            float startDim = isTransparent ? 0.5f : 0.0f;
            dimAnimator = android.animation.ValueAnimator.ofFloat(startDim, targetDim);
            dimAnimator.setDuration(180);
            dimAnimator.addUpdateListener(animation -> {
                if (getWindow() != null) {
                    getWindow().setDimAmount((float) animation.getAnimatedValue());
                }
            });
            dimAnimator.start();
        }
    }
}
