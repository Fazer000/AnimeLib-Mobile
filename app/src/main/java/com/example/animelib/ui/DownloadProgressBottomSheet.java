package com.example.animelib.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.DownloadTask;
import com.example.animelib.services.DownloadService;
import com.example.animelib.util.FlexibleBottomSheetDialogFragment;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DownloadProgressBottomSheet extends FlexibleBottomSheetDialogFragment implements DownloadService.QueueProgressListener {

    private ImageButton btnProgressClose;
    private ImageButton btnHeaderOpenDownloadSheet;
    private ImageView ivProgressHeaderIcon;
    private TextView tvProgressHeaderTitle;
    private TextView tvProgressHeaderSubtitle;
    private TextView tvOverallText;
    private TextView tvOverallStatus;
    private TextView tvOverallPercent;
    private M3LinearProgressIndicator pbOverall;
    private RecyclerView rvProgressTasks;
    private MaterialButton btnOpenDownloadSheet;
    private MaterialButton btnPauseResumeDownload;
    private MaterialButton btnStopDownload;

    private static final String ARG_ANIME_ID = "arg_anime_id";

    private String targetAnimeId;
    private final List<DownloadService.TaskProgressItem> taskItems = new ArrayList<>();
    private final List<DisplayItem> displayItems = new ArrayList<>();
    private TaskProgressAdapter adapter;

    public static DownloadProgressBottomSheet newInstance() {
        return newInstance(null);
    }

    public static DownloadProgressBottomSheet newInstance(@Nullable String animeId) {
        DownloadProgressBottomSheet sheet = new DownloadProgressBottomSheet();
        if (animeId != null && !animeId.trim().isEmpty()) {
            Bundle args = new Bundle();
            args.putString(ARG_ANIME_ID, animeId.trim());
            sheet.setArguments(args);
        }
        return sheet;
    }

    private static class DisplayItem {
        static final int TYPE_HEADER = 0;
        static final int TYPE_TASK = 1;

        final int type;
        final String voiceover;
        final String quality;
        final DownloadService.TaskProgressItem taskItem;

        DisplayItem(String voiceover, String quality) {
            this.type = TYPE_HEADER;
            this.voiceover = voiceover;
            this.quality = quality;
            this.taskItem = null;
        }

        DisplayItem(DownloadService.TaskProgressItem taskItem) {
            this.type = TYPE_TASK;
            this.voiceover = null;
            this.quality = null;
            this.taskItem = taskItem;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        if (getArguments() != null) {
            targetAnimeId = getArguments().getString(ARG_ANIME_ID);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bs_download_progress, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getDialog() instanceof BottomSheetDialog) {
            com.example.animelib.util.FloatingBottomSheetUtils.setupFloatingStyle((BottomSheetDialog) getDialog());
        }

        btnProgressClose = view.findViewById(R.id.btnProgressClose);
        btnHeaderOpenDownloadSheet = view.findViewById(R.id.btnHeaderOpenDownloadSheet);
        ivProgressHeaderIcon = view.findViewById(R.id.ivProgressHeaderIcon);
        tvProgressHeaderTitle = view.findViewById(R.id.tvProgressHeaderTitle);
        tvProgressHeaderSubtitle = view.findViewById(R.id.tvProgressHeaderSubtitle);
        tvOverallText = view.findViewById(R.id.tvOverallText);
        tvOverallStatus = view.findViewById(R.id.tvOverallStatus);
        tvOverallPercent = view.findViewById(R.id.tvOverallPercent);
        pbOverall = view.findViewById(R.id.pbOverall);
        rvProgressTasks = view.findViewById(R.id.rvProgressTasks);
        btnOpenDownloadSheet = view.findViewById(R.id.btnOpenDownloadSheet);
        btnPauseResumeDownload = view.findViewById(R.id.btnPauseResumeDownload);
        btnStopDownload = view.findViewById(R.id.btnStopDownload);

        if (btnProgressClose != null) {
            btnProgressClose.setOnClickListener(v -> dismiss());
        }

        if (btnHeaderOpenDownloadSheet != null) {
            btnHeaderOpenDownloadSheet.setOnClickListener(v -> {
                dismiss();
                DownloadsActivity.start(requireContext());
            });
        }

        if (btnOpenDownloadSheet != null) {
            btnOpenDownloadSheet.setOnClickListener(v -> {
                Runnable previousAction = getOnBackPressedAction();
                dismiss();
                if (getActivity() instanceof com.example.animelib.VideoPlayerActivity) {
                    com.example.animelib.VideoPlayerActivity activity = (com.example.animelib.VideoPlayerActivity) getActivity();
                    activity.showDownloadBottomSheet(() -> activity.showDownloadProgressBottomSheet(previousAction));
                }
            });
        }

        GridLayoutManager gridLayoutManager = new GridLayoutManager(requireContext(), 3);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (adapter != null && adapter.getItemViewType(position) == DisplayItem.TYPE_HEADER) {
                    return 3;
                }
                return 1;
            }
        });

        rvProgressTasks.setLayoutManager(gridLayoutManager);
        rvProgressTasks.setNestedScrollingEnabled(false);
        adapter = new TaskProgressAdapter();
        rvProgressTasks.setAdapter(adapter);

        if (btnPauseResumeDownload != null) {
            btnPauseResumeDownload.setOnClickListener(v -> {
                String activeId = resolveEffectiveAnimeId();
                if (activeId != null && !DownloadService.isTitleFirstInQueue(activeId)) {
                    return;
                }
                if (DownloadService.isRunning()) {
                    DownloadService.pause(requireContext());
                    com.example.animelib.util.CustomToast.showInfo(requireContext(), "Пауза (прогресс сохранён)");
                } else if (DownloadService.hasPendingQueue()) {
                    DownloadService.resume(requireContext());
                    com.example.animelib.util.CustomToast.showInfo(requireContext(), "Возобновление скачивания...");
                }
                updateProgressData();
            });
        }

        btnStopDownload.setOnClickListener(v -> {
            if (targetAnimeId != null && !targetAnimeId.trim().isEmpty()) {
                if (DownloadService.isTitleInQueue(targetAnimeId)) {
                    DownloadService.cancelTitle(requireContext(), targetAnimeId);
                    com.example.animelib.util.CustomToast.showInfo(requireContext(), "Скачивание остановлено");
                }
                dismiss();
            } else if (DownloadService.isRunning() || DownloadService.hasPendingQueue()) {
                DownloadService.cancel(requireContext());
                com.example.animelib.util.CustomToast.showInfo(requireContext(), "Скачивание остановлено");
                dismiss();
            } else {
                dismiss();
            }
        });

        DownloadService.setQueueProgressListener(this);
        updateProgressData();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            com.example.animelib.util.FloatingBottomSheetUtils.applyFloatingToView((BottomSheetDialog) getDialog());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        DownloadService.addQueueProgressListener(this);
        updateProgressData();
    }

    @Override
    public void onPause() {
        super.onPause();
        DownloadService.removeQueueProgressListener(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        DownloadService.removeQueueProgressListener(this);
    }

    @Override
    public void onQueueUpdated() {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(this::updateProgressData);
        }
    }

    @Nullable
    private String resolveEffectiveAnimeId() {
        if (targetAnimeId != null && !targetAnimeId.trim().isEmpty()) {
            return targetAnimeId.trim();
        }
        if (!taskItems.isEmpty() && taskItems.get(0).task != null && taskItems.get(0).task.getAnimeId() != null) {
            return taskItems.get(0).task.getAnimeId().trim();
        }
        return null;
    }

    private void updateProgressData() {
        List<DownloadService.TaskProgressItem> activeItems = DownloadService.getTaskItemsForTitle(targetAnimeId);
        taskItems.clear();
        taskItems.addAll(activeItems);

        displayItems.clear();
        Map<String, List<DownloadService.TaskProgressItem>> groupedMap = new LinkedHashMap<>();

        for (DownloadService.TaskProgressItem item : activeItems) {
            String vo = (item.task.getTeamName() != null && !item.task.getTeamName().isEmpty()) ? item.task.getTeamName() : "Озвучка";
            String q = (item.task.getQuality() != null && !item.task.getQuality().isEmpty()) ? item.task.getQuality() : "";
            if (!q.isEmpty() && !q.toLowerCase().endsWith("p")) {
                q = q + "p";
            }
            String key = vo + "___" + q;
            if (!groupedMap.containsKey(key)) {
                groupedMap.put(key, new ArrayList<>());
            }
            groupedMap.get(key).add(item);
        }

        for (Map.Entry<String, List<DownloadService.TaskProgressItem>> entry : groupedMap.entrySet()) {
            String[] parts = entry.getKey().split("___", -1);
            String voiceover = parts[0];
            String quality = parts.length > 1 ? parts[1] : "";
            displayItems.add(new DisplayItem(voiceover, quality));
            for (DownloadService.TaskProgressItem item : entry.getValue()) {
                displayItems.add(new DisplayItem(item));
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        int total = taskItems.size();
        int completed = 0;
        int pendingCount = 0;
        int inProgressPercentSum = 0;

        for (DownloadService.TaskProgressItem item : taskItems) {
            if (item.status == DownloadService.TaskProgressItem.STATUS_COMPLETED) {
                completed++;
            } else if (item.status == DownloadService.TaskProgressItem.STATUS_DOWNLOADING
                    || item.status == DownloadService.TaskProgressItem.STATUS_WAITING) {
                pendingCount++;
                inProgressPercentSum += Math.max(0, Math.min(99, item.percent));
            }
        }

        String effectiveAnimeId = resolveEffectiveAnimeId();
        boolean isThisTitleFirst = effectiveAnimeId != null
                ? DownloadService.isTitleFirstInQueue(effectiveAnimeId)
                : true;
        boolean isThisTitleDownloading = effectiveAnimeId != null
                ? DownloadService.isTitleDownloading(effectiveAnimeId)
                : DownloadService.isRunning();
        boolean hasPending = pendingCount > 0;

        if (!isAdded() || getContext() == null) return;
        Context ctx = requireContext();
        int purpleColor = ContextCompat.getColor(ctx, R.color.purple_primary);
        int purpleTrackColor = ContextCompat.getColor(ctx, R.color.purple_alpha_25);
        int successColor = ContextCompat.getColor(ctx, R.color.downloaded_tag_fg);
        int accentTextColor = ContextCompat.getColor(ctx, R.color.accent_text_color);

        if (total == 0) {
            tvProgressHeaderSubtitle.setText("Нет активных скачиваний");
            tvOverallText.setText("Очередь пуста");
            if (tvOverallStatus != null) {
                tvOverallStatus.setText("Выберите серии для загрузки");
                tvOverallStatus.setTextColor(accentTextColor);
            }
            tvOverallPercent.setText("0%");
            tvOverallPercent.setTextColor(purpleColor);
            tvOverallPercent.setBackgroundResource(R.drawable.bg_m3_tonal_pill_purple);
            if (pbOverall != null) {
                pbOverall.setIndicatorColor(purpleColor);
                pbOverall.setStopDotColor(purpleColor);
                pbOverall.setTrackColor(purpleTrackColor);
                pbOverall.setProgress(0, false);
            }
            if (btnPauseResumeDownload != null) {
                btnPauseResumeDownload.setVisibility(View.GONE);
            }
            styleStopButton(false);
            return;
        }

        int overallProgress = (int) (((completed * 100.0) + inProgressPercentSum) / total);
        if (overallProgress > 100) overallProgress = 100;

        String animeName = !taskItems.isEmpty() && taskItems.get(0).task.getAnimeTitle() != null ?
                taskItems.get(0).task.getAnimeTitle() : "";

        tvOverallText.setText("Обработано " + completed + " из " + total + " серий");
        tvOverallPercent.setText(overallProgress + "%");

        boolean isAllCompleted = !hasPending && completed == total;
        if (isAllCompleted) {
            tvOverallPercent.setTextColor(successColor);
            tvOverallPercent.setBackgroundResource(R.drawable.bg_m3_tonal_pill_success);
            if (pbOverall != null) {
                pbOverall.setIndicatorColor(successColor);
                pbOverall.setStopDotColor(successColor);
                pbOverall.setTrackColor(purpleTrackColor);
                pbOverall.setProgress(overallProgress);
            }
        } else {
            tvOverallPercent.setTextColor(purpleColor);
            tvOverallPercent.setBackgroundResource(R.drawable.bg_m3_tonal_pill_purple);
            if (pbOverall != null) {
                pbOverall.setIndicatorColor(purpleColor);
                pbOverall.setStopDotColor(purpleColor);
                pbOverall.setTrackColor(purpleTrackColor);
                pbOverall.setProgress(overallProgress);
            }
        }

        if (isThisTitleDownloading) {
            tvProgressHeaderSubtitle.setText(animeName.isEmpty() ? "Загрузка серий..." : animeName);
            if (tvOverallStatus != null) {
                tvOverallStatus.setText("Загрузка в фоне");
                tvOverallStatus.setTextColor(purpleColor);
            }
            if (btnPauseResumeDownload != null) {
                btnPauseResumeDownload.setVisibility(View.VISIBLE);
                btnPauseResumeDownload.setIconResource(R.drawable.ic_pause);
                btnPauseResumeDownload.setContentDescription("Пауза");
            }
            styleStopButton(true);
        } else if (hasPending && isThisTitleFirst) {
            tvProgressHeaderSubtitle.setText(animeName.isEmpty() ? "На паузе" : animeName);
            if (tvOverallStatus != null) {
                tvOverallStatus.setText("На паузе");
                tvOverallStatus.setTextColor(accentTextColor);
            }
            if (btnPauseResumeDownload != null) {
                btnPauseResumeDownload.setVisibility(View.VISIBLE);
                btnPauseResumeDownload.setIconResource(R.drawable.ic_play);
                btnPauseResumeDownload.setContentDescription("Продолжить");
            }
            styleStopButton(true);
        } else if (hasPending) {
            tvProgressHeaderSubtitle.setText(animeName.isEmpty() ? "В ожидании" : animeName);
            if (tvOverallStatus != null) {
                tvOverallStatus.setText("Ожидание очереди");
                tvOverallStatus.setTextColor(accentTextColor);
            }
            if (btnPauseResumeDownload != null) {
                btnPauseResumeDownload.setVisibility(View.GONE);
            }
            styleStopButton(true);
        } else {
            tvProgressHeaderSubtitle.setText(animeName.isEmpty() ? "Завершено" : animeName);
            if (tvOverallStatus != null) {
                tvOverallStatus.setText(isAllCompleted ? "Загрузка завершена" : "Завершено с ошибками");
                tvOverallStatus.setTextColor(isAllCompleted ? successColor : ContextCompat.getColor(ctx, R.color.stop_btn_text));
            }
            if (btnPauseResumeDownload != null) {
                btnPauseResumeDownload.setVisibility(View.GONE);
            }
            styleStopButton(false);
        }
    }

    private void styleStopButton(boolean isActiveDanger) {
        if (btnStopDownload == null || !isAdded() || getContext() == null) return;
        Context ctx = requireContext();
        if (isActiveDanger) {
            int dangerText = ContextCompat.getColor(ctx, R.color.stop_btn_text);
            btnStopDownload.setText("Остановить");
            btnStopDownload.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.stop_btn_bg)));
            btnStopDownload.setRippleColor(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.stop_btn_stroke)));
            btnStopDownload.setTextColor(dangerText);
            btnStopDownload.setIconResource(R.drawable.ic_close);
            btnStopDownload.setIconTint(ColorStateList.valueOf(dangerText));
        } else {
            int neutralText = ContextCompat.getColor(ctx, R.color.dialog_btn_neutral_text);
            btnStopDownload.setText("Закрыть");
            btnStopDownload.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.dialog_btn_neutral_bg)));
            btnStopDownload.setRippleColor(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.dialog_ripple_color)));
            btnStopDownload.setTextColor(neutralText);
            btnStopDownload.setIcon(null);
        }
    }

    private class TaskProgressAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return displayItems.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == DisplayItem.TYPE_HEADER) {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_progress_header, parent, false);
                return new HeaderViewHolder(view);
            } else {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_progress_task, parent, false);
                return new TaskViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            DisplayItem displayItem = displayItems.get(position);

            if (holder instanceof HeaderViewHolder) {
                HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
                headerHolder.tvHeaderVoiceover.setText(displayItem.voiceover);
                if (displayItem.quality != null && !displayItem.quality.isEmpty()) {
                    headerHolder.tvHeaderQuality.setText(displayItem.quality);
                    headerHolder.tvHeaderQuality.setVisibility(View.VISIBLE);
                } else {
                    headerHolder.tvHeaderQuality.setVisibility(View.GONE);
                }
            } else if (holder instanceof TaskViewHolder) {
                TaskViewHolder taskHolder = (TaskViewHolder) holder;
                DownloadService.TaskProgressItem item = displayItem.taskItem;
                DownloadTask task = item.task;

                String rawEp = task.getEpisodeNumber();
                String epName = task.getEpisodeName();
                StringBuilder sb = new StringBuilder();
                if (rawEp != null && !rawEp.trim().isEmpty()) {
                    if (rawEp.toLowerCase().contains("серия")) {
                        sb.append(rawEp.trim());
                    } else {
                        sb.append("Серия ").append(rawEp.trim());
                    }
                } else {
                    sb.append("Серия 1");
                }

                taskHolder.tvTaskTitle.setText(sb.toString());
                taskHolder.tvTaskTitle.setSelected(true);

                if (taskHolder.tvEpisodeName != null) {
                    taskHolder.tvEpisodeName.setVisibility(View.GONE);
                }

                Context context = taskHolder.itemView.getContext();
                int primaryTextColor = ContextCompat.getColor(context, R.color.primary_text_color);
                int accentColor = ContextCompat.getColor(context, R.color.accent_text_color);
                int purpleColor = ContextCompat.getColor(context, R.color.purple_primary);
                int purpleTrackColor = ContextCompat.getColor(context, R.color.purple_alpha_25);

                int activeBg = ContextCompat.getColor(context, R.color.popup_item_selected_bg);
                int idleBg = ContextCompat.getColor(context, R.color.dialog_card_bg);
                int neutralStroke = ContextCompat.getColor(context, R.color.chip_unselected_stroke);
                int successBg = ContextCompat.getColor(context, R.color.downloaded_tag_bg);
                int successFg = ContextCompat.getColor(context, R.color.downloaded_tag_fg);
                int successStroke = ContextCompat.getColor(context, R.color.downloaded_tag_stroke);
                int errorBg = ContextCompat.getColor(context, R.color.stop_btn_bg);
                int errorFg = ContextCompat.getColor(context, R.color.stop_btn_text);
                int errorStroke = ContextCompat.getColor(context, R.color.stop_btn_stroke);

                if (taskHolder.itemContainer != null) {
                    taskHolder.itemContainer.setBackground(null);
                }

                switch (item.status) {
                    case DownloadService.TaskProgressItem.STATUS_DOWNLOADING:
                        if (taskHolder.itemCard != null) {
                            taskHolder.itemCard.setCardBackgroundColor(activeBg);
                            taskHolder.itemCard.setProgressValues(item.percent, purpleTrackColor, purpleColor, 2.2f);
                        }
                        taskHolder.tvTaskTitle.setTextColor(purpleColor);
                        taskHolder.tvPercent.setText(item.percent + "%");
                        taskHolder.tvPercent.setTextColor(purpleColor);
                        break;

                    case DownloadService.TaskProgressItem.STATUS_COMPLETED:
                        if (taskHolder.itemCard != null) {
                            taskHolder.itemCard.setCardBackgroundColor(successBg);
                            taskHolder.itemCard.setProgressValues(100, 0, successStroke, 0.5f);
                        }
                        taskHolder.tvTaskTitle.setTextColor(successFg);
                        taskHolder.tvPercent.setText("Готово");
                        taskHolder.tvPercent.setTextColor(successFg);
                        break;

                    case DownloadService.TaskProgressItem.STATUS_ERROR:
                        if (taskHolder.itemCard != null) {
                            taskHolder.itemCard.setCardBackgroundColor(errorBg);
                            taskHolder.itemCard.setProgressValues(100, 0, errorStroke, 0.5f);
                        }
                        taskHolder.tvTaskTitle.setTextColor(errorFg);
                        taskHolder.tvPercent.setText("Ошибка");
                        taskHolder.tvPercent.setTextColor(errorFg);
                        break;

                    case DownloadService.TaskProgressItem.STATUS_WAITING:
                    default:
                        if (taskHolder.itemCard != null) {
                            taskHolder.itemCard.setCardBackgroundColor(idleBg);
                            taskHolder.itemCard.setProgressValues(0, neutralStroke, 0, 0.5f);
                        }
                        taskHolder.tvTaskTitle.setTextColor(primaryTextColor);
                        taskHolder.tvPercent.setText("Ожидание");
                        taskHolder.tvPercent.setTextColor(accentColor);
                        break;
                }
            }
        }

        @Override
        public int getItemCount() {
            return displayItems.size();
        }

        class HeaderViewHolder extends RecyclerView.ViewHolder {
            TextView tvHeaderVoiceover;
            TextView tvHeaderQuality;

            HeaderViewHolder(@NonNull View itemView) {
                super(itemView);
                tvHeaderVoiceover = itemView.findViewById(R.id.tvHeaderVoiceover);
                tvHeaderQuality = itemView.findViewById(R.id.tvHeaderQuality);
            }
        }

        class TaskViewHolder extends RecyclerView.ViewHolder {
            com.example.animelib.ui.BorderProgressCardView itemCard;
            View itemContainer;
            TextView tvTaskTitle;
            TextView tvEpisodeName;
            TextView tvPercent;

            TaskViewHolder(@NonNull View itemView) {
                super(itemView);
                if (itemView instanceof com.example.animelib.ui.BorderProgressCardView) {
                    itemCard = (com.example.animelib.ui.BorderProgressCardView) itemView;
                } else {
                    itemCard = itemView.findViewById(R.id.itemCard);
                }
                itemContainer = itemView.findViewById(R.id.itemContainer);
                tvTaskTitle = itemView.findViewById(R.id.tvTaskTitle);
                tvEpisodeName = itemView.findViewById(R.id.tvEpisodeName);
                tvPercent = itemView.findViewById(R.id.tvPercent);

                if (tvTaskTitle != null) {
                    tvTaskTitle.setSelected(true);
                }
            }
        }
    }
}
