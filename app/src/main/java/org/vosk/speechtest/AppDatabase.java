package org.vosk.speechtest;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.Executors;

@Database(entities = {KeywordAction.class}, version = 6)
public abstract class AppDatabase extends RoomDatabase {
    public abstract InstructionDao instructionDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getDatabase(final Context context){
        if (INSTANCE ==null){
            synchronized (AppDatabase.class){
                if (INSTANCE ==null){
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class,"database-Voice command")
                            .fallbackToDestructiveMigration()
                            .addCallback(new Callback() {
                                @Override
                                public void onCreate(@NonNull SupportSQLiteDatabase db) {
                                    super.onCreate(db);

                                    Executors.newSingleThreadExecutor().execute(() -> {
                                        InstructionDao dao = INSTANCE.instructionDao();
                                        dao.insert(new KeywordAction("前進", VehicleMode.CAR.name(),"10 00 30 77"));
                                        dao.insert(new KeywordAction("右轉", VehicleMode.CAR.name(),"20 77 40 02"));
                                        dao.insert(new KeywordAction("左轉", VehicleMode.CAR.name(),"10 77 30 05"));
                                        dao.insert(new KeywordAction("後退",VehicleMode.CAR.name(),"20 00 40 77"));
                                        dao.insert(new KeywordAction("停止",VehicleMode.CAR.name(),"50 00 00 00"));
                                        dao.insert(new KeywordAction("解鎖",VehicleMode.CAR.name(),"80 FF FF FF"));
                                        dao.insert(new KeywordAction("上鎖",VehicleMode.CAR.name(),"81 FF FF FF"));
                                        dao.insert(new KeywordAction("加速",VehicleMode.CAR.name(),"30 00 40 77"));
                                        dao.insert(new KeywordAction("減速",VehicleMode.CAR.name(),"40 00 40 77"));
                                        dao.insert(new KeywordAction("向前", VehicleMode.UAV.name(),"20 00 30 77")); //無人機開始 50次錯2次
                                        dao.insert(new KeywordAction("向後", VehicleMode.UAV.name(),"20 10 30 77")); //50次錯1
                                        dao.insert(new KeywordAction("向左", VehicleMode.UAV.name(),"20 20 30 77")); //有問題
                                        dao.insert(new KeywordAction("向右", VehicleMode.UAV.name(),"20 30 30 77")); //沒問題
                                        dao.insert(new KeywordAction("向下", VehicleMode.UAV.name(),"20 40 30 77")); //50次錯2
                                        dao.insert(new KeywordAction("向上", VehicleMode.UAV.name(),"20 50 30 77")); //50次錯1
                                        dao.insert(new KeywordAction("起飛", VehicleMode.UAV.name(),"20 60 30 77")); //50次全對
                                        dao.insert(new KeywordAction("降落", VehicleMode.UAV.name(),"20 70 30 77")); //沒問題
                                        dao.insert(new KeywordAction("懸停", VehicleMode.UAV.name(),"20 80 30 77")); //沒問題
                                        //dao.insert(new KeywordAction("自旋", VehicleMode.UAV.name(),"20 90 30 77"));
                                        dao.insert(new KeywordAction("向左旋轉", VehicleMode.UAV.name(),"20 90 30 77")); //不用更改 50次錯1個
                                        dao.insert(new KeywordAction("向右旋轉", VehicleMode.UAV.name(),"20 98 30 77"));//不用更改 50次全部都正確






                                    });
                                }
                            })
                            .build();
                }
            }
        }return INSTANCE;
    }
}
