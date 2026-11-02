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
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.animelib.api.ApiService;
import com.example.animelib.controllers.MainAuthController;
import com.example.animelib.controllers.MainBannerController;
import com.example.animelib.controllers.MainWebViewController;
import com.example.animelib.controllers.PlayerSplashScreenController;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.dialogs.ThemeSelectionDialog;
import com.example.animelib.models.UpdateInfo;
import com.example.animelib.ui.JSInjectionsHandler;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.ThemeUtils;
import com.example.animelib.util.UpdateManager;
import com.example.animelib.viewmodel.AppSettingsViewModel;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.gson.Gson;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

public class MainActivity extends AppCompatActivity {

    private static final String SEARCH_FRAGMENT_TAG = "search_dialog";

    private WebView webView;
    private CircularProgressIndicator spinner;
    private FrameLayout spinnerBackground;
    private SwipeRefreshLayout swipeRefreshLayout;
    private FrameLayout fullscreenContainer;

    private long lastBackPressTime = 0;
    private static final int BACK_PRESS_INTERVAL = 2000;
    private static final int REQUEST_URL_INPUT = 1001;

    private OkHttpClient httpClient;
    private ExecutorService executor;
    private Gson gson;

    private AppSettingsViewModel viewModel;
    private ApiService apiService;
    private JSInjectionsHandler jsInjectionsHandler;
    private ThemeSelectionDialog themeDialog;
    private DatabaseManager databaseManager;

    private boolean isUrlInputShowing = false;
    private boolean hasInitialUrlLoaded = false;
    private boolean hasShownInitialBanner = false;
    private boolean hasCompletedInitialWebSplash = false;
    private String pendingBannerUrlAfterSplash = null;

    private static final long INITIAL_WEB_SPLASH_TIMEOUT_MS = 16000L;

    private MainBannerController bannerController;
    private MainAuthController authController;
    private MainWebViewController webViewController;
    private PlayerSplashScreenController webSplashController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int initialTheme = ThemeUtils.getSavedThemePreference(this);
        ThemeUtils.applyThemeToActivity(this, initialTheme);

        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        splashScreen.setOnExitAnimationListener(SplashScreenViewProvider::remove);

        super.onCreate(savedInstanceState);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                getSplashScreen().setOnExitAnimationListener(android.window.SplashScreenView::remove);
            } catch (Exception ignored) {}
        }
        JSInjectionsHandler.preloadAssetsAsync(this);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            isUrlInputShowing = savedInstanceState.getBoolean("isUrlInputShowing", false);
            hasShownInitialBanner = savedInstanceState.getBoolean("hasShownInitialBanner", false);
            hasCompletedInitialWebSplash = savedInstanceState.getBoolean("hasCompletedInitialWebSplash", false);
            pendingBannerUrlAfterSplash = savedInstanceState.getString("pendingBannerUrlAfterSplash", null);
        }

        webView = findViewById(R.id.webView);
        spinner = findViewById(R.id.spinner);
        spinnerBackground = findViewById(R.id.spinnerBackground);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);

        webSplashController = new PlayerSplashScreenController(INITIAL_WEB_SPLASH_TIMEOUT_MS);
        webSplashController.initViews(
                findViewById(R.id.mainRootContainer),
                R.id.webSplashScreenOverlay,
                R.id.webSplashContent,
                R.id.webSplashLogoView,
                R.id.webSplashStatusText
        );
        webSplashController.setOnSafetyTimeoutCallback(() -> {
            hasCompletedInitialWebSplash = true;
            updateSystemBarColors();
            flushPendingBannerAfterSplash();
        });
        if (hasCompletedInitialWebSplash) {
            webSplashController.dismissImmediately();
        } else {
            webSplashController.showOnEntry();
            if (isUrlInputShowing) {
                webSplashController.pauseTimers();
            }
            updateSystemBarColors();
        }

        httpClient = new OkHttpClient();
        executor = Executors.newSingleThreadExecutor();
        gson = new Gson();

        viewModel = new ViewModelProvider(this).get(AppSettingsViewModel.class);
        apiService = new ApiService(this);
        databaseManager = DatabaseManager.getInstance(this);

        authController = new MainAuthController(this, databaseManager);
        jsInjectionsHandler = new JSInjectionsHandler(this);
        themeDialog = new ThemeSelectionDialog(this, apiService);

        bannerController = new MainBannerController(this, new MainBannerController.BannerCallback() {
            @Override
            public void onChangeUrlRequested() {
                showUrlInputActivity();
            }

            @Override
            public void onDomainSelected(String targetUrl) {
                hasInitialUrlLoaded = true;
                try {
                    if (viewModel != null) viewModel.saveSettings(targetUrl);
                    if (databaseManager != null) databaseManager.saveSiteUrl(targetUrl);
                } catch (Exception e) {
                    Log.e("MainActivity", "Failed to save domain from banner", e);
                }
                loadUrl(targetUrl, true);
            }
        });

        webViewController = new MainWebViewController(
                this,
                webView,
                spinner,
                spinnerBackground,
                swipeRefreshLayout,
                fullscreenContainer,
                jsInjectionsHandler,
                new MainWebViewController.WebViewCallback() {
                    @Override
                    public void onWatchUrlIntercepted(String resolvedUrl) {
                        getAuthAndStartVideoPlayer(resolvedUrl);
                    }

                    @Override
                    public void onDomainChanged(String newDomain) {
                        Log.d("MainActivity", "Domain changed to: " + newDomain);
                        runOnUiThread(() -> updateSystemBarColors(webView != null ? webView.getUrl() : newDomain));
                    }

                    @Override
                    public void onInitialPageLoaded(String loadedUrl) {
                        runOnUiThread(() -> dismissInitialWebSplash(loadedUrl));
                    }

                    @Override
                    public void onInitialPageLoadFailed(String failingUrl) {
                        runOnUiThread(() -> {
                            pendingBannerUrlAfterSplash = null;
                            dismissInitialWebSplash(failingUrl);
                        });
                    }

                    @Override
                    public boolean isInitialSplashActive() {
                        return isInitialWebSplashActive();
                    }
                }
        );

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );

        loadAndApplyTheme();
        authController.updateTokensOnStartup();

        setupRefreshLayout();
        setupBackPressHandler();

        checkAndLoadUrl(savedInstanceState);
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
                // Заодно прогреваем кэш URL сайта вне UI-потока
                databaseManager.getSiteUrl();

                int sharedPrefTheme = ThemeUtils.getSavedThemePreference(this);
                int dbThemeMode = databaseManager.loadThemeSetting();
                int finalTheme = (sharedPrefTheme >= 0 && sharedPrefTheme <= 2) ? sharedPrefTheme : dbThemeMode;

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    ThemeUtils.applyThemeToActivity(MainActivity.this, finalTheme);
                    updateSystemBarColors();
                });
            } catch (Exception e) {
                Log.e("Theme", "Failed to load and apply theme", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    ThemeUtils.applyThemeToActivity(MainActivity.this, 0);
                    updateSystemBarColors();
                });
            }
        });
    }

    public void onAppThemeSelected(int themeMode) {
        ThemeUtils.applyThemeToActivity(this, themeMode);
        updateSystemBarColors();
        if (jsInjectionsHandler != null && webView != null) {
            jsInjectionsHandler.syncAppThemeToWebView(webView, themeMode);
        }
    }

    private String getFastKnownSiteUrl() {
        if (webView != null && webView.getUrl() != null && !webView.getUrl().isEmpty()
                && !"about:blank".equals(webView.getUrl())) {
            return webView.getUrl();
        }
        if (databaseManager != null) {
            String cached = databaseManager.getCachedSiteUrlOrNull();
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }
        if (viewModel != null && viewModel.getSettings().getValue() != null) {
            String vmUrl = viewModel.getSettings().getValue().getSiteUrl();
            if (vmUrl != null && !vmUrl.trim().isEmpty()) {
                return vmUrl.trim();
            }
        }
        return null;
    }

    private boolean isInitialWebSplashActive() {
        return !hasCompletedInitialWebSplash
                && webSplashController != null
                && !webSplashController.hasDismissed();
    }

    private void dismissInitialWebSplash(String url) {
        if (hasCompletedInitialWebSplash) {
            return;
        }
        hasCompletedInitialWebSplash = true;
        updateSystemBarColors(url != null ? url : getFastKnownSiteUrl());

        if (webSplashController != null && !webSplashController.hasDismissed()) {
            webSplashController.dismissWithAnimation(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    updateSystemBarColors();
                    flushPendingBannerAfterSplash();
                }
            });
        } else {
            flushPendingBannerAfterSplash();
        }
    }

    private void flushPendingBannerAfterSplash() {
        if (pendingBannerUrlAfterSplash != null && bannerController != null) {
            String bannerUrl = pendingBannerUrlAfterSplash;
            pendingBannerUrlAfterSplash = null;
            bannerController.showUrlTopBannerAnimated(bannerUrl);
        }
    }

    public void updateSystemBarColors() {
        updateSystemBarColors(getFastKnownSiteUrl());
    }

    public void updateSystemBarColors(String url) {
        if (isFinishing() || isDestroyed()) return;

        boolean isDarkTheme = ThemeUtils.isDarkTheme(this);
        boolean splashActive = isInitialWebSplashActive();
        int headerColor = ThemeUtils.getSiteHeaderColor(this, url);
        int statusBarColor = splashActive
                ? ContextCompat.getColor(this, R.color.bs_bg_color)
                : headerColor;
        int navBarColor = ContextCompat.getColor(
                this,
                splashActive ? R.color.bs_bg_color : (isDarkTheme ? R.color.dt_primary_color : R.color.lt_primary_color)
        );

        try {
            getWindow().setStatusBarColor(statusBarColor);
            getWindow().setNavigationBarColor(navBarColor);
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to set system bar color", e);
        }

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setBackgroundColor(headerColor);
        }
        if (webView != null) {
            try {
                webView.setBackgroundColor(headerColor);
            } catch (Exception ignored) {}
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int appearance = isDarkTheme ? 0 : mask;
                controller.setSystemBarsAppearance(appearance, mask);
            }
        } else {
            View decorView = getWindow().getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (isDarkTheme) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decorView.setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isUrlInputShowing", isUrlInputShowing);
        outState.putBoolean("hasShownInitialBanner", hasShownInitialBanner);
        outState.putBoolean("hasCompletedInitialWebSplash", hasCompletedInitialWebSplash);
        if (pendingBannerUrlAfterSplash != null) {
            outState.putString("pendingBannerUrlAfterSplash", pendingBannerUrlAfterSplash);
        }
        if (webViewController != null) {
            webViewController.saveState(outState);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (webViewController != null) {
            webViewController.onStart();
        }
    }

    @Override
    protected void onStop() {
        if (webViewController != null) {
            webViewController.onStop();
        }
        super.onStop();
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
                hasInitialUrlLoaded = true;
                loadUrl(openUrl.trim(), false);
            }
        }
    }

    private void checkAndLoadUrl(Bundle savedInstanceState) {
        if (viewModel == null) return;

        if (getIntent() != null && getIntent().hasExtra("EXTRA_OPEN_URL")) {
            String openUrl = getIntent().getStringExtra("EXTRA_OPEN_URL");
            getIntent().removeExtra("EXTRA_OPEN_URL");
            if (openUrl != null && !openUrl.trim().isEmpty()) {
                if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
                }
                hasInitialUrlLoaded = true;
                loadUrl(openUrl.trim(), false);
                checkApiForToast();
                return;
            }
        }

        // Восстанавливаем историю и открытую страницу при пересоздании Activity (смена темы / поворот)
        if (savedInstanceState != null && webViewController != null && webViewController.restoreState(savedInstanceState)) {
            hasInitialUrlLoaded = true;
            hasCompletedInitialWebSplash = true;
            if (webSplashController != null) {
                webSplashController.dismissImmediately();
            }
            updateSystemBarColors(webView != null ? webView.getUrl() : null);
            checkApiForToast();
            return;
        }

        // Быстрый путь: если URL уже прогрет в памяти DatabaseManager — начинаем загрузку сразу без ожидания LiveData
        String cachedSiteUrl = databaseManager != null ? databaseManager.getCachedSiteUrlOrNull() : null;
        if (cachedSiteUrl != null && !cachedSiteUrl.isEmpty()) {
            hasInitialUrlLoaded = true;
            loadUrl(cachedSiteUrl, !hasShownInitialBanner);
            hasShownInitialBanner = true;
            checkApiForToast();
            return;
        }

        viewModel.getSettings().observe(this, appSettings -> {
            if (hasInitialUrlLoaded) {
                return;
            }

            String siteUrl = null;
            if (appSettings != null && appSettings.getSiteUrl() != null && !appSettings.getSiteUrl().trim().isEmpty()) {
                siteUrl = appSettings.getSiteUrl().trim();
                Log.d("MainActivity", "URL loaded from AppSettings: " + siteUrl);
            }

            if (siteUrl != null && !siteUrl.isEmpty()) {
                hasInitialUrlLoaded = true;
                loadUrl(siteUrl, !hasShownInitialBanner);
                hasShownInitialBanner = true;
            } else {
                String fallbackCached = databaseManager != null ? databaseManager.getCachedSiteUrlOrNull() : null;
                if (fallbackCached != null && !fallbackCached.isEmpty()) {
                    hasInitialUrlLoaded = true;
                    loadUrl(fallbackCached, !hasShownInitialBanner);
                    hasShownInitialBanner = true;
                } else {
                    Log.d("MainActivity", "No site URL found, opening UrlInputActivity");
                    showUrlInputActivity();
                }
            }
        });

        checkApiForToast();
    }

    private void loadUrl(String url, boolean showTopBanner) {
        if (url == null || url.trim().isEmpty()) {
            showUrlInputActivity();
            return;
        }

        String targetUrl = url.trim();
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://" + targetUrl;
        }

        Log.d("MainActivity", "Loading URL: " + targetUrl);
        if (isInitialWebSplashActive() && webSplashController != null) {
            webSplashController.restartTimers();
        }
        updateSystemBarColors(targetUrl);
        webViewController.loadUrlInWebView(targetUrl);
        if (showTopBanner && bannerController != null) {
            hasShownInitialBanner = true;
            if (isInitialWebSplashActive()) {
                pendingBannerUrlAfterSplash = targetUrl;
            } else {
                bannerController.showUrlTopBannerAnimated(targetUrl);
            }
        }
    }

    private void showUrlInputActivity() {
        if (isUrlInputShowing) {
            return;
        }
        isUrlInputShowing = true;
        if (isInitialWebSplashActive() && webSplashController != null) {
            webSplashController.pauseTimers();
        }
        Intent intent = new Intent(this, UrlInputActivity.class);
        String currentUrl = getFastKnownSiteUrl();
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
                        if (!isFinishing() && !isDestroyed() && message != null && !message.trim().isEmpty()) {
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
                switch (event.getActionMasked()) {
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
        if (themeDialog != null && !isFinishing() && !isDestroyed()) {
            themeDialog.show();
        }
    }

    public void showSearchFragment() {
        if (isFinishing() || isDestroyed() || getSupportFragmentManager().isStateSaved()) {
            return;
        }
        if (getSupportFragmentManager().findFragmentByTag(SEARCH_FRAGMENT_TAG) != null) {
            return;
        }

        SearchFragment searchFragment = new SearchFragment();

        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_scale_fade_in,
                        R.anim.fragment_scale_fade_out,
                        R.anim.fragment_scale_fade_in,
                        R.anim.fragment_scale_fade_out
                )
                .add(R.id.fragment_container, searchFragment, SEARCH_FRAGMENT_TAG)
                .addToBackStack(SEARCH_FRAGMENT_TAG)
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
        String cached = databaseManager != null ? databaseManager.getCachedSiteUrlOrNull() : null;
        if (cached != null && !cached.isEmpty()) {
            return cached;
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
        if (isFinishing() || isDestroyed()) return;
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

    private static String escapeJsSingleQuoted(String raw) {
        if (raw == null) return "";
        return raw
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    public void updateSelectButton(String selectId, String selectedValue, String selectedText) {
        if (webView != null) {
            String jsCode = String.format(
                "if (window.customSelectHandler) { " +
                "  window.customSelectHandler.updateButtonAfterSelection('%s', '%s', '%s'); " +
                "}",
                escapeJsSingleQuoted(selectId),
                escapeJsSingleQuoted(selectedValue),
                escapeJsSingleQuoted(selectedText)
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
        if (isFinishing() || isDestroyed()) return;
        try {
            String currentUrl = getFastKnownSiteUrl();
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
                        hasInitialUrlLoaded = true;
                        boolean isSelectedOther = com.example.animelib.util.SiteUtils.isOtherRegion(site.getUrl(), this);
                        com.example.animelib.util.SiteUtils.setSavedRegionOther(this, isSelectedOther);
                        viewModel.saveSettings(site.getUrl());
                        databaseManager.saveSiteUrl(site.getUrl());
                        loadUrl(site.getUrl(), true);
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
                    hasInitialUrlLoaded = true;
                    viewModel.saveSettings(siteUrl);
                    databaseManager.saveSiteUrl(siteUrl);
                    loadUrl(siteUrl, true);
                    Log.d("MainActivity", "URL received from UrlInputActivity: " + siteUrl);
                }
            } else {
                if (getFastKnownSiteUrl() == null) {
                    CustomToast.showWarning(this, "Для работы приложения необходимо указать URL сайта");
                    showUrlInputActivity();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (webSplashController != null) {
            webSplashController.dismissImmediately();
        }
        if (bannerController != null) {
            bannerController.destroy();
        }
        if (webViewController != null) {
            webViewController.destroy();
        }
        if (authController != null) {
            authController.shutdown();
        }
        if (apiService != null) {
            apiService.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }

        super.onDestroy();
    }
}
