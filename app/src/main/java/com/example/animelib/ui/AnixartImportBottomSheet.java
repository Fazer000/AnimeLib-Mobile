package com.example.animelib.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.managers.AnixartImportManager;
import com.example.animelib.util.AnixartParser;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * BottomSheet диалог для импорта тайтлов из txt файла Anixart
 */
public class AnixartImportBottomSheet extends FlexibleBottomSheetDialog {

    private static final String TAG = "AnixartImportBS";

    public interface FilePickerRequester {
        void pickTextFile();
    }

    private final AnixartImportManager importManager;
    private final ApiService apiService;
    private FilePickerRequester filePickerRequester;

    private TextView tvSelectedFileName;
    private TextView tvSelectedFileInfo;
    private LinearLayout btnSelectFile;
    private LinearLayout layoutImportStats;
    private TextView tvStatSuccess;
    private TextView tvStatNotFound;
    private TextView tvStatErrors;
    private TextView tvImportProgressFraction;
    private ProgressBar pbImportProgress;
    private TextView tvImportStatusLog;
    private MaterialButton btnCancelOrStop;
    private MaterialButton btnStartImport;

    private List<AnixartParser.AnixartEntry> pendingEntries = new ArrayList<>();
    private int currentSuccess = 0;
    private int currentNotFound = 0;
    private int currentErrors = 0;

    public AnixartImportBottomSheet(@NonNull Context context) {
        super(context);
        this.apiService = new ApiService(context);
        this.importManager = new AnixartImportManager(context);
    }

    public void setFilePickerRequester(FilePickerRequester requester) {
        this.filePickerRequester = requester;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_anixart_import, null);
        setContentView(view);
        FloatingBottomSheetUtils.setupFloatingStyle(this);

        initViews(view);
        checkAuthorization();
    }

    private void initViews(View view) {
        ImageButton btnClose = view.findViewById(R.id.btnCloseImport);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> handleCloseOrCancel());
        }

        tvSelectedFileName = view.findViewById(R.id.tvSelectedFileName);
        tvSelectedFileInfo = view.findViewById(R.id.tvSelectedFileInfo);
        btnSelectFile = view.findViewById(R.id.btnSelectFile);

        layoutImportStats = view.findViewById(R.id.layoutImportStats);
        tvStatSuccess = view.findViewById(R.id.tvStatSuccess);
        tvStatNotFound = view.findViewById(R.id.tvStatNotFound);
        tvStatErrors = view.findViewById(R.id.tvStatErrors);
        tvImportProgressFraction = view.findViewById(R.id.tvImportProgressFraction);
        pbImportProgress = view.findViewById(R.id.pbImportProgress);
        tvImportStatusLog = view.findViewById(R.id.tvImportStatusLog);

        btnCancelOrStop = view.findViewById(R.id.btnCancelOrStop);
        btnStartImport = view.findViewById(R.id.btnStartImport);

        if (btnSelectFile != null) {
            btnSelectFile.setOnClickListener(v -> {
                if (importManager.isRunning()) {
                    CustomToast.showWarning(getContext(), "Идет процесс импорта");
                    return;
                }
                if (filePickerRequester != null) {
                    filePickerRequester.pickTextFile();
                }
            });
        }

        if (btnCancelOrStop != null) {
            btnCancelOrStop.setOnClickListener(v -> handleCloseOrCancel());
        }

        if (btnStartImport != null) {
            btnStartImport.setOnClickListener(v -> startImportProcess());
        }
    }

    private void checkAuthorization() {
        if (!apiService.isAuthorized()) {
            if (tvImportStatusLog != null) {
                tvImportStatusLog.setText("Внимание: Вы не авторизованы на сайте! Закладки могут не сохраниться.");
            }
        }
    }

    /**
     * Загружает содержимое из выбранного URI
     */
    public void onFileSelected(Uri uri, String fileName) {
        if (uri == null) return;
        try {
            Context ctx = getContext();
            InputStream inputStream = ctx.getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                CustomToast.showWarning(ctx, "Не удалось открыть файл");
                return;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }

            String content = sb.toString();
            List<AnixartParser.AnixartEntry> entries = AnixartParser.parse(content);

            if (entries.isEmpty()) {
                CustomToast.showWarning(ctx, "Файл не содержит корректных записей Anixart");
                if (tvSelectedFileInfo != null) {
                    tvSelectedFileInfo.setText("Записей не обнаружено");
                }
                if (btnStartImport != null) {
                    btnStartImport.setEnabled(false);
                }
                return;
            }

            pendingEntries = entries;
            if (tvSelectedFileName != null) {
                tvSelectedFileName.setText(fileName != null ? fileName : "export.txt");
            }
            if (tvSelectedFileInfo != null) {
                tvSelectedFileInfo.setText("Найдено тайтлов для импорта: " + entries.size());
            }
            if (tvImportStatusLog != null) {
                tvImportStatusLog.setText("Готов к импорту " + entries.size() + " тайтлов. Нажмите «Начать импорт».");
            }
            if (btnStartImport != null) {
                btnStartImport.setEnabled(true);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to read txt file", e);
            CustomToast.showWarning(getContext(), "Ошибка чтения файла: " + e.getMessage());
        }
    }

    private void startImportProcess() {
        if (pendingEntries == null || pendingEntries.isEmpty()) {
            CustomToast.showWarning(getContext(), "Выберите текстовый файл со списком");
            return;
        }

        currentSuccess = 0;
        currentNotFound = 0;
        currentErrors = 0;

        if (layoutImportStats != null) layoutImportStats.setVisibility(View.VISIBLE);
        if (pbImportProgress != null) {
            pbImportProgress.setVisibility(View.VISIBLE);
            pbImportProgress.setMax(pendingEntries.size());
            pbImportProgress.setProgress(0);
        }

        updateStatsViews(0, pendingEntries.size());

        if (btnStartImport != null) btnStartImport.setEnabled(false);
        if (btnSelectFile != null) btnSelectFile.setEnabled(false);
        if (btnCancelOrStop != null) {
            btnCancelOrStop.setText(R.string.anixart_import_stop);
            btnCancelOrStop.setTextColor(getContext().getColor(R.color.dialog_danger_text));
        }

        setCancelable(false);
        setCanceledOnTouchOutside(false);

        importManager.startImport(pendingEntries, new AnixartImportManager.ImportListener() {
            @Override
            public void onProgress(int current, int total, AnixartImportManager.ImportItemProgress item) {
                if (pbImportProgress != null) {
                    pbImportProgress.setProgress(current);
                }
                if (item.status == AnixartImportManager.ItemResultStatus.SUCCESS) {
                    currentSuccess++;
                } else if (item.status == AnixartImportManager.ItemResultStatus.NOT_FOUND) {
                    currentNotFound++;
                } else if (item.status == AnixartImportManager.ItemResultStatus.ERROR) {
                    currentErrors++;
                }
                updateStatsViews(current, total);
            }

            @Override
            public void onLog(String message) {
                if (tvImportStatusLog != null) {
                    tvImportStatusLog.setText(message);
                }
            }

            @Override
            public void onFinished(int successCount, int notFoundCount, int errorCount, boolean cancelled) {
                setCancelable(true);
                setCanceledOnTouchOutside(true);

                if (btnStartImport != null) btnStartImport.setEnabled(true);
                if (btnSelectFile != null) btnSelectFile.setEnabled(true);
                if (btnCancelOrStop != null) {
                    btnCancelOrStop.setText(R.string.btn_close);
                    btnCancelOrStop.setTextColor(getContext().getColor(R.color.dialog_cancel_text));
                }

                if (cancelled) {
                    if (tvImportStatusLog != null) {
                        tvImportStatusLog.setText("Импорт остановлен пользователем. Добавлено: " + successCount);
                    }
                    CustomToast.showWarning(getContext(), "Импорт остановлен");
                } else {
                    if (tvImportStatusLog != null) {
                        tvImportStatusLog.setText("Импорт завершен! Успешно: " + successCount + ", не найдено: " + notFoundCount + ", ошибок: " + errorCount);
                    }
                    CustomToast.showSuccess(getContext(), "Импорт завершен: " + successCount + " тайтлов добавлено");
                }
            }
        });
    }

    private void updateStatsViews(int current, int total) {
        if (tvStatSuccess != null) tvStatSuccess.setText("Успешно: " + currentSuccess);
        if (tvStatNotFound != null) tvStatNotFound.setText("Не найдено: " + currentNotFound);
        if (tvStatErrors != null) tvStatErrors.setText("Ошибки: " + currentErrors);
        if (tvImportProgressFraction != null) {
            tvImportProgressFraction.setText(current + " / " + total);
        }
    }

    private void handleCloseOrCancel() {
        if (importManager.isRunning()) {
            importManager.cancel();
            if (tvImportStatusLog != null) {
                tvImportStatusLog.setText("Остановка импорта...");
            }
        } else {
            dismiss();
        }
    }
}
