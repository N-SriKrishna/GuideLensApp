package com.example.guidelensapp.accessibility

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class TextToSpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "TextToSpeechManager"
        private const val UTTERANCE_ID = "GUIDELENS_TTS"
    }

    private var tts: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)

    // State flow for UI feedback
    private val _speakingState = MutableStateFlow(false)
    val speakingState = _speakingState.asStateFlow()

    // Settings
    var speechRate: Float = 0.9f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setSpeechRate(field)
        }

    var pitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    init {
        initializeTTS()
    }

    private fun initializeTTS() {
        try {
            tts = TextToSpeech(context) { status ->
                when (status) {
                    TextToSpeech.SUCCESS -> {
                        val result = tts?.setLanguage(Locale.getDefault())

                        when (result) {
                            TextToSpeech.LANG_MISSING_DATA,
                            TextToSpeech.LANG_NOT_SUPPORTED -> {
                                Log.e(TAG, "Language not supported: ${Locale.getDefault()}")
                                // Fallback to English
                                tts?.setLanguage(Locale.US)
                            }
                            else -> {
                                Log.d(TAG, "TTS initialized successfully")
                                isInitialized.set(true)
                            }
                        }

                        // Set default parameters
                        tts?.setSpeechRate(speechRate)
                        tts?.setPitch(pitch)

                        // Set up utterance listener
                        setupUtteranceListener()

                    }
                    else -> {
                        Log.e(TAG, "TTS initialization failed")
                        isInitialized.set(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TTS", e)
            isInitialized.set(false)
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking.set(true)
                _speakingState.value = true
                Log.d(TAG, "TTS started speaking")
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking.set(false)
                _speakingState.value = false
                Log.d(TAG, "TTS finished speaking")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking.set(false)
                _speakingState.value = false
                Log.e(TAG, "TTS error occurred")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                isSpeaking.set(false)
                _speakingState.value = false
                Log.e(TAG, "TTS error: $errorCode")
            }
        })
    }

    /**
     * Speak text and add to queue
     */
    fun speak(text: String) {
        if (!isInitialized.get()) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            return
        }

        if (text.isBlank()) {
            Log.w(TAG, "Empty text, skipping")
            return
        }

        try {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
            Log.d(TAG, "Speaking: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking text", e)
        }
    }

    /**
     * Speak text immediately, interrupting current speech
     */
    fun speakImmediate(text: String) {
        if (!isInitialized.get()) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            return
        }

        if (text.isBlank()) {
            Log.w(TAG, "Empty text, skipping")
            return
        }

        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            Log.d(TAG, "Speaking immediately: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking text immediately", e)
        }
    }

    /**
     * Stop current speech
     */
    fun stop() {
        try {
            tts?.stop()
            isSpeaking.set(false)
            _speakingState.value = false
            Log.d(TAG, "TTS stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    /**
     * Check if currently speaking
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            isInitialized.set(false)
            Log.d(TAG, "TTS shutdown completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }
}
