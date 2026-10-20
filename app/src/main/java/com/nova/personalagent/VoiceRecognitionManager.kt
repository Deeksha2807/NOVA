package com.nova.personalagent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Handles Android speech recognition for NOVA.
 */
class VoiceRecognitionManager(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    private val appContext = context.applicationContext

    private var speechRecognizer: SpeechRecognizer? = null

    private var isListening = false

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                speechRecognizer = null
                return
            }

            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(appContext)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                    }

                    override fun onBeginningOfSpeech() {
                        isListening = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Audio level callback.
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {
                        // Raw audio is not required.
                    }

                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(errorCode: Int) {
                        isListening = false

                        val message = when (errorCode) {
                            SpeechRecognizer.ERROR_AUDIO ->
                                "Microphone audio error. Check microphone permission."

                            SpeechRecognizer.ERROR_CLIENT ->
                                "Speech recognizer was interrupted. Please try again."

                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Microphone permission is required."

                            SpeechRecognizer.ERROR_NETWORK ->
                                "Speech recognition network error. Check internet."

                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Speech recognition timed out. Check your internet."

                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "No speech detected. Speak clearly after tapping the mic."

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Speech recognizer is busy. Wait a moment and try again."

                            SpeechRecognizer.ERROR_SERVER ->
                                "Speech recognition server error. Please try again."

                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "No speech heard. Tap the mic and start speaking."

                            else ->
                                "Speech recognition failed. Error code: $errorCode"
                        }

                        onError(message)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false

                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val recognizedText = matches
                            ?.firstOrNull()
                            ?.trim()

                        if (!recognizedText.isNullOrBlank()) {
                            onResult(recognizedText)
                        } else {
                            onError(
                                "No speech detected. Please speak and try again."
                            )
                        }
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {
                        // Final result is handled by onResults().
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {
                        // No additional events required.
                    }
                }
            )
        } catch (exception: Exception) {
            speechRecognizer = null
        }
    }

    fun startListening() {
        if (speechRecognizer == null) {
            initializeRecognizer()
        }

        val recognizer = speechRecognizer

        if (recognizer == null) {
            onError(
                "Speech recognition is unavailable. Check Google speech services on the emulator."
            )
            return
        }

        try {
            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                val languageTag = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    languageTag
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    languageTag
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    1
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    false
                )

                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    "Speak now..."
                )

                putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    false
                )
            }

            isListening = true
            recognizer.startListening(intent)

        } catch (exception: Exception) {
            isListening = false

            onError(
                exception.localizedMessage
                    ?: "Unable to start speech recognition. Please try again."
            )
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (exception: Exception) {
            isListening = false
        }
    }

    fun destroy() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (exception: Exception) {
            // Ignore cleanup errors.
        } finally {
            speechRecognizer = null
            isListening = false
        }
    }
}