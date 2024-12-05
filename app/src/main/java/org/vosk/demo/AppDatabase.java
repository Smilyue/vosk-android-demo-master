package org.vosk.demo;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.Executors;

@Database(entities = {KeywordAction.class}, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    public abstract InstructionDao instructionDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getDatabase(final Context context){
        if (INSTANCE ==null){
            synchronized (AppDatabase.class){
                if (INSTANCE ==null){
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class,"database-Voice command")
                            .addCallback(new Callback() {
                                @Override
                                public void onCreate(@NonNull SupportSQLiteDatabase db) {
                                    super.onCreate(db);

                                    Executors.newSingleThreadExecutor().execute(() -> {
                                        InstructionDao dao = INSTANCE.instructionDao();
                                        dao.insert(new KeywordAction("右轉", 1));
                                        dao.insert(new KeywordAction("左轉", 2));
                                        dao.insert(new KeywordAction("前進",3));
                                        dao.insert(new KeywordAction("後退",4));
                                        dao.insert(new KeywordAction("開始",5));
                                        dao.insert(new KeywordAction("返回",6));
                                        dao.insert(new KeywordAction("停止",7));
                                        dao.insert(new KeywordAction("上升",8));
                                        dao.insert(new KeywordAction("降落",9));


                                    });
                                }
                            })
                            .build();
                }
            }
        }return INSTANCE;
    }


}
