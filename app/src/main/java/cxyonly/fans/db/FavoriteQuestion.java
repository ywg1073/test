package cxyonly.fans.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "favorite_questions")
public class FavoriteQuestion {
    @PrimaryKey
    @NonNull
    public String id = "";

    public int itemOrder;
    public String questionNumber;
    public String source;
    public String stemHTML;
    public String stemPreview;
    public String optionsJson;
    public String correctLabels;
    public String optionsHTML;
    public String answerHTML;
    public String solutionHTML;
    public String category;
    public String time;
    public String rawMastery;
    public String rawFavoritedAt;
    public String note;
    public String categoryPath;
    public boolean needSync;
    public long lastModifyTime;
}
