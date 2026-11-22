package com.example.animelib.settings;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.example.animelib.R;
import com.example.animelib.controllers.OpeningAudioDetectorController;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

public class SkipDurationBottomSheet extends FlexibleBottomSheetDialog {
    private static final int TILE_CORNER_OUTER_INACTIVE_DP = 20;
    private static final int TILE_CORNER_INNER_INACTIVE_DP = 8;
    private static final int TILE_CORNER_ACTIVE_PILL_DP = 26;
    private static final long TILE_MORPH_DURATION_MS = 220L;

    private int currentDuration; // in seconds
    private boolean autoSkipOpening;
    private boolean autoSkipEnding;
    private boolean autoSkipSplashscreen;
    private boolean autoSkipCompilation;
    private boolean openingDetectionEnabled;

    private final OnDurationChangedListener listener;
    private final OnAutoSkipChangedListener autoSkipListener;
    private final OnOpeningDetectionChangedListener openingDetectionListener;
    private OnBackPressedListener onBackPressedListener;

    private TextView digitalTimeText;
    private TextView digitalTimeSubtext;
    private Slider timeSlider;

    private TextView preset30, preset60, preset85, preset90, preset110, preset120;

    public SkipDurationBottomSheet(Context context, int currentDuration, OnDurationChangedListener listener) {
        this(context, currentDuration, false, false, false, false,
                OpeningAudioDetectorController.isOpeningDetectionEnabled(context),
                listener, null, null);
    }

    public SkipDurationBottomSheet(Context context,
                                   int currentDuration,
                                   boolean autoSkipOpening,
                                   boolean autoSkipEnding,
                                   boolean autoSkipSplashscreen,
                                   boolean autoSkipCompilation,
                                   OnDurationChangedListener listener,
                                   OnAutoSkipChangedListener autoSkipListener) {
        this(context, currentDuration, autoSkipOpening, autoSkipEnding, autoSkipSplashscreen, autoSkipCompilation,
                OpeningAudioDetectorController.isOpeningDetectionEnabled(context),
                listener, autoSkipListener, null);
    }

    public SkipDurationBottomSheet(Context context,
                                   int currentDuration,
                                   boolean autoSkipOpening,
                                   boolean autoSkipEnding,
                                   boolean autoSkipSplashscreen,
                                   boolean autoSkipCompilation,
                                   boolean openingDetectionEnabled,
                                   OnDurationChangedListener listener,
                                   OnAutoSkipChangedListener autoSkipListener,
                                   OnOpeningDetectionChangedListener openingDetectionListener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.currentDuration = currentDuration;
        this.autoSkipOpening = autoSkipOpening;
        this.autoSkipEnding = autoSkipEnding;
        this.autoSkipSplashscreen = autoSkipSplashscreen;
        this.autoSkipCompilation = autoSkipCompilation;
        this.openingDetectionEnabled = openingDetectionEnabled;
        this.listener = listener;
        this.autoSkipListener = autoSkipListener;
        this.openingDetectionListener = openingDetectionListener;
    }

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    public interface OnDurationChangedListener {
        void onDurationChanged(int durationInSeconds);
    }

    public interface OnAutoSkipChangedListener {
        void onAutoSkipChanged(boolean opening, boolean ending, boolean splashscreen, boolean compilation);
    }

    public interface OnOpeningDetectionChangedListener {
        void onOpeningDetectionChanged(boolean enabled);
    }

    private enum GridCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private static final class ExpressiveTileHolder {
        final TextView view;
        final GradientDrawable shape;
        final RippleDrawable ripple;
        final float[] inactiveRadii;
        final float activeRadius;
        float progress;
        ValueAnimator animator;

        ExpressiveTileHolder(TextView view,
                             GradientDrawable shape,
                             RippleDrawable ripple,
                             float[] inactiveRadii,
                             float activeRadius,
                             float initialProgress) {
            this.view = view;
            this.shape = shape;
            this.ripple = ripple;
            this.inactiveRadii = inactiveRadii;
            this.activeRadius = activeRadius;
            this.progress = initialProgress;
        }
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

        // 2x2 M3 Expressive Auto-skip Tiles
        TextView tileOpening = view.findViewById(R.id.tileAutoSkipOpening);
        TextView tileEnding = view.findViewById(R.id.tileAutoSkipEnding);
        TextView tileSplashscreen = view.findViewById(R.id.tileAutoSkipSplashscreen);
        TextView tileCompilation = view.findViewById(R.id.tileAutoSkipCompilation);

        bindAutoSkipTile(tileOpening, GridCorner.TOP_LEFT, autoSkipOpening, isChecked -> {
            autoSkipOpening = isChecked;
            notifyAutoSkipChanged();
        });
        bindAutoSkipTile(tileEnding, GridCorner.TOP_RIGHT, autoSkipEnding, isChecked -> {
            autoSkipEnding = isChecked;
            notifyAutoSkipChanged();
        });
        bindAutoSkipTile(tileSplashscreen, GridCorner.BOTTOM_LEFT, autoSkipSplashscreen, isChecked -> {
            autoSkipSplashscreen = isChecked;
            notifyAutoSkipChanged();
        });
        bindAutoSkipTile(tileCompilation, GridCorner.BOTTOM_RIGHT, autoSkipCompilation, isChecked -> {
            autoSkipCompilation = isChecked;
            notifyAutoSkipChanged();
        });

        // Smart Opening Detection Switch Card
        View rowOpeningDetection = view.findViewById(R.id.rowOpeningDetection);
        MaterialSwitch switchOpeningDetection = view.findViewById(R.id.switchOpeningDetection);
        if (switchOpeningDetection != null) {
            switchOpeningDetection.setChecked(openingDetectionEnabled);
            switchOpeningDetection.setOnCheckedChangeListener((buttonView, isChecked) -> {
                openingDetectionEnabled = isChecked;
                OpeningAudioDetectorController.setOpeningDetectionEnabled(getContext(), isChecked);
                if (openingDetectionListener != null) {
                    openingDetectionListener.onOpeningDetectionChanged(isChecked);
                }
            });
            if (rowOpeningDetection != null) {
                rowOpeningDetection.setOnClickListener(v -> switchOpeningDetection.toggle());
            }
        }

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

    private interface OnTileToggled {
        void onToggled(boolean active);
    }

    private void bindAutoSkipTile(TextView tileView,
                                  GridCorner corner,
                                  boolean initialActive,
                                  OnTileToggled onToggled) {
        if (tileView == null || getContext() == null) return;

        final float density = getContext().getResources().getDisplayMetrics().density;
        final float outerPx = TILE_CORNER_OUTER_INACTIVE_DP * density;
        final float innerPx = TILE_CORNER_INNER_INACTIVE_DP * density;
        final float pillPx = TILE_CORNER_ACTIVE_PILL_DP * density;

        float tl = corner == GridCorner.TOP_LEFT ? outerPx : innerPx;
        float tr = corner == GridCorner.TOP_RIGHT ? outerPx : innerPx;
        float br = corner == GridCorner.BOTTOM_RIGHT ? outerPx : innerPx;
        float bl = corner == GridCorner.BOTTOM_LEFT ? outerPx : innerPx;
        float[] inactiveRadii = new float[]{tl, tl, tr, tr, br, br, bl, bl};

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);

        int rippleColor = ContextCompat.getColor(getContext(),
                initialActive ? R.color.dialog_ripple_color : R.color.dialog_ripple_color);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null);
        tileView.setBackground(ripple);
        tileView.setSelected(initialActive);

        ExpressiveTileHolder holder = new ExpressiveTileHolder(
                tileView, shape, ripple, inactiveRadii, pillPx, initialActive ? 1f : 0f
        );
        applyTileVisualState(holder, initialActive ? 1f : 0f);

        tileView.setOnClickListener(v -> {
            boolean newState = !tileView.isSelected();
            tileView.setSelected(newState);
            animateTileState(holder, newState);
            if (onToggled != null) {
                onToggled.onToggled(newState);
            }
        });
    }

    private void animateTileState(ExpressiveTileHolder holder, boolean active) {
        if (holder.animator != null) {
            holder.animator.cancel();
        }
        float start = holder.progress;
        float end = active ? 1f : 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(start, end);
        animator.setDuration(TILE_MORPH_DURATION_MS);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(anim -> {
            float fraction = (float) anim.getAnimatedValue();
            applyTileVisualState(holder, fraction);
        });
        holder.animator = animator;
        animator.start();
    }

    private void applyTileVisualState(ExpressiveTileHolder holder, float progress) {
        if (getContext() == null) return;
        holder.progress = progress;

        float[] radii = new float[8];
        for (int i = 0; i < 8; i++) {
            radii[i] = holder.inactiveRadii[i] + (holder.activeRadius - holder.inactiveRadii[i]) * progress;
        }
        holder.shape.setCornerRadii(radii);

        int inactiveBg = ContextCompat.getColor(getContext(), R.color.card_bg_color);
        int activeBg = ContextCompat.getColor(getContext(), R.color.purple_primary);
        int inactiveText = ContextCompat.getColor(getContext(), R.color.primary_text_color);
        int activeText = ContextCompat.getColor(getContext(), R.color.white_color);

        ArgbEvaluator evaluator = new ArgbEvaluator();
        int currentBg = (int) evaluator.evaluate(progress, inactiveBg, activeBg);
        int currentText = (int) evaluator.evaluate(progress, inactiveText, activeText);

        holder.shape.setColor(currentBg);
        holder.view.setTextColor(currentText);

        int rippleColor = progress >= 0.5f
                ? 0x38FFFFFF
                : ContextCompat.getColor(getContext(), R.color.dialog_ripple_color);
        holder.ripple.setColor(ColorStateList.valueOf(rippleColor));
    }

    private void notifyAutoSkipChanged() {
        if (autoSkipListener != null) {
            autoSkipListener.onAutoSkipChanged(autoSkipOpening, autoSkipEnding, autoSkipSplashscreen, autoSkipCompilation);
        }
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
        if (presetView == null || getContext() == null) return;
        if (presetVal == currentVal) {
            presetView.setBackgroundResource(R.drawable.chip_selected);
            presetView.setTextColor(ContextCompat.getColor(getContext(), R.color.purple_primary));
        } else {
            presetView.setBackgroundResource(R.drawable.chip_unselected);
            presetView.setTextColor(ContextCompat.getColor(getContext(), R.color.primary_text_color));
        }
    }

    public void setOnBackPressedListener(OnBackPressedListener listener) {
        this.onBackPressedListener = listener;
        setOnBackPressedAction(listener != null ? listener::onBackPressed : null);
    }
}
