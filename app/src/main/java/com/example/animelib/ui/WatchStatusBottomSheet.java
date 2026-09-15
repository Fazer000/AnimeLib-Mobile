package com.example.animelib.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.adapters.WatchStatusAdapter;
import com.example.animelib.managers.WatchStatusManager;
import com.example.animelib.models.WatchStatusItem;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;

public class WatchStatusBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnStatusSelectedListener {
        void onStatusSelected(WatchStatusItem item);
    }

    private final Object currentStatusId;
    private final OnStatusSelectedListener listener;

    public WatchStatusBottomSheet(@NonNull Context context,
                                  Object currentStatusId,
                                  OnStatusSelectedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.currentStatusId = currentStatusId;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_watch_status, null);
        setContentView(view);

        FloatingBottomSheetUtils.setupFloatingStyle(this);

        RecyclerView recyclerView = view.findViewById(R.id.rvWatchStatuses);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            recyclerView.setNestedScrollingEnabled(false);
            WatchStatusAdapter adapter = new WatchStatusAdapter(
                    WatchStatusManager.STATUS_ITEMS,
                    currentStatusId,
                    item -> {
                        dismiss();
                        if (listener != null) {
                            listener.onStatusSelected(item);
                        }
                    }
            );
            recyclerView.setAdapter(adapter);
        }

        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }
}
