package com.example.animelib.controllers;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.example.animelib.api.ApiService;
import com.example.animelib.api.KodikLinksExtractor;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;
import com.example.animelib.data.entity.PlayerPreferences;
import com.example.animelib.managers.EpisodesManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.managers.TimecodeManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.models.KodikResponse;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.AutoQualityHelper;
import com.example.animelib.util.CustomToast;
import com.example.animelib.util.FloatingBottomSheetUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Контроллер выбора, разрешения и переключения качества видеопотока (AnimeLib, Kodik HLS, скачанные файлы),
 * а также мониторинга зависания буферизации и авто-понижения качества в режиме «Авто».
 */
public class PlayerQualityController {

    private static final String TAG = "PlayerQualityCtrl";

    public interface QualityCallback {
        void onQualityChanged(String newQuality, String newVideoUrl, boolean isHls);
        void onError(String title, String message, Runnable retryAction);
    }

    public interface QualityHost {
        Context getContext();
        ApiService getApiService();
        PlayersManager getPlayersManager();
        EpisodesManager getEpisodesManager();
        TimecodeManager getTimecodeManager();
        PlayerPlaybackController getPlaybackController();
        PlayerControlsOverlayManager getControlsOverlayManager();
        PlayerDialogsController getDialogsController();
        PlayerDownloadController getDownloadController();
        PlayerOfflineController getOfflineController();
        PlayerVideoResolverController getVideoResolverController();
        PlayerPanelsController getPanelsController();
        PlayerSubtitlesController getSubtitlesController();
        PlayerPortraitController getPortraitController();
        PlayerUIBinder getUiBinder();
        boolean isOfflineMode();
        boolean isPlayingDownloadedVideo();
        boolean isFinishingOrDestroyed();
        void showLoading(String message);
        void hideLoading();
        void onEpisodeHeaderUpdateQuick();
        void onAnimeInfoHeaderFull();
        void stopCurrentPlaybackAndRestartVoiceover(EpisodeResponse.PlayerData playerData);
        void setSavedPlayerPosition(long pos);
        void safeRunOnUiThread(Runnable runnable);
    }

    private QualityCallback callback;
    private QualityHost host;

    private String preferredQuality = "Авто";
    private boolean isUsingDownloadedSource = false;
    private String activePlayingQuality = null;
    private volatile boolean isPreferredQualityLoadedFromDb = false;
    private volatile boolean isPreferredQualityChangedByUser = false;
    private boolean enable4K = false;
    private String currentVideoUrl = null;
    private String currentVideoDomain = VideoUrlHelper.DOMAIN_MAIN;
    private KodikResponse currentKodikResponse;

    private final Handler bufferingMonitorHandler = new Handler(Looper.getMainLooper());
    private long bufferingStartTimeMs = 0;
    private boolean isAutoQualityDowngrading = false;

    private final Runnable bufferingMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (host == null || host.isFinishingOrDestroyed()) return;
            PlayerPlaybackController playback = host.getPlaybackController();
            ExoPlayer player = playback != null ? playback.getPlayer() : null;
            if (player == null) return;

            PlayerControlsOverlayManager overlay = host.getControlsOverlayManager();
            if (overlay != null) {
                overlay.updatePlayPauseAndLoadingState(false);
            }

            boolean isBuffering = player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING;
            if (isBuffering) {
                if (bufferingStartTimeMs == 0) {
                    bufferingStartTimeMs = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - bufferingStartTimeMs > 3500) {
                    checkAutoQualityDowngradeOnStalling();
                }
            } else {
                bufferingStartTimeMs = 0;
                isAutoQualityDowngrading = false;
            }

            if (player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED) {
                bufferingMonitorHandler.postDelayed(this, 800);
            }
        }
    };

    public PlayerQualityController() {}

    public void setCallback(QualityCallback callback) {
        this.callback = callback;
    }

    public void setHost(QualityHost host) {
        this.host = host;
    }

    public String getPreferredQuality() {
        return preferredQuality;
    }

    public void setPreferredQuality(String quality) {
        if (!AutoQualityHelper.isDownloadedQuality(quality)) {
            this.preferredQuality = AutoQualityHelper.sanitizeSavedOnlineQuality(quality);
        } else {
            this.preferredQuality = quality;
        }
    }

    public void setPreferredQualityRaw(String quality) {
        this.preferredQuality = quality;
    }

    public boolean isUsingDownloadedSource() {
        return isUsingDownloadedSource;
    }

    public void setUsingDownloadedSource(boolean usingDownloadedSource) {
        this.isUsingDownloadedSource = usingDownloadedSource;
    }

    @Nullable
    public String getActivePlayingQuality() {
        return activePlayingQuality;
    }

    public void setActivePlayingQuality(@Nullable String quality) {
        this.activePlayingQuality = quality;
    }

    public void onActiveQualityResolved(@Nullable String resolvedQuality) {
        String norm = AutoQualityHelper.normalizeResolutionLabel(resolvedQuality);
        if (norm != null && !norm.isEmpty()) {
            activePlayingQuality = norm;
        }
        runOnUi(this::refreshQualityUiState);
    }

    public boolean isPreferredQualityLoadedFromDb() {
        return isPreferredQualityLoadedFromDb;
    }

    public void setPreferredQualityLoadedFromDb(boolean loaded) {
        this.isPreferredQualityLoadedFromDb = loaded;
    }

    public boolean isPreferredQualityChangedByUser() {
        return isPreferredQualityChangedByUser;
    }

    public void setPreferredQualityChangedByUser(boolean changed) {
        this.isPreferredQualityChangedByUser = changed;
    }

    public boolean isEnable4K() {
        return enable4K;
    }

    public void setEnable4K(boolean enable4K) {
        this.enable4K = enable4K;
    }

    @Nullable
    public String getCurrentVideoUrl() {
        return currentVideoUrl;
    }

    public void setCurrentVideoUrl(@Nullable String currentVideoUrl) {
        this.currentVideoUrl = currentVideoUrl;
    }

    public String getCurrentVideoDomain() {
        return currentVideoDomain;
    }

    public void setCurrentVideoDomain(String currentVideoDomain) {
        if (currentVideoDomain != null && !currentVideoDomain.isEmpty()) {
            this.currentVideoDomain = currentVideoDomain;
        }
    }

    public KodikResponse getCurrentKodikResponse() {
        return currentKodikResponse;
    }

    public void setCurrentKodikResponse(KodikResponse response) {
        this.currentKodikResponse = response;
    }

    public void resetForNewEpisode() {
        currentVideoUrl = null;
        isUsingDownloadedSource = false;
        activePlayingQuality = null;
        bufferingStartTimeMs = 0;
        isAutoQualityDowngrading = false;
    }

    public void resetForNewAnime() {
        resetForNewEpisode();
        currentKodikResponse = null;
    }

    public void applyLoadedPreferredQualityIfUnset(String dbQuality) {
        if (!isPreferredQualityChangedByUser && !isPreferredQualityLoadedFromDb
                && dbQuality != null && !dbQuality.trim().isEmpty()) {
            preferredQuality = AutoQualityHelper.sanitizeSavedOnlineQuality(dbQuality);
            isPreferredQualityLoadedFromDb = true;
            runOnUi(this::refreshQualityUiState);
        }
    }

    public void ensurePreferredQualityLoadedSync(ApiService apiService) {
        if (!isPreferredQualityLoadedFromDb && !isPreferredQualityChangedByUser && apiService != null) {
            PlayerPreferences prefs = apiService.loadPlayerPreferences();
            if (prefs != null && prefs.getPreferredQuality() != null && !prefs.getPreferredQuality().trim().isEmpty()) {
                preferredQuality = AutoQualityHelper.sanitizeSavedOnlineQuality(prefs.getPreferredQuality());
            } else {
                preferredQuality = AutoQualityHelper.sanitizeSavedOnlineQuality(preferredQuality);
            }
            isPreferredQualityLoadedFromDb = true;
        } else {
            preferredQuality = AutoQualityHelper.sanitizeSavedOnlineQuality(preferredQuality);
        }
    }

    public void onUserManualVoiceoverSelected(EpisodeResponse.PlayerData playerData) {
        PlayerOfflineController offlineCtrl = host != null ? host.getOfflineController() : null;
        DownloadedEpisodeEntity downloadedEp = offlineCtrl != null
                ? offlineCtrl.findDownloadedEpisodeForPlayer(playerData)
                : null;
        if (downloadedEp != null) {
            isUsingDownloadedSource = true;
            activePlayingQuality = AutoQualityHelper.normalizeResolutionLabel(downloadedEp.getQuality());
        } else {
            isUsingDownloadedSource = false;
            activePlayingQuality = null;
            if (AutoQualityHelper.isDownloadedQuality(preferredQuality)) {
                preferredQuality = AutoQualityHelper.sanitizeSavedOnlineQuality(preferredQuality);
            }
        }
        refreshQualityUiState();
    }

    @Nullable
    public String resolveDownloadUrl(PlayersManager playersManager, String currentVideoDomain, String quality) {
        if (playersManager == null) return null;
        EpisodeResponse.PlayerData playerData = playersManager.getCurrentPlayerData();
        if (playerData == null || playerData.getVideo() == null || playerData.getVideo().getQuality() == null) {
            return null;
        }

        String effectiveQuality = quality;
        if (AutoQualityHelper.isAutoQuality(quality)) {
            List<String> available = playersManager.getAvailableQualities();
            effectiveQuality = AutoQualityHelper.resolveBestQuality(null, available, quality, 0);
        }

        int target = AutoQualityHelper.extractResolution(effectiveQuality);
        for (EpisodeResponse.QualityData data : playerData.getVideo().getQuality()) {
            if (data.getQuality() == target) {
                String domain = ("animelib".equalsIgnoreCase(playerData.getPlayer()) && playerData.getVideoDomain() != null && !playerData.getVideoDomain().isEmpty())
                        ? playerData.getVideoDomain() : currentVideoDomain;
                return VideoUrlHelper.toAbsoluteVideoUrl(data.getHref(), domain);
            }
        }
        if (!playerData.getVideo().getQuality().isEmpty()) {
            EpisodeResponse.QualityData data = playerData.getVideo().getQuality().get(0);
            String domain = ("animelib".equalsIgnoreCase(playerData.getPlayer()) && playerData.getVideoDomain() != null && !playerData.getVideoDomain().isEmpty())
                    ? playerData.getVideoDomain() : currentVideoDomain;
            return VideoUrlHelper.toAbsoluteVideoUrl(data.getHref(), domain);
        }
        return null;
    }

    public static class KodikHlsResult {
        public final String url;
        public final String quality;

        public KodikHlsResult(String url, String quality) {
            this.url = url;
            this.quality = quality;
        }
    }

    @Nullable
    public KodikHlsResult resolveKodikHlsResult(KodikResponse response, String targetQuality) {
        if (response == null || response.getData() == null) return null;

        String qualityToUse = targetQuality;
        if (AutoQualityHelper.isAutoQuality(targetQuality)) {
            List<String> available = new ArrayList<>();
            for (String key : response.getData().keySet()) {
                int r = AutoQualityHelper.extractResolution(key);
                if (r > 0) available.add(r + "p");
            }
            qualityToUse = AutoQualityHelper.resolveBestQuality(null, available, targetQuality, 0);
        }

        List<String> candidates = KodikLinksExtractor.collectCandidateUrls(
                response, qualityToUse, true);
        if (!candidates.isEmpty()) {
            return new KodikHlsResult(candidates.get(0), targetQuality);
        }
        return null;
    }

    @Nullable
    public String resolveKodikHlsUrl(KodikResponse response, String targetQuality) {
        KodikHlsResult res = resolveKodikHlsResult(response, targetQuality);
        return res != null ? res.url : null;
    }

    @Nullable
    public String resolveAnimelibUrl(EpisodeResponse.PlayerData playerData, String targetQuality) {
        if (playerData == null || playerData.getVideo() == null || playerData.getVideo().getQuality() == null || playerData.getVideo().getQuality().isEmpty()) {
            return null;
        }
        List<EpisodeResponse.QualityData> qList = new ArrayList<>(playerData.getVideo().getQuality());
        qList.sort((q1, q2) -> Integer.compare(q2.getQuality(), q1.getQuality()));

        List<String> available = new ArrayList<>();
        for (EpisodeResponse.QualityData q : qList) {
            int res = q.getQuality();
            if (res == 2160 && !enable4K) continue;
            available.add(res + "p");
        }

        Context ctx = host != null ? host.getContext() : null;
        long estimate = 0;
        if (ctx != null) {
            try {
                estimate = DefaultBandwidthMeter.getSingletonInstance(ctx).getBitrateEstimate();
            } catch (Exception ignored) {}
        }

        String effectiveQuality = AutoQualityHelper.resolveBestQuality(ctx, available, targetQuality, estimate);
        int targetRes = AutoQualityHelper.extractResolution(effectiveQuality);

        EpisodeResponse.QualityData selectedQuality = null;
        for (EpisodeResponse.QualityData qData : qList) {
            if (qData.getQuality() == targetRes) {
                selectedQuality = qData;
                break;
            }
        }
        if (selectedQuality == null && !qList.isEmpty()) {
            for (EpisodeResponse.QualityData qData : qList) {
                if (qData.getQuality() == 2160 && !enable4K) continue;
                selectedQuality = qData;
                break;
            }
            if (selectedQuality == null) {
                selectedQuality = qList.get(0);
            }
        }
        if (selectedQuality != null && selectedQuality.getHref() != null) {
            activePlayingQuality = selectedQuality.getQuality() + "p";
            String domain = (playerData.getVideoDomain() != null && !playerData.getVideoDomain().isEmpty())
                    ? playerData.getVideoDomain() : currentVideoDomain;
            return VideoUrlHelper.toAbsoluteVideoUrl(selectedQuality.getHref(), domain);
        }
        return null;
    }

    @Nullable
    public String resolveKodikHlsUrlWithContext(KodikResponse kodikResponse, String targetQuality) {
        if (kodikResponse == null || kodikResponse.getData() == null || kodikResponse.getData().isEmpty()) {
            return null;
        }
        List<String> availableQualities = new ArrayList<>();
        List<String> rawKeys = new ArrayList<>(kodikResponse.getData().keySet());
        rawKeys.sort((k1, k2) -> Integer.compare(
                AutoQualityHelper.extractResolution(k2),
                AutoQualityHelper.extractResolution(k1)
        ));
        for (String key : rawKeys) {
            int res = AutoQualityHelper.extractResolution(key);
            if (res == 2160 && !enable4K) continue;
            String qStr = res > 0 ? res + "p" : key;
            if (!availableQualities.contains(qStr)) {
                availableQualities.add(qStr);
            }
        }
        if (availableQualities.isEmpty()) {
            availableQualities.add("720p");
        }

        Context ctx = host != null ? host.getContext() : null;
        long estimate = 0;
        if (ctx != null) {
            try {
                estimate = DefaultBandwidthMeter.getSingletonInstance(ctx).getBitrateEstimate();
            } catch (Exception ignored) {}
        }

        String effectiveQuality = AutoQualityHelper.resolveBestQuality(ctx, availableQualities, targetQuality, estimate);
        String normEffective = AutoQualityHelper.normalizeResolutionLabel(effectiveQuality);
        if (normEffective != null) {
            activePlayingQuality = normEffective;
        }
        List<String> candidates = KodikLinksExtractor.collectCandidateUrls(
                kodikResponse, effectiveQuality, enable4K);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Nullable
    public String resolveUrlForQuality(EpisodeResponse.PlayerData playerData, String targetQuality) {
        if (host != null) {
            PlayerDownloadController dlc = host.getDownloadController();
            PlayerOfflineController offlineCtrl = host.getOfflineController();
            if (dlc != null && dlc.isDownloadedQuality(targetQuality) && offlineCtrl != null) {
                DownloadedEpisodeEntity downloadedEp = offlineCtrl.getDownloadedEpisodeForActive();
                if (offlineCtrl.hasValidDownloadedFile(downloadedEp)) {
                    return Uri.fromFile(new File(downloadedEp.getLocalFilePath())).toString();
                }
                return null;
            }
        }

        if (playerData == null) return null;

        if ("kodik".equalsIgnoreCase(playerData.getPlayer())) {
            if (currentKodikResponse != null && currentKodikResponse.getData() != null) {
                return resolveKodikHlsUrlWithContext(currentKodikResponse, targetQuality);
            }
        } else if ("animelib".equalsIgnoreCase(playerData.getPlayer())) {
            return resolveAnimelibUrl(playerData, targetQuality);
        }
        return null;
    }

    public String getEffectiveActiveQuality() {
        PlayerPlaybackController playback = host != null ? host.getPlaybackController() : null;
        ExoPlayer player = playback != null ? playback.getPlayer() : null;
        PlayerOfflineController offlineCtrl = host != null ? host.getOfflineController() : null;

        if (host != null && host.isPlayingDownloadedVideo()) {
            DownloadedEpisodeEntity downloadedEp = offlineCtrl != null ? offlineCtrl.getDownloadedEpisodeForActive() : null;
            if (downloadedEp != null) {
                String norm = AutoQualityHelper.normalizeResolutionLabel(downloadedEp.getQuality());
                if (norm != null && !norm.isEmpty()) {
                    return norm;
                }
            }
            if (activePlayingQuality != null && !activePlayingQuality.isEmpty()) {
                return activePlayingQuality;
            }
            if (player != null && player.getVideoFormat() != null) {
                String fromFmt = AutoQualityHelper.formatVideoDimensionsToQuality(
                        player.getVideoFormat().width, player.getVideoFormat().height);
                if (fromFmt != null) {
                    return fromFmt;
                }
            }
            return "1080p";
        }

        if (activePlayingQuality != null && !activePlayingQuality.isEmpty()) {
            return activePlayingQuality;
        }

        if (player != null && player.getVideoFormat() != null) {
            String fromFmt = AutoQualityHelper.formatVideoDimensionsToQuality(
                    player.getVideoFormat().width, player.getVideoFormat().height);
            if (fromFmt != null) {
                return fromFmt;
            }
        }

        String urlQuality = PlayerMetadataHelper.extractResolutionFromUrl(currentVideoUrl);
        if (urlQuality != null) {
            return urlQuality;
        }

        PlayersManager pm = host != null ? host.getPlayersManager() : null;
        if (pm != null) {
            List<String> available = pm.getAvailableQualities();
            if (available != null && !available.isEmpty()) {
                Context ctx = host != null ? host.getContext() : null;
                long estimate = 0;
                if (ctx != null) {
                    try {
                        estimate = DefaultBandwidthMeter.getSingletonInstance(ctx).getBitrateEstimate();
                    } catch (Exception ignored) {}
                }
                String resolved = AutoQualityHelper.resolveBestQuality(ctx, available, preferredQuality, estimate);
                String normResolved = AutoQualityHelper.normalizeResolutionLabel(resolved);
                if (normResolved != null && !normResolved.isEmpty()) {
                    return normResolved;
                }
            }
        }

        String normPref = AutoQualityHelper.normalizeResolutionLabel(preferredQuality);
        return normPref != null ? normPref : "";
    }

    public String getCurrentDisplayQualityForSettings() {
        if (host != null && host.isPlayingDownloadedVideo()) {
            PlayerOfflineController offlineCtrl = host.getOfflineController();
            DownloadedEpisodeEntity downloadedEp = offlineCtrl != null ? offlineCtrl.getDownloadedEpisodeForActive() : null;
            return PlayerDownloadController.formatDownloadedOptionLabel(downloadedEp);
        }
        return preferredQuality;
    }

    public void updateSettingsQualityTag() {
        if (host == null || host.getUiBinder() == null) return;
        PlayerUIBinder ui = host.getUiBinder();
        TextView settingsQualityTag = ui.settingsQualityTag;
        ImageButton settingsButton = ui.settingsButton;
        if (settingsQualityTag != null) {
            if (settingsButton != null && settingsButton.getVisibility() != View.VISIBLE) {
                settingsQualityTag.setVisibility(View.GONE);
                return;
            }
            String activeQuality = getEffectiveActiveQuality();
            String tag = FloatingBottomSheetUtils.getStrictResolutionBadge(activeQuality);
            if (tag != null && !tag.isEmpty()) {
                settingsQualityTag.setText(tag);
                settingsQualityTag.setVisibility(View.VISIBLE);
            } else {
                settingsQualityTag.setVisibility(View.GONE);
            }
        }
    }

    public void refreshQualityUiState() {
        updateSettingsQualityTag();
        if (host == null) return;
        PlayerDialogsController dialogsCtrl = host.getDialogsController();
        PlayerOfflineController offlineCtrl = host.getOfflineController();
        PlayersManager pm = host.getPlayersManager();
        if (dialogsCtrl != null) {
            List<String> qualities;
            if (host.isOfflineMode()) {
                qualities = offlineCtrl != null ? offlineCtrl.getQualitiesWithDownloadedOption(new ArrayList<>()) : new ArrayList<>();
            } else {
                List<String> onlineQualities = pm != null ? pm.getAvailableQualities() : new ArrayList<>();
                qualities = offlineCtrl != null ? offlineCtrl.getQualitiesWithDownloadedOption(onlineQualities) : onlineQualities;
            }
            dialogsCtrl.updateSettingsQualities(
                    qualities,
                    getCurrentDisplayQualityForSettings(),
                    getEffectiveActiveQuality(),
                    host.isPlayingDownloadedVideo()
            );
        }
        runOnUi(() -> {
            if (host == null) return;
            boolean isOffline = host.isOfflineMode();
            PlayerUIBinder ui = host.getUiBinder();
            if (ui != null && ui.menuToggleButton != null) {
                ui.menuToggleButton.setEnabled(true);
                ui.menuToggleButton.setClickable(true);
                ui.menuToggleButton.setAlpha(isOffline ? 0.3f : 1.0f);
            }
            PlayerPortraitController portraitCtrl = host.getPortraitController();
            if (portraitCtrl != null) {
                portraitCtrl.updateVoiceoverButtonState();
            }
        });
    }

    public void onQualitySelectedFromSettings(String quality) {
        if (host == null) return;
        Log.d(TAG, "Selected quality in settings: " + quality);

        PlayerDownloadController dlc = host.getDownloadController();
        PlayerOfflineController offlineCtrl = host.getOfflineController();
        PlayerPortraitController portraitCtrl = host.getPortraitController();
        ApiService apiService = host.getApiService();

        if (dlc != null && dlc.isDownloadedQuality(quality) && offlineCtrl != null) {
            DownloadedEpisodeEntity downloadedEp = offlineCtrl.getDownloadedEpisodeForActive();
            if (!offlineCtrl.hasValidDownloadedFile(downloadedEp)) {
                CustomToast.showWarning(host.getContext(), "Скачанный файл не найден");
                return;
            }
            boolean alreadyDownloaded = host.isPlayingDownloadedVideo();
            isUsingDownloadedSource = true;
            activePlayingQuality = AutoQualityHelper.normalizeResolutionLabel(downloadedEp.getQuality());
            refreshQualityUiState();
            host.onEpisodeHeaderUpdateQuick();
            if (portraitCtrl != null) portraitCtrl.updatePortraitVoiceoverPlayerUI();

            if (!alreadyDownloaded) {
                String localUrl = Uri.fromFile(new File(downloadedEp.getLocalFilePath())).toString();
                applyStreamUrlSwitch(quality, localUrl);
            }
            return;
        }

        boolean wasDownloaded = host.isPlayingDownloadedVideo();
        String oldOnlineQuality = preferredQuality;
        String newOnlineQuality = AutoQualityHelper.sanitizeSavedOnlineQuality(quality);

        isUsingDownloadedSource = false;
        isPreferredQualityChangedByUser = true;
        isPreferredQualityLoadedFromDb = true;
        preferredQuality = newOnlineQuality;
        if (apiService != null) {
            apiService.savePreferredQuality(preferredQuality);
        }

        if (wasDownloaded) {
            activePlayingQuality = null;
        }
        refreshQualityUiState();
        host.onEpisodeHeaderUpdateQuick();
        if (portraitCtrl != null) portraitCtrl.updatePortraitVoiceoverPlayerUI();

        if (!wasDownloaded && AutoQualityHelper.matchQuality(newOnlineQuality, oldOnlineQuality)) {
            return;
        }

        switchToOnlineQualityStream(newOnlineQuality, oldOnlineQuality);
    }

    public void switchToOnlineQualityStream(String newOnlineQuality, String oldQuality) {
        if (host == null) return;
        PlayersManager playersManager = host.getPlayersManager();
        EpisodesManager episodesManager = host.getEpisodesManager();
        PlayerPlaybackController playback = host.getPlaybackController();
        PlayerOfflineController offlineCtrl = host.getOfflineController();
        PlayerVideoResolverController resolver = host.getVideoResolverController();
        PlayerPortraitController portraitCtrl = host.getPortraitController();
        TimecodeManager timecodeManager = host.getTimecodeManager();
        ApiService apiService = host.getApiService();

        if (playersManager == null) return;
        ExoPlayer player = playback != null ? playback.getPlayer() : null;

        EpisodeResponse.PlayerData currentPlayer = playersManager.getCurrentPlayerData();
        if (offlineCtrl != null && offlineCtrl.isSyntheticPlayerData(currentPlayer)) {
            List<EpisodeResponse.PlayerData> all = playersManager.getAllPlayers();
            if (all != null && !all.isEmpty()) {
                EpisodeResponse.PlayerData matched = offlineCtrl.findMatchingOnlinePlayerForDownloaded(all, offlineCtrl.getDownloadedEpisodeForActive());
                currentPlayer = matched != null ? matched : all.get(0);
                playersManager.setCurrentPlayerData(currentPlayer);
                host.onEpisodeHeaderUpdateQuick();
                if (portraitCtrl != null) portraitCtrl.updatePortraitVoiceoverPlayerUI();
            } else {
                EpisodesListResponse.EpisodeItem curEp = episodesManager != null ? episodesManager.getCurrentEpisode() : null;
                if (curEp != null) {
                    long pos = player != null ? Math.max(0L, player.getCurrentPosition()) : 0L;
                    host.setSavedPlayerPosition(pos);
                    playersManager.setCurrentPlayerData(null);
                    host.showLoading("Загрузка онлайн-источника...");
                    playersManager.loadPlayersForEpisode(curEp.getId(), true);
                }
                return;
            }
        }

        if (currentPlayer == null) return;

        if ("kodik".equalsIgnoreCase(currentPlayer.getPlayer())
                && (currentKodikResponse == null || currentKodikResponse.getData() == null || currentKodikResponse.getData().isEmpty())) {
            String kodikSrc = currentPlayer.getSrc();
            if (kodikSrc != null && !kodikSrc.isEmpty() && apiService != null) {
                if (!kodikSrc.startsWith("http")) kodikSrc = "https:" + kodikSrc;
                final String finalKodikSrc = kodikSrc;
                final EpisodeResponse.PlayerData targetPd = currentPlayer;
                final long posToRestore = player != null ? Math.max(0L, player.getCurrentPosition()) : 0L;
                final boolean wasPlaying = player == null || player.getPlayWhenReady();
                final boolean isSeamless = playback != null && playback.isSeamlessSwitching();
                final boolean shouldBeSeamless = player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY) && !isSeamless;
                final int switchGen = resolver != null ? resolver.getPlaybackRestartGeneration() : 0;
                if (!shouldBeSeamless) {
                    host.showLoading("Загрузка онлайн-качества...");
                }
                apiService.fetchKodikVideoLinks(finalKodikSrc, false, new ApiService.KodikVideoCallback() {
                    @Override
                    public void onKodikVideoReceived(KodikResponse response) {
                        runOnUi(() -> {
                            int currentGen = resolver != null ? resolver.getPlaybackRestartGeneration() : 0;
                            if (switchGen != currentGen || host.isPlayingDownloadedVideo()) {
                                return;
                            }
                            if (!shouldBeSeamless) {
                                host.hideLoading();
                            }
                            currentKodikResponse = response;
                            playersManager.setCurrentKodikResponse(response);
                            if (resolver != null) {
                                resolver.resetKodikRecoveryState();
                                resolver.setActiveKodikContext(finalKodikSrc, response, preferredQuality);
                            }
                            String newVideoUrl = resolveKodikHlsUrlWithContext(response, preferredQuality);
                            refreshQualityUiState();
                            ExoPlayer livePlayer = playback != null ? playback.getPlayer() : null;
                            if (newVideoUrl != null && !newVideoUrl.equals(currentVideoUrl)) {
                                if (shouldBeSeamless && livePlayer != null && (livePlayer.isPlaying() || livePlayer.getPlaybackState() == Player.STATE_READY)) {
                                    switchQualitySeamlesslyInternal(preferredQuality, oldQuality, newVideoUrl);
                                } else {
                                    currentVideoUrl = newVideoUrl;
                                    if (timecodeManager != null) {
                                        timecodeManager.setTimecodes(targetPd);
                                    }
                                    switchMediaSourceInternal(newVideoUrl, posToRestore, wasPlaying);
                                }
                            } else {
                                host.setSavedPlayerPosition(posToRestore);
                                host.stopCurrentPlaybackAndRestartVoiceover(targetPd);
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUi(() -> {
                            int currentGen = resolver != null ? resolver.getPlaybackRestartGeneration() : 0;
                            if (switchGen != currentGen) {
                                return;
                            }
                            host.hideLoading();
                            host.setSavedPlayerPosition(posToRestore);
                            host.stopCurrentPlaybackAndRestartVoiceover(targetPd);
                        });
                    }
                });
                return;
            }
        }

        if ("animelib".equalsIgnoreCase(currentPlayer.getPlayer())
                && currentPlayer.getVideoDomain() != null && !currentPlayer.getVideoDomain().isEmpty()) {
            currentVideoDomain = currentPlayer.getVideoDomain();
        }

        String newVideoUrl = resolveUrlForQuality(currentPlayer, newOnlineQuality);
        refreshQualityUiState();
        if (newVideoUrl != null) {
            applyStreamUrlSwitch(newOnlineQuality, newVideoUrl);
        } else {
            restartPlayerWithNewQuality();
        }
    }

    public void applyStreamUrlSwitch(String targetLabel, String newVideoUrl) {
        if (newVideoUrl == null) {
            restartPlayerWithNewQuality();
            return;
        }
        PlayerPlaybackController playback = host != null ? host.getPlaybackController() : null;
        ExoPlayer player = playback != null ? playback.getPlayer() : null;
        boolean isSeamless = playback != null && playback.isSeamlessSwitching();
        boolean isCurrentlyPlaying = player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY) && !isSeamless;
        if (isCurrentlyPlaying && !newVideoUrl.equals(currentVideoUrl)) {
            switchQualitySeamlesslyInternal(targetLabel, preferredQuality, newVideoUrl);
        } else if (!newVideoUrl.equals(currentVideoUrl)) {
            long currentPos = player != null ? player.getCurrentPosition() : 0;
            boolean wasPlaying = player == null || player.getPlayWhenReady();
            currentVideoUrl = newVideoUrl;
            switchMediaSourceInternal(newVideoUrl, currentPos, wasPlaying);
            refreshQualityUiState();
        } else {
            refreshQualityUiState();
        }
    }

    public void switchMediaSourceInternal(String newVideoUrl, long startPosition, boolean playWhenReady) {
        if (host == null) return;
        PlayerPlaybackController playback = host.getPlaybackController();
        PlayerControlsOverlayManager overlay = host.getControlsOverlayManager();
        PlayerSubtitlesController subs = host.getSubtitlesController();

        if (playback != null) {
            playback.cleanupBackgroundPlayer();
        }
        if (overlay != null) {
            overlay.setVideoLoading(true);
            overlay.setHasRenderedFirstFrame(false);
            overlay.updatePlayPauseAndLoadingState(true);
        }
        if (playback != null) {
            MediaItem mediaItem = subs != null ? subs.createMediaItemWithSubtitles(newVideoUrl) : MediaItem.fromUri(newVideoUrl);
            playback.switchMediaSource(newVideoUrl, mediaItem, startPosition, playWhenReady);
        }
    }

    public void switchQualitySeamlesslyInternal(String newQuality, String oldQuality, String newVideoUrl) {
        if (host == null) return;
        PlayerPlaybackController playback = host.getPlaybackController();
        PlayerSubtitlesController subs = host.getSubtitlesController();
        if (playback != null) {
            MediaItem mediaItem = subs != null ? subs.createMediaItemWithSubtitles(newVideoUrl) : MediaItem.fromUri(newVideoUrl);
            playback.switchQualitySeamlessly(newQuality, oldQuality, newVideoUrl, mediaItem);
        }
    }

    public void restartPlayerWithNewQuality() {
        if (host == null) return;
        PlayerPlaybackController playback = host.getPlaybackController();
        PlayerPanelsController panels = host.getPanelsController();
        PlayersManager playersManager = host.getPlayersManager();
        PlayerOfflineController offlineCtrl = host.getOfflineController();
        PlayerVideoResolverController resolver = host.getVideoResolverController();
        PlayerSubtitlesController subs = host.getSubtitlesController();
        TimecodeManager timecodeManager = host.getTimecodeManager();

        if (playback != null) {
            playback.preservePlayerAspectRatio();
            playback.stopCurrentPlayback();
        }
        if (panels != null) {
            panels.applyPlayerSidePanelTransform(0f);
        }
        if (playersManager == null) return;

        EpisodeResponse.PlayerData currentPlayerData = playersManager.getCurrentPlayerData();
        if (currentPlayerData == null) {
            return;
        }

        ExoPlayer player = playback != null ? playback.getPlayer() : null;
        long currentPosition = player != null ? player.getCurrentPosition() : 0;
        boolean playWhenReady = player != null && player.getPlayWhenReady();

        if (resolver != null) {
            resolver.incrementPlaybackRestartGeneration();
            resolver.cancelPendingResolutions();
        }

        if (host.isPlayingDownloadedVideo() && offlineCtrl != null && playback != null) {
            DownloadedEpisodeEntity downloadedEp = offlineCtrl.getDownloadedEpisodeForActive();
            if (offlineCtrl.hasValidDownloadedFile(downloadedEp)) {
                File file = new File(downloadedEp.getLocalFilePath());
                currentVideoUrl = Uri.fromFile(file).toString();
                activePlayingQuality = AutoQualityHelper.normalizeResolutionLabel(downloadedEp.getQuality());
                if (timecodeManager != null) {
                    timecodeManager.setTimecodes(currentPlayerData);
                }
                MediaItem mediaItem = subs != null ? subs.createMediaItemWithSubtitles(currentVideoUrl) : MediaItem.fromUri(currentVideoUrl);
                playback.initializePlayer(currentVideoUrl, mediaItem, playback.getCurrentResizeMode(), playWhenReady, currentPosition);
                playersManager.setCurrentPlayerData(currentPlayerData);
                refreshQualityUiState();
                Log.d(TAG, "Restarted player with local downloaded file: " + file.getAbsolutePath());
                return;
            }
        }

        if ("animelib".equalsIgnoreCase(currentPlayerData.getPlayer()) && resolver != null) {
            resolver.handleAnimelibPlayer(currentPlayerData, currentPosition);
        } else if ("kodik".equalsIgnoreCase(currentPlayerData.getPlayer()) && resolver != null) {
            if (currentKodikResponse != null && currentKodikResponse.getData() != null) {
                resolver.startHlsPlayer(currentKodikResponse, currentPosition);
                playersManager.setCurrentPlayerData(currentPlayerData);
                return;
            }
            resolver.handleKodikPlayer(currentPlayerData, currentPosition);
        }

        playersManager.setCurrentPlayerData(currentPlayerData);
    }

    public void startBufferingMonitoring() {
        bufferingMonitorHandler.removeCallbacks(bufferingMonitorRunnable);
        bufferingMonitorHandler.post(bufferingMonitorRunnable);
    }

    public void stopBufferingMonitoring() {
        bufferingMonitorHandler.removeCallbacks(bufferingMonitorRunnable);
    }

    private void checkAutoQualityDowngradeOnStalling() {
        if (host == null || isAutoQualityDowngrading || host.isPlayingDownloadedVideo()
                || !AutoQualityHelper.isAutoQuality(preferredQuality)) {
            return;
        }
        PlayersManager playersManager = host.getPlayersManager();
        if (playersManager == null) return;

        List<String> available = playersManager.getAvailableQualities();
        if (available == null || available.isEmpty()) return;

        EpisodeResponse.PlayerData currentPlayerData = playersManager.getCurrentPlayerData();
        if (currentPlayerData == null) return;

        String lowerQuality = PlayerMetadataHelper.findLowerQualityForStall(available, getEffectiveActiveQuality());
        if (lowerQuality != null) {
            String newVideoUrl = resolveUrlForQuality(currentPlayerData, lowerQuality);
            if (newVideoUrl != null && !newVideoUrl.equals(currentVideoUrl)) {
                isAutoQualityDowngrading = true;
                bufferingStartTimeMs = 0;
                activePlayingQuality = AutoQualityHelper.normalizeResolutionLabel(lowerQuality);
                refreshQualityUiState();
                Log.w(TAG, "Auto Quality: Network stalling detected! Downgrading stream to " + lowerQuality + " while keeping Auto mode");
                applyStreamUrlSwitch(lowerQuality, newVideoUrl);
            }
        }
    }

    public void switchQuality(PlayersManager playersManager, String currentVideoDomain, String newQuality) {
        if (playersManager == null) return;

        EpisodeResponse.PlayerData playerData = playersManager.getCurrentPlayerData();
        if (playerData == null) return;

        if ("kodik".equalsIgnoreCase(playerData.getPlayer())) {
            if (currentKodikResponse != null) {
                KodikHlsResult result = resolveKodikHlsResult(currentKodikResponse, newQuality);
                if (result != null && result.url != null) {
                    this.preferredQuality = result.quality;
                    if (callback != null) {
                        callback.onQualityChanged(result.quality, result.url, true);
                    }
                } else if (callback != null) {
                    callback.onError("Ошибка смены качества", "Не удалось сформировать HLS ссылку для качества " + newQuality, null);
                }
            }
        } else {
            this.preferredQuality = newQuality;
            String videoUrl = resolveDownloadUrl(playersManager, currentVideoDomain, newQuality);
            if (videoUrl != null && callback != null) {
                callback.onQualityChanged(newQuality, videoUrl, false);
            } else if (callback != null) {
                callback.onError("Ошибка смены качества", "Ссылка для качества " + newQuality + " не найдена", null);
            }
        }
    }

    public void cleanup() {
        stopBufferingMonitoring();
        bufferingMonitorHandler.removeCallbacksAndMessages(null);
    }

    private void runOnUi(Runnable action) {
        if (host != null) {
            host.safeRunOnUiThread(action);
        } else {
            action.run();
        }
    }
}
