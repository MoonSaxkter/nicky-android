package com.example.android_app.stt

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class SttEvent(
    val partial: String? = null,
    val finalText: String? = null,
    val error: String? = null,
    val listening: Boolean? = null
)

object Speech {

    fun isAvailable(context: android.content.Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun listenFlow(
        recognizer: SpeechRecognizer,
        language: String = "es-US",           // default más compatible en emuladores
        preferOffline: Boolean = false          // por defecto online para evitar "Idioma no disponible"
    ): Flow<SttEvent> = callbackFlow {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // intenta on-device si el modelo está disponible
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SttEvent(listening = true))
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                list?.firstOrNull()?.let { trySend(SttEvent(partial = it)) }
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                list?.firstOrNull()?.let { trySend(SttEvent(finalText = it)) }
                trySend(SttEvent(listening = false))
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
                    SpeechRecognizer.ERROR_CLIENT -> "Error de cliente"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos insuficientes"
                    SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de red agotado"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No te entendí"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Motor ocupado"
                    SpeechRecognizer.ERROR_SERVER -> "Error del servidor"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No detecté voz"
                    10 -> "Demasiadas solicitudes"
                    11 -> "Servidor desconectado"
                    12 -> "Idioma no soportado"
                    13 -> "Idioma no disponible"
                    14 -> "No se pudo verificar soporte de idioma"
                    else -> "Error desconocido ($error)"
                }
                trySend(SttEvent(error = msg, listening = false))
            }

            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent)

        awaitClose {
            try {
                recognizer.cancel()
                recognizer.destroy()
            } catch (_: Exception) {}
        }
    }
}