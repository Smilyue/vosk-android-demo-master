package org.vosk.demo;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface InstructionDao {
    @Insert
    void insert(KeywordAction keywordAction);

    @Query("SELECT int_value FROM keyword_actions WHERE keyword = :keyword")
    Integer findIntValueByKeyword(String keyword);;
    @Query("SELECT keyword FROM keyword_actions")
    List<String> getAllKeywords();
}
