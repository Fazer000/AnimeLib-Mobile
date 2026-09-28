package com.example.animelib.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import com.example.animelib.MainActivity;
import com.example.animelib.R;
import com.example.animelib.util.CookieSyncManager;
import com.example.animelib.VideoPlayerActivity;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

public class TitleWebViewBottomSheet extends FlexibleBottomSheetDialog {

    private static final String TAG = "TitleWebViewBS";

    private final String titleName;
    private final String targetUrl;

    private FrameLayout layoutWebHeader;
    private ImageView btnRefreshWeb;
    private ImageView btnCloseWeb;
    private ProgressBar progressBarWeb;
    private WebView webViewTitle;
    private View layoutWebError;
    private Button btnRetryWeb;
    private JSInjectionsHandler jsInjectionsHandler;
    private boolean isNavigatingToPlayer = false;

    public TitleWebViewBottomSheet(@NonNull Context context, String titleName, String targetUrl) {
        super(context);
        this.titleName = titleName;
        this.targetUrl = targetUrl;
    }

    private Activity getActivityFromContext(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return (ctx instanceof Activity) ? (Activity) ctx : null;
    }

    private synchronized void openPlayerFromUrl(String rawUrl) {
        Activity act = getActivityFromContext(getContext());
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            Log.w(TAG, "Activity is null or finishing, cannot start player");
            return;
        }

        act.runOnUiThread(() -> {
            synchronized (TitleWebViewBottomSheet.this) {
                if (isNavigatingToPlayer) {
                    Log.d(TAG, "Already navigating to player, ignoring duplicate request: " + rawUrl);
                    return;
                }
                isNavigatingToPlayer = true;
            }

            String currentUrl = (webViewTitle != null && webViewTitle.getUrl() != null) ? webViewTitle.getUrl() : targetUrl;
            String finalUrl = VideoUrlHelper.resolveAnimeUrl(rawUrl, currentUrl);
            Log.d(TAG, "Opening VideoPlayerActivity with resolved URL: " + finalUrl);

            try {
                dismiss();
            } catch (Exception e) {
                Log.w(TAG, "Error dismissing BottomSheet: " + e.getMessage());
            }

            if (act instanceof MainActivity) {
                ((MainActivity) act).getAuthAndStartVideoPlayer(finalUrl);
            } else {
                VideoPlayerActivity.startFromAnimePage(act, finalUrl);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_title_webview, null);
        setContentView(view);
        setCanceledOnTouchOutside(true);
        setCancelable(true);

        FloatingBottomSheetUtils.setupFloatingStyle(this);

        FrameLayout webViewContainer = view.findViewById(R.id.webViewContainer);
        if (webViewContainer != null) {
            int screenHeight = getContext().getResources().getDisplayMetrics().heightPixels;
            int targetHeight = (int) (screenHeight * 0.70f);
            ViewGroup.LayoutParams lp = webViewContainer.getLayoutParams();
            if (lp != null) {
                lp.height = targetHeight;
                webViewContainer.setLayoutParams(lp);
            }
        }

        initViews(view);
        setupWebView();
        loadUrl();
        setupBehavior();
    }

    private void setupBehavior() {
        BottomSheetBehavior<FrameLayout> behavior = getBehavior();
        if (behavior != null) {
            behavior.setFitToContents(true);
            behavior.setSkipCollapsed(true);
            behavior.setHideable(true);
            int screenHeight = getContext().getResources().getDisplayMetrics().heightPixels;
            behavior.setPeekHeight(screenHeight * 2);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private void initViews(View root) {
        layoutWebHeader = root.findViewById(R.id.layoutWebHeader);
        btnRefreshWeb = root.findViewById(R.id.btnRefreshWeb);
        btnCloseWeb = root.findViewById(R.id.btnCloseWeb);
        progressBarWeb = root.findViewById(R.id.progressBarWeb);
        webViewTitle = root.findViewById(R.id.webViewTitle);
        layoutWebError = root.findViewById(R.id.layoutWebError);
        btnRetryWeb = root.findViewById(R.id.btnRetryWeb);

        updateHeaderColor(targetUrl);

        if (btnCloseWeb != null) {
            btnCloseWeb.setOnClickListener(v -> dismiss());
        }

        if (btnRefreshWeb != null) {
            btnRefreshWeb.setOnClickListener(v -> {
                if (webViewTitle != null) {
                    try {
                        webViewTitle.reload();
                    } catch (Exception e) {
                        Log.e(TAG, "Error reloading webView: " + e.getMessage());
                    }
                }
            });
        }

        if (btnRetryWeb != null) {
            btnRetryWeb.setOnClickListener(v -> {
                if (layoutWebError != null) {
                    layoutWebError.setVisibility(View.GONE);
                }
                if (webViewTitle != null) {
                    try {
                        webViewTitle.reload();
                    } catch (Exception e) {
                        Log.e(TAG, "Error retrying webView: " + e.getMessage());
                    }
                }
            });
        }

        setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (webViewTitle != null) {
                    try {
                        if (webViewTitle.canGoBack()) {
                            webViewTitle.goBack();
                            return true;
                        }
                    } catch (Exception ignored) {}
                }
            }
            return false;
        });
    }

    private void setupWebView() {
        if (webViewTitle == null) return;

        try {
            jsInjectionsHandler = new JSInjectionsHandler(getContext());
            jsInjectionsHandler.setOnPlayerButtonClickListener(this::openPlayerFromUrl);
            jsInjectionsHandler.addJavaScriptInterface(webViewTitle);

            // Синхронизируем куки сети Lib перед загрузкой BottomSheet
            CookieSyncManager.syncAllCookies();

            webViewTitle.addJavascriptInterface(new Object() {
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

            WebSettings webSettings = webViewTitle.getSettings();
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
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            webSettings.setAllowFileAccess(true);
            webSettings.setAllowContentAccess(true);
            webSettings.setLoadsImagesAutomatically(true);
            webSettings.setMediaPlaybackRequiresUserGesture(false);
            webSettings.setGeolocationEnabled(true);
            webSettings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
            try {
                webSettings.setForceDark(WebSettings.FORCE_DARK_AUTO);
            } catch (Exception ignored) {}

            webViewTitle.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            webViewTitle.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                }
                return false;
            });

            try {
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setAcceptThirdPartyCookies(webViewTitle, true);
                cookieManager.flush();
            } catch (Exception e) {
                Log.w(TAG, "CookieManager setup failed: " + e.getMessage());
            }

            webViewTitle.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (request != null && request.getUrl() != null) {
                        return handleUrlLoading(request.getUrl().toString());
                    }
                    return false;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return handleUrlLoading(url);
                }

                private boolean handleUrlLoading(String url) {
                    if (url == null) return false;

                    if (url.startsWith("intent://") || url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") || url.startsWith("market:")) {
                        try {
                            Intent intent;
                            if (url.startsWith("intent://")) {
                                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                            } else {
                                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            }
                            getContext().startActivity(intent);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open external url: " + url, e);
                        }
                        return true;
                    }

                    if (url.contains("/watch") || url.contains("episode")) {
                        openPlayerFromUrl(url);
                        return true;
                    }
                    return false;
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    updateHeaderColor(url);
                    CookieSyncManager.syncFromUrl(url);
                    CookieSyncManager.injectAuthLocalStorage(view);
                    if (progressBarWeb != null) {
                        progressBarWeb.setVisibility(View.VISIBLE);
                    }
                    if (layoutWebError != null) {
                        layoutWebError.setVisibility(View.GONE);
                    }
                    if (jsInjectionsHandler != null) {
                        jsInjectionsHandler.setupPlayerButtonListeners(view);
                        jsInjectionsHandler.setupBottomSheetInjections(view);
                    }
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    updateHeaderColor(url);
                    CookieSyncManager.syncFromUrl(url);
                    CookieSyncManager.injectAuthLocalStorage(view);
                    com.example.animelib.util.LatestViewsManager.injectLatestViewsToWebView(view);
                    if (progressBarWeb != null) {
                        progressBarWeb.setVisibility(View.GONE);
                    }
                    if (jsInjectionsHandler != null) {
                        jsInjectionsHandler.reinjectDomListeners(view);
                        jsInjectionsHandler.setupBottomSheetInjections(view);
                    }
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if (request != null && request.isForMainFrame()) {
                        if (progressBarWeb != null) {
                            progressBarWeb.setVisibility(View.GONE);
                        }
                        if (layoutWebError != null) {
                            layoutWebError.setVisibility(View.VISIBLE);
                        }
                    }
                }

                @Override
                public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                    if (handler != null) {
                        handler.proceed();
                    }
                }
            });

            webViewTitle.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    if (progressBarWeb != null) {
                        if (newProgress < 100) {
                            progressBarWeb.setVisibility(View.VISIBLE);
                            progressBarWeb.setProgress(newProgress);
                        } else {
                            progressBarWeb.setVisibility(View.GONE);
                        }
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup WebView: " + e.getMessage(), e);
        }
    }

    private Map<String, String> getRequestHeaders() {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Cache-Control", "max-age=0");
        headers.put("Connection", "keep-alive");
        return headers;
    }

    private void loadUrl() {
        if (webViewTitle != null && targetUrl != null && !targetUrl.isEmpty()) {
            Log.d(TAG, "Loading URL in BottomSheet WebView: " + targetUrl);
            try {
                CookieSyncManager.syncFromUrl(targetUrl);
                webViewTitle.loadUrl(targetUrl, getRequestHeaders());
            } catch (Exception e) {
                Log.e(TAG, "Failed to load URL: " + e.getMessage(), e);
            }
        }
    }

    private void updateHeaderColor(String url) {
        if (layoutWebHeader == null) return;
        Context context = getContext();
        if (context == null) return;

        int headerColor = com.example.animelib.util.ThemeUtils.getSiteHeaderColor(context, url);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(headerColor);
        float radius = 20 * context.getResources().getDisplayMetrics().density;
        bg.setCornerRadii(new float[]{
                radius, radius, // top-left
                radius, radius, // top-right
                0, 0,           // bottom-right
                0, 0            // bottom-left
        });
        layoutWebHeader.setBackground(bg);
    }

    @Override
    public void dismiss() {
        if (webViewTitle != null) {
            final WebView wv = webViewTitle;
            webViewTitle = null;
            try {
                wv.stopLoading();
                if (wv.getParent() instanceof ViewGroup) {
                    ((ViewGroup) wv.getParent()).removeView(wv);
                }
                wv.post(() -> {
                    try {
                        wv.destroy();
                    } catch (Exception e) {
                        Log.w(TAG, "Error destroying webView: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Error detaching webView: " + e.getMessage());
            }
        }
        try {
            super.dismiss();
        } catch (Exception t) {
            Log.w(TAG, "Error dismissing dialog: " + t.getMessage());
        }
    }
}
