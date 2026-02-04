package com.example.signex.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.signex.data.AppDatabase
import com.example.signex.data.AppRepository
import com.example.signex.data.AudioText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: AppRepository
    // Expose all saved texts
    val savedTexts = MutableStateFlow<List<AudioText>>(emptyList())
    
    // Tracking for camera rotation used by overlay
    var lastRotation: Int = 0

    // Smoothed Gesture State
    private val _smoothedGesture = MutableStateFlow("SEARCHING...")
    val smoothedGesture: StateFlow<String> = _smoothedGesture

    companion object {
        private const val HISTORY_SIZE = 5
    }

    private val gestureHistory = mutableListOf<String>()

    // Speech UI State
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _spokenText = MutableStateFlow("")
    val spokenText: StateFlow<String> = _spokenText

    private val _speechError = MutableStateFlow<String?>(null)
    val speechError: StateFlow<String?> = _speechError

    private var speechRecognizer: SpeechRecognizer? = null
    private val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    fun onGestureDetected(gesture: String) {
        if (gesture.isEmpty() || gesture == "WAITING..." || gesture.contains("Buffering")) {
            return
        }
        
        synchronized(gestureHistory) {
            gestureHistory.add(gesture)
            if (gestureHistory.size > HISTORY_SIZE) {
                gestureHistory.removeAt(0)
            }
            
            // Majority voting
            val counts = gestureHistory.groupingBy { it }.eachCount()
            val best = counts.maxByOrNull { it.value }
            
            if (best != null && best.value >= 3) { // Require at least 3 out of 5 frames
                _smoothedGesture.value = best.key
            }
        }
    }

    init {
        val audioTextDao = AppDatabase.getDatabase(application).audioTextDao()
        repository = AppRepository(audioTextDao)

        viewModelScope.launch {
            repository.allAudioTexts.collectLatest {
                savedTexts.value = it
            }
        }
    }

    fun startListening(context: Context) {
        if (_isListening.value) return

        viewModelScope.launch { // Ensure UI thread usage for SpeechRecognizer creation if needed (though it needs main thread usually, checking context)
             try {
                 if (SpeechRecognizer.isRecognitionAvailable(context)) {
                     // Main thread check often required for SpeechRecognizer
                     // We will initialize it on the call or in Activity but VM is OK if careful.
                     // Best practice: SpeechRecognizer should be created on Main Looper.
                     // We assume this is called from UI (Main thread).
                     val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                     recognizer.setRecognitionListener(object : RecognitionListener {
                         override fun onReadyForSpeech(params: Bundle?) {}
                         override fun onBeginningOfSpeech() {}
                         override fun onRmsChanged(rmsdB: Float) {}
                         override fun onBufferReceived(buffer: ByteArray?) {}
                         override fun onEndOfSpeech() {
                             _isListening.value = false
                         }
                         override fun onError(error: Int) {
                             _isListening.value = false
                             _speechError.value = "Error code: $error"
                         }
                         override fun onResults(results: Bundle?) {
                             val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                             if (!matches.isNullOrEmpty()) {
                                 val text = matches[0]
                                 _spokenText.value = text
                                 saveText(text)
                             }
                         }
                         override fun onPartialResults(partialResults: Bundle?) {
                             val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                             if (!matches.isNullOrEmpty()) {
                                 _spokenText.value = matches[0]
                             }
                         }
                         override fun onEvent(eventType: Int, params: Bundle?) {}
                     })
                     speechRecognizer = recognizer
                     recognizer.startListening(speechIntent)
                     _isListening.value = true
                     _speechError.value = null
                 } else {
                     _speechError.value = "Speech recognition not available on this device"
                 }
             } catch (e: Exception) {
                 _speechError.value = "Failed to start speech: ${e.message}"
                 e.printStackTrace()
             }
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isListening.value = false
    }

    private fun saveText(text: String) {
        viewModelScope.launch {
            if (text.isNotBlank()) {
                repository.insert(AudioText(text = text))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
    }
}
