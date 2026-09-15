package com.example.animelib.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
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
    private TextView tvOverallPercent;
    private ProgressBar pbOverall;
    private RecyclerView rvProgressTasks;
    private MaterialButton btnOpenDownloadSheet;
    private MaterialButton btnStopDownload;

    private final List<DownloadService.TaskProgressItem> taskItems = new ArrayList<>();
    private final List<DisplayItem> displayItems = new ArrayList<>();
    private TaskProgressAdapter adapter;

    public static DownloadProgressBottomSheet newInstance() {
        return new DownloadProgressBottomSheet();
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
        tvOverallPercent = view.findViewById(R.id.tvOverallPercent);
        pbOverall = view.findViewById(R.id.pbOverall);
        rvProgressTasks = view.findViewById(R.id.rvProgressTasks);
        btnOpenDownloadSheet = view.findViewById(R.id.btnOpenDownloadSheet);
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
                dismiss();
                if (getActivity() instanceof com.example.animelib.VideoPlayerActivity) {
                    ((com.example.animelib.VideoPlayerActivity) getActivity()).showDownloadBottomSheet();
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

        btnStopDownload.setOnClickListener(v -> {
            if (DownloadService.isRunning()) {
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

    private void updateProgressData() {
        List<DownloadService.TaskProgressItem> activeItems = DownloadService.getActiveTaskItems();
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
        int activeDownloadingPercent = 0;

        for (DownloadService.TaskProgressItem item : taskItems) {
            if (item.status == DownloadService.TaskProgressItem.STATUS_COMPLETED) {
                completed++;
            } else if (item.status == DownloadService.TaskProgressItem.STATUS_DOWNLOADING) {
                activeDownloadingPercent = item.percent;
            }
        }

        boolean isRunning = DownloadService.isRunning();

        if (total == 0) {
            tvProgressHeaderSubtitle.setText("Нет активных скачиваний");
            tvOverallText.setText("Очередь пуста");
            tvOverallPercent.setText("0%");
            pbOverall.setProgress(0);
            if (btnStopDownload != null) {
                btnStopDownload.setText("Закрыть");
                btnStopDownload.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.dialog_danger_bg)));
                btnStopDownload.setTextColor(ContextCompat.getColor(requireContext(), R.color.dialog_danger_text));
            }
            return;
        }

        int overallProgress = (int) (((completed * 100.0) + activeDownloadingPercent) / total);
        if (overallProgress > 100) overallProgress = 100;

        String animeName = !taskItems.isEmpty() && taskItems.get(0).task.getAnimeTitle() != null ?
                taskItems.get(0).task.getAnimeTitle() : "";

        if (isRunning) {
            tvProgressHeaderSubtitle.setText(animeName.isEmpty() ? "Загрузка серий..." : animeName);
            tvOverallText.setText("Обработано " + completed + " из " + total + " серий");
            tvOverallPercent.setText(overallProgress + "%");
            pbOverall.setProgress(overallProgress);
            if (btnStopDownload != null) {
                btnStopDownload.setText("Остановить");
                btnStopDownload.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.dialog_danger_bg)));
                btnStopDownload.setTextColor(ContextCompat.getColor(requireContext(), R.color.dialog_danger_text));
            }
        } else {
            tvProgressHeaderSubtitle.setText("Завершено");
            tvOverallText.setText("Обработано " + completed + " из " + total + " серий");
            tvOverallPercent.setText(overallProgress + "%");
            pbOverall.setProgress(overallProgress);
            if (btnStopDownload != null) {
                btnStopDownload.setText("Закрыть");
                btnStopDownload.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.dialog_danger_bg)));
                btnStopDownload.setTextColor(ContextCompat.getColor(requireContext(), R.color.dialog_danger_text));
            }
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
                int secondaryColor = ContextCompat.getColor(context, R.color.secondary_text_color);
                int accentColor = ContextCompat.getColor(context, R.color.accent_text_color);

                int selectedBg = ContextCompat.getColor(context, R.color.chip_selected_bg);
                int unselectedBg = ContextCompat.getColor(context, R.color.chip_unselected_bg);
                int baseStroke = ContextCompat.getColor(context, R.color.chip_unselected_stroke);
                int accentStroke = ContextCompat.getColor(context, R.color.chip_selected_stroke);

                if (taskHolder.itemContainer != null) {
                    taskHolder.itemContainer.setBackground(null);
                }

                switch (item.status) {
                    case DownloadService.TaskProgressItem.STATUS_DOWNLOADING:
                        if (taskHolder.itemCard != null) {
                            taskHolder.itemCard.setCardBackgroundColor(selectedBg);
                            taskHolder.itemCard.setProgressValues(item.percent, baseStroke, accentStroke, 2.5f);
                        }
                        taskHolder.tvPercent.setText(item.percent + "%");
                        taskHolder.tvPercent.setTextColor(secondaryColor);
                        break;

                    case DownloadService.TaskProgressItem.STATUS_COMPLETED:
                        if (taskHolder.itemCard != null) {
                            taskHolder.itemCard.setCardBackgroundColor(unselectedBg);
                            taskHolder.itemCard.setProgressValues(100, baseStroke, 0xFF10B981, 2.0f);
                        }
                        taskHolder.tvPercent.setText("100%");
                        taskHolder.tvPercent.setTextColor(0xFF10B981);
                        break;

                    case DownloadService.TaskProgressItem.STATUS_ERROR:
                        if (taskHolder.itemCard != null) {
                            taskHolder.itemCard.setCardBackgroundColor(unselectedBg);
                            taskHolder.itemCard.setProgressValues(item.percent, baseStroke, 0xFFEF4444, 2.0f);
                        }
                        taskHolder.tvPercent.setText("Ошибка");
                        taskHolder.tvPercent.setTextColor(0xFFEF4444);
                        break;

                    case DownloadService.TaskProgressItem.STATUS_WAITING:
                    default:
                        if (taskHolder.itemCard != null) {
                            taskHolder.itemCard.setCardBackgroundColor(unselectedBg);
                            taskHolder.itemCard.setProgressValues(0, baseStroke, accentStroke, 1.5f);
                        }
                        taskHolder.tvPercent.setText("0%");
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
