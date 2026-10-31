package com.example.animelib.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.animelib.data.entity.PlayerPreferences;

/**
 * DAO для работы с предпочтениями плеера и озвучки
 */
@Dao
public interface PlayerPreferencesDao {
    
    /**
     * Вставка или обновление предпочтений (upsert)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PlayerPreferences preferences);
    
    /**
     * Получение предпочтений синхронно (самая актуальная запись)
     */
    @Query("SELECT * FROM player_preferences ORDER BY id DESC LIMIT 1")
    PlayerPreferences getPreferencesSync();

    /**
     * Удаление устаревших дублирующих строк при наличии
     */
    @Query("DELETE FROM player_preferences WHERE id != :keepId")
    void deleteDuplicatesExcept(int keepId);
    
    /**
     * Получение количества записей (для проверки существования)
     */
    @Query("SELECT COUNT(*) FROM player_preferences")
    int getCount();
}





