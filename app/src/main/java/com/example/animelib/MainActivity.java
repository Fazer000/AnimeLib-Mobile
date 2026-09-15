package com.example.animelib;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.animelib.api.ApiService;
import com.example.animelib.controllers.MainAuthController;
import com.example.animelib.controllers.MainBannerController;
import com.example.animelib.controllers.MainWebViewController;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.dialogs.ThemeSelectionDialog;
import com.example.animelib.models.ToastData;
import com.example.animelib.models.UpdateInfo;
import com.example.animelib.ui.JSInjectionsHandler;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.ThemeUtils;
import com.example.animelib.util.UpdateManager;
import com.example.animelib.viewmodel.AppSettingsViewModel;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.gson.Gson;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private CircularProgressIndicator spinner;
    private FrameLayout spinnerBackground;
    private SwipeRefreshLayout swipeRefreshLayout;
    private FrameLayout fullscreenContainer;

    private long lastBackPressTime = 0;
    private static final int BACK_PRESS_INTERVAL = 2000;
    private static final int REQUEST_URL_INPUT = 1001;

    private OkHttpClient httpClient;
    private Executor executor;
    private Gson gson;

    private AppSettingsViewModel viewModel;
    private ApiService apiService;
    private JSInjectionsHandler JSInjectionsHandler;
    private ThemeSelectionDialog themeDialog;
    private DatabaseManager databaseManager;

    private boolean isUrlInputShowing = false;

    private MainBannerController bannerController;
    private MainAuthController authController;
    private MainWebViewController webViewController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int initialTheme = ThemeUtils.getSavedThemePreference(this);
        ThemeUtils.applyThemeToActivity(this, initialTheme);

        SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            isUrlInputShowing = savedInstanceState.getBoolean("isUrlInputShowing", false);
        }

        webView = findViewById(R.id.webView);
        spinner = findViewById(R.id.spinner);
        spinnerBackground = findViewById(R.id.spinnerBackground);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);

        httpClient = new OkHttpClient();
        executor = Executors.newSingleThreadExecutor();
        gson = new Gson();

        viewModel = new ViewModelProvider(this).get(AppSettingsViewModel.class);
        apiService = new ApiService(this);
        databaseManager = new DatabaseManager(this);

        authController = new MainAuthController(this, databaseManager);
        JSInjectionsHandler = new JSInjectionsHandler(this);
        themeDialog = new ThemeSelectionDialog(this, apiService);

        bannerController = new MainBannerController(this, new MainBannerController.BannerCallback() {
            @Override
            public void onChangeUrlRequested() {
                showUrlInputActivity();
            }

            @Override
            public void onDomainSelected(String targetUrl) {
                try {
                    if (viewModel != null) viewModel.saveSettings(targetUrl);
                    if (databaseManager != null) databaseManager.saveSiteUrl(targetUrl);
                } catch (Exception e) {
                    Log.e("MainActivity", "Failed to save domain from banner", e);
                }
                loadUrl(targetUrl);
            }
        });

        webViewController = new MainWebViewController(
                this,
                webView,
                spinner,
                spinnerBackground,
                swipeRefreshLayout,
                fullscreenContainer,
                JSInjectionsHandler,
                new MainWebViewController.WebViewCallback() {
                    @Override
                    public void onWatchUrlIntercepted(String resolvedUrl) {
                        getAuthAndStartVideoPlayer(resolvedUrl);
                    }

                    @Override
                    public void onDomainChanged(String newDomain) {
                        Log.d("MainActivity", "Domain changed to: " + newDomain);
                    }
                }
        );

        try {
            android.webkit.WebView tempWebView = new android.webkit.WebView(this);
            tempWebView.clearCache(true);
            tempWebView.clearHistory();
            tempWebView.destroy();
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to clear WebView cache", e);
        }

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );

        loadAndApplyTheme();
        authController.updateTokensOnStartup();

        setupRefreshLayout();
        setupBackPressHandler();

        checkAndLoadUrl();
        checkForUpdatesOnStartup();
    }

    private void checkForUpdatesOnStartup() {
        UpdateManager.checkForUpdates(this, httpClient, new UpdateManager.CheckUpdateCallback() {
            @Override
            public void onUpdateCheckResult(boolean hasUpdate, UpdateInfo updateInfo, String currentVersion) {
                if (hasUpdate && updateInfo != null) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && !isUrlInputShowing) {
                            CustomToast.showUpdateAlert(MainActivity.this, updateInfo);
                        }
                    });
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.d("MainActivity", "Startup update check error: " + errorMessage);
            }
        });
    }

    private void loadAndApplyTheme() {
        executor.execute(() -> {
            try {
                int sharedPrefTheme = ThemeUtils.getSavedThemePreference(this);
                int dbThemeMode = databaseManager.loadThemeSetting();
                int finalTheme = (sharedPrefTheme >= 0 && sharedPrefTheme <= 2) ? sharedPrefTheme : dbThemeMode;

                runOnUiThread(() -> {
                    ThemeUtils.applyThemeToActivity(MainActivity.this, finalTheme);
                    updateSystemBarColors();
                });
            } catch (Exception e) {
                Log.e("Theme", "Failed to load and apply theme", e);
                runOnUiThread(() -> {
                    ThemeUtils.applyThemeToActivity(MainActivity.this, 0);
                    updateSystemBarColors();
                });
            }
        });
    }

    private void updateSystemBarColors() {
        int nightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean isDarkTheme = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int appearance = isDarkTheme ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                controller.setSystemBarsAppearance(appearance, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            View decorView = getWindow().getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (isDarkTheme) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decorView.setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isUrlInputShowing", isUrlInputShowing);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra("EXTRA_OPEN_URL")) {
            String openUrl = intent.getStringExtra("EXTRA_OPEN_URL");
            intent.removeExtra("EXTRA_OPEN_URL");
            if (openUrl != null && !openUrl.trim().isEmpty()) {
                if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
                }
                loadUrl(openUrl.trim());
            }
        }
    }

    private void checkAndLoadUrl() {
        if (viewModel == null) return;

        if (getIntent() != null && getIntent().hasExtra("EXTRA_OPEN_URL")) {
            String openUrl = getIntent().getStringExtra("EXTRA_OPEN_URL");
            getIntent().removeExtra("EXTRA_OPEN_URL");
            if (openUrl != null && !openUrl.trim().isEmpty()) {
                if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
                }
                loadUrl(openUrl.trim());
                checkApiForToast();
                return;
            }
        }

        viewModel.getSettings().observe(this, appSettings -> {
            String siteUrl = null;

            if (appSettings != null && appSettings.getSiteUrl() != null && !appSettings.getSiteUrl().trim().isEmpty()) {
                siteUrl = appSettings.getSiteUrl().trim();
                Log.d("MainActivity", "URL loaded from AppSettings: " + siteUrl);
            }

            if (siteUrl == null || siteUrl.isEmpty()) {
                siteUrl = databaseManager.getSiteUrl();
                if (siteUrl != null && !siteUrl.trim().isEmpty()) {
                    Log.d("MainActivity", "URL loaded from DatabaseManager: " + siteUrl);
                    viewModel.saveSettings(siteUrl);
                }
            }

            if (siteUrl != null && !siteUrl.trim().isEmpty()) {
                loadUrl(siteUrl);
            } else {
                Log.d("MainActivity", "No site URL found, opening UrlInputActivity");
                showUrlInputActivity();
            }
        });

        checkApiForToast();
    }

    private void loadUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            showUrlInputActivity();
            return;
        }

        String targetUrl = url.trim();
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://" + targetUrl;
        }

        Log.d("MainActivity", "Loading URL: " + targetUrl);
        webViewController.loadUrlInWebView(targetUrl);
        if (bannerController != null) {
            bannerController.showUrlTopBannerAnimated(targetUrl);
        }
    }

    private void showUrlInputActivity() {
        if (isUrlInputShowing) {
            return;
        }
        isUrlInputShowing = true;
        Intent intent = new Intent(this, UrlInputActivity.class);
        String currentUrl = null;
        if (webView != null && webView.getUrl() != null && !webView.getUrl().isEmpty()) {
            currentUrl = webView.getUrl();
        }
        if (currentUrl == null || currentUrl.isEmpty()) {
            currentUrl = databaseManager.getSiteUrl();
        }
        if (currentUrl != null && !currentUrl.isEmpty()) {
            intent.putExtra("current_url", currentUrl);
        }
        startActivityForResult(intent, REQUEST_URL_INPUT);
    }

    private void checkApiForToast() {
        if (apiService != null) {
            apiService.checkApiForToast(new ApiService.ToastCheckCallback() {
                @Override
                public void onToastReceived(String message, String newUrl) {
                    runOnUiThread(() -> {
                        if (message != null && !message.trim().isEmpty()) {
                            CustomToast.showInfo(MainActivity.this, message.trim());
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    Log.d("MainActivity", "No toast or error fetching toast: " + error);
                }
            });
        }
    }

    public void disallowInterceptTouch() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.requestDisallowInterceptTouchEvent(true);
        }
    }

    private float startTouchX = 0f;
    private float startTouchY = 0f;

    @SuppressLint("ClickableViewAccessibility")
    private void setupRefreshLayout() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> webViewController.reloadWebView());
            swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> webView != null && webView.getScrollY() > 0);
            swipeRefreshLayout.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }

        if (webView != null) {
            webView.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startTouchX = event.getX();
                        startTouchY = event.getY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(event.getX() - startTouchX);
                        float dy = Math.abs(event.getY() - startTouchY);
                        if (dx > dy && dx > 10 && swipeRefreshLayout != null) {
                            swipeRefreshLayout.requestDisallowInterceptTouchEvent(true);
                        }
                        break;
                }
                return false;
            });
        }
    }

    private void setupBackPressHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webViewController != null && webViewController.isCustomViewShowing()) {
                    webViewController.hideCustomView();
                } else if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastBackPressTime < BACK_PRESS_INTERVAL) {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    } else {
                        lastBackPressTime = currentTime;
                        CustomToast.showInfo(MainActivity.this, "Нажмите еще раз для выхода");
                    }
                }
            }
        });
    }

    public void showThemeDialog() {
        if (themeDialog != null) {
            themeDialog.show();
        }
    }

    public void showSearchFragment() {
        SearchFragment searchFragment = new SearchFragment();

        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_scale_fade_in,
                        R.anim.fragment_scale_fade_out,
                        R.anim.fragment_scale_fade_in,
                        R.anim.fragment_scale_fade_out
                )
                .add(R.id.fragment_container, searchFragment)
                .addToBackStack(null)
                .commit();

        Log.d("MainActivity", "Search fragment shown with scale-fade animation");
    }

    public void loadUrlInWebView(String url) {
        if (webViewController != null && url != null) {
            String fullUrl = url.startsWith("/") ? resolveBaseUrl() + url : url;
            webViewController.loadUrlInWebView(fullUrl);
        }
    }

    private String resolveBaseUrl() {
        String currentUrl = webView != null ? webView.getUrl() : null;
        if (currentUrl != null && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) {
            try {
                java.net.URL parsed = new java.net.URL(currentUrl);
                return parsed.getProtocol() + "://" + parsed.getHost();
            } catch (Exception e) {
                Log.w("MainActivity", "Failed to parse WebView URL: " + currentUrl, e);
            }
        }
        return "https://" + getString(R.string.site_url);
    }

    public void getAuthFromLocalStorage() {
        getAuthFromLocalStorage(null);
    }

    public void getAuthFromLocalStorage(Runnable callback) {
        if (authController != null) {
            authController.getAuthFromLocalStorage(webView, callback);
        } else if (callback != null) {
            callback.run();
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    public void getAuthAndStartVideoPlayer(String animeUrl) {
        if (authController != null) {
            authController.getAuthAndStartVideoPlayer(webView, animeUrl);
        }
    }

    public void clearAuthToken() {
        if (authController != null) {
            authController.clearAuthToken();
        }
    }

    public void saveOAuthTokenFromJson(String jsonString) {
        if (authController != null) {
            authController.saveOAuthTokenFromJson(jsonString);
        }
    }

    public void saveAuthMeDataFromJson(String jsonString) {
        if (authController != null) {
            authController.saveAuthMeDataFromJson(jsonString);
        }
    }

    public void showCustomSelectDialog(String dialogDataJson) {
        try {
            Gson gsonInstance = gson != null ? gson : new Gson();
            SelectDialogData dialogData = gsonInstance.fromJson(dialogDataJson, SelectDialogData.class);

            if (dialogData == null || dialogData.options == null || dialogData.values == null) {
                Log.e("MainActivity", "Invalid dialog data received");
                return;
            }

            com.example.animelib.ui.CustomSelectBottomSheet bottomSheet =
                new com.example.animelib.ui.CustomSelectBottomSheet(
                    this,
                    dialogData.options,
                    dialogData.values,
                    dialogData.currentValue,
                    (value, text) -> updateSelectButton(dialogData.selectId, value, text)
                );
            bottomSheet.show();

            Log.d("MainActivity", "Custom select bottom sheet shown for: " + dialogData.selectId);

        } catch (Exception e) {
            Log.e("MainActivity", "Error showing custom select dialog", e);
        }
    }

    public void updateSelectButton(String selectId, String selectedValue, String selectedText) {
        if (webView != null) {
            String jsCode = String.format(
                "if (window.customSelectHandler) { " +
                "  window.customSelectHandler.updateButtonAfterSelection('%s', '%s', '%s'); " +
                "}",
                selectId, selectedValue, selectedText
            );

            webView.evaluateJavascript(jsCode, result -> {
                Log.d("MainActivity", "Select button updated: " + selectId + " = " + selectedText);
            });
        }
    }

    private static class SelectDialogData {
        public String title;
        public java.util.List<String> options;
        public java.util.List<String> values;
        public String currentValue;
        public String selectId;
    }

    public void showDomainChangeSpinner() {
        if (webViewController != null) {
            webViewController.showDomainChangeSpinner();
        }
    }

    public void hideDomainChangeSpinner() {
        if (webViewController != null) {
            webViewController.hideDomainChangeSpinner();
        }
    }

    public void showSiteSelectionBottomSheet() {
        try {
            String currentUrl = null;
            if (webView != null) {
                currentUrl = webView.getUrl();
            }
            if (currentUrl == null || currentUrl.isEmpty()) {
                currentUrl = databaseManager.getSiteUrl();
            }

            boolean isOtherRegion = com.example.animelib.util.SiteUtils.isOtherRegion(currentUrl, this);

            String animeUrl = com.example.animelib.util.SiteUtils.getUrlForSiteAndRegion("animelib", isOtherRegion);
            String mangaUrl = com.example.animelib.util.SiteUtils.getUrlForSiteAndRegion("mangalib", isOtherRegion);
            String ranobeUrl = com.example.animelib.util.SiteUtils.getUrlForSiteAndRegion("ranobelib", isOtherRegion);

            String currentKey = com.example.animelib.util.SiteUtils.getSiteKey(currentUrl);

            java.util.List<com.example.animelib.models.SiteOption> allSites = new java.util.ArrayList<>();
            allSites.add(new com.example.animelib.models.SiteOption("animelib", "AnimeLib", animeUrl, R.drawable.ic_site_animelib));
            allSites.add(new com.example.animelib.models.SiteOption("mangalib", "MangaLib", mangaUrl, R.drawable.ic_site_mangalib));
            allSites.add(new com.example.animelib.models.SiteOption("ranobelib", "RanobeLib", ranobeUrl, R.drawable.ic_site_ranobelib));

            java.util.List<com.example.animelib.models.SiteOption> filteredSites = new java.util.ArrayList<>();
            for (com.example.animelib.models.SiteOption option : allSites) {
                if (!option.getKey().equalsIgnoreCase(currentKey)) {
                    filteredSites.add(option);
                }
            }

            com.example.animelib.ui.SiteSelectionBottomSheet bottomSheet =
                new com.example.animelib.ui.SiteSelectionBottomSheet(
                    this,
                    filteredSites,
                    site -> {
                        boolean isSelectedOther = com.example.animelib.util.SiteUtils.isOtherRegion(site.getUrl(), this);
                        com.example.animelib.util.SiteUtils.setSavedRegionOther(this, isSelectedOther);
                        viewModel.saveSettings(site.getUrl());
                        databaseManager.saveSiteUrl(site.getUrl());
                        loadUrl(site.getUrl());
                    }
                );
            bottomSheet.setOnDismissListener(dialog -> {
                if (webView != null) {
                    webView.post(() -> webView.evaluateJavascript("if (window.animelibKillSitesPopup) window.animelibKillSitesPopup();", null));
                }
            });
            bottomSheet.show();
            if (webView != null) {
                webView.post(() -> webView.evaluateJavascript("if (window.animelibKillSitesPopup) window.animelibKillSitesPopup();", null));
            }

            Log.d("MainActivity", "Site selection bottom sheet shown, currentKey=" + currentKey + ", isOtherRegion=" + isOtherRegion);
        } catch (Exception e) {
            Log.e("MainActivity", "Error showing site selection bottom sheet", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_URL_INPUT) {
            isUrlInputShowing = false;
            if (resultCode == RESULT_OK && data != null) {
                String siteUrl = data.getStringExtra("site_url");
                if (siteUrl != null && !siteUrl.isEmpty()) {
                    viewModel.saveSettings(siteUrl);
                    loadUrl(siteUrl);
                    Log.d("MainActivity", "URL received from UrlInputActivity: " + siteUrl);
                }
            } else {
                if (viewModel.getSettings().getValue() == null || viewModel.getSettings().getValue().getSiteUrl() == null) {
                    CustomToast.showWarning(this, "Для работы приложения необходимо указать URL сайта");
                    showUrlInputActivity();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (bannerController != null) {
            bannerController.destroy();
        }
        if (webViewController != null) {
            webViewController.destroy();
        }
        if (apiService != null) {
            apiService.shutdown();
        }

        super.onDestroy();
    }
}
