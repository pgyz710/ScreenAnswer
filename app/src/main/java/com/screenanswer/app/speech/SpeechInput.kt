package com.screenanswer.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.Closeable

/**
 * Thin wrapper around Android's on-device/on-system SpeechRecognizer. No API key and no cloud
 * client is required; the phone's recognition service does the work (accuracy depends on it).
 */
class SpeechInput(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var finished = false

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onFailure: ((Int) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(languageTag: String) {
        stop()
        finished = false
        val client = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = client
        client.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                onFailure?.invoke(error)
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                onFinal?.invoke(firstResult(results))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = firstResult(partialResults)
                if (text.isNotBlank()) onPartial?.invoke(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        runCatching { client.startListening(intent) }.onFailure {
            finished = true
            onFailure?.invoke(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

    fun stop() {
        finished = true
        recognizer?.let { client ->
            runCatching { client.cancel() }
            runCatching { client.destroy() }
        }
        recognizer = null
    }

    override fun close() = stop()
}
