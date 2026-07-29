package cxyonly.fans.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface AppCacheDao {
    @Query("SELECT * FROM app_cache WHERE cacheKey = :key LIMIT 1")
    AppCacheEntity getByKey(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(AppCacheEntity entity);
}
