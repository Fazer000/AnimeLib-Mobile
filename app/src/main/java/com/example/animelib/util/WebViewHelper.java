package com.example.animelib.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.example.animelib.ui.JSInjectionsHandler;

/**
 * Общие настройки и вспомогательные методы для WebView (MainWebViewController и TitleWebViewBottomSheet),
 * устраняющие дублирование конфигурации, Cookie-моста и обработки внешних схем.
 */
public final class WebViewHelper {

    private static final String TAG = "WebViewHelper";

    public static final String MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private WebViewHelper() {
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void configureCommonSettings(WebView webView, String userAgent) {
        if (webView == null) {
            return;
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);

        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setGeolocationEnabled(false);
        settings.setSafeBrowsingEnabled(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setOffscreenPreRaster(true);
        if (userAgent != null && !userAgent.isEmpty()) {
            settings.setUserAgentString(userAgent);
        }

        try {
            settings.setForceDark(WebSettings.FORCE_DARK_AUTO);
        } catch (Exception ignored) {
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    public static void applyForceDarkForUrl(WebView view, String url) {
        if (view == null) {
            return;
        }
        try {
            int targetMode = JSInjectionsHandler.isIgnoredColorDomain(url)
                    ? WebSettings.FORCE_DARK_OFF
                    : WebSettings.FORCE_DARK_AUTO;
            if (view.getSettings().getForceDark() != targetMode) {
                view.getSettings().setForceDark(targetMode);
            }
        } catch (Exception ignored) {
        }
    }

    public static void attachCookieBridge(WebView webView) {
        if (webView == null) {
            return;
        }
        webView.addJavascriptInterface(new CookieBridge(), "CookieManager");
    }

    public static boolean isExternalScheme(String url) {
        if (url == null) {
            return false;
        }
        return url.startsWith("intent://")
                || url.startsWith("tel:")
                || url.startsWith("mailto:")
                || url.startsWith("sms:")
                || url.startsWith("market:");
    }

    public static void handleExternalUrl(Context context, String url) {
        if (context == null || url == null) {
            return;
        }
        if (url.startsWith("intent://")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                try {
                    context.startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) {
                    String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                    if (fallbackUrl != null) {
                        try {
                            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)));
                            return;
                        } catch (Exception ex) {
                            Log.e(TAG, "Failed to open fallback URL", ex);
                        }
                    }
                    String packageName = intent.getPackage();
                    if (packageName != null) {
                        try {
                            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
                            return;
                        } catch (Exception ex) {
                            Log.e(TAG, "Failed to open Play Market", ex);
                        }
                    }
                    CustomToast.showWarning(context, "Не удалось открыть приложение");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing intent URL: " + url, e);
                CustomToast.showWarning(context, "Ошибка обработки ссылки");
            }
            return;
        }

        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.e(TAG, "Error handling special scheme: " + url, e);
            CustomToast.showWarning(context, "Не удалось открыть ссылку");
        }
    }

    private static final class CookieBridge {
        @JavascriptInterface
        public void setCookie(String name, String value, String domain) {
            if (name == null || value == null || domain == null) {
                return;
            }
            String cleanName = name.trim();
            if (cleanName.isEmpty() || containsControlChars(cleanName) || containsControlChars(value) || containsControlChars(domain)) {
                return;
            }
            String cookieString = cleanName + "=" + value;
            CookieManager.getInstance().setCookie(domain, cookieString);
            CookieSyncManager.copyCookieStringToAllDomains(cookieString);
        }

        @JavascriptInterface
        public String getCookie(String name, String domain) {
            if (name == null || domain == null) {
                return null;
            }
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

        private static boolean containsControlChars(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\r' || c == '\n' || c == '\0') {
                    return true;
                }
            }
            return false;
        }
    }
}
