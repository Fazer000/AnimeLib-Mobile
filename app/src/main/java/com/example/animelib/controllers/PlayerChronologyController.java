package com.example.animelib.controllers;

import android.content.Context;
import android.util.Log;
import android.view.View;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.FranchiseResponse;
import com.example.animelib.ui.FranchiseMapBottomSheet;

/**
 * Контроллер для управления кнопкой интерактивной карты франшизы и фоновой предзагрузкой
 */
public class PlayerChronologyController {

    private static final String TAG = "PlayerChronologyCtrl";

    public interface ChronologyCallback {
        Context getContext();
        ApiService getApiService();
        void safeRunOnUiThread(Runnable runnable);
        PlayerDialogsController getPlayerDialogsController();
        boolean isOfflineMode();
    }

    private final ChronologyCallback callback;

    private View btnOpenChronologyMap;
    private FranchiseResponse currentFranchiseResponse;
    private long currentShikimoriId = -1;

    public PlayerChronologyController(ChronologyCallback callback) {
        this.callback = callback;
    }

    public void initViews(View rootView) {
        if (rootView == null) return;

        btnOpenChronologyMap = rootView.findViewById(R.id.btnOpenChronologyMap);

        if (btnOpenChronologyMap != null) {
            boolean hasValidId = currentShikimoriId > 0;
            btnOpenChronologyMap.setEnabled(hasValidId);
            btnOpenChronologyMap.setAlpha(hasValidId ? 1.0f : 0.4f);
            btnOpenChronologyMap.setOnClickListener(v -> {
                if (btnOpenChronologyMap.isEnabled() && currentShikimoriId > 0) {
                    openChronologyMapSheet();
                }
            });
        }
    }

    public void loadChronologyForAnime(AnimeInfoResponse animeInfo) {
        if (callback != null && callback.isOfflineMode()) {
            if (btnOpenChronologyMap != null) {
                btnOpenChronologyMap.setEnabled(false);
                btnOpenChronologyMap.setAlpha(0.4f);
            }
            return;
        }

        if (animeInfo == null || animeInfo.getData() == null) {
            Log.w(TAG, "animeInfo is null, cannot load franchise");
            if (btnOpenChronologyMap != null) {
                btnOpenChronologyMap.setEnabled(false);
                btnOpenChronologyMap.setAlpha(0.4f);
            }
            return;
        }

        String shikimoriHref = animeInfo.getData().getShikimori_href();
        long shikimoriId = ApiService.extractShikimoriId(shikimoriHref);
        this.currentShikimoriId = shikimoriId;

        if (shikimoriId <= 0) {
            Log.d(TAG, "No valid Shikimori ID found in shikimori_href: " + shikimoriHref);
            if (btnOpenChronologyMap != null) {
                btnOpenChronologyMap.setEnabled(false);
                btnOpenChronologyMap.setAlpha(0.4f);
            }
            return;
        }

        // Enable Map button now that valid Shikimori ID was received from Animelib API
        if (btnOpenChronologyMap != null) {
            btnOpenChronologyMap.setVisibility(View.VISIBLE);
            btnOpenChronologyMap.setEnabled(true);
            btnOpenChronologyMap.setAlpha(1.0f);
        }

        // Check if already in cache
        FranchiseResponse cached = ApiService.getCachedFranchise(shikimoriId);
        if (cached != null) {
            Log.d(TAG, "Franchise already in memory cache for ID: " + shikimoriId);
            this.currentFranchiseResponse = cached;
            return;
        }

        // Preload franchise in background so when user taps "Карта", it opens instantly
        ApiService apiService = callback != null ? callback.getApiService() : null;
        if (apiService == null) return;

        Log.d(TAG, "Preloading franchise data for Shikimori ID: " + shikimoriId);
        apiService.getFranchise(shikimoriId, new ApiService.FranchiseCallback() {
            @Override
            public void onFranchiseReceived(FranchiseResponse response) {
                currentFranchiseResponse = response;
                Log.d(TAG, "Franchise preloaded successfully for ID: " + shikimoriId);
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "Franchise background preload warning: " + error);
            }
        });
    }

    public void openChronologyMapSheet() {
        if (callback == null) return;
        Context context = callback.getContext();
        if (context == null) return;

        try {
            FranchiseMapBottomSheet sheet = new FranchiseMapBottomSheet(context, currentFranchiseResponse, currentShikimoriId);
            sheet.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing FranchiseMapBottomSheet", e);
        }
    }
}
