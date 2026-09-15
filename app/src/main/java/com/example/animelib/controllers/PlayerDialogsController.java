package com.example.animelib.controllers;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.example.animelib.R;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.settings.BugReportBottomSheet;
import com.example.animelib.settings.SettingsBottomSheet;
import com.example.animelib.ui.DownloadsActivity;
import com.example.animelib.ui.TitleWebViewBottomSheet;
import com.example.animelib.util.CustomToast;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * Контроллер для отображения и управления диалоговыми окнами и BottomSheet в плеере.
 */
public class PlayerDialogsController {

    private final Activity activity;
    private AlertDialog currentErrorDialog;
    private SettingsBottomSheet currentSettingsBottomSheet;

    public interface DialogCallback {
        PlayersManager getPlayersManager();
        String getPreferredQuality();
        void hideLoading();
        void showLoading(String message);
        void safeRunOnUiThread(Runnable runnable);
    }

    private DialogCallback callback;

    public PlayerDialogsController(Activity activity) {
        this.activity = activity;
    }

    public void setCallback(DialogCallback callback) {
        this.callback = callback;
    }

    public void dismissErrorDialog() {
        if (currentErrorDialog != null && currentErrorDialog.isShowing()) {
            try {
                currentErrorDialog.dismiss();
            } catch (Exception ignored) {}
            currentErrorDialog = null;
        }
    }

    public void showVideoErrorDialog(String title, String message, Runnable retryAction) {
        showVideoErrorDialog(title, message, null, retryAction, false);
    }

    public void showVideoErrorDialog(String title, String message, Runnable retryAction, boolean isVoiceoverError) {
        showVideoErrorDialog(title, message, null, retryAction, isVoiceoverError);
    }

    public void showVideoErrorDialog(String title, String message, @Nullable String logDetails, Runnable retryAction, boolean isVoiceoverError) {
        if (activity == null || activity.isFinishing()) return;

        Runnable action = () -> {
            dismissErrorDialog();

            if (callback != null) {
                callback.hideLoading();
            }

            LayoutInflater inflater = activity.getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.dialog_video_error, null);
            TextView titleTv = dialogView.findViewById(R.id.errorTitleText);
            TextView messageTv = dialogView.findViewById(R.id.errorMessageText);
            TextView detailsTv = dialogView.findViewById(R.id.errorPlayerDetailsText);
            TextView logTv = dialogView.findViewById(R.id.errorLogDetailsText);
            MaterialButton retryBtn = dialogView.findViewById(R.id.retryButton);
            MaterialButton goToDownloadsBtn = dialogView.findViewById(R.id.goToDownloadsButton);
            MaterialButton copyLogBtn = dialogView.findViewById(R.id.copyLogButton);
            MaterialButton exitBtn = dialogView.findViewById(R.id.exitButton);
            ImageButton closeCrossBtn = dialogView.findViewById(R.id.closeErrorCrossButton);

            if (goToDownloadsBtn != null) {
                goToDownloadsBtn.setOnClickListener(v -> {
                    dismissErrorDialog();
                    DownloadsActivity.start(activity);
                });
            }

            if (titleTv != null) {
                titleTv.setText((title != null && !title.isEmpty()) ? title : "Ошибка загрузки видео");
            }

            if (messageTv != null) {
                messageTv.setText(message != null ? message : "Произошла ошибка при загрузке видео.");
            }

            PlayersManager pm = callback != null ? callback.getPlayersManager() : null;
            EpisodeResponse.PlayerData playerData = pm != null ? pm.getCurrentPlayerData() : null;
            StringBuilder playerDetailsBuilder = new StringBuilder();
            if (playerData != null && !isVoiceoverError && detailsTv != null) {
                String pName = playerData.getPlayer() != null ? playerData.getPlayer() : "Неизвестный";
                String tName = playerData.getTeam() != null ? playerData.getTeam().getName() : "";
                String qName = callback != null ? callback.getPreferredQuality() : "";
                playerDetailsBuilder.append("Плеер: ").append(pName);
                if (tName != null && !tName.isEmpty()) {
                    playerDetailsBuilder.append(" (").append(tName).append(")");
                }
                if (qName != null && !qName.isEmpty()) {
                    playerDetailsBuilder.append(" • ").append(qName);
                }
                detailsTv.setText(playerDetailsBuilder.toString());
                detailsTv.setVisibility(View.VISIBLE);
            } else if (detailsTv != null) {
                detailsTv.setVisibility(View.GONE);
            }

            final StringBuilder fullLogBuilder = new StringBuilder();
            fullLogBuilder.append("=== ANIME LIB ERROR LOG ===");
            fullLogBuilder.append("\nTitle: ").append(title != null ? title : "Ошибка");
            fullLogBuilder.append("\nMessage: ").append(message != null ? message : "Нет сообщения");
            if (playerDetailsBuilder.length() > 0) {
                fullLogBuilder.append("\nDetails: ").append(playerDetailsBuilder);
            }
            if (logDetails != null && !logDetails.isEmpty()) {
                fullLogBuilder.append("\n\n--- TECHNICAL DETAILS ---\n").append(logDetails);
            }

            final String fullLogString = fullLogBuilder.toString();

            if (logTv != null) {
                logTv.setText(fullLogString);
            }

            MaterialButton reportBugBtn = dialogView.findViewById(R.id.reportBugButton);
            if (reportBugBtn != null) {
                reportBugBtn.setOnClickListener(v -> {
                    dismissErrorDialog();
                    BugReportBottomSheet bugReportBottomSheet = new BugReportBottomSheet(activity, title, fullLogString);
                    bugReportBottomSheet.show();
                });
            }

            if (copyLogBtn != null) {
                copyLogBtn.setOnClickListener(v -> {
                    try {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("AnimeLib Error Log", fullLogString);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(clip);
                            CustomToast.showInfo(activity, "Лог скопирован в буфер обмена");
                        }
                    } catch (Exception e) {
                        CustomToast.showWarning(activity, "Ошибка копирования лога");
                    }
                });
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setView(dialogView);
            currentErrorDialog = builder.create();

            if (currentErrorDialog.getWindow() != null) {
                currentErrorDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }

            if (retryBtn != null) {
                retryBtn.setOnClickListener(v -> {
                    dismissErrorDialog();
                    if (retryAction != null) {
                        if (callback != null) callback.showLoading("Повторная попытка...");
                        retryAction.run();
                    }
                });
            }

            if (exitBtn != null) {
                exitBtn.setOnClickListener(v -> {
                    dismissErrorDialog();
                    activity.finish();
                });
            }

            if (closeCrossBtn != null) {
                closeCrossBtn.setOnClickListener(v -> dismissErrorDialog());
            }

            currentErrorDialog.show();
        };

        if (callback != null) {
            callback.safeRunOnUiThread(action);
        } else {
            activity.runOnUiThread(action);
        }
    }

    public void showTitleWebViewBottomSheet(String titleName, String webUrl) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            TitleWebViewBottomSheet bottomSheet = new TitleWebViewBottomSheet(activity, titleName, webUrl);
            bottomSheet.show();
        } catch (Exception e) {
            android.util.Log.e("PlayerDialogsCtrl", "Error showing TitleWebViewBottomSheet", e);
        }
    }

    public interface SettingsDataProvider {
        boolean isOfflineMode();
        PlayersManager getPlayersManager();
        List<String> getAvailableQualities();
        String getPreferredQuality();
        void onQualitySelected(String newQuality);
        float getPlaybackSpeed();
        void onSpeedChanged(float speed);
        boolean isEnable4K();
        void onEnable4KChanged(boolean enabled);
        boolean isEnableAmbientLight();
        void onEnableAmbientLightChanged(boolean enabled);
        boolean isAutoPlay();
        void onAutoPlayChanged(boolean enabled);
        int getLongSkipDuration();
        void onLongSkipDurationChanged(int duration);
        int getCurrentTheme();
        void onThemeChanged(int themeMode);
        float[] getVideoFilters();
        void onVideoFiltersChanged(float b, float c, float s, float g, float h);
        boolean isSurround3DEnabled();
        int getSurroundMode();
        float getSurroundSpatialWidth();
        float getSurroundDialogueBoost();
        float getSurroundBassBoost();
        float getSurroundTrebleBoost();
        void onSurround3DChanged(boolean enabled, int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost);
        int getCurrentResizeMode();
        void onResizeModeChanged(int newMode);
        boolean isSubtitlesEnabled();
        String getSubtitleFormat();
        List<EpisodeResponse.SubtitleData> getSubtitles();
        void onSubtitleSettingsChanged(boolean enabled, String format);
        float getSubtitleTextSize();
        int getSubtitleTextColor();
        int getSubtitleBackgroundColor();
        int getSubtitleEdgeType();
        int getSubtitleEdgeColor();
        void onSubtitleStyleSettingsChanged(float textSize, int textColor, int bgColor, int edgeType, int edgeColor);
        String getCurrentVideoDomain();
        void onVideoDomainChanged(String domain);
        default boolean isPlayingDownloadedVideo() {
            return isOfflineMode();
        }
    }

    public void showSettingsDialog(SettingsDataProvider provider) {
        if (activity == null || activity.isFinishing() || provider == null) return;

        if (!provider.isOfflineMode() && (provider.getPlayersManager() == null || provider.getPlayersManager().getCurrentPlayerData() == null)) {
            return;
        }

        List<String> availableQualities = provider.getAvailableQualities();
        if (!provider.isOfflineMode() && (availableQualities == null || availableQualities.isEmpty())) {
            CustomToast.showWarning(activity, "Качества недоступны");
            return;
        }

        SettingsBottomSheet dialog = new SettingsBottomSheet(activity, availableQualities, provider.getPreferredQuality(),
                provider::onQualitySelected,
                provider.getPlaybackSpeed(),
                provider::onSpeedChanged,
                provider.isEnable4K(),
                provider::onEnable4KChanged,
                provider.isEnableAmbientLight(),
                provider::onEnableAmbientLightChanged,
                provider.isAutoPlay(),
                provider::onAutoPlayChanged,
                provider.getLongSkipDuration(),
                provider::onLongSkipDurationChanged,
                provider.getCurrentTheme(),
                provider::onThemeChanged);

        dialog.setOfflineMode(provider.isOfflineMode());
        dialog.setDownloadedVideo(provider.isPlayingDownloadedVideo());

        float[] filters = provider.getVideoFilters();
        if (filters != null && filters.length >= 5) {
            dialog.setVideoFilters(filters[0], filters[1], filters[2], filters[3], filters[4],
                    provider::onVideoFiltersChanged);
        }

        dialog.setSurround3DSettings(
                provider.isSurround3DEnabled(),
                provider.getSurroundMode(),
                provider.getSurroundSpatialWidth(),
                provider.getSurroundDialogueBoost(),
                provider.getSurroundBassBoost(),
                provider.getSurroundTrebleBoost(),
                provider::onSurround3DChanged
        );

        dialog.setResizeMode(provider.getCurrentResizeMode(), provider::onResizeModeChanged);

        dialog.setSubtitleSettings(
                provider.isSubtitlesEnabled(),
                provider.getSubtitleFormat(),
                provider.getSubtitles(),
                provider::onSubtitleSettingsChanged
        );

        dialog.setSubtitleStyleSettings(
                provider.getSubtitleTextSize(),
                provider.getSubtitleTextColor(),
                provider.getSubtitleBackgroundColor(),
                provider.getSubtitleEdgeType(),
                provider.getSubtitleEdgeColor(),
                provider::onSubtitleStyleSettingsChanged
        );

        boolean checkKodik = false;
        if (provider.getPlayersManager() != null && provider.getPlayersManager().getCurrentPlayerData() != null) {
            String pType = provider.getPlayersManager().getCurrentPlayerData().getPlayer();
            if (pType != null && "kodik".equalsIgnoreCase(pType)) {
                checkKodik = true;
            }
        }
        dialog.setVideoServerSettings(provider.getCurrentVideoDomain(), checkKodik, provider::onVideoDomainChanged);

        this.currentSettingsBottomSheet = dialog;
        dialog.show();
    }

    public void setCurrentSettingsBottomSheet(SettingsBottomSheet bottomSheet) {
        this.currentSettingsBottomSheet = bottomSheet;
    }

    public SettingsBottomSheet getCurrentSettingsBottomSheet() {
        return currentSettingsBottomSheet;
    }

    public void dismissSettingsBottomSheet() {
        if (currentSettingsBottomSheet != null && currentSettingsBottomSheet.isShowing()) {
            try {
                currentSettingsBottomSheet.dismiss();
            } catch (Exception ignored) {}
            currentSettingsBottomSheet = null;
        }
    }

    public void updateSettingsQualities(List<String> newQualities, String preferredQuality) {
        if (currentSettingsBottomSheet != null && currentSettingsBottomSheet.isShowing()) {
            currentSettingsBottomSheet.updateQualities(newQualities, preferredQuality);
        }
    }
}
