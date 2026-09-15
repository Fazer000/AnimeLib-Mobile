package com.example.animelib.controllers;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.SensorManager;
import android.view.OrientationEventListener;

/**
 * Контроллер отслеживания физической ориентации устройства и переключения режимов экрана.
 */
public class PlayerOrientationController {

    private final Activity activity;
    private OrientationEventListener orientationEventListener;
    private int lastPhysicalOrientation = -1;
    private boolean manualOrientationOverride = false;

    public PlayerOrientationController(Activity activity) {
        this.activity = activity;
    }

    public void init() {
        orientationEventListener = new OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;

                int currentPhysicalOrientation;
                if ((orientation >= 315 || orientation < 45) || (orientation >= 135 && orientation < 225)) {
                    currentPhysicalOrientation = Configuration.ORIENTATION_PORTRAIT;
                } else if ((orientation >= 45 && orientation < 135) || (orientation >= 225 && orientation < 315)) {
                    currentPhysicalOrientation = Configuration.ORIENTATION_LANDSCAPE;
                } else {
                    return;
                }

                if (lastPhysicalOrientation != -1 && currentPhysicalOrientation != lastPhysicalOrientation) {
                    if (manualOrientationOverride) {
                        manualOrientationOverride = false;
                        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                    }
                }
                lastPhysicalOrientation = currentPhysicalOrientation;
            }
        };

        enable();
    }

    public void toggleFullscreenOrientation() {
        boolean isPortrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        manualOrientationOverride = true;
        if (isPortrait) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    public void enable() {
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    public void disable() {
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    public void cleanup() {
        disable();
        orientationEventListener = null;
    }
}
