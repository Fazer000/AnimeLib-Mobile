package com.example.animelib.controllers;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.animelib.R;
import com.example.animelib.util.ItemAnimationUtils;
import com.example.animelib.util.SiteUtils;
import com.google.android.material.card.MaterialCardView;

public class MainBannerController {

    public interface BannerCallback {
        void onChangeUrlRequested();
        void onDomainSelected(String targetUrl);
    }

    private View urlTopBanner;
    private MaterialCardView btnBannerSelectCis;
    private MaterialCardView btnBannerSelectOther;
    private ImageView bannerIconCis;
    private ImageView bannerIconOther;
    private TextView tvBannerCisTitle;
    private TextView tvBannerCisHost;
    private TextView tvBannerOtherTitle;
    private TextView tvBannerOtherHost;

    private final Handler urlBannerHandler = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private Runnable urlBannerRunnable;
    private final BannerCallback callback;

    private String lastUrl = null;

    public MainBannerController(Activity activity, BannerCallback callback) {
        this.activity = activity;
        this.callback = callback;
        initViews(activity);
    }

    private void initViews(Activity activity) {
        urlTopBanner = activity.findViewById(R.id.urlTopBanner);
        btnBannerSelectCis = activity.findViewById(R.id.btnBannerSelectCis);
        btnBannerSelectOther = activity.findViewById(R.id.btnBannerSelectOther);
        bannerIconCis = activity.findViewById(R.id.bannerIconCis);
        bannerIconOther = activity.findViewById(R.id.bannerIconOther);
        tvBannerCisTitle = activity.findViewById(R.id.tvBannerCisTitle);
        tvBannerCisHost = activity.findViewById(R.id.tvBannerCisHost);
        tvBannerOtherTitle = activity.findViewById(R.id.tvBannerOtherTitle);
        tvBannerOtherHost = activity.findViewById(R.id.tvBannerOtherHost);

        if (btnBannerSelectCis != null) {
            btnBannerSelectCis.setOnClickListener(v -> {
                SiteUtils.setSavedRegionOther(activity, false);
                String siteKey = SiteUtils.getSiteKey(lastUrl);
                String targetUrl = SiteUtils.getUrlForSiteAndRegion(siteKey, false);
                ItemAnimationUtils.animateItemStateTransition(v, true);
                selectDomainFromBanner(targetUrl);
            });
        }

        if (btnBannerSelectOther != null) {
            btnBannerSelectOther.setOnClickListener(v -> {
                SiteUtils.setSavedRegionOther(activity, true);
                String siteKey = SiteUtils.getSiteKey(lastUrl);
                String targetUrl = SiteUtils.getUrlForSiteAndRegion(siteKey, true);
                ItemAnimationUtils.animateItemStateTransition(v, true);
                selectDomainFromBanner(targetUrl);
            });
        }
    }

    private void selectDomainFromBanner(String targetUrl) {
        this.lastUrl = targetUrl;
        if (callback != null) {
            callback.onDomainSelected(targetUrl);
        }

        updateBannerSelectionUI(targetUrl);

        if (urlBannerRunnable != null) {
            urlBannerHandler.removeCallbacks(urlBannerRunnable);
        }
        urlBannerRunnable = this::hideUrlTopBannerAnimated;
        urlBannerHandler.postDelayed(urlBannerRunnable, 1200);
    }

    public void updateBannerSelectionUI(String currentUrl) {
        if (urlTopBanner == null) return;
        boolean isCis = !SiteUtils.isOtherRegion(currentUrl, activity);
        String siteKey = SiteUtils.getSiteKey(currentUrl);

        if (tvBannerCisHost != null) {
            tvBannerCisHost.setText(SiteUtils.getDomainHost(SiteUtils.getUrlForSiteAndRegion(siteKey, false)));
        }
        if (tvBannerOtherHost != null) {
            tvBannerOtherHost.setText(SiteUtils.getDomainHost(SiteUtils.getUrlForSiteAndRegion(siteKey, true)));
        }

        int selectedBg = ContextCompat.getColor(activity, R.color.chip_selected_bg);
        int selectedStroke = ContextCompat.getColor(activity, R.color.purple_primary);
        int unselectedBg = ContextCompat.getColor(activity, R.color.chip_unselected_bg);
        int unselectedStroke = ContextCompat.getColor(activity, R.color.chip_unselected_stroke);
        int primaryText = ContextCompat.getColor(activity, R.color.primary_text_color);
        int secondaryText = ContextCompat.getColor(activity, R.color.secondary_text_color);

        int selectedStrokePx = Math.max(1, Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 0.5f, activity.getResources().getDisplayMetrics())));
        int unselectedStrokePx = selectedStrokePx;

        applySegmentStyle(
                btnBannerSelectCis, bannerIconCis, tvBannerCisTitle, tvBannerCisHost,
                isCis, selectedBg, selectedStroke, selectedStrokePx,
                unselectedBg, unselectedStroke, unselectedStrokePx, primaryText, secondaryText
        );
        applySegmentStyle(
                btnBannerSelectOther, bannerIconOther, tvBannerOtherTitle, tvBannerOtherHost,
                !isCis, selectedBg, selectedStroke, selectedStrokePx,
                unselectedBg, unselectedStroke, unselectedStrokePx, primaryText, secondaryText
        );
    }

    private void applySegmentStyle(
            MaterialCardView card,
            ImageView checkIcon,
            TextView titleView,
            TextView hostView,
            boolean isSelected,
            int selectedBg,
            int selectedStroke,
            int selectedStrokePx,
            int unselectedBg,
            int unselectedStroke,
            int unselectedStrokePx,
            int primaryText,
            int secondaryText
    ) {
        if (card == null) return;
        if (isSelected) {
            card.setCardBackgroundColor(selectedBg);
            card.setStrokeColor(selectedStroke);
            card.setStrokeWidth(selectedStrokePx);
            if (checkIcon != null) checkIcon.setVisibility(View.VISIBLE);
            if (titleView != null) titleView.setTextColor(selectedStroke);
            if (hostView != null) hostView.setTextColor(selectedStroke);
        } else {
            card.setCardBackgroundColor(unselectedBg);
            card.setStrokeColor(unselectedStroke);
            card.setStrokeWidth(unselectedStrokePx);
            if (checkIcon != null) checkIcon.setVisibility(View.GONE);
            if (titleView != null) titleView.setTextColor(primaryText);
            if (hostView != null) hostView.setTextColor(secondaryText);
        }
    }

    public void showUrlTopBannerAnimated(String url) {
        if (urlTopBanner == null) return;
        this.lastUrl = url;

        updateBannerSelectionUI(url);

        if (urlBannerRunnable != null) {
            urlBannerHandler.removeCallbacks(urlBannerRunnable);
        }

        urlTopBanner.setVisibility(View.VISIBLE);
        urlTopBanner.setAlpha(0f);

        urlTopBanner.post(() -> {
            int height = urlTopBanner.getHeight();
            if (height <= 0) {
                height = (int) (64 * urlTopBanner.getResources().getDisplayMetrics().density);
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
                height = (int) (72 * urlTopBanner.getResources().getDisplayMetrics().density);
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
