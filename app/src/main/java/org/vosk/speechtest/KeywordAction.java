package org.vosk.speechtest;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

@Entity(
        tableName = "keyword_actions",
        primaryKeys = {"keyword","vehicle_mode"}
)

public class KeywordAction {

    @NonNull  public String keyword;

    @NonNull
    @ColumnInfo(name = "vehicle_mode")
    public String vehicleMode;


    @NonNull
    public String packet;

    public KeywordAction(@NonNull String keyword,
                         @NonNull String vehicleMode,
                         @NonNull String packet){
        this.keyword = keyword;
        this.vehicleMode =vehicleMode;
        this.packet = packet;

    }

}
