package com.example.animelib.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.animelib.R;
import com.example.animelib.models.UpdateInfo;
import android.util.Log;

public class CustomToast {

    public static final int TYPE_WARNING = 0;
    public static final int TYPE_SUCCESS = 1;
    public static final int TYPE_INFO = 2;

    private static final String TAG_CUSTOM_TOAST = "CUSTOM_TOAST_VIEW_TAG";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Runnable currentDismissRunnable = null;
    private static java.lang.ref.WeakReference<View> lastToastViewRef = null;

    /**
     * Показывает красивое всплывающее уведомление снизу экрана.
     * Тип уведомления подбирается автоматически по тексту.
     */
    public static void show(Context context, String message) {
        if (message == null || message.trim().isEmpty()) return;
        int type = autoDetectType(message);
        show(context, message, type);
    }

    public static void showWarning(Context context, String message) {
        show(context, message, TYPE_WARNING);
    }

    public static void showSuccess(Context context, String message) {
        show(context, message, TYPE_SUCCESS);
    }

    public static void showInfo(Context context, String message) {
        show(context, message, TYPE_INFO);
    }

    public static void showUpdateAlert(Context context, UpdateInfo updateInfo) {
        if (context == null || updateInfo == null) return;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> showUpdateAlert(context, updateInfo));
            return;
        }

        Activity activity = getActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        if (activity instanceof com.example.animelib.UrlInputActivity) {
            return;
        }

        if (lastToastViewRef != null) {
            View oldToast = lastToastViewRef.get();
            if (oldToast != null) {
                try {
                    android.view.ViewParent parent = oldToast.getParent();
                    if (parent instanceof ViewGroup) {
                        ((ViewGroup) parent).removeView(oldToast);
                    }
                } catch (Exception ignored) {}
            }
            lastToastViewRef = null;
        }

        if (currentDismissRunnable != null) {
            mainHandler.removeCallbacks(currentDismissRunnable);
            currentDismissRunnable = null;
        }

        ViewGroup rootView = getTopMostRootView(activity);
        if (rootView == null) return;

        View oldViewInRoot = rootView.findViewWithTag(TAG_CUSTOM_TOAST);
        if (oldViewInRoot != null) {
            try {
                rootView.removeView(oldViewInRoot);
            } catch (Exception ignored) {}
        }

        LayoutInflater inflater = LayoutInflater.from(activity);
        View toastView = inflater.inflate(R.layout.layout_custom_toast, rootView, false);
        toastView.setTag(TAG_CUSTOM_TOAST);

        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int maxToastWidth = Math.min((int) (screenWidth * 0.90f), Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 360f, activity.getResources().getDisplayMetrics())));
        int reservedPadding = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 60f, activity.getResources().getDisplayMetrics()));

        ImageView iconView = toastView.findViewById(R.id.customToastIcon);
        TextView textView = toastView.findViewById(R.id.customToastText);

        String tagName = updateInfo.getTagName() != null ? updateInfo.getTagName() : "";
        String message = "Доступно обновление " + tagName + "! Нажмите, чтобы обновиться";
        textView.setText(message);
        textView.setMaxWidth(Math.max(maxToastWidth - reservedPadding, 140));

        ViewGroup.LayoutParams lp;
        int marginBottom = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 80f, activity.getResources().getDisplayMetrics()));
        int marginHorizontal = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, activity.getResources().getDisplayMetrics()));

        if (rootView instanceof android.widget.FrameLayout) {
            android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            flp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            flp.bottomMargin = marginBottom;
            flp.leftMargin = marginHorizontal;
            flp.rightMargin = marginHorizontal;
            lp = flp;
        } else if (rootView instanceof android.widget.RelativeLayout) {
            android.widget.RelativeLayout.LayoutParams rlp = new android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            rlp.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
            rlp.bottomMargin = marginBottom;
            rlp.leftMargin = marginHorizontal;
            rlp.rightMargin = marginHorizontal;
            lp = rlp;
        } else if (rootView instanceof androidx.coordinatorlayout.widget.CoordinatorLayout) {
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams clp = new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            clp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            clp.bottomMargin = marginBottom;
            clp.leftMargin = marginHorizontal;
            clp.rightMargin = marginHorizontal;
            lp = clp;
        } else if (rootView instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout.LayoutParams llp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            llp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            llp.bottomMargin = marginBottom;
            llp.leftMargin = marginHorizontal;
            llp.rightMargin = marginHorizontal;
            lp = llp;
        } else {
            ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            mlp.bottomMargin = marginBottom;
            mlp.leftMargin = marginHorizontal;
            mlp.rightMargin = marginHorizontal;
            lp = mlp;
        }

        toastView.setLayoutParams(lp);

        toastView.setElevation(2000f);
        toastView.setTranslationZ(2000f);
        androidx.core.view.ViewCompat.setElevation(toastView, 2000f);
        androidx.core.view.ViewCompat.setTranslationZ(toastView, 2000f);

        iconView.setImageResource(R.drawable.ic_toast_update);

        boolean isDark = isDarkTheme(activity);
        android.graphics.drawable.GradientDrawable pillBg = new android.graphics.drawable.GradientDrawable();
        pillBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        float cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20f, activity.getResources().getDisplayMetrics());
        pillBg.setCornerRadius(cornerRadius);

        int strokeWidth = Math.max(1, Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 0.5f, activity.getResources().getDisplayMetrics())));

        if (isDark) {
            // Темная тема: темный плавающий пилл с изящной контрастной рамкой
            pillBg.setColor(android.graphics.Color.parseColor("#25242D"));
            pillBg.setStroke(strokeWidth, android.graphics.Color.parseColor("#3D3C48"));
            textView.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.app_text_primary));
        } else {
            // Светлая тема: чистый белый пилл с мягкой рамкой и темным текстом
            pillBg.setColor(android.graphics.Color.parseColor("#FFFFFF"));
            pillBg.setStroke(strokeWidth, android.graphics.Color.parseColor("#E2E8F0"));
            textView.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.app_text_primary));
        }
        toastView.setBackground(pillBg);

        float initialTranslationY = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 48f, activity.getResources().getDisplayMetrics());
        toastView.setAlpha(0f);
        toastView.setScaleX(0.88f);
        toastView.setScaleY(0.88f);
        toastView.setTranslationY(initialTranslationY);

        toastView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();

        toastView.setOnClickListener(v -> {
            dismissToast(rootView, toastView);
            com.example.animelib.UpdateActivity.start(activity, updateInfo);
        });

        currentDismissRunnable = () -> dismissToast(rootView, toastView);
        mainHandler.postDelayed(currentDismissRunnable, 10000);

        try {
            rootView.addView(toastView);
            lastToastViewRef = new java.lang.ref.WeakReference<>(toastView);
        } catch (Exception e) {
            Log.e("CustomToast", "Failed to add update alert to rootView", e);
        }
    }

    public static void show(Context context, String message, int type) {
        if (context == null || message == null || message.trim().isEmpty()) return;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> show(context, message, type));
            return;
        }

        Activity activity = getActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            // Фолбэк на стандартный тост, если не удалось получить Activity
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            return;
        }

        // Удаляем предыдущий тост из любого родительского View
        if (lastToastViewRef != null) {
            View oldToast = lastToastViewRef.get();
            if (oldToast != null) {
                try {
                    android.view.ViewParent parent = oldToast.getParent();
                    if (parent instanceof ViewGroup) {
                        ((ViewGroup) parent).removeView(oldToast);
                    }
                } catch (Exception ignored) {}
            }
            lastToastViewRef = null;
        }

        // Отменяем предыдущие запланированные скрытия
        if (currentDismissRunnable != null) {
            mainHandler.removeCallbacks(currentDismissRunnable);
            currentDismissRunnable = null;
        }

        ViewGroup rootView = getTopMostRootView(activity);
        if (rootView == null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            return;
        }

        View oldViewInRoot = rootView.findViewWithTag(TAG_CUSTOM_TOAST);
        if (oldViewInRoot != null) {
            try {
                rootView.removeView(oldViewInRoot);
            } catch (Exception ignored) {}
        }

        // Инфлейтим новый макет
        LayoutInflater inflater = LayoutInflater.from(activity);
        View toastView = inflater.inflate(R.layout.layout_custom_toast, rootView, false);
        toastView.setTag(TAG_CUSTOM_TOAST);

        // Гарантируем правильные параметры выравнивания по центру снизу с высокими слоями z-index
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int maxToastWidth = Math.min((int) (screenWidth * 0.82f), Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 320f, activity.getResources().getDisplayMetrics())));
        int reservedPadding = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 70f, activity.getResources().getDisplayMetrics()));

        ImageView iconView = toastView.findViewById(R.id.customToastIcon);
        TextView textView = toastView.findViewById(R.id.customToastText);

        textView.setText(message);
        textView.setMaxWidth(Math.max(maxToastWidth - reservedPadding, 120));

        ViewGroup.LayoutParams lp;
        int marginBottom = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 72f, activity.getResources().getDisplayMetrics()));
        int marginHorizontal = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, activity.getResources().getDisplayMetrics()));

        if (rootView instanceof android.widget.FrameLayout) {
            android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            flp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            flp.bottomMargin = marginBottom;
            flp.leftMargin = marginHorizontal;
            flp.rightMargin = marginHorizontal;
            lp = flp;
        } else if (rootView instanceof android.widget.RelativeLayout) {
            android.widget.RelativeLayout.LayoutParams rlp = new android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            rlp.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
            rlp.bottomMargin = marginBottom;
            rlp.leftMargin = marginHorizontal;
            rlp.rightMargin = marginHorizontal;
            lp = rlp;
        } else if (rootView instanceof androidx.coordinatorlayout.widget.CoordinatorLayout) {
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams clp = new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            clp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            clp.bottomMargin = marginBottom;
            clp.leftMargin = marginHorizontal;
            clp.rightMargin = marginHorizontal;
            lp = clp;
        } else if (rootView instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout.LayoutParams llp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            llp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            llp.bottomMargin = marginBottom;
            llp.leftMargin = marginHorizontal;
            llp.rightMargin = marginHorizontal;
            lp = llp;
        } else {
            ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            mlp.bottomMargin = marginBottom;
            mlp.leftMargin = marginHorizontal;
            mlp.rightMargin = marginHorizontal;
            lp = mlp;
        }

        toastView.setLayoutParams(lp);

        // Высокий z-index / elevation для отображения поверх диалогов и BottomSheet
        toastView.setElevation(2000f);
        toastView.setTranslationZ(2000f);
        androidx.core.view.ViewCompat.setElevation(toastView, 2000f);
        androidx.core.view.ViewCompat.setTranslationZ(toastView, 2000f);

        switch (type) {
            case TYPE_SUCCESS:
                iconView.setImageResource(R.drawable.ic_toast_success);
                break;
            case TYPE_INFO:
                iconView.setImageResource(R.drawable.ic_toast_info);
                break;
            case TYPE_WARNING:
            default:
                iconView.setImageResource(R.drawable.ic_toast_warning);
                break;
        }

        try {
            rootView.addView(toastView);
            lastToastViewRef = new java.lang.ref.WeakReference<>(toastView);
        } catch (Exception e) {
            android.util.Log.e("CustomToast", "Failed to add toast to rootView", e);
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            return;
        }

        // Адаптация под тему (Light / Dark)
        boolean isDark = isDarkTheme(activity);
        android.graphics.drawable.GradientDrawable pillBg = new android.graphics.drawable.GradientDrawable();
        pillBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        float cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20f, activity.getResources().getDisplayMetrics());
        pillBg.setCornerRadius(cornerRadius);

        int strokeWidth = Math.max(1, Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 0.5f, activity.getResources().getDisplayMetrics())));

        if (isDark) {
            // Темная тема: темный плавающий пилл с изящной контрастной рамкой
            pillBg.setColor(android.graphics.Color.parseColor("#25242D"));
            pillBg.setStroke(strokeWidth, android.graphics.Color.parseColor("#3D3C48"));
            textView.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.app_text_primary));
        } else {
            // Светлая тема: чистый белый пилл с мягкой рамкой и темным текстом
            pillBg.setColor(android.graphics.Color.parseColor("#FFFFFF"));
            pillBg.setStroke(strokeWidth, android.graphics.Color.parseColor("#E2E8F0"));
            textView.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.app_text_primary));
        }
        toastView.setBackground(pillBg);

        // Начальное состояние для плавного всплывания с масшабированием
        float initialTranslationY = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 48f, activity.getResources().getDisplayMetrics());
        toastView.setAlpha(0f);
        toastView.setScaleX(0.88f);
        toastView.setScaleY(0.88f);
        toastView.setTranslationY(initialTranslationY);

        // Анимация появления снизу вверх с эффектом легкой упругости
        toastView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();

        // Скрытие по клику
        toastView.setOnClickListener(v -> dismissToast(rootView, toastView));

        // Таймер скрытия через 3.2 секунды
        currentDismissRunnable = () -> dismissToast(rootView, toastView);
        mainHandler.postDelayed(currentDismissRunnable, 3200);
    }

    private static ViewGroup getTopMostRootView(Activity activity) {
        if (activity == null) return null;

        // 1. Попытка получить топовый View из WindowManagerGlobal (активный диалог / BottomSheet)
        try {
            Class<?> wmClass = Class.forName("android.view.WindowManagerGlobal");
            Object wmInstance = wmClass.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field viewsField = wmClass.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            Object viewsObj = viewsField.get(wmInstance);

            if (viewsObj instanceof java.util.List) {
                java.util.List<?> views = (java.util.List<?>) viewsObj;
                for (int i = views.size() - 1; i >= 0; i--) {
                    Object obj = views.get(i);
                    if (obj instanceof View) {
                        View v = (View) obj;
                        if (v.isShown() && v.isAttachedToWindow() && v.getWindowVisibility() == View.VISIBLE) {
                            if (v instanceof ViewGroup) {
                                return (ViewGroup) v;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.d("CustomToast", "WindowManagerGlobal reflection fallback: " + t.getMessage());
        }

        // 2. Попытка через FragmentManager найти видимый DialogFragment
        if (activity instanceof androidx.fragment.app.FragmentActivity) {
            try {
                androidx.fragment.app.FragmentManager fm = ((androidx.fragment.app.FragmentActivity) activity).getSupportFragmentManager();
                java.util.List<androidx.fragment.app.Fragment> fragments = fm.getFragments();
                if (fragments != null) {
                    for (int i = fragments.size() - 1; i >= 0; i--) {
                        androidx.fragment.app.Fragment f = fragments.get(i);
                        if (f instanceof androidx.fragment.app.DialogFragment) {
                            androidx.fragment.app.DialogFragment df = (androidx.fragment.app.DialogFragment) f;
                            if (df.isVisible() && df.getDialog() != null && df.getDialog().isShowing()) {
                                android.view.Window w = df.getDialog().getWindow();
                                if (w != null && w.getDecorView() instanceof ViewGroup) {
                                    return (ViewGroup) w.getDecorView();
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 3. Фолбэк на DecorView или android.R.id.content стандартной Activity
        android.view.Window window = activity.getWindow();
        if (window != null && window.getDecorView() instanceof ViewGroup) {
            return (ViewGroup) window.getDecorView();
        }

        View content = activity.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            return (ViewGroup) content;
        }

        return null;
    }

    private static void dismissToast(ViewGroup rootView, View toastView) {
        if (toastView == null) return;
        
        if (currentDismissRunnable != null) {
            mainHandler.removeCallbacks(currentDismissRunnable);
            currentDismissRunnable = null;
        }

        float exitTranslationY = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 36f, toastView.getContext().getResources().getDisplayMetrics());

        toastView.animate()
                .alpha(0f)
                .translationY(exitTranslationY)
                .setDuration(220)
                .setInterpolator(new AccelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    try {
                        android.view.ViewParent parent = toastView.getParent();
                        if (parent instanceof ViewGroup) {
                            ((ViewGroup) parent).removeView(toastView);
                        } else if (rootView != null) {
                            rootView.removeView(toastView);
                        }
                    } catch (Exception ignored) {}
                })
                .start();
    }

    private static Activity getActivity(Context context) {
        if (context == null) return null;
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static int autoDetectType(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("успеш") || lower.contains("сохранен") || lower.contains("удален") 
                || lower.contains("завершен") || lower.contains("опубликован") || lower.contains("добавлен")) {
            return TYPE_SUCCESS;
        }
        if (lower.contains("правила") || lower.contains("информац")) {
            return TYPE_INFO;
        }
        return TYPE_WARNING;
    }

    public static boolean isDarkTheme(Context context) {
        if (context == null) return false;

        // 1. Проверяем сохраненную настройку темы пользователя через ThemeUtils
        try {
            int savedTheme = ThemeUtils.getSavedThemePreference(context);
            if (savedTheme == ThemeUtils.THEME_LIGHT) {
                return false;
            } else if (savedTheme == ThemeUtils.THEME_DARK) {
                return true;
            }
        } catch (Exception ignored) {}

        // 2. Проверяем глобальный AppCompatDelegate режим
        try {
            int defaultNight = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
            if (defaultNight == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
                return false;
            } else if (defaultNight == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                return true;
            }
        } catch (Exception ignored) {}

        // 3. Проверяем локальный режим активности
        if (context instanceof androidx.appcompat.app.AppCompatActivity) {
            try {
                int localNight = ((androidx.appcompat.app.AppCompatActivity) context).getDelegate().getLocalNightMode();
                if (localNight == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
                    return false;
                } else if (localNight == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        // 4. Проверяем системную конфигурацию UI_MODE_NIGHT_MASK
        try {
            int nightMode = context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
                return false;
            } else if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                return true;
            }
        } catch (Exception ignored) {}

        // 5. Фолбэк: определяем тему по цвету текста темы (в светлой теме primaryTextColor темный)
        try {
            TypedValue typedValue = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
                    || context.getTheme().resolveAttribute(R.attr.primaryTextColor, typedValue, true)) {
                int color;
                if (typedValue.resourceId != 0) {
                    color = androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId);
                } else {
                    color = typedValue.data;
                }
                double luminance = (0.299 * android.graphics.Color.red(color) 
                        + 0.587 * android.graphics.Color.green(color) 
                        + 0.114 * android.graphics.Color.blue(color)) / 255.0;
                return luminance > 0.5;
            }
        } catch (Exception ignored) {}

        return false;
    }
}
