package cxyonly.fans.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface PracticeCacheDao {
    @Query("SELECT * FROM practice_cache WHERE categoryId = :categoryId LIMIT 1")
    PracticeCacheEntity getByCategory(String categoryId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(PracticeCacheEntity entity);
}
