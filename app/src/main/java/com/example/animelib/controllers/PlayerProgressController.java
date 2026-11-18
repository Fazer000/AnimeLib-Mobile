package com.example.animelib.controllers;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.OfflineBookmarkEntity;
import com.example.animelib.managers.BookmarkManager;
import com.example.animelib.managers.BookmarkSyncDispatcher;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.OfflineSyncManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.AnimeBookmarkResponse;
import com.example.animelib.models.AnimeInfoResponse;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.ui.WatchStatusBottomSheet;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.LatestViewsManager;
import com.google.gson.JsonObject;

/**
 * Контроллер для отслеживания прогресса просмотра, ручного/автоматического сохранения закладок
 * и управления статусом просмотра аниме.
 */
public class PlayerProgressController {

    private static final String TAG = "PlayerProgressCtrl";

    private final Context context;
    private final Context appContext;
    private final ApiService apiService;

    private boolean isCurrentEpisodeMarkedViewed = false;
    private boolean autoBookmarkSaved = false;
    private int lastAutoSavedEpisodeId = -1;
    private String lastAutoSavedTimecode = null;
    private Object currentWatchStatusId = null;

    // Снимок последнего валидного состояния воспроизведения, чтобы при выходе из плеера
    // позиция, эпизод, озвучка и слаг никогда не терялись даже после остановки/очистки ExoPlayer
    private EpisodesListResponse.EpisodeItem lastValidEpisode = null;
    private EpisodeResponse.PlayerData lastValidPlayer = null;
    private long lastValidPositionMs = 0L;
    private long lastValidDurationMs = 0L;
    private String lastValidMediaSlug = null;
    private String lastValidOfflineAnimeId = null;
    private boolean hasPlaybackStartedForCurrentEpisode = false;

    private final Handler viewProgressHandler = new Handler(Looper.getMainLooper());
    private Runnable viewProgressRunnable;

    public interface ProgressCallback {
        String getAnimeId();
        String getAnimeUrl();
        String getMediaSlug();
        String resolveOfflineAnimeId();
        boolean isOfflineMode();
        boolean hasUserSwitchedEpisode();
        boolean hasRenderedFirstFrame();
        Player getPlayer();
        EpisodesManager getEpisodesManager();
        PlayersManager getPlayersManager();
        ImageView getBookmarkButton();
        ImageView getPortraitBookmarkButton();
        Object getCurrentWatchStatusId();
        void saveLatestViewOnExit();
        void safeRunOnUiThread(Runnable runnable);
    }

    private ProgressCallback callback;

    public PlayerProgressController(Context context, ApiService apiService) {
        this.context = context;
        this.appContext = (context != null && context.getApplicationContext() != null)
                ? context.getApplicationContext()
                : context;
        this.apiService = apiService;
    }

    public void setCallback(ProgressCallback callback) {
        this.callback = callback;
    }

    public Object getCurrentWatchStatusId() {
        return currentWatchStatusId;
    }

    public void updatePortraitWatchStatusUI(Object statusId) {
        this.currentWatchStatusId = statusId;
    }

    public boolean isCurrentEpisodeMarkedViewed() {
        return isCurrentEpisodeMarkedViewed;
    }

    public void setCurrentEpisodeMarkedViewed(boolean viewed) {
        this.isCurrentEpisodeMarkedViewed = viewed;
    }

    public boolean isAutoBookmarkSaved() {
        return autoBookmarkSaved;
    }

    public void setAutoBookmarkSaved(boolean saved) {
        this.autoBookmarkSaved = saved;
    }

    public void onPlaybackReady() {
        hasPlaybackStartedForCurrentEpisode = true;
        capturePlaybackSnapshot();
    }

    public long getLastValidPositionMsForEpisode(int episodeId) {
        if (lastValidEpisode != null && lastValidEpisode.getId() == episodeId && lastValidPositionMs > 0) {
            return lastValidPositionMs;
        }
        return 0L;
    }

    public long getLastValidDurationMsForEpisode(int episodeId) {
        if (lastValidEpisode != null && lastValidEpisode.getId() == episodeId && lastValidDurationMs > 0) {
            return lastValidDurationMs;
        }
        return 0L;
    }

    @Nullable
    public EpisodesListResponse.EpisodeItem getLastValidEpisode() {
        return lastValidEpisode;
    }

    @Nullable
    public EpisodeResponse.PlayerData getLastValidPlayer() {
        return lastValidPlayer;
    }

    public void resetProgressForNewEpisode() {
        isCurrentEpisodeMarkedViewed = false;
        autoBookmarkSaved = false;
        lastAutoSavedEpisodeId = -1;
        lastAutoSavedTimecode = null;
        lastValidEpisode = null;
        lastValidPositionMs = 0L;
        lastValidDurationMs = 0L;
        hasPlaybackStartedForCurrentEpisode = false;
    }

    public void resetForNewAnime() {
        resetProgressForNewEpisode();
        lastValidPlayer = null;
        lastValidMediaSlug = null;
        lastValidOfflineAnimeId = null;
        currentWatchStatusId = null;
    }

    public void capturePlaybackSnapshot() {
        if (callback == null) return;

        EpisodesManager em = callback.getEpisodesManager();
        if (em != null && em.getCurrentEpisode() != null) {
            EpisodesListResponse.EpisodeItem curEp = em.getCurrentEpisode();
            if (lastValidEpisode != null && lastValidEpisode.getId() != curEp.getId()) {
                lastValidPositionMs = 0L;
                lastValidDurationMs = 0L;
            }
            lastValidEpisode = curEp;
        }

        PlayersManager pm = callback.getPlayersManager();
        if (pm != null && pm.getCurrentPlayerData() != null) {
            lastValidPlayer = pm.getCurrentPlayerData();
        }

        String slug = callback.getMediaSlug();
        if (slug != null && !slug.trim().isEmpty()) {
            lastValidMediaSlug = slug.trim();
        }

        String offlineId = callback.resolveOfflineAnimeId();
        if (offlineId != null && !offlineId.trim().isEmpty()) {
            lastValidOfflineAnimeId = offlineId.trim();
        }

        if (callback.hasRenderedFirstFrame()) {
            hasPlaybackStartedForCurrentEpisode = true;
        }

        Player p = callback.getPlayer();
        if (p != null) {
            long pos = p.getCurrentPosition();
            long dur = p.getDuration();
            if (pos > 0) {
                lastValidPositionMs = pos;
                hasPlaybackStartedForCurrentEpisode = true;
            }
            if (dur > 0) {
                lastValidDurationMs = dur;
            }
        }
    }

    public void startViewProgressTracking() {
        stopViewProgressTracking();
        viewProgressRunnable = new Runnable() {
            @Override
            public void run() {
                capturePlaybackSnapshot();
                checkPlaybackViewProgress();
                if (callback != null) {
                    Player p = callback.getPlayer();
                    if (p != null && p.isPlaying()) {
                        viewProgressHandler.postDelayed(this, 1000);
                    }
                }
            }
        };
        viewProgressHandler.post(viewProgressRunnable);
    }

    public void stopViewProgressTracking() {
        capturePlaybackSnapshot();
        if (viewProgressRunnable != null) {
            viewProgressHandler.removeCallbacks(viewProgressRunnable);
            viewProgressRunnable = null;
        }
    }

    private void checkPlaybackViewProgress() {
        if (isCurrentEpisodeMarkedViewed || callback == null) return;
        Player p = callback.getPlayer();
        if (p == null) return;

        long duration = p.getDuration();
        long currentPos = p.getCurrentPosition();

        if (duration > 0 && currentPos >= (long) (duration * 0.60)) {
            isCurrentEpisodeMarkedViewed = true;
            Log.d(TAG, "60% view threshold reached: " + currentPos + "/" + duration + "ms");

            String animeId = callback.getAnimeId();

            int playerId = 0;
            PlayersManager pm = callback.getPlayersManager();
            if (pm != null && pm.getCurrentPlayerData() != null) {
                playerId = pm.getCurrentPlayerData().getId();
            } else if (lastValidPlayer != null) {
                playerId = lastValidPlayer.getId();
            }

            if (animeId != null && !animeId.isEmpty() && playerId > 0) {
                Log.d(TAG, "Enqueuing VIEW task for animeId: " + animeId + ", playerId: " + playerId);
                OfflineSyncManager.getInstance(appContext).enqueueViewTask(animeId, playerId);
            }
        }
    }

    public void enableBookmarkButton() {
        if (callback == null) return;
        boolean isAuthOrOffline = callback.isOfflineMode() || (apiService != null && apiService.isAuthorized());
        float alpha = isAuthOrOffline ? 1.0f : 0.5f;
        ImageView bmBtn = callback.getBookmarkButton();
        if (bmBtn != null) {
            bmBtn.setEnabled(true);
            bmBtn.setClickable(true);
            bmBtn.setAlpha(alpha);
        }
        ImageView portraitBmBtn = callback.getPortraitBookmarkButton();
        if (portraitBmBtn != null) {
            portraitBmBtn.setEnabled(true);
            portraitBmBtn.setClickable(true);
            portraitBmBtn.setFocusable(true);
            portraitBmBtn.setAlpha(alpha);
        }
    }

    public void addBookmark() {
        if (callback == null) return;
        Log.d(TAG, "Add bookmark method called");

        EpisodesManager em = callback.getEpisodesManager();
        EpisodesListResponse.EpisodeItem currentEpisode = em != null ? em.getCurrentEpisode() : null;
        if (currentEpisode == null) {
            currentEpisode = lastValidEpisode;
        }
        if (currentEpisode == null || currentEpisode.getId() <= 0) {
            Log.e(TAG, "Current episode is null, cannot add bookmark");
            CustomToast.showWarning(context, "Не удалось добавить закладку: эпизод не выбран");
            return;
        }

        Player p = callback.getPlayer();
        long livePosition = p != null ? p.getCurrentPosition() : 0L;
        long currentPosition = livePosition > 0
                ? livePosition
                : getLastValidPositionMsForEpisode(currentEpisode.getId());
        final String timecode = ApiService.formatTimecode(currentPosition);

        if (callback.isOfflineMode()) {
            String animeId = callback.resolveOfflineAnimeId();
            DatabaseManager dbMgr = BookmarkSyncDispatcher.getInstance(context).getAppDatabaseManager();
            if (dbMgr == null && apiService != null) {
                dbMgr = apiService.getDatabaseManager();
            }

            if (animeId != null && !animeId.trim().isEmpty() && dbMgr != null) {
                final EpisodesListResponse.EpisodeItem savedEp = currentEpisode;
                final String finalAnimeId = animeId.trim();
                final DatabaseManager finalDbMgr = dbMgr;
                dbMgr.saveOfflineBookmark(
                        finalAnimeId,
                        savedEp.getId(),
                        savedEp.getNumber(),
                        timecode,
                        currentPosition,
                        () -> {
                            syncOfflineBookmarkWithApiOrWorker(finalAnimeId, savedEp, timecode, finalDbMgr);
                            callback.safeRunOnUiThread(() -> {
                                autoBookmarkSaved = true;
                                updateBookmarkButtonColor(true);

                                AnimeBookmarkResponse.BookmarkData offlineBookmark = new AnimeBookmarkResponse.BookmarkData();
                                offlineBookmark.setItemId(savedEp.getId());
                                offlineBookmark.setProgress(timecode);
                                if (em != null) {
                                    em.updateBookmarkInAdapter(offlineBookmark);
                                }

                                CustomToast.showSuccess(context, "Закладка сохранена (" + timecode + ")");
                            });
                        },
                        () -> callback.safeRunOnUiThread(() -> {
                            updateBookmarkButtonColor(false);
                            CustomToast.showWarning(context, "Не удалось добавить закладку");
                        })
                );
            } else {
                CustomToast.showWarning(context, "Не удалось добавить закладку");
            }
            return;
        }

        boolean authorized = (apiService != null && apiService.isAuthorized())
                || BookmarkSyncDispatcher.getInstance(context).isAuthorized();
        if (!authorized) {
            CustomToast.showWarning(context, "Без авторизации поставить закладку нельзя");
            return;
        }

        PlayersManager pm = callback.getPlayersManager();
        EpisodeResponse.PlayerData currentPlayer = pm != null ? pm.getCurrentPlayerData() : null;
        if (currentPlayer == null) {
            currentPlayer = lastValidPlayer;
        }

        String mediaSlug = callback.getMediaSlug();
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            Log.e(TAG, "Media slug is null, cannot add bookmark");
            CustomToast.showWarning(context, "Не удалось добавить закладку: не определено аниме");
            return;
        }

        Object effectiveStatus = currentWatchStatusId != null ? currentWatchStatusId : callback.getCurrentWatchStatusId();
        if (effectiveStatus == null && em != null && em.getAnimeBookmark() != null && em.getAnimeBookmark().getStatus() > 0) {
            effectiveStatus = em.getAnimeBookmark().getStatus();
        }

        BookmarkManager bmManager = (em != null && em.getBookmarkManager() != null)
                ? em.getBookmarkManager()
                : new BookmarkManager(context, apiService);

        bmManager.addBookmark(
                mediaSlug,
                currentPlayer,
                currentEpisode,
                currentPosition,
                effectiveStatus,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        updateBookmarkButtonColor(true);

                        AnimeBookmarkResponse.BookmarkData updatedBookmark = new AnimeBookmarkResponse.BookmarkData();
                        updatedBookmark.setItemId(episodeId);
                        updatedBookmark.setProgress(timecode);
                        if (em != null) {
                            em.updateBookmarkInAdapter(updatedBookmark);
                        }
                        updateEpisodesListAfterBookmark();
                    }

                    @Override
                    public void onBookmarkError(String error) {
                        updateBookmarkButtonColor(false);
                    }
                },
                true
        );
    }

    public void fetchAndApplyWatchStatus() {
        if (callback == null || callback.isOfflineMode() || apiService == null) return;
        String mediaSlug = callback.getMediaSlug();
        if (mediaSlug != null && !mediaSlug.isEmpty()) {
            apiService.fetchAnimeBookmark(mediaSlug, new ApiService.AnimeBookmarkCallback() {
                @Override
                public void onBookmarkReceived(AnimeBookmarkResponse response) {
                    if (response != null && response.getData() != null) {
                        int status = response.getData().getStatus();
                        if (status > 0) {
                            callback.safeRunOnUiThread(() -> updatePortraitWatchStatusUI(status));
                        }
                    }
                }

                @Override
                public void onError(String error) {
                    Log.d(TAG, "Error fetching watch status: " + error);
                }
            });
        }
    }

    public void showWatchStatusBottomSheet() {
        if (callback == null) return;
        if (apiService == null || !apiService.isAuthorized()) {
            CustomToast.showWarning(context, "Для изменения статуса просмотра необходимо авторизоваться");
            return;
        }
        if (callback.isOfflineMode()) {
            CustomToast.showInfo(context, "Смена статуса недоступна в офлайн режиме");
            return;
        }

        String mediaSlug = callback.getMediaSlug();
        if (mediaSlug == null || mediaSlug.isEmpty()) {
            CustomToast.showWarning(context, "Не удалось определить аниме для обновления статуса");
            return;
        }

        final String finalMediaSlug = mediaSlug;
        EpisodesManager em = callback.getEpisodesManager();
        WatchStatusBottomSheet bottomSheet = new WatchStatusBottomSheet(context, currentWatchStatusId, item -> {
            updatePortraitWatchStatusUI(item.getId());
            apiService.updateWatchStatus(finalMediaSlug, item.getId(), new ApiService.BookmarkCallback() {
                @Override
                public void onSuccess(String message) {
                    if (em != null && em.getBookmarkManager() != null) {
                        em.getBookmarkManager().invalidateCache(finalMediaSlug);
                    }
                    CustomToast.showSuccess(context, "Статус изменен: " + item.getLabel());
                }

                @Override
                public void onError(String error) {
                    CustomToast.showWarning(context, "Не удалось изменить статус просмотра");
                }
            });
        });
        bottomSheet.show();
    }

    public void autoSaveBookmark() {
        if (callback == null) return;

        capturePlaybackSnapshot();
        saveLatestViewOnExit();

        EpisodesManager em = callback.getEpisodesManager();
        EpisodesListResponse.EpisodeItem currentEpisode = (em != null && em.getCurrentEpisode() != null)
                ? em.getCurrentEpisode()
                : lastValidEpisode;
        if (currentEpisode == null || currentEpisode.getId() <= 0) return;

        Player p = callback.getPlayer();
        long livePos = p != null ? p.getCurrentPosition() : 0L;
        long currentPosition = livePos > 0
                ? livePos
                : getLastValidPositionMsForEpisode(currentEpisode.getId());

        boolean playbackReady = hasPlaybackStartedForCurrentEpisode || callback.hasRenderedFirstFrame();
        AnimeBookmarkResponse.BookmarkData existingBookmark = em != null ? em.getAnimeBookmark() : null;
        boolean switchedToNewEpisode = callback.hasUserSwitchedEpisode()
                && (existingBookmark == null || existingBookmark.getItemId() != currentEpisode.getId());

        if (currentPosition < 1000 && !(switchedToNewEpisode && playbackReady)) {
            return;
        }

        final long safePosition = Math.max(0L, currentPosition);
        final String timecode = ApiService.formatTimecode(safePosition);

        boolean isOffline = callback.isOfflineMode();
        if (isOffline) {
            String animeId = callback.resolveOfflineAnimeId();
            if (animeId == null || animeId.trim().isEmpty()) {
                animeId = lastValidOfflineAnimeId != null ? lastValidOfflineAnimeId : callback.getAnimeId();
            }
            if (animeId == null || animeId.trim().isEmpty()) {
                showAutoSaveErrorToast("Не удалось сохранить закладку");
                return;
            }
            DatabaseManager dbMgr = appContext != null
                    ? BookmarkSyncDispatcher.getInstance(appContext).getAppDatabaseManager()
                    : (apiService != null ? apiService.getDatabaseManager() : null);
            if (dbMgr != null) {
                if (lastAutoSavedEpisodeId == currentEpisode.getId() && timecode.equals(lastAutoSavedTimecode)) {
                    return;
                }
                lastAutoSavedEpisodeId = currentEpisode.getId();
                lastAutoSavedTimecode = timecode;
                final EpisodesListResponse.EpisodeItem savedEp = currentEpisode;
                final String finalAnimeId = animeId.trim();
                final DatabaseManager finalDbMgr = dbMgr;
                dbMgr.saveOfflineBookmark(
                        finalAnimeId,
                        savedEp.getId(),
                        savedEp.getNumber(),
                        timecode,
                        safePosition,
                        () -> {
                            autoBookmarkSaved = true;
                            syncOfflineBookmarkWithApiOrWorker(finalAnimeId, savedEp, timecode, finalDbMgr);
                        },
                        () -> {
                            lastAutoSavedEpisodeId = -1;
                            lastAutoSavedTimecode = null;
                            showAutoSaveErrorToast("Не удалось сохранить закладку");
                        }
                );
            } else {
                showAutoSaveErrorToast("Не удалось сохранить закладку");
            }
            return;
        }

        boolean authorized = (apiService != null && apiService.isAuthorized())
                || (appContext != null && BookmarkSyncDispatcher.getInstance(appContext).isAuthorized());
        if (!authorized) {
            return;
        }

        String mediaSlug = callback.getMediaSlug();
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            mediaSlug = lastValidMediaSlug;
        }
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            String animeUrl = callback.getAnimeUrl();
            mediaSlug = (animeUrl != null && !animeUrl.isEmpty()) ? ApiService.extractMediaSlugFromUrl(animeUrl) : null;
        }
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            showAutoSaveErrorToast("Не удалось сохранить закладку: не определено аниме");
            return;
        }

        PlayersManager pm = callback.getPlayersManager();
        EpisodeResponse.PlayerData currentPlayer = (pm != null && pm.getCurrentPlayerData() != null)
                ? pm.getCurrentPlayerData()
                : lastValidPlayer;

        if (lastAutoSavedEpisodeId == currentEpisode.getId() && timecode.equals(lastAutoSavedTimecode)) {
            return;
        }
        lastAutoSavedEpisodeId = currentEpisode.getId();
        lastAutoSavedTimecode = timecode;

        Object statusId = currentWatchStatusId != null ? currentWatchStatusId : callback.getCurrentWatchStatusId();
        if (statusId == null && existingBookmark != null && existingBookmark.getStatus() > 0) {
            statusId = existingBookmark.getStatus();
        }

        BookmarkManager bmManager = (em != null && em.getBookmarkManager() != null)
                ? em.getBookmarkManager()
                : (appContext != null ? new BookmarkManager(appContext, apiService) : null);
        if (bmManager == null) {
            lastAutoSavedEpisodeId = -1;
            lastAutoSavedTimecode = null;
            return;
        }

        bmManager.addBookmark(
                mediaSlug,
                currentPlayer,
                currentEpisode,
                safePosition,
                statusId,
                new BookmarkManager.BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int episodeId) {
                        autoBookmarkSaved = true;
                    }

                    @Override
                    public void onBookmarkError(String error) {
                        Log.e(TAG, "Failed to auto-save bookmark: " + error);
                        lastAutoSavedEpisodeId = -1;
                        lastAutoSavedTimecode = null;
                    }
                },
                false
        );
    }

    private void syncOfflineBookmarkWithApiOrWorker(String animeId,
                                                    EpisodesListResponse.EpisodeItem episode,
                                                    String timecode,
                                                    DatabaseManager dbMgr) {
        Context effectiveCtx = this.appContext != null ? this.appContext : this.context;
        if (effectiveCtx == null || episode == null) {
            return;
        }
        boolean authorized = (apiService != null && apiService.isAuthorized())
                || BookmarkSyncDispatcher.getInstance(effectiveCtx).isAuthorized();
        if (!authorized) {
            return;
        }

        int parsedEpNumber = parseEpisodeNumber(episode.getNumber());
        DownloadedEpisodeEntity downloadedEp = dbMgr != null
                ? dbMgr.findDownloadedEpisode(animeId, episode.getId(), episode.getNumber(), null)
                : null;
        int realEpisodeId = (downloadedEp != null)
                ? (downloadedEp.getEpisodeId() > 0 ? downloadedEp.getEpisodeId() : 0)
                : (episode.getId() > 0 ? episode.getId() : 0);

        String mediaSlug = callback != null ? callback.getMediaSlug() : null;
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            mediaSlug = lastValidMediaSlug;
        }
        Object statusId = currentWatchStatusId != null
                ? currentWatchStatusId
                : (callback != null ? callback.getCurrentWatchStatusId() : null);

        OfflineSyncManager.getInstance(effectiveCtx).handleOfflinePlayerBookmark(
                animeId,
                mediaSlug,
                realEpisodeId,
                0,
                parsedEpNumber,
                timecode,
                statusId
        );
    }

    private static int parseEpisodeNumber(@Nullable String rawNumber) {
        if (rawNumber == null || rawNumber.trim().isEmpty()) {
            return 1;
        }
        try {
            int parsed = Integer.parseInt(rawNumber.trim());
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            String digits = rawNumber.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    int parsed = Integer.parseInt(digits);
                    if (parsed > 0) return parsed;
                } catch (NumberFormatException ignored2) {}
            }
        }
        return 1;
    }

    private void showAutoSaveErrorToast(String message) {
        final Context ctx = this.appContext != null ? this.appContext : this.context;
        if (ctx == null) return;
        viewProgressHandler.post(() -> CustomToast.showWarning(ctx, message));
    }

    public void saveLatestViewOnExit() {
        if (callback != null) {
            callback.saveLatestViewOnExit();
        }
    }

    public void updateBookmarkButtonColor(boolean isBookmarked) {
        if (callback == null) return;
        callback.safeRunOnUiThread(() -> {
            boolean isAuthOrOffline = callback.isOfflineMode() || (apiService != null && apiService.isAuthorized());
            float alpha = isAuthOrOffline ? 1.0f : 0.5f;

            ImageView bmBtn = callback.getBookmarkButton();
            if (bmBtn != null) {
                bmBtn.setEnabled(true);
                bmBtn.setClickable(true);
                bmBtn.setAlpha(alpha);
                int targetColor = isBookmarked ? context.getResources().getColor(R.color.bookmark_color) : context.getResources().getColor(R.color.white_color);
                animateImageColorFilter(bmBtn, targetColor);
            }

            ImageView portraitBmBtn = callback.getPortraitBookmarkButton();
            if (portraitBmBtn != null) {
                portraitBmBtn.setEnabled(true);
                portraitBmBtn.setClickable(true);
                portraitBmBtn.setFocusable(true);
                portraitBmBtn.setAlpha(alpha);
                int targetColor = isBookmarked ? context.getResources().getColor(R.color.bookmark_color) : context.getResources().getColor(R.color.primary_text_color);
                animateImageColorFilter(portraitBmBtn, targetColor);
            }
        });
    }

    public void updateEpisodesListAfterBookmark() {
        if (callback == null) return;
        callback.safeRunOnUiThread(() -> {
            EpisodesManager em = callback.getEpisodesManager();
            if (em == null) return;

            if (callback.isOfflineMode()) {
                String animeId = callback.resolveOfflineAnimeId();
                if (animeId != null && apiService != null && apiService.getDatabaseManager() != null) {
                    OfflineBookmarkEntity bm = apiService.getDatabaseManager().getOfflineBookmarkSync(animeId);
                    if (bm != null) {
                        AnimeBookmarkResponse.BookmarkData bookmarkData = new AnimeBookmarkResponse.BookmarkData();
                        bookmarkData.setItemId(bm.getEpisodeId());
                        bookmarkData.setProgress(bm.getTimecode());
                        em.updateBookmarkInAdapter(bookmarkData);
                    }
                }
                return;
            }

            String mediaSlug = callback.getMediaSlug();
            if (mediaSlug != null && !mediaSlug.isEmpty()) {
                em.getBookmarkManager().fetchAnimeBookmark(mediaSlug, new BookmarkManager.AnimeBookmarkCallback() {
                    @Override
                    public void onBookmarkReceived(AnimeBookmarkResponse response) {
                        callback.safeRunOnUiThread(() -> {
                            if (response != null && response.getData() != null) {
                                em.updateBookmarkInAdapter(response.getData());
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Failed to update bookmark in episodes list: " + error);
                    }
                });
            }
        });
    }

    private static class ColorAnimHolder {
        int color;
        ValueAnimator animator;
    }

    private void animateImageColorFilter(ImageView imageView, int targetColor) {
        if (imageView == null) return;
        ColorAnimHolder holder = (ColorAnimHolder) imageView.getTag();
        if (holder == null) {
            holder = new ColorAnimHolder();
            holder.color = targetColor;
            imageView.setTag(holder);
            imageView.setColorFilter(targetColor);
            return;
        }

        if (holder.animator != null) {
            holder.animator.cancel();
            holder.animator = null;
        }

        if (holder.color == targetColor) {
            imageView.setColorFilter(targetColor);
            return;
        }

        final ColorAnimHolder finalHolder = holder;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), holder.color, targetColor);
        anim.setDuration(220);
        anim.addUpdateListener(animation -> {
            int val = (int) animation.getAnimatedValue();
            finalHolder.color = val;
            imageView.setColorFilter(val);
        });
        holder.animator = anim;
        anim.start();
    }

    public void saveLatestViewOnExit(
            AnimeInfoResponse currentAnimeInfo,
            String currentAnimeId,
            String animeUrl,
            String intentAnimeTitle,
            EpisodesListResponse.EpisodeItem currentEpisode,
            EpisodeResponse.PlayerData currentPlayer,
            long currentPosition,
            long duration
    ) {
        EpisodesListResponse.EpisodeItem ep = currentEpisode != null ? currentEpisode : lastValidEpisode;
        if (context == null || ep == null) return;

        long effectivePos = currentPosition > 0 ? currentPosition : getLastValidPositionMsForEpisode(ep.getId());
        long effectiveDur = duration > 0 ? duration : getLastValidDurationMsForEpisode(ep.getId());
        if (effectivePos < 1000) return;

        EpisodeResponse.PlayerData effPlayer = currentPlayer != null ? currentPlayer : lastValidPlayer;

        try {
            JsonObject viewObj = new JsonObject();

            JsonObject mediaObj = new JsonObject();
            if (currentAnimeInfo != null && currentAnimeInfo.getData() != null) {
                AnimeInfoResponse.Data data = currentAnimeInfo.getData();
                mediaObj.addProperty("id", data.getId());
                mediaObj.addProperty("name", data.getName() != null ? data.getName() : (data.getRus_name() != null ? data.getRus_name() : "Anime"));
                mediaObj.addProperty("rus_name", data.getRus_name() != null ? data.getRus_name() : "");
                mediaObj.addProperty("eng_name", data.getEng_name() != null ? data.getEng_name() : "");

                String slugUrl = data.getSlug_url() != null ? data.getSlug_url() : "";
                mediaObj.addProperty("slug_url", slugUrl);

                String slug = slugUrl;
                if (slugUrl.contains("--")) {
                    String[] parts = slugUrl.split("--");
                    if (parts.length > 1) slug = parts[1];
                }
                mediaObj.addProperty("slug", slug);

                JsonObject coverObj = new JsonObject();
                if (data.getCover() != null) {
                    coverObj.addProperty("filename", data.getCover().getFilename() != null ? data.getCover().getFilename() : "");
                    coverObj.addProperty("thumbnail", data.getCover().getThumbnail() != null ? data.getCover().getThumbnail() : "");
                    coverObj.addProperty("default", data.getCover().getDefaultUrl() != null ? data.getCover().getDefaultUrl() : "");
                    coverObj.addProperty("md", data.getCover().getMd() != null ? data.getCover().getMd() : "");
                }
                mediaObj.add("cover", coverObj);
                mediaObj.addProperty("site", 5);
                mediaObj.addProperty("model", "anime");
            } else {
                int animeIdInt = 0;
                String activeAnimeUrl = animeUrl;

                if (currentAnimeId != null) {
                    String extractedNumeric = VideoUrlHelper.extractAnimeId(currentAnimeId);
                    if (extractedNumeric != null) {
                        try { animeIdInt = Integer.parseInt(extractedNumeric); } catch (Exception ignored) {}
                    } else {
                        try { animeIdInt = Integer.parseInt(currentAnimeId); } catch (Exception ignored) {}
                    }
                }
                if (animeIdInt == 0 && activeAnimeUrl != null) {
                    String extracted = apiService != null ? apiService.extractAnimeId(activeAnimeUrl) : null;
                    if (extracted != null) {
                        try { animeIdInt = Integer.parseInt(extracted); } catch (Exception ignored) {}
                    }
                }

                String titleStr = (intentAnimeTitle != null) ? intentAnimeTitle : "Anime";
                String slugUrlStr = lastValidMediaSlug != null
                        ? lastValidMediaSlug
                        : (activeAnimeUrl != null ? ApiService.extractMediaSlugFromUrl(activeAnimeUrl) : "anime");

                mediaObj.addProperty("id", animeIdInt);
                mediaObj.addProperty("name", titleStr);
                mediaObj.addProperty("rus_name", titleStr);
                mediaObj.addProperty("eng_name", titleStr);
                mediaObj.addProperty("slug", slugUrlStr != null ? slugUrlStr : "anime");
                mediaObj.addProperty("slug_url", slugUrlStr != null ? slugUrlStr : "anime");
                JsonObject coverObj = new JsonObject();
                mediaObj.add("cover", coverObj);
                mediaObj.addProperty("site", 5);
                mediaObj.addProperty("model", "anime");
            }
            viewObj.add("media", mediaObj);

            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("id", ep.getId());
            itemObj.addProperty("number", ep.getNumber() != null ? ep.getNumber() : "1");
            viewObj.add("item", itemObj);

            JsonObject progressObj = new JsonObject();
            progressObj.addProperty("current", ApiService.formatTimecode(effectivePos));
            progressObj.addProperty("total", ApiService.formatTimecode(effectiveDur));
            double percent = effectiveDur > 0 ? (double) Math.round((effectivePos * 100.0 / effectiveDur) * 100.0) / 100.0 : 0.0;
            progressObj.addProperty("percent", percent);
            viewObj.add("progress", progressObj);

            JsonObject metaObj = new JsonObject();
            int teamId = (effPlayer != null && effPlayer.getTeam() != null) ? effPlayer.getTeam().getId() : 0;
            int transType = (effPlayer != null && effPlayer.getTranslationType() != null) ? effPlayer.getTranslationType().getId() : 1;
            String playerStr = (effPlayer != null && effPlayer.getPlayer() != null) ? effPlayer.getPlayer() : "Animelib";

            metaObj.addProperty("team", teamId);
            metaObj.addProperty("translation_type", transType);
            metaObj.addProperty("player", playerStr);
            metaObj.addProperty("episode", ep.getId());
            viewObj.add("meta", metaObj);

            Context targetCtx = appContext != null ? appContext : context;
            LatestViewsManager.saveLatestView(targetCtx, viewObj);
            Log.d(TAG, "Saved latest-view on exit for media: " + mediaObj.get("name").getAsString());

        } catch (Exception e) {
            Log.e(TAG, "Error saving latest view on exit", e);
        }
    }
}
