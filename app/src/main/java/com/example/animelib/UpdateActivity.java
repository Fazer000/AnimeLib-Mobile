package com.example.animelib;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.example.animelib.models.UpdateInfo;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.ThemeUtils;
import com.example.animelib.util.UpdateManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateActivity extends AppCompatActivity {
    private static final String TAG = "UpdateActivity";
    public static final String EXTRA_UPDATE_INFO = "extra_update_info";

    private UpdateInfo updateInfo;
    private UpdateInfo stableInfo;

    private TextView tvVersionTitle;
    private TextView tvCurrentVsNew;
    private TextView tvReleaseDate;
    private TextView tvChangelog;

    private com.example.animelib.ui.M3LinearProgressIndicator progressDownload;
    private TextView tvDownloadStatus;
    private MaterialButton btnDownload;
    private MaterialButton btnOpenGithub;

    private MaterialSwitch switchBetaUpdates;
    private LinearLayout betaUpdatesCard;

    private LinearLayout layoutRollbackSection;
    private TextView tvRollbackDescription;
    private MaterialButton btnRollbackToStable;

    private boolean isDownloading = false;

    public static void start(Context context, UpdateInfo updateInfo) {
        Intent intent = new Intent(context, UpdateActivity.class);
        if (updateInfo != null) {
            intent.putExtra(EXTRA_UPDATE_INFO, updateInfo);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!isTaskRoot()) {
            Intent intent = getIntent();
            if (intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN.equals(intent.getAction())) {
                finish();
                return;
            }
        }
        try {
            ThemeUtils.applyThemeToActivity(this, ThemeUtils.getSavedThemePreference(this));
        } catch (Exception ignored) {}
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

        setupSystemBars();

        if (getIntent() != null && getIntent().hasExtra(EXTRA_UPDATE_INFO)) {
            try {
                updateInfo = (UpdateInfo) getIntent().getSerializableExtra(EXTRA_UPDATE_INFO);
            } catch (Exception e) {
                Log.e(TAG, "Error reading updateInfo extra", e);
            }
        }

        initViews();

        if (updateInfo != null) {
            bindUpdateInfo(updateInfo);
        } else {
            loadUpdatesFromNetwork();
        }
    }

    private void setupSystemBars() {
        try {
            if (getWindow() != null) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                int headerColor = androidx.core.content.ContextCompat.getColor(this, R.color.toolbar_header_bg);
                getWindow().setStatusBarColor(headerColor);
                boolean isDark = ThemeUtils.isDarkTheme(this);
                androidx.core.view.WindowInsetsControllerCompat insetsController =
                        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                if (insetsController != null) {
                    insetsController.setAppearanceLightStatusBars(!isDark);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting system bars", e);
        }
    }

    private void initViews() {
        ImageButton btnBack = findViewById(R.id.btnBack);
        ImageView ivUpdateLogo = findViewById(R.id.ivUpdateLogo);
        if (ivUpdateLogo != null) {
            boolean isDark = ThemeUtils.isDarkTheme(this);
            ivUpdateLogo.setImageResource(isDark ? R.drawable.ic_logo_non_back_night : R.drawable.ic_logo_non_back_light);
        }

        tvVersionTitle = findViewById(R.id.tvVersionTitle);
        tvCurrentVsNew = findViewById(R.id.tvCurrentVsNew);
        tvReleaseDate = findViewById(R.id.tvReleaseDate);
        tvChangelog = findViewById(R.id.tvChangelog);

        progressDownload = findViewById(R.id.progressDownload);
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus);
        btnDownload = findViewById(R.id.btnDownload);
        btnOpenGithub = findViewById(R.id.btnOpenGithub);

        betaUpdatesCard = findViewById(R.id.betaUpdatesCard);
        switchBetaUpdates = findViewById(R.id.switchBetaUpdates);

        layoutRollbackSection = findViewById(R.id.layoutRollbackSection);
        tvRollbackDescription = findViewById(R.id.tvRollbackDescription);
        btnRollbackToStable = findViewById(R.id.btnRollbackToStable);

        btnBack.setOnClickListener(v -> finish());

        if (switchBetaUpdates != null) {
            boolean isBetaEnabled = UpdateManager.isBetaUpdatesEnabled(this);
            switchBetaUpdates.setChecked(isBetaEnabled);
            switchBetaUpdates.setOnCheckedChangeListener((buttonView, isChecked) -> {
                UpdateManager.setBetaUpdatesEnabled(this, isChecked);
                CustomToast.showInfo(this, isChecked ? "Включено получение бета-версий" : "Выключено получение бета-версий");
                loadUpdatesFromNetwork();
            });
        }

        if (betaUpdatesCard != null && switchBetaUpdates != null) {
            betaUpdatesCard.setOnClickListener(v -> switchBetaUpdates.toggle());
        }

        btnOpenGithub.setOnClickListener(v -> {
            try {
                String url = (updateInfo != null && updateInfo.getHtmlUrl() != null && !updateInfo.getHtmlUrl().isEmpty())
                        ? updateInfo.getHtmlUrl()
                        : "https://github.com/Fazer000/AnimeLib-Mobile";
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception e) {
                CustomToast.showWarning(this, "Не удалось открыть браузер");
            }
        });

        btnDownload.setOnClickListener(v -> {
            if (isDownloading) return;
            if (updateInfo != null) {
                checkInstallPermissionAndDownload(updateInfo);
            }
        });

        if (btnRollbackToStable != null) {
            btnRollbackToStable.setOnClickListener(v -> {
                if (isDownloading) return;
                confirmAndRollback();
            });
        }
    }

    private void bindUpdateInfo(UpdateInfo info) {
        this.updateInfo = info;
        this.stableInfo = info != null ? info.getStableUpdateInfo() : UpdateManager.getCachedLatestStable();

        String currentVer = UpdateManager.getCurrentVersion(this);
        boolean isCurrentBeta = UpdateManager.isCurrentVersionBeta(this);

        if (info != null) {
            String latestTag = info.getTagName() != null ? info.getTagName() : "v0.0.0";
            String typeSuffix = info.isBeta() ? " (Beta)" : " (Релиз)";
            tvVersionTitle.setText("Доступна версия " + latestTag + typeSuffix);
            tvCurrentVsNew.setText("У вас: v" + currentVer + "  ➔  Новая: " + latestTag);

            String pubDate = formatDate(info.getPublishedAt());
            if (!pubDate.isEmpty()) {
                tvReleaseDate.setText("Дата публикации: " + pubDate);
                tvReleaseDate.setVisibility(View.VISIBLE);
            } else {
                tvReleaseDate.setVisibility(View.GONE);
            }

            String changelogText = info.getChangelog();
            if (changelogText != null && !changelogText.trim().isEmpty()) {
                changelogText = changelogText.replaceAll("(?m)^#+\\s*", "• ");
                tvChangelog.setText(changelogText.trim());
            } else {
                tvChangelog.setText("Описание изменений отсутствует.");
            }

            btnDownload.setEnabled(true);
            btnDownload.setText(R.string.btn_download_apk);
        } else {
            tvVersionTitle.setText("У вас последняя версия");
            tvCurrentVsNew.setText("Текущая версия: v" + currentVer);
            tvReleaseDate.setVisibility(View.GONE);
            tvChangelog.setText("Обновления отсутствуют.");
            btnDownload.setEnabled(false);
            btnDownload.setText("ОБНОВЛЕНИЙ НЕТ");
        }

        // Configure rollback section if current version is beta
        if (layoutRollbackSection != null) {
            if (isCurrentBeta) {
                layoutRollbackSection.setVisibility(View.VISIBLE);
                String stableTag = stableInfo != null && stableInfo.getTagName() != null ? stableInfo.getTagName() : "";
                if (!stableTag.isEmpty()) {
                    btnRollbackToStable.setText("ОТКАТИТЬСЯ ДО " + stableTag);
                    tvRollbackDescription.setText("У вас установлена тестовая бета-версия (" + currentVer + "). Вы можете вернуться к стабильной сборке " + stableTag + ".");
                } else {
                    btnRollbackToStable.setText("ОТКАТИТЬСЯ ДО СТАБИЛЬНОЙ ВЕРСИИ");
                    tvRollbackDescription.setText("У вас установлена тестовая бета-версия (" + currentVer + "). Вы можете вернуться к последней стабильной сборке.");
                }
            } else {
                layoutRollbackSection.setVisibility(View.GONE);
            }
        }
    }

    private void loadUpdatesFromNetwork() {
        if (progressDownload != null) {
            progressDownload.setVisibility(View.VISIBLE);
            progressDownload.setIndeterminate(true);
        }
        if (tvDownloadStatus != null) {
            tvDownloadStatus.setVisibility(View.VISIBLE);
            tvDownloadStatus.setText("Проверка обновлений...");
        }

        UpdateManager.checkForUpdates(this, null, new UpdateManager.CheckUpdateCallback() {
            @Override
            public void onUpdateCheckResult(boolean hasUpdate, UpdateInfo newUpdateInfo, String currentVersion) {
                runOnUiThread(() -> {
                    if (progressDownload != null) progressDownload.setVisibility(View.GONE);
                    if (tvDownloadStatus != null) tvDownloadStatus.setVisibility(View.GONE);
                    bindUpdateInfo(newUpdateInfo);
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    if (progressDownload != null) progressDownload.setVisibility(View.GONE);
                    if (tvDownloadStatus != null) tvDownloadStatus.setVisibility(View.GONE);
                    CustomToast.showWarning(UpdateActivity.this, errorMessage);
                });
            }
        });
    }

    private void confirmAndRollback() {
        UpdateInfo targetStable = stableInfo != null ? stableInfo : UpdateManager.getCachedLatestStable();
        if (targetStable != null && targetStable.getApkUrl() != null && !targetStable.getApkUrl().isEmpty()) {
            showCustomRollbackDialog(targetStable);
        } else {
            CustomToast.showInfo(this, "Поиск последней стабильной версии...");
            UpdateManager.checkForUpdates(this, null, new UpdateManager.CheckUpdateCallback() {
                @Override
                public void onUpdateCheckResult(boolean hasUpdate, UpdateInfo newInfo, String currentVersion) {
                    runOnUiThread(() -> {
                        UpdateInfo foundStable = newInfo != null && newInfo.getStableUpdateInfo() != null
                                ? newInfo.getStableUpdateInfo()
                                : UpdateManager.getCachedLatestStable();
                        if (foundStable != null && foundStable.getApkUrl() != null && !foundStable.getApkUrl().isEmpty()) {
                            stableInfo = foundStable;
                            showCustomRollbackDialog(foundStable);
                        } else {
                            CustomToast.showWarning(UpdateActivity.this, "Стабильная версия на сервере не найдена");
                        }
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> CustomToast.showWarning(UpdateActivity.this, "Не удалось найти стабильную версию: " + errorMessage));
                }
            });
        }
    }

    private void showCustomRollbackDialog(UpdateInfo targetStable) {
        if (isFinishing() || isDestroyed()) return;

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.dialog_confirm_rollback);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        TextView tvTitle = dialog.findViewById(R.id.tvRollbackDialogTitle);
        TextView tvMessage = dialog.findViewById(R.id.tvRollbackDialogMessage);
        ImageButton btnClose = dialog.findViewById(R.id.btnCloseRollbackDialog);
        MaterialButton btnCancel = dialog.findViewById(R.id.btnCancelRollback);
        MaterialButton btnConfirm = dialog.findViewById(R.id.btnConfirmRollback);

        String stableTag = targetStable.getTagName() != null ? targetStable.getTagName() : "стабильную версию";
        String currentVer = UpdateManager.getCurrentVersion(this);

        if (tvTitle != null) {
            tvTitle.setText("Откат до версии " + stableTag);
        }

        if (tvMessage != null) {
            tvMessage.setText("Вы уверены, что хотите откатиться на " + stableTag + "?\n\nТекущая бета-версия (" + currentVer + ") будет заменена.");
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                dialog.dismiss();
                checkInstallPermissionAndDownload(targetStable);
            });
        }

        dialog.show();
    }

    private void checkInstallPermissionAndDownload(UpdateInfo infoToDownload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                CustomToast.showInfo(this, "Разрешите установку из неизвестных источников");
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Error opening install permission settings", e);
                }
                return;
            }
        }
        startApkDownload(infoToDownload);
    }

    @SuppressLint("SetTextI18n")
    private void startApkDownload(UpdateInfo targetInfo) {
        if (targetInfo == null) return;
        String apkUrl = targetInfo.getApkUrl();
        if (apkUrl == null || apkUrl.isEmpty() || apkUrl.equalsIgnoreCase(targetInfo.getHtmlUrl())) {
            CustomToast.showInfo(this, "Открываем страницу релиза на GitHub...");
            btnOpenGithub.performClick();
            return;
        }

        isDownloading = true;
        btnDownload.setEnabled(false);
        btnDownload.setText("ЗАГРУЗКА...");
        if (btnRollbackToStable != null) btnRollbackToStable.setEnabled(false);

        progressDownload.setVisibility(View.VISIBLE);
        progressDownload.setIndeterminate(true);
        tvDownloadStatus.setVisibility(View.VISIBLE);
        tvDownloadStatus.setText("Подключение к GitHub...");

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "AnimeLib-App")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull java.io.IOException e) {
                runOnUiThread(() -> {
                    isDownloading = false;
                    btnDownload.setEnabled(true);
                    btnDownload.setText(R.string.btn_download_apk);
                    if (btnRollbackToStable != null) btnRollbackToStable.setEnabled(true);
                    progressDownload.setVisibility(View.GONE);
                    tvDownloadStatus.setText("Ошибка скачивания");
                    CustomToast.showWarning(UpdateActivity.this, "Ошибка скачивания файла");
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws java.io.IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        isDownloading = false;
                        btnDownload.setEnabled(true);
                        btnDownload.setText(R.string.btn_download_apk);
                        if (btnRollbackToStable != null) btnRollbackToStable.setEnabled(true);
                        progressDownload.setVisibility(View.GONE);
                        tvDownloadStatus.setText("Ошибка сервера HTTP " + response.code());
                        CustomToast.showWarning(UpdateActivity.this, "Ошибка загрузки файла");
                    });
                    return;
                }

                try {
                    File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (downloadsDir != null && !downloadsDir.exists()) {
                        downloadsDir.mkdirs();
                    }
                    String fileName = targetInfo.isBeta() ? "app-beta-update.apk" : "app-stable-update.apk";
                    File apkFile = new File(downloadsDir, fileName);
                    if (apkFile.exists()) {
                        apkFile.delete();
                    }

                    long contentLength = response.body() != null ? response.body().contentLength() : 0;
                    InputStream inputStream = response.body() != null ? response.body().byteStream() : null;

                    if (inputStream == null) {
                        runOnUiThread(() -> {
                            isDownloading = false;
                            btnDownload.setEnabled(true);
                            btnDownload.setText(R.string.btn_download_apk);
                            if (btnRollbackToStable != null) btnRollbackToStable.setEnabled(true);
                            progressDownload.setVisibility(View.GONE);
                            CustomToast.showWarning(UpdateActivity.this, "Не удалось получить файл");
                        });
                        return;
                    }

                    OutputStream outputStream = new FileOutputStream(apkFile);
                    byte[] buffer = new byte[8192];
                    long bytesReadTotal = 0;
                    int read;

                    runOnUiThread(() -> {
                        progressDownload.setIndeterminate(false);
                        progressDownload.setMax(100);
                    });

                    long lastProgressUpdate = 0;

                    while ((read = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                        bytesReadTotal += read;

                        long now = System.currentTimeMillis();
                        if (contentLength > 0 && (now - lastProgressUpdate > 200)) {
                            lastProgressUpdate = now;
                            final int progress = (int) ((bytesReadTotal * 100) / contentLength);
                            final long currentMB = bytesReadTotal / (1024 * 1024);
                            final long totalMB = contentLength / (1024 * 1024);

                            runOnUiThread(() -> {
                                progressDownload.setProgress(progress);
                                tvDownloadStatus.setText("Загрузка: " + progress + "% (" + currentMB + " / " + totalMB + " МБ)");
                            });
                        }
                    }

                    outputStream.flush();
                    outputStream.close();
                    inputStream.close();

                    runOnUiThread(() -> {
                        isDownloading = false;
                        btnDownload.setEnabled(true);
                        btnDownload.setText("УСТАНОВИТЬ ПОВТОРНО");
                        if (btnRollbackToStable != null) btnRollbackToStable.setEnabled(true);
                        progressDownload.setProgress(100);
                        tvDownloadStatus.setText("Загрузка завершена!");
                        CustomToast.showSuccess(UpdateActivity.this, "Файл загружен! Запуск установки...");
                        installApk(apkFile);
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Error saving APK", e);
                    runOnUiThread(() -> {
                        isDownloading = false;
                        btnDownload.setEnabled(true);
                        btnDownload.setText(R.string.btn_download_apk);
                        if (btnRollbackToStable != null) btnRollbackToStable.setEnabled(true);
                        progressDownload.setVisibility(View.GONE);
                        tvDownloadStatus.setText("Ошибка сохранения файла");
                        CustomToast.showWarning(UpdateActivity.this, "Ошибка сохранения файла обновления");
                    });
                }
            }
        });
    }

    private void installApk(File apkFile) {
        try {
            if (!apkFile.exists()) {
                CustomToast.showWarning(this, "Файл APK не найден");
                return;
            }

            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch package installer", e);
            CustomToast.showWarning(this, "Не удалось запустить установку обновления");
        }
    }

    private String formatDate(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return "";
        try {
            if (isoDate.contains("T")) {
                String datePart = isoDate.split("T")[0];
                String[] parts = datePart.split("-");
                if (parts.length == 3) {
                    return parts[2] + "." + parts[1] + "." + parts[0];
                }
            }
            return isoDate;
        } catch (Exception e) {
            return isoDate;
        }
    }
}
