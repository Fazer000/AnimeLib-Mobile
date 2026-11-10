package com.example.animelib.controllers;

import android.content.Context;
import android.util.Log;

import androidx.media3.exoplayer.ExoPlayer;

import com.example.animelib.api.ApiService;
import com.example.animelib.managers.SurroundSoundManager;
import com.example.animelib.util.Surround51AudioProcessor;

public class PlayerAudioController {

    private static final String TAG = "PlayerAudioController";

    private SurroundSoundManager surroundSoundManager;
    private boolean enableSurroundSound = true;
    private int surroundMode = 0;
    private float surroundSpatialWidth = 1.0f;
    private float surroundDialogueBoost = 1.0f;
    private float surroundBassBoost = 1.0f;
    private float surroundMidBoost = 1.0f;
    private float surroundTrebleBoost = 1.0f;

    public void init(Context context, ApiService apiService) {
        surroundSoundManager = new SurroundSoundManager(context);
        if (apiService != null) {
            loadSettings(apiService);
        }
    }

    public void loadSettings(ApiService apiService) {
        enableSurroundSound = apiService.loadSurroundSoundSetting();
        surroundMode = apiService.loadSurround3DMode();
        surroundSpatialWidth = apiService.loadSurroundSpatialWidth();
        surroundDialogueBoost = apiService.loadSurroundDialogueBoost();
        surroundBassBoost = apiService.loadSurroundBassBoost();
        surroundMidBoost = apiService.loadSurroundMidBoost();
        surroundTrebleBoost = apiService.loadSurroundTrebleBoost();
        applyToManager();
    }

    public void applyToManager() {
        if (surroundSoundManager != null) {
            surroundSoundManager.setEnabled(enableSurroundSound);
            surroundSoundManager.setSpatialMode(surroundMode);
            surroundSoundManager.setSpatialWidth(surroundSpatialWidth);
            surroundSoundManager.setDialogueBoost(surroundDialogueBoost);
            surroundSoundManager.setBassBoostLevel(surroundBassBoost);
            surroundSoundManager.setMidBoostLevel(surroundMidBoost);
            surroundSoundManager.setTrebleBoostLevel(surroundTrebleBoost);
        }
    }

    public void updateSettings(boolean enabled, int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost, ApiService apiService) {
        updateSettings(enabled, mode, spatialWidth, dialogueBoost, bassBoost, 1.0f, trebleBoost, apiService);
    }

    public void updateSettings(boolean enabled, int mode, float spatialWidth, float dialogueBoost, float bassBoost, float midBoost, float trebleBoost, ApiService apiService) {
        this.enableSurroundSound = enabled;
        this.surroundMode = mode;
        this.surroundSpatialWidth = spatialWidth;
        this.surroundDialogueBoost = dialogueBoost;
        this.surroundBassBoost = bassBoost;
        this.surroundMidBoost = midBoost;
        this.surroundTrebleBoost = trebleBoost;

        if (apiService != null) {
            apiService.saveSurroundSoundSetting(enabled);
            apiService.saveSurround3DSettings(mode, spatialWidth, dialogueBoost, bassBoost, midBoost, trebleBoost);
        }

        applyToManager();
        Log.d(TAG, "Updated 3D Surround sound settings: enabled=" + enabled + ", mode=" + mode + ", width=" + spatialWidth + ", mid=" + midBoost);
    }

    public void attachPlayer(ExoPlayer player) {
        if (surroundSoundManager != null && player != null) {
            surroundSoundManager.attachPlayer(player);
        }
    }

    public Surround51AudioProcessor getSurroundAudioProcessor() {
        return surroundSoundManager != null ? surroundSoundManager.getSurroundAudioProcessor() : null;
    }

    public SurroundSoundManager getSurroundSoundManager() {
        return surroundSoundManager;
    }

    public boolean isEnableSurroundSound() {
        return enableSurroundSound;
    }

    public int getSurroundMode() {
        return surroundMode;
    }

    public float getSurroundSpatialWidth() {
        return surroundSpatialWidth;
    }

    public float getSurroundDialogueBoost() {
        return surroundDialogueBoost;
    }

    public float getSurroundBassBoost() {
        return surroundBassBoost;
    }

    public float getSurroundMidBoost() {
        return surroundMidBoost;
    }

    public float getSurroundTrebleBoost() {
        return surroundTrebleBoost;
    }

    public void release() {
        if (surroundSoundManager != null) {
            surroundSoundManager.release();
            surroundSoundManager = null;
        }
    }
}
