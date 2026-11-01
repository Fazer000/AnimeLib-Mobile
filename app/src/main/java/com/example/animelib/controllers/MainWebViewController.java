package com.example.animelib.controllers;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.animelib.MainActivity;
import com.example.animelib.R;
import com.example.animelib.ui.DownloadsActivity;
import com.example.animelib.ui.JSInjectionsHandler;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.CookieSyncManager;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.LatestViewsManager;
import com.example.animelib.util.ThemeUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainWebViewController {

    private static final String TAG = "MainWebViewController";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final Map<String, String> NAVIGATION_HEADERS;
    private static final Set<String> WARMED_HOSTS = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        Map<String, String> map = new HashMap<>();
        map.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        map.put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        NAVIGATION_HEADERS = Collections.unmodifiableMap(map);
    }

    public interface WebViewCallback {
        void onWatchUrlIntercepted(String resolvedUrl);
        void onDomainChanged(String newDomain);
    }

    private final AppCompatActivity activity;
    private final WebView webView;
    private final CircularProgressIndicator spinner;
    private final FrameLayout spinnerBackground;
    private final SwipeRefreshLayout swipeRefreshLayout;
    private final FrameLayout fullscreenContainer;

    private LinearProgressIndicator webProgressBar;
    private View webErrorLayout;
    private TextView tvErrorTitle;
    private TextView tvErrorMessage;
    private MaterialButton btnRetryLoad;
    private View btnOpenDownloads;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private String currentDomain = null;
    private String lastFailedUrl = null;
    private String lastRequestedUrl = null;
    private boolean isFirstLoad = true;
    private boolean isDestroyed = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideSpinnerRunnable = this::hideDomainChangeSpinner;
    private final Runnable hideTopProgressRunnable = () -> {
        if (webProgressBar != null) {
            webProgressBar.setVisibility(View.GONE);
        }
    };

    private final JSInjectionsHandler jsInjectionsHandler;
    private final WebViewCallback callback;

    public MainWebViewController(
            AppCompatActivity activity,
            WebView webView,
            CircularProgressIndicator spinner,
            FrameLayout spinnerBackground,
            SwipeRefreshLayout swipeRefreshLayout,
            FrameLayout fullscreenContainer,
            JSInjectionsHandler jsInjectionsHandler,
            WebViewCallback callback) {
        this.activity = activity;
        this.webView = webView;
        this.spinner = spinner;
        this.spinnerBackground = spinnerBackground;
        this.swipeRefreshLayout = swipeRefreshLayout;
        this.fullscreenContainer = fullscreenContainer;
        this.jsInjectionsHandler = jsInjectionsHandler;
        this.callback = callback;

        initViews();
        setupWebView();
    }

    private void initViews() {
        webProgressBar = activity.findViewById(R.id.webProgressBar);
        webErrorLayout = activity.findViewById(R.id.webErrorLayout);
        tvErrorTitle = activity.findViewById(R.id.tvErrorTitle);
        tvErrorMessage = activity.findViewById(R.id.tvErrorMessage);
        btnRetryLoad = activity.findViewById(R.id.btnRetryLoad);
        btnOpenDownloads = activity.findViewById(R.id.btnOpenDownloads);

        if (btnRetryLoad != null) {
            btnRetryLoad.setOnClickListener(v -> reloadWebView());
        }

        if (btnOpenDownloads != null) {
            btnOpenDownloads.setOnClickListener(v -> {
                Intent intent = new Intent(activity, DownloadsActivity.class);
                activity.startActivity(intent);
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void setupWebView() {
        if (webView == null) return;

        // Предотвращаем белую вспышку при первом рендере в тёмной теме
        try {
            webView.setBackgroundColor(ThemeUtils.getSiteHeaderColor(activity, null));
        } catch (Exception ignored) {}

        WebSettings webSettings = webView.getSettings();

        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setCacheMode(isNetworkAvailable() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);

        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportZoom(false);

        webSettings.setTextZoom(100);
        webSettings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);

        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(false);
        webSettings.setAllowUniversalAccessFromFileURLs(false);

        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setGeolocationEnabled(false);
        webSettings.setUserAgentString(USER_AGENT);
        webSettings.setNeedInitialFocus(false);

        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setBlockNetworkImage(false);
        webSettings.setBlockNetworkLoads(false);
        webSettings.setSafeBrowsingEnabled(false);
        try {
            webSettings.setForceDark(WebSettings.FORCE_DARK_AUTO);
        } catch (Exception ignored) {}

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webSettings.setOffscreenPreRaster(true);
        WebView.setWebContentsDebuggingEnabled(false);

        webView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        webView.setScrollbarFadingEnabled(true);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setInitialScale(0);
        webSettings.setMinimumFontSize(8);
        webSettings.setMinimumLogicalFontSize(8);
        webSettings.setDefaultFontSize(16);
        webSettings.setDefaultFixedFontSize(13);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        CookieSyncManager.warmUpAsync(activity);

        if (jsInjectionsHandler != null) {
            jsInjectionsHandler.addJavaScriptInterface(webView);
        }

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void setCookie(String name, String value, String domain) {
                CookieManager.getInstance().setCookie(domain, name + "=" + value);
                CookieSyncManager.copyCookieStringToAllDomains(name + "=" + value);
            }

            @android.webkit.JavascriptInterface
            public String getCookie(String name, String domain) {
                String cookies = CookieManager.getInstance().getCookie(domain);
                if (cookies != null) {
                    String[] cookieArray = cookies.split(";");
                    for (String cookie : cookieArray) {
                        String[] parts = cookie.trim().split("=", 2);
                        if (parts.length == 2 && parts[0].trim().equals(name)) {
                            return parts[1].trim();
                        }
                    }
                }
                return null;
            }
        }, "CookieManager");

        webView.setWebViewClient(new WebViewClient() {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                String url = request.getUrl().toString();

                if (url.startsWith("intent://")) {
                    handleIntentUrl(url);
                    return true;
                }

                if (url.startsWith("tel:") || url.startsWith("mailto:") ||
                        url.startsWith("sms:") || url.startsWith("market:")) {
                    handleSpecialScheme(url);
                    return true;
                }

                // Перехватываем переход в нативный плеер ДО проверки сети, чтобы скачанные серии открывались и в офлайне
                if (url.contains("/watch") || url.contains("episode")) {
                    String currentUrl = view.getUrl();
                    String resolvedUrl = VideoUrlHelper.resolveAnimeUrl(url, currentUrl);
                    if (callback != null) {
                        callback.onWatchUrlIntercepted(resolvedUrl);
                    }
                    return true;
                }

                boolean online = isNetworkAvailable();
                view.getSettings().setCacheMode(online ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);

                String newDomain = extractDomain(url);
                if (newDomain != null) {
                    prewarmDnsAsync(newDomain);
                    if (currentDomain != null && !currentDomain.equalsIgnoreCase(newDomain)) {
                        showDomainChangeSpinner();
                    }
                }

                return false;
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);

                if (request != null && request.isForMainFrame()) {
                    String url = request.getUrl() != null ? request.getUrl().toString() : "";
                    int statusCode = errorResponse != null ? errorResponse.getStatusCode() : 0;
                    Log.w(TAG, "HTTP Error " + statusCode + " for URL: " + url);

                    if (statusCode == 404) {
                        activity.runOnUiThread(() -> {
                            if (webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                showCustomErrorPage(url, "HTTP 404: Страница не найдена");
                            }
                        });
                    } else if (statusCode >= 500) {
                        showCustomErrorPage(url, "Ошибка сервера HTTP " + statusCode);
                    }
                }
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                if (handler != null) {
                    handler.proceed();
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (url == null || "about:blank".equals(url)) {
                    return;
                }

                lastRequestedUrl = url;
                if (webErrorLayout != null && webErrorLayout.getVisibility() == View.VISIBLE) {
                    webErrorLayout.setVisibility(View.GONE);
                }
                if (webView != null && webView.getVisibility() != View.VISIBLE && customView == null) {
                    webView.setVisibility(View.VISIBLE);
                }

                showTopProgress(15);

                String newDomain = extractDomain(url);
                boolean isEmbedOrPlayer =
                        url.contains("kodik") || url.contains("rutube") ||
                        url.contains("youtube") || url.contains("alloha") ||
                        url.contains("/player") || url.contains("iframe");

                boolean domainChanged = newDomain != null && (currentDomain == null || !currentDomain.equalsIgnoreCase(newDomain));
                if (!isEmbedOrPlayer && (isFirstLoad || (currentDomain != null && domainChanged))) {
                    showDomainChangeSpinner();
                }

                if (domainChanged) {
                    currentDomain = newDomain;
                    if (callback != null) {
                        callback.onDomainChanged(newDomain);
                    }
                } else if (activity instanceof MainActivity) {
                    ((MainActivity) activity).updateSystemBarColors(url);
                }

                applyForceDarkForUrl(view, url);
                CookieSyncManager.syncFromUrl(url);
                CookieSyncManager.injectAuthLocalStorage(view);

                if (jsInjectionsHandler != null) {
                    jsInjectionsHandler.setupPlayerButtonListeners(view);
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                if (url == null || "about:blank".equals(url)) {
                    return;
                }

                // Как только первые пиксели новой страницы отрисованы — сразу убираем блокирующий спиннер
                // и активируем DOM-слушатели, не дожидаясь загрузки сторонних картинок/скриптов!
                hideDomainChangeSpinner();
                if (isFirstLoad) {
                    isFirstLoad = false;
                }
                if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                    swipeRefreshLayout.setRefreshing(false);
                }
                if (jsInjectionsHandler != null) {
                    jsInjectionsHandler.reinjectDomListeners(view);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null || "about:blank".equals(url)) {
                    return;
                }

                hideDomainChangeSpinner();
                completeTopProgress();
                isFirstLoad = false;

                applyForceDarkForUrl(view, url);

                if (jsInjectionsHandler != null) {
                    jsInjectionsHandler.reinjectDomListeners(view);
                }
                CookieSyncManager.syncFromUrl(url);
                CookieSyncManager.injectAuthLocalStorage(view);
                LatestViewsManager.injectLatestViewsToWebView(view);

                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).updateSystemBarColors(url);
                }
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame()) {
                    return;
                }

                String errorDescription = null;
                int errorCode = 0;
                if (error != null) {
                    errorCode = error.getErrorCode();
                    CharSequence desc = error.getDescription();
                    if (desc != null && desc.length() > 0) {
                        errorDescription = desc.toString();
                    }
                }

                // Игнорируем отменённую навигацию при быстрых кликах по ссылкам
                if (errorDescription != null && errorDescription.contains("ERR_ABORTED")) {
                    return;
                }

                String failingUrl = request.getUrl() != null ? request.getUrl().toString() : lastRequestedUrl;
                if (!isNetworkAvailable() || (errorDescription != null && (
                        errorDescription.contains("ERR_INTERNET_DISCONNECTED") ||
                        errorDescription.contains("ERR_NAME_NOT_RESOLVED") ||
                        errorDescription.contains("ERR_ADDRESS_UNREACHABLE")))) {
                    showCustomErrorPage(
                            failingUrl,
                            "Нет подключения к интернету",
                            "Отсутствует подключение к интернету. Проверьте соединение или откройте скачанные аниме."
                    );
                } else {
                    showCustomErrorPage(
                            failingUrl,
                            errorDescription != null ? errorDescription : ("Код ошибки: " + errorCode)
                    );
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.w(TAG, "WebView renderer process gone (didCrash=" + (detail != null && detail.didCrash()) + "), recovering safely");
                hideDomainChangeSpinner();
                completeTopProgress();
                if (!isDestroyed && lastRequestedUrl != null && !activity.isFinishing()) {
                    showCustomErrorPage(lastRequestedUrl, "Страница была выгружена системой", "Нажмите «Обновить», чтобы восстановить страницу.");
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress < 100) {
                    showTopProgress(Math.max(15, newProgress));
                    if (newProgress >= 80) {
                        hideDomainChangeSpinner();
                    }
                } else {
                    completeTopProgress();
                    hideDomainChangeSpinner();
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customViewCallback = callback;

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
                fullscreenContainer.addView(customView, params);
                fullscreenContainer.setVisibility(View.VISIBLE);

                webView.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }

                fullscreenContainer.removeView(customView);
                customView = null;

                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }
            }
        });
    }

    private void applyForceDarkForUrl(WebView view, String url) {
        if (view == null) return;
        try {
            int targetMode = JSInjectionsHandler.isIgnoredColorDomain(url)
                    ? WebSettings.FORCE_DARK_OFF
                    : WebSettings.FORCE_DARK_AUTO;
            if (view.getSettings().getForceDark() != targetMode) {
                view.getSettings().setForceDark(targetMode);
            }
        } catch (Exception ignored) {}
    }

    private void showTopProgress(int progress) {
        if (webProgressBar == null) return;
        uiHandler.removeCallbacks(hideTopProgressRunnable);
        if (webProgressBar.getVisibility() != View.VISIBLE) {
            webProgressBar.setAlpha(1f);
            webProgressBar.setVisibility(View.VISIBLE);
        }
        webProgressBar.setProgressCompat(progress, true);
    }

    private void completeTopProgress() {
        if (webProgressBar == null || webProgressBar.getVisibility() != View.VISIBLE) return;
        webProgressBar.setProgressCompat(100, true);
        uiHandler.removeCallbacks(hideTopProgressRunnable);
        uiHandler.postDelayed(hideTopProgressRunnable, 220);
    }

    private void prewarmDnsAsync(final String host) {
        if (host == null || host.isEmpty() || !WARMED_HOSTS.add(host)) {
            return;
        }
        IO_EXECUTOR.execute(() -> {
            try {
                InetAddress.getByName(host);
            } catch (Exception ignored) {
                WARMED_HOSTS.remove(host);
            }
        });
    }

    private void handleIntentUrl(String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            try {
                activity.startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                if (fallbackUrl != null) {
                    try {
                        Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl));
                        activity.startActivity(marketIntent);
                        return;
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed to open fallback URL", ex);
                    }
                }
                String packageName = intent.getPackage();
                if (packageName != null) {
                    try {
                        Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName));
                        activity.startActivity(marketIntent);
                        return;
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed to open Play Market", ex);
                    }
                }
                CustomToast.showWarning(activity, "Не удалось открыть приложение");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing intent URL: " + url, e);
            CustomToast.showWarning(activity, "Ошибка обработки ссылки");
        }
    }

    private void handleSpecialScheme(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error handling special scheme: " + url, e);
            CustomToast.showWarning(activity, "Не удалось открыть ссылку");
        }
    }

    public void showCustomErrorPage(String failingUrl, String errorMsg) {
        showCustomErrorPage(failingUrl, null, errorMsg);
    }

    public void showCustomErrorPage(String failingUrl, String title, String errorMsg) {
        if (failingUrl != null && !"about:blank".equals(failingUrl)) {
            this.lastFailedUrl = failingUrl;
        }

        activity.runOnUiThread(() -> {
            hideDomainChangeSpinner();
            completeTopProgress();

            if (webErrorLayout != null) {
                webErrorLayout.setVisibility(View.VISIBLE);

                if (tvErrorTitle != null) {
                    tvErrorTitle.setText((title != null && !title.isEmpty()) ? title : "Ошибка загрузки страницы");
                }

                if (tvErrorMessage != null) {
                    tvErrorMessage.setText((errorMsg != null && !errorMsg.isEmpty())
                            ? errorMsg
                            : ("Не удалось открыть страницу: " + (failingUrl != null ? failingUrl : "")));
                }
            }

            if (webView != null) {
                webView.stopLoading();
                webView.setVisibility(View.INVISIBLE);
            }

            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false);
            }
        });
    }

    public void reloadWebView() {
        if (webErrorLayout != null) {
            webErrorLayout.setVisibility(View.GONE);
        }
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            webView.getSettings().setCacheMode(isNetworkAvailable() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
            if (lastFailedUrl != null && !lastFailedUrl.isEmpty() && !"about:blank".equals(lastFailedUrl)) {
                String retryUrl = lastFailedUrl;
                lastFailedUrl = null;
                loadUrlInWebView(retryUrl);
            } else if (webView.getUrl() != null && !"about:blank".equals(webView.getUrl())) {
                webView.reload();
            } else if (lastRequestedUrl != null && !lastRequestedUrl.isEmpty()) {
                loadUrlInWebView(lastRequestedUrl);
            }
        }
    }

    public void loadUrlInWebView(String url) {
        if (webView == null || url == null || url.trim().isEmpty()) {
            return;
        }
        String cleanUrl = url.trim();
        lastRequestedUrl = cleanUrl;
        String host = extractDomain(cleanUrl);
        if (host != null) {
            prewarmDnsAsync(host);
        }
        webView.getSettings().setCacheMode(isNetworkAvailable() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        webView.loadUrl(cleanUrl, NAVIGATION_HEADERS);
    }

    public boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking network availability", e);
        }
        return true;
    }

    public void showDomainChangeSpinner() {
        activity.runOnUiThread(() -> {
            uiHandler.removeCallbacks(hideSpinnerRunnable);
            if (spinnerBackground != null) {
                spinnerBackground.animate().cancel();
                spinnerBackground.setVisibility(View.VISIBLE);
                spinnerBackground.setAlpha(1f);
            }
            if (spinner != null) {
                spinner.animate().cancel();
                spinner.setVisibility(View.VISIBLE);
                spinner.setAlpha(1f);
                spinner.setScaleX(1f);
                spinner.setScaleY(1f);
            }
            uiHandler.postDelayed(hideSpinnerRunnable, 5000);
        });
    }

    public void hideDomainChangeSpinner() {
        activity.runOnUiThread(() -> {
            uiHandler.removeCallbacks(hideSpinnerRunnable);
            if (spinner != null && spinner.getVisibility() == View.VISIBLE) {
                spinner.animate().cancel();
                spinner.animate()
                        .alpha(0f)
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(160)
                        .setInterpolator(new AccelerateInterpolator())
                        .withEndAction(() -> {
                            if (spinner != null) spinner.setVisibility(View.GONE);
                        })
                        .start();
            }
            if (spinnerBackground != null && spinnerBackground.getVisibility() == View.VISIBLE) {
                spinnerBackground.animate().cancel();
                spinnerBackground.animate()
                        .alpha(0f)
                        .setDuration(160)
                        .setInterpolator(new AccelerateInterpolator())
                        .withEndAction(() -> {
                            if (spinnerBackground != null) spinnerBackground.setVisibility(View.GONE);
                        })
                        .start();
            }
        });
    }

    public String extractDomain(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            String candidate = (!url.startsWith("http://") && !url.startsWith("https://"))
                    ? ("https://" + url)
                    : url;
            Uri uri = Uri.parse(candidate);
            String host = uri.getHost();
            return (host != null && !host.isEmpty()) ? host.toLowerCase() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isCustomViewShowing() {
        return customView != null;
    }

    public void hideCustomView() {
        if (customView != null && webView != null && webView.getWebChromeClient() != null) {
            webView.getWebChromeClient().onHideCustomView();
        }
    }

    public String getCurrentDomain() {
        return currentDomain;
    }

    public void saveState(Bundle outState) {
        if (webView != null && outState != null) {
            webView.saveState(outState);
            if (lastRequestedUrl != null) {
                outState.putString("webview_last_requested_url", lastRequestedUrl);
            }
        }
    }

    public boolean restoreState(Bundle savedInstanceState) {
        if (webView == null || savedInstanceState == null) {
            return false;
        }
        lastRequestedUrl = savedInstanceState.getString("webview_last_requested_url", null);
        if (webView.restoreState(savedInstanceState) != null && webView.getUrl() != null) {
            isFirstLoad = false;
            currentDomain = extractDomain(webView.getUrl());
            return true;
        }
        return false;
    }

    public void onStart() {
        if (webView != null && !isDestroyed) {
            webView.onResume();
        }
    }

    public void onStop() {
        if (webView != null && !isDestroyed) {
            webView.onPause();
        }
        IO_EXECUTOR.execute(() -> {
            try {
                CookieManager.getInstance().flush();
            } catch (Exception ignored) {}
        });
    }

    public void destroy() {
        isDestroyed = true;
        uiHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            try {
                webView.stopLoading();
                if (webView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) webView.getParent()).removeView(webView);
                }
                webView.setWebChromeClient(null);
                webView.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error destroying WebView", e);
            }
        }
    }
}
