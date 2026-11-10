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
import com.google.android.material.materialswitch.MaterialSwitch;

public class SurroundSoundBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnSurroundChangedListener {
        void onSurroundChanged(boolean enabled, int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost, float midBoost);
        default void onSurroundChanged(boolean enabled, int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost) {
            onSurroundChanged(enabled, mode, spatialWidth, dialogueBoost, bassBoost, trebleBoost, 1.0f);
        }
    }

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    private boolean isEnabled = true;
    private int currentMode = Surround51AudioProcessor.MODE_CINEMA_3D;
    private float currentSpatialWidth = 1.0f;
    private float currentDialogueBoost = 1.0f;
    private float currentBassBoost = 1.0f;
    private float currentMidBoost = 1.0f;
    private float currentTrebleBoost = 1.0f;

    private final OnSurroundChangedListener listener;
    private OnBackPressedListener onBackPressedListener;

    private MaterialSwitch mainSwitch;
    private LinearLayout controlsContainer;

    private View pillCinema, pillConcert, pillVoice;
    private View unselectedCinema, unselectedConcert, unselectedVoice;
    private TextView[] widthChips = new TextView[4];
    private TextView[] dialogueChips = new TextView[4];
    private TextView[] bassChips = new TextView[4];
    private TextView[] midsChips = new TextView[4];
    private TextView[] trebleChips = new TextView[4];

    private final float[] widthValues = {0.7f, 1.0f, 1.4f, 1.8f};
    private final float[] dialogueValues = {0.7f, 1.0f, 1.4f, 1.8f};
    private final float[] bassValues = {0.7f, 1.0f, 1.4f, 1.8f};
    private final float[] midsValues = {0.7f, 1.0f, 1.4f, 1.8f};
    private final float[] trebleValues = {0.7f, 1.0f, 1.4f, 1.8f};

    public SurroundSoundBottomSheet(@NonNull Context context,
                                    boolean isEnabled,
                                    int mode,
                                    float spatialWidth,
                                    float dialogueBoost,
                                    float bassBoost,
                                    float trebleBoost,
                                    OnSurroundChangedListener listener) {
        this(context, isEnabled, mode, spatialWidth, dialogueBoost, bassBoost, 1.0f, trebleBoost, listener);
    }

    public SurroundSoundBottomSheet(@NonNull Context context,
                                    boolean isEnabled,
                                    int mode,
                                    float spatialWidth,
                                    float dialogueBoost,
                                    float bassBoost,
                                    float midBoost,
                                    float trebleBoost,
                                    OnSurroundChangedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.isEnabled = isEnabled;
        this.currentMode = mode;
        this.currentSpatialWidth = spatialWidth;
        this.currentDialogueBoost = dialogueBoost;
        this.currentBassBoost = bassBoost;
        this.currentMidBoost = midBoost;
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
        pillVoice = view.findViewById(R.id.pillVoice);
        pillConcert = view.findViewById(R.id.pillConcert);

        unselectedCinema = view.findViewById(R.id.unselectedCinema);
        unselectedVoice = view.findViewById(R.id.unselectedVoice);
        unselectedConcert = view.findViewById(R.id.unselectedConcert);

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

        midsChips[0] = view.findViewById(R.id.chipMids0);
        midsChips[1] = view.findViewById(R.id.chipMids1);
        midsChips[2] = view.findViewById(R.id.chipMids2);
        midsChips[3] = view.findViewById(R.id.chipMids3);

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

        // Mode option click listeners (Cinema, Voice, Concert)
        View modeCinemaOption = view.findViewById(R.id.modeCinemaOption);
        if (modeCinemaOption != null) {
            modeCinemaOption.setOnClickListener(v -> selectMode(Surround51AudioProcessor.MODE_CINEMA_3D));
        }

        View modeVoiceOption = view.findViewById(R.id.modeVoiceOption);
        if (modeVoiceOption != null) {
            modeVoiceOption.setOnClickListener(v -> selectMode(Surround51AudioProcessor.MODE_VOICE_3D));
        }

        View modeConcertOption = view.findViewById(R.id.modeConcertOption);
        if (modeConcertOption != null) {
            modeConcertOption.setOnClickListener(v -> selectMode(Surround51AudioProcessor.MODE_CONCERT_3D));
        }

        updateModeUI();

        // Width chip clicks
        for (int i = 0; i < 4; i++) {
            final int index = i;
            if (widthChips[i] != null) {
                widthChips[i].setOnClickListener(v -> {
                    currentSpatialWidth = widthValues[index];
                    updateWidthChipsUI();
                    notifyChanges();
                });
            }
        }
        updateWidthChipsUI();

        // Dialogue chip clicks
        for (int i = 0; i < 4; i++) {
            final int index = i;
            if (dialogueChips[i] != null) {
                dialogueChips[i].setOnClickListener(v -> {
                    currentDialogueBoost = dialogueValues[index];
                    updateDialogueChipsUI();
                    notifyChanges();
                });
            }
        }
        updateDialogueChipsUI();

        // Bass chip clicks
        for (int i = 0; i < 4; i++) {
            final int index = i;
            if (bassChips[i] != null) {
                bassChips[i].setOnClickListener(v -> {
                    currentBassBoost = bassValues[index];
                    updateBassChipsUI();
                    notifyChanges();
                });
            }
        }
        updateBassChipsUI();

        // Mids chip clicks
        for (int i = 0; i < 4; i++) {
            final int index = i;
            if (midsChips[i] != null) {
                midsChips[i].setOnClickListener(v -> {
                    currentMidBoost = midsValues[index];
                    updateMidsChipsUI();
                    notifyChanges();
                });
            }
        }
        updateMidsChipsUI();

        // Treble chip clicks
        for (int i = 0; i < 4; i++) {
            final int index = i;
            if (trebleChips[i] != null) {
                trebleChips[i].setOnClickListener(v -> {
                    currentTrebleBoost = trebleValues[index];
                    updateTrebleChipsUI();
                    notifyChanges();
                });
            }
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
        boolean isVoice = (currentMode == Surround51AudioProcessor.MODE_VOICE_3D);
        boolean isConcert = (currentMode == Surround51AudioProcessor.MODE_CONCERT_3D);

        if (pillCinema != null) pillCinema.setVisibility(isCinema ? View.VISIBLE : View.GONE);
        if (unselectedCinema != null) unselectedCinema.setVisibility(isCinema ? View.GONE : View.VISIBLE);

        if (pillVoice != null) pillVoice.setVisibility(isVoice ? View.VISIBLE : View.GONE);
        if (unselectedVoice != null) unselectedVoice.setVisibility(isVoice ? View.GONE : View.VISIBLE);

        if (pillConcert != null) pillConcert.setVisibility(isConcert ? View.VISIBLE : View.GONE);
        if (unselectedConcert != null) unselectedConcert.setVisibility(isConcert ? View.GONE : View.VISIBLE);
    }

    private void updateWidthChipsUI() {
        int selectedIdx = getClosestIndex(currentSpatialWidth, widthValues);
        int selectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.purple_primary);
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
        int selectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.purple_primary);
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
        int selectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.purple_primary);
        int unselectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.primary_text_color);
        for (int i = 0; i < 4; i++) {
            if (bassChips[i] != null) {
                boolean isSelected = (i == selectedIdx);
                bassChips[i].setBackgroundResource(isSelected ? R.drawable.chip_selected : R.drawable.chip_unselected);
                bassChips[i].setTextColor(isSelected ? selectedColor : unselectedColor);
            }
        }
    }

    private void updateMidsChipsUI() {
        int selectedIdx = getClosestIndex(currentMidBoost, midsValues);
        int selectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.purple_primary);
        int unselectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.primary_text_color);
        for (int i = 0; i < 4; i++) {
            if (midsChips[i] != null) {
                boolean isSelected = (i == selectedIdx);
                midsChips[i].setBackgroundResource(isSelected ? R.drawable.chip_selected : R.drawable.chip_unselected);
                midsChips[i].setTextColor(isSelected ? selectedColor : unselectedColor);
            }
        }
    }

    private void updateTrebleChipsUI() {
        int selectedIdx = getClosestIndex(currentTrebleBoost, trebleValues);
        int selectedColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.purple_primary);
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
            listener.onSurroundChanged(isEnabled, currentMode, currentSpatialWidth, currentDialogueBoost, currentBassBoost, currentTrebleBoost, currentMidBoost);
        }
    }
}

