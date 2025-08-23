package com.example.android_app

import android.Manifest
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.example.android_app.voice.VoiceService
import com.example.android_app.util.Net
import com.example.android_app.stt.Speech
import android.speech.tts.TextToSpeech
import android.speech.SpeechRecognizer
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var textView: TextView
    private lateinit var buttonTalk: Button
    private lateinit var buttonListen: Button

    private var isOnline: Boolean = false
    private var tts: TextToSpeech? = null

    // Flag de escucha
    private var isListening = false

    // Permiso micrófono
    private val askAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else textView.text = "Permiso de micrófono denegado"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        buttonTalk = findViewById(R.id.buttonTalk)
        buttonListen = findViewById(R.id.buttonListen)

        // Observa conectividad
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Net.observe(this@MainActivity).collectLatest { online ->
                    isOnline = online
                    if (!isListening) {
                        textView.text = if (online) "Conectado (modo nube listo)" else "Sin Internet (modo local)"
                    }
                }
            }
        }

        // TTS local
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "CR")
            }
        }

        // Botón Hablar (nube/local)
        buttonTalk.setOnClickListener {
            // Si estaba escuchando, cancelamos escucha para que no se pisen
            stopListeningIfNeeded()

            val texto = "Hola, soy Nicky. ¿Listo para jugar?"
            if (isOnline) {
                lifecycleScope.launch {
                    VoiceService.speak(
                        context = this@MainActivity,
                        text = texto,
                        onStart = { textView.text = "Nicky está hablando (nube)..." },
                        onDone  = {
                            if (!isListening) {
                                textView.text = if (isOnline) "Conectado (modo nube listo)" else "Sin Internet (modo local)"
                            }
                        },
                        onError = { msg -> textView.text = "Error: $msg" }
                    )
                }
            } else {
                textView.text = "Nicky está hablando (local)..."
                tts?.speak(
                    "Hola, soy Nicky en modo local por desconexión de internet.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "nicky_offline"
                )
            }
        }

        // Botón Escuchar (STT)
        buttonListen.setOnClickListener {
            if (isListening) {
                stopListeningIfNeeded()
                textView.text = if (isOnline) "Conectado (modo nube listo)" else "Sin Internet (modo local)"
            } else {
                // Paramos cualquier TTS en curso antes de escuchar
                tts?.stop()
                askAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startListening() {
        // Crea un recognizer por sesión (para un ciclo limpio)
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        isListening = true
        textView.text = "🎙️ Escuchando… hablá con normalidad"

        lifecycleScope.launch {
            Speech.listenFlow(recognizer, language = "es-Es").collectLatest { ev ->
                ev.listening?.let { listening ->
                    isListening = listening
                    if (!listening && textView.text.contains("Escuchando")) {
                        textView.text = "Procesando…"
                    }
                }
                ev.partial?.let { partial ->
                    textView.text = "🗣️ $partial"
                }
                ev.finalText?.let { final ->
                    textView.text = "✅ $final"

                    // (Opcional) Pequeña demo: si dice “buscar …”, respondemos
                    val low = final.lowercase()
                    if (low.startsWith("buscar ") || low.startsWith("investiga ")) {
                        val consulta = low.removePrefix("buscar ").removePrefix("investiga ").trim()
                        val respuesta = if (consulta.isBlank()) {
                            "¿Qué querés que busque?"
                        } else {
                            "Buscaré: $consulta. (demo)"
                        }
                        // Habla respuesta (respeta modo online/offline)
                        if (isOnline) {
                            VoiceService.speak(
                                this@MainActivity,
                                respuesta,
                                onStart = { textView.text = "Nicky está hablando (nube)..." },
                                onDone  = { textView.text = "Listo. Tocá Escuchar para otra consulta." },
                                onError = { msg -> textView.text = "Error: $msg" }
                            )
                        } else {
                            textView.text = "Nicky está hablando (local)…"
                            tts?.speak(respuesta, TextToSpeech.QUEUE_FLUSH, null, "nicky_reply")
                        }
                    } else {
                        // Respuesta neutra
                        val respuesta = "Te escuché: $final"
                        if (isOnline) {
                            VoiceService.speak(
                                this@MainActivity,
                                respuesta,
                                onStart = { textView.text = "Nicky está hablando (nube)..." },
                                onDone  = { textView.text = "Listo. Tocá Escuchar para otra consulta." },
                                onError = { msg -> textView.text = "Error: $msg" }
                            )
                        } else {
                            textView.text = "Nicky está hablando (local)…"
                            tts?.speak(respuesta, TextToSpeech.QUEUE_FLUSH, null, "nicky_reply")
                        }
                    }
                }
                ev.error?.let { err ->
                    textView.text = "⚠️ $err"
                }
            }
        }
    }

    private fun stopListeningIfNeeded() {
        // Truco: crear y destruir un recognizer vacío no detiene el actual.
        // Como usamos un recognizer por sesión dentro del Flow, al cerrar la
        // colección (p.ej. al salir de la Activity) se cierra solo. Aquí solo
        // marcamos estado.
        isListening = false
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}