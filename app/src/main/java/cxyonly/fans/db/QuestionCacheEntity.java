package cxyonly.fans.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "question_cache")
public class QuestionCacheEntity {
    @PrimaryKey
    @NonNull
    public String questionId = "";

    public String jsonContent;
    public String mastery;
    public String note;
    public boolean isFavorite;
    public boolean needSync;
    public boolean favoritePending;
    public boolean masteryPending;
    public boolean notePending;
    public long lastModifyTime;
}
