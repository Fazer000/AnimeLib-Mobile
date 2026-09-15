package com.example.animelib.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.example.animelib.R;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.ThemeUtils;

public class ThemeSelectionBottomSheet extends FlexibleBottomSheetDialog {
    
    public interface OnThemeChangedListener {
        void onThemeChanged(int themeMode);
    }
    
    public interface OnBackPressedListener {
        void onBackPressed();
    }
    
    private OnThemeChangedListener listener;
    private OnBackPressedListener onBackPressedListener;
    private int currentTheme;
    
    private View themeAutoPill;
    private View themeAutoUnselected;
    private View themeLightPill;
    private View themeLightUnselected;
    private View themeDarkPill;
    private View themeDarkUnselected;
    
    public ThemeSelectionBottomSheet(@NonNull Context context, int currentTheme, OnThemeChangedListener listener) {
        super(context, com.example.animelib.R.style.ThemeOverlay_AnimeLIB_BottomSheetDialog);
        this.currentTheme = currentTheme;
        this.listener = listener;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_theme_selection, null);
        setContentView(view);
        com.example.animelib.util.FloatingBottomSheetUtils.setupFloatingStyle(this);

        // Находим элементы
        ImageButton closeButton = view.findViewById(R.id.bs_theme_back);
        LinearLayout themeAutoOption = view.findViewById(R.id.themeAutoOption);
        LinearLayout themeLightOption = view.findViewById(R.id.themeLightOption);
        LinearLayout themeDarkOption = view.findViewById(R.id.themeDarkOption);
        
        themeAutoPill = view.findViewById(R.id.themeAutoPill);
        themeAutoUnselected = view.findViewById(R.id.themeAutoUnselected);
        themeLightPill = view.findViewById(R.id.themeLightPill);
        themeLightUnselected = view.findViewById(R.id.themeLightUnselected);
        themeDarkPill = view.findViewById(R.id.themeDarkPill);
        themeDarkUnselected = view.findViewById(R.id.themeDarkUnselected);
        
        // Устанавливаем текущую тему
        updateThemeSelection();
        
        // Обработчики кликов
        closeButton.setOnClickListener(v -> {
            if (onBackPressedListener != null) {
                dismiss();
                onBackPressedListener.onBackPressed();
            } else {
                dismiss();
            }
        });
        
        themeAutoOption.setOnClickListener(v -> selectTheme(ThemeUtils.THEME_SYSTEM));
        themeLightOption.setOnClickListener(v -> selectTheme(ThemeUtils.THEME_LIGHT));
        themeDarkOption.setOnClickListener(v -> selectTheme(ThemeUtils.THEME_DARK));
        
        // Закрытие при клике вне диалога
        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }
    
    private void selectTheme(int themeMode) {
        if (themeMode != currentTheme) {
            currentTheme = themeMode;
            updateThemeSelection();
            
            // Применяем тему без перезагрузки активности
            if (getContext() instanceof android.app.Activity) {
                ThemeUtils.applyThemeToActivity((android.app.Activity) getContext(), themeMode);
            } else {
                ThemeUtils.applyTheme(themeMode);
            }
            
            // Уведомляем слушателя
            if (listener != null) {
                listener.onThemeChanged(themeMode);
            }
        }
        dismiss();
    }
    
    private void updateThemeSelection() {
        boolean isAuto = currentTheme == ThemeUtils.THEME_SYSTEM;
        boolean isLight = currentTheme == ThemeUtils.THEME_LIGHT;
        boolean isDark = currentTheme == ThemeUtils.THEME_DARK;

        if (themeAutoPill != null) themeAutoPill.setVisibility(isAuto ? View.VISIBLE : View.GONE);
        if (themeAutoUnselected != null) themeAutoUnselected.setVisibility(isAuto ? View.GONE : View.VISIBLE);

        if (themeLightPill != null) themeLightPill.setVisibility(isLight ? View.VISIBLE : View.GONE);
        if (themeLightUnselected != null) themeLightUnselected.setVisibility(isLight ? View.GONE : View.VISIBLE);

        if (themeDarkPill != null) themeDarkPill.setVisibility(isDark ? View.VISIBLE : View.GONE);
        if (themeDarkUnselected != null) themeDarkUnselected.setVisibility(isDark ? View.GONE : View.VISIBLE);
    }
    
    public int getCurrentTheme() {
        return currentTheme;
    }
    
    public String getCurrentThemeText() {
        switch (currentTheme) {
            case ThemeUtils.THEME_SYSTEM:
                return "Авто";
            case ThemeUtils.THEME_LIGHT:
                return "Светлая";
            case ThemeUtils.THEME_DARK:
                return "Темная";
            default:
                return "Авто";
        }
    }
    
    public void setOnBackPressedListener(OnBackPressedListener listener) {
        this.onBackPressedListener = listener;
    }
    
    @Override
    public void onBackPressed() {
        if (onBackPressedListener != null) {
            onBackPressedListener.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }
}
