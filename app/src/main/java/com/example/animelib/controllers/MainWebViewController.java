package com.example.animelib.controllers;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.animelib.R;
import com.example.animelib.ui.DownloadsActivity;
import com.example.animelib.ui.JSInjectionsHandler;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.CookieSyncManager;
import com.example.animelib.util.CustomToast;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class MainWebViewController {

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

    private View webErrorLayout;
    private TextView tvErrorTitle;
    private TextView tvErrorMessage;
    private MaterialButton btnRetryLoad;
    private View btnOpenDownloads;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private String currentDomain = null;
    private String lastFailedUrl = null;
    private boolean isFirstLoad = true;

    private final Handler spinnerTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideSpinnerRunnable = this::hideDomainChangeSpinner;

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

        initErrorViews();
        setupWebView();
    }

    private void initErrorViews() {
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
        WebSettings webSettings = webView.getSettings();

        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

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
        webSettings.setGeolocationEnabled(true);
        webSettings.setUserAgentString(getRandomUserAgent());
        webSettings.setNeedInitialFocus(false);

        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setBlockNetworkImage(false);
        webSettings.setBlockNetworkLoads(false);
        webSettings.setSafeBrowsingEnabled(true);
        webSettings.setForceDark(WebSettings.FORCE_DARK_AUTO);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webSettings.setOffscreenPreRaster(true);
        WebView.setWebContentsDebuggingEnabled(false);

        webView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        webView.setScrollbarFadingEnabled(true);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        webView.setInitialScale(0);
        webView.getSettings().setMinimumFontSize(8);
        webView.getSettings().setMinimumLogicalFontSize(8);
        webView.getSettings().setDefaultFontSize(16);
        webView.getSettings().setDefaultFixedFontSize(13);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        cookieManager.flush();
        CookieManager.setAcceptFileSchemeCookies(true);

        CookieSyncManager.syncAllCookies();

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
                        String[] parts = cookie.trim().split("=");
                        if (parts.length == 2 && parts[0].equals(name)) {
                            return parts[1];
                        }
                    }
                }
                return null;
            }
        }, "CookieManager");

        Map<String, String> headers = getStringStringMap();
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d("MainWebViewController", "shouldOverrideUrlLoading URL: " + url);

                if (url.startsWith("intent://")) {
                    handleIntentUrl(url);
                    return true;
                }

                if (url.startsWith("tel:") || url.startsWith("mailto:") ||
                    url.startsWith("sms:") || url.startsWith("market:")) {
                    handleSpecialScheme(url);
                    return true;
                }

                String newDomain = extractDomain(url);
                if (!isNetworkAvailable()) {
                    showCustomErrorPage(url, "Нет подключения к интернету", "Отсутствует подключение к интернету. Проверьте соединение и повторите попытку.");
                    return true;
                }

                if (newDomain != null && currentDomain != null && !currentDomain.equals(newDomain)) {
                    showDomainChangeSpinner();
                }

                if (url != null && (url.contains("/watch") || url.contains("episode"))) {
                    String currentUrl = view.getUrl();
                    String resolvedUrl = VideoUrlHelper.resolveAnimeUrl(url, currentUrl);
                    if (callback != null) {
                        callback.onWatchUrlIntercepted(resolvedUrl);
                    }
                    return true;
                }

                return false;
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);

                if (request != null && request.isForMainFrame()) {
                    String url = request.getUrl() != null ? request.getUrl().toString() : "";
                    int statusCode = errorResponse != null ? errorResponse.getStatusCode() : 0;
                    Log.w("MainWebViewController", "HTTP Error " + statusCode + " for URL: " + url);

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
                handler.cancel();
                String failingUrl = error != null ? error.getUrl() : null;
                String errorMsg = "Ошибка SSL-соединения: " + (error != null ? error.toString() : "Небезопасный сертификат");
                showCustomErrorPage(failingUrl, errorMsg);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d("MainWebViewController", "onPageStarted URL: " + url);

                if (url != null && !"about:blank".equals(url)) {
                    if (!isNetworkAvailable()) {
                        showCustomErrorPage(url, "Нет подключения к интернету", "Отсутствует подключение к интернету. Проверьте соединение и повторите попытку.");
                        return;
                    }
                    if (webErrorLayout != null) {
                        webErrorLayout.setVisibility(View.GONE);
                    }
                    if (webView != null) {
                        webView.setVisibility(View.VISIBLE);
                    }
                }

                String newDomain = extractDomain(url);
                boolean isEmbedOrPlayer = url != null && (
                        url.contains("kodik") || url.contains("v2.kodik") ||
                        url.contains("rutube") || url.contains("youtube") ||
                        url.contains("alloha") || url.contains("cache") ||
                        url.contains("/player") || url.contains("iframe")
                );

                if (!isEmbedOrPlayer && url != null && !"about:blank".equals(url)) {
                    if (isFirstLoad || (newDomain != null && currentDomain != null && !currentDomain.equalsIgnoreCase(newDomain))) {
                        showDomainChangeSpinner();
                    }
                }

                currentDomain = newDomain;
                if (callback != null && newDomain != null) {
                    callback.onDomainChanged(newDomain);
                }
                swipeRefreshLayout.setRefreshing(false);

                CookieSyncManager.syncFromUrl(url);
                CookieSyncManager.injectAuthLocalStorage(view);

                if (jsInjectionsHandler != null) {
                    jsInjectionsHandler.setupPlayerButtonListeners(view);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && "about:blank".equals(url)) {
                    return;
                }

                hideDomainChangeSpinner();

                if (jsInjectionsHandler != null) {
                    jsInjectionsHandler.reinjectDomListeners(view);
                }
                CookieSyncManager.syncFromUrl(url);
                CookieSyncManager.injectAuthLocalStorage(view);
                com.example.animelib.util.LatestViewsManager.injectLatestViewsToWebView(view);

                if (isFirstLoad) {
                    isFirstLoad = false;
                }
                swipeRefreshLayout.setRefreshing(false);
                Log.d("MainWebViewController", "Finished loading: " + url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    String errorDescription = null;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && error != null) {
                        CharSequence desc = error.getDescription();
                        if (desc != null && desc.length() > 0) {
                            errorDescription = desc.toString();
                        } else {
                            errorDescription = "Код ошибки: " + error.getErrorCode();
                        }
                    }
                    String failingUrl = request.getUrl() != null ? request.getUrl().toString() : null;
                    showCustomErrorPage(failingUrl, errorDescription);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showCustomErrorPage(failingUrl, description != null ? description : ("Код ошибки: " + errorCode));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress >= 100) {
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

    private void handleIntentUrl(String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            try {
                activity.startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                if (fallbackUrl != null) {
                    try {
                        Intent marketIntent = new Intent(Intent.ACTION_VIEW);
                        marketIntent.setData(android.net.Uri.parse(fallbackUrl));
                        activity.startActivity(marketIntent);
                        return;
                    } catch (Exception ex) {
                        Log.e("MainWebViewController", "Failed to open fallback URL", ex);
                    }
                }
                String packageName = intent.getPackage();
                if (packageName != null) {
                    try {
                        Intent marketIntent = new Intent(Intent.ACTION_VIEW);
                        marketIntent.setData(android.net.Uri.parse("market://details?id=" + packageName));
                        activity.startActivity(marketIntent);
                        return;
                    } catch (Exception ex) {
                        Log.e("MainWebViewController", "Failed to open Play Market", ex);
                    }
                }
                CustomToast.showWarning(activity, "Не удалось открыть приложение");
            }
        } catch (Exception e) {
            Log.e("MainWebViewController", "Error parsing intent URL: " + url, e);
            CustomToast.showWarning(activity, "Ошибка обработки ссылки");
        }
    }

    private void handleSpecialScheme(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(url));
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e("MainWebViewController", "Error handling special scheme: " + url, e);
            CustomToast.showWarning(activity, "Не удалось открыть ссылку");
        }
    }

    public void showCustomErrorPage(String failingUrl, String errorMsg) {
        showCustomErrorPage(failingUrl, null, errorMsg);
    }

    public void showCustomErrorPage(String failingUrl, String title, String errorMsg) {
        this.lastFailedUrl = failingUrl;

        activity.runOnUiThread(() -> {
            hideDomainChangeSpinner();

            if (webErrorLayout != null) {
                webErrorLayout.setVisibility(View.VISIBLE);

                if (tvErrorTitle != null) {
                    if (title != null && !title.isEmpty()) {
                        tvErrorTitle.setText(title);
                    } else {
                        tvErrorTitle.setText("Ошибка загрузки страницы");
                    }
                }

                if (tvErrorMessage != null) {
                    if (errorMsg != null && !errorMsg.isEmpty()) {
                        tvErrorMessage.setText(errorMsg);
                    } else {
                        tvErrorMessage.setText("Не удалось открыть страницу: " + (failingUrl != null ? failingUrl : ""));
                    }
                }
            }

            if (webView != null) {
                webView.setVisibility(View.GONE);
                webView.loadUrl("about:blank");
            }

            swipeRefreshLayout.setRefreshing(false);
        });
    }

    public void reloadWebView() {
        if (webErrorLayout != null) {
            webErrorLayout.setVisibility(View.GONE);
        }
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            if (lastFailedUrl != null && !lastFailedUrl.isEmpty() && !"about:blank".equals(lastFailedUrl)) {
                loadUrlInWebView(lastFailedUrl);
            } else {
                webView.reload();
            }
        }
    }

    public void loadUrlInWebView(String url) {
        if (webView != null) {
            webView.loadUrl(url, getStringStringMap());
        }
    }

    public boolean isNetworkAvailable() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                return activeNetworkInfo != null && activeNetworkInfo.isConnected();
            }
        } catch (Exception e) {
            Log.e("MainWebViewController", "Error checking network availability", e);
        }
        return true;
    }

    public void showDomainChangeSpinner() {
        activity.runOnUiThread(() -> {
            if (spinnerTimeoutHandler != null) {
                spinnerTimeoutHandler.removeCallbacks(hideSpinnerRunnable);
            }
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
            if (spinnerTimeoutHandler != null) {
                spinnerTimeoutHandler.postDelayed(hideSpinnerRunnable, 10000);
            }
        });
    }

    public void hideDomainChangeSpinner() {
        activity.runOnUiThread(() -> {
            if (spinnerTimeoutHandler != null) {
                spinnerTimeoutHandler.removeCallbacks(hideSpinnerRunnable);
            }
            if (spinner != null) {
                spinner.animate().cancel();
                spinner.animate()
                        .alpha(0f)
                        .scaleX(0.8f)
                        .scaleY(0.8f)
                        .setDuration(200)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> {
                            if (spinner != null) spinner.setVisibility(View.GONE);
                        })
                        .start();
            }
            if (spinnerBackground != null) {
                spinnerBackground.animate().cancel();
                spinnerBackground.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> {
                            if (spinnerBackground != null) spinnerBackground.setVisibility(View.GONE);
                        })
                        .start();
            }
        });
    }

    private String getRandomUserAgent() {
        return "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    }

    private Map<String, String> getStringStringMap() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        return headers;
    }

    public String extractDomain(String url) {
        try {
            if (url == null || url.isEmpty()) {
                return null;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            java.net.URL urlObj = new java.net.URL(url);
            return urlObj.getHost();
        } catch (Exception e) {
            Log.w("MainWebViewController", "Failed to extract domain from URL: " + url, e);
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

    public void destroy() {
        if (spinnerTimeoutHandler != null) {
            spinnerTimeoutHandler.removeCallbacks(hideSpinnerRunnable);
        }
        if (webView != null) {
            webView.destroy();
        }
    }
}
