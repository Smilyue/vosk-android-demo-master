package org.vosk.demo;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "keyword_actions")
public class KeywordAction {
    @PrimaryKey(autoGenerate = true)
    public int id;
    @ColumnInfo(name = "keyword")
    public String keyword;

    @ColumnInfo(name = "int_value")
    public int intValue;

    public KeywordAction(String keyword, int intValue){
        this.keyword = keyword;
        this.intValue =intValue;

    }

}
