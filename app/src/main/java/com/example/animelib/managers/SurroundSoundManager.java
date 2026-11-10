package com.example.animelib.managers;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.os.Build;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.example.animelib.util.Surround51AudioProcessor;

/**
 * Manager for 5.1 Surround Sound / Spatial Audio in Video Player.
 * Controls Android system Virtualizer, Bass Boost, EQ, and custom ExoPlayer 5.1 AudioProcessor.
 */
public class SurroundSoundManager {

    private static final String TAG = "SurroundSoundManager";

    private final Context context;
    private boolean isEnabled = true;

    private ExoPlayer player;
    private Player.Listener playerListener;

    private Virtualizer virtualizer;
    private BassBoost bassBoost;
    private Equalizer equalizer;
    private int activeAudioSessionId = C.AUDIO_SESSION_ID_UNSET;

    private final Surround51AudioProcessor surroundAudioProcessor;

    public SurroundSoundManager(Context context) {
        this.context = context.getApplicationContext();
        this.surroundAudioProcessor = new Surround51AudioProcessor();
    }

    public Surround51AudioProcessor getSurroundAudioProcessor() {
        return surroundAudioProcessor;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        surroundAudioProcessor.setEnabled(enabled);

        if (activeAudioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            updateAudioEffectsState(enabled);
        }

        if (player != null) {
            updatePlayerAudioAttributes(enabled);
        }

        Log.d(TAG, "5.1 Surround Sound toggled: " + enabled);
    }

    public void setSpatialMode(int mode) {
        surroundAudioProcessor.setSpatialMode(mode);
        if (activeAudioSessionId != C.AUDIO_SESSION_ID_UNSET && isEnabled) {
            updateAudioEffectsState(true);
        }
    }

    public int getSpatialMode() {
        return surroundAudioProcessor.getSpatialMode();
    }

    public void setSpatialWidth(float width) {
        surroundAudioProcessor.setSpatialWidth(width);
        if (activeAudioSessionId != C.AUDIO_SESSION_ID_UNSET && isEnabled) {
            updateAudioEffectsState(true);
        }
    }

    public float getSpatialWidth() {
        return surroundAudioProcessor.getSpatialWidth();
    }

    public void setDialogueBoost(float boost) {
        surroundAudioProcessor.setDialogueBoost(boost);
        if (activeAudioSessionId != C.AUDIO_SESSION_ID_UNSET && isEnabled) {
            updateAudioEffectsState(true);
        }
    }

    public float getDialogueBoost() {
        return surroundAudioProcessor.getDialogueBoost();
    }

    public void setBassBoostLevel(float bass) {
        surroundAudioProcessor.setBassBoostLevel(bass);
        if (activeAudioSessionId != C.AUDIO_SESSION_ID_UNSET && isEnabled) {
            updateAudioEffectsState(true);
        }
    }

    public float getBassBoostLevel() {
        return surroundAudioProcessor.getBassBoostLevel();
    }

    public void setMidBoostLevel(float mid) {
        surroundAudioProcessor.setMidBoostLevel(mid);
        if (activeAudioSessionId != C.AUDIO_SESSION_ID_UNSET && isEnabled) {
            updateAudioEffectsState(true);
        }
    }

    public float getMidBoostLevel() {
        return surroundAudioProcessor.getMidBoostLevel();
    }

    public void setTrebleBoostLevel(float treble) {
        surroundAudioProcessor.setTrebleBoostLevel(treble);
        if (activeAudioSessionId != C.AUDIO_SESSION_ID_UNSET && isEnabled) {
            updateAudioEffectsState(true);
        }
    }

    public float getTrebleBoostLevel() {
        return surroundAudioProcessor.getTrebleBoostLevel();
    }

    public void attachPlayer(ExoPlayer newPlayer) {
        if (this.player == newPlayer) {
            return;
        }

        detachCurrentPlayer();

        this.player = newPlayer;
        if (this.player == null) {
            return;
        }

        // Configure player AudioAttributes for Spatializer / Movie multi-channel audio
        updatePlayerAudioAttributes(isEnabled);

        // Listen for Audio Session ID changes
        playerListener = new Player.Listener() {
            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                Log.d(TAG, "Audio session ID changed: " + audioSessionId);
                applyAudioSessionEffects(audioSessionId);
            }
        };

        player.addListener(playerListener);

        // Apply immediately if session ID is already valid
        int currentSessionId = player.getAudioSessionId();
        if (currentSessionId != C.AUDIO_SESSION_ID_UNSET && currentSessionId > 0) {
            applyAudioSessionEffects(currentSessionId);
        }
    }

    public void applyAudioSessionEffects(int audioSessionId) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
            return;
        }

        if (activeAudioSessionId == audioSessionId && virtualizer != null) {
            updateAudioEffectsState(isEnabled);
            return;
        }

        releaseAudioEffects();

        activeAudioSessionId = audioSessionId;

        try {
            // 1. Android System Virtualizer (3D Soundstage / HRTF 5.1 Matrix Virtualizer)
            virtualizer = new Virtualizer(0, audioSessionId);
            virtualizer.setEnabled(isEnabled);
            if (virtualizer.getStrengthSupported()) {
                virtualizer.setStrength((short) 1000); // Maximum 5.1 spatial strength
            }
            Log.d(TAG, "Initialized Virtualizer for audioSessionId=" + audioSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Virtualizer effect", e);
            virtualizer = null;
        }

        try {
            // 2. Bass Boost for Cinema Sub-bass Depth
            bassBoost = new BassBoost(0, audioSessionId);
            bassBoost.setEnabled(isEnabled);
            if (bassBoost.getStrengthSupported()) {
                bassBoost.setStrength((short) 700); // Deep cinematic sub-bass
            }
            Log.d(TAG, "Initialized BassBoost for audioSessionId=" + audioSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize BassBoost effect", e);
            bassBoost = null;
        }

        try {
            // 3. Equalizer for Dialogue, Sub-bass & Treble Airiness
            equalizer = new Equalizer(0, audioSessionId);
            equalizer.setEnabled(isEnabled);
            short numBands = equalizer.getNumberOfBands();
            if (numBands > 0) {
                short[] range = equalizer.getBandLevelRange();
                for (short band = 0; band < numBands; band++) {
                    int centerFreq = equalizer.getCenterFreq(band); // in mHz (1000 mHz = 1 Hz)
                    if (centerFreq <= 250000) { // Sub-bass < 250Hz
                        short boost = (short) Math.min(range[1], 350); // +3.5dB
                        equalizer.setBandLevel(band, boost);
                    } else if (centerFreq >= 800000 && centerFreq <= 4000000) { // Dialogue 800Hz - 4kHz
                        short boost = (short) Math.min(range[1], 300); // +3.0dB
                        equalizer.setBandLevel(band, boost);
                    } else if (centerFreq >= 6000000) { // Treble detail > 6kHz
                        short boost = (short) Math.min(range[1], 250); // +2.5dB
                        equalizer.setBandLevel(band, boost);
                    }
                }
            }
            Log.d(TAG, "Initialized Equalizer for audioSessionId=" + audioSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Equalizer effect", e);
            equalizer = null;
        }
    }

    private void updateAudioEffectsState(boolean enabled) {
        try {
            if (virtualizer != null) {
                virtualizer.setEnabled(enabled);
                if (enabled && virtualizer.getStrengthSupported()) {
                    short virtStrength = (short) Math.min(1000, (int) (800 * surroundAudioProcessor.getSpatialWidth()));
                    virtualizer.setStrength(virtStrength);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating Virtualizer state", e);
        }

        try {
            if (bassBoost != null) {
                bassBoost.setEnabled(enabled);
                if (enabled && bassBoost.getStrengthSupported()) {
                    short bassStrength = (short) Math.min(1000, (int) (650 * surroundAudioProcessor.getBassBoostLevel()));
                    bassBoost.setStrength(bassStrength);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating BassBoost state", e);
        }

        try {
            if (equalizer != null) {
                equalizer.setEnabled(enabled);
                if (enabled) {
                    short numBands = equalizer.getNumberOfBands();
                    if (numBands > 0) {
                        short[] range = equalizer.getBandLevelRange();
                        float dialogueFactor = surroundAudioProcessor.getDialogueBoost();
                        float bassFactor = surroundAudioProcessor.getBassBoostLevel();
                        float midFactor = surroundAudioProcessor.getMidBoostLevel();
                        float trebleFactor = surroundAudioProcessor.getTrebleBoostLevel();
                        int mode = surroundAudioProcessor.getSpatialMode();

                        for (short band = 0; band < numBands; band++) {
                            int centerFreq = equalizer.getCenterFreq(band); // in mHz
                            short targetBoost = 0;
                            if (centerFreq <= 250000) { // Sub-bass < 250Hz
                                float baseGain = (mode == Surround51AudioProcessor.MODE_CINEMA_3D) ? 450f : 250f;
                                targetBoost = (short) Math.min(range[1], (int) (baseGain * bassFactor));
                            } else if (centerFreq > 250000 && centerFreq < 800000) { // Mids & Body (250Hz - 800Hz)
                                float baseGain = 300f;
                                targetBoost = (short) Math.min(range[1], (int) (baseGain * midFactor));
                            } else if (centerFreq >= 800000 && centerFreq <= 4000000) { // Vocal 800Hz - 4kHz
                                float baseGain = (mode == Surround51AudioProcessor.MODE_VOICE_3D) ? 550f : 300f;
                                targetBoost = (short) Math.min(range[1], (int) (baseGain * dialogueFactor));
                            } else if (centerFreq >= 6000000) { // Treble detail > 6kHz
                                float baseGain = (mode == Surround51AudioProcessor.MODE_CONCERT_3D) ? 450f : 250f;
                                targetBoost = (short) Math.min(range[1], (int) (baseGain * trebleFactor));
                            }
                            equalizer.setBandLevel(band, targetBoost);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating Equalizer state", e);
        }
    }

    private void updatePlayerAudioAttributes(boolean enabled) {
        if (player == null) {
            return;
        }

        try {
            AudioAttributes.Builder builder = new AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) { // API 32+ (Android 12L / 13)
                builder.setSpatializationBehavior(
                        enabled ? C.SPATIALIZATION_BEHAVIOR_AUTO : C.SPATIALIZATION_BEHAVIOR_NEVER
                );
            }

            player.setAudioAttributes(builder.build(), false);
            Log.d(TAG, "Updated Player AudioAttributes for 5.1 spatialization: behavior=" + (enabled ? "AUTO" : "NEVER"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to update Player AudioAttributes", e);
        }
    }

    private void detachCurrentPlayer() {
        if (player != null) {
            if (playerListener != null) {
                player.removeListener(playerListener);
                playerListener = null;
            }
            player = null;
        }
    }

    private void releaseAudioEffects() {
        if (virtualizer != null) {
            try {
                virtualizer.setEnabled(false);
                virtualizer.release();
            } catch (Exception ignored) {}
            virtualizer = null;
        }

        if (bassBoost != null) {
            try {
                bassBoost.setEnabled(false);
                bassBoost.release();
            } catch (Exception ignored) {}
            bassBoost = null;
        }

        if (equalizer != null) {
            try {
                equalizer.setEnabled(false);
                equalizer.release();
            } catch (Exception ignored) {}
            equalizer = null;
        }

        activeAudioSessionId = C.AUDIO_SESSION_ID_UNSET;
    }

    public void release() {
        detachCurrentPlayer();
        releaseAudioEffects();
        Log.d(TAG, "SurroundSoundManager released.");
    }
}
