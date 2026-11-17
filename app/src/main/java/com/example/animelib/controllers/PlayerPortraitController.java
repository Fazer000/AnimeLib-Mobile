package com.example.animelib.controllers;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.services.DownloadService;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.ItemAnimationUtils;
import com.example.animelib.util.SkeletonHelper;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.Objects;

/**
 * Контроллер портретного интерфейса плеера, заголовков эпизода/тайтла,
 * скелетонов загрузки, а также загрузки и отображения информации об аниме.
 */
public class PlayerPortraitController {

    private static final String TAG = "PlayerPortraitCtrl";

    public interface PortraitHost {
        Intent getIntent();
        String getCurrentAnimeId();
        String getAnimeUrl();
        AnimeInfoResponse getCurrentAnimeInfo();
        void setCurrentAnimeInfo(AnimeInfoResponse animeInfo);
        void setCurrentPosterUrl(String posterUrl);
        ApiService getApiService();
        EpisodesManager getEpisodesManager();
        PlayersManager getPlayersManager();
        PlayerUIBinder getUiBinder();
        PlayerOfflineController getOfflineController();
        PlayerCommentsController getCommentsController();
        PlayerAnimeInfoController getAnimeInfoController();
        PlayerChronologyController getChronologyController();
        PlayerRelatedTitlesController getRelatedTitlesController();
        PlayerSplashScreenController getSplashScreenController();
        PlayerProgressController getProgressController();
        boolean isOfflineMode();
        void markAnimeInfoLoaded();
        void showDownloadBottomSheet();
        void showDownloadProgressBottomSheet();
        void safeRunOnUiThread(Runnable runnable);
    }

    private final Activity activity;
    private final PortraitHost host;

    // Portrait views
    private NestedScrollView portraitScrollView;
    private FloatingActionButton btnPortraitScrollToTop;
    private View portraitVoiceoverPlayerButton;
    private TextView tvPortraitVoiceover;
    private TextView tvPortraitPlayer;
    private ImageView ivPortraitVoiceoverChevron;
    private ImageView portraitBookmarkButton;
    private ImageButton portraitDownloadButton;
    private RecyclerView portraitEpisodesRecyclerView;
    private TextView tvPortraitAnimeTitle;
    private TextView tvPortraitEpisodeTitle;
    private View btnPortraitSortComments;
    private TextView tvPortraitSortComments;
    private View btnPortraitCommentRules;
    private RecyclerView portraitCommentsRecyclerView;
    private TextView tvPortraitEmptyComments;
    private View portraitCommentsLoadingOverlay;
    private View portraitDownloadProgressContainer;
    private View portraitDownloadProgressCard;
    private View portraitRelatedTitlesContainer;
    private RecyclerView portraitRelatedTitlesRecyclerView;

    public PlayerPortraitController(Activity activity, PortraitHost host) {
        this.activity = activity;
        this.host = host;
    }

    public void initializePortraitViews() {
        portraitScrollView = activity.findViewById(R.id.portraitScrollView);
        btnPortraitScrollToTop = activity.findViewById(R.id.btnPortraitScrollToTop);
        portraitVoiceoverPlayerButton = activity.findViewById(R.id.portraitVoiceoverPlayerButton);
        tvPortraitVoiceover = activity.findViewById(R.id.tvPortraitVoiceover);
        tvPortraitPlayer = activity.findViewById(R.id.tvPortraitPlayer);
        ivPortraitVoiceoverChevron = activity.findViewById(R.id.ivPortraitVoiceoverChevron);
        portraitBookmarkButton = activity.findViewById(R.id.portraitBookmarkButton);
        portraitDownloadButton = activity.findViewById(R.id.portraitDownloadButton);

        portraitEpisodesRecyclerView = activity.findViewById(R.id.portraitEpisodesRecyclerView);
        tvPortraitAnimeTitle = activity.findViewById(R.id.tvPortraitAnimeTitle);
        tvPortraitEpisodeTitle = activity.findViewById(R.id.tvPortraitEpisodeTitle);
        btnPortraitSortComments = activity.findViewById(R.id.btnPortraitSortComments);
        tvPortraitSortComments = activity.findViewById(R.id.tvPortraitSortComments);
        btnPortraitCommentRules = activity.findViewById(R.id.btnPortraitCommentRules);
        portraitCommentsRecyclerView = activity.findViewById(R.id.portraitCommentsRecyclerView);
        tvPortraitEmptyComments = activity.findViewById(R.id.tvPortraitEmptyComments);
        portraitCommentsLoadingOverlay = activity.findViewById(R.id.portraitCommentsLoadingOverlay);
        portraitDownloadProgressContainer = activity.findViewById(R.id.portraitDownloadProgressContainer);
        portraitDownloadProgressCard = activity.findViewById(R.id.portraitDownloadProgressCard);
        portraitRelatedTitlesContainer = activity.findViewById(R.id.portraitRelatedTitlesContainer);
        portraitRelatedTitlesRecyclerView = activity.findViewById(R.id.portraitRelatedTitlesRecyclerView);

        boolean isOfflineMode = host.isOfflineMode();
        PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
        if (isOfflineMode) {
            if (portraitRelatedTitlesContainer != null) {
                portraitRelatedTitlesContainer.setVisibility(View.GONE);
            }
            if (animeInfoCtrl != null) {
                animeInfoCtrl.setOfflineMode(true);
            }
        }

        if (portraitDownloadProgressCard != null) {
            portraitDownloadProgressCard.setOnClickListener(v -> host.showDownloadProgressBottomSheet());
        }

        if (!isOfflineMode) {
            showPlayerTextSkeletons();
        } else {
            hideAllSkeletonsForOffline();
        }

        if (portraitVoiceoverPlayerButton != null) {
            portraitVoiceoverPlayerButton.setOnClickListener(v -> ItemAnimationUtils.animateItemClick(v, () -> {
                if (host.isOfflineMode()) {
                    CustomToast.showInfo(activity, "Выбор озвучки недоступен в офлайн режиме");
                    return;
                }
                PlayersManager pm = host.getPlayersManager();
                if (pm != null) {
                    pm.toggleMenu();
                }
            }));
        }

        ApiService apiService = host.getApiService();
        if (portraitBookmarkButton != null) {
            boolean isAuthOrOffline = isOfflineMode || (apiService != null && apiService.isAuthorized());
            portraitBookmarkButton.setAlpha(isAuthOrOffline ? 1.0f : 0.5f);
            portraitBookmarkButton.setOnClickListener(v -> {
                PlayerProgressController progressCtrl = host.getProgressController();
                if (progressCtrl != null) {
                    progressCtrl.addBookmark();
                }
            });
            portraitBookmarkButton.setOnLongClickListener(v -> {
                PlayerProgressController progressCtrl = host.getProgressController();
                if (progressCtrl != null) {
                    progressCtrl.showWatchStatusBottomSheet();
                }
                return true;
            });
        }

        if (portraitDownloadButton != null) {
            portraitDownloadButton.setOnClickListener(v -> {
                if (host.isOfflineMode()) {
                    CustomToast.showInfo(activity, "Воспроизводится скачанный файл");
                    return;
                }
                if (DownloadService.isRunning()) {
                    host.showDownloadProgressBottomSheet();
                } else {
                    host.showDownloadBottomSheet();
                }
            });
        }

        EpisodesManager episodesManager = host.getEpisodesManager();
        if (episodesManager != null && portraitEpisodesRecyclerView != null) {
            episodesManager.setPortraitEpisodesRecyclerView(portraitEpisodesRecyclerView);
            View btnPortraitSearch = activity.findViewById(R.id.btnPortraitEpisodeSearch);
            EditText etPortraitNum = activity.findViewById(R.id.etPortraitEpisodeNumber);
            if (btnPortraitSearch != null && etPortraitNum != null) {
                episodesManager.setupPortraitSearchViews(btnPortraitSearch, etPortraitNum);
            }
        }

        PlayerCommentsController commentsCtrl = host.getCommentsController();
        if (commentsCtrl != null && portraitCommentsRecyclerView != null) {
            commentsCtrl.initializePortraitViews(
                    portraitCommentsRecyclerView,
                    portraitCommentsLoadingOverlay,
                    tvPortraitEmptyComments,
                    btnPortraitSortComments,
                    tvPortraitSortComments,
                    btnPortraitCommentRules,
                    isOfflineMode
            );
        }

        if (btnPortraitScrollToTop != null) {
            btnPortraitScrollToTop.setOnClickListener(v -> {
                if (portraitScrollView != null) {
                    portraitScrollView.smoothScrollTo(0, 0);
                }
            });
        }

        if (portraitScrollView != null) {
            portraitScrollView.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                PlayerCommentsController cc = host.getCommentsController();
                if (btnPortraitScrollToTop != null) {
                    boolean imeActive = cc != null && cc.isImeVisible();
                    if (!imeActive && scrollY > 300) {
                        int childHeight = (v.getChildCount() > 0) ? v.getChildAt(0).getMeasuredHeight() : 0;
                        int scrollBottom = scrollY + v.getMeasuredHeight();
                        int threshold = (int) (80 * activity.getResources().getDisplayMetrics().density);
                        boolean isNearBottom = (childHeight > 0 && (childHeight - scrollBottom < threshold)) || !v.canScrollVertically(1);

                        if (isNearBottom) {
                            if (btnPortraitScrollToTop.getVisibility() == View.VISIBLE) {
                                btnPortraitScrollToTop.hide();
                            }
                        } else {
                            if (btnPortraitScrollToTop.getVisibility() != View.VISIBLE) {
                                btnPortraitScrollToTop.show();
                            }
                        }
                    } else {
                        if (btnPortraitScrollToTop.getVisibility() == View.VISIBLE) {
                            btnPortraitScrollToTop.hide();
                        }
                    }
                }

                if (cc != null && !host.isOfflineMode()
                        && activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                    cc.onPortraitScrollChanged(v, scrollY, oldScrollY);
                }
            });
        }
    }

    @Nullable
    public ImageView getPortraitBookmarkButton() {
        return portraitBookmarkButton;
    }

    @Nullable
    public View getPortraitRelatedTitlesContainer() {
        return portraitRelatedTitlesContainer;
    }

    @Nullable
    public RecyclerView getPortraitRelatedTitlesRecyclerView() {
        return portraitRelatedTitlesRecyclerView;
    }

    public void enableBookmarkButtons() {
        ApiService apiService = host.getApiService();
        boolean isAuthOrOffline = host.isOfflineMode() || (apiService != null && apiService.isAuthorized());
        float alpha = isAuthOrOffline ? 1.0f : 0.5f;

        PlayerUIBinder ui = host.getUiBinder();
        if (ui != null && ui.bookmarkButton != null) {
            ui.bookmarkButton.setEnabled(true);
            ui.bookmarkButton.setClickable(true);
            ui.bookmarkButton.setAlpha(alpha);
        }
        if (portraitBookmarkButton != null) {
            portraitBookmarkButton.setEnabled(true);
            portraitBookmarkButton.setClickable(true);
            portraitBookmarkButton.setFocusable(true);
            portraitBookmarkButton.setAlpha(alpha);
        }
    }

    public void applyOfflineButtonStates() {
        if (portraitDownloadButton != null) {
            portraitDownloadButton.setEnabled(false);
            portraitDownloadButton.setClickable(false);
            portraitDownloadButton.setFocusable(false);
            portraitDownloadButton.setAlpha(0.35f);
        }
        if (portraitBookmarkButton != null) {
            portraitBookmarkButton.setEnabled(true);
            portraitBookmarkButton.setClickable(true);
            portraitBookmarkButton.setFocusable(true);
            portraitBookmarkButton.setAlpha(1.0f);
        }
        if (portraitVoiceoverPlayerButton != null) {
            portraitVoiceoverPlayerButton.setEnabled(false);
            portraitVoiceoverPlayerButton.setClickable(false);
            portraitVoiceoverPlayerButton.setFocusable(false);
            portraitVoiceoverPlayerButton.setAlpha(0.6f);
        }
        if (ivPortraitVoiceoverChevron != null) {
            ivPortraitVoiceoverChevron.setVisibility(View.GONE);
        }
        if (portraitDownloadProgressContainer != null) {
            portraitDownloadProgressContainer.setVisibility(View.GONE);
        }
    }

    public void applyOfflineTitleViews(@Nullable String animeTitle, @Nullable String episodeTitle) {
        if (tvPortraitAnimeTitle != null) {
            SkeletonHelper.hideSkeleton(tvPortraitAnimeTitle, animeTitle != null ? animeTitle : "");
        }
        if (tvPortraitEpisodeTitle != null) {
            EpisodesManager em = host.getEpisodesManager();
            EpisodesListResponse.EpisodeItem curEp = em != null ? em.getCurrentEpisode() : null;
            Intent intent = host.getIntent();
            String epNum = curEp != null && curEp.getNumber() != null
                    ? curEp.getNumber()
                    : (intent != null ? intent.getStringExtra("EXTRA_EPISODE_NUMBER") : null);
            String cleanName = PlayerMetadataHelper.cleanEpisodeName(episodeTitle, epNum);
            if (!cleanName.isEmpty()) {
                SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, cleanName);
                tvPortraitEpisodeTitle.setVisibility(View.VISIBLE);
            } else {
                SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, "");
                tvPortraitEpisodeTitle.setVisibility(View.GONE);
            }
        }
    }

    public void showPlayerTextSkeletons() {
        if (host.isOfflineMode()) return;
        host.safeRunOnUiThread(() -> {
            PlayerUIBinder ui = host.getUiBinder();
            if (ui != null) {
                if (ui.animeTitleView != null) SkeletonHelper.showSkeleton(ui.animeTitleView, 160);
                if (ui.currentTeamName != null) SkeletonHelper.showSkeleton(ui.currentTeamName, 80);
                if (ui.currentEpisodeNumberView != null) SkeletonHelper.showSkeleton(ui.currentEpisodeNumberView, 65);
                if (ui.currentEpisodeName != null) SkeletonHelper.showSkeleton(ui.currentEpisodeName, 100);
            }

            if (tvPortraitAnimeTitle != null) SkeletonHelper.showSkeleton(tvPortraitAnimeTitle, 180);
            if (tvPortraitEpisodeTitle != null) SkeletonHelper.showSkeleton(tvPortraitEpisodeTitle, 120);
            if (tvPortraitVoiceover != null) SkeletonHelper.showSkeleton(tvPortraitVoiceover, 90);
            if (tvPortraitPlayer != null) SkeletonHelper.showSkeleton(tvPortraitPlayer, 60);

            PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
            if (animeInfoCtrl != null) {
                animeInfoCtrl.showSkeletons();
            }
        });
    }

    public void hideAllSkeletonsForOffline() {
        host.safeRunOnUiThread(() -> {
            Intent intent = host.getIntent();
            PlayerOfflineController offlineCtrl = host.getOfflineController();
            String animeTitle = intent != null ? intent.getStringExtra("EXTRA_ANIME_TITLE") : null;
            if ((animeTitle == null || animeTitle.isEmpty()) && offlineCtrl != null) {
                DownloadedEpisodeEntity cur = offlineCtrl.getCurrentOfflineEpisode();
                if (cur != null) animeTitle = cur.getAnimeTitle();
            }
            if (animeTitle == null) animeTitle = "";

            PlayerUIBinder ui = host.getUiBinder();
            String voiceoverName = offlineCtrl != null ? offlineCtrl.getOfflineVoiceoverName() : "Офлайн";
            String playerTypeName = offlineCtrl != null ? offlineCtrl.getOfflinePlayerTypeName() : "Офлайн";

            if (ui != null && ui.animeTitleView != null) {
                SkeletonHelper.hideSkeleton(ui.animeTitleView, animeTitle);
            }
            if (tvPortraitAnimeTitle != null) {
                SkeletonHelper.hideSkeleton(tvPortraitAnimeTitle, animeTitle);
            }
            if (ui != null && ui.currentTeamName != null) {
                SkeletonHelper.hideSkeleton(ui.currentTeamName, voiceoverName);
            }
            if (tvPortraitVoiceover != null) {
                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, voiceoverName);
            }
            if (tvPortraitPlayer != null) {
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, playerTypeName);
            }

            EpisodesManager episodesManager = host.getEpisodesManager();
            EpisodesListResponse.EpisodeItem curEp = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
            String epNum = curEp != null && curEp.getNumber() != null
                    ? curEp.getNumber()
                    : (intent != null ? intent.getStringExtra("EXTRA_EPISODE_NUMBER") : null);
            String rawName = curEp != null && curEp.getName() != null
                    ? curEp.getName()
                    : (intent != null ? intent.getStringExtra("EXTRA_EPISODE_TITLE") : null);
            String cleanName = PlayerMetadataHelper.cleanEpisodeName(rawName, epNum);

            if (ui != null && ui.currentEpisodeNumberView != null) {
                if (epNum != null && !epNum.isEmpty()) {
                    SkeletonHelper.hideSkeleton(ui.currentEpisodeNumberView, epNum + " серия");
                } else {
                    SkeletonHelper.hideSkeleton(ui.currentEpisodeNumberView, "");
                }
            }
            if (ui != null && ui.currentEpisodeName != null) {
                if (!cleanName.isEmpty()) {
                    SkeletonHelper.hideSkeleton(ui.currentEpisodeName, ", " + cleanName);
                } else {
                    SkeletonHelper.hideSkeleton(ui.currentEpisodeName, "");
                }
            }
            if (tvPortraitEpisodeTitle != null) {
                if (!cleanName.isEmpty()) {
                    SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, cleanName);
                    tvPortraitEpisodeTitle.setVisibility(View.VISIBLE);
                } else {
                    SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, "");
                    tvPortraitEpisodeTitle.setVisibility(View.GONE);
                }
            }

            PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
            if (animeInfoCtrl != null && host.isOfflineMode()) {
                animeInfoCtrl.setOfflineMode(true);
                animeInfoCtrl.hideSkeletons(animeTitle);
            }
        });
    }

    public void updatePortraitVoiceoverPlayerUI() {
        host.safeRunOnUiThread(() -> {
            if (tvPortraitVoiceover == null || tvPortraitPlayer == null) return;
            PlayerOfflineController offlineCtrl = host.getOfflineController();
            DownloadedEpisodeEntity downloadedEp = offlineCtrl != null ? offlineCtrl.getDownloadedEpisodeForActive() : null;
            boolean isPlayingDownloaded = offlineCtrl != null && offlineCtrl.isPlayingDownloadedVideo();

            if (isPlayingDownloaded && downloadedEp != null) {
                offlineCtrl.syncCurrentPlayerDataForDownloadedIfNeeded();
                String voiceoverName = (downloadedEp.getTeamName() != null && !downloadedEp.getTeamName().trim().isEmpty())
                        ? downloadedEp.getTeamName().trim() : offlineCtrl.getOfflineVoiceoverName();
                String playerTypeName = (downloadedEp.getPlayerType() != null && !downloadedEp.getPlayerType().trim().isEmpty())
                        ? PlayerMetadataHelper.capitalizeFirst(downloadedEp.getPlayerType(), offlineCtrl.getOfflinePlayerTypeName())
                        : offlineCtrl.getOfflinePlayerTypeName();

                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, voiceoverName);
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, playerTypeName);
                ItemAnimationUtils.animateTextChange(tvPortraitVoiceover);
                return;
            }

            if (host.isOfflineMode()) {
                String vo = offlineCtrl != null ? offlineCtrl.getOfflineVoiceoverName() : "Офлайн";
                String pt = offlineCtrl != null ? offlineCtrl.getOfflinePlayerTypeName() : "Офлайн";
                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, vo);
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, pt);
                ItemAnimationUtils.animateTextChange(tvPortraitVoiceover);
                return;
            }

            PlayersManager playersManager = host.getPlayersManager();
            if (playersManager == null) return;
            EpisodeResponse.PlayerData cur = playersManager.getCurrentPlayerData();
            if (cur != null) {
                String teamName = (cur.getTeam() != null && cur.getTeam().getName() != null && !cur.getTeam().getName().isEmpty())
                        ? cur.getTeam().getName()
                        : ((cur.getTranslationType() != null && cur.getTranslationType().getLabel() != null)
                                ? cur.getTranslationType().getLabel() : "Озвучка");
                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, teamName);

                String playerName = PlayerMetadataHelper.capitalizeFirst(cur.getPlayer(), "Плеер");
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, playerName);
                ItemAnimationUtils.animateTextChange(tvPortraitVoiceover);
            } else {
                SkeletonHelper.hideSkeleton(tvPortraitVoiceover, "Выбрать озвучку");
                SkeletonHelper.hideSkeleton(tvPortraitPlayer, "");
            }
        });
    }

    public void ensureAnimeTitleSet() {
        host.safeRunOnUiThread(() -> {
            PlayerUIBinder ui = host.getUiBinder();
            TextView animeTitleView = ui != null ? ui.animeTitleView : null;
            String currentText = animeTitleView != null ? animeTitleView.getText().toString() : "";
            boolean isSkeleton = animeTitleView != null && Boolean.TRUE.equals(animeTitleView.getTag(R.id.tag_skeleton_active));

            if (currentText.isEmpty() || currentText.equals("Аниме") || isSkeleton) {
                String realTitle = null;
                AnimeInfoResponse currentAnimeInfo = host.getCurrentAnimeInfo();
                if (currentAnimeInfo != null && currentAnimeInfo.getData() != null) {
                    realTitle = currentAnimeInfo.getData().getRus_name();
                    if (realTitle == null || realTitle.isEmpty()) {
                        realTitle = currentAnimeInfo.getData().getName();
                    }
                }
                Intent intent = host.getIntent();
                if ((realTitle == null || realTitle.isEmpty()) && intent != null) {
                    realTitle = intent.getStringExtra("EXTRA_ANIME_TITLE");
                    if (realTitle == null || realTitle.isEmpty()) {
                        realTitle = intent.getStringExtra("anime_title");
                    }
                }
                if (realTitle != null && !realTitle.isEmpty() && !realTitle.equals("Аниме")) {
                    if (animeTitleView != null) {
                        SkeletonHelper.hideSkeleton(animeTitleView, realTitle);
                    }
                    updatePortraitHeaderTitlesUI();
                }
            }
        });
    }

    public void updatePortraitHeaderTitlesUI() {
        host.safeRunOnUiThread(() -> {
            PlayerUIBinder ui = host.getUiBinder();
            TextView animeTitleView = ui != null ? ui.animeTitleView : null;
            PlayerOfflineController offlineCtrl = host.getOfflineController();
            AnimeInfoResponse currentAnimeInfo = host.getCurrentAnimeInfo();
            Intent intent = host.getIntent();
            boolean isOfflineMode = host.isOfflineMode();
            boolean isPlayingDownloaded = offlineCtrl != null && offlineCtrl.isPlayingDownloadedVideo();

            if (tvPortraitAnimeTitle != null) {
                String animeTitleStr = "";
                if (currentAnimeInfo != null && currentAnimeInfo.getData() != null) {
                    animeTitleStr = currentAnimeInfo.getData().getRus_name();
                    if (animeTitleStr == null || animeTitleStr.trim().isEmpty()) {
                        animeTitleStr = currentAnimeInfo.getData().getName();
                    }
                }
                if ((animeTitleStr == null || animeTitleStr.trim().isEmpty()) && intent != null) {
                    String extra = intent.getStringExtra("EXTRA_ANIME_TITLE");
                    if (extra == null || extra.isEmpty()) {
                        extra = intent.getStringExtra("anime_title");
                    }
                    if (extra != null && !extra.trim().isEmpty()) {
                        animeTitleStr = extra.trim();
                    }
                }
                if ((animeTitleStr == null || animeTitleStr.trim().isEmpty()) && animeTitleView != null) {
                    boolean isSkeleton = Boolean.TRUE.equals(animeTitleView.getTag(R.id.tag_skeleton_active));
                    if (!isSkeleton) {
                        String viewText = animeTitleView.getText().toString().trim();
                        if (!viewText.isEmpty() && !viewText.equals("Аниме")) {
                            animeTitleStr = viewText;
                        }
                    }
                }
                if ((animeTitleStr == null || animeTitleStr.trim().isEmpty()) && isPlayingDownloaded) {
                    DownloadedEpisodeEntity dlEp = offlineCtrl.getDownloadedEpisodeForActive();
                    if (dlEp != null && dlEp.getAnimeTitle() != null && !dlEp.getAnimeTitle().trim().isEmpty()) {
                        animeTitleStr = dlEp.getAnimeTitle().trim();
                    }
                }

                if (animeTitleStr != null && !animeTitleStr.trim().isEmpty()) {
                    SkeletonHelper.hideSkeleton(tvPortraitAnimeTitle, animeTitleStr.trim());
                    tvPortraitAnimeTitle.setVisibility(View.VISIBLE);
                } else if (!isOfflineMode) {
                    SkeletonHelper.showSkeleton(tvPortraitAnimeTitle, 180);
                    tvPortraitAnimeTitle.setVisibility(View.VISIBLE);
                } else {
                    SkeletonHelper.hideSkeleton(tvPortraitAnimeTitle, "Аниме");
                    tvPortraitAnimeTitle.setVisibility(View.VISIBLE);
                }
            }

            if (tvPortraitEpisodeTitle != null) {
                EpisodesManager episodesManager = host.getEpisodesManager();
                EpisodesListResponse.EpisodeItem curEp = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
                DownloadedEpisodeEntity dlEp = offlineCtrl != null ? offlineCtrl.getDownloadedEpisodeForActive() : null;

                String rawPortraitName = (curEp != null && curEp.getName() != null && !curEp.getName().trim().isEmpty())
                        ? curEp.getName() : null;
                if ((rawPortraitName == null || rawPortraitName.isEmpty()) && dlEp != null) {
                    rawPortraitName = dlEp.getEpisodeName();
                }
                if ((rawPortraitName == null || rawPortraitName.isEmpty()) && intent != null) {
                    rawPortraitName = intent.getStringExtra("EXTRA_EPISODE_TITLE");
                    if (rawPortraitName == null || rawPortraitName.isEmpty()) {
                        rawPortraitName = intent.getStringExtra("episode_title");
                    }
                }
                String portraitEpNum = (curEp != null) ? curEp.getNumber() : null;
                if ((portraitEpNum == null || portraitEpNum.isEmpty()) && dlEp != null) {
                    portraitEpNum = dlEp.getEpisodeNumber();
                }
                if ((portraitEpNum == null || portraitEpNum.isEmpty()) && intent != null) {
                    portraitEpNum = intent.getStringExtra("EXTRA_EPISODE_NUMBER");
                }

                String cleanPortraitName = PlayerMetadataHelper.cleanEpisodeName(rawPortraitName, portraitEpNum);
                if (!cleanPortraitName.isEmpty()) {
                    SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, cleanPortraitName);
                    tvPortraitEpisodeTitle.setVisibility(View.VISIBLE);
                } else if (currentEpisodeIsNullOrUnloaded(curEp, isPlayingDownloaded, dlEp)) {
                    SkeletonHelper.showSkeleton(tvPortraitEpisodeTitle, 120);
                    tvPortraitEpisodeTitle.setVisibility(View.VISIBLE);
                } else {
                    SkeletonHelper.hideSkeleton(tvPortraitEpisodeTitle, "");
                    tvPortraitEpisodeTitle.setVisibility(View.GONE);
                }
            }
        });
    }

    private boolean currentEpisodeIsNullOrUnloaded(EpisodesListResponse.EpisodeItem curEp, boolean isPlayingDownloaded, DownloadedEpisodeEntity dlEp) {
        if (isPlayingDownloaded) return false;
        return curEp == null || (curEp.getName() == null && curEp.getNumber() == null);
    }

    public void updateEpisodeHeaderQuick() {
        host.safeRunOnUiThread(() -> {
            if (host.isOfflineMode()) {
                hideAllSkeletonsForOffline();
                return;
            }

            PlayerOfflineController offlineCtrl = host.getOfflineController();
            DownloadedEpisodeEntity downloadedEp = offlineCtrl != null ? offlineCtrl.getDownloadedEpisodeForActive() : null;
            boolean isPlayingDownloaded = offlineCtrl != null && offlineCtrl.isPlayingDownloadedVideo();

            PlayersManager playersManager = host.getPlayersManager();
            EpisodesManager episodesManager = host.getEpisodesManager();
            EpisodeResponse.PlayerData currentPlayerData = playersManager != null ? playersManager.getCurrentPlayerData() : null;
            EpisodesListResponse.EpisodeItem currentEpisode = episodesManager != null ? episodesManager.getCurrentEpisode() : null;

            String tm = (currentPlayerData != null && currentPlayerData.getTeam() != null)
                    ? currentPlayerData.getTeam().getName() : null;
            if ((tm == null || tm.isEmpty()) && isPlayingDownloaded && downloadedEp != null
                    && downloadedEp.getTeamName() != null && !downloadedEp.getTeamName().trim().isEmpty()) {
                tm = downloadedEp.getTeamName().trim();
            }

            String ep = (currentEpisode != null) ? currentEpisode.getNumber() : null;
            if ((ep == null || ep.isEmpty()) && isPlayingDownloaded && downloadedEp != null) {
                ep = downloadedEp.getEpisodeNumber();
            }

            String rawEm = (currentEpisode != null && currentEpisode.getName() != null && !Objects.equals(currentEpisode.getName(), ""))
                    ? currentEpisode.getName() : null;
            if ((rawEm == null || rawEm.isEmpty()) && downloadedEp != null) {
                rawEm = downloadedEp.getEpisodeName();
            }
            Intent intent = host.getIntent();
            if ((rawEm == null || rawEm.isEmpty()) && intent != null) {
                rawEm = intent.getStringExtra("EXTRA_EPISODE_TITLE");
                if (rawEm == null || rawEm.isEmpty()) {
                    rawEm = intent.getStringExtra("episode_title");
                }
            }
            String em = PlayerMetadataHelper.cleanEpisodeName(rawEm, ep);

            PlayerUIBinder ui = host.getUiBinder();
            if (ui != null) {
                if (ui.currentTeamName != null) {
                    if (tm != null && !tm.isEmpty()) {
                        SkeletonHelper.hideSkeleton(ui.currentTeamName, tm);
                    } else if (currentPlayerData == null && !isPlayingDownloaded) {
                        SkeletonHelper.showSkeleton(ui.currentTeamName, 80);
                    } else {
                        String fallbackVo = (isPlayingDownloaded && offlineCtrl != null) ? offlineCtrl.getOfflineVoiceoverName() : "";
                        SkeletonHelper.hideSkeleton(ui.currentTeamName, fallbackVo);
                    }
                }

                if (ui.currentEpisodeNumberView != null) {
                    if (ep != null && !ep.isEmpty()) {
                        SkeletonHelper.hideSkeleton(ui.currentEpisodeNumberView, ep + " серия");
                    } else if (currentEpisode == null && !isPlayingDownloaded) {
                        SkeletonHelper.showSkeleton(ui.currentEpisodeNumberView, 65);
                    } else {
                        SkeletonHelper.hideSkeleton(ui.currentEpisodeNumberView, "");
                    }
                }

                if (ui.currentEpisodeName != null) {
                    if (em != null && !em.isEmpty()) {
                        SkeletonHelper.hideSkeleton(ui.currentEpisodeName, ", " + em);
                    } else if (currentEpisode == null && !isPlayingDownloaded) {
                        SkeletonHelper.showSkeleton(ui.currentEpisodeName, 100);
                    } else {
                        SkeletonHelper.hideSkeleton(ui.currentEpisodeName, "");
                    }
                }

                if (isPlayingDownloaded && downloadedEp != null && ui.animeTitleView != null) {
                    boolean isTitleSkeleton = Boolean.TRUE.equals(ui.animeTitleView.getTag(R.id.tag_skeleton_active));
                    if (isTitleSkeleton && host.getCurrentAnimeInfo() == null
                            && downloadedEp.getAnimeTitle() != null && !downloadedEp.getAnimeTitle().trim().isEmpty()) {
                        SkeletonHelper.hideSkeleton(ui.animeTitleView, downloadedEp.getAnimeTitle().trim());
                    }
                }
            }

            updatePortraitVoiceoverPlayerUI();
            updatePortraitHeaderTitlesUI();
        });
    }

    public void updateAnimeInfoHeaderFull() {
        updateEpisodeHeaderQuick();

        Intent intent = host.getIntent();
        PlayerUIBinder ui = host.getUiBinder();
        TextView animeTitleView = ui != null ? ui.animeTitleView : null;

        String initialTitle = intent != null ? intent.getStringExtra("EXTRA_ANIME_TITLE") : null;
        if (initialTitle == null || initialTitle.isEmpty()) {
            initialTitle = intent != null ? intent.getStringExtra("anime_title") : null;
        }
        if (initialTitle != null && !initialTitle.isEmpty() && animeTitleView != null) {
            SkeletonHelper.hideSkeleton(animeTitleView, initialTitle);
            updatePortraitHeaderTitlesUI();
        }

        AnimeInfoResponse currentAnimeInfo = host.getCurrentAnimeInfo();
        if (currentAnimeInfo != null && currentAnimeInfo.getData() != null) {
            String rus = currentAnimeInfo.getData().getRus_name();
            if (rus == null || rus.isEmpty()) rus = currentAnimeInfo.getData().getName();
            if (rus != null && !rus.isEmpty() && animeTitleView != null) {
                SkeletonHelper.hideSkeleton(animeTitleView, rus);
            }
            updatePortraitHeaderTitlesUI();
            return;
        }

        if (animeTitleView == null) return;
        ApiService apiService = host.getApiService();
        if (apiService == null) return;

        String animeUrl = host.getAnimeUrl();
        String slugOrId = animeUrl != null ? apiService.extractAnimeSlug(animeUrl) : null;
        if (slugOrId == null && animeUrl != null) {
            slugOrId = apiService.extractAnimeId(animeUrl);
        }
        if (slugOrId == null) {
            slugOrId = host.getCurrentAnimeId();
        }
        if (slugOrId == null) return;

        apiService.fetchAnimeInfo(slugOrId, new ApiService.AnimeInfoCallback() {
            @Override
            public void onAnimeInfoReceived(AnimeInfoResponse response) {
                host.safeRunOnUiThread(() -> {
                    if (response != null && response.getData() != null) {
                        displayAnimeInfo(response);
                    }
                });
            }

            @Override
            public void onError(String error) {
                host.safeRunOnUiThread(() -> {
                    Intent in = host.getIntent();
                    String fallback = in != null ? in.getStringExtra("EXTRA_ANIME_TITLE") : null;
                    if (fallback == null || fallback.isEmpty()) {
                        fallback = in != null ? in.getStringExtra("anime_title") : null;
                    }
                    AnimeInfoResponse info = host.getCurrentAnimeInfo();
                    if ((fallback == null || fallback.isEmpty()) && info != null && info.getData() != null) {
                        fallback = info.getData().getRus_name();
                    }
                    if (fallback == null || fallback.isEmpty()) {
                        fallback = "Аниме";
                    }
                    SkeletonHelper.hideSkeleton(animeTitleView, fallback);
                    updatePortraitHeaderTitlesUI();
                    PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
                    if (animeInfoCtrl != null && host.getCurrentAnimeInfo() == null) {
                        animeInfoCtrl.showError(() -> updateAnimeInfoHeaderFull());
                    }
                });
                Log.w(TAG, "Failed to load anime title: " + error);
            }
        });
    }

    public void loadAnimeInfoForPlaceholder() {
        if (host.isOfflineMode()) return;
        AnimeInfoResponse currentAnimeInfo = host.getCurrentAnimeInfo();
        if (currentAnimeInfo != null && currentAnimeInfo.getData() != null) {
            displayAnimeInfo(currentAnimeInfo);
            return;
        }
        ApiService apiService = host.getApiService();
        if (apiService == null) return;

        String animeUrl = host.getAnimeUrl();
        String animeSlug = animeUrl != null ? apiService.extractAnimeSlug(animeUrl) : null;
        if (animeSlug == null && animeUrl != null) {
            animeSlug = apiService.extractAnimeId(animeUrl);
        }
        if (animeSlug == null) {
            animeSlug = host.getCurrentAnimeId();
        }
        if (animeSlug == null) return;

        PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
        if (animeInfoCtrl != null) {
            animeInfoCtrl.setOnRetryListener(this::loadAnimeInfoForPlaceholder);
            animeInfoCtrl.showSkeletons();
        }

        apiService.fetchAnimeInfo(animeSlug, new ApiService.AnimeInfoCallback() {
            @Override
            public void onAnimeInfoReceived(AnimeInfoResponse response) {
                host.safeRunOnUiThread(() -> displayAnimeInfo(response));
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Failed to load anime info: " + errorMessage);
                host.safeRunOnUiThread(() -> {
                    host.markAnimeInfoLoaded();
                    PlayerSplashScreenController splashCtrl = host.getSplashScreenController();
                    if (splashCtrl != null && splashCtrl.isShowing()) {
                        splashCtrl.dismissWithAnimation(null);
                    }
                    if (animeInfoCtrl != null && host.getCurrentAnimeInfo() == null) {
                        animeInfoCtrl.showError(PlayerPortraitController.this::loadAnimeInfoForPlaceholder);
                    }
                });
            }
        });
    }

    @SuppressLint("SetTextI18n")
    public void displayAnimeInfo(AnimeInfoResponse animeInfo) {
        host.markAnimeInfoLoaded();

        if (animeInfo == null || animeInfo.getData() == null) return;
        boolean alreadyDisplayed = (host.getCurrentAnimeInfo() == animeInfo);
        host.setCurrentAnimeInfo(animeInfo);

        String rus = animeInfo.getData().getRus_name();
        if (rus == null || rus.isEmpty()) rus = animeInfo.getData().getName();
        PlayerUIBinder ui = host.getUiBinder();
        if (rus != null && !rus.isEmpty() && ui != null && ui.animeTitleView != null) {
            SkeletonHelper.hideSkeleton(ui.animeTitleView, rus);
            updatePortraitHeaderTitlesUI();
        }

        if (alreadyDisplayed) {
            return;
        }

        PlayerRelatedTitlesController relatedCtrl = host.getRelatedTitlesController();
        if (relatedCtrl != null) {
            relatedCtrl.setAnimeInfoToRelatedPanel(animeInfo.getData());
        }

        PlayerAnimeInfoController animeInfoCtrl = host.getAnimeInfoController();
        if (animeInfoCtrl != null) {
            animeInfoCtrl.displayAnimeInfo(activity, animeInfo);
            if (animeInfoCtrl.getCurrentPosterUrl() != null) {
                host.setCurrentPosterUrl(animeInfoCtrl.getCurrentPosterUrl());
            }
        }

        PlayerChronologyController chronologyCtrl = host.getChronologyController();
        if (chronologyCtrl != null) {
            chronologyCtrl.loadChronologyForAnime(animeInfo);
        }

        String currentAnimeId = host.getCurrentAnimeId();
        if (relatedCtrl != null && currentAnimeId != null && !host.isOfflineMode()) {
            relatedCtrl.loadRelatedTitles(currentAnimeId, host.getAnimeUrl());
        }
    }
}
