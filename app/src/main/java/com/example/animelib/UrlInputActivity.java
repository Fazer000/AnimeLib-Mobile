package com.example.animelib;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.animelib.data.DatabaseManager;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.SiteUtils;
import com.example.animelib.util.ThemeUtils;
import com.example.animelib.viewmodel.AppSettingsViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

public class UrlInputActivity extends AppCompatActivity {

    public static final String URL_CIS = "https://v5.animelib.org";
    public static final String URL_OTHER = "https://animelib.org";

    private MaterialCardView cardCis;
    private MaterialCardView cardOther;
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
        int initialTheme = ThemeUtils.getSavedThemePreference(this);
        ThemeUtils.applyThemeToActivity(this, initialTheme);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_url_input);

        databaseManager = new DatabaseManager(this);
        viewModel = new ViewModelProvider(this).get(AppSettingsViewModel.class);

        loadAndApplyTheme();
        initializeViews();
        setupListeners();
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
    }

    private void initializeViews() {
        cardCis = findViewById(R.id.cardCis);
        cardOther = findViewById(R.id.cardOther);
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
                updateSelectionUi();
            });
        }).start();

        saveButton.setEnabled(true);
    }

    private void setupListeners() {
        if (cardCis != null) {
            cardCis.setOnClickListener(v -> {
                selectedIsOther = false;
                selectedUrl = urlCis;
                updateSelectionUi();
            });
        }

        if (cardOther != null) {
            cardOther.setOnClickListener(v -> {
                selectedIsOther = true;
                selectedUrl = urlOther;
                updateSelectionUi();
            });
        }

        if (saveButton != null) {
            saveButton.setOnClickListener(v -> saveUrl());
        }
    }

    private void updateSelectionUi() {
        int secondaryColor = getThemeColor(R.attr.secondaryColor);
        int borderColor = getThemeColor(R.attr.borderColor);

        boolean isCis = !selectedIsOther;
        int strokeWidthPx = Math.max(1, Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 0.5f, getResources().getDisplayMetrics())));

        if (cardCis != null && cardOther != null) {
            if (isCis) {
                cardCis.setStrokeColor(secondaryColor);
                cardCis.setStrokeWidth(strokeWidthPx);
                if (selectedPillCis != null) selectedPillCis.setVisibility(View.VISIBLE);
                if (unselectedIndicatorCis != null) unselectedIndicatorCis.setVisibility(View.GONE);

                cardOther.setStrokeColor(borderColor);
                cardOther.setStrokeWidth(strokeWidthPx);
                if (selectedPillOther != null) selectedPillOther.setVisibility(View.GONE);
                if (unselectedIndicatorOther != null) unselectedIndicatorOther.setVisibility(View.VISIBLE);
            } else {
                cardOther.setStrokeColor(secondaryColor);
                cardOther.setStrokeWidth(strokeWidthPx);
                if (selectedPillOther != null) selectedPillOther.setVisibility(View.VISIBLE);
                if (unselectedIndicatorOther != null) unselectedIndicatorOther.setVisibility(View.GONE);

                cardCis.setStrokeColor(borderColor);
                cardCis.setStrokeWidth(strokeWidthPx);
                if (selectedPillCis != null) selectedPillCis.setVisibility(View.GONE);
                if (unselectedIndicatorCis != null) unselectedIndicatorCis.setVisibility(View.VISIBLE);
            }
        }
    }

    private int getThemeColor(int attrResId) {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(attrResId, typedValue, true);
        return typedValue.data;
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

