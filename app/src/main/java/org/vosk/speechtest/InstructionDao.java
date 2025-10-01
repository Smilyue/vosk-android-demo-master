package org.vosk.speechtest;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface InstructionDao {
    @Insert
    void insert(KeywordAction keywordAction);

    @Query("SELECT packet FROM keyword_actions "+" WHERE keyword = :kw AND vehicle_mode = :mode LIMIT 1")
    String findPacketByKeyword(String kw,String mode);
    @Query("SELECT keyword FROM keyword_actions WHERE vehicle_mode = :mode")
    List<String> getAllKeywords(String mode);
}
