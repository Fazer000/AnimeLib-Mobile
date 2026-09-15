package com.example.animelib.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.adapters.VideoServerAdapter;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.FlexibleBottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

public class VideoServerBottomSheet extends FlexibleBottomSheetDialog {
    private final VideoServerAdapter.OnServerSelectedListener listener;
    private VideoServerAdapter adapter;
    private OnBackPressedListener onBackPressedListener;

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    public VideoServerBottomSheet(Context context, String currentDomain, VideoServerAdapter.OnServerSelectedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.listener = listener;

        List<VideoServerAdapter.ServerOption> options = new ArrayList<>();
        options.add(new VideoServerAdapter.ServerOption(VideoUrlHelper.DOMAIN_MAIN, VideoUrlHelper.getDomainDisplayName(VideoUrlHelper.DOMAIN_MAIN), VideoUrlHelper.URL_MAIN));
        options.add(new VideoServerAdapter.ServerOption(VideoUrlHelper.DOMAIN_SECONDARY_1, VideoUrlHelper.getDomainDisplayName(VideoUrlHelper.DOMAIN_SECONDARY_1), VideoUrlHelper.URL_SECONDARY_1));
        options.add(new VideoServerAdapter.ServerOption(VideoUrlHelper.DOMAIN_SECONDARY_2, VideoUrlHelper.getDomainDisplayName(VideoUrlHelper.DOMAIN_SECONDARY_2), VideoUrlHelper.URL_SECONDARY_2));

        this.adapter = new VideoServerAdapter(options, currentDomain, domain -> {
            if (listener != null) {
                listener.onServerSelected(domain);
            }
            dismiss();
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        @SuppressLint("InflateParams") View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_video_server, null);
        setContentView(view);
        com.example.animelib.util.FloatingBottomSheetUtils.setupFloatingStyle(this);

        ImageButton backButton = view.findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> {
            if (onBackPressedListener != null) {
                onBackPressedListener.onBackPressed();
            }
            dismiss();
        });

        RecyclerView recyclerView = view.findViewById(R.id.serverRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setNestedScrollingEnabled(false);
        recyclerView.setAdapter(adapter);

        setCancelable(true);
    }

    public void setOnBackPressedListener(OnBackPressedListener listener) {
        this.onBackPressedListener = listener;
    }
}
