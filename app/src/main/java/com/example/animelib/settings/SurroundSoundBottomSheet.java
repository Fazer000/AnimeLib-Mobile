package com.example.animelib.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.example.animelib.R;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.Surround51AudioProcessor;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SurroundSoundBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnSurroundChangedListener {
        void onSurroundChanged(boolean enabled, int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost);
    }

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    private boolean isEnabled = true;
    private int currentMode = Surround51AudioProcessor.MODE_CINEMA_3D;
    private float currentSpatialWidth = 1.0f;
    private float currentDialogueBoost = 1.0f;
    private float currentBassBoost = 1.0f;
    private float currentTrebleBoost = 1.0f;

    private final OnSurroundChangedListener listener;
    private OnBackPressedListener onBackPressedListener;

    private MaterialSwitch mainSwitch;
    private LinearLayout controlsContainer;

    private View pillCinema, pillConcert, pillVoice, pillClassic, pillExtreme;
    private View unselectedCinema, unselectedConcert, unselectedVoice, unselectedClassic, unselectedExtreme;
    private TextView[] widthChips = new TextView[4];
    private TextView[] dialogueChips = new TextView[4];
    private TextView[] bassChips = new TextView[4];
    private TextView[] trebleChips = new TextView[4];

    private final float[] widthValues = {0.7f, 1.0f, 1.4f, 1.8f};
    private final float[] dialogueValues = {0.7f, 1.0f, 1.4f, 1.8f};
    private final float[] bassValues = {0.7f, 1.0f, 1.4f, 1.8f};
    private final float[] trebleValues = {0.7f, 1.0f, 1.4f, 1.8f};

    public SurroundSoundBottomSheet(@NonNull Context context,
                                    boolean isEnabled,
                                    int mode,
                                    float spatialWidth,
                                    float dialogueBoost,
                                    float bassBoost,
                                    float trebleBoost,
                                    OnSurroundChangedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.isEnabled = isEnabled;
        this.currentMode = mode;
        this.currentSpatialWidth = spatialWidth;
        this.currentDialogueBoost = dialogueBoost;
        this.currentBassBoost = bassBoost;
        this.currentTrebleBoost = trebleBoost;
        this.listener = listener;
    }

    public void setOnBackPressedListener(OnBackPressedListener onBackPressedListener) {
        this.onBackPressedListener = onBackPressedListener;
    }

    @SuppressLint("InflateParams")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_surround_sound, null);
        setContentView(view);
        FloatingBottomSheetUtils.setupFloatingStyle(this);

        ImageButton backButton = view.findViewById(R.id.backButton);
        ImageButton closeButton = view.findViewById(R.id.closeButton);
        mainSwitch = view.findViewById(R.id.surroundMainSwitch);
        controlsContainer = view.findViewById(R.id.surroundControlsContainer);

        pillCinema = view.findViewById(R.id.pillCinema);
        pillConcert = view.findViewById(R.id.pillConcert);
        pillVoice = view.findViewById(R.id.pillVoice);
        pillClassic = view.findViewById(R.id.pillClassic);
        pillExtreme = view.findViewById(R.id.pillExtreme);

        unselectedCinema = view.findViewById(R.id.unselectedCinema);
        unselectedConcert = view.findViewById(R.id.unselectedConcert);
        unselectedVoice = view.findViewById(R.id.unselectedVoice);
        unselectedClassic = view.findViewById(R.id.unselectedClassic);
        unselectedExtreme = view.findViewById(R.id.unselectedExtreme);

        widthChips[0] = view.findViewById(R.id.chipWidth0);
        widthChips[1] = view.findViewById(R.id.chipWidth1);
        widthChips[2] = view.findViewById(R.id.chipWidth2);
        widthChips[3] = view.findViewById(R.id.chipWidth3);

        dialogueChips[0] = view.findViewById(R.id.chipDialogue0);
        dialogueChips[1] = view.findViewById(R.id.chipDialogue1);
        dialogueChips[2] = view.findViewById(R.id.chipDialogue2);
        dialogueChips[3] = view.findViewById(R.id.chipDialogue3);

        bassChips[0] = view.findViewById(R.id.chipBass0);
        bassChips[1] = view.findViewById(R.id.chipBass1);
        bassChips[2] = view.findViewById(R.id.chipBass2);
        bassChips[3] = view.findViewById(R.id.chipBass3);

        trebleChips[0] = view.findViewById(R.id.chipTreble0);
        trebleChips[1] = view.findViewById(R.id.chipTreble1);
        trebleChips[2] = view.findViewById(R.id.chipTreble2);
        trebleChips[3] = view.findViewById(R.id.chipTreble3);

        backButton.setOnClickListener(v -> {
            dismiss();
            if (onBackPressedListener != null) {
                onBackPressedListener.onBackPressed();
            }
        });

        closeButton.setOnClickListener(v -> dismiss());

        mainSwitch.setChecked(isEnabled);
        updateControlsVisibility(isEnabled);

        mainSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isEnabled = isChecked;
            updateControlsVisibility(isChecked);
            notifyChanges();
        });

        // Mode option click listeners
        view.findViewById(R.id.modeCinemaOption).setOnClickListener(v -> selectMode(Surround51AudioProcessor.MODE_CINEMA_3D));
        view.findViewById(R.id.modeConcertOption).setOnClickListener(v -> selectMode(Surround51AudioProcessor.MODE_CONCERT_3D));
        view.findViewById(R.id.modeVoiceOption).setOnClickListener(v -> selectMode(Surround51AudioProcessor.MODE_VOICE_3D));
        view.findViewById(R.id.modeClassicOption).setOnClickListener(v -> selectMode(Surround51AudioProcessor.MODE_CLASSIC_51));
        view.findViewById(R.id.modeExtremeOption).setOnClickListener(v -> selectMode(Surround51AudioProcessor.MODE_EXTREME_3D));

        updateModeUI();

        // Width chip clicks
        for (int i = 0; i < 4; i++) {
            final int index = i;
            widthChips[i].setOnClickListener(v -> {
                currentSpatialWidth = widthValues[index];
                updateWidthChipsUI();
                notifyChanges();
            });
        }
        updateWidthChipsUI();

        // Dialogue chip clicks
        for (int i = 0; i < 4; i++) {
            final int index = i;
            dialogueChips[i].setOnClickListener(v -> {
                currentDialogueBoost = dialogueValues[index];
                updateDialogueChipsUI();
                notifyChanges();
            });
        }
        updateDialogueChipsUI();

        // Bass chip clicks
        for (int i = 0; i < 4; i++) {
            final int index = i;
            bassChips[i].setOnClickListener(v -> {
                currentBassBoost = bassValues[index];
                updateBassChipsUI();
                notifyChanges();
            });
        }
        updateBassChipsUI();

        // Treble chip clicks
        for (int i = 0; i < 4; i++) {
            final int index = i;
            trebleChips[i].setOnClickListener(v -> {
                currentTrebleBoost = trebleValues[index];
                updateTrebleChipsUI();
                notifyChanges();
            });
        }
        updateTrebleChipsUI();
    }

    private void updateControlsVisibility(boolean enabled) {
        if (controlsContainer != null) {
            controlsContainer.setAlpha(enabled ? 1.0f : 0.4f);
            for (int i = 0; i < controlsContainer.getChildCount(); i++) {
                View child = controlsContainer.getChildAt(i);
                child.setEnabled(enabled);
            }
        }
    }

    private void selectMode(int mode) {
        currentMode = mode;
        updateModeUI();
        notifyChanges();
    }

    private void updateModeUI() {
        boolean isCinema = (currentMode == Surround51AudioProcessor.MODE_CINEMA_3D);
        boolean isConcert = (currentMode == Surround51AudioProcessor.MODE_CONCERT_3D);
        boolean isVoice = (currentMode == Surround51AudioProcessor.MODE_VOICE_3D);
        boolean isClassic = (currentMode == Surround51AudioProcessor.MODE_CLASSIC_51);
        boolean isExtreme = (currentMode == Surround51AudioProcessor.MODE_EXTREME_3D);

        if (pillCinema != null) pillCinema.setVisibility(isCinema ? View.VISIBLE : View.GONE);
        if (unselectedCinema != null) unselectedCinema.setVisibility(isCinema ? View.GONE : View.VISIBLE);

        if (pillConcert != null) pillConcert.setVisibility(isConcert ? View.VISIBLE : View.GONE);
        if (unselectedConcert != null) unselectedConcert.setVisibility(isConcert ? View.GONE : View.VISIBLE);

        if (pillVoice != null) pillVoice.setVisibility(isVoice ? View.VISIBLE : View.GONE);
        if (unselectedVoice != null) unselectedVoice.setVisibility(isVoice ? View.GONE : View.VISIBLE);

        if (pillClassic != null) pillClassic.setVisibility(isClassic ? View.VISIBLE : View.GONE);
        if (unselectedClassic != null) unselectedClassic.setVisibility(isClassic ? View.GONE : View.VISIBLE);

        if (pillExtreme != null) pillExtreme.setVisibility(isExtreme ? View.VISIBLE : View.GONE);
        if (unselectedExtreme != null) unselectedExtreme.setVisibility(isExtreme ? View.GONE : View.VISIBLE);
    }

    private void updateWidthChipsUI() {
        int selectedIdx = getClosestIndex(currentSpatialWidth, widthValues);
        int selectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.secondary_text_color);
        int unselectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.primary_text_color);
        for (int i = 0; i < 4; i++) {
            if (widthChips[i] != null) {
                boolean isSelected = (i == selectedIdx);
                widthChips[i].setBackgroundResource(isSelected ? R.drawable.chip_selected : R.drawable.chip_unselected);
                widthChips[i].setTextColor(isSelected ? selectedColor : unselectedColor);
            }
        }
    }

    private void updateDialogueChipsUI() {
        int selectedIdx = getClosestIndex(currentDialogueBoost, dialogueValues);
        int selectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.secondary_text_color);
        int unselectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.primary_text_color);
        for (int i = 0; i < 4; i++) {
            if (dialogueChips[i] != null) {
                boolean isSelected = (i == selectedIdx);
                dialogueChips[i].setBackgroundResource(isSelected ? R.drawable.chip_selected : R.drawable.chip_unselected);
                dialogueChips[i].setTextColor(isSelected ? selectedColor : unselectedColor);
            }
        }
    }

    private void updateBassChipsUI() {
        int selectedIdx = getClosestIndex(currentBassBoost, bassValues);
        int selectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.secondary_text_color);
        int unselectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.primary_text_color);
        for (int i = 0; i < 4; i++) {
            if (bassChips[i] != null) {
                boolean isSelected = (i == selectedIdx);
                bassChips[i].setBackgroundResource(isSelected ? R.drawable.chip_selected : R.drawable.chip_unselected);
                bassChips[i].setTextColor(isSelected ? selectedColor : unselectedColor);
            }
        }
    }

    private void updateTrebleChipsUI() {
        int selectedIdx = getClosestIndex(currentTrebleBoost, trebleValues);
        int selectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.secondary_text_color);
        int unselectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.primary_text_color);
        for (int i = 0; i < 4; i++) {
            if (trebleChips[i] != null) {
                boolean isSelected = (i == selectedIdx);
                trebleChips[i].setBackgroundResource(isSelected ? R.drawable.chip_selected : R.drawable.chip_unselected);
                trebleChips[i].setTextColor(isSelected ? selectedColor : unselectedColor);
            }
        }
    }

    private int getClosestIndex(float value, float[] array) {
        int bestIdx = 0;
        float minDiff = Math.abs(value - array[0]);
        for (int i = 1; i < array.length; i++) {
            float diff = Math.abs(value - array[i]);
            if (diff < minDiff) {
                minDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private void notifyChanges() {
        if (listener != null) {
            listener.onSurroundChanged(isEnabled, currentMode, currentSpatialWidth, currentDialogueBoost, currentBassBoost, currentTrebleBoost);
        }
    }
}
