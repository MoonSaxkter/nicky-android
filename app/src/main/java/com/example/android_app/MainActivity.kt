package com.example.android_app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.example.android_app.voice.VoiceService
import com.example.android_app.util.Net
import android.speech.tts.TextToSpeech
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var textView: TextView
    private lateinit var buttonTalk: Button

    // Estado de conectividad actual
    private var isOnline: Boolean = false

    // TTS local para modo offline
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        buttonTalk = findViewById(R.id.buttonTalk)

        // 1) Observar conectividad y reflejar en UI
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Net.observe(this@MainActivity).collectLatest { online ->
                    isOnline = online
                    textView.text = if (online) {
                        "Conectado (modo nube listo)"
                    } else {
                        "Sin Internet (modo local)"
                    }
                }
            }
        }

        // 2) Inicializar TTS local (para fallback offline)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Español Costa Rica (ajústalo si querés)
                tts?.language = Locale("es", "CR")
            }
        }

        // 3) Botón: hablar con nube si hay Internet; con TTS local si no
        buttonTalk.setOnClickListener {
            val texto = "Hola, soy Nicky. ¿Listo para jugar?"
            if (isOnline) {
                // ElevenLabs
                lifecycleScope.launch {
                    VoiceService.speak(
                        context = this@MainActivity,
                        text = texto,
                        onStart = { textView.text = "Nicky está hablando (nube)..." },
                        onDone  = { textView.text = if (isOnline) "Conectado (modo nube listo)" else "Sin Internet (modo local)" },
                        onError = { msg -> textView.text = "Error: $msg" }
                    )
                }
            } else {
                // TTS local
                textView.text = "Nicky está hablando (local)..."
                tts?.speak(
                    "Hola, soy Nicky en modo local por desconexión de internet.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "nicky_offline"
                )
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}