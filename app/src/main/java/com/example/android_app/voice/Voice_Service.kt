package com.example.android_app.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import com.example.android_app.BuildConfig

object VoiceService {

    private val client by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .callTimeout(java.time.Duration.ofSeconds(30))
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(30))
            .build()
    }

    /**
     * Descarga audio de ElevenLabs y lo reproduce.
     * onStart: UI -> "Hablando..."
     * onDone : UI -> "Listo para escucharte"
     * onError: UI -> mensaje de error
     */

    suspend fun speak(
        context: Context,
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.ELEVEN_API_KEY.orEmpty()
        val voiceId = BuildConfig.ELEVEN_VOICE.orEmpty()
        val TAG = "VoiceService"

        if (apiKey.isBlank() || voiceId.isBlank()) {
            onError?.invoke("Faltan claves de ElevenLabs (ELEVEN_API_KEY/ELEVEN_VOICE).")
            return@withContext
        }

        try {
            val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId?optimize_streaming_latency=2"

            val json = """
                {
                  "text": ${text.jsonEscaped()},
                  "model_id": "eleven_multilingual_v2",
                  "voice_settings": { "stability": 0.4, "similarity_boost": 0.8, "style": 0.6 }
                }
            """.trimIndent()

            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "audio/mpeg")
                .addHeader("xi-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(req).execute()
            Log.d(TAG, "ElevenLabs response code=${resp.code}, message=${resp.message}")
            if (!resp.isSuccessful) {
                onError?.invoke("ElevenLabs ${resp.code}: ${resp.message}")
                return@withContext
            }

            val bytes = resp.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                onError?.invoke("Audio vacío desde ElevenLabs.")
                Log.e(TAG, "Empty audio buffer")
                return@withContext
            }
            Log.d(TAG, "Received audio bytes: ${bytes.size}")
            if (bytes.size < 500) {
                onError?.invoke("El audio recibido es demasiado pequeño (${bytes.size} bytes). Revisa la API key/voice o el volumen del emulador.")
                Log.e(TAG, "Tiny MP3 (${bytes.size} bytes)")
                return@withContext
            }

            // Guardar temporalmente y reproducir
            val mp3 = File.createTempFile("nicky_tts_", ".mp3", context.cacheDir)
            mp3.outputStream().use { it.write(bytes) }

            withContext(Dispatchers.Main) {
                onStart?.invoke()
                val player = MediaPlayer()
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                player.setVolume(1.0f, 1.0f)
                player.setDataSource(mp3.absolutePath)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener {
                    it.release()
                    onDone?.invoke()
                    // Limpieza
                    mp3.delete()
                }
                player.setOnErrorListener { mp, _, what ->
                    mp.release()
                    onError?.invoke("Error de reproducción ($what).")
                    mp3.delete()
                    true
                }
                player.setOnInfoListener { _, what, extra ->
                    Log.d(TAG, "MediaPlayer info: what=$what extra=$extra")
                    false
                }
                player.prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo TTS", e)
            onError?.invoke("Fallo TTS: ${e.message}")
        }
    }
}

/** Escapa comillas para JSON simple */
private fun String.jsonEscaped(): String =
    "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""