package com.example.animelib.controllers;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.animelib.R;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.util.ImageLoader;
import com.example.animelib.util.SkeletonHelper;

/**
 * Контроллер карточки информации об аниме и скелетонов загрузки.
 */
public class PlayerAnimeInfoController {

    private static final String TAG = "PlayerAnimeInfoCtrl";

    private View animeInfoPlaceholder;
    private ImageView animeInfoPoster;
    private TextView animeInfoTitle;
    private TextView animeInfoOriginalTitle;
    private TextView animeInfoYear;
    private TextView animeInfoType;
    private TextView animeInfoStatus;
    private TextView animeInfoRating;
    private TextView animeInfoEpisodes;
    private TextView animeInfoAge;
    private TextView animeInfoReleaseDate;
    private TextView animeInfoShikimori;

    // Portrait expanded info views
    private View portraitTitleContainer;
    private ImageView ivPortraitAnimeTitleChevron;
    private View portraitExpandedAnimeInfoContainer;
    private ImageView ivPortraitInfoPoster;
    private TextView tvPortraitInfoRatingPill;
    private TextView tvPortraitInfoTitle;
    private TextView tvPortraitInfoEngName;
    private TextView tvPortraitInfoType;
    private TextView tvPortraitInfoStatus;
    private TextView tvPortraitInfoEpisodes;
    private TextView tvPortraitInfoReleaseDate;
    private HorizontalScrollView hsvPortraitAuthors;
    private LinearLayout llPortraitAuthorsContainer;
    private TextView tvPortraitInfoSummary;
    private TextView btnToggleSummary;
    private boolean isSummaryExpanded = false;
    private com.google.android.material.chip.ChipGroup cgPortraitInfoTagsAndGenres;

    // Error & Retry views
    private View portraitInfoContentLayout;
    private View portraitInfoErrorLayout;
    private View btnRetryPortraitAnimeInfo;

    private View animeInfoContentLayout;
    private View animeInfoErrorLayout;
    private View btnRetryAnimeInfoPlaceholder;

    private Runnable onRetryListener;

    private boolean isOfflineMode = false;
    private boolean hasShownInitialAnimeInfo = false;
    private String currentPosterUrl = "";
    private AnimeInfoResponse currentAnimeInfo;

    public void initViews(View rootView) {
        if (rootView == null) return;
        animeInfoPlaceholder = rootView.findViewById(R.id.animeInfoPlaceholder);
        animeInfoPoster = rootView.findViewById(R.id.animeInfoPoster);
        animeInfoTitle = rootView.findViewById(R.id.animeInfoTitle);
        animeInfoOriginalTitle = rootView.findViewById(R.id.animeInfoOriginalTitle);
        animeInfoYear = rootView.findViewById(R.id.animeInfoYear);
        animeInfoType = rootView.findViewById(R.id.animeInfoType);
        animeInfoStatus = rootView.findViewById(R.id.animeInfoStatus);
        animeInfoRating = rootView.findViewById(R.id.animeInfoRating);
        animeInfoEpisodes = rootView.findViewById(R.id.animeInfoEpisodes);
        animeInfoAge = rootView.findViewById(R.id.animeInfoAge);
        animeInfoReleaseDate = rootView.findViewById(R.id.animeInfoReleaseDate);
        animeInfoShikimori = rootView.findViewById(R.id.animeInfoShikimori);

        // Portrait Views
        portraitTitleContainer = rootView.findViewById(R.id.portraitTitleContainer);
        ivPortraitAnimeTitleChevron = rootView.findViewById(R.id.ivPortraitAnimeTitleChevron);
        portraitExpandedAnimeInfoContainer = rootView.findViewById(R.id.portraitExpandedAnimeInfoContainer);
        ivPortraitInfoPoster = rootView.findViewById(R.id.ivPortraitInfoPoster);
        tvPortraitInfoRatingPill = rootView.findViewById(R.id.tvPortraitInfoRatingPill);
        tvPortraitInfoTitle = rootView.findViewById(R.id.tvPortraitInfoTitle);
        tvPortraitInfoEngName = rootView.findViewById(R.id.tvPortraitInfoEngName);
        tvPortraitInfoType = rootView.findViewById(R.id.tvPortraitInfoType);
        tvPortraitInfoStatus = rootView.findViewById(R.id.tvPortraitInfoStatus);
        tvPortraitInfoEpisodes = rootView.findViewById(R.id.tvPortraitInfoEpisodes);
        tvPortraitInfoReleaseDate = rootView.findViewById(R.id.tvPortraitInfoReleaseDate);
        hsvPortraitAuthors = rootView.findViewById(R.id.hsvPortraitAuthors);
        llPortraitAuthorsContainer = rootView.findViewById(R.id.llPortraitAuthorsContainer);
        tvPortraitInfoSummary = rootView.findViewById(R.id.tvPortraitInfoSummary);
        btnToggleSummary = rootView.findViewById(R.id.btnToggleSummary);
        cgPortraitInfoTagsAndGenres = rootView.findViewById(R.id.cgPortraitInfoTagsAndGenres);

        // Error & Retry layouts
        portraitInfoContentLayout = rootView.findViewById(R.id.portraitInfoContentLayout);
        portraitInfoErrorLayout = rootView.findViewById(R.id.portraitInfoErrorLayout);
        btnRetryPortraitAnimeInfo = rootView.findViewById(R.id.btnRetryPortraitAnimeInfo);

        animeInfoContentLayout = rootView.findViewById(R.id.animeInfoContentLayout);
        animeInfoErrorLayout = rootView.findViewById(R.id.animeInfoErrorLayout);
        btnRetryAnimeInfoPlaceholder = rootView.findViewById(R.id.btnRetryAnimeInfoPlaceholder);

        View.OnClickListener retryClickListener = v -> {
            if (onRetryListener != null) {
                showSkeletons();
                onRetryListener.run();
            }
        };

        if (btnRetryPortraitAnimeInfo != null) {
            btnRetryPortraitAnimeInfo.setOnClickListener(retryClickListener);
        }
        if (btnRetryAnimeInfoPlaceholder != null) {
            btnRetryAnimeInfoPlaceholder.setOnClickListener(retryClickListener);
        }

        if (portraitTitleContainer != null) {
            portraitTitleContainer.setOnClickListener(v -> {
                if (!isOfflineMode) {
                    togglePortraitInfoExpanded();
                }
            });
        }

        View btnPortraitCloseExpanded = rootView.findViewById(R.id.btnPortraitCloseExpanded);
        if (btnPortraitCloseExpanded != null) {
            btnPortraitCloseExpanded.setOnClickListener(v -> {
                if (!isOfflineMode) {
                    togglePortraitInfoExpanded();
                }
            });
        }
    }

    public void setOfflineMode(boolean offlineMode) {
        this.isOfflineMode = offlineMode;
        if (portraitTitleContainer != null) {
            portraitTitleContainer.setEnabled(!offlineMode);
            portraitTitleContainer.setClickable(!offlineMode);
            portraitTitleContainer.setFocusable(!offlineMode);
        }
        if (ivPortraitAnimeTitleChevron != null) {
            ivPortraitAnimeTitleChevron.setVisibility(offlineMode ? View.GONE : View.VISIBLE);
        }
        if (offlineMode && portraitExpandedAnimeInfoContainer != null) {
            portraitExpandedAnimeInfoContainer.setVisibility(View.GONE);
        }
    }

    public boolean isOfflineMode() {
        return isOfflineMode;
    }

    public void setOnRetryListener(Runnable listener) {
        this.onRetryListener = listener;
    }

    public void showError(Runnable retryAction) {
        if (retryAction != null) {
            this.onRetryListener = retryAction;
        }

        hideSkeletons(null);

        if (portraitInfoContentLayout != null) portraitInfoContentLayout.setVisibility(View.GONE);
        if (portraitInfoErrorLayout != null) portraitInfoErrorLayout.setVisibility(View.VISIBLE);

        if (animeInfoContentLayout != null) animeInfoContentLayout.setVisibility(View.GONE);
        if (animeInfoErrorLayout != null) animeInfoErrorLayout.setVisibility(View.VISIBLE);
    }

    public void showSkeletons() {
        if (portraitInfoErrorLayout != null) portraitInfoErrorLayout.setVisibility(View.GONE);
        if (portraitInfoContentLayout != null) portraitInfoContentLayout.setVisibility(View.VISIBLE);

        if (animeInfoErrorLayout != null) animeInfoErrorLayout.setVisibility(View.GONE);
        if (animeInfoContentLayout != null) animeInfoContentLayout.setVisibility(View.VISIBLE);

        if (animeInfoTitle != null) SkeletonHelper.showSkeleton(animeInfoTitle, 200);
        if (animeInfoOriginalTitle != null) SkeletonHelper.showSkeleton(animeInfoOriginalTitle, 140);
        if (animeInfoRating != null) SkeletonHelper.showSkeleton(animeInfoRating, 40);
        if (animeInfoEpisodes != null) SkeletonHelper.showSkeleton(animeInfoEpisodes, 80);
        if (animeInfoYear != null) SkeletonHelper.showSkeleton(animeInfoYear, 50);
        if (animeInfoAge != null) SkeletonHelper.showSkeleton(animeInfoAge, 50);
        if (animeInfoType != null) SkeletonHelper.showSkeleton(animeInfoType, 60);
        if (animeInfoStatus != null) SkeletonHelper.showSkeleton(animeInfoStatus, 70);
        if (animeInfoReleaseDate != null) SkeletonHelper.showSkeleton(animeInfoReleaseDate, 100);
        if (animeInfoShikimori != null) SkeletonHelper.showSkeleton(animeInfoShikimori, 40);
    }

    public void hideSkeletons(String defaultTitle) {
        if (animeInfoTitle != null) SkeletonHelper.hideSkeleton(animeInfoTitle, defaultTitle != null ? defaultTitle : "");
        if (animeInfoOriginalTitle != null) SkeletonHelper.hideSkeleton(animeInfoOriginalTitle, "");
        if (animeInfoRating != null) SkeletonHelper.hideSkeleton(animeInfoRating, "");
        if (animeInfoEpisodes != null) SkeletonHelper.hideSkeleton(animeInfoEpisodes, "");
        if (animeInfoYear != null) SkeletonHelper.hideSkeleton(animeInfoYear, "");
        if (animeInfoAge != null) SkeletonHelper.hideSkeleton(animeInfoAge, "");
        if (animeInfoType != null) SkeletonHelper.hideSkeleton(animeInfoType, "");
        if (animeInfoStatus != null) SkeletonHelper.hideSkeleton(animeInfoStatus, "");
        if (animeInfoReleaseDate != null) SkeletonHelper.hideSkeleton(animeInfoReleaseDate, "");
        if (animeInfoShikimori != null) SkeletonHelper.hideSkeleton(animeInfoShikimori, "");
    }

    public void displayAnimeInfo(Context context, AnimeInfoResponse animeInfo) {
        if (portraitInfoErrorLayout != null) portraitInfoErrorLayout.setVisibility(View.GONE);
        if (portraitInfoContentLayout != null) portraitInfoContentLayout.setVisibility(View.VISIBLE);

        if (animeInfoErrorLayout != null) animeInfoErrorLayout.setVisibility(View.GONE);
        if (animeInfoContentLayout != null) animeInfoContentLayout.setVisibility(View.VISIBLE);

        if (animeInfo == null || animeInfo.getData() == null) {
            Log.e(TAG, "displayAnimeInfo: animeInfo or data is null");
            return;
        }

        this.currentAnimeInfo = animeInfo;
        AnimeInfoResponse.Data data = animeInfo.getData();

        if (animeInfoTitle != null) {
            SkeletonHelper.hideSkeleton(animeInfoTitle, data.getRus_name() != null ? data.getRus_name() : "—");
        }

        if (animeInfoOriginalTitle != null) {
            if (!TextUtils.isEmpty(data.getEng_name())) {
                SkeletonHelper.hideSkeleton(animeInfoOriginalTitle, data.getEng_name());
                animeInfoOriginalTitle.setVisibility(View.VISIBLE);
            } else {
                SkeletonHelper.hideSkeleton(animeInfoOriginalTitle, "");
                animeInfoOriginalTitle.setVisibility(View.GONE);
            }
        }

        if (animeInfoYear != null) {
            String year = (data.getReleaseDate() != null && data.getReleaseDate().length() >= 4)
                    ? data.getReleaseDate().substring(0, 4) : "—";
            SkeletonHelper.hideSkeleton(animeInfoYear, year);
        }

        if (animeInfoType != null) {
            String type = (data.getType() != null && data.getType().getLabel() != null)
                    ? data.getType().getLabel().replace("TV ", "") : "—";
            SkeletonHelper.hideSkeleton(animeInfoType, type);
        }

        if (animeInfoStatus != null) {
            String statusLabel = (data.getStatus() != null && data.getStatus().getLabel() != null)
                    ? data.getStatus().getLabel() : "—";
            SkeletonHelper.hideSkeleton(animeInfoStatus, statusLabel);
        }

        if (animeInfoRating != null) {
            String ratingStr = (data.getRating() != null && data.getRating().getAverageFormated() != null)
                    ? data.getRating().getAverageFormated() : "—";
            SkeletonHelper.hideSkeleton(animeInfoRating, ratingStr);
        }

        if (animeInfoEpisodes != null) {
            String epStr = (data.getItems_count() != null && data.getItems_count().getUploaded() > 0)
                    ? (data.getItems_count().getUploaded() + " эпизодов") : "—";
            SkeletonHelper.hideSkeleton(animeInfoEpisodes, epStr);
        }

        if (animeInfoAge != null) {
            String ageStr = (data.getAgeRestriction() != null && data.getAgeRestriction().getLabel() != null)
                    ? data.getAgeRestriction().getLabel() : "—";
            SkeletonHelper.hideSkeleton(animeInfoAge, ageStr);
        }

        if (animeInfoReleaseDate != null) {
            String relStr = data.getReleaseDateString() != null ? data.getReleaseDateString() : "—";
            SkeletonHelper.hideSkeleton(animeInfoReleaseDate, relStr);
        }

        if (animeInfoShikimori != null) {
            String shikiStr = data.getShikimori_href() != null ? String.valueOf(data.getShiki_rate()) : "—";
            SkeletonHelper.hideSkeleton(animeInfoShikimori, shikiStr);
        }

        if (animeInfoPoster != null && data.getCover() != null) {
            String posterUrl = data.getCover().getDefaultUrl();
            if (posterUrl != null && !posterUrl.isEmpty()) {
                currentPosterUrl = posterUrl;
                ImageLoader.getInstance()
                        .loadInto(animeInfoPoster, posterUrl, R.drawable.ic_image_placeholder);
            } else {
                animeInfoPoster.setImageResource(R.drawable.ic_image_placeholder);
            }
        }

        populatePortraitViews(context, data);
    }

    public void togglePortraitInfoExpanded() {
        if (isOfflineMode || portraitExpandedAnimeInfoContainer == null) return;

        android.view.ViewGroup parent = (android.view.ViewGroup) portraitExpandedAnimeInfoContainer.getParent();
        if (parent != null) {
            android.transition.AutoTransition transition = new android.transition.AutoTransition();
            transition.setDuration(220);
            android.transition.TransitionManager.beginDelayedTransition(parent, transition);
        }

        boolean isExpanded = portraitExpandedAnimeInfoContainer.getVisibility() == View.VISIBLE;
        if (isExpanded) {
            portraitExpandedAnimeInfoContainer.setVisibility(View.GONE);
            if (portraitTitleContainer != null) {
                portraitTitleContainer.setVisibility(View.VISIBLE);
            }
            if (ivPortraitAnimeTitleChevron != null) {
                ivPortraitAnimeTitleChevron.animate().rotation(90f).setDuration(200).start();
            }
        } else {
            portraitExpandedAnimeInfoContainer.setVisibility(View.VISIBLE);
            if (portraitTitleContainer != null) {
                portraitTitleContainer.setVisibility(View.GONE);
            }
            if (ivPortraitAnimeTitleChevron != null) {
                ivPortraitAnimeTitleChevron.animate().rotation(270f).setDuration(200).start();
            }
        }
    }

    private void populatePortraitViews(Context context, AnimeInfoResponse.Data data) {
        if (data == null) return;

        // 1. Poster
        if (ivPortraitInfoPoster != null) {
            String posterUrl = data.getCover() != null ? data.getCover().getDefaultUrl() : null;
            if (posterUrl != null && !posterUrl.isEmpty()) {
                ImageLoader.getInstance().loadInto(ivPortraitInfoPoster, posterUrl, R.drawable.ic_image_placeholder);
            } else {
                ivPortraitInfoPoster.setImageResource(R.drawable.ic_image_placeholder);
            }
        }

        // 2. Rating Pill Overlay ("Тайтл ★ 9.59")
        if (tvPortraitInfoRatingPill != null) {
            String ratingStr = (data.getRating() != null && !TextUtils.isEmpty(data.getRating().getAverageFormated()))
                    ? data.getRating().getAverageFormated() : null;
            if (ratingStr != null) {
                int votes = data.getRating() != null ? data.getRating().getVotes() : 0;
                String pillText = votes > 0 ? ("Тайтл ★ " + ratingStr + "  " + votes) : ("Тайтл ★ " + ratingStr);
                tvPortraitInfoRatingPill.setText(pillText);
                tvPortraitInfoRatingPill.setVisibility(View.VISIBLE);
            } else {
                tvPortraitInfoRatingPill.setVisibility(View.GONE);
            }
        }

        // 3. Titles
        if (tvPortraitInfoTitle != null) {
            String title = !TextUtils.isEmpty(data.getRus_name()) ? data.getRus_name() : data.getName();
            if (!TextUtils.isEmpty(title)) {
                tvPortraitInfoTitle.setText(title);
                tvPortraitInfoTitle.setVisibility(View.VISIBLE);
            }
        }

        if (tvPortraitInfoEngName != null) {
            if (!TextUtils.isEmpty(data.getEng_name())) {
                tvPortraitInfoEngName.setText(data.getEng_name());
                tvPortraitInfoEngName.setVisibility(View.VISIBLE);
            } else {
                tvPortraitInfoEngName.setVisibility(View.GONE);
            }
        }

        // 4. Metadata Grid: Type, Status, Episodes, Release
        if (tvPortraitInfoType != null) {
            String type = (data.getType() != null && data.getType().getLabel() != null)
                    ? data.getType().getLabel().replace("TV ", "") : "—";
            tvPortraitInfoType.setText(type);
        }

        if (tvPortraitInfoStatus != null) {
            String status = (data.getStatus() != null && data.getStatus().getLabel() != null)
                    ? data.getStatus().getLabel() : "—";
            tvPortraitInfoStatus.setText(status);
        }

        if (tvPortraitInfoEpisodes != null) {
            String epStr = "—";
            if (data.getItems_count() != null) {
                if (data.getItems_count().getTotal() > 0 && data.getItems_count().getUploaded() > 0) {
                    epStr = data.getItems_count().getUploaded() + " из " + data.getItems_count().getTotal();
                } else if (data.getItems_count().getUploaded() > 0) {
                    epStr = data.getItems_count().getUploaded() + " эп.";
                }
            }
            tvPortraitInfoEpisodes.setText(epStr);
        }

        if (tvPortraitInfoReleaseDate != null) {
            String releaseStr = !TextUtils.isEmpty(data.getReleaseDateString()) ? data.getReleaseDateString() : "—";
            tvPortraitInfoReleaseDate.setText(releaseStr);
        }

        // 5. Authors & Publisher Cards
        if (llPortraitAuthorsContainer != null) {
            llPortraitAuthorsContainer.removeAllViews();
            boolean hasAuthors = false;

            if (data.getAuthors() != null && !data.getAuthors().isEmpty()) {
                for (AnimeInfoResponse.TagOrGenre author : data.getAuthors()) {
                    View card = createAuthorCardView(context, author.getName(), "Автор", author.getAvatarUrl());
                    llPortraitAuthorsContainer.addView(card);
                    hasAuthors = true;
                }
            }

            if (data.getPublisher() != null && !data.getPublisher().isEmpty()) {
                for (AnimeInfoResponse.TagOrGenre pub : data.getPublisher()) {
                    View card = createAuthorCardView(context, pub.getName(), "Издатель", pub.getAvatarUrl());
                    llPortraitAuthorsContainer.addView(card);
                    hasAuthors = true;
                }
            }

            if (hsvPortraitAuthors != null) {
                hsvPortraitAuthors.setVisibility(hasAuthors ? View.VISIBLE : View.GONE);
            } else {
                llPortraitAuthorsContainer.setVisibility(hasAuthors ? View.VISIBLE : View.GONE);
            }
        }

        // 6. Summary / Synopsis
        String summaryText = data.getSummaryText();
        if (tvPortraitInfoSummary != null) {
            if (!TextUtils.isEmpty(summaryText)) {
                CharSequence formattedSummary;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    formattedSummary = android.text.Html.fromHtml(summaryText, android.text.Html.FROM_HTML_MODE_LEGACY);
                } else {
                    formattedSummary = android.text.Html.fromHtml(summaryText);
                }

                final String cleanSummary = formattedSummary.toString().trim();
                tvPortraitInfoSummary.setText(cleanSummary);
                tvPortraitInfoSummary.setVisibility(View.VISIBLE);

                // Default collapsed state (3 lines max)
                isSummaryExpanded = false;
                tvPortraitInfoSummary.setMaxLines(3);
                tvPortraitInfoSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);

                // Determine if expansion button should be shown
                tvPortraitInfoSummary.post(() -> {
                    if (tvPortraitInfoSummary.getLayout() != null) {
                        int lineCount = tvPortraitInfoSummary.getLineCount();
                        boolean isEllipsized = tvPortraitInfoSummary.getLayout().getEllipsisCount(lineCount - 1) > 0;
                        if (lineCount > 3 || isEllipsized || cleanSummary.length() > 150) {
                            if (btnToggleSummary != null) {
                                btnToggleSummary.setVisibility(View.VISIBLE);
                                btnToggleSummary.setText("Развернуть");
                            }
                        } else {
                            if (btnToggleSummary != null) {
                                btnToggleSummary.setVisibility(View.GONE);
                            }
                        }
                    } else if (cleanSummary.length() > 150) {
                        if (btnToggleSummary != null) {
                            btnToggleSummary.setVisibility(View.VISIBLE);
                            btnToggleSummary.setText("Развернуть");
                        }
                    } else {
                        if (btnToggleSummary != null) {
                            btnToggleSummary.setVisibility(View.GONE);
                        }
                    }
                });

                View.OnClickListener toggleClickListener = v -> {
                    if (btnToggleSummary != null && btnToggleSummary.getVisibility() == View.VISIBLE) {
                        if (isSummaryExpanded) {
                            tvPortraitInfoSummary.setMaxLines(3);
                            tvPortraitInfoSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            btnToggleSummary.setText("Развернуть");
                            isSummaryExpanded = false;
                        } else {
                            tvPortraitInfoSummary.setMaxLines(Integer.MAX_VALUE);
                            tvPortraitInfoSummary.setEllipsize(null);
                            btnToggleSummary.setText("Свернуть");
                            isSummaryExpanded = true;
                        }
                    }
                };

                if (btnToggleSummary != null) {
                    btnToggleSummary.setOnClickListener(toggleClickListener);
                }
                tvPortraitInfoSummary.setOnClickListener(toggleClickListener);

            } else {
                tvPortraitInfoSummary.setVisibility(View.GONE);
                if (btnToggleSummary != null) {
                    btnToggleSummary.setVisibility(View.GONE);
                }
            }
        }

        // 7. Flow Chips (Age restriction, Genres, Tags)
        if (cgPortraitInfoTagsAndGenres != null) {
            cgPortraitInfoTagsAndGenres.removeAllViews();
            boolean hasChips = false;

            // Age restriction
            if (data.getAgeRestriction() != null && !TextUtils.isEmpty(data.getAgeRestriction().getLabel())) {
                com.google.android.material.chip.Chip ageChip = createAgeChip(context, data.getAgeRestriction().getLabel());
                cgPortraitInfoTagsAndGenres.addView(ageChip);
                hasChips = true;
            }

            // Genres
            if (data.getGenres() != null && !data.getGenres().isEmpty()) {
                for (AnimeInfoResponse.TagOrGenre genre : data.getGenres()) {
                    com.google.android.material.chip.Chip chip = createTagChip(context, genre.getName(), true);
                    cgPortraitInfoTagsAndGenres.addView(chip);
                    hasChips = true;
                }
            }

            // Tags
            if (data.getTags() != null && !data.getTags().isEmpty()) {
                for (AnimeInfoResponse.TagOrGenre tag : data.getTags()) {
                    com.google.android.material.chip.Chip chip = createTagChip(context, "# " + tag.getName(), false);
                    cgPortraitInfoTagsAndGenres.addView(chip);
                    hasChips = true;
                }
            }

            cgPortraitInfoTagsAndGenres.setVisibility(hasChips ? View.VISIBLE : View.GONE);
        }
    }

    private View createAuthorCardView(Context context, String name, String role, String avatarUrl) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_author_card);
        int pH = dpToPx(context, 10);
        int pV = dpToPx(context, 8);
        card.setPadding(pH, pV, pH, pV);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dpToPx(context, 8));
        card.setLayoutParams(params);

        com.google.android.material.imageview.ShapeableImageView icon = new com.google.android.material.imageview.ShapeableImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int iconSize = dpToPx(context, 26);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.setMarginEnd(dpToPx(context, 8));
        icon.setLayoutParams(iconParams);
        icon.setShapeAppearanceModel(
                icon.getShapeAppearanceModel()
                        .toBuilder()
                        .setAllCornerSizes(dpToPx(context, 6f))
                        .build()
        );

        if (!TextUtils.isEmpty(avatarUrl)) {
            ImageLoader.getInstance().loadInto(icon, avatarUrl, R.drawable.ic_avatar_placeholder);
        } else {
            icon.setImageResource(R.drawable.ic_avatar_placeholder);
        }

        card.addView(icon);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvName = new TextView(context);
        tvName.setText(name);
        tvName.setTextSize(12f);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvName.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.primary_text_color));
        textCol.addView(tvName);

        TextView tvRole = new TextView(context);
        tvRole.setText(role);
        tvRole.setTextSize(10.5f);
        tvRole.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.accent_text_color));
        textCol.addView(tvRole);

        card.addView(textCol);
        return card;
    }

    private com.google.android.material.chip.Chip createAgeChip(Context context, String text) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(context);
        chip.setText(text);
        chip.setTextSize(12f);
        chip.setTextColor(android.graphics.Color.parseColor("#FF5252"));
        chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2A181C")));
        chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4D2026")));
        chip.setChipStrokeWidth(dpToPx(context, 0.5f));
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipStartPadding(dpToPx(context, 6));
        chip.setChipEndPadding(dpToPx(context, 6));
        chip.setTextStartPadding(dpToPx(context, 1));
        chip.setTextEndPadding(dpToPx(context, 1));
        chip.setChipMinHeight(dpToPx(context, 20));
        chip.setPadding(0, 0, 0, 0);
        return chip;
    }

    private com.google.android.material.chip.Chip createTagChip(Context context, String text, boolean isGenre) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(context);
        chip.setText(text);
        chip.setTextSize(12f);
        if (isGenre) {
            chip.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.purple_primary));
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.purple_alpha_10)));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.purple_alpha_25)));
            chip.setChipStrokeWidth(dpToPx(context, 0.5f));
        } else {
            chip.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.accent_text_color));
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.chip_unselected_bg)));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.chip_unselected_stroke)));
            chip.setChipStrokeWidth(dpToPx(context, 0.5f));
        }
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipStartPadding(dpToPx(context, 6));
        chip.setChipEndPadding(dpToPx(context, 6));
        chip.setTextStartPadding(dpToPx(context, 1));
        chip.setTextEndPadding(dpToPx(context, 1));
        chip.setChipMinHeight(dpToPx(context, 20));
        chip.setPadding(0, 0, 0, 0);
        return chip;
    }

    private float dpToPx(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

    private int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public void showPlaceholder() {
        if (animeInfoPlaceholder != null) {
            animeInfoPlaceholder.setVisibility(View.GONE);
        }
    }

    public void hidePlaceholderAnimated() {
        if (animeInfoPlaceholder != null && animeInfoPlaceholder.getVisibility() == View.VISIBLE) {
            animeInfoPlaceholder.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        if (animeInfoPlaceholder != null) {
                            animeInfoPlaceholder.setVisibility(View.GONE);
                            animeInfoPlaceholder.setAlpha(1f);
                        }
                    })
                    .start();
        }
    }

    public void hidePlaceholderImmediately() {
        if (animeInfoPlaceholder != null) {
            animeInfoPlaceholder.setVisibility(View.GONE);
        }
    }

    public boolean isHasShownInitialAnimeInfo() {
        return hasShownInitialAnimeInfo;
    }

    public void setHasShownInitialAnimeInfo(boolean hasShown) {
        this.hasShownInitialAnimeInfo = hasShown;
    }

    public String getCurrentPosterUrl() {
        return currentPosterUrl;
    }

    public AnimeInfoResponse getCurrentAnimeInfo() {
        return currentAnimeInfo;
    }
}
