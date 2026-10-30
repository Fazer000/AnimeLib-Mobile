package com.example.animelib.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.animelib.data.entity.PendingSyncTaskEntity;

import java.util.List;

@Dao
public interface PendingSyncTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertTask(PendingSyncTaskEntity task);

    @Query("SELECT * FROM pending_sync_tasks ORDER BY createdAt ASC")
    List<PendingSyncTaskEntity> getAllTasksSync();

    @Query("SELECT * FROM pending_sync_tasks WHERE taskType = 'VIEW' AND animeId = :animeId AND playerId = :playerId LIMIT 1")
    PendingSyncTaskEntity findViewTaskSync(String animeId, int playerId);

    @Update
    void updateTask(PendingSyncTaskEntity task);

    @Query("DELETE FROM pending_sync_tasks WHERE id = :id")
    void deleteTaskById(long id);

    @Query("DELETE FROM pending_sync_tasks")
    void deleteAllTasksSync();
}
