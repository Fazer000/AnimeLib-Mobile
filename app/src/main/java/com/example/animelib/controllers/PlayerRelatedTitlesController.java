package com.example.animelib.controllers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.adapters.HorizontalRelatedTitlesAdapter;
import com.example.animelib.api.ApiService;
import com.example.animelib.managers.AmbientLightManager;
import com.example.animelib.managers.RelatedTitlesManager;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.RelatedTitlesResponse;
import com.example.animelib.util.CustomToast;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;

public class PlayerRelatedTitlesController {

    private static final String TAG = "PlayerRelatedTitlesCtrl";

    public interface RelatedTitlesCallback {
        Context getContext();
        ApiService getApiService();
        AmbientLightManager getAmbientLightManager();
        View getExoController();
        PlayerDialogsController getPlayerDialogsController();
        void saveLatestViewOnExit();
        boolean isOfflineMode();
        void safeRunOnUiThread(Runnable runnable);
    }

    private final RelatedTitlesCallback callback;
    private RelatedTitlesManager relatedTitlesManager;
    private HorizontalRelatedTitlesAdapter relatedTitlesAdapter;
    private HorizontalRelatedTitlesAdapter portraitRelatedTitlesAdapter;

    private android.widget.FrameLayout relatedTitlesOverlay;
    private View relatedTitlesDimOverlay;
    private RecyclerView relatedTitlesRecyclerView;
    private View portraitRelatedTitlesContainer;
    private RecyclerView portraitRelatedTitlesRecyclerView;
    private View portraitRelatedLoadingIndicator;
    private View portraitRelatedSkeletonContainer;
    private View portraitRelatedErrorContainer;
    private View btnRetryPortraitRelated;

    private final List<com.example.animelib.util.SkeletonHelper.SkeletonShimmerDrawable> activeShimmers = new ArrayList<>();

    private String lastAnimeId;
    private String lastAnimeUrl;

    public PlayerRelatedTitlesController(RelatedTitlesCallback callback) {
        this.callback = callback;
    }

    public RelatedTitlesManager getRelatedTitlesManager() {
        return relatedTitlesManager;
    }

    public void initialize(
            android.widget.FrameLayout relatedTitlesOverlay,
            View relatedTitlesDimOverlay,
            RecyclerView relatedTitlesRecyclerView,
            View portraitRelatedTitlesContainer,
            RecyclerView portraitRelatedTitlesRecyclerView,
            View rootOverlay
    ) {
        this.relatedTitlesOverlay = relatedTitlesOverlay;
        this.relatedTitlesDimOverlay = relatedTitlesDimOverlay;
        this.relatedTitlesRecyclerView = relatedTitlesRecyclerView;
        this.portraitRelatedTitlesContainer = portraitRelatedTitlesContainer;
        this.portraitRelatedTitlesRecyclerView = portraitRelatedTitlesRecyclerView;

        Context context = callback != null ? callback.getContext() : null;
        if (context == null) return;

        // Setup main overlay RecyclerView
        if (relatedTitlesRecyclerView != null) {
            relatedTitlesRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            relatedTitlesAdapter = new HorizontalRelatedTitlesAdapter(new ArrayList<>(), this::onRelatedTitleSelected);
        }

        // Initialize RelatedTitlesManager
        relatedTitlesManager = new RelatedTitlesManager();
        if (rootOverlay != null) {
            TextView relatedTitlesHeader = rootOverlay.findViewById(R.id.relatedTitlesHeader);
            LinearLayout relatedAnimeInfoContainer = rootOverlay.findViewById(R.id.relatedAnimeInfoContainer);
            ImageView relatedAnimeCover = rootOverlay.findViewById(R.id.relatedAnimeCover);
            TextView relatedAnimeTitle = rootOverlay.findViewById(R.id.relatedAnimeTitle);
            TextView relatedAnimeEngTitle = rootOverlay.findViewById(R.id.relatedAnimeEngTitle);
            Chip relatedAnimeTypeChip = rootOverlay.findViewById(R.id.relatedAnimeTypeChip);
            Chip relatedAnimeStatusChip = rootOverlay.findViewById(R.id.relatedAnimeStatusChip);
            Chip relatedAnimeYearChip = rootOverlay.findViewById(R.id.relatedAnimeYearChip);
            Chip relatedAnimeAgeChip = rootOverlay.findViewById(R.id.relatedAnimeAgeChip);
            TextView relatedAnimeRating = rootOverlay.findViewById(R.id.relatedAnimeRating);
            TextView relatedAnimeVotes = rootOverlay.findViewById(R.id.relatedAnimeVotes);
            TextView relatedAnimeEpisodes = rootOverlay.findViewById(R.id.relatedAnimeEpisodes);

            relatedTitlesManager.initialize(
                    relatedTitlesOverlay, relatedTitlesDimOverlay, relatedTitlesRecyclerView,
                    relatedTitlesHeader, relatedAnimeInfoContainer, relatedAnimeCover,
                    relatedAnimeTitle, relatedAnimeEngTitle, relatedAnimeTypeChip,
                    relatedAnimeStatusChip, relatedAnimeYearChip, relatedAnimeAgeChip,
                    relatedAnimeRating, relatedAnimeVotes, relatedAnimeEpisodes
            );
            relatedTitlesManager.setAdapter(relatedTitlesAdapter);
        }

        relatedTitlesManager.setPlayerInterfaceControlListener(new RelatedTitlesManager.OnPlayerInterfaceControlListener() {
            @Override
            public void onHidePlayerInterface() {}

            @Override
            public void onShowPlayerInterface() {
                if (callback != null && callback.getAmbientLightManager() != null) {
                    callback.getAmbientLightManager().resume();
                }
            }

            @Override
            public void onPlayerInterfaceAlpha(float alpha) {
                if (callback != null && callback.getExoController() != null) {
                    callback.getExoController().setAlpha(alpha);
                }
            }
        });

        setupRelatedTitlesDragToClose(context);

        // Setup Portrait RecyclerView and controls
        if (portraitRelatedTitlesContainer != null) {
            portraitRelatedSkeletonContainer = portraitRelatedTitlesContainer.findViewById(R.id.portraitRelatedSkeletonContainer);
            portraitRelatedLoadingIndicator = portraitRelatedTitlesContainer.findViewById(R.id.portraitRelatedLoadingIndicator);
            portraitRelatedErrorContainer = portraitRelatedTitlesContainer.findViewById(R.id.portraitRelatedErrorContainer);
            btnRetryPortraitRelated = portraitRelatedTitlesContainer.findViewById(R.id.btnRetryPortraitRelated);

            View.OnClickListener retryListener = v -> {
                if (lastAnimeId != null || lastAnimeUrl != null) {
                    CustomToast.showInfo(context, "Повторная загрузка связанного...");
                    loadRelatedTitles(lastAnimeId, lastAnimeUrl);
                }
            };

            if (btnRetryPortraitRelated != null) {
                btnRetryPortraitRelated.setOnClickListener(retryListener);
            }
        }

        if (portraitRelatedTitlesRecyclerView != null) {
            portraitRelatedTitlesRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            portraitRelatedTitlesAdapter = new HorizontalRelatedTitlesAdapter(new ArrayList<>(), this::onRelatedTitleSelected);
            portraitRelatedTitlesRecyclerView.setAdapter(portraitRelatedTitlesAdapter);
        }
    }

    private void startSkeletonShimmer() {
        stopSkeletonShimmer();
        if (portraitRelatedSkeletonContainer == null) return;

        Context context = callback != null ? callback.getContext() : null;
        if (context == null) return;

        boolean isDark = CustomToast.isDarkTheme(context);

        int[] viewIds = new int[]{
                R.id.skCover1, R.id.skTextType1, R.id.skTextTitle1, R.id.skTextSubTitle1, R.id.skTextStatus1,
                R.id.skCover2, R.id.skTextType2, R.id.skTextTitle2, R.id.skTextSubTitle2, R.id.skTextStatus2
        };

        for (int id : viewIds) {
            View v = portraitRelatedSkeletonContainer.findViewById(id);
            if (v != null) {
                com.example.animelib.util.SkeletonHelper.SkeletonShimmerDrawable shimmer =
                        new com.example.animelib.util.SkeletonHelper.SkeletonShimmerDrawable(8f, isDark);
                v.setBackground(shimmer);
                activeShimmers.add(shimmer);
            }
        }
    }

    private void stopSkeletonShimmer() {
        for (com.example.animelib.util.SkeletonHelper.SkeletonShimmerDrawable shimmer : activeShimmers) {
            shimmer.stopAnimation();
        }
        activeShimmers.clear();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupRelatedTitlesDragToClose(Context context) {
        if (relatedTitlesOverlay == null || context == null) return;

        final float[] initialY = {0f};
        final float[] lastY = {0f};
        final boolean[] isDragging = {false};
        final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        relatedTitlesOverlay.setOnTouchListener((v, event) -> {
            if (relatedTitlesManager == null || !relatedTitlesManager.isRelatedTitlesVisible()) {
                return false;
            }

            float currentY = event.getRawY();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialY[0] = currentY;
                    lastY[0] = currentY;
                    isDragging[0] = false;
                    relatedTitlesOverlay.animate().cancel();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float deltaY = currentY - initialY[0];
                    float moveDelta = currentY - lastY[0];

                    if (!isDragging[0] && Math.abs(deltaY) > touchSlop) {
                        isDragging[0] = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }

                    if (isDragging[0]) {
                        if (deltaY < 0) {
                            relatedTitlesOverlay.setTranslationY(deltaY);
                        } else {
                            relatedTitlesOverlay.setTranslationY(0);
                        }

                        int screenHeight = v.getResources().getDisplayMetrics().heightPixels;
                        float currentTranslation = relatedTitlesOverlay.getTranslationY();
                        float progress = 1.0f + (currentTranslation / screenHeight);
                        progress = Math.max(0f, Math.min(1f, progress));

                        if (relatedTitlesDimOverlay != null) {
                            relatedTitlesDimOverlay.setAlpha(progress);
                        }

                        if (callback != null && callback.getExoController() != null) {
                            callback.getExoController().setAlpha(1f - progress);
                        }
                    }

                    lastY[0] = currentY;
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isDragging[0]) {
                        float finalDeltaY = currentY - initialY[0];
                        float velocity = lastY[0] - currentY;

                        int screenHeight = v.getResources().getDisplayMetrics().heightPixels;
                        float dismissThreshold = screenHeight * 0.15f;

                        boolean shouldDismiss = (finalDeltaY < -dismissThreshold) || (velocity > 50 && finalDeltaY < 0);

                        if (shouldDismiss) {
                            relatedTitlesManager.hideRelatedTitles();
                            if (callback != null && callback.getAmbientLightManager() != null) {
                                callback.getAmbientLightManager().resume();
                            }
                        } else {
                            relatedTitlesOverlay.animate()
                                    .translationY(0f)
                                    .setDuration(200)
                                    .setInterpolator(new DecelerateInterpolator())
                                    .setUpdateListener(animation -> {
                                        float currentTranslation = relatedTitlesOverlay.getTranslationY();
                                        float progress = 1.0f + (currentTranslation / screenHeight);

                                        if (relatedTitlesDimOverlay != null) {
                                            relatedTitlesDimOverlay.setAlpha(progress);
                                        }

                                        if (callback != null && callback.getExoController() != null) {
                                            callback.getExoController().setAlpha(1f - progress);
                                        }
                                    })
                                    .start();
                        }

                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    isDragging[0] = false;
                    return true;
            }

            return false;
        });
    }

    public void loadRelatedTitles(String currentAnimeId, String animeUrl) {
        if (currentAnimeId == null) {
            Log.w(TAG, "No anime ID available for loading related titles");
            return;
        }

        this.lastAnimeId = currentAnimeId;
        this.lastAnimeUrl = animeUrl;

        String animeSlug = currentAnimeId;
        if (animeUrl != null && !animeUrl.isEmpty()) {
            animeSlug = ApiService.extractMediaSlugFromUrl(animeUrl);
        }

        if (animeSlug == null || animeSlug.isEmpty()) {
            Log.w(TAG, "No anime slug available for loading related titles");
            return;
        }

        boolean isOffline = callback != null && callback.isOfflineMode();
        if (isOffline) {
            if (portraitRelatedTitlesContainer != null) {
                portraitRelatedTitlesContainer.setVisibility(View.GONE);
            }
            return;
        }

        Log.d(TAG, "Loading related titles for anime: " + animeSlug);

        if (portraitRelatedTitlesContainer != null) {
            portraitRelatedTitlesContainer.setVisibility(View.VISIBLE);
        }
        if (portraitRelatedSkeletonContainer != null) {
            portraitRelatedSkeletonContainer.setVisibility(View.VISIBLE);
            startSkeletonShimmer();
        }
        if (portraitRelatedLoadingIndicator != null) {
            portraitRelatedLoadingIndicator.setVisibility(View.GONE);
        }
        if (portraitRelatedErrorContainer != null) {
            portraitRelatedErrorContainer.setVisibility(View.GONE);
        }
        if (portraitRelatedTitlesRecyclerView != null) {
            portraitRelatedTitlesRecyclerView.setVisibility(View.GONE);
        }

        ApiService apiService = callback != null ? callback.getApiService() : null;
        if (apiService == null) {
            if (portraitRelatedSkeletonContainer != null) {
                stopSkeletonShimmer();
                portraitRelatedSkeletonContainer.setVisibility(View.GONE);
            }
            if (portraitRelatedLoadingIndicator != null) {
                portraitRelatedLoadingIndicator.setVisibility(View.GONE);
            }
            if (portraitRelatedErrorContainer != null) {
                portraitRelatedErrorContainer.setVisibility(View.VISIBLE);
            }
            return;
        }

        apiService.getRelatedTitles(animeSlug, new ApiService.RelatedTitlesCallback() {
            @Override
            public void onRelatedTitlesReceived(RelatedTitlesResponse response) {
                if (callback != null) {
                    callback.safeRunOnUiThread(() -> {
                        if (portraitRelatedSkeletonContainer != null) {
                            stopSkeletonShimmer();
                            portraitRelatedSkeletonContainer.setVisibility(View.GONE);
                        }
                        if (portraitRelatedLoadingIndicator != null) {
                            portraitRelatedLoadingIndicator.setVisibility(View.GONE);
                        }
                        if (response.getData() != null && !response.getData().isEmpty()) {
                            Log.d(TAG, "Related titles loaded: " + response.getData().size());
                            if (portraitRelatedErrorContainer != null) {
                                portraitRelatedErrorContainer.setVisibility(View.GONE);
                            }
                            if (portraitRelatedTitlesRecyclerView != null) {
                                portraitRelatedTitlesRecyclerView.setVisibility(View.VISIBLE);
                            }
                            showRelatedTitles(response.getData());
                        } else {
                            Log.d(TAG, "No related titles found");
                            if (portraitRelatedTitlesContainer != null) {
                                portraitRelatedTitlesContainer.setVisibility(View.GONE);
                            }
                        }
                    });
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error loading related titles: " + error);
                if (callback != null) {
                    callback.safeRunOnUiThread(() -> {
                        if (portraitRelatedSkeletonContainer != null) {
                            stopSkeletonShimmer();
                            portraitRelatedSkeletonContainer.setVisibility(View.GONE);
                        }
                        if (portraitRelatedLoadingIndicator != null) {
                            portraitRelatedLoadingIndicator.setVisibility(View.GONE);
                        }
                        if (portraitRelatedTitlesRecyclerView != null) {
                            portraitRelatedTitlesRecyclerView.setVisibility(View.GONE);
                        }
                        if (portraitRelatedErrorContainer != null) {
                            portraitRelatedErrorContainer.setVisibility(View.VISIBLE);
                        }
                        if (portraitRelatedTitlesContainer != null) {
                            portraitRelatedTitlesContainer.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        });
    }

    public void showRelatedTitles(List<RelatedTitlesResponse.RelatedTitle> relatedTitles) {
        if (relatedTitlesManager != null) {
            relatedTitlesManager.updateRelatedTitles(relatedTitles);
        }
        if (portraitRelatedTitlesAdapter != null) {
            portraitRelatedTitlesAdapter.updateData(relatedTitles);
            if (portraitRelatedTitlesContainer != null) {
                boolean isOffline = callback != null && callback.isOfflineMode();
                if (portraitRelatedTitlesAdapter.getItemCount() > 0 && !isOffline) {
                    portraitRelatedTitlesContainer.setVisibility(View.VISIBLE);
                } else {
                    portraitRelatedTitlesContainer.setVisibility(View.GONE);
                }
            }
        }
    }

    public void setAnimeInfoToRelatedPanel(AnimeInfoResponse.Data animeData) {
        if (relatedTitlesManager == null || animeData == null) {
            Log.w(TAG, "relatedTitlesManager or animeData is null");
            return;
        }

        String coverUrl = null;
        if (animeData.getCover() != null && animeData.getCover().getDefaultUrl() != null) {
            coverUrl = animeData.getCover().getDefaultUrl();
        }

        String title = animeData.getRus_name();
        if (title == null || title.trim().isEmpty()) {
            title = animeData.getEng_name();
        }
        if (title == null || title.trim().isEmpty()) {
            title = animeData.getName();
        }
        if (title == null) {
            title = "Без названия";
        }

        String engTitle = null;
        if (animeData.getRus_name() != null && !animeData.getRus_name().trim().isEmpty()) {
            engTitle = animeData.getEng_name();
        }

        String type = null;
        if (animeData.getType() != null && animeData.getType().getLabel() != null) {
            type = animeData.getType().getLabel();
        }

        String status = null;
        if (animeData.getStatus() != null && animeData.getStatus().getLabel() != null) {
            status = animeData.getStatus().getLabel();
        }

        String year = null;
        if (animeData.getReleaseDateString() != null && !animeData.getReleaseDateString().isEmpty()) {
            try {
                String dateStr = animeData.getReleaseDateString();
                if (dateStr.contains(".")) {
                    String[] parts = dateStr.split("\\.");
                    if (parts.length >= 3) {
                        year = parts[2];
                    }
                } else if (dateStr.matches("\\d{4}")) {
                    year = dateStr;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse year from: " + animeData.getReleaseDateString());
            }
        }

        String ageRating = null;
        if (animeData.getAgeRestriction() != null && animeData.getAgeRestriction().getLabel() != null) {
            ageRating = animeData.getAgeRestriction().getLabel();
        }

        String rating = "";
        String votes = "";
        if (animeData.getRating() != null) {
            if (animeData.getRating().getAverageFormated() != null) {
                rating = animeData.getRating().getAverageFormated();
            }
            if (animeData.getRating().getVotesFormated() != null) {
                votes = "(" + animeData.getRating().getVotesFormated() + ")";
            }
        }

        String episodes = null;
        if (animeData.getItems_count() != null) {
            episodes = "Эпизоды: " + animeData.getItems_count().getUploaded() +
                    " / " + animeData.getItems_count().getTotal();
        }

        relatedTitlesManager.setAnimeInfo(coverUrl, title, engTitle, type, status, year,
                ageRating, rating, votes, episodes);
    }

    public void onRelatedTitleSelected(RelatedTitlesResponse.Media media) {
        if (media == null) {
            Log.w(TAG, "Related title media is null");
            return;
        }

        if (callback != null) {
            callback.saveLatestViewOnExit();
        }

        Context context = callback != null ? callback.getContext() : null;
        if (context == null) return;

        String titleName = HorizontalRelatedTitlesAdapter.getDisplayTitle(media);
        String webUrl = HorizontalRelatedTitlesAdapter.buildWebUrl(media, context);

        Log.d(TAG, "Related title selected: " + titleName + " -> " + webUrl);

        if (webUrl == null || webUrl.isEmpty()) {
            CustomToast.showWarning(context, "Не удалось сформировать ссылку для тайтла");
            return;
        }

        try {
            if (relatedTitlesManager != null && relatedTitlesManager.isRelatedTitlesVisible()) {
                relatedTitlesManager.hideRelatedTitles();
            }

            if (callback != null && callback.getPlayerDialogsController() != null) {
                callback.getPlayerDialogsController().showTitleWebViewBottomSheet(titleName, webUrl);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error showing TitleWebViewBottomSheet: " + t.getMessage(), t);
            CustomToast.showWarning(context, "Не удалось открыть страницу тайтла");
        }
    }
}
