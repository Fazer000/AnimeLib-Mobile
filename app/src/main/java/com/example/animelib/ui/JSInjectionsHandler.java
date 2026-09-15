package com.example.animelib.ui;

import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.example.animelib.VideoPlayerActivity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Класс для обработки нажатий кнопок плеера в WebView.
 * Содержит JavaScript интерфейс и логику настройки слушателей
 */
public class JSInjectionsHandler {
    private static final String TAG = "PlayerButtonHandler";
    private static final String JS_INTERFACE_NAME = "AndroidInterface";
    
    private final Context context;
    private final Map<String, String> jsCache = new ConcurrentHashMap<>();

    public interface OnPlayerButtonClickListener {
        void onPlayerButtonClicked(String buttonHref);
    }

    private OnPlayerButtonClickListener onPlayerButtonClickListener;

    public void setOnPlayerButtonClickListener(OnPlayerButtonClickListener listener) {
        this.onPlayerButtonClickListener = listener;
    }
    
    public JSInjectionsHandler(Context context) {
        this.context = context;
    }
    
    /**
     * Добавляет JavaScript интерфейс к WebView
     */
    public void addJavaScriptInterface(WebView webView) {
        webView.addJavascriptInterface(new PlayerButtonJSInterface(), JS_INTERFACE_NAME);
        Log.d(TAG, "JavaScript interface added to WebView");
    }
    
    /**
     * Настраивает слушатели кнопок плеера для SPA приложений
     */
    public void setupPlayerButtonListeners(WebView webView) {
        Log.d(TAG, "Setting up SPA-aware player button listeners");

        // Загружаем и выполняем JavaScript файлы
        loadAndExecuteJS(webView, "js/license-button-listener.js", "License button listener");
        loadAndExecuteJS(webView, "js/player-button-listener.js", "Player button listener");
        loadAndExecuteJS(webView, "js/theme-button-listener.js", "Theme button listener");
        loadAndExecuteJS(webView, "js/custom-select-handler.js", "Custom select handler");
        loadAndExecuteJS(webView, "js/domain-change-handler.js", "Domain change handler");
        loadAndExecuteJS(webView, "js/auth-handler.js", "Auth handler");
        loadAndExecuteJS(webView, "js/search-button-listener.js", "Search button listener");
        loadAndExecuteJS(webView, "js/downloaded-button-listener.js", "Downloaded button listener");
        loadAndExecuteJS(webView, "js/carousel-fix.js", "Carousel scroll fix");
        loadAndExecuteJS(webView, "js/debug-info.js", "Debug info");
        loadAndExecuteJS(webView, "js/button-checker.js", "Button checker");
        loadAndExecuteJS(webView, "js/site-logo-long-press.js", "Site logo long press listener");
        loadAndExecuteJS(webView, "js/theme-color-override.js", "Theme color override");

    }

    /**
     * Повторно внедряет DOM-зависимые слушатели после полной загрузки страницы
     */
    public void reinjectDomListeners(WebView webView) {
        Log.d(TAG, "Re-injecting DOM-dependent listeners");

        loadAndExecuteJS(webView, "js/license-button-listener.js", "License button listener");
        loadAndExecuteJS(webView, "js/player-button-listener.js", "Player button listener");
        loadAndExecuteJS(webView, "js/theme-button-listener.js", "Theme button listener");
        loadAndExecuteJS(webView, "js/search-button-listener.js", "Search button listener");
        loadAndExecuteJS(webView, "js/downloaded-button-listener.js", "Downloaded button listener");
        loadAndExecuteJS(webView, "js/carousel-fix.js", "Carousel scroll fix");
        loadAndExecuteJS(webView, "js/auth-handler.js", "Auth handler");
        loadAndExecuteJS(webView, "js/site-logo-long-press.js", "Site logo long press listener");
        loadAndExecuteJS(webView, "js/theme-color-override.js", "Theme color override");
    }

    /**
     * Внедряет скрипты, специфичные для BottomSheet WebView (например, скрытие нижнего меню)
     */
    public void setupBottomSheetInjections(WebView webView) {
        loadAndExecuteJS(webView, "js/remove-bottom-menu.js", "Remove bottom menu");
    }

    private String getJSFromAsset(String assetPath) {
        if (jsCache.containsKey(assetPath)) {
            return jsCache.get(assetPath);
        }
        try (InputStream inputStream = context.getAssets().open(assetPath)) {
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            String jsCode = new String(buffer, StandardCharsets.UTF_8);
            jsCache.put(assetPath, jsCode);
            return jsCode;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load JS file: " + assetPath, e);
            return null;
        }
    }

    /**
     * Загружает и выполняет JavaScript файл из assets
     */
    private void loadAndExecuteJS(WebView webView, String assetPath, String description) {
        if (webView == null) return;
        String jsCode = getJSFromAsset(assetPath);
        if (jsCode != null && !jsCode.isEmpty()) {
            webView.evaluateJavascript(jsCode, null);
        }
    }
    
    
    private static android.app.Activity getActivityFromContext(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof android.app.Activity) {
                return (android.app.Activity) ctx;
            }
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return (ctx instanceof android.app.Activity) ? (android.app.Activity) ctx : null;
    }

    /**
     * JavaScript интерфейс для обработки нажатий кнопок плеера
     */
    private class PlayerButtonJSInterface {
        @OptIn(markerClass = UnstableApi.class)
        @JavascriptInterface
        public void onPlayerButtonClicked(String buttonHref) {
            if (buttonHref != null && buttonHref.startsWith("http") && !buttonHref.toLowerCase().contains("animelib")) {
                Log.d(TAG, "Ignoring player button click for non-animelib domain: " + buttonHref);
                return;
            }
            if (onPlayerButtonClickListener != null) {
                onPlayerButtonClickListener.onPlayerButtonClicked(buttonHref);
                return;
            }
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Getting auth token before starting VideoPlayerActivity for URL: " + buttonHref);
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).getAuthAndStartVideoPlayer(buttonHref);
                    } else {
                        VideoPlayerActivity.startFromAnimePage(activity, buttonHref);
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot start VideoPlayerActivity");
            }
        }
        
        @JavascriptInterface
        public void onThemeButtonClicked() {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Theme button clicked, showing theme dialog");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).showThemeDialog();
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot show theme dialog");
            }
        }

        @JavascriptInterface
        public void onSiteLogoLongPressed() {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Site logo long pressed, showing site selection BS");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).showSiteSelectionBottomSheet();
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot show site selection bottom sheet");
            }
        }
        
        @JavascriptInterface
        public void showCustomSelectDialog(String dialogDataJson) {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Custom select dialog requested with data: " + dialogDataJson);
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).showCustomSelectDialog(dialogDataJson);
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot show custom select dialog");
            }
        }
        
        @JavascriptInterface
        public void updateSelectButton(String selectId, String selectedValue, String selectedText) {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Updating select button: " + selectId + " = " + selectedText);
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).updateSelectButton(selectId, selectedValue, selectedText);
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot update select button");
            }
        }
        
        @JavascriptInterface
        public void showDomainChangeSpinner() {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Showing domain change spinner");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).showDomainChangeSpinner();
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot show domain change spinner");
            }
        }
        
        @JavascriptInterface
        public void hideDomainChangeSpinner() {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Hiding domain change spinner");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).hideDomainChangeSpinner();
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot hide domain change spinner");
            }
        }
        
        @JavascriptInterface
        public void handleOnBackPressed() {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Back button pressed from WebView");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).onBackPressed();
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot handle back press");
            }
        }
        
        @JavascriptInterface
        public void saveAuthLocalStorage(String authJson) {
            com.example.animelib.util.CookieSyncManager.saveAuthJson(authJson);
        }

        @JavascriptInterface
        public String getSavedAuthJson() {
            return com.example.animelib.util.CookieSyncManager.getAuthJson(context);
        }

        @JavascriptInterface
        public void saveLatestViews(String latestViewsJson) {
            com.example.animelib.util.LatestViewsManager.updateFromJS(context, latestViewsJson);
        }

        @JavascriptInterface
        public String getLatestViewsJson() {
            return com.example.animelib.util.LatestViewsManager.getLatestViewsJson(context);
        }

        @JavascriptInterface
        public void getAuthFromLocalStorage() {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Getting auth from localStorage");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).getAuthFromLocalStorage();
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot get auth from localStorage");
            }
        }

        @JavascriptInterface
        public void clearAuthToken() {
            com.example.animelib.util.CookieSyncManager.clearAuthJson();
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Clearing auth token from WebView logout");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).clearAuthToken();
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot clear auth token");
            }
        }

        @JavascriptInterface
        public void saveOAuthToken(String jsonString) {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "OAuth token response received from WebView");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).saveOAuthTokenFromJson(jsonString);
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot save OAuth token");
            }
        }

        @JavascriptInterface
        public void saveAuthMeData(String jsonString) {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Auth me response received from WebView");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).saveAuthMeDataFromJson(jsonString);
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot save Auth me data");
            }
        }
        
        @JavascriptInterface
        public void onSearchButtonClicked() {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Search button clicked from WebView");
                    if (activity instanceof com.example.animelib.MainActivity) {
                        ((com.example.animelib.MainActivity) activity).showSearchFragment();
                    }
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot handle search button click");
            }
        }

        @JavascriptInterface
        public void onDownloadedButtonClicked() {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d("PlayerHandler", "Downloaded button clicked from WebView");
                    com.example.animelib.ui.DownloadsActivity.start(activity);
                });
            } else {
                Log.e(TAG, "Context is not an Activity, cannot open DownloadsActivity");
            }
        }

        @JavascriptInterface
        public void disallowInterceptTouch() {
            android.app.Activity activity = getActivityFromContext(context);
            if (activity instanceof com.example.animelib.MainActivity) {
                activity.runOnUiThread(() -> {
                    ((com.example.animelib.MainActivity) activity).disallowInterceptTouch();
                });
            }
        }
    }
}
