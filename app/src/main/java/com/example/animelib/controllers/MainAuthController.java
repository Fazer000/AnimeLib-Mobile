package com.example.animelib.controllers;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.animelib.VideoPlayerActivity;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.TokenEntity;
import com.example.animelib.models.TokenResponse;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.CookieSyncManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainAuthController {

    private final Context context;
    private final DatabaseManager databaseManager;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public MainAuthController(Context context, DatabaseManager databaseManager) {
        this.context = context;
        this.databaseManager = databaseManager;
    }

    public void updateTokensOnStartup() {
        executor.execute(() -> {
            try {
                boolean hasToken = databaseManager.hasToken();
                Log.d("MainAuthController", "Has token in DB: " + hasToken);

                if (hasToken) {
                    TokenEntity token = databaseManager.getToken();
                    if (token != null) {
                        Log.d("MainAuthController", "Token found in DB: " + token.getAccessToken().substring(0, Math.min(20, token.getAccessToken().length())) + "...");
                        long currentTime = System.currentTimeMillis();
                        long tokenExpiry = token.getTimestamp() + (token.getExpiresIn() * 1000);

                        if (currentTime < tokenExpiry) {
                            Log.d("MainAuthController", "Token is still valid, expires at: " + new java.util.Date(tokenExpiry));
                        } else {
                            Log.d("MainAuthController", "Token expired, will update from localStorage");
                        }
                    }
                } else {
                    Log.d("MainAuthController", "No token in DB, will load from localStorage when available");
                }

            } catch (Exception e) {
                Log.e("MainAuthController", "Error checking tokens on startup", e);
            }
        });
    }

    public void getAuthFromLocalStorage(WebView webView, Runnable callback) {
        if (webView != null) {
            webView.evaluateJavascript(
                "localStorage.getItem('auth')",
                value -> {
                    if (context instanceof AppCompatActivity) {
                        ((AppCompatActivity) context).runOnUiThread(() -> processAuthValue(value, callback));
                    } else {
                        processAuthValue(value, callback);
                    }
                }
            );
        } else {
            Log.d("MainAuthController", "WebView не готов");
            if (callback != null) {
                callback.run();
            }
        }
    }

    private void processAuthValue(String value, Runnable callback) {
        String authValue = value != null ? value.replaceAll("^\"|\"$", "") : "null";

        if (authValue != null && !"null".equals(authValue)) {
            authValue = authValue.replace("\\\"", "\"");
        }

        if ("null".equals(authValue)) {
            Log.d("MainAuthController", "Auth не найден в localStorage");
            if (callback != null) {
                callback.run();
            }
        } else {
            Log.d("MainAuthController", "Auth получен из localStorage");
            try {
                if (authValue.startsWith("{") && authValue.endsWith("}")) {
                    CookieSyncManager.saveAuthJson(authValue);
                    Gson gson = new Gson();
                    TokenResponse tokenResponse = gson.fromJson(authValue, TokenResponse.class);
                    if (tokenResponse != null && tokenResponse.getToken() != null) {
                        saveTokensToDatabase(tokenResponse, authValue, callback);
                        Log.d("MainAuthController", "Successfully parsed and saved tokens");
                    } else {
                        Log.w("MainAuthController", "Token data not found in auth response");
                        if (callback != null) callback.run();
                    }
                } else {
                    Log.w("MainAuthController", "Auth value is not valid JSON");
                    if (callback != null) callback.run();
                }
            } catch (Exception e) {
                Log.e("MainAuthController", "Error parsing auth JSON: " + e.getMessage());
                if (callback != null) callback.run();
            }
        }
    }

    public void getAuthAndStartVideoPlayer(WebView webView, String animeUrl) {
        Log.d("MainAuthController", "Getting auth token before starting VideoPlayerActivity");
        String currentUrl = webView != null ? webView.getUrl() : null;
        String resolvedUrl = VideoUrlHelper.resolveAnimeUrl(animeUrl, currentUrl);
        getAuthFromLocalStorage(webView, () -> {
            Log.d("MainAuthController", "Starting VideoPlayerActivity with resolved URL: " + resolvedUrl);
            VideoPlayerActivity.startFromAnimePage(context, resolvedUrl);
        });
    }

    public void saveTokensToDatabase(TokenResponse tokenResponse, String rawAuthJson, Runnable callback) {
        if (tokenResponse == null || tokenResponse.getToken() == null) {
            runOnUiIfNeeded(callback);
            return;
        }

        TokenResponse.TokenData tokenData = tokenResponse.getToken();
        String userId = null;
        String username = null;

        if (tokenResponse.getAuth() != null) {
            if (tokenResponse.getAuth().getId() != 0) {
                userId = String.valueOf(tokenResponse.getAuth().getId());
            }
            username = tokenResponse.getAuth().getUsername();
        }

        if (userId == null || userId.isEmpty()) {
            userId = ApiService.extractUserIdFromToken(tokenData.getAccessToken());
        }

        final String finalUserId = userId;
        final String finalUsername = username;

        executor.execute(() -> {
            try {
                TokenEntity existing = databaseManager.getToken();
                long timestamp = System.currentTimeMillis();

                if (existing != null) {
                    existing.setTokenType(tokenData.getTokenType());
                    existing.setExpiresIn(tokenData.getExpiresIn());
                    existing.setAccessToken(tokenData.getAccessToken());
                    existing.setRefreshToken(tokenData.getRefreshToken());
                    existing.setTimestamp(timestamp);
                    if (finalUserId != null && !finalUserId.isEmpty()) {
                        existing.setUserId(finalUserId);
                    }
                    if (finalUsername != null) {
                        existing.setUsername(finalUsername);
                    }
                    databaseManager.saveToken(existing);
                    Log.d("MainAuthController", "Updated existing token in database. UserId: " + existing.getUserId() + ", Username: " + existing.getUsername());
                } else {
                    TokenEntity newToken = new TokenEntity(
                            tokenData.getTokenType(),
                            tokenData.getExpiresIn(),
                            tokenData.getAccessToken(),
                            tokenData.getRefreshToken(),
                            timestamp,
                            finalUserId,
                            finalUsername
                    );
                    databaseManager.saveToken(newToken);
                    Log.d("MainAuthController", "Saved new token in database. UserId: " + finalUserId + ", Username: " + finalUsername);
                }

                CookieSyncManager.syncAllCookies();
            } catch (Exception e) {
                Log.e("MainAuthController", "Error saving tokens to database", e);
            } finally {
                runOnUiIfNeeded(callback);
            }
        });
    }

    public void clearAuthToken() {
        Log.d("MainAuthController", "Clearing auth token and user ID from database");
        executor.execute(() -> {
            try {
                databaseManager.deleteToken();
                Log.d("MainAuthController", "Token and user ID successfully deleted from database");
            } catch (Exception e) {
                Log.e("MainAuthController", "Error deleting token from database", e);
            }
        });
    }

    public void saveOAuthTokenFromJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            Log.w("MainAuthController", "Empty OAuth token JSON received");
            return;
        }

        Log.d("MainAuthController", "Processing intercepted OAuth token response");
        executor.execute(() -> {
            try {
                Gson gson = new Gson();
                JsonObject json = gson.fromJson(jsonString, JsonObject.class);
                if (json == null || !json.has("access_token") || json.get("access_token").isJsonNull()) {
                    Log.w("MainAuthController", "Invalid OAuth response JSON or missing access_token");
                    return;
                }

                String accessToken = json.get("access_token").getAsString();
                String refreshToken = json.has("refresh_token") && !json.get("refresh_token").isJsonNull()
                        ? json.get("refresh_token").getAsString() : null;
                String tokenType = json.has("token_type") && !json.get("token_type").isJsonNull()
                        ? json.get("token_type").getAsString() : "Bearer";
                long expiresIn = json.has("expires_in") && !json.get("expires_in").isJsonNull()
                        ? json.get("expires_in").getAsLong() : 2678399L;
                long timestamp = System.currentTimeMillis();

                TokenEntity existing = databaseManager.getToken();
                if (existing != null) {
                    existing.setTokenType(tokenType);
                    existing.setExpiresIn(expiresIn);
                    existing.setAccessToken(accessToken);
                    if (refreshToken != null) {
                        existing.setRefreshToken(refreshToken);
                    }
                    existing.setTimestamp(timestamp);
                    if (existing.getUserId() == null || existing.getUserId().isEmpty()) {
                        String extractedId = ApiService.extractUserIdFromToken(accessToken);
                        if (extractedId != null) {
                            existing.setUserId(extractedId);
                        }
                    }
                    databaseManager.saveToken(existing);
                    Log.d("MainAuthController", "Updated existing token with new OAuth token. userId: " + existing.getUserId());
                } else {
                    String extractedId = ApiService.extractUserIdFromToken(accessToken);
                    TokenEntity newToken = new TokenEntity(
                            tokenType,
                            expiresIn,
                            accessToken,
                            refreshToken,
                            timestamp,
                            extractedId,
                            null
                    );
                    databaseManager.saveToken(newToken);
                    Log.d("MainAuthController", "Saved new OAuth token. userId: " + extractedId);
                }
                CookieSyncManager.syncAllCookies();

            } catch (Exception e) {
                Log.e("MainAuthController", "Error saving OAuth token from JSON", e);
            }
        });
    }

    public void saveAuthMeDataFromJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            Log.w("MainAuthController", "Empty Auth me JSON received");
            return;
        }

        Log.d("MainAuthController", "Processing intercepted Auth me response");
        executor.execute(() -> {
            try {
                Gson gson = new Gson();
                JsonObject json = gson.fromJson(jsonString, JsonObject.class);
                if (json == null || !json.has("data") || json.get("data").isJsonNull()) {
                    Log.w("MainAuthController", "Invalid Auth me JSON or missing data object");
                    return;
                }

                JsonObject dataObj = json.getAsJsonObject("data");
                if (!dataObj.has("id") || dataObj.get("id").isJsonNull()) {
                    Log.w("MainAuthController", "Auth me data missing id");
                    return;
                }

                long id = dataObj.get("id").getAsLong();
                String userIdStr = String.valueOf(id);
                String username = dataObj.has("username") && !dataObj.get("username").isJsonNull()
                        ? dataObj.get("username").getAsString() : null;

                TokenEntity existing = databaseManager.getToken();
                if (existing != null) {
                    existing.setUserId(userIdStr);
                    if (username != null) {
                        existing.setUsername(username);
                    }
                    databaseManager.saveToken(existing);
                    Log.d("MainAuthController", "Updated token entity with user ID from auth/me: " + userIdStr + ", username: " + username);
                } else {
                    TokenEntity newToken = new TokenEntity(
                            "Bearer",
                            2678399L,
                            "",
                            "",
                            System.currentTimeMillis(),
                            userIdStr,
                            username
                    );
                    databaseManager.saveToken(newToken);
                    Log.d("MainAuthController", "Saved new token entity with user ID from auth/me: " + userIdStr + ", username: " + username);
                }
                CookieSyncManager.syncAllCookies();

            } catch (Exception e) {
                Log.e("MainAuthController", "Error saving Auth me data from JSON", e);
            }
        });
    }

    private void runOnUiIfNeeded(Runnable runnable) {
        if (runnable == null) return;
        if (context instanceof AppCompatActivity) {
            ((AppCompatActivity) context).runOnUiThread(runnable);
        } else {
            runnable.run();
        }
    }
}
