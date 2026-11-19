package com.example.animelib.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.example.animelib.R;
import com.example.animelib.VideoPlayerActivity;
import com.example.animelib.api.ApiClientCore;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.DownloadedAnimeEntity;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.services.DownloadService;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.ImageLoader;
import com.example.animelib.util.ThemeUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

public class DownloadsActivity extends AppCompatActivity implements DownloadService.QueueProgressListener {

    private static final String TAG = "DownloadsActivity";

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "downloads-ui-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile ApiService apiService;
    private DatabaseManager databaseManager;

    // Queue Section Views
    private LinearLayout containerQueueSection;
    private RecyclerView rvDownloadQueue;
    private QueueAdapter queueAdapter;
    private ItemTouchHelper queueItemTouchHelper;
    private final List<DownloadService.TitleQueueInfo> queueList = new ArrayList<>();
    private final Map<String, Long> queueStableIds = new ConcurrentHashMap<>();
    private long nextQueueStableId = 1L;
    private boolean isDraggingQueue = false;

    // Search Section Views
    private LinearLayout layoutSearchContainer;
    private EditText etSearchDownloads;
    private ImageButton btnClearSearch;
    private String currentSearchQuery = "";

    // Downloaded Section Views
    private LinearLayout containerDownloadedSection;
    private TextView tvDownloadedSectionHeader;
    private RecyclerView rvDownloadedAnime;
    private LinearLayout layoutEmpty;
    private MaterialButton btnBackToApp;

    private AnimeAdapter animeAdapter;
    private final List<DownloadedAnimeEntity> animeList = new ArrayList<>();
    private final List<DownloadedAnimeEntity> displayedAnimeList = new ArrayList<>();
    private final Map<String, Long> animeSizesMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> animeEpisodeCountsMap = new ConcurrentHashMap<>();

    private String currentSortType = "date"; // "date", "name", "size"

    public static void start(Context context) {
        Intent intent = new Intent(context, DownloadsActivity.class);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            ThemeUtils.applyThemeToActivity(this, ThemeUtils.getSavedThemePreference(this));
        } catch (Exception ignored) {}
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        if (getWindow() != null) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            int headerColor = androidx.core.content.ContextCompat.getColor(this, R.color.toolbar_header_bg);
            getWindow().setStatusBarColor(headerColor);
            boolean isDark = ThemeUtils.getSavedThemePreference(this) == ThemeUtils.THEME_DARK ||
                    (ThemeUtils.getSavedThemePreference(this) == ThemeUtils.THEME_SYSTEM &&
                            (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES);
            androidx.core.view.WindowInsetsControllerCompat insetsController =
                    androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (insetsController != null) {
                insetsController.setAppearanceLightStatusBars(!isDark);
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4201);
            }
        }

        databaseManager = DatabaseManager.getInstance(this);

        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        // Search controls
        layoutSearchContainer = findViewById(R.id.layoutSearchContainer);
        etSearchDownloads = findViewById(R.id.etSearchDownloads);
        btnClearSearch = findViewById(R.id.btnClearSearch);

        View btnSearch = findViewById(R.id.btnSearchDownloads);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> toggleSearch());
        }

        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                if (etSearchDownloads != null && etSearchDownloads.getText().length() > 0) {
                    etSearchDownloads.setText("");
                } else {
                    hideSearch();
                }
            });
        }

        if (etSearchDownloads != null) {
            etSearchDownloads.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    currentSearchQuery = s != null ? s.toString().trim() : "";
                    applyFilterAndSort();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        // Sort controls
        View btnSort = findViewById(R.id.btnSortDownloads);
        if (btnSort != null) {
            btnSort.setOnClickListener(v -> showSortOptionsPopup(btnSort));
        }

        // Initialize Queue section views
        containerQueueSection = findViewById(R.id.containerQueueSection);
        rvDownloadQueue = findViewById(R.id.rvDownloadQueue);
        queueAdapter = new QueueAdapter();
        if (rvDownloadQueue != null) {
            rvDownloadQueue.setLayoutManager(new LinearLayoutManager(this));
            rvDownloadQueue.setAdapter(queueAdapter);
            if (rvDownloadQueue.getItemAnimator() instanceof SimpleItemAnimator) {
                ((SimpleItemAnimator) rvDownloadQueue.getItemAnimator()).setSupportsChangeAnimations(false);
            }
            queueItemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                @Override
                public boolean isLongPressDragEnabled() {
                    return false;
                }

                @Override
                public boolean isItemViewSwipeEnabled() {
                    return false;
                }

                @Override
                public boolean onMove(@NonNull RecyclerView recyclerView,
                                      @NonNull RecyclerView.ViewHolder viewHolder,
                                      @NonNull RecyclerView.ViewHolder target) {
                    int fromPos = viewHolder.getBindingAdapterPosition();
                    int toPos = target.getBindingAdapterPosition();
                    if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION
                            || fromPos == toPos) {
                        return false;
                    }
                    if (fromPos < toPos) {
                        for (int i = fromPos; i < toPos; i++) {
                            Collections.swap(queueList, i, i + 1);
                        }
                    } else {
                        for (int i = fromPos; i > toPos; i--) {
                            Collections.swap(queueList, i, i - 1);
                        }
                    }
                    queueAdapter.notifyItemMoved(fromPos, toPos);
                    return true;
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                    // Swipe disabled
                }

                @Override
                public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                    super.onSelectedChanged(viewHolder, actionState);
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        isDraggingQueue = true;
                        if (rvDownloadQueue != null) {
                            rvDownloadQueue.requestDisallowInterceptTouchEvent(true);
                        }
                        if (viewHolder != null) {
                            viewHolder.itemView.animate()
                                    .scaleX(1.02f)
                                    .scaleY(1.02f)
                                    .alpha(0.94f)
                                    .setDuration(120)
                                    .start();
                        }
                    }
                }

                @Override
                public void clearView(@NonNull RecyclerView recyclerView,
                                      @NonNull RecyclerView.ViewHolder viewHolder) {
                    super.clearView(recyclerView, viewHolder);
                    viewHolder.itemView.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(120)
                            .start();
                    isDraggingQueue = false;
                    if (rvDownloadQueue != null) {
                        rvDownloadQueue.requestDisallowInterceptTouchEvent(false);
                    }
                    List<String> orderedIds = new ArrayList<>();
                    for (DownloadService.TitleQueueInfo info : queueList) {
                        if (info != null && info.animeId != null) {
                            orderedIds.add(info.animeId);
                        }
                    }
                    if (!orderedIds.isEmpty()) {
                        DownloadService.reorderTitles(DownloadsActivity.this, orderedIds);
                    }
                    updateQueueSection();
                }
            });
            queueItemTouchHelper.attachToRecyclerView(rvDownloadQueue);
        }

        // Initialize Downloaded section views
        containerDownloadedSection = findViewById(R.id.containerDownloadedSection);
        tvDownloadedSectionHeader = findViewById(R.id.tvDownloadedSectionHeader);
        rvDownloadedAnime = findViewById(R.id.rvDownloadedAnime);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        btnBackToApp = findViewById(R.id.btnBackToApp);

        if (btnBackToApp != null) {
            btnBackToApp.setOnClickListener(v -> finish());
        }

        animeAdapter = new AnimeAdapter();
        if (rvDownloadedAnime != null) {
            rvDownloadedAnime.setLayoutManager(new LinearLayoutManager(this));
            rvDownloadedAnime.setAdapter(animeAdapter);
        }

        databaseManager.getAllDownloadedAnimeLiveData().observe(this, list -> {
            synchronized (animeList) {
                animeList.clear();
                if (list != null) animeList.addAll(list);
            }

            // Ensure posters are cached locally & calculate sizes/episodes count
            if (!backgroundExecutor.isShutdown()) {
                backgroundExecutor.execute(() -> {
                    if (list != null) {
                        for (DownloadedAnimeEntity anime : list) {
                            if (isFinishing() || isDestroyed()) return;
                            ensureLocalPoster(anime);
                            List<DownloadedEpisodeEntity> episodes = databaseManager.getEpisodesForAnimeSync(anime.getAnimeId());
                            long totalBytes = 0;
                            int epCount = 0;
                            int subsCount = 0;
                            int segmentsCount = 0;
                            if (episodes != null) {
                                epCount = episodes.size();
                                for (DownloadedEpisodeEntity ep : episodes) {
                                    if (ep != null) {
                                        long actualSize = com.example.animelib.util.FileUtils.getEpisodeTotalDiskSize(ep.getLocalFilePath());
                                        totalBytes += actualSize > 0 ? actualSize : ep.getFileSize();
                                        if (com.example.animelib.models.OfflineEpisodeMetadata.hasSubtitles(ep)) {
                                            subsCount++;
                                        }
                                        if (com.example.animelib.models.OfflineEpisodeMetadata.hasTimecodes(ep)) {
                                            segmentsCount++;
                                        }
                                    }
                                }
                            }
                            animeSizesMap.put(anime.getAnimeId(), totalBytes);
                            animeEpisodeCountsMap.put(anime.getAnimeId(), epCount);
                            animeMetaSummaryMap.put(anime.getAnimeId(), buildOfflineCategorySummary(anime, subsCount, segmentsCount));
                        }
                    }
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            applyFilterAndSort();
                        }
                    });
                });
            }
        });
    }

    private void toggleSearch() {
        if (layoutSearchContainer == null) return;
        if (layoutSearchContainer.getVisibility() == View.VISIBLE) {
            hideSearch();
        } else {
            layoutSearchContainer.setVisibility(View.VISIBLE);
            if (etSearchDownloads != null) {
                etSearchDownloads.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etSearchDownloads, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        }
    }

    private void hideSearch() {
        if (layoutSearchContainer != null) {
            layoutSearchContainer.setVisibility(View.GONE);
        }
        if (etSearchDownloads != null) {
            etSearchDownloads.setText("");
        }
        currentSearchQuery = "";
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
        applyFilterAndSort();
    }

    private void showSortOptionsPopup(View anchorView) {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_downloads_sort, null);

        PopupWindow popupWindow = new PopupWindow(
                popupView,
                dpToPx(170),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );

        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        popupWindow.setElevation(dpToPx(12));
        popupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        TextView itemDate = popupView.findViewById(R.id.item_sort_date);
        TextView itemName = popupView.findViewById(R.id.item_sort_name);
        TextView itemSize = popupView.findViewById(R.id.item_sort_size);

        resetSortItemStyle(itemDate);
        resetSortItemStyle(itemName);
        resetSortItemStyle(itemSize);

        if ("name".equals(currentSortType)) {
            setSortItemSelectedStyle(itemName);
        } else if ("size".equals(currentSortType)) {
            setSortItemSelectedStyle(itemSize);
        } else {
            setSortItemSelectedStyle(itemDate);
        }

        itemDate.setOnClickListener(v -> {
            currentSortType = "date";
            applyFilterAndSort();
            popupWindow.dismiss();
        });

        itemName.setOnClickListener(v -> {
            currentSortType = "name";
            applyFilterAndSort();
            popupWindow.dismiss();
        });

        itemSize.setOnClickListener(v -> {
            currentSortType = "size";
            applyFilterAndSort();
            popupWindow.dismiss();
        });

        popupWindow.showAsDropDown(anchorView, 0, dpToPx(4));
    }

    private void resetSortItemStyle(TextView tv) {
        if (tv != null) {
            tv.setBackground(null);
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.accent_text_color));
        }
    }

    private void setSortItemSelectedStyle(TextView tv) {
        if (tv != null) {
            tv.setBackgroundResource(R.drawable.bg_sort_popup_item_selected);
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.sort_popup_item_selected_text));
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void applyFilterAndSort() {
        synchronized (animeList) {
            displayedAnimeList.clear();
            if (currentSearchQuery.isEmpty()) {
                displayedAnimeList.addAll(animeList);
            } else {
                String q = currentSearchQuery.toLowerCase();
                for (DownloadedAnimeEntity a : animeList) {
                    if (a != null && a.getTitle() != null && a.getTitle().toLowerCase().contains(q)) {
                        displayedAnimeList.add(a);
                    }
                }
            }

            // Apply Sorting
            if ("name".equals(currentSortType)) {
                Collections.sort(displayedAnimeList, (a1, a2) -> {
                    String t1 = a1.getTitle() != null ? a1.getTitle() : "";
                    String t2 = a2.getTitle() != null ? a2.getTitle() : "";
                    return t1.compareToIgnoreCase(t2);
                });
            } else if ("size".equals(currentSortType)) {
                Collections.sort(displayedAnimeList, (a1, a2) -> {
                    long s1 = animeSizesMap.containsKey(a1.getAnimeId()) ? animeSizesMap.get(a1.getAnimeId()) : 0L;
                    long s2 = animeSizesMap.containsKey(a2.getAnimeId()) ? animeSizesMap.get(a2.getAnimeId()) : 0L;
                    return Long.compare(s2, s1); // descending
                });
            } else {
                // "date" - newest saved first
                Collections.sort(displayedAnimeList, (a1, a2) -> Long.compare(a2.getSavedAt(), a1.getSavedAt()));
            }
        }

        if (animeAdapter != null) {
            animeAdapter.notifyDataSetChanged();
        }

        updateQueueSection();
        updateEmptyState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            int currentTheme = ThemeUtils.getSavedThemePreference(this);
            ThemeUtils.applyThemeToActivity(this, currentTheme);
            if (getWindow() != null) {
                boolean isDark = currentTheme == ThemeUtils.THEME_DARK ||
                        (currentTheme == ThemeUtils.THEME_SYSTEM &&
                                (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES);
                androidx.core.view.WindowInsetsControllerCompat insetsController =
                        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                if (insetsController != null) {
                    insetsController.setAppearanceLightStatusBars(!isDark);
                }
            }
        } catch (Exception ignored) {}
        DownloadService.addQueueProgressListener(this);
        updateQueueSection();
    }

    @Override
    protected void onPause() {
        super.onPause();
        DownloadService.removeQueueProgressListener(this);
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        if (apiService != null) {
            apiService.shutdown();
            apiService = null;
        }
        super.onDestroy();
    }

    private ApiService getOrCreateApiService() {
        ApiService local = apiService;
        if (local == null) {
            synchronized (this) {
                local = apiService;
                if (local == null) {
                    local = new ApiService(getApplicationContext());
                    apiService = local;
                }
            }
        }
        return local;
    }

    @Override
    public void onQueueUpdated() {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                updateQueueSection();
            }
        });
    }

    private void updateEmptyState() {
        boolean hasDownloaded;
        synchronized (animeList) {
            hasDownloaded = !displayedAnimeList.isEmpty();
        }
        boolean hasQueue = !queueList.isEmpty();

        if (containerDownloadedSection != null) {
            containerDownloadedSection.setVisibility(hasDownloaded ? View.VISIBLE : View.GONE);
        }
        if (layoutEmpty != null) {
            layoutEmpty.setVisibility(!hasDownloaded && !hasQueue ? View.VISIBLE : View.GONE);
        }
    }

    private void updateQueueSection() {
        if (isDraggingQueue) {
            return;
        }

        List<DownloadService.TitleQueueInfo> infos = DownloadService.getTitleQueueInfos();
        queueList.clear();
        if (infos != null) {
            queueList.addAll(infos);
        }

        boolean hasQueue = !queueList.isEmpty();

        if (tvDownloadedSectionHeader != null) {
            tvDownloadedSectionHeader.setVisibility(hasQueue ? View.VISIBLE : View.GONE);
        }

        if (containerQueueSection != null) {
            containerQueueSection.setVisibility(hasQueue ? View.VISIBLE : View.GONE);
        }

        if (queueAdapter != null) {
            queueAdapter.notifyDataSetChanged();
        }

        updateEmptyState();
    }

    private class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.QueueViewHolder> {

        QueueAdapter() {
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= queueList.size()) return RecyclerView.NO_ID;
            DownloadService.TitleQueueInfo info = queueList.get(position);
            if (info == null || info.animeId == null) return RecyclerView.NO_ID;
            Long stableId = queueStableIds.get(info.animeId);
            if (stableId == null) {
                stableId = nextQueueStableId++;
                queueStableIds.put(info.animeId, stableId);
            }
            return stableId;
        }

        @NonNull
        @Override
        public QueueViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_queue, parent, false);
            return new QueueViewHolder(view);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public void onBindViewHolder(@NonNull QueueViewHolder holder, int position) {
            if (position < 0 || position >= queueList.size()) return;
            DownloadService.TitleQueueInfo info = queueList.get(position);
            if (info == null) return;

            boolean animateProgress = info.animeId != null && info.animeId.equals(holder.lastBoundAnimeId);
            holder.lastBoundAnimeId = info.animeId;

            holder.tvQueueAnimeTitle.setText(info.animeTitle != null ? info.animeTitle : "Загрузка");

            if (info.posterUrl != null && !info.posterUrl.trim().isEmpty()) {
                ImageLoader.getInstance().loadInto(holder.ivQueuePoster, info.posterUrl, R.drawable.skeleton_placeholder);
            } else {
                holder.ivQueuePoster.setImageResource(R.drawable.skeleton_placeholder);
            }

            holder.tvQueueProgressText.setText(info.overallPercent + "%  " + info.completedCount + " из " + info.totalCount);
            holder.tvQueueBadgeProgress.setText(info.completedCount + " / " + info.totalCount);

            int primaryPurple = ContextCompat.getColor(DownloadsActivity.this, R.color.purple_primary);
            int purpleTrack = ContextCompat.getColor(DownloadsActivity.this, R.color.purple_alpha_25);
            int secondaryText = ContextCompat.getColor(DownloadsActivity.this, R.color.secondary_text_color);
            int mutedTrack = ColorUtils.setAlphaComponent(secondaryText, 64);
            int accentText = ContextCompat.getColor(DownloadsActivity.this, R.color.accent_text_color);
            int unselectedStroke = ContextCompat.getColor(DownloadsActivity.this, R.color.chip_unselected_stroke);

            if (info.isFirstInQueue) {
                if (info.isDownloading) {
                    holder.tvQueueStatus.setText("Загрузка");
                    holder.tvQueueStatus.setTextColor(primaryPurple);
                    holder.cardQueueItem.setStrokeColor(primaryPurple);
                    holder.cardQueueItem.setStrokeWidth(dpToPx(1));
                    holder.pbQueueProgress.setIndicatorColor(primaryPurple);
                    holder.pbQueueProgress.setTrackColor(purpleTrack);
                    holder.pbQueueProgress.setStopDotColor(primaryPurple);
                } else {
                    holder.tvQueueStatus.setText("Пауза");
                    holder.tvQueueStatus.setTextColor(accentText);
                    holder.cardQueueItem.setStrokeColor(unselectedStroke);
                    holder.cardQueueItem.setStrokeWidth(Math.max(1, dpToPx(1) / 2));
                    holder.pbQueueProgress.setIndicatorColor(secondaryText);
                    holder.pbQueueProgress.setTrackColor(mutedTrack);
                    holder.pbQueueProgress.setStopDotColor(secondaryText);
                }

                holder.btnQueuePause.setVisibility(View.VISIBLE);
                holder.tvQueuePauseText.setText(info.isDownloading ? "Пауза" : "Продолжить");
                holder.ivQueuePauseIcon.setImageResource(info.isDownloading ? R.drawable.ic_pause : R.drawable.ic_play);
                holder.btnQueuePause.setContentDescription(info.isDownloading ? "Поставить загрузку на паузу" : "Продолжить загрузку");
                holder.btnQueuePause.setOnClickListener(v -> {
                    if (DownloadService.isRunning()) {
                        DownloadService.pause(DownloadsActivity.this);
                        CustomToast.showInfo(DownloadsActivity.this, "Пауза (прогресс сохранён)");
                    } else if (DownloadService.hasPendingQueue()) {
                        DownloadService.resume(DownloadsActivity.this);
                        CustomToast.showInfo(DownloadsActivity.this, "Возобновление загрузки...");
                    }
                    updateQueueSection();
                });
            } else {
                holder.tvQueueStatus.setText("В ожидании");
                holder.tvQueueStatus.setTextColor(accentText);
                holder.cardQueueItem.setStrokeColor(unselectedStroke);
                holder.cardQueueItem.setStrokeWidth(Math.max(1, dpToPx(1) / 2));
                holder.pbQueueProgress.setIndicatorColor(secondaryText);
                holder.pbQueueProgress.setTrackColor(mutedTrack);
                holder.pbQueueProgress.setStopDotColor(secondaryText);
                holder.btnQueuePause.setVisibility(View.GONE);
                holder.btnQueuePause.setOnClickListener(null);
            }

            holder.pbQueueProgress.setProgress(info.overallPercent, animateProgress);

            holder.btnQueueCancel.setOnClickListener(v -> {
                DownloadService.cancelTitle(DownloadsActivity.this, info.animeId);
                CustomToast.showInfo(DownloadsActivity.this, "Загрузка отменена");
                updateQueueSection();
            });

            holder.ivQueueDragHandle.setAlpha(queueList.size() > 1 ? 1.0f : 0.45f);
            holder.ivQueueDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (rvDownloadQueue != null) {
                        rvDownloadQueue.requestDisallowInterceptTouchEvent(true);
                    }
                    if (queueItemTouchHelper != null) {
                        queueItemTouchHelper.startDrag(holder);
                    }
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return queueList.size();
        }

        class QueueViewHolder extends RecyclerView.ViewHolder {
            final MaterialCardView cardQueueItem;
            final ImageView ivQueuePoster;
            final TextView tvQueueAnimeTitle;
            final TextView tvQueueStatus;
            final TextView tvQueueProgressText;
            final M3LinearProgressIndicator pbQueueProgress;
            final ImageButton ivQueueDragHandle;
            final TextView tvQueueBadgeProgress;
            final View btnQueuePause;
            final ImageView ivQueuePauseIcon;
            final TextView tvQueuePauseText;
            final View btnQueueCancel;
            String lastBoundAnimeId;

            QueueViewHolder(@NonNull View itemView) {
                super(itemView);
                cardQueueItem = itemView.findViewById(R.id.cardQueueItem);
                ivQueuePoster = itemView.findViewById(R.id.ivQueuePoster);
                tvQueueAnimeTitle = itemView.findViewById(R.id.tvQueueAnimeTitle);
                tvQueueStatus = itemView.findViewById(R.id.tvQueueStatus);
                tvQueueProgressText = itemView.findViewById(R.id.tvQueueProgressText);
                pbQueueProgress = itemView.findViewById(R.id.pbQueueProgress);
                ivQueueDragHandle = itemView.findViewById(R.id.ivQueueDragHandle);
                tvQueueBadgeProgress = itemView.findViewById(R.id.tvQueueBadgeProgress);
                btnQueuePause = itemView.findViewById(R.id.btnQueuePause);
                ivQueuePauseIcon = itemView.findViewById(R.id.ivQueuePauseIcon);
                tvQueuePauseText = itemView.findViewById(R.id.tvQueuePauseText);
                btnQueueCancel = itemView.findViewById(R.id.btnQueueCancel);
            }
        }
    }

    private void ensureLocalPoster(DownloadedAnimeEntity anime) {
        if (anime == null) return;
        String animeId = anime.getAnimeId();
        if (animeId == null || animeId.isEmpty()) return;

        File internalDir = new File(getFilesDir(), "cached_posters");
        if (!internalDir.exists()) internalDir.mkdirs();
        File internalPosterFile = new File(internalDir, animeId + ".jpg");

        File externalDir = getExternalFilesDir("cached_posters");
        if (externalDir != null && !externalDir.exists()) externalDir.mkdirs();
        File externalPosterFile = externalDir != null ? new File(externalDir, animeId + ".jpg") : null;

        if (internalPosterFile.exists() && internalPosterFile.length() > 0) {
            String path = internalPosterFile.getAbsolutePath();
            if (!path.equals(anime.getPosterUrl())) {
                anime.setPosterUrl(path);
                if (!backgroundExecutor.isShutdown()) {
                    backgroundExecutor.execute(() -> databaseManager.saveDownloadedAnime(anime));
                }
            }
            return;
        }

        if (externalPosterFile != null && externalPosterFile.exists() && externalPosterFile.length() > 0) {
            String path = externalPosterFile.getAbsolutePath();
            if (!path.equals(anime.getPosterUrl())) {
                anime.setPosterUrl(path);
                if (!backgroundExecutor.isShutdown()) {
                    backgroundExecutor.execute(() -> databaseManager.saveDownloadedAnime(anime));
                }
            }
            return;
        }

        String posterUrl = anime.getPosterUrl();
        boolean needsTitle = anime.getTitle() == null || anime.getTitle().equals("Аниме") ||
                anime.getTitle().equals("Загрузка") || anime.getTitle().startsWith("Аниме #");

        if (backgroundExecutor.isShutdown()) return;
        backgroundExecutor.execute(() -> {
            try {
                if (isFinishing() || isDestroyed()) return;
                String url = posterUrl;
                if (needsTitle || url == null || url.trim().isEmpty() || !url.startsWith("http")) {
                    final String[] fetchedCover = new String[1];
                    final String[] fetchedTitle = new String[1];
                    CountDownLatch latch = new CountDownLatch(1);
                    getOrCreateApiService().fetchAnimeInfo(anime.getAnimeId(), new ApiService.AnimeInfoCallback() {
                        @Override
                        public void onAnimeInfoReceived(AnimeInfoResponse response) {
                            if (response != null && response.getData() != null) {
                                if (response.getData().getCover() != null) {
                                    fetchedCover[0] = response.getData().getCover().getDefaultUrl();
                                }
                                if (response.getData().getRus_name() != null && !response.getData().getRus_name().trim().isEmpty()) {
                                    fetchedTitle[0] = response.getData().getRus_name().trim();
                                } else if (response.getData().getName() != null && !response.getData().getName().trim().isEmpty()) {
                                    fetchedTitle[0] = response.getData().getName().trim();
                                }
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error) {
                            latch.countDown();
                        }
                    });
                    latch.await(5, TimeUnit.SECONDS);
                    if (fetchedCover[0] != null && !fetchedCover[0].trim().isEmpty()) {
                        url = fetchedCover[0];
                    }
                    if (fetchedTitle[0] != null && !fetchedTitle[0].trim().isEmpty()) {
                        anime.setTitle(fetchedTitle[0]);
                        databaseManager.saveDownloadedAnime(anime);
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed() && animeAdapter != null) {
                                animeAdapter.notifyDataSetChanged();
                            }
                        });
                    }
                }

                if (url != null && !url.trim().isEmpty()) {
                    if (url.startsWith("//")) url = "https:" + url;
                    else if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://animelib.me" + (url.startsWith("/") ? "" : "/") + url;

                    Request request = new Request.Builder().url(url).build();
                    try (Response response = ApiClientCore.getSharedHttpClient().newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            byte[] bytes = response.body().bytes();
                            if (bytes != null && bytes.length > 0) {
                                try (FileOutputStream fos = new FileOutputStream(internalPosterFile)) {
                                    fos.write(bytes);
                                    fos.flush();
                                }
                                if (externalPosterFile != null) {
                                    try (FileOutputStream fos = new FileOutputStream(externalPosterFile)) {
                                        fos.write(bytes);
                                        fos.flush();
                                    } catch (Exception ignored) {}
                                }
                                anime.setPosterUrl(internalPosterFile.getAbsolutePath());
                                databaseManager.saveDownloadedAnime(anime);
                                runOnUiThread(() -> {
                                    if (!isFinishing() && !isDestroyed() && animeAdapter != null) {
                                        animeAdapter.notifyDataSetChanged();
                                    }
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to cache poster offline: " + e.getMessage());
            }
        });
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) {
            return String.format("%.2f GB", mb / 1024.0);
        } else {
            return String.format("%.1f MB", mb);
        }
    }

    private final java.util.Map<String, String> animeMetaSummaryMap = new java.util.concurrent.ConcurrentHashMap<>();

    private String buildOfflineCategorySummary(DownloadedAnimeEntity anime, int subsCount, int segmentsCount) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (anime != null && anime.getAnimeType() != null && !anime.getAnimeType().trim().isEmpty()) {
            parts.add(anime.getAnimeType().trim());
        } else {
            parts.add("Аниме");
        }
        if (anime != null && anime.getReleaseYear() != null && !anime.getReleaseYear().trim().isEmpty()) {
            parts.add(anime.getReleaseYear().trim());
        }
        if (subsCount > 0) {
            parts.add("Субтитры");
        }
        if (segmentsCount > 0) {
            parts.add("Сегменты");
        }
        return String.join(" • ", parts);
    }

    private String getEpisodeBadgeText(int count) {
        return com.example.animelib.models.OfflineEpisodeMetadata.formatRussianEpisodesCount(count);
    }

    private class AnimeAdapter extends RecyclerView.Adapter<AnimeAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_downloaded_anime, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            DownloadedAnimeEntity anime;
            synchronized (animeList) {
                if (position < 0 || position >= displayedAnimeList.size()) return;
                anime = displayedAnimeList.get(position);
            }
            if (anime == null) return;

            holder.tvAnimeTitle.setText(anime.getTitle() != null ? anime.getTitle() : "Аниме");
            if (holder.tvAnimeCategory != null) {
                String summary = animeMetaSummaryMap.get(anime.getAnimeId());
                holder.tvAnimeCategory.setText(summary != null && !summary.isEmpty() ? summary : "Аниме");
            }

            // Load Poster from local file or disk cache or HTTP
            String posterPath = anime.getPosterUrl();
            if (posterPath == null || posterPath.isEmpty() || (!posterPath.startsWith("/") && !new File(posterPath).exists())) {
                File internalFile = new File(new File(getFilesDir(), "cached_posters"), anime.getAnimeId() + ".jpg");
                if (internalFile.exists() && internalFile.length() > 0) {
                    posterPath = internalFile.getAbsolutePath();
                } else {
                    File externalDir = getExternalFilesDir("cached_posters");
                    if (externalDir != null) {
                        File externalFile = new File(externalDir, anime.getAnimeId() + ".jpg");
                        if (externalFile.exists() && externalFile.length() > 0) {
                            posterPath = externalFile.getAbsolutePath();
                        }
                    }
                }
            }

            if (posterPath != null && !posterPath.isEmpty()) {
                ImageLoader.getInstance().loadInto(
                        holder.ivPoster,
                        posterPath,
                        R.drawable.skeleton_placeholder
                );
            } else {
                holder.ivPoster.setImageResource(R.drawable.skeleton_placeholder);
            }

            long totalBytes = animeSizesMap.getOrDefault(anime.getAnimeId(), 0L);
            int epCount = animeEpisodeCountsMap.getOrDefault(anime.getAnimeId(), 0);

            holder.tvEpisodeBadge.setText(getEpisodeBadgeText(epCount));
            if (holder.tvSizeBadge != null) {
                holder.tvSizeBadge.setText(formatFileSize(totalBytes));
                holder.tvSizeBadge.setVisibility(View.VISIBLE);
            }

            View.OnClickListener playListener = v -> playFirstEpisode(anime);

            holder.layoutHeader.setOnClickListener(playListener);

            if (holder.btnAnimeOptions != null) {
                holder.btnAnimeOptions.setOnClickListener(v -> showAnimeOptionsMenu(holder.btnAnimeOptions, anime));
            }
        }

        private void showAnimeOptionsMenu(View anchorView, DownloadedAnimeEntity anime) {
            View popupView = LayoutInflater.from(DownloadsActivity.this).inflate(R.layout.popup_download_item_options, null);

            PopupWindow popupWindow = new PopupWindow(
                    popupView,
                    dpToPx(170),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
            );

            popupWindow.setOutsideTouchable(true);
            popupWindow.setFocusable(true);
            popupWindow.setElevation(dpToPx(12));
            popupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

            View itemPlay = popupView.findViewById(R.id.item_option_play);
            View itemDelete = popupView.findViewById(R.id.item_option_delete);

            itemPlay.setOnClickListener(v -> {
                popupWindow.dismiss();
                playFirstEpisode(anime);
            });

            itemDelete.setOnClickListener(v -> {
                popupWindow.dismiss();
                confirmDeleteAnime(anime);
            });

            popupWindow.showAsDropDown(anchorView, 0, dpToPx(4));
        }

        private void playFirstEpisode(DownloadedAnimeEntity anime) {
            if (backgroundExecutor.isShutdown()) return;
            backgroundExecutor.execute(() -> {
                List<DownloadedEpisodeEntity> episodes = databaseManager.getEpisodesForAnimeSync(anime.getAnimeId());
                if (episodes != null && !episodes.isEmpty()) {
                    DownloadedEpisodeEntity ep = null;
                    com.example.animelib.data.entity.OfflineBookmarkEntity bookmark = databaseManager.getOfflineBookmarkSync(anime.getAnimeId());
                    if (bookmark != null) {
                        for (DownloadedEpisodeEntity dep : episodes) {
                            if (bookmark.getEpisodeId() != 0 && dep.getEpisodeId() == bookmark.getEpisodeId()) {
                                ep = dep;
                                break;
                            }
                            if (dep.getId() != null && dep.getId().equals(String.valueOf(bookmark.getEpisodeId()))) {
                                ep = dep;
                                break;
                            }
                            if (dep.getEpisodeNumber() != null && dep.getEpisodeNumber().equals(bookmark.getEpisodeNumber())) {
                                ep = dep;
                                break;
                            }
                        }
                    }
                    if (ep == null) {
                        ep = episodes.get(0);
                    }
                    DownloadedEpisodeEntity finalEp = ep;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        VideoPlayerActivity.startForOfflineEpisode(
                                DownloadsActivity.this,
                                finalEp.getLocalFilePath(),
                                anime.getTitle(),
                                VideoPlayerActivity.cleanEpisodeName(finalEp.getEpisodeName(), finalEp.getEpisodeNumber()),
                                anime.getAnimeId(),
                                finalEp.getEpisodeNumber()
                        );
                    });
                } else {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            com.example.animelib.util.CustomToast.showInfo(DownloadsActivity.this, "Нет скачанных серий");
                        }
                    });
                }
            });
        }

        private void confirmDeleteAnime(DownloadedAnimeEntity anime) {
            try {
                View dialogView = LayoutInflater.from(DownloadsActivity.this).inflate(R.layout.dialog_delete_anime, null);
                TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
                TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
                ImageButton btnClose = dialogView.findViewById(R.id.btnClose);
                MaterialButton btnCancel = dialogView.findViewById(R.id.btnCancel);
                MaterialButton btnDelete = dialogView.findViewById(R.id.btnDelete);

                String animeTitle = (anime != null && anime.getTitle() != null) ? anime.getTitle() : "аниме";
                if (tvMessage != null) {
                    tvMessage.setText("Вы уверены, что хотите удалить «" + animeTitle + "» и все скачанные серии из памяти устройства?");
                }

                AlertDialog dialog = new AlertDialog.Builder(DownloadsActivity.this)
                        .setView(dialogView)
                        .create();

                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                }

                if (btnClose != null) {
                    btnClose.setOnClickListener(v -> dialog.dismiss());
                }
                if (btnCancel != null) {
                    btnCancel.setOnClickListener(v -> dialog.dismiss());
                }
                if (btnDelete != null) {
                    btnDelete.setOnClickListener(v -> {
                        dialog.dismiss();
                        databaseManager.deleteDownloadedAnime(anime.getAnimeId());
                        CustomToast.showSuccess(DownloadsActivity.this, "Аниме удалено");
                    });
                }

                dialog.show();
            } catch (Exception e) {
                Log.e("DownloadsActivity", "Error showing delete anime dialog", e);
                databaseManager.deleteDownloadedAnime(anime.getAnimeId());
                CustomToast.showSuccess(DownloadsActivity.this, "Аниме удалено");
            }
        }

        @Override
        public int getItemCount() {
            synchronized (animeList) {
                return displayedAnimeList.size();
            }
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            View layoutHeader;
            ImageView ivPoster;
            TextView tvAnimeTitle;
            TextView tvAnimeCategory;
            TextView tvEpisodeBadge;
            TextView tvSizeBadge;
            ImageView btnAnimeOptions;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                layoutHeader = itemView.findViewById(R.id.layoutHeader);
                ivPoster = itemView.findViewById(R.id.ivPoster);
                tvAnimeTitle = itemView.findViewById(R.id.tvAnimeTitle);
                tvAnimeCategory = itemView.findViewById(R.id.tvAnimeCategory);
                tvEpisodeBadge = itemView.findViewById(R.id.tvEpisodeBadge);
                tvSizeBadge = itemView.findViewById(R.id.tvSizeBadge);
                btnAnimeOptions = itemView.findViewById(R.id.btnAnimeOptions);
            }
        }
    }
}
