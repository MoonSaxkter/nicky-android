package com.example.android_app.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.Build
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
            .build()
    }

    /** Solicita foco de audio para TTS. Devuelve triple (concedido, AudioManager, AudioFocusRequest?) */
    private fun requestAudioFocus(context: Context, onFocusChange: (Int) -> Unit): Triple<Boolean, AudioManager, AudioFocusRequest?> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val afr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
                .setWillPauseWhenDucked(false)
                .build()
            val res = am.requestAudioFocus(afr)
            Triple(res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, am, afr)
        } else {
            @Suppress("DEPRECATION")
            val res = am.requestAudioFocus(
                { change -> onFocusChange(change) },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            Triple(res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, am, null)
        }
    }

    /** Libera el foco de audio si fue solicitado. */
    private fun abandonAudioFocus(am: AudioManager, afr: AudioFocusRequest?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (afr != null) am.abandonAudioFocusRequest(afr)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
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

        // Diagnóstico: confirmar que las claves están llegando desde BuildConfig
        Log.d(TAG, "ELEVEN_API_KEY length=${apiKey.length}, prefix=${apiKey.take(3)}…")
        Log.d(TAG, "ELEVEN_VOICE=$voiceId")

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
                if (resp.code == 401) {
                    onError?.invoke("ElevenLabs 401: clave inválida o ausente. Verifica ELEVEN_API_KEY/ELEVEN_VOICE.")
                } else if (resp.code == 403) {
                    onError?.invoke("ElevenLabs 403: acceso denegado. Revisa permisos de la clave.")
                } else {
                    onError?.invoke("ElevenLabs ${resp.code}: ${resp.message}")
                }
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

                var currentVolume = 1.0f
                var player: MediaPlayer? = null
                var focusAm: AudioManager? = null
                var focusAfr: AudioFocusRequest? = null

                // 1) Pide foco de audio (con ducking)
                val (granted, tmpAm, tmpAfr) = requestAudioFocus(context) { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            // Recupera volumen normal
                            player?.setVolume(1.0f, 1.0f)
                            currentVolume = 1.0f
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            // Baja volumen mientras otra app suena
                            player?.setVolume(0.2f, 0.2f)
                            currentVolume = 0.2f
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // Pausa breve si otra app toma foco temporal
                            if (player?.isPlaying == true) player?.pause()
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            // Pérdida total de foco: detén y libera
                            player?.let {
                                if (it.isPlaying) it.stop()
                                it.release()
                            }
                            // Abandona foco usando variables externas
                            focusAm?.let { amLocal -> abandonAudioFocus(amLocal, focusAfr) }
                        }
                    }
                }
                // Guarda las referencias reales del foco
                focusAm = tmpAm
                focusAfr = tmpAfr

                if (!granted) {
                    onError?.invoke("No se pudo obtener el foco de audio.")
                    // Limpia archivo temporal si no vamos a reproducir
                    mp3.delete()
                    return@withContext
                }

                // 2) Prepara y reproduce
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .build()
                    )
                    setVolume(currentVolume, currentVolume)
                    setDataSource(mp3.absolutePath)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener {
                        it.release()
                        onDone?.invoke()
                        focusAm?.let { amLocal -> abandonAudioFocus(amLocal, focusAfr) }
                        // Limpieza
                        mp3.delete()
                    }
                    setOnErrorListener { mp, _, what ->
                        mp.release()
                        onError?.invoke("Error de reproducción ($what).")
                        focusAm?.let { amLocal -> abandonAudioFocus(amLocal, focusAfr) }
                        mp3.delete()
                        true
                    }
                    setOnInfoListener { _, what, extra ->
                        Log.d(TAG, "MediaPlayer info: what=$what extra=$extra")
                        false
                    }
                    prepareAsync()
                }
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