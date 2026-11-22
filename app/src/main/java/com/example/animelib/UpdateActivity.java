package com.example.animelib;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.animelib.models.UpdateInfo;
import com.example.animelib.services.AppUpdateDownloadService;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.ThemeUtils;
import com.example.animelib.util.UpdateManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.util.Locale;

public class UpdateActivity extends AppCompatActivity implements AppUpdateDownloadService.DownloadStateListener {
    private static final String TAG = "UpdateActivity";
    public static final String EXTRA_UPDATE_INFO = "extra_update_info";
    public static final String EXTRA_AUTO_INSTALL = "extra_auto_install";
    private static final int REQUEST_POST_NOTIFICATIONS = 4305;

    private UpdateInfo updateInfo;
    private UpdateInfo stableInfo;
    private UpdateInfo currentBetaInfo;

    private TextView tvVersionTitle;
    private TextView tvCurrentVsNew;
    private TextView tvReleaseDate;
    private TextView tvChangelog;

    private com.example.animelib.ui.M3LinearProgressIndicator progressDownload;
    private TextView tvDownloadStatus;
    private MaterialButton btnDownload;
    private MaterialButton btnCancelDownload;
    private MaterialButton btnOpenGithub;

    private MaterialSwitch switchBetaUpdates;
    private LinearLayout betaUpdatesCard;

    private LinearLayout layoutRollbackSection;
    private TextView tvRollbackDescription;
    private MaterialButton btnReinstallBeta;
    private MaterialButton btnRollbackToStable;

    private boolean isCheckingNetwork = false;
    private boolean isActivityResumed = false;
    private boolean pendingAutoInstallFromIntent = false;

    // Отложенные действия после выдачи разрешений (установка из неизвестных источников / уведомления)
    @Nullable
    private UpdateInfo pendingDownloadInfo;
    private boolean pendingForceRedownload = false;
    @Nullable
    private File pendingInstallFile;
    @Nullable
    private UpdateInfo pendingNotifPermDownloadInfo;
    private boolean pendingNotifPermForceRedownload = false;

    public static void start(Context context, @Nullable UpdateInfo updateInfo) {
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
        readIntentExtras(getIntent());
        initViews();

        // Если уже идёт загрузка или есть сохранённый готовый APK, подхватываем его UpdateInfo
        AppUpdateDownloadService.DownloadSnapshot snapshot = AppUpdateDownloadService.getSnapshot();
        if (updateInfo == null) {
            if (snapshot.targetInfo != null) {
                updateInfo = snapshot.targetInfo;
            } else {
                updateInfo = AppUpdateDownloadService.getSavedDownloadedUpdateInfo(this);
            }
        }

        if (updateInfo != null) {
            bindUpdateInfo(updateInfo);
        } else {
            loadUpdatesFromNetwork();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntentExtras(intent);
        if (updateInfo != null) {
            bindUpdateInfo(updateInfo);
        }
        if (isActivityResumed) {
            syncWithDownloadService(true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppUpdateDownloadService.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityResumed = true;

        // Если пользователь вернулся из настроек разрешения установки APK — автоматически продолжаем
        if (canRequestPackageInstallsCompat()) {
            if (pendingInstallFile != null) {
                File fileToInstall = pendingInstallFile;
                pendingInstallFile = null;
                if (fileToInstall.exists()) {
                    launchApkInstaller(fileToInstall);
                }
            } else if (pendingDownloadInfo != null) {
                UpdateInfo infoToDownload = pendingDownloadInfo;
                boolean force = pendingForceRedownload;
                pendingDownloadInfo = null;
                pendingForceRedownload = false;
                requestNotificationPermissionAndStartDownload(infoToDownload, force);
            }
        }

        syncWithDownloadService(true);
    }

    @Override
    protected void onPause() {
        isActivityResumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        AppUpdateDownloadService.removeListener(this);
        super.onStop();
    }

    private void readIntentExtras(@Nullable Intent intent) {
        if (intent == null) return;
        if (intent.hasExtra(EXTRA_UPDATE_INFO)) {
            try {
                UpdateInfo extra = (UpdateInfo) intent.getSerializableExtra(EXTRA_UPDATE_INFO);
                if (extra != null) {
                    updateInfo = extra;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading updateInfo extra", e);
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_INSTALL, false)) {
            pendingAutoInstallFromIntent = true;
            intent.removeExtra(EXTRA_AUTO_INSTALL);
        }
    }

    private void setupSystemBars() {
        try {
            if (getWindow() != null) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                int headerColor = ContextCompat.getColor(this, R.color.toolbar_header_bg);
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
        btnCancelDownload = findViewById(R.id.btnCancelDownload);
        btnOpenGithub = findViewById(R.id.btnOpenGithub);

        betaUpdatesCard = findViewById(R.id.betaUpdatesCard);
        switchBetaUpdates = findViewById(R.id.switchBetaUpdates);

        layoutRollbackSection = findViewById(R.id.layoutRollbackSection);
        tvRollbackDescription = findViewById(R.id.tvRollbackDescription);
        btnReinstallBeta = findViewById(R.id.btnReinstallBeta);
        btnRollbackToStable = findViewById(R.id.btnRollbackToStable);

        btnBack.setOnClickListener(v -> finish());

        if (switchBetaUpdates != null) {
            try {
                android.content.res.ColorStateList trackTint = ContextCompat.getColorStateList(this, R.color.switch_track_tint);
                android.content.res.ColorStateList thumbTint = ContextCompat.getColorStateList(this, R.color.switch_thumb_tint);
                if (trackTint != null) switchBetaUpdates.setTrackTintList(trackTint);
                if (thumbTint != null) switchBetaUpdates.setThumbTintList(thumbTint);
                switchBetaUpdates.setTrackDecorationTintList(null);
                switchBetaUpdates.setThumbIconTintList(null);
            } catch (Exception ignored) {}

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
            if (AppUpdateDownloadService.isDownloadActive()) return;

            // 1. Если для текущего экрана уже скачан валидный APK — устанавливаем СРАЗУ без повторной загрузки!
            File readyApk = resolveReadyApkForScreen();
            if (readyApk != null) {
                checkInstallPermissionAndInstall(readyApk);
                return;
            }

            // 2. Иначе скачиваем обновление (или переустанавливаем текущую бету, если нет более новой)
            String currentVer = UpdateManager.getCurrentVersion(this);
            boolean isNewerAvailable = updateInfo != null && UpdateManager.isUpdateAvailable(currentVer, updateInfo.getTagName());
            if (isNewerAvailable) {
                checkInstallPermissionAndDownload(updateInfo, false);
            } else if (UpdateManager.isCurrentVersionBeta(this)) {
                startBetaReinstall();
            } else if (updateInfo != null) {
                checkInstallPermissionAndDownload(updateInfo, false);
            }
        });

        if (btnCancelDownload != null) {
            btnCancelDownload.setOnClickListener(v -> {
                AppUpdateDownloadService.cancelDownload(this);
                CustomToast.showInfo(this, "Загрузка обновления отменена");
            });
        }

        if (btnReinstallBeta != null) {
            btnReinstallBeta.setOnClickListener(v -> {
                if (AppUpdateDownloadService.isDownloadActive()) return;
                startBetaReinstall();
            });
        }

        if (btnRollbackToStable != null) {
            btnRollbackToStable.setOnClickListener(v -> {
                if (AppUpdateDownloadService.isDownloadActive()) return;
                confirmAndRollback();
            });
        }
    }

    @SuppressLint("SetTextI18n")
    private void bindUpdateInfo(@Nullable UpdateInfo info) {
        this.updateInfo = info;
        this.stableInfo = (info != null && info.getStableUpdateInfo() != null)
                ? info.getStableUpdateInfo()
                : UpdateManager.getCachedLatestStable();
        this.currentBetaInfo = UpdateManager.getCachedCurrentBeta();

        String currentVer = UpdateManager.getCurrentVersion(this);
        boolean isCurrentBeta = UpdateManager.isCurrentVersionBeta(this);
        boolean isNewerAvailable = info != null && UpdateManager.isUpdateAvailable(currentVer, info.getTagName());

        if (info != null && isNewerAvailable) {
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
        } else {
            tvVersionTitle.setText("У вас последняя версия");
            tvCurrentVsNew.setText("Текущая версия: v" + currentVer);

            UpdateInfo displayInfo = info != null ? info : (isCurrentBeta ? currentBetaInfo : stableInfo);
            if (displayInfo != null) {
                String pubDate = formatDate(displayInfo.getPublishedAt());
                if (!pubDate.isEmpty()) {
                    tvReleaseDate.setText("Дата публикации: " + pubDate);
                    tvReleaseDate.setVisibility(View.VISIBLE);
                } else {
                    tvReleaseDate.setVisibility(View.GONE);
                }
                String changelogText = displayInfo.getChangelog();
                if (changelogText != null && !changelogText.trim().isEmpty()) {
                    changelogText = changelogText.replaceAll("(?m)^#+\\s*", "• ");
                    tvChangelog.setText(changelogText.trim());
                } else {
                    tvChangelog.setText("Установлена актуальная сборка.");
                }
            } else {
                tvReleaseDate.setVisibility(View.GONE);
                tvChangelog.setText("Обновления отсутствуют.");
            }
        }

        // Настройка секции управления бета-версией (переустановка + откат)
        if (layoutRollbackSection != null) {
            if (isCurrentBeta) {
                layoutRollbackSection.setVisibility(View.VISIBLE);
                if (betaUpdatesCard != null) {
                    betaUpdatesCard.setBackgroundResource(R.drawable.bg_m3_item_top);
                    if (betaUpdatesCard.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                        android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) betaUpdatesCard.getLayoutParams();
                        lp.bottomMargin = (int) (3 * getResources().getDisplayMetrics().density);
                        betaUpdatesCard.setLayoutParams(lp);
                    }
                }

                String betaTag = (currentBetaInfo != null && currentBetaInfo.getTagName() != null && !currentBetaInfo.getTagName().isEmpty())
                        ? currentBetaInfo.getTagName()
                        : ("v" + currentVer);
                if (btnReinstallBeta != null) {
                    btnReinstallBeta.setText("ПЕРЕУСТАНОВИТЬ " + betaTag);
                }

                String stableTag = stableInfo != null && stableInfo.getTagName() != null ? stableInfo.getTagName() : "";
                if (!stableTag.isEmpty()) {
                    btnRollbackToStable.setText("ОТКАТИТЬСЯ ДО " + stableTag);
                    tvRollbackDescription.setText("У вас установлена бета-версия (" + currentVer + "). Вы можете переустановить её или вернуться к стабильной сборке " + stableTag + ".");
                } else {
                    btnRollbackToStable.setText("ОТКАТИТЬСЯ ДО СТАБИЛЬНОЙ ВЕРСИИ");
                    tvRollbackDescription.setText("У вас установлена бета-версия (" + currentVer + "). Вы можете переустановить её или вернуться к последней стабильной сборке.");
                }
            } else {
                layoutRollbackSection.setVisibility(View.GONE);
                if (betaUpdatesCard != null) {
                    betaUpdatesCard.setBackgroundResource(R.drawable.bg_m3_item_single);
                    if (betaUpdatesCard.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                        android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) betaUpdatesCard.getLayoutParams();
                        lp.bottomMargin = 0;
                        betaUpdatesCard.setLayoutParams(lp);
                    }
                }
            }
        }

        syncWithDownloadService(false);
    }

    private void loadUpdatesFromNetwork() {
        isCheckingNetwork = true;
        if (!AppUpdateDownloadService.isDownloadActive()) {
            if (progressDownload != null) {
                progressDownload.setVisibility(View.VISIBLE);
                progressDownload.setIndeterminate(true);
            }
            if (tvDownloadStatus != null) {
                tvDownloadStatus.setVisibility(View.VISIBLE);
                tvDownloadStatus.setText("Проверка обновлений...");
            }
        }

        UpdateManager.checkForUpdates(this, null, new UpdateManager.CheckUpdateCallback() {
            @Override
            public void onUpdateCheckResult(boolean hasUpdate, UpdateInfo newUpdateInfo, String currentVersion) {
                runOnUiThread(() -> {
                    isCheckingNetwork = false;
                    if (isFinishing() || isDestroyed()) return;
                    bindUpdateInfo(newUpdateInfo);
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    isCheckingNetwork = false;
                    if (isFinishing() || isDestroyed()) return;
                    syncWithDownloadService(false);
                    CustomToast.showWarning(UpdateActivity.this, errorMessage);
                });
            }
        });
    }

    // ==================== Download State Synchronization ====================

    @Override
    public void onDownloadStateChanged(@NonNull AppUpdateDownloadService.DownloadSnapshot snapshot) {
        if (isFinishing() || isDestroyed()) return;
        applyDownloadSnapshot(snapshot, isActivityResumed);
    }

    private void syncWithDownloadService(boolean allowAutoInstall) {
        applyDownloadSnapshot(AppUpdateDownloadService.getSnapshot(), allowAutoInstall && isActivityResumed);
    }

    @SuppressLint("SetTextI18n")
    private void applyDownloadSnapshot(@NonNull AppUpdateDownloadService.DownloadSnapshot snapshot,
                                       boolean allowAutoInstall) {
        boolean isDownloading = snapshot.isActive();

        if (isDownloading) {
            btnDownload.setEnabled(false);
            btnDownload.setText("ЗАГРУЗКА...");
            btnDownload.setIconResource(R.drawable.ic_download);
            if (btnCancelDownload != null) {
                btnCancelDownload.setVisibility(View.VISIBLE);
                btnCancelDownload.setEnabled(true);
            }
            if (btnReinstallBeta != null) btnReinstallBeta.setEnabled(false);
            if (btnRollbackToStable != null) btnRollbackToStable.setEnabled(false);

            progressDownload.setVisibility(View.VISIBLE);
            tvDownloadStatus.setVisibility(View.VISIBLE);

            if (snapshot.state == AppUpdateDownloadService.State.CONNECTING) {
                progressDownload.setIndeterminate(true);
                tvDownloadStatus.setText("Подключение к GitHub...");
            } else if (snapshot.totalBytes > 0L) {
                progressDownload.setIndeterminate(false);
                progressDownload.setMax(100);
                progressDownload.setProgress(snapshot.progress);
                String status = String.format(
                        Locale.US,
                        "Загрузка: %d%% (%.1f / %.1f МБ)",
                        snapshot.progress,
                        snapshot.downloadedBytes / (1024f * 1024f),
                        snapshot.totalBytes / (1024f * 1024f)
                );
                tvDownloadStatus.setText(status);
            } else {
                progressDownload.setIndeterminate(true);
                tvDownloadStatus.setText(String.format(
                        Locale.US,
                        "Загружено: %.1f МБ",
                        snapshot.downloadedBytes / (1024f * 1024f)
                ));
            }
            return;
        }

        // Не скачивается — скрываем кнопку отмены, включаем кнопки управления бетой
        if (btnCancelDownload != null) {
            btnCancelDownload.setVisibility(View.GONE);
        }
        if (btnReinstallBeta != null) btnReinstallBeta.setEnabled(true);
        if (btnRollbackToStable != null) btnRollbackToStable.setEnabled(true);

        // Проверяем, есть ли уже скачанный и валидный APK на диске
        File readyApk = resolveReadyApkForScreen();
        if (readyApk != null) {
            progressDownload.setVisibility(View.VISIBLE);
            progressDownload.setIndeterminate(false);
            progressDownload.setMax(100);
            progressDownload.setProgress(100);

            String readyTag = resolveReadyApkTag(snapshot);
            tvDownloadStatus.setVisibility(View.VISIBLE);
            tvDownloadStatus.setText(readyTag.isEmpty()
                    ? "Файл загружен и готов к установке"
                    : ("Версия " + readyTag + " загружена и готова к установке"));

            btnDownload.setEnabled(true);
            btnDownload.setText(R.string.btn_install_apk);
            btnDownload.setIconResource(R.drawable.ic_check);

            boolean shouldAutoInstall = allowAutoInstall
                    && (snapshot.pendingAutoInstall || pendingAutoInstallFromIntent);
            if (shouldAutoInstall) {
                pendingAutoInstallFromIntent = false;
                AppUpdateDownloadService.consumePendingAutoInstall();
                AppUpdateDownloadService.clearResultNotification(this);
                CustomToast.showSuccess(this, "Файл загружен! Запуск установки...");
                checkInstallPermissionAndInstall(readyApk);
            }
            return;
        }

        // Готового файла нет — обрабатываем ошибки / отмену / обычное состояние
        if (snapshot.state == AppUpdateDownloadService.State.ERROR) {
            progressDownload.setVisibility(View.GONE);
            tvDownloadStatus.setVisibility(View.VISIBLE);
            tvDownloadStatus.setText(snapshot.errorMessage != null ? snapshot.errorMessage : "Ошибка скачивания");
        } else if (snapshot.state == AppUpdateDownloadService.State.CANCELLED) {
            progressDownload.setVisibility(View.GONE);
            tvDownloadStatus.setVisibility(View.VISIBLE);
            tvDownloadStatus.setText("Загрузка отменена");
        } else if (!isCheckingNetwork) {
            progressDownload.setVisibility(View.GONE);
            tvDownloadStatus.setVisibility(View.GONE);
        }

        String currentVer = UpdateManager.getCurrentVersion(this);
        boolean isCurrentBeta = UpdateManager.isCurrentVersionBeta(this);
        boolean isNewerAvailable = updateInfo != null && UpdateManager.isUpdateAvailable(currentVer, updateInfo.getTagName());

        if (isNewerAvailable) {
            btnDownload.setEnabled(true);
            btnDownload.setText(R.string.btn_download_apk);
            btnDownload.setIconResource(R.drawable.ic_download);
        } else if (isCurrentBeta) {
            btnDownload.setEnabled(true);
            btnDownload.setText(R.string.btn_reinstall_beta);
            btnDownload.setIconResource(R.drawable.ic_refresh);
        } else {
            btnDownload.setEnabled(false);
            btnDownload.setText("ОБНОВЛЕНИЙ НЕТ");
            btnDownload.setIconResource(R.drawable.ic_download);
        }
    }

    @Nullable
    private File resolveReadyApkForScreen() {
        AppUpdateDownloadService.DownloadSnapshot snapshot = AppUpdateDownloadService.getSnapshot();

        // 1. Если сервис недавно завершил загрузку (обновление, откат или переустановка беты)
        if (snapshot.state == AppUpdateDownloadService.State.COMPLETED && snapshot.targetInfo != null) {
            File completed = AppUpdateDownloadService.getReadyDownloadedApk(this, snapshot.targetInfo);
            if (completed != null) {
                return completed;
            }
        }

        // 2. Проверяем сохранённый APK для отображаемого updateInfo
        if (updateInfo != null) {
            File matched = AppUpdateDownloadService.getReadyDownloadedApk(this, updateInfo);
            if (matched != null) {
                return matched;
            }
        }

        // 3. Если Activity была открыта по уведомлению после перезапуска процесса — берём последний готовый APK
        if (pendingAutoInstallFromIntent) {
            return AppUpdateDownloadService.getReadyDownloadedApk(this, null);
        }

        return null;
    }

    @NonNull
    private String resolveReadyApkTag(@NonNull AppUpdateDownloadService.DownloadSnapshot snapshot) {
        if (snapshot.state == AppUpdateDownloadService.State.COMPLETED
                && snapshot.targetInfo != null
                && snapshot.targetInfo.getTagName() != null) {
            return snapshot.targetInfo.getTagName();
        }
        UpdateInfo saved = AppUpdateDownloadService.getSavedDownloadedUpdateInfo(this);
        if (saved != null && saved.getTagName() != null) {
            return saved.getTagName();
        }
        if (updateInfo != null && updateInfo.getTagName() != null) {
            return updateInfo.getTagName();
        }
        return "";
    }

    // ==================== Beta Reinstall & Stable Rollback ====================

    private void startBetaReinstall() {
        UpdateInfo targetBeta = resolveCurrentBetaUpdateInfo();
        if (targetBeta != null && hasDirectApkUrl(targetBeta)) {
            checkInstallPermissionAndDownload(targetBeta, true);
            return;
        }

        CustomToast.showInfo(this, "Поиск актуальной бета-сборки...");
        UpdateManager.checkForUpdates(this, null, new UpdateManager.CheckUpdateCallback() {
            @Override
            public void onUpdateCheckResult(boolean hasUpdate, UpdateInfo newInfo, String currentVersion) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    currentBetaInfo = UpdateManager.getCachedCurrentBeta();
                    UpdateInfo foundBeta = resolveCurrentBetaUpdateInfo();
                    if (foundBeta != null && hasDirectApkUrl(foundBeta)) {
                        checkInstallPermissionAndDownload(foundBeta, true);
                    } else if (newInfo != null && hasDirectApkUrl(newInfo)) {
                        checkInstallPermissionAndDownload(newInfo, true);
                    } else {
                        CustomToast.showWarning(UpdateActivity.this, "APK бета-версии не найден на GitHub");
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    CustomToast.showWarning(UpdateActivity.this, "Не удалось загрузить данные релиза: " + errorMessage);
                });
            }
        });
    }

    @Nullable
    private UpdateInfo resolveCurrentBetaUpdateInfo() {
        if (currentBetaInfo != null && hasDirectApkUrl(currentBetaInfo)) {
            return currentBetaInfo;
        }
        UpdateInfo cached = UpdateManager.getCachedCurrentBeta();
        if (cached != null && hasDirectApkUrl(cached)) {
            currentBetaInfo = cached;
            return cached;
        }
        if (updateInfo != null && updateInfo.isBeta() && hasDirectApkUrl(updateInfo)) {
            return updateInfo;
        }
        return null;
    }

    private void confirmAndRollback() {
        UpdateInfo targetStable = stableInfo != null ? stableInfo : UpdateManager.getCachedLatestStable();
        if (targetStable != null && hasDirectApkUrl(targetStable)) {
            showCustomRollbackDialog(targetStable);
        } else {
            CustomToast.showInfo(this, "Поиск последней стабильной версии...");
            UpdateManager.checkForUpdates(this, null, new UpdateManager.CheckUpdateCallback() {
                @Override
                public void onUpdateCheckResult(boolean hasUpdate, UpdateInfo newInfo, String currentVersion) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        UpdateInfo foundStable = newInfo != null && newInfo.getStableUpdateInfo() != null
                                ? newInfo.getStableUpdateInfo()
                                : UpdateManager.getCachedLatestStable();
                        if (foundStable != null && hasDirectApkUrl(foundStable)) {
                            stableInfo = foundStable;
                            showCustomRollbackDialog(foundStable);
                        } else {
                            CustomToast.showWarning(UpdateActivity.this, "Стабильная версия на сервере не найдена");
                        }
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        CustomToast.showWarning(UpdateActivity.this, "Не удалось найти стабильную версию: " + errorMessage);
                    });
                }
            });
        }
    }

    private void showCustomRollbackDialog(@NonNull UpdateInfo targetStable) {
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
                checkInstallPermissionAndDownload(targetStable, false);
            });
        }

        dialog.show();
    }

    // ==================== Permissions, Service Launch & APK Install ====================

    private boolean hasDirectApkUrl(@Nullable UpdateInfo info) {
        if (info == null) return false;
        String apkUrl = info.getApkUrl();
        return apkUrl != null && !apkUrl.isEmpty() && !apkUrl.equalsIgnoreCase(info.getHtmlUrl());
    }

    private boolean canRequestPackageInstallsCompat() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private void openInstallPermissionSettings() {
        CustomToast.showInfo(this, "Разрешите установку из неизвестных источников");
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error opening install permission settings", e);
        }
    }

    private void checkInstallPermissionAndDownload(@NonNull UpdateInfo infoToDownload, boolean forceRedownload) {
        if (!hasDirectApkUrl(infoToDownload)) {
            CustomToast.showInfo(this, "Открываем страницу релиза на GitHub...");
            btnOpenGithub.performClick();
            return;
        }

        if (!canRequestPackageInstallsCompat()) {
            pendingInstallFile = null;
            pendingDownloadInfo = infoToDownload;
            pendingForceRedownload = forceRedownload;
            openInstallPermissionSettings();
            return;
        }

        requestNotificationPermissionAndStartDownload(infoToDownload, forceRedownload);
    }

    private void checkInstallPermissionAndInstall(@NonNull File apkFile) {
        if (!apkFile.exists()) {
            CustomToast.showWarning(this, "Файл APK не найден");
            AppUpdateDownloadService.clearDownloadedApkRecord(this);
            syncWithDownloadService(false);
            return;
        }
        if (!canRequestPackageInstallsCompat()) {
            pendingDownloadInfo = null;
            pendingInstallFile = apkFile;
            openInstallPermissionSettings();
            return;
        }
        launchApkInstaller(apkFile);
    }

    private void requestNotificationPermissionAndStartDownload(@NonNull UpdateInfo targetInfo, boolean forceRedownload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingNotifPermDownloadInfo = targetInfo;
            pendingNotifPermForceRedownload = forceRedownload;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS
            );
            return;
        }
        startBackgroundDownloadService(targetInfo, forceRedownload);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS && pendingNotifPermDownloadInfo != null) {
            UpdateInfo info = pendingNotifPermDownloadInfo;
            boolean force = pendingNotifPermForceRedownload;
            pendingNotifPermDownloadInfo = null;
            pendingNotifPermForceRedownload = false;
            startBackgroundDownloadService(info, force);
        }
    }

    private void startBackgroundDownloadService(@NonNull UpdateInfo targetInfo, boolean forceRedownload) {
        AppUpdateDownloadService.startDownload(this, targetInfo, forceRedownload);
    }

    private void launchApkInstaller(@NonNull File apkFile) {
        try {
            if (!apkFile.exists()) {
                CustomToast.showWarning(this, "Файл APK не найден");
                AppUpdateDownloadService.clearDownloadedApkRecord(this);
                syncWithDownloadService(false);
                return;
            }

            AppUpdateDownloadService.clearResultNotification(this);
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

    private String formatDate(@Nullable String isoDate) {
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
