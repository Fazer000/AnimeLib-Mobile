package com.example.animelib.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

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
 * - С единой кнопкой действия на всю ширину (Запуск / Остановить)
 * - M3LinearProgressIndicator в стиле activity_update
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
    private TextView tvStatSkipped;
    private TextView tvStatNotFound;
    private TextView tvStatErrors;
    private TextView tvImportProgressFraction;
    private M3LinearProgressIndicator m3ProgressIndicator;
    private TextView tvImportStatusLog;
    private MaterialButton btnActionImport;

    private List<AnixartParser.AnixartEntry> pendingEntries = new ArrayList<>();
    private int currentSuccess = 0;
    private int currentSkipped = 0;
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
            btnClose.setOnClickListener(v -> handleHeaderClose());
        }

        tvSelectedFileName = view.findViewById(R.id.tvSelectedFileName);
        tvSelectedFileInfo = view.findViewById(R.id.tvSelectedFileInfo);
        btnSelectFile = view.findViewById(R.id.btnSelectFile);

        layoutImportStats = view.findViewById(R.id.layoutImportStats);
        tvStatSuccess = view.findViewById(R.id.tvStatSuccess);
        tvStatSkipped = view.findViewById(R.id.tvStatSkipped);
        tvStatNotFound = view.findViewById(R.id.tvStatNotFound);
        tvStatErrors = view.findViewById(R.id.tvStatErrors);
        tvImportProgressFraction = view.findViewById(R.id.tvImportProgressFraction);
        m3ProgressIndicator = view.findViewById(R.id.m3ProgressIndicator);
        tvImportStatusLog = view.findViewById(R.id.tvImportStatusLog);

        btnActionImport = view.findViewById(R.id.btnActionImport);

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

        if (btnActionImport != null) {
            btnActionImport.setOnClickListener(v -> {
                if (importManager.isRunning()) {
                    // Пользователь нажал "Остановить"
                    stopImportProcess();
                } else {
                    // Пользователь нажал "Начать импорт"
                    startImportProcess();
                }
            });
        }

        updateActionButtonState(false);
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
                if (btnActionImport != null) {
                    btnActionImport.setEnabled(false);
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
            if (btnActionImport != null) {
                btnActionImport.setEnabled(true);
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
        currentSkipped = 0;
        currentNotFound = 0;
        currentErrors = 0;

        if (layoutImportStats != null) layoutImportStats.setVisibility(View.VISIBLE);
        if (m3ProgressIndicator != null) {
            m3ProgressIndicator.setMax(pendingEntries.size());
            m3ProgressIndicator.setProgress(0, false);
        }

        updateStatsViews(0, pendingEntries.size());

        if (btnSelectFile != null) btnSelectFile.setEnabled(false);
        updateActionButtonState(true);

        setCancelable(false);
        setCanceledOnTouchOutside(false);

        importManager.startImport(pendingEntries, new AnixartImportManager.ImportListener() {
            @Override
            public void onProgress(int current, int total, AnixartImportManager.ImportItemProgress item) {
                if (m3ProgressIndicator != null) {
                    m3ProgressIndicator.setProgress(current, true);
                }
                if (item.status == AnixartImportManager.ItemResultStatus.SUCCESS) {
                    currentSuccess++;
                } else if (item.status == AnixartImportManager.ItemResultStatus.ALREADY_EXISTS) {
                    currentSkipped++;
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
            public void onFinished(int successCount, int alreadyExistsCount, int notFoundCount, int errorCount, boolean cancelled) {
                setCancelable(true);
                setCanceledOnTouchOutside(true);

                if (btnSelectFile != null) btnSelectFile.setEnabled(true);
                updateActionButtonState(false);

                if (cancelled) {
                    if (tvImportStatusLog != null) {
                        tvImportStatusLog.setText("Импорт остановлен пользователем. Добавлено: " + successCount + ", уже в списке: " + alreadyExistsCount);
                    }
                    CustomToast.showWarning(getContext(), "Импорт остановлен");
                } else {
                    if (tvImportStatusLog != null) {
                        tvImportStatusLog.setText("Импорт завершен! Добавлено: " + successCount + ", уже в списке: " + alreadyExistsCount + ", не найдено: " + notFoundCount + ", ошибок: " + errorCount);
                    }
                    CustomToast.showSuccess(getContext(), "Импорт завершен: " + successCount + " добавлено, " + alreadyExistsCount + " пропущено");
                }
            }
        });
    }

    private void stopImportProcess() {
        if (importManager.isRunning()) {
            importManager.cancel();
            if (tvImportStatusLog != null) {
                tvImportStatusLog.setText("Остановка импорта...");
            }
            if (btnActionImport != null) {
                btnActionImport.setEnabled(false);
            }
        }
    }

    private void updateActionButtonState(boolean isRunning) {
        if (btnActionImport == null) return;
        btnActionImport.setEnabled(true);
        if (isRunning) {
            btnActionImport.setText(R.string.anixart_import_stop);
            btnActionImport.setIcon(ContextCompat.getDrawable(getContext(), R.drawable.ic_pause));
            btnActionImport.setBackgroundTintList(ContextCompat.getColorStateList(getContext(), R.color.dialog_danger_bg));
            btnActionImport.setTextColor(ContextCompat.getColor(getContext(), R.color.dialog_danger_text));
            btnActionImport.setIconTint(ContextCompat.getColorStateList(getContext(), R.color.dialog_danger_text));
        } else {
            btnActionImport.setText(R.string.anixart_import_start);
            btnActionImport.setIcon(ContextCompat.getDrawable(getContext(), R.drawable.ic_file_upload));
            btnActionImport.setBackgroundTintList(ContextCompat.getColorStateList(getContext(), R.color.purple_primary));
            btnActionImport.setTextColor(ContextCompat.getColor(getContext(), R.color.white_color));
            btnActionImport.setIconTint(ContextCompat.getColorStateList(getContext(), R.color.white_color));
            if (pendingEntries.isEmpty()) {
                btnActionImport.setEnabled(false);
            }
        }
    }

    private void updateStatsViews(int current, int total) {
        if (tvStatSuccess != null) tvStatSuccess.setText("Добавлено: " + currentSuccess);
        if (tvStatSkipped != null) tvStatSkipped.setText("Уже в списке: " + currentSkipped);
        if (tvStatNotFound != null) tvStatNotFound.setText("Не найдено: " + currentNotFound);
        if (tvStatErrors != null) tvStatErrors.setText("Ошибки: " + currentErrors);
        if (tvImportProgressFraction != null) {
            tvImportProgressFraction.setText(current + " / " + total);
        }
    }

    private void handleHeaderClose() {
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
