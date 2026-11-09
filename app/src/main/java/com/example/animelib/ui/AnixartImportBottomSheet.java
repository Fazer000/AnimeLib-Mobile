package com.example.animelib.ui;

import android.content.Context;
import android.graphics.Color;
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
 * - С графическим UI прогресса и текущего тайтла без необработанных логов
 * - Наглядной итоговой статистикой и карточками ненайденных/проблемных тайтлов
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

    // Card current live status
    private LinearLayout cardCurrentStatus;
    private TextView tvCurrentStateBadge;
    private TextView tvCurrentTitleName;
    private TextView tvCurrentStatusDetail;

    // Final Results Section
    private LinearLayout layoutResultsSection;
    private LinearLayout cardAllSuccess;
    private LinearLayout layoutProblemItemsSection;
    private TextView tvProblemItemsHeader;
    private LinearLayout layoutProblemItemsContainer;

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

        cardCurrentStatus = view.findViewById(R.id.cardCurrentStatus);
        tvCurrentStateBadge = view.findViewById(R.id.tvCurrentStateBadge);
        tvCurrentTitleName = view.findViewById(R.id.tvCurrentTitleName);
        tvCurrentStatusDetail = view.findViewById(R.id.tvCurrentStatusDetail);

        layoutResultsSection = view.findViewById(R.id.layoutResultsSection);
        cardAllSuccess = view.findViewById(R.id.cardAllSuccess);
        layoutProblemItemsSection = view.findViewById(R.id.layoutProblemItemsSection);
        tvProblemItemsHeader = view.findViewById(R.id.tvProblemItemsHeader);
        layoutProblemItemsContainer = view.findViewById(R.id.layoutProblemItemsContainer);

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
            CustomToast.showWarning(getContext(), "Вы не авторизованы на сайте!");
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

            // Сбрасываем отображение предыдущих результатов при выборе нового файла
            if (layoutResultsSection != null) layoutResultsSection.setVisibility(View.GONE);
            if (cardCurrentStatus != null) cardCurrentStatus.setVisibility(View.GONE);
            if (layoutImportStats != null) layoutImportStats.setVisibility(View.GONE);

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
        if (cardCurrentStatus != null) cardCurrentStatus.setVisibility(View.VISIBLE);
        if (layoutResultsSection != null) layoutResultsSection.setVisibility(View.GONE);

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
                updateCurrentItemCard(item);
            }

            @Override
            public void onLog(String message) {
                if (tvCurrentStatusDetail != null && message != null) {
                    if (message.contains("429") || message.contains("Лимит")) {
                        if (tvCurrentStateBadge != null) {
                            tvCurrentStateBadge.setText("ПАУЗА 429");
                            tvCurrentStateBadge.setBackgroundResource(R.drawable.badge_not_found);
                            tvCurrentStateBadge.setTextColor(Color.parseColor("#FFB74D"));
                        }
                    }
                    tvCurrentStatusDetail.setText(message);
                }
            }

            @Override
            public void onFinished(int successCount, int alreadyExistsCount, int notFoundCount, int errorCount, boolean cancelled, List<AnixartImportManager.ImportItemProgress> problemItems) {
                setCancelable(true);
                setCanceledOnTouchOutside(true);

                if (btnSelectFile != null) btnSelectFile.setEnabled(true);
                updateActionButtonState(false);

                if (cardCurrentStatus != null) cardCurrentStatus.setVisibility(View.GONE);
                showResultsUI(successCount, alreadyExistsCount, notFoundCount, errorCount, cancelled, problemItems);

                if (cancelled) {
                    CustomToast.showWarning(getContext(), "Импорт остановлен");
                } else {
                    CustomToast.showSuccess(getContext(), "Импорт завершен: " + successCount + " добавлено, " + alreadyExistsCount + " пропущено");
                }
            }
        });
    }

    private void updateCurrentItemCard(AnixartImportManager.ImportItemProgress item) {
        if (item == null) return;

        String title = item.entry != null ? item.entry.getEffectiveTitle() : "";
        if (tvCurrentTitleName != null) {
            tvCurrentTitleName.setText(title);
        }

        if (tvCurrentStateBadge == null || tvCurrentStatusDetail == null) return;

        switch (item.status) {
            case PROCESSING:
                tvCurrentStateBadge.setText("ПОИСК");
                tvCurrentStateBadge.setBackgroundResource(R.drawable.badge_success);
                tvCurrentStateBadge.setTextColor(Color.parseColor("#FFFFFF"));
                tvCurrentStatusDetail.setText("Поиск аниме в базе AnimeLib...");
                break;
            case ALREADY_EXISTS:
                tvCurrentStateBadge.setText("В СПИСКЕ");
                tvCurrentStateBadge.setBackgroundResource(R.drawable.badge_success);
                tvCurrentStateBadge.setTextColor(Color.parseColor("#FFFFFF"));
                tvCurrentStatusDetail.setText("Уже присутствует в списке «Просмотрено»");
                break;
            case SUCCESS:
                tvCurrentStateBadge.setText("ДОБАВЛЕНО");
                tvCurrentStateBadge.setBackgroundResource(R.drawable.badge_success);
                tvCurrentStateBadge.setTextColor(Color.parseColor("#FFFFFF"));
                tvCurrentStatusDetail.setText("Успешно добавлено в закладки!");
                break;
            case NOT_FOUND:
                tvCurrentStateBadge.setText("НЕ НАЙДЕНО");
                tvCurrentStateBadge.setBackgroundResource(R.drawable.badge_not_found);
                tvCurrentStateBadge.setTextColor(Color.parseColor("#FFB74D"));
                tvCurrentStatusDetail.setText("Не найдено в базе AnimeLib");
                break;
            case ERROR:
                tvCurrentStateBadge.setText("ОШИБКА");
                tvCurrentStateBadge.setBackgroundResource(R.drawable.badge_error);
                tvCurrentStateBadge.setTextColor(Color.parseColor("#FCA5A5"));
                tvCurrentStatusDetail.setText(item.errorMessage != null ? item.errorMessage : "Ошибка сохранения");
                break;
            default:
                break;
        }
    }

    private void showResultsUI(int successCount, int alreadyExistsCount, int notFoundCount, int errorCount, boolean cancelled, List<AnixartImportManager.ImportItemProgress> problemItems) {
        if (layoutResultsSection == null) return;
        layoutResultsSection.setVisibility(View.VISIBLE);

        if (problemItems == null || problemItems.isEmpty()) {
            if (cardAllSuccess != null) cardAllSuccess.setVisibility(View.VISIBLE);
            if (layoutProblemItemsSection != null) layoutProblemItemsSection.setVisibility(View.GONE);
        } else {
            if (cardAllSuccess != null) cardAllSuccess.setVisibility(View.GONE);
            if (layoutProblemItemsSection != null) layoutProblemItemsSection.setVisibility(View.VISIBLE);

            if (tvProblemItemsHeader != null) {
                tvProblemItemsHeader.setText("Не удалось импортировать (" + problemItems.size() + "):");
            }

            if (layoutProblemItemsContainer != null) {
                layoutProblemItemsContainer.removeAllViews();
                LayoutInflater inflater = LayoutInflater.from(getContext());

                for (AnixartImportManager.ImportItemProgress pItem : problemItems) {
                    View itemView = inflater.inflate(R.layout.item_anixart_import_problem, layoutProblemItemsContainer, false);

                    TextView tvTitle = itemView.findViewById(R.id.tvProblemTitleName);
                    TextView tvBadge = itemView.findViewById(R.id.tvProblemBadge);
                    TextView tvSearch = itemView.findViewById(R.id.tvProblemSearchQuery);
                    TextView tvReason = itemView.findViewById(R.id.tvProblemReason);

                    String mainTitle = pItem.entry != null && !pItem.entry.getPrimaryTitle().isEmpty()
                            ? pItem.entry.getPrimaryTitle()
                            : (pItem.entry != null ? pItem.entry.getEffectiveTitle() : "");

                    if (tvTitle != null) tvTitle.setText(mainTitle);

                    if (pItem.entry != null && !pItem.entry.getSearchTitle().isEmpty() && !pItem.entry.getSearchTitle().equalsIgnoreCase(mainTitle)) {
                        if (tvSearch != null) {
                            tvSearch.setVisibility(View.VISIBLE);
                            tvSearch.setText("Поиск: " + pItem.entry.getSearchTitle());
                        }
                    } else if (tvSearch != null) {
                        tvSearch.setVisibility(View.GONE);
                    }

                    if (pItem.status == AnixartImportManager.ItemResultStatus.NOT_FOUND) {
                        if (tvBadge != null) {
                            tvBadge.setText("НЕ НАЙДЕНО");
                            tvBadge.setBackgroundResource(R.drawable.badge_not_found);
                            tvBadge.setTextColor(Color.parseColor("#FFB74D"));
                        }
                        if (tvReason != null) {
                            tvReason.setText("Тайтл не найден в каталоге AnimeLib");
                        }
                    } else {
                        if (tvBadge != null) {
                            tvBadge.setText("ОШИБКА");
                            tvBadge.setBackgroundResource(R.drawable.badge_error);
                            tvBadge.setTextColor(Color.parseColor("#FCA5A5"));
                        }
                        if (tvReason != null) {
                            tvReason.setText(pItem.errorMessage != null ? pItem.errorMessage : "Ошибка сохранения статуса");
                        }
                    }

                    layoutProblemItemsContainer.addView(itemView);
                }
            }
        }
    }

    private void stopImportProcess() {
        if (importManager.isRunning()) {
            importManager.cancel();
            if (tvCurrentStatusDetail != null) {
                tvCurrentStatusDetail.setText("Остановка импорта...");
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
            if (tvCurrentStatusDetail != null) {
                tvCurrentStatusDetail.setText("Остановка импорта...");
            }
        } else {
            dismiss();
        }
    }
}
