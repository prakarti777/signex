package com.example.signex.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_text_table")
data class AudioText(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
