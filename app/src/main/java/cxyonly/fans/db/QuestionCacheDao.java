package cxyonly.fans.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface QuestionCacheDao {
    @Query("SELECT * FROM question_cache WHERE questionId = :id LIMIT 1")
    QuestionCacheEntity getById(String id);

    @Query("SELECT * FROM question_cache WHERE needSync = 1")
    List<QuestionCacheEntity> getPendingSync();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(QuestionCacheEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<QuestionCacheEntity> entities);
}
