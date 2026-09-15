package com.example.animelib.controllers;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;
import android.util.Rational;

import androidx.media3.exoplayer.ExoPlayer;

import com.example.animelib.R;

import java.util.ArrayList;
import java.util.List;

public class PlayerPipController {

    private static final String TAG = "PlayerPipController";

    public static final String ACTION_PIP_PLAY_PAUSE = "com.example.animelib.PIP_PLAY_PAUSE";
    public static final String ACTION_PIP_REWIND = "com.example.animelib.PIP_REWIND";
    public static final String ACTION_PIP_FAST_FORWARD = "com.example.animelib.PIP_FAST_FORWARD";

    private static final int PIP_REQ_PLAY_PAUSE = 101;
    private static final int PIP_REQ_REWIND = 102;
    private static final int PIP_REQ_FAST_FORWARD = 103;

    private final Activity activity;
    private final PipCallback callback;
    private BroadcastReceiver pipBroadcastReceiver;
    private boolean isInPictureInPictureMode = false;

    public interface PipCallback {
        ExoPlayer getPlayer();
        void onPipEnterUIState();
        void onPipExitUIState();
        void onPipSeekingStarted();
        void onPipSeekingEnded();
    }

    public PlayerPipController(Activity activity, PipCallback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public boolean isInPictureInPictureMode() {
        return isInPictureInPictureMode;
    }

    public void setInPictureInPictureMode(boolean inPip) {
        this.isInPictureInPictureMode = inPip;
    }

    public void setupPipReceiver() {
        if (pipBroadcastReceiver != null) return;

        pipBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                ExoPlayer player = callback != null ? callback.getPlayer() : null;
                if (player == null) return;

                String action = intent.getAction();
                if (ACTION_PIP_PLAY_PAUSE.equals(action)) {
                    if (player.isPlaying()) {
                        player.pause();
                    } else {
                        player.play();
                    }
                    updatePictureInPictureParams();
                } else if (ACTION_PIP_REWIND.equals(action)) {
                    long currentPos = player.getCurrentPosition();
                    long newPos = Math.max(0, currentPos - 10000);
                    if (callback != null) callback.onPipSeekingStarted();
                    player.seekTo(newPos);
                    if (callback != null) callback.onPipSeekingEnded();
                } else if (ACTION_PIP_FAST_FORWARD.equals(action)) {
                    long currentPos = player.getCurrentPosition();
                    long duration = player.getDuration();
                    long newPos = duration > 0 ? Math.min(duration, currentPos + 10000) : currentPos + 10000;
                    if (callback != null) callback.onPipSeekingStarted();
                    player.seekTo(newPos);
                    if (callback != null) callback.onPipSeekingEnded();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PIP_PLAY_PAUSE);
        filter.addAction(ACTION_PIP_REWIND);
        filter.addAction(ACTION_PIP_FAST_FORWARD);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(pipBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(pipBroadcastReceiver, filter);
        }
    }

    public void unregisterPipReceiver() {
        if (pipBroadcastReceiver != null) {
            try {
                activity.unregisterReceiver(pipBroadcastReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering PIP receiver", e);
            }
            pipBroadcastReceiver = null;
        }
    }

    public List<RemoteAction> buildPipActions() {
        List<RemoteAction> actions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Rewind 10s
            Intent rewindIntent = new Intent(ACTION_PIP_REWIND).setPackage(activity.getPackageName());
            PendingIntent rewindPendingIntent = PendingIntent.getBroadcast(
                    activity, PIP_REQ_REWIND, rewindIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            Icon rewindIcon = Icon.createWithResource(activity, R.drawable.ic_pip_rewind);
            actions.add(new RemoteAction(rewindIcon, "10 сек назад", "Перемотать на 10 секунд назад", rewindPendingIntent));

            // Play / Pause
            ExoPlayer player = callback != null ? callback.getPlayer() : null;
            boolean isPlaying = player != null && player.isPlaying();
            Intent playPauseIntent = new Intent(ACTION_PIP_PLAY_PAUSE).setPackage(activity.getPackageName());
            PendingIntent playPausePendingIntent = PendingIntent.getBroadcast(
                    activity, PIP_REQ_PLAY_PAUSE, playPauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            Icon playPauseIcon = Icon.createWithResource(
                    activity,
                    isPlaying ? R.drawable.ic_pip_pause : R.drawable.ic_pip_play
            );
            String playPauseTitle = isPlaying ? "Пауза" : "Воспроизведение";
            actions.add(new RemoteAction(playPauseIcon, playPauseTitle, playPauseTitle, playPausePendingIntent));

            // Fast Forward 10s
            Intent ffIntent = new Intent(ACTION_PIP_FAST_FORWARD).setPackage(activity.getPackageName());
            PendingIntent ffPendingIntent = PendingIntent.getBroadcast(
                    activity, PIP_REQ_FAST_FORWARD, ffIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            Icon ffIcon = Icon.createWithResource(activity, R.drawable.ic_pip_fast_forward);
            actions.add(new RemoteAction(ffIcon, "10 сек вперед", "Перемотать на 10 секунд вперед", ffPendingIntent));
        }
        return actions;
    }

    public void updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            try {
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .setActions(buildPipActions())
                        .build();
                activity.setPictureInPictureParams(params);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update Picture-in-Picture params", e);
            }
        }
    }

    public void enterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .setActions(buildPipActions())
                        .build();
                activity.enterPictureInPictureMode(params);
            } catch (Exception e) {
                Log.e(TAG, "Failed to enter Picture-in-Picture mode", e);
            }
        }
    }

    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        if (isInPictureInPictureMode) {
            ExoPlayer player = callback != null ? callback.getPlayer() : null;
            if (player != null && !player.isPlaying()) {
                player.play();
                Log.d(TAG, "Resumed playback in PiP mode");
            }
            if (callback != null) callback.onPipEnterUIState();
            updatePictureInPictureParams();
            Log.d(TAG, "Entered Picture-in-Picture mode");
        } else {
            if (callback != null) callback.onPipExitUIState();
            Log.d(TAG, "Exited Picture-in-Picture mode");
        }
    }
}
