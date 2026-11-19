package com.example.animelib.controllers;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;

import com.example.animelib.api.ApiService;
import com.example.animelib.managers.AmbientLightManager;
import com.example.animelib.managers.PlayersManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.ui.VideoUrlHelper;
import com.example.animelib.util.AutoQualityHelper;
import com.example.animelib.util.CustomToast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Мост между VideoPlayerActivity, контроллерами плеера и SettingsBottomSheet.
 * Вынесен из Activity для разгрузки основного класса экрана.
 */
public class PlayerSettingsBridge implements PlayerDialogsController.SettingsDataProvider {

    public interface Host {
        Context getContext();
        boolean isOfflineMode();
        boolean isPlayingDownloadedVideo();
        @Nullable ExoPlayer getActivePlayer();
        ApiService getApiService();
        PlayersManager getPlayersManager();
        @Nullable AmbientLightManager getAmbientLightManager();
        @Nullable PlayerOfflineController getPlayerOfflineController();
        @Nullable PlayerQualityController getPlayerQualityController();
        @Nullable PlayerFiltersController getPlayerFiltersController();
        @Nullable PlayerAudioController getPlayerAudioController();
        @Nullable PlayerPlaybackController getPlayerPlaybackController();
        @Nullable PlayerSubtitlesController getPlayerSubtitlesController();

        boolean isEnableAmbientLight();
        void setEnableAmbientLight(boolean enabled);

        boolean isAutoPlay();
        void setAutoPlayAndPrepare(boolean enabled);

        int getLongSkipDuration();
        void setLongSkipDuration(int duration);

        int getCurrentTheme();
        void applyThemeAndOrientation(int themeMode);
    }

    private final Host host;

    public PlayerSettingsBridge(Host host) {
        this.host = host;
    }

    @Override
    public boolean isOfflineMode() {
        return host.isOfflineMode();
    }

    @Override
    public boolean isPlayingDownloadedVideo() {
        return host.isPlayingDownloadedVideo();
    }

    @Override
    public PlayersManager getPlayersManager() {
        return host.getPlayersManager();
    }

    @Override
    public List<String> getAvailableQualities() {
        PlayerOfflineController offlineCtrl = host.getPlayerOfflineController();
        PlayersManager pm = host.getPlayersManager();
        if (offlineCtrl != null) {
            if (host.isOfflineMode()) {
                return offlineCtrl.getQualitiesWithDownloadedOption(new ArrayList<>());
            }
            return offlineCtrl.getQualitiesWithDownloadedOption(pm.getAvailableQualities());
        }
        return pm.getAvailableQualities();
    }

    @Override
    public String getPreferredQuality() {
        PlayerQualityController qCtrl = host.getPlayerQualityController();
        return qCtrl != null ? qCtrl.getCurrentDisplayQualityForSettings() : "Авто";
    }

    @Override
    public String getActivePlayingQuality() {
        PlayerQualityController qCtrl = host.getPlayerQualityController();
        return qCtrl != null ? qCtrl.getEffectiveActiveQuality() : "";
    }

    @Override
    public void onQualitySelected(String quality) {
        PlayerQualityController qCtrl = host.getPlayerQualityController();
        if (qCtrl != null) {
            qCtrl.onQualitySelectedFromSettings(quality);
        }
    }

    @Override
    public float getPlaybackSpeed() {
        ExoPlayer p = host.getActivePlayer();
        return p != null ? p.getPlaybackParameters().speed : 1.0f;
    }

    @Override
    public void onSpeedChanged(float speed) {
        ExoPlayer p = host.getActivePlayer();
        if (p != null) {
            p.setPlaybackSpeed(speed);
        }
    }

    @Override
    public boolean isEnable4K() {
        PlayerQualityController qCtrl = host.getPlayerQualityController();
        return qCtrl != null && qCtrl.isEnable4K();
    }

    @Override
    public void onEnable4KChanged(boolean enabled) {
        PlayerQualityController qCtrl = host.getPlayerQualityController();
        if (qCtrl == null) return;
        qCtrl.setEnable4K(enabled);
        host.getApiService().save4KSetting(enabled);
        host.getPlayersManager().setEnable4K(enabled);
        if (!enabled && !host.isPlayingDownloadedVideo()
                && AutoQualityHelper.extractResolution(qCtrl.getEffectiveActiveQuality()) >= 2160) {
            qCtrl.setActivePlayingQuality(null);
            qCtrl.switchToOnlineQualityStream(qCtrl.getPreferredQuality(), "2160p");
        }
        qCtrl.refreshQualityUiState();
    }

    @Override
    public boolean isEnableAmbientLight() {
        return host.isEnableAmbientLight();
    }

    @Override
    public void onEnableAmbientLightChanged(boolean enabled) {
        host.setEnableAmbientLight(enabled);
        host.getApiService().saveAmbientLightSetting(enabled);
        AmbientLightManager alm = host.getAmbientLightManager();
        if (alm != null) {
            alm.setEnabled(enabled);
        }
    }

    @Override
    public boolean isAutoPlay() {
        return host.isAutoPlay();
    }

    @Override
    public void onAutoPlayChanged(boolean enabled) {
        host.setAutoPlayAndPrepare(enabled);
        host.getApiService().saveAutoPlaySetting(enabled);
    }

    @Override
    public int getLongSkipDuration() {
        return host.getLongSkipDuration();
    }

    @Override
    public void onLongSkipDurationChanged(int duration) {
        host.setLongSkipDuration(duration);
        host.getApiService().saveLongSkipDurationSetting(duration);
    }

    @Override
    public int getCurrentTheme() {
        return host.getCurrentTheme();
    }

    @Override
    public void onThemeChanged(int themeMode) {
        host.applyThemeAndOrientation(themeMode);
        host.getApiService().saveThemeSetting(themeMode);
    }

    @Override
    public float[] getVideoFilters() {
        PlayerFiltersController fCtrl = host.getPlayerFiltersController();
        return new float[]{
                fCtrl != null ? fCtrl.getFilterBrightness() : 0f,
                fCtrl != null ? fCtrl.getFilterContrast() : 100f,
                fCtrl != null ? fCtrl.getFilterSaturation() : 100f,
                fCtrl != null ? fCtrl.getFilterGamma() : 1.0f,
                fCtrl != null ? fCtrl.getFilterHue() : 0f
        };
    }

    @Override
    public void onVideoFiltersChanged(float b, float c, float s, float g, float h) {
        PlayerFiltersController fCtrl = host.getPlayerFiltersController();
        if (fCtrl != null) {
            fCtrl.setFilters(b, c, s, g, h);
        }
        host.getApiService().saveVideoFilters(b, c, s, g, h);
    }

    @Override
    public boolean isSurround3DEnabled() {
        PlayerAudioController aCtrl = host.getPlayerAudioController();
        return aCtrl != null && aCtrl.isEnableSurroundSound();
    }

    @Override
    public int getSurroundMode() {
        PlayerAudioController aCtrl = host.getPlayerAudioController();
        return aCtrl != null ? aCtrl.getSurroundMode() : 0;
    }

    @Override
    public float getSurroundSpatialWidth() {
        PlayerAudioController aCtrl = host.getPlayerAudioController();
        return aCtrl != null ? aCtrl.getSurroundSpatialWidth() : 1.0f;
    }

    @Override
    public float getSurroundDialogueBoost() {
        PlayerAudioController aCtrl = host.getPlayerAudioController();
        return aCtrl != null ? aCtrl.getSurroundDialogueBoost() : 1.0f;
    }

    @Override
    public float getSurroundBassBoost() {
        PlayerAudioController aCtrl = host.getPlayerAudioController();
        return aCtrl != null ? aCtrl.getSurroundBassBoost() : 1.0f;
    }

    @Override
    public float getSurroundMidBoost() {
        PlayerAudioController aCtrl = host.getPlayerAudioController();
        return aCtrl != null ? aCtrl.getSurroundMidBoost() : 1.0f;
    }

    @Override
    public float getSurroundTrebleBoost() {
        PlayerAudioController aCtrl = host.getPlayerAudioController();
        return aCtrl != null ? aCtrl.getSurroundTrebleBoost() : 1.0f;
    }

    @Override
    public void onSurround3DChanged(boolean enabled, int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost) {
        onSurround3DChanged(enabled, mode, spatialWidth, dialogueBoost, bassBoost, trebleBoost, 1.0f);
    }

    @Override
    public void onSurround3DChanged(boolean enabled, int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost, float midBoost) {
        PlayerAudioController aCtrl = host.getPlayerAudioController();
        if (aCtrl != null) {
            aCtrl.updateSettings(enabled, mode, spatialWidth, dialogueBoost, bassBoost, midBoost, trebleBoost, host.getApiService());
        }
    }

    @Override
    public int getCurrentResizeMode() {
        PlayerPlaybackController pCtrl = host.getPlayerPlaybackController();
        return pCtrl != null ? pCtrl.getCurrentResizeMode() : AspectRatioFrameLayout.RESIZE_MODE_FIT;
    }

    @Override
    public void onResizeModeChanged(int newMode) {
        host.getApiService().saveVideoResizeModeSetting(newMode);
        PlayerPlaybackController pCtrl = host.getPlayerPlaybackController();
        if (pCtrl != null) {
            pCtrl.setVideoResizeMode(newMode);
        }
        AmbientLightManager alm = host.getAmbientLightManager();
        if (alm != null) {
            alm.updateBoundsFromPlayer();
        }
    }

    @Override
    public boolean isSubtitlesEnabled() {
        PlayerSubtitlesController sCtrl = host.getPlayerSubtitlesController();
        return sCtrl != null && sCtrl.isSubtitlesEnabled();
    }

    @Override
    public String getSubtitleFormat() {
        PlayerSubtitlesController sCtrl = host.getPlayerSubtitlesController();
        return sCtrl != null ? sCtrl.getSubtitleFormat() : "ass";
    }

    @Override
    public List<EpisodeResponse.SubtitleData> getSubtitles() {
        PlayersManager pm = host.getPlayersManager();
        return (pm != null && pm.getCurrentPlayerData() != null)
                ? pm.getCurrentPlayerData().getSubtitles() : null;
    }

    @Override
    public void onSubtitleSettingsChanged(boolean enabled, String format) {
        PlayerSubtitlesController sCtrl = host.getPlayerSubtitlesController();
        if (sCtrl != null) {
            sCtrl.updateSubtitleSettings(enabled, format);
        }
    }

    @Override
    public float getSubtitleTextSize() {
        PlayerSubtitlesController sCtrl = host.getPlayerSubtitlesController();
        return sCtrl != null ? sCtrl.getSubtitleTextSize() : 18f;
    }

    @Override
    public int getSubtitleTextColor() {
        PlayerSubtitlesController sCtrl = host.getPlayerSubtitlesController();
        return sCtrl != null ? sCtrl.getSubtitleTextColor() : 0xFFFFFFFF;
    }

    @Override
    public int getSubtitleBackgroundColor() {
        PlayerSubtitlesController sCtrl = host.getPlayerSubtitlesController();
        return sCtrl != null ? sCtrl.getSubtitleBackgroundColor() : 0x00000000;
    }

    @Override
    public int getSubtitleEdgeType() {
        PlayerSubtitlesController sCtrl = host.getPlayerSubtitlesController();
        return sCtrl != null ? sCtrl.getSubtitleEdgeType() : CaptionStyleCompat.EDGE_TYPE_OUTLINE;
    }

    @Override
    public int getSubtitleEdgeColor() {
        PlayerSubtitlesController sCtrl = host.getPlayerSubtitlesController();
        return sCtrl != null ? sCtrl.getSubtitleEdgeColor() : 0xFF000000;
    }

    @Override
    public void onSubtitleStyleSettingsChanged(float textSize, int textColor, int bgColor, int edgeType, int edgeColor) {
        PlayerSubtitlesController sCtrl = host.getPlayerSubtitlesController();
        if (sCtrl != null) {
            sCtrl.updateSubtitleStyleSettings(textSize, textColor, bgColor, edgeType, edgeColor);
        }
    }

    @Override
    public String getCurrentVideoDomain() {
        PlayerQualityController qCtrl = host.getPlayerQualityController();
        return qCtrl != null ? qCtrl.getCurrentVideoDomain() : VideoUrlHelper.DOMAIN_MAIN;
    }

    @Override
    public void onVideoDomainChanged(String domain) {
        if (host.isPlayingDownloadedVideo()) {
            CustomToast.showWarning(host.getContext(), "Смена сервера недоступна для скачанного видео");
            return;
        }
        PlayerQualityController qCtrl = host.getPlayerQualityController();
        PlayersManager pm = host.getPlayersManager();
        if (qCtrl != null && !Objects.equals(qCtrl.getCurrentVideoDomain(), domain)) {
            qCtrl.setCurrentVideoDomain(domain);
            boolean isKodik = pm != null && pm.getCurrentPlayerData() != null
                    && "kodik".equalsIgnoreCase(pm.getCurrentPlayerData().getPlayer());
            if (!isKodik && host.getActivePlayer() != null) {
                qCtrl.restartPlayerWithNewQuality();
            }
        }
    }
}
