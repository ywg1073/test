package cxyonly.fans.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_cache")
public class AppCacheEntity {
    @PrimaryKey
    @NonNull
    public String cacheKey = "";

    public String jsonContent;
    public long lastModifyTime;
}
