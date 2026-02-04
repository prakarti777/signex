package com.example.signex.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioTextDao {
    @Query("SELECT * FROM audio_text_table ORDER BY timestamp DESC")
    fun getAllAudioTexts(): Flow<List<AudioText>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(audioText: AudioText)
}
