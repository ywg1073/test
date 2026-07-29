package cxyonly.fans.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {
        FavoriteQuestion.class,
        AppCacheEntity.class,
        PracticeCacheEntity.class,
        QuestionCacheEntity.class
}, version = 3, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;
    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE question_cache ADD COLUMN favoritePending INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE question_cache ADD COLUMN masteryPending INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE question_cache ADD COLUMN notePending INTEGER NOT NULL DEFAULT 0");
        }
    };

    public abstract FavoriteQuestionDao favoriteQuestionDao();
    public abstract AppCacheDao appCacheDao();
    public abstract PracticeCacheDao practiceCacheDao();
    public abstract QuestionCacheDao questionCacheDao();

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "cxy_favorites.db"
                    ).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration().build();
                }
            }
        }
        return INSTANCE;
    }
}
