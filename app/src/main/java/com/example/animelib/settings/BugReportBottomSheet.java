package com.example.animelib.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.animelib.R;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.google.android.material.button.MaterialButton;

public class BugReportBottomSheet extends FlexibleBottomSheetDialog {

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    private OnBackPressedListener onBackPressedListener;
    private String initialTitle;
    private String initialLog;

    public BugReportBottomSheet(@NonNull Context context) {
        this(context, null, null);
    }

    public BugReportBottomSheet(@NonNull Context context, String initialTitle, String initialLog) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.initialTitle = initialTitle;
        this.initialLog = initialLog;
    }

    public void setOnBackPressedListener(OnBackPressedListener listener) {
        this.onBackPressedListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_bug_report, null);
        setContentView(view);
        FloatingBottomSheetUtils.setupFloatingStyle(this);

        ImageButton backButton = view.findViewById(R.id.bs_bug_report_back);
        ImageButton closeButton = view.findViewById(R.id.bs_bug_report_close);
        EditText etBugTitle = view.findViewById(R.id.etBugTitle);
        EditText etBugDescription = view.findViewById(R.id.etBugDescription);
        TextView tvDeviceInfo = view.findViewById(R.id.tvDeviceInfo);
        MaterialButton btnOpenGithub = view.findViewById(R.id.btnOpenGithub);
        MaterialButton btnCopyReport = view.findViewById(R.id.btnCopyReport);

        String appVersion = "1.8.7";
        try {
            PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            appVersion = pInfo.versionName;
        } catch (Exception ignored) {}

        String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
        String androidVersion = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";

        StringBuilder diagBuilder = new StringBuilder();
        diagBuilder.append("Приложение: v").append(appVersion).append("\n");
        diagBuilder.append("Устройство: ").append(deviceModel).append("\n");
        diagBuilder.append("ОС: Android ").append(androidVersion);

        if (initialLog != null && !initialLog.trim().isEmpty()) {
            diagBuilder.append("\n\nДоп. данные / Лог:\n").append(initialLog.trim());
        }

        String diagnosticInfoText = diagBuilder.toString();
        if (tvDeviceInfo != null) {
            tvDeviceInfo.setText(diagnosticInfoText);
        }

        if (initialTitle != null && etBugTitle != null) {
            etBugTitle.setText(initialTitle);
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                dismiss();
                if (onBackPressedListener != null) {
                    onBackPressedListener.onBackPressed();
                }
            });
        }

        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }

        final String finalAppVersion = appVersion;
        final String finalDeviceModel = deviceModel;
        final String finalAndroidVersion = androidVersion;

        if (btnOpenGithub != null) {
            btnOpenGithub.setOnClickListener(v -> {
                String inputTitle = etBugTitle != null ? etBugTitle.getText().toString().trim() : "";
                String inputDesc = etBugDescription != null ? etBugDescription.getText().toString().trim() : "";

                String issueTitle = inputTitle.isEmpty() ? "[Bug Report] Ошибка в приложении AnimeLib" : inputTitle;
                String markdownBody = buildMarkdownReport(inputDesc, finalAppVersion, finalDeviceModel, finalAndroidVersion, initialLog);

                try {
                    Uri githubUri = Uri.parse("https://github.com/Fazer000/AnimeLib-Mobile/issues/new")
                            .buildUpon()
                            .appendQueryParameter("title", issueTitle)
                            .appendQueryParameter("body", markdownBody)
                            .build();

                    Intent intent = new Intent(Intent.ACTION_VIEW, githubUri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                } catch (Exception e) {
                    CustomToast.showWarning(getContext(), "Не удалось открыть браузер");
                }
            });
        }

        if (btnCopyReport != null) {
            btnCopyReport.setOnClickListener(v -> {
                String inputDesc = etBugDescription != null ? etBugDescription.getText().toString().trim() : "";
                String markdownBody = buildMarkdownReport(inputDesc, finalAppVersion, finalDeviceModel, finalAndroidVersion, initialLog);

                try {
                    ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Bug Report", markdownBody);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                        CustomToast.showSuccess(getContext(), "Отчет скопирован в буфер обмена");
                    }
                } catch (Exception e) {
                    CustomToast.showWarning(getContext(), "Ошибка копирования отчета");
                }
            });
        }

        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    private String buildMarkdownReport(String userDescription, String appVersion, String deviceModel, String androidVersion, String extraLog) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Описание проблемы\n");
        if (userDescription != null && !userDescription.isEmpty()) {
            sb.append(userDescription).append("\n\n");
        } else {
            sb.append("*(Описание не указано)*\n\n");
        }

        sb.append("## Системная информация\n");
        sb.append("- **Версия приложения:** v").append(appVersion).append("\n");
        sb.append("- **Устройство:** ").append(deviceModel).append("\n");
        sb.append("- **Версия Android:** ").append(androidVersion).append("\n");

        if (extraLog != null && !extraLog.trim().isEmpty()) {
            sb.append("\n## Технические логи / Контекст\n");
            sb.append("```\n").append(extraLog.trim()).append("\n```\n");
        }

        return sb.toString();
    }
}
