package cxyonly.fans.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FavoriteQuestionDao {
    @Query("SELECT * FROM favorite_questions ORDER BY itemOrder ASC")
    List<FavoriteQuestion> getAll();

    @Query("SELECT * FROM favorite_questions WHERE id = :id LIMIT 1")
    FavoriteQuestion getById(String id);

    @Query("SELECT * FROM favorite_questions WHERE needSync = 1")
    List<FavoriteQuestion> getPendingSync();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(FavoriteQuestion question);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<FavoriteQuestion> questions);

    @Query("DELETE FROM favorite_questions WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM favorite_questions")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM favorite_questions")
    int getCount();
}
