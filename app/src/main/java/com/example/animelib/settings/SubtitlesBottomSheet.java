package com.example.animelib.settings;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media3.ui.CaptionStyleCompat;

import com.example.animelib.R;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;
import java.util.Locale;

public class SubtitlesBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnSubtitlesToggledListener {
        void onSubtitlesToggled(boolean enabled);
    }

    public interface OnSubtitleFormatChangedListener {
        void onSubtitleFormatChanged(String format);
    }

    public interface OnSubtitleStyleChangedListener {
        void onSubtitleStyleChanged(float textSize, int textColor, int bgColor, int edgeType, int edgeColor);
    }

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    private boolean subtitlesEnabled;
    private String currentFormat; // "ass", "vtt", "auto"
    private float selectedTextSize = 18f;
    private int selectedTextColor = 0xFFFFFFFF;
    private int selectedBgColor = 0x00000000;
    private int selectedEdgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE;
    private int selectedEdgeColor = 0xFF000000;

    private final List<EpisodeResponse.SubtitleData> availableSubtitles;
    private final OnSubtitlesToggledListener toggledListener;
    private final OnSubtitleFormatChangedListener formatListener;
    private OnSubtitleStyleChangedListener styleListener;
    private OnBackPressedListener onBackPressedListener;

    private View pillAuto, pillAss, pillVtt;
    private View unselectedAuto, unselectedAss, unselectedVtt;
    private MaterialSwitch enableSwitch;
    private TextView subtitlePreviewText;
    private View subtitleAssLockBanner;
    private View subtitleStyleCustomizationContainer;

    private TextView chipSize14, chipSize16, chipSize18, chipSize22, chipSize26;
    private TextView chipColorWhite, chipColorYellow, chipColorCyan, chipColorGreen;
    private TextView chipBgNone, chipBgTranslucent, chipBgBlack;
    private TextView chipEdgeOutline, chipEdgeShadow, chipEdgeNone;

    public SubtitlesBottomSheet(@NonNull Context context,
                                boolean subtitlesEnabled,
                                String currentFormat,
                                List<EpisodeResponse.SubtitleData> availableSubtitles,
                                OnSubtitlesToggledListener toggledListener,
                                OnSubtitleFormatChangedListener formatListener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.subtitlesEnabled = subtitlesEnabled;
        this.currentFormat = currentFormat != null ? currentFormat : "ass";
        this.availableSubtitles = availableSubtitles;
        this.toggledListener = toggledListener;
        this.formatListener = formatListener;
    }

    public void setStyleSettings(float textSize, int textColor, int bgColor, int edgeType, int edgeColor,
                                 OnSubtitleStyleChangedListener styleListener) {
        this.selectedTextSize = textSize > 0 ? textSize : 18f;
        this.selectedTextColor = textColor;
        this.selectedBgColor = bgColor;
        this.selectedEdgeType = edgeType;
        this.selectedEdgeColor = edgeColor;
        this.styleListener = styleListener;
    }

    public void setOnBackPressedListener(OnBackPressedListener onBackPressedListener) {
        this.onBackPressedListener = onBackPressedListener;
        setOnBackPressedAction(onBackPressedListener != null ? onBackPressedListener::onBackPressed : null);
    }

    /**
     * Returns true when ASS/SSA subtitles are selected (explicitly "ass"/"ssa",
     * or "auto" when an ASS/SSA track is available or tracks are not yet enumerated).
     */
    public static boolean isAssCustomizationLocked(String format, List<EpisodeResponse.SubtitleData> available) {
        if ("ass".equalsIgnoreCase(format) || "ssa".equalsIgnoreCase(format)) {
            return true;
        }
        if ("vtt".equalsIgnoreCase(format)
                || "webvtt".equalsIgnoreCase(format)
                || "srt".equalsIgnoreCase(format)) {
            return false;
        }
        // "auto" prefers ASS if available
        if (available == null || available.isEmpty()) {
            return true;
        }
        for (EpisodeResponse.SubtitleData sub : available) {
            if (sub != null) {
                String fmt = sub.getFormat() != null ? sub.getFormat().trim().toLowerCase(Locale.ROOT) : "";
                String src = sub.getSrc() != null ? sub.getSrc().toLowerCase(Locale.ROOT) : "";
                if ("ass".equals(fmt) || "ssa".equals(fmt) || src.contains(".ass") || src.contains(".ssa")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAssSelected() {
        return isAssCustomizationLocked(currentFormat, availableSubtitles);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_subtitles_selection, null);
        setContentView(view);
        com.example.animelib.util.FloatingBottomSheetUtils.setupFloatingStyle(this);

        ImageButton backButton = view.findViewById(R.id.bs_subtitles_back);
        LinearLayout enableRow = view.findViewById(R.id.subtitlesEnableRow);
        enableSwitch = view.findViewById(R.id.subtitlesEnableSwitch);

        LinearLayout autoOption = view.findViewById(R.id.subtitlesAutoOption);
        LinearLayout assOption = view.findViewById(R.id.subtitlesAssOption);
        LinearLayout vttOption = view.findViewById(R.id.subtitlesVttOption);

        pillAuto = view.findViewById(R.id.pillAuto);
        pillAss = view.findViewById(R.id.pillAss);
        pillVtt = view.findViewById(R.id.pillVtt);

        unselectedAuto = view.findViewById(R.id.unselectedAuto);
        unselectedAss = view.findViewById(R.id.unselectedAss);
        unselectedVtt = view.findViewById(R.id.unselectedVtt);

        subtitlePreviewText = view.findViewById(R.id.subtitlePreviewText);
        subtitleAssLockBanner = view.findViewById(R.id.subtitleAssLockBanner);
        subtitleStyleCustomizationContainer = view.findViewById(R.id.subtitleStyleCustomizationContainer);

        // Size Chips
        chipSize14 = view.findViewById(R.id.chipSize14);
        chipSize16 = view.findViewById(R.id.chipSize16);
        chipSize18 = view.findViewById(R.id.chipSize18);
        chipSize22 = view.findViewById(R.id.chipSize22);
        chipSize26 = view.findViewById(R.id.chipSize26);

        // Color Chips
        chipColorWhite = view.findViewById(R.id.chipColorWhite);
        chipColorYellow = view.findViewById(R.id.chipColorYellow);
        chipColorCyan = view.findViewById(R.id.chipColorCyan);
        chipColorGreen = view.findViewById(R.id.chipColorGreen);

        // Background Chips
        chipBgNone = view.findViewById(R.id.chipBgNone);
        chipBgTranslucent = view.findViewById(R.id.chipBgTranslucent);
        chipBgBlack = view.findViewById(R.id.chipBgBlack);

        // Edge Chips
        chipEdgeOutline = view.findViewById(R.id.chipEdgeOutline);
        chipEdgeShadow = view.findViewById(R.id.chipEdgeShadow);
        chipEdgeNone = view.findViewById(R.id.chipEdgeNone);

        enableSwitch.setChecked(subtitlesEnabled);

        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            subtitlesEnabled = isChecked;
            if (toggledListener != null) {
                toggledListener.onSubtitlesToggled(isChecked);
            }
        });

        enableRow.setOnClickListener(v -> enableSwitch.setChecked(!enableSwitch.isChecked()));

        updateSelection();

        backButton.setOnClickListener(v -> {
            if (onBackPressedListener != null) {
                dismiss();
                onBackPressedListener.onBackPressed();
            } else {
                dismiss();
            }
        });

        autoOption.setOnClickListener(v -> selectFormat("auto"));
        assOption.setOnClickListener(v -> selectFormat("ass"));
        vttOption.setOnClickListener(v -> selectFormat("vtt"));

        // Setup size chip listeners (guarded against ASS lock)
        if (chipSize14 != null) chipSize14.setOnClickListener(v -> { if (!isAssSelected()) { selectedTextSize = 14f; notifyStyleChanged(); } });
        if (chipSize16 != null) chipSize16.setOnClickListener(v -> { if (!isAssSelected()) { selectedTextSize = 16f; notifyStyleChanged(); } });
        if (chipSize18 != null) chipSize18.setOnClickListener(v -> { if (!isAssSelected()) { selectedTextSize = 18f; notifyStyleChanged(); } });
        if (chipSize22 != null) chipSize22.setOnClickListener(v -> { if (!isAssSelected()) { selectedTextSize = 22f; notifyStyleChanged(); } });
        if (chipSize26 != null) chipSize26.setOnClickListener(v -> { if (!isAssSelected()) { selectedTextSize = 26f; notifyStyleChanged(); } });

        // Setup color chip listeners
        if (chipColorWhite != null) chipColorWhite.setOnClickListener(v -> { if (!isAssSelected()) { selectedTextColor = 0xFFFFFFFF; notifyStyleChanged(); } });
        if (chipColorYellow != null) chipColorYellow.setOnClickListener(v -> { if (!isAssSelected()) { selectedTextColor = 0xFFFFEB3B; notifyStyleChanged(); } });
        if (chipColorCyan != null) chipColorCyan.setOnClickListener(v -> { if (!isAssSelected()) { selectedTextColor = 0xFF00E5FF; notifyStyleChanged(); } });
        if (chipColorGreen != null) chipColorGreen.setOnClickListener(v -> { if (!isAssSelected()) { selectedTextColor = 0xFF76FF03; notifyStyleChanged(); } });

        // Setup background chip listeners
        if (chipBgNone != null) chipBgNone.setOnClickListener(v -> { if (!isAssSelected()) { selectedBgColor = 0x00000000; notifyStyleChanged(); } });
        if (chipBgTranslucent != null) chipBgTranslucent.setOnClickListener(v -> { if (!isAssSelected()) { selectedBgColor = 0x80000000; notifyStyleChanged(); } });
        if (chipBgBlack != null) chipBgBlack.setOnClickListener(v -> { if (!isAssSelected()) { selectedBgColor = 0xFF000000; notifyStyleChanged(); } });

        // Setup edge chip listeners
        if (chipEdgeOutline != null) chipEdgeOutline.setOnClickListener(v -> { if (!isAssSelected()) { selectedEdgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE; notifyStyleChanged(); } });
        if (chipEdgeShadow != null) chipEdgeShadow.setOnClickListener(v -> { if (!isAssSelected()) { selectedEdgeType = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW; notifyStyleChanged(); } });
        if (chipEdgeNone != null) chipEdgeNone.setOnClickListener(v -> { if (!isAssSelected()) { selectedEdgeType = CaptionStyleCompat.EDGE_TYPE_NONE; notifyStyleChanged(); } });

        updateStyleUI();
        updateStyleLockState();

        // Render available episode subtitle tracks if present
        LinearLayout tracksContainer = view.findViewById(R.id.subtitlesTracksContainer);
        LinearLayout tracksList = view.findViewById(R.id.subtitlesTracksList);

        if (availableSubtitles != null && !availableSubtitles.isEmpty() && tracksContainer != null && tracksList != null) {
            tracksContainer.setVisibility(View.VISIBLE);
            tracksList.removeAllViews();

            String effectiveActiveFormat = isAssSelected() ? "ass" : "vtt";
            for (int i = 0; i < availableSubtitles.size(); i++) {
                EpisodeResponse.SubtitleData sub = availableSubtitles.get(i);
                if (sub == null) continue;

                View trackRow = LayoutInflater.from(getContext()).inflate(R.layout.item_subtitle_track, tracksList, false);
                TextView trackTitle = trackRow.findViewById(R.id.subtitleTrackTitle);
                TextView trackFormat = trackRow.findViewById(R.id.subtitleTrackFormat);
                View selectedPill = trackRow.findViewById(R.id.selectedPill);
                View unselectedIndicator = trackRow.findViewById(R.id.unselectedIndicator);

                String formatUpper = sub.getFormat() != null ? sub.getFormat().toUpperCase(Locale.ROOT) : "SUB";
                String title = sub.getFilename() != null ? sub.getFilename() : ("Субтитры #" + (i + 1));
                if (sub.getName() != null && !sub.getName().isEmpty() && !sub.getName().equals(sub.getFilename())) {
                    title = sub.getName();
                }

                if (trackTitle != null) trackTitle.setText(title);
                if (trackFormat != null) trackFormat.setText(formatUpper);

                String subFmt = sub.getFormat() != null ? sub.getFormat().trim().toLowerCase(Locale.ROOT) : "";
                boolean isSelected = subFmt.equalsIgnoreCase(currentFormat)
                        || ("auto".equalsIgnoreCase(currentFormat)
                        && (subFmt.equalsIgnoreCase(effectiveActiveFormat)
                        || ("ass".equals(effectiveActiveFormat) && "ssa".equals(subFmt))));
                if (selectedPill != null) selectedPill.setVisibility(isSelected ? View.VISIBLE : View.GONE);
                if (unselectedIndicator != null) unselectedIndicator.setVisibility(isSelected ? View.GONE : View.VISIBLE);

                String fmt = sub.getFormat() != null ? sub.getFormat().toLowerCase(Locale.ROOT) : "ass";
                trackRow.setOnClickListener(v -> selectFormat(fmt));

                tracksList.addView(trackRow);
            }
        }

        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    private void selectFormat(String format) {
        currentFormat = format;
        updateSelection();
        updateStyleLockState();
        if (formatListener != null) {
            formatListener.onSubtitleFormatChanged(format);
        }
        dismiss();
    }

    private void updateSelection() {
        boolean isAuto = "auto".equalsIgnoreCase(currentFormat);
        boolean isAss = "ass".equalsIgnoreCase(currentFormat) || "ssa".equalsIgnoreCase(currentFormat);
        boolean isVtt = "vtt".equalsIgnoreCase(currentFormat) || "webvtt".equalsIgnoreCase(currentFormat);

        if (pillAuto != null) pillAuto.setVisibility(isAuto ? View.VISIBLE : View.GONE);
        if (unselectedAuto != null) unselectedAuto.setVisibility(isAuto ? View.GONE : View.VISIBLE);

        if (pillAss != null) pillAss.setVisibility(isAss ? View.VISIBLE : View.GONE);
        if (unselectedAss != null) unselectedAss.setVisibility(isAss ? View.GONE : View.VISIBLE);

        if (pillVtt != null) pillVtt.setVisibility(isVtt ? View.VISIBLE : View.GONE);
        if (unselectedVtt != null) unselectedVtt.setVisibility(isVtt ? View.GONE : View.VISIBLE);
    }

    private void updateStyleLockState() {
        boolean locked = isAssSelected();
        if (subtitleAssLockBanner != null) {
            subtitleAssLockBanner.setVisibility(locked ? View.VISIBLE : View.GONE);
        }
        if (subtitleStyleCustomizationContainer != null) {
            subtitleStyleCustomizationContainer.setAlpha(locked ? 0.38f : 1.0f);
        }

        TextView[] allChips = new TextView[] {
                chipSize14, chipSize16, chipSize18, chipSize22, chipSize26,
                chipColorWhite, chipColorYellow, chipColorCyan, chipColorGreen,
                chipBgNone, chipBgTranslucent, chipBgBlack,
                chipEdgeOutline, chipEdgeShadow, chipEdgeNone
        };
        for (TextView chip : allChips) {
            if (chip != null) {
                chip.setEnabled(!locked);
                chip.setClickable(!locked);
                chip.setFocusable(!locked);
            }
        }
    }

    private void updateStyleUI() {
        if (subtitlePreviewText != null) {
            subtitlePreviewText.setTextSize(TypedValue.COMPLEX_UNIT_SP, selectedTextSize);
            subtitlePreviewText.setTextColor(selectedTextColor);
            subtitlePreviewText.setBackgroundColor(selectedBgColor);

            if (selectedEdgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) {
                subtitlePreviewText.setShadowLayer(6f, 3f, 3f, selectedEdgeColor);
            } else if (selectedEdgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
                subtitlePreviewText.setShadowLayer(4f, 0f, 0f, selectedEdgeColor);
            } else {
                subtitlePreviewText.setShadowLayer(0f, 0f, 0f, 0);
            }
        }

        // Font Size Chips
        updateChipState(chipSize14, Math.abs(selectedTextSize - 14f) < 0.5f);
        updateChipState(chipSize16, Math.abs(selectedTextSize - 16f) < 0.5f);
        updateChipState(chipSize18, Math.abs(selectedTextSize - 18f) < 0.5f);
        updateChipState(chipSize22, Math.abs(selectedTextSize - 22f) < 0.5f);
        updateChipState(chipSize26, Math.abs(selectedTextSize - 26f) < 0.5f);

        // Text Color Chips
        updateChipState(chipColorWhite, selectedTextColor == 0xFFFFFFFF);
        updateChipState(chipColorYellow, selectedTextColor == 0xFFFFEB3B);
        updateChipState(chipColorCyan, selectedTextColor == 0xFF00E5FF);
        updateChipState(chipColorGreen, selectedTextColor == 0xFF76FF03);

        // Background Color Chips
        updateChipState(chipBgNone, selectedBgColor == 0x00000000);
        updateChipState(chipBgTranslucent, selectedBgColor == 0x80000000);
        updateChipState(chipBgBlack, selectedBgColor == 0xFF000000);

        // Edge Chips
        updateChipState(chipEdgeOutline, selectedEdgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE);
        updateChipState(chipEdgeShadow, selectedEdgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW);
        updateChipState(chipEdgeNone, selectedEdgeType == CaptionStyleCompat.EDGE_TYPE_NONE);
    }

    private void updateChipState(TextView chip, boolean isSelected) {
        if (chip == null || getContext() == null) return;
        chip.setBackgroundResource(isSelected ? R.drawable.chip_selected : R.drawable.chip_unselected);
        chip.setTextColor(ContextCompat.getColor(getContext(), isSelected ? R.color.purple_primary : R.color.primary_text_color));
    }

    private void notifyStyleChanged() {
        if (isAssSelected()) {
            return;
        }
        updateStyleUI();
        if (styleListener != null) {
            styleListener.onSubtitleStyleChanged(selectedTextSize, selectedTextColor, selectedBgColor, selectedEdgeType, selectedEdgeColor);
        }
    }
}
