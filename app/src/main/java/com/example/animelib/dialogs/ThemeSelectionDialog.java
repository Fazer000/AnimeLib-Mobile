package com.example.animelib.dialogs;

import android.content.Context;
import android.util.Log;

import com.example.animelib.api.ApiService;
import com.example.animelib.settings.ThemeSelectionBottomSheet;
import com.example.animelib.util.ThemeUtils;

/**
 * Диалог выбора темы приложения (использует BottomSheet)
 */
public class ThemeSelectionDialog {
    
    private static final String TAG = "ThemeSelectionDialog";
    
    private final Context context;
    private final ApiService apiService;
    
    /**
     * Конструктор ThemeSelectionDialog
     * @param context Контекст приложения
     * @param apiService Сервис для API запросов
     */
    public ThemeSelectionDialog(Context context, ApiService apiService) {
        this.context = context;
        this.apiService = apiService;
    }
    
    /**
     * Показывает диалог выбора темы в формате BottomSheet
     */
    public void show() {
        Log.d(TAG, "Showing theme bottom sheet dialog");
        int currentTheme = ThemeUtils.getSavedThemePreference(context);
        
        ThemeSelectionBottomSheet bottomSheet = new ThemeSelectionBottomSheet(context, currentTheme, themeMode -> {
            Log.d(TAG, "Theme selected: " + themeMode);
            
            if (apiService != null) {
                apiService.saveThemeSetting(themeMode);
            }
            
            ThemeUtils.saveThemePreference(context, themeMode);
            
            if (context instanceof com.example.animelib.MainActivity) {
                ((com.example.animelib.MainActivity) context).onAppThemeSelected(themeMode);
            } else if (context instanceof android.app.Activity) {
                ThemeUtils.applyThemeToActivity((android.app.Activity) context, themeMode);
            } else {
                ThemeUtils.applyTheme(themeMode);
            }
        });
        
        bottomSheet.show();
    }
}

