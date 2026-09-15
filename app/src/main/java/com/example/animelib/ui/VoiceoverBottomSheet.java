package com.example.animelib.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager2.widget.ViewPager2;

import com.example.animelib.R;
import com.example.animelib.adapters.PlayerTabsAdapter;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

public class VoiceoverBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnPlayerSelectedListener {
        void onPlayerSelected(EpisodeResponse.PlayerData playerData);
    }

    public interface OnDownloadRequestedListener {
        void onDownloadRequested();
    }

    private List<EpisodeResponse.PlayerData> animelibPlayers;
    private List<EpisodeResponse.PlayerData> kodikPlayers;
    private EpisodeResponse.PlayerData currentPlayerData;
    private boolean isLoading = false;

    private final OnPlayerSelectedListener selectionListener;
    private final OnDownloadRequestedListener downloadListener;

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private View loadingOverlay;
    private FrameLayout playersContainer;
    private PlayerTabsAdapter tabsAdapter;
    private ImageButton btnDownloadFromMenu;

    private String activeTab = "animelib";

    public VoiceoverBottomSheet(@NonNull Context context,
                                List<EpisodeResponse.PlayerData> animelibPlayers,
                                List<EpisodeResponse.PlayerData> kodikPlayers,
                                EpisodeResponse.PlayerData currentPlayerData,
                                boolean isLoading,
                                OnPlayerSelectedListener selectionListener,
                                OnDownloadRequestedListener downloadListener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.animelibPlayers = animelibPlayers != null ? animelibPlayers : new ArrayList<>();
        this.kodikPlayers = kodikPlayers != null ? kodikPlayers : new ArrayList<>();
        this.currentPlayerData = currentPlayerData;
        this.isLoading = isLoading;
        this.selectionListener = selectionListener;
        this.downloadListener = downloadListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_voiceovers, null);
        setContentView(view);
        setCanceledOnTouchOutside(true);
        setCancelable(true);

        FloatingBottomSheetUtils.setupFloatingStyle(this);

        tabLayout = view.findViewById(R.id.tabLayout);
        viewPager = view.findViewById(R.id.viewPager);
        loadingOverlay = view.findViewById(R.id.menuLoadingOverlay);
        playersContainer = view.findViewById(R.id.playersContainer);

        ImageButton closeButton = view.findViewById(R.id.closeMenuButton);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }

        btnDownloadFromMenu = view.findViewById(R.id.btnDownloadFromMenu);
        if (btnDownloadFromMenu != null) {
            updateDownloadButtonState(com.example.animelib.services.DownloadService.isRunning());
            btnDownloadFromMenu.setOnClickListener(v -> {
                dismiss();
                if (downloadListener != null) {
                    downloadListener.onDownloadRequested();
                }
            });
        }

        setupViewPager(view);
        setupSearchAndSort(view);
        setLoading(isLoading);
        setupBottomSheetBehavior();
    }

    private void setupSearchAndSort(View view) {
        if (view == null) return;
        EditText etSearch = view.findViewById(R.id.etSearchVoiceovers);
        ImageButton btnClear = view.findViewById(R.id.btnClearVoiceoversSearch);
        View btnSort = view.findViewById(R.id.btnSortVoiceovers);
        TextView tvSortText = view.findViewById(R.id.tvSortVoiceovers);

        if (tvSortText != null && tabsAdapter != null) {
            tvSortText.setText(com.example.animelib.util.VoiceoverSortHelper.getSortLabel(tabsAdapter.getSortType()));
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String q = s.toString();
                    if (btnClear != null) {
                        btnClear.setVisibility(q.trim().isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    if (tabsAdapter != null) {
                        tabsAdapter.setFilterQuery(q);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnClear != null && etSearch != null) {
            btnClear.setOnClickListener(v -> etSearch.setText(""));
        }

        if (btnSort != null) {
            btnSort.setOnClickListener(v -> {
                if (tabsAdapter != null && getContext() != null) {
                    com.example.animelib.util.VoiceoverSortHelper.showSortPopup(btnSort, getContext(), tabsAdapter);
                }
            });
        }
    }

    public void updateDownloadButtonState(boolean isDownloading) {
        if (btnDownloadFromMenu != null) {
            btnDownloadFromMenu.setAlpha(1.0f);
            com.example.animelib.util.DownloadAnimationUtils.updateDownloadIconAnimation(btnDownloadFromMenu, isDownloading);
        }
    }

    @NonNull
    @Override
    public BottomSheetBehavior<FrameLayout> getBehavior() {
        return super.getBehavior();
    }

    private void setupBottomSheetBehavior() {
        BottomSheetBehavior<FrameLayout> behavior = getBehavior();
        if (behavior != null) {
            behavior.setFitToContents(true);
            behavior.setSkipCollapsed(true);
            behavior.setHideable(true);
            int screenHeight = getContext().getResources().getDisplayMetrics().heightPixels;
            behavior.setPeekHeight(screenHeight * 2);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private void setupViewPager(View view) {
        if (viewPager == null) return;

        if (playersContainer instanceof MaxHeightFrameLayout) {
            MaxHeightFrameLayout mhl = (MaxHeightFrameLayout) playersContainer;
            mhl.setMaxHeightRatio(0.45f);
            mhl.setFixedHeight(true);
        }

        determineActiveTab();

        tabsAdapter = new PlayerTabsAdapter(
                animelibPlayers,
                kodikPlayers,
                currentPlayerData,
                playerData -> {
                    dismiss();
                    if (selectionListener != null) {
                        selectionListener.onPlayerSelected(playerData);
                    }
                }
        );
        viewPager.setAdapter(tabsAdapter);

        if (tabLayout != null) {
            new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
                if (position == 0) {
                    tab.setText("AnimeLib");
                } else if (position == 1) {
                    tab.setText("Kodik");
                }
            }).attach();

            for (int i = 0; i < tabLayout.getTabCount(); i++) {
                com.google.android.material.tabs.TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab != null && tab.view != null) {
                    tab.view.setClipToOutline(true);
                }
            }
        }

        int targetPage = "kodik".equals(activeTab) ? 1 : 0;
        viewPager.setCurrentItem(targetPage, false);
    }

    private void determineActiveTab() {
        boolean hasAnimelib = animelibPlayers != null && !animelibPlayers.isEmpty();
        boolean hasKodik = kodikPlayers != null && !kodikPlayers.isEmpty();

        if (currentPlayerData != null && currentPlayerData.getPlayer() != null) {
            String p = currentPlayerData.getPlayer().toLowerCase();
            if ("kodik".equals(p) && hasKodik) {
                activeTab = "kodik";
                return;
            } else if ("animelib".equals(p) && hasAnimelib) {
                activeTab = "animelib";
                return;
            }
        }

        if (!hasAnimelib && hasKodik) {
            activeTab = "kodik";
        } else {
            activeTab = "animelib";
        }
    }

    public void updateData(List<EpisodeResponse.PlayerData> animelibPlayers,
                           List<EpisodeResponse.PlayerData> kodikPlayers,
                           EpisodeResponse.PlayerData currentPlayerData) {
        this.animelibPlayers = animelibPlayers != null ? animelibPlayers : new ArrayList<>();
        this.kodikPlayers = kodikPlayers != null ? kodikPlayers : new ArrayList<>();
        this.currentPlayerData = currentPlayerData;

        determineActiveTab();

        if (tabsAdapter != null) {
            tabsAdapter.updateData(this.animelibPlayers, this.kodikPlayers, this.currentPlayerData);
        }

        if (viewPager != null) {
            int selectedIndex = "kodik".equals(activeTab) ? 1 : 0;
            viewPager.setCurrentItem(selectedIndex, false);
        }

        BottomSheetBehavior<FrameLayout> behavior = getBehavior();
        if (behavior != null && isShowing()) {
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    public void setLoading(boolean loading) {
        this.isLoading = loading;
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }
}
