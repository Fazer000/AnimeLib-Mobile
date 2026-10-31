package com.example.animelib;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.animelib.util.ImageLoader;

import java.lang.ref.WeakReference;

public class AnimeLibApplication extends Application {
    private static AnimeLibApplication instance;
    private static volatile WeakReference<Activity> currentActivityRef;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ImageLoader.init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    currentActivityRef = new WeakReference<>(activity);
                }
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    currentActivityRef = new WeakReference<>(activity);
                }
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                WeakReference<Activity> ref = currentActivityRef;
                if (ref != null && ref.get() == activity && (activity.isFinishing() || activity.isDestroyed())) {
                    currentActivityRef = null;
                }
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                WeakReference<Activity> ref = currentActivityRef;
                if (ref != null && ref.get() == activity) {
                    currentActivityRef = null;
                }
            }
        });
    }

    public static AnimeLibApplication getInstance() {
        return instance;
    }

    @Nullable
    public static Activity getCurrentActivity() {
        WeakReference<Activity> ref = currentActivityRef;
        if (ref == null) return null;
        Activity activity = ref.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }
        return activity;
    }
}
