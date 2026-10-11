package com.nova.personalagent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Wraps Android's [SpeechRecognizer] API so that [HomeScreen] stays clean
 * and focused on UI.
 *
 * How to use:
 *  1. Create an instance (once, via `remember` in Compose).
 *  2. Call [startListening] when the mic button is tapped.
 *  3. Receive results through [onResult] or errors through [onError].
 *  4. Call [destroy] when the screen is no longer visible (via DisposableEffect).
 *
 * ⚠️  [SpeechRecognizer] MUST be created and used on the main (UI) thread.
 *     Compose always runs on the main thread, so this is fine.
 *
 * @param context   Android context – used to create the SpeechRecognizer.
 * @param onResult  Called with the recognised text string when speech is detected.
 * @param onError   Called with a human-readable error message when something goes wrong.
 */
class VoiceRecognitionManager(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    // The Android speech recognizer instance.
    // It is null if speech recognition is not available on this device.
    private var speechRecognizer: SpeechRecognizer? = null

    init {
        // Check whether the device has a speech recognition service installed.
        // On the Pixel emulator with Google services this is always true.
        if (SpeechRecognizer.isRecognitionAvailable(context)) {

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

            // RecognitionListener receives all events from the SpeechRecognizer.
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {

                // Called when the recognizer is ready and the user can start speaking.
                override fun onReadyForSpeech(params: Bundle?) { /* handled by HomeScreen state */ }

                // Called when the user has started speaking.
                override fun onBeginningOfSpeech() { /* no action needed */ }

                // Called repeatedly with the microphone volume level – useful for a
                // visual VU-meter in a future task, but ignored for now.
                override fun onRmsChanged(rmsdB: Float) { /* ignored */ }

                // Raw audio buffer – not needed for text recognition.
                override fun onBufferReceived(buffer: ByteArray?) { /* ignored */ }

                // Called when the user stops speaking.  The recognizer is still
                // processing; we wait for onResults or onError.
                override fun onEndOfSpeech() { /* ignored */ }

                /**
                 * Called when recognition fails for any reason.
                 * We map the numeric error code to a friendly message.
                 */
                override fun onError(errorCode: Int) {
                    val message = when (errorCode) {
                        SpeechRecognizer.ERROR_NO_MATCH         -> "No speech detected. Please try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT   -> "Listening timed out. Please try again."
                        SpeechRecognizer.ERROR_AUDIO            -> "Microphone error. Check audio permissions."
                        SpeechRecognizer.ERROR_NETWORK          -> "Network error. Check your connection."
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT  -> "Network timed out."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is required."
                        // ERROR_CLIENT (code 5) can happen if startListening() is called
                        // while the recognizer is still processing a previous request.
                        SpeechRecognizer.ERROR_CLIENT           -> "Please wait and try again."
                        SpeechRecognizer.ERROR_SERVER           -> "Server error. Try again later."
                        else -> "Recognition failed (code $errorCode). Please try again."
                    }
                    onError(message)
                }

                /**
                 * Called when recognition succeeds.
                 * [SpeechRecognizer.RESULTS_RECOGNITION] is an ordered list of possible
                 * transcriptions; we take the best one (index 0).
                 */
                override fun onResults(results: Bundle?) {
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

                    val bestMatch = matches?.firstOrNull()?.trim()

                    if (!bestMatch.isNullOrBlank()) {
                        onResult(bestMatch)
                    } else {
                        // Results bundle was empty – treat it like no speech detected.
                        onError("No speech detected. Please try again.")
                    }
                }

                // Partial results arrive while the user is still speaking.
                // We only care about the final result for now.
                override fun onPartialResults(partialResults: Bundle?) { /* ignored */ }

                // Reserved for future events from the recognizer service.
                override fun onEvent(eventType: Int, params: Bundle?) { /* ignored */ }
            })

        } else {
            // Speech recognition service is not installed/available on this device.
            // onError will be called from startListening() when the user taps the button.
        }
    }

    /**
     * Starts a single listening session.
     *
     * The recognizer listens until silence is detected or the built-in timeout
     * expires.  Results arrive via [onResult]; errors via [onError].
     *
     * Safe to call multiple times – each call starts a fresh session.
     */
    fun startListening() {
        if (speechRecognizer == null) {
            // Device does not have a speech recognition service.
            onError("Speech recognition is not available on this device.")
            return
        }

        // Build the Intent that tells the recognizer what kind of speech to expect.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // FREE_FORM allows natural, conversational input (vs. WEB_SEARCH style).
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Use the device's current locale (e.g. en-US, en-IN, etc.).
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            // We only need the single best transcription.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        // Start listening.  Results will arrive in the RecognitionListener above.
        speechRecognizer?.startListening(intent)
    }

    /**
     * Asks the recognizer to stop listening and process whatever audio it has.
     * This is called when the user taps the button again while listening.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    /**
     * Releases all resources held by the [SpeechRecognizer].
     *
     * MUST be called when the screen is destroyed (or leaves composition) to
     * prevent resource leaks and microphone being held in the background.
     *
     * In HomeScreen.kt this is called inside a `DisposableEffect { onDispose { } }`.
     */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
