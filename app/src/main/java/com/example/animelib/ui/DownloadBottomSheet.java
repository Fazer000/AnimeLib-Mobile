package com.example.animelib.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.models.DownloadTask;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.services.DownloadService;
import com.example.animelib.util.FlexibleBottomSheetDialogFragment;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadBottomSheet extends FlexibleBottomSheetDialogFragment {

    private static final int CHIP_CORNER_OUTER_DP = 14;
    private static final int CHIP_CORNER_INNER_DP = 6;
    private static final int CHIP_CORNER_ACTIVE_PILL_DP = 18;
    private static final int EPISODE_CORNER_OUTER_DP = 16;
    private static final int EPISODE_CORNER_INNER_DP = 6;
    private static final int EPISODE_CORNER_ACTIVE_PILL_DP = 20;
    private static final long EXPRESSIVE_MORPH_DURATION_MS = 220L;

    private static final class ExpressivePillState {
        final TextView view;
        final GradientDrawable shape;
        final RippleDrawable ripple;
        float[] inactiveRadii;
        final float activeRadius;
        float progress;
        boolean targetActive;
        ValueAnimator animator;

        ExpressivePillState(TextView view,
                            GradientDrawable shape,
                            RippleDrawable ripple,
                            float[] inactiveRadii,
                            float activeRadius,
                            boolean initialActive) {
            this.view = view;
            this.shape = shape;
            this.ripple = ripple;
            this.inactiveRadii = inactiveRadii;
            this.activeRadius = activeRadius;
            this.progress = initialActive ? 1f : 0f;
            this.targetActive = initialActive;
        }
    }

    private final Map<TextView, ExpressivePillState> expressiveChipStates = new HashMap<>();

    public static class VoiceoverOption {
        public String title;
        public String teamName;
        public int teamId;
        public String playerType;
        public String coverUrl;
        public String qualitiesString;

        public VoiceoverOption(String title, String teamName, int teamId, String playerType, String coverUrl, String qualitiesString) {
            this.title = title;
            this.teamName = teamName;
            this.teamId = teamId;
            this.playerType = playerType;
            this.coverUrl = coverUrl;
            this.qualitiesString = qualitiesString;
        }

        public VoiceoverOption(String title, String teamName, int teamId, String playerType) {
            this(title, teamName, teamId, playerType, null, null);
        }

        @NonNull
        @Override
        public String toString() {
            return title;
        }
    }

    public static class EpisodeCheckItem {
        public EpisodesListResponse.EpisodeItem episode;
        public boolean isChecked;
        public boolean isAvailable;
        public boolean isAlreadyDownloaded;
        public boolean isDownloading;

        public EpisodeCheckItem(EpisodesListResponse.EpisodeItem episode, boolean isChecked, boolean isAvailable, boolean isAlreadyDownloaded) {
            this.episode = episode;
            this.isChecked = isChecked;
            this.isAvailable = isAvailable;
            this.isAlreadyDownloaded = isAlreadyDownloaded;
            this.isDownloading = false;
        }
    }

    private String animeId;
    private String animeTitle;
    private String posterUrl;
    private List<EpisodesListResponse.EpisodeItem> episodeItems = new ArrayList<>();
    private List<EpisodeResponse.PlayerData> currentPlayers = new ArrayList<>();

    // UI Elements - Navigation & Header
    private ImageButton btnBsBack;
    private ImageButton btnBsClose;
    private TextView tvTitle;
    private TextView tvSubtitle;

    // Step 1 UI Elements
    private View layoutStep1;
    private TextView chipPlayerAnimelib;
    private TextView chipPlayerKodik;
    private LinearLayout cgQualities;
    private TextView chipQ2160;
    private TextView chipQ1080;
    private TextView chipQ720;
    private TextView chipQ480;
    private TextView chipQ360;
    private RecyclerView rvVoiceovers;
    private MaterialButton btnNextStep;

    // Step 2 UI Elements
    private LinearLayout layoutStep2;
    private TextView btnSelectAll;
    private TextView btnDeselectAll;
    private TextView btnInvertSelection;
    private TextView tvSelectedCounter;
    private ProgressBar pbLoading;
    private RecyclerView rvEpisodes;
    private MaterialButton btnDownload;

    // State Variables
    private String selectedPlayerType = "animelib"; // "animelib" or "kodik"
    private String selectedQuality = "1080p";
    private VoiceoverOption selectedVoiceover;

    private final Map<String, Set<String>> kodikQualitiesCache = new HashMap<>();
    private final List<TextView> dynamicQualityChips = new ArrayList<>();

    private final List<VoiceoverOption> allVoiceovers = new ArrayList<>();
    private final List<VoiceoverOption> displayedVoiceovers = new ArrayList<>();
    private VoiceoverAdapter voiceoverAdapter;

    private final List<EpisodeCheckItem> checkItems = new ArrayList<>();
    private EpisodeAdapter episodeAdapter;

    private ApiService apiService;
    private DatabaseManager databaseManager;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "download-bs-worker");
        t.setDaemon(true);
        return t;
    });

    public static DownloadBottomSheet newInstance(String animeId, String animeTitle, String posterUrl,
                                                 List<EpisodesListResponse.EpisodeItem> episodeItems,
                                                 List<EpisodeResponse.PlayerData> currentPlayers) {
        DownloadBottomSheet fragment = new DownloadBottomSheet();
        fragment.animeId = animeId;
        fragment.animeTitle = animeTitle;
        fragment.posterUrl = posterUrl;
        if (episodeItems != null) fragment.episodeItems = episodeItems;
        if (currentPlayers != null) fragment.currentPlayers = currentPlayers;
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bs_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getDialog() instanceof BottomSheetDialog) {
            com.example.animelib.util.FloatingBottomSheetUtils.setupFloatingStyle((BottomSheetDialog) getDialog());
        }

        Context ctx = requireContext();
        apiService = new ApiService(ctx);
        databaseManager = DatabaseManager.getInstance(ctx);

        // Header & Views init
        btnBsBack = view.findViewById(R.id.btnBsBack);
        btnBsClose = view.findViewById(R.id.btnBsClose);
        tvTitle = view.findViewById(R.id.tvTitle);
        tvSubtitle = view.findViewById(R.id.tvSubtitle);

        // Step 1 Views init
        layoutStep1 = view.findViewById(R.id.layoutStep1);
        chipPlayerAnimelib = view.findViewById(R.id.chipPlayerAnimelib);
        chipPlayerKodik = view.findViewById(R.id.chipPlayerKodik);
        cgQualities = view.findViewById(R.id.cgQualities);
        chipQ2160 = view.findViewById(R.id.chipQ2160);
        chipQ1080 = view.findViewById(R.id.chipQ1080);
        chipQ720 = view.findViewById(R.id.chipQ720);
        chipQ480 = view.findViewById(R.id.chipQ480);
        chipQ360 = view.findViewById(R.id.chipQ360);
        rvVoiceovers = view.findViewById(R.id.rvVoiceovers);
        btnNextStep = view.findViewById(R.id.btnNextStep);

        // Step 2 Views init
        layoutStep2 = view.findViewById(R.id.layoutStep2);
        btnSelectAll = view.findViewById(R.id.btnSelectAll);
        btnDeselectAll = view.findViewById(R.id.btnDeselectAll);
        btnInvertSelection = view.findViewById(R.id.btnInvertSelection);
        tvSelectedCounter = view.findViewById(R.id.tvSelectedCounter);
        pbLoading = view.findViewById(R.id.pbLoading);
        rvEpisodes = view.findViewById(R.id.rvEpisodes);
        btnDownload = view.findViewById(R.id.btnDownload);

        // Setup Header Listeners
        if (btnBsClose != null) btnBsClose.setOnClickListener(v -> dismiss());
        if (btnBsBack != null) {
            btnBsBack.setVisibility(getOnBackPressedAction() != null ? View.VISIBLE : View.GONE);
            btnBsBack.setOnClickListener(v -> performBackPress());
        }
        ImageButton btnBsGoToDownloads = view.findViewById(R.id.btnBsGoToDownloads);
        if (btnBsGoToDownloads != null) {
            btnBsGoToDownloads.setOnClickListener(v -> {
                dismiss();
                DownloadsActivity.start(requireContext());
            });
        }

        // Setup Step 1 Chips & Recycler
        setupPlayerChips();
        setupQualityChips();
        parseAllVoiceovers();
        updatePlayerChipsUI();
        setupVoiceoversList();

        btnNextStep.setOnClickListener(v -> {
            if (selectedVoiceover == null && !displayedVoiceovers.isEmpty()) {
                selectedVoiceover = displayedVoiceovers.get(0);
            }
            if (selectedVoiceover == null) {
                com.example.animelib.util.CustomToast.showWarning(requireContext(), "Выберите озвучку");
                return;
            }
            showStep2();
        });

        // Setup Step 2 Listeners & Recycler
        setupEpisodesList();

        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> {
                for (EpisodeCheckItem item : checkItems) {
                    if (item.isAvailable && !item.isAlreadyDownloaded) {
                        item.isChecked = true;
                    }
                }
                if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
                updateDownloadButtonText();
            });
        }

        if (btnDeselectAll != null) {
            btnDeselectAll.setOnClickListener(v -> {
                for (EpisodeCheckItem item : checkItems) {
                    item.isChecked = false;
                }
                if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
                updateDownloadButtonText();
            });
        }

        if (btnInvertSelection != null) {
            btnInvertSelection.setOnClickListener(v -> {
                for (EpisodeCheckItem item : checkItems) {
                    if (item.isAvailable && !item.isAlreadyDownloaded) {
                        item.isChecked = !item.isChecked;
                    }
                }
                if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
                updateDownloadButtonText();
            });
        }

        btnDownload.setOnClickListener(v -> startDownloadQueue());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            com.example.animelib.util.FloatingBottomSheetUtils.applyFloatingToView((BottomSheetDialog) getDialog());
        }
    }

    @Override
    public void onDestroy() {
        backgroundExecutor.shutdownNow();
        if (apiService != null) {
            apiService.shutdown();
            apiService = null;
        }
        super.onDestroy();
    }

    @Override
    protected boolean onHandleInternalBack() {
        if (layoutStep2 != null && layoutStep2.getVisibility() == View.VISIBLE) {
            showStep1();
            return true;
        }
        return false;
    }

    private void showStep1() {
        if (btnBsBack != null) {
            btnBsBack.setVisibility(getOnBackPressedAction() != null ? View.VISIBLE : View.GONE);
        }
        tvTitle.setText("Скачивание серий");
        tvSubtitle.setText("Озвучка и качество");

        if (layoutStep2.getVisibility() == View.VISIBLE) {
            layoutStep2.animate()
                .alpha(0f)
                .translationX(100f)
                .setDuration(180)
                .withEndAction(() -> {
                    layoutStep2.setVisibility(View.GONE);
                    layoutStep1.setTranslationX(-100f);
                    layoutStep1.setAlpha(0f);
                    layoutStep1.setVisibility(View.VISIBLE);
                    layoutStep1.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(180)
                        .start();
                })
                .start();
        } else {
            layoutStep1.setVisibility(View.VISIBLE);
            layoutStep2.setVisibility(View.GONE);
        }
    }

    private void showStep2() {
        btnBsBack.setVisibility(View.VISIBLE);
        tvTitle.setText("Выбор серий");
        updateDownloadButtonText();

        if (selectedVoiceover != null) {
            checkVoiceoverAvailability(selectedVoiceover);
        }

        if (layoutStep1.getVisibility() == View.VISIBLE) {
            layoutStep1.animate()
                .alpha(0f)
                .translationX(-100f)
                .setDuration(180)
                .withEndAction(() -> {
                    layoutStep1.setVisibility(View.GONE);
                    layoutStep2.setTranslationX(100f);
                    layoutStep2.setAlpha(0f);
                    layoutStep2.setVisibility(View.VISIBLE);
                    layoutStep2.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(180)
                        .start();
                })
                .start();
        } else {
            layoutStep1.setVisibility(View.GONE);
            layoutStep2.setVisibility(View.VISIBLE);
        }
    }

    private void setupPlayerChips() {
        chipPlayerAnimelib.setOnClickListener(v -> {
            if (!"animelib".equalsIgnoreCase(selectedPlayerType)) {
                selectedPlayerType = "animelib";
                updatePlayerChipsUI(true);
                updateVoiceoversForSelectedPlayer();
            }
        });

        chipPlayerKodik.setOnClickListener(v -> {
            if (!"kodik".equalsIgnoreCase(selectedPlayerType)) {
                selectedPlayerType = "kodik";
                updatePlayerChipsUI(true);
                updateVoiceoversForSelectedPlayer();
            }
        });

        updatePlayerChipsUI(false);
    }

    private void updatePlayerChipsUI() {
        updatePlayerChipsUI(false);
    }

    private void updatePlayerChipsUI(boolean animate) {
        if (!isAdded() || getContext() == null) return;

        boolean hasAnimelib = false;
        boolean hasKodik = false;

        if (currentPlayers != null) {
            for (EpisodeResponse.PlayerData p : currentPlayers) {
                if (p != null && p.getPlayer() != null) {
                    String pType = p.getPlayer().toLowerCase();
                    if (pType.contains("animelib")) {
                        hasAnimelib = true;
                    }
                    if (pType.contains("kodik")) {
                        hasKodik = true;
                    }
                }
            }
        }

        for (VoiceoverOption vo : allVoiceovers) {
            if (vo != null && vo.playerType != null) {
                if (vo.playerType.contains("animelib")) hasAnimelib = true;
                if (vo.playerType.contains("kodik")) hasKodik = true;
            }
        }

        if (!hasAnimelib && !hasKodik) {
            hasAnimelib = true;
            hasKodik = true;
        }

        if (chipPlayerAnimelib != null) {
            chipPlayerAnimelib.setVisibility(hasAnimelib ? View.VISIBLE : View.GONE);
        }
        if (chipPlayerKodik != null) {
            chipPlayerKodik.setVisibility(hasKodik ? View.VISIBLE : View.GONE);
        }

        if ("animelib".equalsIgnoreCase(selectedPlayerType) && !hasAnimelib && hasKodik) {
            selectedPlayerType = "kodik";
        } else if ("kodik".equalsIgnoreCase(selectedPlayerType) && !hasKodik && hasAnimelib) {
            selectedPlayerType = "animelib";
        }

        List<TextView> visiblePlayers = new ArrayList<>();
        if (chipPlayerAnimelib != null && hasAnimelib) visiblePlayers.add(chipPlayerAnimelib);
        if (chipPlayerKodik != null && hasKodik) visiblePlayers.add(chipPlayerKodik);

        boolean animelibSelected = "animelib".equalsIgnoreCase(selectedPlayerType);
        updateConnectedExpressiveRow(visiblePlayers, chip ->
                (chip == chipPlayerAnimelib) ? animelibSelected : !animelibSelected, animate);
    }

    private void setupQualityChips() {
        View.OnClickListener listener = v -> {
            TextView tv = (TextView) v;
            String clickedQuality = tv.getText().toString();
            if (!clickedQuality.equalsIgnoreCase(selectedQuality)) {
                selectedQuality = clickedQuality;
                updateQualityChipsUI(true);
            }
        };

        if (chipQ2160 != null) chipQ2160.setOnClickListener(listener);
        if (chipQ1080 != null) chipQ1080.setOnClickListener(listener);
        if (chipQ720 != null) chipQ720.setOnClickListener(listener);
        if (chipQ480 != null) chipQ480.setOnClickListener(listener);
        if (chipQ360 != null) chipQ360.setOnClickListener(listener);

        updateQualityChipsUI(false);
    }

    private void updateQualityChipsUI() {
        updateQualityChipsUI(false);
    }

    private void updateQualityChipsUI(boolean animate) {
        List<TextView> allChips = new ArrayList<>();
        if (chipQ2160 != null) allChips.add(chipQ2160);
        if (chipQ1080 != null) allChips.add(chipQ1080);
        if (chipQ720 != null) allChips.add(chipQ720);
        if (chipQ480 != null) allChips.add(chipQ480);
        if (chipQ360 != null) allChips.add(chipQ360);
        allChips.addAll(dynamicQualityChips);

        if (!isAdded() || getContext() == null) return;

        List<TextView> visibleChips = new ArrayList<>();
        for (TextView chip : allChips) {
            if (chip != null && chip.getVisibility() == View.VISIBLE) {
                visibleChips.add(chip);
            }
        }

        updateConnectedExpressiveRow(visibleChips,
                chip -> chip.getText().toString().equalsIgnoreCase(selectedQuality), animate);
    }

    private interface ChipActivePredicate {
        boolean isActive(TextView chip);
    }

    private void updateConnectedExpressiveRow(List<TextView> visibleChips,
                                              ChipActivePredicate predicate,
                                              boolean animate) {
        if (!isAdded() || getContext() == null || visibleChips.isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;
        float outerPx = CHIP_CORNER_OUTER_DP * density;
        float innerPx = CHIP_CORNER_INNER_DP * density;
        float pillPx = CHIP_CORNER_ACTIVE_PILL_DP * density;
        int gapPx = (int) (4 * density);

        int count = visibleChips.size();
        for (int i = 0; i < count; i++) {
            TextView chip = visibleChips.get(i);
            boolean isFirst = (i == 0);
            boolean isLast = (i == count - 1);

            ViewGroup.LayoutParams rawLp = chip.getLayoutParams();
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) rawLp;
                int desiredMarginEnd = isLast ? 0 : gapPx;
                if (mlp.getMarginEnd() != desiredMarginEnd) {
                    mlp.setMarginEnd(desiredMarginEnd);
                    chip.setLayoutParams(mlp);
                }
            }

            float leftRadius = isFirst ? outerPx : innerPx;
            float rightRadius = isLast ? outerPx : innerPx;
            float[] inactiveRadii = new float[]{
                    leftRadius, leftRadius,
                    rightRadius, rightRadius,
                    rightRadius, rightRadius,
                    leftRadius, leftRadius
            };

            boolean active = predicate.isActive(chip);
            ExpressivePillState state = expressiveChipStates.get(chip);
            if (state == null) {
                GradientDrawable shape = new GradientDrawable();
                shape.setShape(GradientDrawable.RECTANGLE);
                RippleDrawable ripple = new RippleDrawable(
                        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.dialog_ripple_color)),
                        shape,
                        null
                );
                chip.setBackground(ripple);
                state = new ExpressivePillState(chip, shape, ripple, inactiveRadii, pillPx, active);
                expressiveChipStates.put(chip, state);
                applyExpressiveChipVisual(state, active ? 1f : 0f);
            } else {
                state.inactiveRadii = inactiveRadii;
                if (animate && state.targetActive != active) {
                    state.targetActive = active;
                    animateExpressiveChipState(state, active);
                } else {
                    if (state.animator != null) {
                        state.animator.cancel();
                        state.animator = null;
                    }
                    state.targetActive = active;
                    applyExpressiveChipVisual(state, active ? 1f : 0f);
                }
            }
            chip.setSelected(active);
        }
    }

    private void animateExpressiveChipState(ExpressivePillState state, boolean active) {
        if (state.animator != null) {
            state.animator.cancel();
        }
        float start = state.progress;
        float end = active ? 1f : 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(start, end);
        animator.setDuration(EXPRESSIVE_MORPH_DURATION_MS);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(anim -> applyExpressiveChipVisual(state, (float) anim.getAnimatedValue()));
        state.animator = animator;
        animator.start();
    }

    private void applyExpressiveChipVisual(ExpressivePillState state, float progress) {
        if (!isAdded() || getContext() == null) return;
        state.progress = progress;

        float[] radii = new float[8];
        for (int i = 0; i < 8; i++) {
            radii[i] = state.inactiveRadii[i] + (state.activeRadius - state.inactiveRadii[i]) * progress;
        }
        state.shape.setCornerRadii(radii);

        int inactiveBg = ContextCompat.getColor(requireContext(), R.color.chip_unselected_bg);
        int activeBg = ContextCompat.getColor(requireContext(), R.color.purple_primary);
        int inactiveStroke = ContextCompat.getColor(requireContext(), R.color.chip_unselected_stroke);
        int activeStroke = ContextCompat.getColor(requireContext(), R.color.purple_primary);
        int inactiveText = ContextCompat.getColor(requireContext(), R.color.primary_text_color);
        int activeText = ContextCompat.getColor(requireContext(), R.color.white_color);

        ArgbEvaluator evaluator = new ArgbEvaluator();
        int currentBg = (int) evaluator.evaluate(progress, inactiveBg, activeBg);
        int currentStroke = (int) evaluator.evaluate(progress, inactiveStroke, activeStroke);
        int currentText = (int) evaluator.evaluate(progress, inactiveText, activeText);

        float density = getResources().getDisplayMetrics().density;
        state.shape.setColor(currentBg);
        state.shape.setStroke(Math.max(1, Math.round(0.5f * density)), currentStroke);
        state.view.setTextColor(currentText);

        int rippleColor = progress >= 0.5f
                ? 0x38FFFFFF
                : ContextCompat.getColor(requireContext(), R.color.dialog_ripple_color);
        state.ripple.setColor(ColorStateList.valueOf(rippleColor));
    }

    private EpisodeResponse.PlayerData getSelectedPlayerData() {
        if (currentPlayers == null || currentPlayers.isEmpty()) {
            return null;
        }
        if (selectedVoiceover != null) {
            for (EpisodeResponse.PlayerData p : currentPlayers) {
                if (p != null && p.getPlayer() != null) {
                    boolean matchPlayer = p.getPlayer().equalsIgnoreCase(selectedPlayerType) ||
                            ("animelib".equalsIgnoreCase(selectedPlayerType) && p.getPlayer().toLowerCase().contains("animelib")) ||
                            ("kodik".equalsIgnoreCase(selectedPlayerType) && p.getPlayer().toLowerCase().contains("kodik"));
                    boolean matchTeam = p.getTeam() != null &&
                            (p.getTeam().getId() == selectedVoiceover.teamId ||
                             (p.getTeam().getName() != null && selectedVoiceover.teamName != null && p.getTeam().getName().equalsIgnoreCase(selectedVoiceover.teamName)));
                    if (matchPlayer && matchTeam) {
                        return p;
                    }
                }
            }
        }
        for (EpisodeResponse.PlayerData p : currentPlayers) {
            if (p != null && p.getPlayer() != null) {
                boolean matchPlayer = p.getPlayer().equalsIgnoreCase(selectedPlayerType) ||
                        ("animelib".equalsIgnoreCase(selectedPlayerType) && p.getPlayer().toLowerCase().contains("animelib")) ||
                        ("kodik".equalsIgnoreCase(selectedPlayerType) && p.getPlayer().toLowerCase().contains("kodik"));
                if (matchPlayer) {
                    return p;
                }
            }
        }
        return null;
    }

    private void updateAvailableQualities() {
        EpisodeResponse.PlayerData playerData = getSelectedPlayerData();
        Set<String> availableQualities = new HashSet<>();

        if (playerData != null) {
            if (playerData.getVideo() != null && playerData.getVideo().getQuality() != null && !playerData.getVideo().getQuality().isEmpty()) {
                for (EpisodeResponse.QualityData qd : playerData.getVideo().getQuality()) {
                    if (qd != null && qd.getQuality() > 0) {
                        availableQualities.add(String.valueOf(qd.getQuality()));
                    }
                }
            }

            if (availableQualities.isEmpty() && playerData.getSrc() != null && !playerData.getSrc().isEmpty()) {
                String src = playerData.getSrc();
                if (kodikQualitiesCache.containsKey(src)) {
                    availableQualities.addAll(kodikQualitiesCache.get(src));
                } else if ("kodik".equalsIgnoreCase(selectedPlayerType) || src.contains("kodik")) {
                    apiService.fetchKodikVideoLinks(src, new ApiService.KodikVideoCallback() {
                        @Override
                        public void onKodikVideoReceived(KodikResponse response) {
                            if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                                Set<String> fetched = new HashSet<>(response.getData().keySet());
                                kodikQualitiesCache.put(src, fetched);
                                if (isAdded() && getActivity() != null) {
                                    getActivity().runOnUiThread(() -> updateAvailableQualities());
                                }
                            }
                        }

                        @Override
                        public void onError(String error) {
                        }
                    });
                }
            }
        }

        applyQualityFilter(availableQualities);
    }

    private void applyQualityFilter(Set<String> availableQualities) {
        if (!isAdded() || getContext() == null) return;

        if (cgQualities != null && !dynamicQualityChips.isEmpty()) {
            for (TextView dynamicChip : dynamicQualityChips) {
                ExpressivePillState stale = expressiveChipStates.remove(dynamicChip);
                if (stale != null && stale.animator != null) {
                    stale.animator.cancel();
                }
                cgQualities.removeView(dynamicChip);
            }
            dynamicQualityChips.clear();
        }

        TextView[] standardChips = new TextView[]{chipQ2160, chipQ1080, chipQ720, chipQ480, chipQ360};
        String[] standardKeys = new String[]{"2160", "1080", "720", "480", "360"};

        if (availableQualities == null || availableQualities.isEmpty()) {
            for (TextView chip : standardChips) {
                if (chip != null) chip.setVisibility(View.VISIBLE);
            }
            updateQualityChipsUI();
            return;
        }

        List<String> sortedQualities = new ArrayList<>(availableQualities);
        Collections.sort(sortedQualities, (q1, q2) -> {
            try {
                return Integer.compare(Integer.parseInt(q2), Integer.parseInt(q1));
            } catch (NumberFormatException e) {
                return q2.compareTo(q1);
            }
        });

        for (TextView chip : standardChips) {
            if (chip != null) chip.setVisibility(View.GONE);
        }

        for (String qKey : sortedQualities) {
            boolean matched = false;
            for (int i = 0; i < standardKeys.length; i++) {
                if (standardKeys[i].equalsIgnoreCase(qKey)) {
                    if (standardChips[i] != null) {
                        standardChips[i].setVisibility(View.VISIBLE);
                    }
                    matched = true;
                    break;
                }
            }

            if (!matched && cgQualities != null) {
                TextView dynamicChip = createDynamicQualityChip(qKey);
                dynamicQualityChips.add(dynamicChip);
                cgQualities.addView(dynamicChip);
            }
        }

        boolean isSelectedAvailable = false;
        for (String qKey : sortedQualities) {
            if (selectedQuality != null && selectedQuality.equalsIgnoreCase(qKey + "p")) {
                isSelectedAvailable = true;
                break;
            }
        }

        if (!isSelectedAvailable && !sortedQualities.isEmpty()) {
            selectedQuality = sortedQualities.get(0) + "p";
        }

        updateQualityChipsUI();
    }

    private TextView createDynamicQualityChip(String qualityKey) {
        TextView chip = new TextView(requireContext());
        float density = getResources().getDisplayMetrics().density;
        chip.setText(qualityKey + "p");
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        chip.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.open_sans_bold));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setMinWidth((int) (64 * density));
        chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_text_color));
        int pdH = (int) (12 * density);
        chip.setPadding(pdH, 0, pdH, 0);
        chip.setClickable(true);
        chip.setFocusable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (36 * density)
        );
        lp.setMarginEnd((int) (4 * density));
        chip.setLayoutParams(lp);

        chip.setOnClickListener(v -> {
            String clickedQuality = qualityKey + "p";
            if (!clickedQuality.equalsIgnoreCase(selectedQuality)) {
                selectedQuality = clickedQuality;
                updateQualityChipsUI(true);
            }
        });
        return chip;
    }

    private void parseAllVoiceovers() {
        allVoiceovers.clear();
        Set<String> addedKeys = new HashSet<>();

        if (currentPlayers != null) {
            for (EpisodeResponse.PlayerData p : currentPlayers) {
                if (p.getTeam() != null) {
                    String pType = p.getPlayer() != null ? p.getPlayer().toLowerCase() : "animelib";
                    String title = p.getTeam().getName();
                    String key = p.getTeam().getId() + "_" + pType;
                    if (!addedKeys.contains(key)) {
                        addedKeys.add(key);
                        String coverUrl = p.getCoverUrl();
                        String qualitiesStr = "";
                        if (p.getVideo() != null && p.getVideo().getQuality() != null && !p.getVideo().getQuality().isEmpty()) {
                            List<String> qList = new ArrayList<>();
                            for (EpisodeResponse.QualityData qd : p.getVideo().getQuality()) {
                                if (qd != null && qd.getQuality() > 0) {
                                    qList.add(qd.getQuality() + "p");
                                }
                            }
                            if (!qList.isEmpty()) {
                                qualitiesStr = String.join(", ", qList);
                            }
                        }
                        allVoiceovers.add(new VoiceoverOption(title, p.getTeam().getName(), p.getTeam().getId(), pType, coverUrl, qualitiesStr));
                    }
                }
            }
        }
    }

    private void setupVoiceoversList() {
        rvVoiceovers.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvVoiceovers.setNestedScrollingEnabled(false);
        voiceoverAdapter = new VoiceoverAdapter();
        rvVoiceovers.setAdapter(voiceoverAdapter);

        updateVoiceoversForSelectedPlayer();
    }

    private void updateVoiceoversForSelectedPlayer() {
        displayedVoiceovers.clear();

        for (VoiceoverOption vo : allVoiceovers) {
            if (vo.playerType.equalsIgnoreCase(selectedPlayerType) ||
               ("animelib".equalsIgnoreCase(selectedPlayerType) && vo.playerType.contains("animelib")) ||
               ("kodik".equalsIgnoreCase(selectedPlayerType) && vo.playerType.contains("kodik"))) {
                displayedVoiceovers.add(vo);
            }
        }

        if (displayedVoiceovers.isEmpty()) {
            // Default fallback for chosen player
            String defaultTeamName = "animelib".equalsIgnoreCase(selectedPlayerType) ? "Основная озвучка (AnimeLib)" : "Основная озвучка (Kodik)";
            String defaultQualities = "kodik".equalsIgnoreCase(selectedPlayerType) ? "720p, 480p, 360p" : "1080p, 720p, 480p, 360p";
            displayedVoiceovers.add(new VoiceoverOption(defaultTeamName, defaultTeamName, 0, selectedPlayerType, null, defaultQualities));
        }

        selectedVoiceover = displayedVoiceovers.get(0);
        if (voiceoverAdapter != null) {
            voiceoverAdapter.notifyDataSetChanged();
        }
        updateAvailableQualities();
    }

    private void setupEpisodesList() {
        checkItems.clear();
        if (episodeItems != null && !episodeItems.isEmpty()) {
            for (EpisodesListResponse.EpisodeItem ep : episodeItems) {
                checkItems.add(new EpisodeCheckItem(ep, true, true, false));
            }
        }

        rvEpisodes.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        rvEpisodes.setNestedScrollingEnabled(false);
        episodeAdapter = new EpisodeAdapter();
        rvEpisodes.setAdapter(episodeAdapter);
        updateDownloadButtonText();

        if ((episodeItems == null || episodeItems.isEmpty()) && animeId != null) {
            fetchEpisodesFromApi();
        }
    }

    private void fetchEpisodesFromApi() {
        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        apiService.fetchEpisodesList(animeId, new ApiService.EpisodesCallback() {
            @Override
            public void onEpisodesReceived(EpisodesListResponse response) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                        if (response != null && response.getData() != null) {
                            episodeItems = response.getData();
                            checkItems.clear();
                            for (EpisodesListResponse.EpisodeItem ep : episodeItems) {
                                checkItems.add(new EpisodeCheckItem(ep, true, true, false));
                            }
                            if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
                            if (selectedVoiceover != null) {
                                checkVoiceoverAvailability(selectedVoiceover);
                            } else {
                                updateDownloadButtonText();
                            }
                        }
                    });
                }
            }

            @Override
            public void onError(String error) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                        showErrorAlertDialog("Ошибка загрузки озвучек", "Не удалось загрузить список серий и озвучек.", () -> fetchEpisodesFromApi());
                    });
                }
            }
        });
    }

    private void showErrorAlertDialog(String title, String message, Runnable retryAction) {
        if (!isAdded() || getContext() == null) return;
        try {
            View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_video_error, null);
            TextView titleTv = dialogView.findViewById(R.id.errorTitleText);
            TextView messageTv = dialogView.findViewById(R.id.errorMessageText);
            TextView detailsTv = dialogView.findViewById(R.id.errorPlayerDetailsText);
            com.google.android.material.button.MaterialButton retryBtn = dialogView.findViewById(R.id.retryButton);
            com.google.android.material.button.MaterialButton exitBtn = dialogView.findViewById(R.id.exitButton);
            ImageButton closeCrossBtn = dialogView.findViewById(R.id.closeErrorCrossButton);

            if (titleTv != null && title != null) titleTv.setText(title);
            if (messageTv != null && message != null) messageTv.setText(message);
            if (detailsTv != null) detailsTv.setVisibility(View.GONE);

            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
            builder.setView(dialogView);
            androidx.appcompat.app.AlertDialog dialog = builder.create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }

            if (exitBtn != null) {
                exitBtn.setOnClickListener(v -> dialog.dismiss());
            }

            if (retryBtn != null) {
                retryBtn.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (retryAction != null) {
                        retryAction.run();
                    }
                });
            }

            if (closeCrossBtn != null) {
                closeCrossBtn.setOnClickListener(v -> dialog.dismiss());
            }

            dialog.show();
        } catch (Exception e) {
            com.example.animelib.util.CustomToast.show(requireContext(), message);
        }
    }

    private void checkVoiceoverAvailability(VoiceoverOption option) {
        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        if (backgroundExecutor.isShutdown()) return;
        backgroundExecutor.execute(() -> {
            List<DownloadService.TaskProgressItem> activeTasks = DownloadService.getActiveTaskItems();

            for (EpisodeCheckItem item : checkItems) {
                int epId = item.episode.getId();
                String epNum = item.episode.getNumber();
                DownloadedEpisodeEntity downloaded = databaseManager.findDownloadedEpisode(animeId, epId, epNum, option.teamName);

                boolean isDownloadingOrQueued = false;
                if (activeTasks != null) {
                    for (DownloadService.TaskProgressItem activeItem : activeTasks) {
                        if (activeItem.task != null &&
                            java.util.Objects.equals(activeItem.task.getAnimeId(), animeId) &&
                            (activeItem.task.getEpisodeId() == epId || java.util.Objects.equals(activeItem.task.getEpisodeNumber(), epNum)) &&
                            (activeItem.status == DownloadService.TaskProgressItem.STATUS_WAITING ||
                             activeItem.status == DownloadService.TaskProgressItem.STATUS_DOWNLOADING)) {
                            isDownloadingOrQueued = true;
                            break;
                        }
                    }
                }

                boolean hasValidFile = false;
                if (downloaded != null && downloaded.getLocalFilePath() != null) {
                    java.io.File localFile = new java.io.File(downloaded.getLocalFilePath());
                    hasValidFile = localFile.exists() && localFile.length() > 0;
                }

                item.isAlreadyDownloaded = hasValidFile;
                item.isDownloading = isDownloadingOrQueued;
                item.isAvailable = true; // By default available
                item.isChecked = item.isAvailable && !item.isAlreadyDownloaded && !item.isDownloading;
            }

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                    if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
                    updateDownloadButtonText();
                });
            }
        });
    }

    private void updateDownloadButtonText() {
        int selectedCount = 0;
        for (EpisodeCheckItem item : checkItems) {
            if (item.isAvailable && !item.isAlreadyDownloaded && !item.isDownloading && item.isChecked) {
                selectedCount++;
            }
        }

        if (tvSelectedCounter != null) {
            tvSelectedCounter.setText(selectedCount + " из " + checkItems.size());
        }

        if (tvSubtitle != null && layoutStep2 != null && layoutStep2.getVisibility() == View.VISIBLE) {
            String voiceoverName = (selectedVoiceover != null ? selectedVoiceover.teamName : "Озвучка");
            String qualityName = selectedQuality != null ? selectedQuality : "";
            tvSubtitle.setText(voiceoverName + " • " + qualityName);
        }

        if (btnDownload != null) {
            btnDownload.setText(selectedCount > 0 ? ("Скачать (" + selectedCount + ")") : "Скачать");
            btnDownload.setEnabled(selectedCount > 0);
            btnDownload.setAlpha(selectedCount > 0 ? 1.0f : 0.45f);
        }
    }

    private void startDownloadQueue() {
        if (selectedVoiceover == null || selectedQuality == null) return;
        String qualityNum = selectedQuality.replace("p", "");

        ArrayList<DownloadTask> tasks = new ArrayList<>();
        for (EpisodeCheckItem item : checkItems) {
            if (item.isChecked && item.isAvailable && !item.isAlreadyDownloaded && !item.isDownloading) {
                String epNum = item.episode.getNumber();
                String rawName = item.episode.getName();
                String epName = com.example.animelib.VideoPlayerActivity.cleanEpisodeName(rawName, epNum);
                DownloadTask task = new DownloadTask(
                        animeId,
                        animeTitle != null ? animeTitle : "Аниме #" + animeId,
                        posterUrl,
                        item.episode.getId(),
                        epNum,
                        epName,
                        selectedVoiceover.teamName,
                        selectedVoiceover.teamId,
                        selectedVoiceover.playerType,
                        qualityNum
                );
                tasks.add(task);
            }
        }

        if (tasks.isEmpty()) {
            com.example.animelib.util.CustomToast.showWarning(requireContext(), "Не выбрано ни одной серии");
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4201);
            }
        }

        DownloadService.startQueue(requireContext(), tasks);
        com.example.animelib.util.CustomToast.showSuccess(requireContext(),
                "Скачивание запущено в фоне (" + com.example.animelib.models.OfflineEpisodeMetadata.formatRussianEpisodesCount(tasks.size()) + ")");
        dismiss();
    }

    private int getAttrColor(int attrRes, int defaultColor) {
        try {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            if (requireContext().getTheme().resolveAttribute(attrRes, typedValue, true)) {
                return typedValue.data;
            }
        } catch (Exception ignored) {}
        return defaultColor;
    }

    // ================= ADAPTERS =================

    private class VoiceoverAdapter extends RecyclerView.Adapter<VoiceoverAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_voiceover_choice, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VoiceoverOption vo = displayedVoiceovers.get(position);
            holder.tvVoiceoverName.setText(vo.teamName);

            String qText = vo.qualitiesString;
            if (qText != null && !qText.isEmpty()) {
                holder.tvVoiceoverDetails.setText("Качество: " + qText);
            } else if ("kodik".equalsIgnoreCase(vo.playerType)) {
                holder.tvVoiceoverDetails.setText("Качество: 720p, 480p, 360p");
            } else {
                holder.tvVoiceoverDetails.setText("Качество: 1080p, 720p, 480p, 360p");
            }

            if (holder.teamIcon != null) {
                if (vo.coverUrl != null && !vo.coverUrl.isEmpty()) {
                    com.example.animelib.util.ImageLoader.getInstance().loadInto(holder.teamIcon, vo.coverUrl, R.drawable.ic_avatar_placeholder);
                } else {
                    holder.teamIcon.setImageResource(R.drawable.ic_avatar_placeholder);
                }
            }

            int count = getItemCount();
            if (holder.itemContainer != null) {
                if (count == 1) {
                    holder.itemContainer.setBackgroundResource(R.drawable.bg_m3_item_single);
                } else if (position == 0) {
                    holder.itemContainer.setBackgroundResource(R.drawable.bg_m3_item_top);
                } else if (position == count - 1) {
                    holder.itemContainer.setBackgroundResource(R.drawable.bg_m3_item_bottom);
                } else {
                    holder.itemContainer.setBackgroundResource(R.drawable.bg_m3_item_middle);
                }
            }

            boolean isSelected = (selectedVoiceover != null && selectedVoiceover.equals(vo));
            if (holder.selectedPill != null) {
                holder.selectedPill.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            }
            if (holder.unselectedIndicator != null) {
                holder.unselectedIndicator.setVisibility(isSelected ? View.GONE : View.VISIBLE);
            }
            if (holder.tvVoiceoverName != null) {
                holder.tvVoiceoverName.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), isSelected ? R.color.purple_primary : R.color.primary_text_color));
            }

            holder.itemContainer.setScaleX(1.0f);
            holder.itemContainer.setScaleY(1.0f);

            holder.itemContainer.setOnClickListener(v -> {
                com.example.animelib.util.ItemAnimationUtils.animateItemClick(v, () -> {
                    selectedVoiceover = vo;
                    notifyDataSetChanged();
                    updateAvailableQualities();
                });
            });
        }

        @Override
        public int getItemCount() {
            return displayedVoiceovers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            View itemContainer;
            ImageView teamIcon;
            TextView tvVoiceoverName;
            TextView tvVoiceoverDetails;
            View selectedPill;
            View unselectedIndicator;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                itemContainer = itemView.findViewById(R.id.itemContainer);
                teamIcon = itemView.findViewById(R.id.teamIcon);
                tvVoiceoverName = itemView.findViewById(R.id.tvVoiceoverName);
                tvVoiceoverDetails = itemView.findViewById(R.id.tvVoiceoverDetails);
                selectedPill = itemView.findViewById(R.id.selectedPill);
                unselectedIndicator = itemView.findViewById(R.id.unselectedIndicator);
            }
        }
    }

    private class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

        private static final int GRID_SPAN = 3;

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_episode, parent, false);
            return new ViewHolder(view);
        }

        private float[] computeGridInactiveRadii(Context ctx, int position, int totalCount) {
            float density = ctx.getResources().getDisplayMetrics().density;
            float outerPx = EPISODE_CORNER_OUTER_DP * density;
            float innerPx = EPISODE_CORNER_INNER_DP * density;

            int row = position / GRID_SPAN;
            int col = position % GRID_SPAN;
            int lastRow = Math.max(0, (totalCount - 1) / GRID_SPAN);

            boolean isTopRow = (row == 0);
            boolean isBottomRow = (row == lastRow);
            boolean isBottomEdge = (position + GRID_SPAN >= totalCount);
            boolean isLeftCol = (col == 0);
            boolean isRightEdge = (col == GRID_SPAN - 1) || (position == totalCount - 1);

            float tl = (isTopRow && isLeftCol) ? outerPx : innerPx;
            float tr = (isTopRow && isRightEdge) ? outerPx : innerPx;
            float bl = (isBottomRow && isLeftCol) ? outerPx : innerPx;
            float br = (isBottomEdge && isRightEdge) ? outerPx : innerPx;

            return new float[]{tl, tl, tr, tr, br, br, bl, bl};
        }

        private void applyEpisodeExpressiveState(ViewHolder holder, float progress) {
            Context ctx = holder.itemView.getContext();
            holder.progress = progress;

            float[] radii = new float[8];
            for (int i = 0; i < 8; i++) {
                radii[i] = holder.inactiveRadii[i] + (holder.activePillRadius - holder.inactiveRadii[i]) * progress;
            }
            holder.tileShape.setCornerRadii(radii);

            int inactiveBg = ContextCompat.getColor(ctx, R.color.chip_unselected_bg);
            int activeBg = ContextCompat.getColor(ctx, R.color.chip_selected_bg);
            int inactiveStroke = ContextCompat.getColor(ctx, R.color.chip_unselected_stroke);
            int activeStroke = ContextCompat.getColor(ctx, R.color.purple_primary);
            int titleColor = ContextCompat.getColor(ctx, R.color.primary_text_color);
            int inactiveStatus = ContextCompat.getColor(ctx, R.color.accent_text_color);
            int activeStatus = ContextCompat.getColor(ctx, R.color.purple_primary);

            ArgbEvaluator evaluator = new ArgbEvaluator();
            float density = ctx.getResources().getDisplayMetrics().density;
            int strokeWidthPx = Math.max(1, Math.round((0.5f + 0.7f * progress) * density));

            holder.tileShape.setColor((int) evaluator.evaluate(progress, inactiveBg, activeBg));
            holder.tileShape.setStroke(strokeWidthPx, (int) evaluator.evaluate(progress, inactiveStroke, activeStroke));
            holder.tvEpisodeName.setTextColor(titleColor);
            holder.tvStatus.setTextColor((int) evaluator.evaluate(progress, inactiveStatus, activeStatus));

            int rippleColor = progress >= 0.5f
                    ? ContextCompat.getColor(ctx, R.color.purple_alpha_25)
                    : ContextCompat.getColor(ctx, R.color.dialog_ripple_color);
            holder.tileRipple.setColor(ColorStateList.valueOf(rippleColor));

            boolean showCheck = progress >= 0.5f;
            holder.tvStatus.setText(showCheck ? "Выбрано" : "Доступно");
            if (holder.ivEpisodeStatus != null) {
                holder.ivEpisodeStatus.setVisibility(showCheck ? View.VISIBLE : View.GONE);
                if (showCheck) {
                    holder.ivEpisodeStatus.setImageResource(R.drawable.ic_check);
                    holder.ivEpisodeStatus.setImageTintList(ColorStateList.valueOf(activeStatus));
                }
            }
        }

        private void animateEpisodeState(ViewHolder holder, boolean active) {
            if (holder.morphAnimator != null) {
                holder.morphAnimator.cancel();
            }
            float start = holder.progress;
            float end = active ? 1f : 0f;
            ValueAnimator animator = ValueAnimator.ofFloat(start, end);
            animator.setDuration(EXPRESSIVE_MORPH_DURATION_MS);
            animator.setInterpolator(new FastOutSlowInInterpolator());
            animator.addUpdateListener(anim -> applyEpisodeExpressiveState(holder, (float) anim.getAnimatedValue()));
            holder.morphAnimator = animator;
            animator.start();
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            if (holder.morphAnimator != null) {
                holder.morphAnimator.cancel();
                holder.morphAnimator = null;
            }

            EpisodeCheckItem item = checkItems.get(position);
            String title = item.episode.getNumber() + " Серия";
            holder.tvEpisodeName.setText(title);

            String statusTag = com.example.animelib.util.EpisodeUtils.getTransliteratedStatusLabel(item.episode.getStatus());
            if (holder.statusTagText != null) {
                if (statusTag != null && !statusTag.isEmpty()) {
                    holder.statusTagText.setText(statusTag);
                    holder.statusTagText.setVisibility(View.VISIBLE);
                } else {
                    holder.statusTagText.setVisibility(View.GONE);
                }
            }

            Context ctx = holder.itemView.getContext();
            holder.inactiveRadii = computeGridInactiveRadii(ctx, position, checkItems.size());

            int cardBgColor = ContextCompat.getColor(ctx, R.color.card_bg_color);
            int primaryTextColor = ContextCompat.getColor(ctx, R.color.primary_text_color);
            int downloadedBgColor = ContextCompat.getColor(ctx, R.color.downloaded_tag_bg);
            int downloadedFgColor = ContextCompat.getColor(ctx, R.color.downloaded_tag_fg);
            int blueColor = ContextCompat.getColor(ctx, R.color.app_text_accent_blue);
            int errorBgColor = ContextCompat.getColor(ctx, R.color.stop_btn_bg);
            int errorColor = ContextCompat.getColor(ctx, R.color.stop_btn_text);
            int neutralRipple = ContextCompat.getColor(ctx, R.color.dialog_ripple_color);

            int thinStrokePx = Math.max(1, Math.round(0.5f * ctx.getResources().getDisplayMetrics().density));
            int downloadedStrokeColor = ContextCompat.getColor(ctx, R.color.downloaded_tag_stroke);
            int unavailableStrokeColor = ContextCompat.getColor(ctx, R.color.stop_btn_stroke);
            int neutralStrokeColor = ContextCompat.getColor(ctx, R.color.chip_unselected_stroke);

            holder.itemView.setScaleX(1.0f);
            holder.itemView.setScaleY(1.0f);

            if (item.isAlreadyDownloaded) {
                holder.tileShape.setCornerRadii(holder.inactiveRadii);
                holder.tileShape.setColor(downloadedBgColor);
                holder.tileShape.setStroke(thinStrokePx, downloadedStrokeColor);
                holder.tileRipple.setColor(ColorStateList.valueOf(0x00000000));
                holder.tvStatus.setText("Скачано");
                holder.tvStatus.setTextColor(downloadedFgColor);
                holder.tvEpisodeName.setTextColor(downloadedFgColor);
                holder.itemView.setAlpha(0.95f);
                if (holder.ivEpisodeStatus != null) {
                    holder.ivEpisodeStatus.setVisibility(View.VISIBLE);
                    holder.ivEpisodeStatus.setImageResource(R.drawable.ic_check);
                    holder.ivEpisodeStatus.setImageTintList(ColorStateList.valueOf(downloadedFgColor));
                }
            } else if (item.isDownloading) {
                holder.tileShape.setCornerRadii(holder.inactiveRadii);
                holder.tileShape.setColor(cardBgColor);
                holder.tileShape.setStroke(thinStrokePx, neutralStrokeColor);
                holder.tileRipple.setColor(ColorStateList.valueOf(neutralRipple));
                holder.tvStatus.setText("Загрузка");
                holder.tvStatus.setTextColor(blueColor);
                holder.tvEpisodeName.setTextColor(primaryTextColor);
                holder.itemView.setAlpha(0.82f);
                if (holder.ivEpisodeStatus != null) {
                    holder.ivEpisodeStatus.setVisibility(View.VISIBLE);
                    holder.ivEpisodeStatus.setImageResource(R.drawable.ic_download);
                    holder.ivEpisodeStatus.setImageTintList(ColorStateList.valueOf(blueColor));
                }
            } else if (!item.isAvailable) {
                holder.tileShape.setCornerRadii(holder.inactiveRadii);
                holder.tileShape.setColor(errorBgColor);
                holder.tileShape.setStroke(thinStrokePx, unavailableStrokeColor);
                holder.tileRipple.setColor(ColorStateList.valueOf(0x00000000));
                holder.tvStatus.setText("Недоступно");
                holder.tvStatus.setTextColor(errorColor);
                holder.tvEpisodeName.setTextColor(primaryTextColor);
                holder.itemView.setAlpha(0.55f);
                if (holder.ivEpisodeStatus != null) {
                    holder.ivEpisodeStatus.setVisibility(View.GONE);
                }
            } else {
                holder.itemView.setAlpha(1.0f);
                applyEpisodeExpressiveState(holder, item.isChecked ? 1f : 0f);
            }

            holder.itemView.setOnClickListener(v -> {
                if (item.isAvailable && !item.isAlreadyDownloaded && !item.isDownloading) {
                    item.isChecked = !item.isChecked;
                    animateEpisodeState(holder, item.isChecked);
                    updateDownloadButtonText();
                }
            });
        }

        @Override
        public int getItemCount() {
            return checkItems.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            View itemContainer;
            TextView tvEpisodeName;
            TextView statusTagText;
            ImageView ivEpisodeStatus;
            TextView tvStatus;
            final GradientDrawable tileShape;
            final RippleDrawable tileRipple;
            final float activePillRadius;
            float[] inactiveRadii = new float[8];
            float progress = 0f;
            ValueAnimator morphAnimator;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                itemContainer = itemView.findViewById(R.id.itemContainer);
                tvEpisodeName = itemView.findViewById(R.id.tvEpisodeName);
                statusTagText = itemView.findViewById(R.id.statusTagText);
                ivEpisodeStatus = itemView.findViewById(R.id.ivEpisodeStatus);
                tvStatus = itemView.findViewById(R.id.tvStatus);

                Context ctx = itemView.getContext();
                activePillRadius = EPISODE_CORNER_ACTIVE_PILL_DP * ctx.getResources().getDisplayMetrics().density;
                tileShape = new GradientDrawable();
                tileShape.setShape(GradientDrawable.RECTANGLE);
                tileRipple = new RippleDrawable(
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.dialog_ripple_color)),
                        tileShape,
                        null
                );
                itemView.setBackground(tileRipple);
            }
        }
    }
}
