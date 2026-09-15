package com.example.animelib.controllers;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.example.animelib.R;

public class MainBannerController {

    public interface BannerCallback {
        void onChangeUrlRequested();
        void onDomainSelected(String targetUrl);
    }

    private View urlTopBanner;
    private TextView tvCurrentUrlBanner;
    private View btnCloseUrlBanner;
    private View btnBannerSelectCis;
    private View btnBannerSelectOther;

    private final Handler urlBannerHandler = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private Runnable urlBannerRunnable;
    private BannerCallback callback;

    public MainBannerController(Activity activity, BannerCallback callback) {
        this.activity = activity;
        this.callback = callback;
        initViews(activity);
    }

    private String lastUrl = null;

    private void initViews(Activity activity) {
        urlTopBanner = activity.findViewById(R.id.urlTopBanner);
        tvCurrentUrlBanner = activity.findViewById(R.id.tvCurrentUrlBanner);
        btnCloseUrlBanner = activity.findViewById(R.id.btnCloseUrlBanner);
        btnBannerSelectCis = activity.findViewById(R.id.btnBannerSelectCis);
        btnBannerSelectOther = activity.findViewById(R.id.btnBannerSelectOther);

        if (btnCloseUrlBanner != null) {
            btnCloseUrlBanner.setOnClickListener(v -> hideUrlTopBannerAnimated());
        }

        if (btnBannerSelectCis != null) {
            btnBannerSelectCis.setOnClickListener(v -> {
                com.example.animelib.util.SiteUtils.setSavedRegionOther(activity, false);
                String siteKey = com.example.animelib.util.SiteUtils.getSiteKey(lastUrl);
                String targetUrl = com.example.animelib.util.SiteUtils.getUrlForSiteAndRegion(siteKey, false);
                selectDomainFromBanner(targetUrl);
            });
        }

        if (btnBannerSelectOther != null) {
            btnBannerSelectOther.setOnClickListener(v -> {
                com.example.animelib.util.SiteUtils.setSavedRegionOther(activity, true);
                String siteKey = com.example.animelib.util.SiteUtils.getSiteKey(lastUrl);
                String targetUrl = com.example.animelib.util.SiteUtils.getUrlForSiteAndRegion(siteKey, true);
                selectDomainFromBanner(targetUrl);
            });
        }
    }

    private void selectDomainFromBanner(String targetUrl) {
        if (callback != null) {
            callback.onDomainSelected(targetUrl);
        }

        updateBannerSelectionUI(targetUrl);
        if (tvCurrentUrlBanner != null) {
            tvCurrentUrlBanner.setText("Текущий адрес: " + targetUrl);
        }

        urlBannerHandler.postDelayed(this::hideUrlTopBannerAnimated, 1200);
    }

    public void updateBannerSelectionUI(String currentUrl) {
        if (urlTopBanner == null) return;
        boolean isCis = !com.example.animelib.util.SiteUtils.isOtherRegion(currentUrl, activity);

        View bannerPillCis = urlTopBanner.findViewById(R.id.bannerPillCis);
        View bannerIndicatorOther = urlTopBanner.findViewById(R.id.bannerIndicatorOther);

        if (btnBannerSelectCis != null) {
            btnBannerSelectCis.setBackgroundResource(isCis ? R.drawable.card_resize_selected : R.drawable.card_resize_unselected);
            if (bannerPillCis != null) bannerPillCis.setVisibility(isCis ? View.VISIBLE : View.GONE);
        }

        if (btnBannerSelectOther != null) {
            btnBannerSelectOther.setBackgroundResource(!isCis ? R.drawable.card_resize_selected : R.drawable.card_resize_unselected);
            if (bannerIndicatorOther != null) bannerIndicatorOther.setVisibility(!isCis ? View.VISIBLE : View.GONE);
        }
    }

    public void showUrlTopBannerAnimated(String url) {
        if (urlTopBanner == null) return;
        this.lastUrl = url;

        if (tvCurrentUrlBanner != null && url != null && !url.trim().isEmpty()) {
            tvCurrentUrlBanner.setText("Текущий адрес: " + url);
        }
        updateBannerSelectionUI(url);

        if (urlBannerRunnable != null) {
            urlBannerHandler.removeCallbacks(urlBannerRunnable);
        }

        urlTopBanner.setVisibility(View.VISIBLE);
        urlTopBanner.setAlpha(0f);

        urlTopBanner.post(() -> {
            int height = urlTopBanner.getHeight();
            if (height <= 0) {
                height = (int) (50 * urlTopBanner.getResources().getDisplayMetrics().density);
            }
            urlTopBanner.setTranslationY(-height - 50f);
            urlTopBanner.animate()
                    .translationY(0f)
                    .alpha(1.0f)
                    .setDuration(400)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        });

        urlBannerRunnable = this::hideUrlTopBannerAnimated;
        urlBannerHandler.postDelayed(urlBannerRunnable, 5000);
    }

    public void hideUrlTopBannerAnimated() {
        if (urlBannerRunnable != null) {
            urlBannerHandler.removeCallbacks(urlBannerRunnable);
        }

        if (urlTopBanner != null && urlTopBanner.getVisibility() == View.VISIBLE) {
            int height = urlTopBanner.getHeight();
            if (height <= 0) {
                height = (int) (70 * urlTopBanner.getResources().getDisplayMetrics().density);
            }
            urlTopBanner.animate()
                    .translationY(-height - 100f)
                    .alpha(0f)
                    .setDuration(350)
                    .setInterpolator(new AccelerateInterpolator(1.5f))
                    .withEndAction(() -> urlTopBanner.setVisibility(View.GONE))
                    .start();
        }
    }

    public void destroy() {
        if (urlBannerHandler != null && urlBannerRunnable != null) {
            urlBannerHandler.removeCallbacks(urlBannerRunnable);
        }
    }
}
