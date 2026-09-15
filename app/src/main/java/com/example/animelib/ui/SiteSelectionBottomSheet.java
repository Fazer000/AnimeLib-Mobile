package com.example.animelib.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.adapters.SiteSelectionAdapter;
import com.example.animelib.models.SiteOption;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;

import java.util.List;

public class SiteSelectionBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnSiteSelectedListener {
        void onSiteSelected(SiteOption site);
    }

    private final List<SiteOption> sites;
    private final OnSiteSelectedListener listener;

    public SiteSelectionBottomSheet(@NonNull Context context,
                                   List<SiteOption> sites,
                                   OnSiteSelectedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.sites = sites;
        this.listener = listener;
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

        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }
}
