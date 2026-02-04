package com.example.signex.data

import kotlinx.coroutines.flow.Flow

class AppRepository(private val audioTextDao: AudioTextDao) {
    val allAudioTexts: Flow<List<AudioText>> = audioTextDao.getAllAudioTexts()

    suspend fun insert(audioText: AudioText) {
        audioTextDao.insert(audioText)
    }
}
