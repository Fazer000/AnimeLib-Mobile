package com.example.animelib.ui;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.adapters.SiteSelectionAdapter;
import com.example.animelib.models.SiteOption;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.example.animelib.util.ItemAnimationUtils;
import com.example.animelib.util.SiteUtils;

import java.util.List;

public class SiteSelectionBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnSiteSelectedListener {
        void onSiteSelected(SiteOption site);
    }

    public interface OnAnimeLibLinkSelectedListener {
        void onAnimeLibLinkSelected(boolean isOtherRegion, String targetUrl);
    }

    private final List<SiteOption> sites;
    private boolean isOtherRegion;
    private final OnSiteSelectedListener listener;
    private final OnAnimeLibLinkSelectedListener linkListener;

    private View linkCisOption;
    private View linkOtherOption;
    private View linkCisSelectedPill;
    private View linkCisUnselected;
    private View linkOtherSelectedPill;
    private View linkOtherUnselected;

    public SiteSelectionBottomSheet(@NonNull Context context,
                                   List<SiteOption> sites,
                                   OnSiteSelectedListener listener) {
        this(context, sites, SiteUtils.isSavedRegionOther(context), listener, null);
    }

    public SiteSelectionBottomSheet(@NonNull Context context,
                                   List<SiteOption> sites,
                                   boolean isOtherRegion,
                                   OnSiteSelectedListener listener,
                                   OnAnimeLibLinkSelectedListener linkListener) {
        super(context, R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.sites = sites;
        this.isOtherRegion = isOtherRegion;
        this.listener = listener;
        this.linkListener = linkListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_site_selection, null);
        setContentView(view);

        FloatingBottomSheetUtils.setupFloatingStyle(this);

        RecyclerView recyclerView = view.findViewById(R.id.siteRecyclerView);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            recyclerView.setNestedScrollingEnabled(false);
            SiteSelectionAdapter adapter = new SiteSelectionAdapter(sites, site -> {
                dismiss();
                if (listener != null) {
                    listener.onSiteSelected(site);
                }
            });
            recyclerView.setAdapter(adapter);
        }

        setupAnimeLibLinkSection(view);

        setCancelable(true);
        setCanceledOnTouchOutside(true);

        triggerVibration();
    }

    private void setupAnimeLibLinkSection(@NonNull View view) {
        linkCisOption = view.findViewById(R.id.linkCisOption);
        linkOtherOption = view.findViewById(R.id.linkOtherOption);
        linkCisSelectedPill = view.findViewById(R.id.linkCisSelectedPill);
        linkCisUnselected = view.findViewById(R.id.linkCisUnselected);
        linkOtherSelectedPill = view.findViewById(R.id.linkOtherSelectedPill);
        linkOtherUnselected = view.findViewById(R.id.linkOtherUnselected);

        TextView tvLinkCisUrl = view.findViewById(R.id.tvLinkCisUrl);
        TextView tvLinkOtherUrl = view.findViewById(R.id.tvLinkOtherUrl);

        String cisUrl = SiteUtils.getUrlForSiteAndRegion("animelib", false);
        String otherUrl = SiteUtils.getUrlForSiteAndRegion("animelib", true);

        if (tvLinkCisUrl != null) {
            tvLinkCisUrl.setText(SiteUtils.getDomainHost(cisUrl));
        }
        if (tvLinkOtherUrl != null) {
            tvLinkOtherUrl.setText(SiteUtils.getDomainHost(otherUrl));
        }

        updateLinkSelectionUi(isOtherRegion, false);

        if (linkCisOption != null) {
            linkCisOption.setOnClickListener(v -> handleLinkClick(v, false, cisUrl));
        }
        if (linkOtherOption != null) {
            linkOtherOption.setOnClickListener(v -> handleLinkClick(v, true, otherUrl));
        }
    }

    private void handleLinkClick(View clickedView, boolean selectedOther, String targetUrl) {
        this.isOtherRegion = selectedOther;
        updateLinkSelectionUi(selectedOther, true);
        ItemAnimationUtils.animateItemClick(clickedView, () -> {
            dismiss();
            if (linkListener != null) {
                linkListener.onAnimeLibLinkSelected(selectedOther, targetUrl);
            }
        });
    }

    private void updateLinkSelectionUi(boolean selectedOther, boolean animate) {
        if (linkCisSelectedPill != null) {
            linkCisSelectedPill.setVisibility(selectedOther ? View.GONE : View.VISIBLE);
        }
        if (linkCisUnselected != null) {
            linkCisUnselected.setVisibility(selectedOther ? View.VISIBLE : View.GONE);
        }
        if (linkOtherSelectedPill != null) {
            linkOtherSelectedPill.setVisibility(selectedOther ? View.VISIBLE : View.GONE);
        }
        if (linkOtherUnselected != null) {
            linkOtherUnselected.setVisibility(selectedOther ? View.GONE : View.VISIBLE);
        }
        if (animate) {
            ItemAnimationUtils.animateItemStateTransition(linkCisOption, !selectedOther);
            ItemAnimationUtils.animateItemStateTransition(linkOtherOption, selectedOther);
        }
    }

    private void triggerVibration() {
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(40);
                }
            }
        } catch (Exception ignored) {}
        try {
            if (getWindow() != null && getWindow().getDecorView() != null) {
                getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        } catch (Exception ignored) {}
    }
}
