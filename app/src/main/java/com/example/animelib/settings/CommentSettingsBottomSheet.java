package com.example.animelib.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.animelib.R;
import com.example.animelib.data.AppSettings;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.example.animelib.views.ExpressiveSlider;
import com.example.animelib.views.SliderLabelsView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

public class CommentSettingsBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnCommentSettingsListener {
        void onCommentsInPlayerToggled(boolean disabled);
        void onRepliesLimitChanged(int limit);
    }

    private final DatabaseManager databaseManager;
    private final OnCommentSettingsListener listener;

    private boolean disableCommentsInPlayer;
    private int repliesLimit; // 1..10, or -1 (unlimited)

    private MaterialSwitch switchDisableComments;
    private TextView tvDisableCommentsSubtext;
    private TextView tvRepliesLimitValue;
    private TextView tvRepliesLimitSubtext;
    private ExpressiveSlider sliderRepliesLimit;
    private SliderLabelsView sliderLabelsView;

    private TextView presetReply1;
    private TextView presetReply2;
    private TextView presetReply3;
    private TextView presetReply5;
    private TextView presetReply10;
    private TextView presetReplyUnlimited;

    public CommentSettingsBottomSheet(@NonNull Context context, @Nullable OnCommentSettingsListener listener) {
        super(context, R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.databaseManager = DatabaseManager.getInstance(context);
        this.listener = listener;
        this.disableCommentsInPlayer = databaseManager.loadDisableCommentsInPlayerSetting();
        this.repliesLimit = databaseManager.loadCommentRepliesLimitSetting();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_comment_settings, null);
        setContentView(view);
        FloatingBottomSheetUtils.setupFloatingStyle(this);

        ImageButton btnCloseSettings = view.findViewById(R.id.btnCloseSettings);
        if (btnCloseSettings != null) {
            btnCloseSettings.setOnClickListener(v -> dismiss());
        }

        View cardDisableComments = view.findViewById(R.id.cardDisableComments);
        switchDisableComments = view.findViewById(R.id.switchDisableComments);
        tvDisableCommentsSubtext = view.findViewById(R.id.tvDisableCommentsSubtext);

        tvRepliesLimitValue = view.findViewById(R.id.tvRepliesLimitValue);
        tvRepliesLimitSubtext = view.findViewById(R.id.tvRepliesLimitSubtext);
        sliderRepliesLimit = view.findViewById(R.id.sliderRepliesLimit);
        sliderLabelsView = view.findViewById(R.id.sliderLabelsView);

        presetReply1 = view.findViewById(R.id.presetReply1);
        presetReply2 = view.findViewById(R.id.presetReply2);
        presetReply3 = view.findViewById(R.id.presetReply3);
        presetReply5 = view.findViewById(R.id.presetReply5);
        presetReply10 = view.findViewById(R.id.presetReply10);
        presetReplyUnlimited = view.findViewById(R.id.presetReplyUnlimited);

        // Setup comments switch
        if (switchDisableComments != null) {
            switchDisableComments.setChecked(disableCommentsInPlayer);
            updateDisableCommentsUI(disableCommentsInPlayer);

            switchDisableComments.setOnCheckedChangeListener((buttonView, isChecked) -> {
                disableCommentsInPlayer = isChecked;
                updateDisableCommentsUI(isChecked);
                new Thread(() -> {
                    try {
                        databaseManager.saveDisableCommentsInPlayerSetting(isChecked);
                    } catch (Exception ignored) {}
                }).start();
                if (listener != null) {
                    listener.onCommentsInPlayerToggled(isChecked);
                }
            });
        }

        if (cardDisableComments != null && switchDisableComments != null) {
            cardDisableComments.setOnClickListener(v -> {
                switchDisableComments.toggle();
            });
        }

        // Setup slider & labels view
        float initialSliderVal = getSliderValueFromLimit(repliesLimit);
        if (sliderRepliesLimit != null) {
            sliderRepliesLimit.setValue(initialSliderVal);
            sliderRepliesLimit.addOnChangeListener((slider, value, fromUser) -> {
                int intVal = Math.round(value);
                int newLimit = getLimitFromSliderValue(intVal);
                if (newLimit != this.repliesLimit) {
                    this.repliesLimit = newLimit;
                    updateLimitUI(newLimit);
                    if (sliderLabelsView != null) {
                        sliderLabelsView.setCurrentValue(newLimit);
                    }
                }
            });
            sliderRepliesLimit.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) {}

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    applyLimitChange(repliesLimit);
                }
            });
        }

        if (sliderLabelsView != null) {
            sliderLabelsView.setCurrentValue(repliesLimit);
            sliderLabelsView.setOnLabelClickListener(this::selectLimit);
        }

        updateLimitUI(repliesLimit);

        // Setup preset chips
        setupPreset(presetReply1, 1);
        setupPreset(presetReply2, 2);
        setupPreset(presetReply3, 3);
        setupPreset(presetReply5, 5);
        setupPreset(presetReply10, 10);
        setupPreset(presetReplyUnlimited, AppSettings.REPLIES_UNLIMITED);
    }

    private void selectLimit(int targetLimit) {
        this.repliesLimit = targetLimit;
        float sliderVal = getSliderValueFromLimit(targetLimit);
        if (sliderRepliesLimit != null) {
            sliderRepliesLimit.setValue(sliderVal);
        }
        if (sliderLabelsView != null) {
            sliderLabelsView.setCurrentValue(targetLimit);
        }
        updateLimitUI(targetLimit);
        applyLimitChange(targetLimit);
    }

    private void setupPreset(TextView presetView, int targetLimit) {
        if (presetView == null) return;
        presetView.setOnClickListener(v -> selectLimit(targetLimit));
    }

    private void applyLimitChange(int limit) {
        new Thread(() -> {
            try {
                databaseManager.saveCommentRepliesLimitSetting(limit);
            } catch (Exception ignored) {}
        }).start();
        if (listener != null) {
            listener.onRepliesLimitChanged(limit);
        }
    }

    @Override
    public void dismiss() {
        applyLimitChange(repliesLimit);
        super.dismiss();
    }

    private float getSliderValueFromLimit(int limit) {
        if (limit == AppSettings.REPLIES_UNLIMITED || limit < 0 || limit >= 11) {
            return 11.0f;
        }
        if (limit < 1) return 1.0f;
        return (float) limit;
    }

    private int getLimitFromSliderValue(int sliderVal) {
        if (sliderVal >= 11) {
            return AppSettings.REPLIES_UNLIMITED;
        }
        return sliderVal;
    }

    private void updateDisableCommentsUI(boolean disabled) {
        if (tvDisableCommentsSubtext != null) {
            if (disabled) {
                tvDisableCommentsSubtext.setText("Кнопка и панель комментариев в плеере скрыты");
            } else {
                tvDisableCommentsSubtext.setText("Кнопка и панель комментариев в плеере активны");
            }
        }
    }

    private void updateLimitUI(int limit) {
        boolean isUnlimited = (limit == AppSettings.REPLIES_UNLIMITED || limit < 0 || limit >= 11);

        if (tvRepliesLimitValue != null) {
            if (isUnlimited) {
                tvRepliesLimitValue.setText("Неограниченно");
            } else {
                tvRepliesLimitValue.setText(formatRepliesPlural(limit));
            }
        }

        if (tvRepliesLimitSubtext != null) {
            if (isUnlimited) {
                tvRepliesLimitSubtext.setText("Все ответы отображаются сразу без скрытия");
            } else {
                tvRepliesLimitSubtext.setText("Остальные ответы скрыты под кнопкой «Показать ещё»");
            }
        }

        // Highlight active preset chip
        updatePresetChip(presetReply1, limit == 1);
        updatePresetChip(presetReply2, limit == 2);
        updatePresetChip(presetReply3, limit == 3);
        updatePresetChip(presetReply5, limit == 5);
        updatePresetChip(presetReply10, limit == 10);
        updatePresetChip(presetReplyUnlimited, isUnlimited);

        if (sliderLabelsView != null) {
            sliderLabelsView.setCurrentValue(limit);
        }
    }

    private void updatePresetChip(TextView chip, boolean isSelected) {
        if (chip == null) return;
        chip.setBackgroundResource(isSelected ? R.drawable.chip_selected : R.drawable.chip_unselected);
        int textColor = isSelected
                ? ContextCompat.getColor(getContext(), R.color.purple_primary)
                : ContextCompat.getColor(getContext(), R.color.primary_text_color);
        chip.setTextColor(textColor);
    }

    public static String formatRepliesPlural(int n) {
        int rem10 = n % 10;
        int rem100 = n % 100;
        if (rem100 >= 11 && rem100 <= 19) return n + " ответов";
        if (rem10 == 1) return n + " ответ";
        if (rem10 >= 2 && rem10 <= 4) return n + " ответа";
        return n + " ответов";
    }
}
