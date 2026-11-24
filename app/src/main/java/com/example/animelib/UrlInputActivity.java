package com.example.animelib;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.animelib.data.DatabaseManager;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.ItemAnimationUtils;
import com.example.animelib.util.SiteUtils;
import com.example.animelib.util.ThemeUtils;
import com.example.animelib.viewmodel.AppSettingsViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

public class UrlInputActivity extends AppCompatActivity {

    public static final String URL_CIS = "https://v5.animelib.org";
    public static final String URL_OTHER = "https://animelib.org";

    private View urlInputRoot;
    private MaterialCardView cardCis;
    private MaterialCardView cardOther;
    private View iconContainerCis;
    private View iconContainerOther;
    private ImageView ivIconCis;
    private ImageView ivIconOther;
    private View selectedPillCis;
    private View unselectedIndicatorCis;
    private View selectedPillOther;
    private View unselectedIndicatorOther;
    private TextView tvCisBadge;
    private TextView tvOtherBadge;
    private MaterialButton saveButton;

    private DatabaseManager databaseManager;
    private AppSettingsViewModel viewModel;

    private String urlCis = URL_CIS;
    private String urlOther = URL_OTHER;
    private String selectedUrl = URL_CIS;
    private boolean selectedIsOther = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!isTaskRoot()) {
            Intent intent = getIntent();
            if (intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN.equals(intent.getAction())) {
                finish();
                return;
            }
        }
        int initialTheme = ThemeUtils.getSavedThemePreference(this);
        ThemeUtils.applyThemeToActivity(this, initialTheme);

        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_url_input);

        databaseManager = DatabaseManager.getInstance(this);
        viewModel = new ViewModelProvider(this).get(AppSettingsViewModel.class);

        loadAndApplyTheme();
        applyEdgeToEdgeInsets();
        updateSystemBarsAppearance();
        initializeViews();
        setupListeners();
    }

    private void applyEdgeToEdgeInsets() {
        urlInputRoot = findViewById(R.id.urlInputRoot);
        if (urlInputRoot == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(urlInputRoot, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void updateSystemBarsAppearance() {
        try {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            boolean isDark = ThemeUtils.isDarkTheme(this);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null) {
                controller.setAppearanceLightStatusBars(!isDark);
                controller.setAppearanceLightNavigationBars(!isDark);
            }
        } catch (Exception ignored) {}
    }

    private void loadAndApplyTheme() {
        try {
            int sharedPrefTheme = ThemeUtils.getSavedThemePreference(this);
            int themeMode = databaseManager.loadThemeSetting();
            int finalTheme = (sharedPrefTheme >= 0 && sharedPrefTheme <= 2) ? sharedPrefTheme : themeMode;
            ThemeUtils.applyThemeToActivity(this, finalTheme);
            Log.d("UrlInputActivity", "Theme applied: " + finalTheme);
        } catch (Exception e) {
            Log.e("UrlInputActivity", "Failed to load and apply theme", e);
            ThemeUtils.applyThemeToActivity(this, ThemeUtils.getSavedThemePreference(this));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAndApplyTheme();
        updateSystemBarsAppearance();
    }

    private void initializeViews() {
        cardCis = findViewById(R.id.cardCis);
        cardOther = findViewById(R.id.cardOther);
        iconContainerCis = findViewById(R.id.iconContainerCis);
        iconContainerOther = findViewById(R.id.iconContainerOther);
        ivIconCis = findViewById(R.id.ivIconCis);
        ivIconOther = findViewById(R.id.ivIconOther);
        selectedPillCis = findViewById(R.id.selectedPillCis);
        unselectedIndicatorCis = findViewById(R.id.unselectedIndicatorCis);
        selectedPillOther = findViewById(R.id.selectedPillOther);
        unselectedIndicatorOther = findViewById(R.id.unselectedIndicatorOther);
        tvCisBadge = findViewById(R.id.tvCisBadge);
        tvOtherBadge = findViewById(R.id.tvOtherBadge);
        saveButton = findViewById(R.id.saveButton);

        new Thread(() -> {
            String currentUrl = getIntent().getStringExtra("current_url");
            if (currentUrl == null || currentUrl.isEmpty()) {
                currentUrl = databaseManager.getSiteUrl();
            }

            String siteKey = SiteUtils.getSiteKey(currentUrl);
            selectedIsOther = SiteUtils.isOtherRegion(currentUrl, this);

            urlCis = SiteUtils.getUrlForSiteAndRegion(siteKey, false);
            urlOther = SiteUtils.getUrlForSiteAndRegion(siteKey, true);

            selectedUrl = selectedIsOther ? urlOther : urlCis;

            runOnUiThread(() -> {
                if (tvCisBadge != null) {
                    tvCisBadge.setText(SiteUtils.getDomainHost(urlCis));
                }
                if (tvOtherBadge != null) {
                    tvOtherBadge.setText(SiteUtils.getDomainHost(urlOther));
                }
                updateSelectionUi(false);
            });
        }).start();

        if (saveButton != null) {
            saveButton.setEnabled(true);
        }
    }

    private void setupListeners() {
        if (cardCis != null) {
            cardCis.setOnClickListener(v -> {
                if (selectedIsOther) {
                    selectedIsOther = false;
                    selectedUrl = urlCis;
                    updateSelectionUi(true);
                }
            });
        }

        if (cardOther != null) {
            cardOther.setOnClickListener(v -> {
                if (!selectedIsOther) {
                    selectedIsOther = true;
                    selectedUrl = urlOther;
                    updateSelectionUi(true);
                }
            });
        }

        if (saveButton != null) {
            saveButton.setOnClickListener(v -> saveUrl());
        }
    }

    private void updateSelectionUi(boolean animate) {
        int selectedBg = ContextCompat.getColor(this, R.color.chip_selected_bg);
        int selectedStroke = ContextCompat.getColor(this, R.color.purple_primary);
        int unselectedBg = ContextCompat.getColor(this, R.color.card_bg_color);
        int unselectedStroke = ContextCompat.getColor(this, R.color.chip_unselected_stroke);
        int secondaryText = ContextCompat.getColor(this, R.color.secondary_text_color);

        int selectedStrokePx = Math.max(1, Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 0.5f, getResources().getDisplayMetrics())));
        int unselectedStrokePx = selectedStrokePx;

        boolean isCis = !selectedIsOther;

        applyCardState(
                cardCis, iconContainerCis, ivIconCis, tvCisBadge, selectedPillCis, unselectedIndicatorCis,
                isCis, selectedBg, selectedStroke, selectedStrokePx,
                unselectedBg, unselectedStroke, unselectedStrokePx, secondaryText
        );
        applyCardState(
                cardOther, iconContainerOther, ivIconOther, tvOtherBadge, selectedPillOther, unselectedIndicatorOther,
                !isCis, selectedBg, selectedStroke, selectedStrokePx,
                unselectedBg, unselectedStroke, unselectedStrokePx, secondaryText
        );

        if (animate) {
            ItemAnimationUtils.animateItemStateTransition(cardCis, isCis);
            ItemAnimationUtils.animateItemStateTransition(cardOther, !isCis);
        }
    }

    private void applyCardState(
            MaterialCardView card,
            View iconContainer,
            ImageView icon,
            TextView badge,
            View selectedPill,
            View unselectedIndicator,
            boolean isSelected,
            int selectedBg,
            int selectedStroke,
            int selectedStrokePx,
            int unselectedBg,
            int unselectedStroke,
            int unselectedStrokePx,
            int secondaryText
    ) {
        if (card == null) return;
        if (isSelected) {
            card.setCardBackgroundColor(selectedBg);
            card.setStrokeColor(selectedStroke);
            card.setStrokeWidth(selectedStrokePx);
            if (iconContainer != null) iconContainer.setBackgroundResource(R.drawable.bg_m3_tonal_pill_purple);
            if (icon != null) icon.setColorFilter(selectedStroke);
            if (badge != null) badge.setTextColor(selectedStroke);
            if (selectedPill != null) selectedPill.setVisibility(View.VISIBLE);
            if (unselectedIndicator != null) unselectedIndicator.setVisibility(View.GONE);
        } else {
            card.setCardBackgroundColor(unselectedBg);
            card.setStrokeColor(unselectedStroke);
            card.setStrokeWidth(unselectedStrokePx);
            if (iconContainer != null) iconContainer.setBackgroundResource(R.drawable.bg_m3_url_inner_box);
            if (icon != null) icon.setColorFilter(secondaryText);
            if (badge != null) badge.setTextColor(secondaryText);
            if (selectedPill != null) selectedPill.setVisibility(View.GONE);
            if (unselectedIndicator != null) unselectedIndicator.setVisibility(View.VISIBLE);
        }
    }

    private void saveUrl() {
        try {
            SiteUtils.setSavedRegionOther(this, selectedIsOther);
            viewModel.saveSettings(selectedUrl);
            databaseManager.saveSiteUrl(selectedUrl);

            Log.d("UrlInputActivity", "Region domain saved: " + selectedUrl + ", selectedIsOther=" + selectedIsOther);

            Intent resultIntent = new Intent();
            resultIntent.putExtra("site_url", selectedUrl);
            setResult(RESULT_OK, resultIntent);

            CustomToast.showSuccess(this, "Сервер сохранен: " + (selectedIsOther ? "Остальные страны" : "СНГ"));
            finish();

        } catch (Exception e) {
            Log.e("UrlInputActivity", "Failed to save region domain", e);
            CustomToast.showWarning(this, "Ошибка при сохранении сервера");
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
