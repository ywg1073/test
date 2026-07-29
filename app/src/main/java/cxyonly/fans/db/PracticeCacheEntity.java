package cxyonly.fans.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "practice_cache")
public class PracticeCacheEntity {
    @PrimaryKey
    @NonNull
    public String categoryId = "";

    public String jsonContent;
    public long lastModifyTime;
}
