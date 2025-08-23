package com.example.android_app

import android.Manifest
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
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

private const val LISTEN_WINDOW_MS = 6000L
private const val FOLLOWUP_MS = 10000L
private val KEYWORDS = listOf("nicky", "niki", "niky", "nikki", "nicol", "asistente", "ayuda")

private var listenJob: kotlinx.coroutines.Job? = null
private var windowJob: kotlinx.coroutines.Job? = null
private var heardSomething = false
private var lastPartial: String? = null
private var finalizeJob: kotlinx.coroutines.Job? = null
private var followupUntil: Long = 0L
private var greetedUntil: Long = 0L
private var partialDebounceJob: kotlinx.coroutines.Job? = null
private var handlingUtterance: Boolean = false

private var pendingPermissionAction: (() -> Unit)? = null

class MainActivity : AppCompatActivity() {


    private lateinit var textView: TextView
    private lateinit var buttonTalk: Button
    private lateinit var buttonListen: Button

    private var isOnline: Boolean = false
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false

    private enum class State { IDLE, SPEAKING, LISTENING }
    private var state: State = State.IDLE

    // Permiso micrófono
    private val askAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingPermissionAction?.invoke()
        } else {
            textView.text = "Permiso de micrófono denegado"
        }
        pendingPermissionAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        buttonTalk = findViewById(R.id.buttonTalk)
        buttonListen = findViewById(R.id.buttonListen) // opcional en este modo

        // Observa conectividad
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Net.observe(this@MainActivity).collectLatest { online ->
                    isOnline = online
                    if (state != State.LISTENING && state != State.SPEAKING) {
                        textView.text = if (online) "Conectado (modo nube listo)" else "Sin Internet (modo local)"
                    }
                }
            }
        }

        // TTS local (para fallback)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "ES") // usa "es-ES" como dijiste
            }
        }

        // Botón Hablar: inicia el ciclo manos libres con palabra clave
        buttonTalk.setOnClickListener {
            stopListeningIfNeeded()
            val action = {
                speakAndThenListen("Hola, soy Nicky. ¿Listo para conversar?")
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                action()
            } else {
                pendingPermissionAction = action
                askAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // (Opcional) Botón Escuchar manual
        buttonListen.setOnClickListener {
            if (state == State.LISTENING) {
                stopListeningIfNeeded()
                textView.text = if (isOnline) "Conectado (modo nube listo)" else "Sin Internet (modo local)"
                state = State.IDLE
            } else {
                tts?.stop()
                val action = { startListeningWindow("es-ES") }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    action()
                } else {
                    pendingPermissionAction = action
                    askAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        // Set texto inicial amigable si está idle
        if (state == State.IDLE) {
            textView.text = "Tocá \"Hablar\" para empezar"
        }
    }

    private fun startListeningWindow(language: String = "es-ES") {
        // Si ya estaba escuchando, corta esa sesión
        stopListeningIfNeeded()

        // Crea un recognizer por sesión
        val recognizerLocal = SpeechRecognizer.createSpeechRecognizer(this)
        isListening = true
        heardSomething = false
        state = State.LISTENING
        textView.text = "🎙️ Te escucho…"
        handlingUtterance = false

        // Reiniciar estado de parciales y cualquier finalización pendiente
        lastPartial = null
        finalizeJob?.cancel()
        finalizeJob = null

        // Si hay un job previo, cancelalo
        listenJob?.cancel()

        listenJob = lifecycleScope.launch {
            try {
                // Timeout independiente (no cancela el colector)
                windowJob?.cancel()
                windowJob = launch {
                    kotlinx.coroutines.delay(LISTEN_WINDOW_MS)
                    if (!heardSomething) {
                        // No se oyó nada: cerrar sesión
                        try { recognizerLocal.cancel() } catch (_: Exception) {}
                        try { recognizerLocal.destroy() } catch (_: Exception) {}
                        isListening = false
                        state = State.IDLE
                        textView.text = if (isOnline) "Listo cuando quieras" else "Sin Internet (modo local)"
                    } else {
                        // Sí hubo parciales pero no llegó finalText ni onEndOfSpeech:
                        // forzar finalización usando el último parcial.
                        if (finalizeJob == null) {
                            finalizeJob = lifecycleScope.launch {
                                if (handlingUtterance) return@launch
                                val finalFromPartial = lastPartial?.trim()
                                heardSomething = finalFromPartial?.isNotEmpty() == true
                                isListening = false
                                state = State.IDLE
                                windowJob?.cancel()
                                windowJob = null

                                if (finalFromPartial.isNullOrEmpty()) {
                                    val aviso = "No te escuché bien. Decí ‘Nicky’ y tu pedido."
                                    if (isOnline) {
                                        VoiceService.speak(
                                            context = this@MainActivity,
                                            text = aviso,
                                            onStart = { textView.text = "Nicky está hablando (nube)..." },
                                            onDone  = { startListeningWindow(language) },
                                            onError = { msg -> textView.text = "Error: $msg" }
                                        )
                                    } else {
                                        textView.text = "Nicky está hablando (local)…"
                                        tts?.speak(aviso, TextToSpeech.QUEUE_FLUSH, null, "nicky_prompt_kw")
                                        launch {
                                            kotlinx.coroutines.delay(800)
                                            startListeningWindow(language)
                                        }
                                    }
                                } else {
                                    if (!handlingUtterance) {
                                        handlingUtterance = true
                                        handleFinalText(language, finalFromPartial)
                                    }
                                }
                                finalizeJob = null
                            }
                        }
                    }
                }

                // Colección del flujo
                com.example.android_app.stt.Speech.listenFlow(
                    recognizer = recognizerLocal,
                    language = language,
                    preferOffline = false
                ).collectLatest { ev ->
                    ev.listening?.let { listening ->
                        if (!listening) {
                            // Programa una finalización diferida si no llega "finalText".
                            // Si ya hay una en curso, no crear otra.
                            if (finalizeJob == null) {
                                finalizeJob = lifecycleScope.launch {
                                    if (handlingUtterance) return@launch
                                    kotlinx.coroutines.delay(900)
                                    if (handlingUtterance) return@launch
                                    val finalFromPartial = lastPartial?.trim()
                                    windowJob?.cancel()
                                    windowJob = null
                                    heardSomething = finalFromPartial?.isNotEmpty() == true
                                    isListening = false
                                    state = State.IDLE

                                    if (finalFromPartial.isNullOrEmpty()) {
                                        val aviso = "No te escuché bien. Decí ‘Nicky’ y tu pedido."
                                        if (isOnline) {
                                            VoiceService.speak(
                                                context = this@MainActivity,
                                                text = aviso,
                                                onStart = { textView.text = "Nicky está hablando (nube)..." },
                                                onDone  = { startListeningWindow(language) },
                                                onError = { msg -> textView.text = "Error: $msg" }
                                            )
                                        } else {
                                            textView.text = "Nicky está hablando (local)…"
                                            tts?.speak(aviso, TextToSpeech.QUEUE_FLUSH, null, "nicky_prompt_kw")
                                            launch {
                                                kotlinx.coroutines.delay(800)
                                                startListeningWindow(language)
                                            }
                                        }
                                    } else {
                                        handlingUtterance = true
                                        handleFinalText(language, finalFromPartial)
                                    }
                                    finalizeJob = null
                                }
                            }
                        } else {
                            // Volvió a escuchar: cancela cualquier finalización pendiente
                            finalizeJob?.cancel()
                            finalizeJob = null
                        }
                    }

                    ev.partial?.let { partial ->
                        heardSomething = true
                        lastPartial = partial
                        textView.text = "🗣️ $partial"
                        // Mientras recibimos parciales, no queremos cerrar por tiempo
                        finalizeJob?.cancel()
                        finalizeJob = null
                        partialDebounceJob?.cancel()
                        partialDebounceJob = lifecycleScope.launch {
                            if (handlingUtterance) return@launch
                            kotlinx.coroutines.delay(1200)
                            if (state == State.LISTENING && finalizeJob == null) {
                                val text = lastPartial?.trim()
                                if (!text.isNullOrEmpty() && !handlingUtterance) {
                                    handlingUtterance = true
                                    // Cerrar escucha y proceder con el parcial
                                    windowJob?.cancel()
                                    windowJob = null
                                    isListening = false
                                    state = State.IDLE
                                    handleFinalText(language, text)
                                }
                            }
                        }
                    }

                    ev.finalText?.let { final ->
                        if (handlingUtterance) return@let
                        handlingUtterance = true
                        finalizeJob?.cancel()
                        finalizeJob = null
                        partialDebounceJob?.cancel()
                        partialDebounceJob = null
                        lastPartial = null
                        heardSomething = true
                        isListening = false
                        state = State.IDLE
                        windowJob?.cancel()
                        windowJob = null

                        handleFinalText(language, final)
                    }

                    ev.error?.let { err ->
                        partialDebounceJob?.cancel()
                        partialDebounceJob = null
                        finalizeJob?.cancel()
                        finalizeJob = null
                        lastPartial = null
                        isListening = false
                        state = State.IDLE
                        textView.text = "⚠️ $err"
                        // Reintenta si estás online
                        if (isOnline) startListeningWindow(language)
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancelación normal
            } catch (e: Exception) {
                textView.text = "⚠️ Error: ${e.message ?: "desconocido"}"
            } finally {
                try { recognizerLocal.cancel() } catch (_: Exception) {}
                try { recognizerLocal.destroy() } catch (_: Exception) {}
            }
        }
    }

    /** Maneja texto final (con keyword o dentro de follow-up) y vuelve a escuchar */
    private fun handleFinalText(language: String, finalText: String) {
        // Ensure only one handling path executes (safety in case of concurrent callbacks)
        handlingUtterance = true
        windowJob?.cancel()
        finalizeJob?.cancel()
        partialDebounceJob?.cancel()
        val low = finalText.lowercase()
        val hasKeyword = KEYWORDS.any { k -> low.contains(k) }
        val allowFollowup = System.currentTimeMillis() <= followupUntil
        val proceed = hasKeyword || allowFollowup

        if (!proceed) {
            val aviso = "No entendí. Decí ‘Nicky’ y tu pedido."
            if (isOnline) {
                lifecycleScope.launch {
                    VoiceService.speak(
                        context = this@MainActivity,
                        text = aviso,
                        onStart = { textView.text = "Nicky está hablando (nube)..." },
                        onDone  = { startListeningWindow(language) },
                        onError = { msg -> textView.text = "Error: $msg" }
                    )
                }
            } else {
                textView.text = "Nicky está hablando (local)…"
                tts?.speak(aviso, TextToSpeech.QUEUE_FLUSH, null, "nicky_prompt_kw")
                textView.postDelayed({ startListeningWindow(language) }, 800)
            }
            return
        } else {
            // Abrimos ventana de follow-up para próximos turnos sin keyword
            followupUntil = System.currentTimeMillis() + FOLLOWUP_MS
        }

        // --- Saludos con más frases y anti-repetición ---
        val greetingTriggers = listOf(
            "hola",
            "buenos días",
            "buenas tardes",
            "buenas noches",
            "qué tal",
            "que tal",
            "como estás",
            "cómo estás",
            "hey",
            "buen día",
            "buen dia"
        )
        val esSaludo = greetingTriggers.any { low.contains(it) }

        var respuesta = ""
        if (esSaludo && hasKeyword) {
            val now = System.currentTimeMillis()
            val primeraVez = now > greetedUntil

            val opcionesPrimeraVezOnline = listOf(
                "¡Hola! ¿En qué te puedo ayudar?",
                "¡Buenas! Decime, ¿qué ocupás?",
                "Aquí estoy, lista para ayudarte.",
                "¡Qué tal! Contame, ¿qué hacemos?"
            )
            val opcionesFollowUpOnline = listOf(
                "De nuevo por aquí 😊 ¿qué más hacemos?",
                "Te escucho, ¿qué más necesitás?",
                "Decime nomás."
            )

            val opcionesPrimeraVezLocal = listOf(
                "¡Hola! Estoy en modo local, pero igual te escucho.",
                "¡Buenas! Sin conexión, pero aquí estoy.",
                "Hola, aunque sin internet sigo escuchándote."
            )
            val opcionesFollowUpLocal = listOf(
                "Te sigo escuchando.",
                "Dale, te escucho.",
                "Aquí estoy, decime."
            )

            respuesta = when {
                isOnline && primeraVez  -> opcionesPrimeraVezOnline.random()
                isOnline && !primeraVez -> opcionesFollowUpOnline.random()
                !isOnline && primeraVez -> opcionesPrimeraVezLocal.random()
                else                    -> opcionesFollowUpLocal.random()
            }

            // Evitar repetir saludo completo por ~90s
            greetedUntil = now + 90_000
        } else if (low.startsWith("buscar ") || low.startsWith("investiga ")) {
            val consulta = low.removePrefix("buscar ").removePrefix("investiga ").trim()
            respuesta = if (consulta.isBlank()) "¿Qué querés que busque?" else "Buscaré: $consulta. (demo)"
        } else {
            // Variar un poco para sonar más natural
            val opciones = listOf(
                "Te escuché: $finalText",
                "Entendí: $finalText",
                "Escuché que dijiste: $finalText",
                "Vale, $finalText"
            )
            respuesta = opciones.random()
        }

        if (isOnline) {
            lifecycleScope.launch {
                VoiceService.speak(
                    context = this@MainActivity,
                    text = respuesta,
                    onStart = { textView.text = "Nicky está hablando (nube)..." },
                    onDone  = { startListeningWindow(language) },
                    onError = { msg -> textView.text = "Error: $msg" }
                )
            }
        } else {
            textView.text = "Nicky está hablando (local)…"
            tts?.speak(respuesta, TextToSpeech.QUEUE_FLUSH, null, "nicky_reply")
            textView.postDelayed({ startListeningWindow(language) }, 800)
        }
    }

    /** Habla (nube/local) y al terminar entra a escuchar */
    private fun speakAndThenListen(texto: String) {
        state = State.SPEAKING
        tts?.stop()
        stopListeningIfNeeded()

        if (isOnline) {
            lifecycleScope.launch {
                VoiceService.speak(
                    context = this@MainActivity,
                    text = texto,
                    onStart = { textView.text = "Nicky está hablando (nube)..." },
                    onDone  = {
                        textView.text = "Listo, te escucho…"
                        followupUntil = System.currentTimeMillis() + FOLLOWUP_MS
                        startListeningWindow("es-ES")
                    },
                    onError = { msg ->
                        textView.text = "Error: $msg. Paso a modo local."
                        // si falla nube, cae a local
                        textView.postDelayed({
                            startListeningWindow("es-ES")
                        }, 200)
                    }
                )
            }
        } else {
            textView.text = "Nicky está hablando (local)…"
            tts?.speak("Hola, soy Nicky en modo local por desconexión de internet.",
                TextToSpeech.QUEUE_FLUSH, null, "nicky_local")
            textView.postDelayed({
                textView.text = "Listo, te escucho…"
                followupUntil = System.currentTimeMillis() + FOLLOWUP_MS
                startListeningWindow("es-ES")
            }, 1200)
        }
    }

    /** TTS local y al terminar escuchar */
    private fun speakLocalThenListen(texto: String) {
        state = State.SPEAKING
        textView.text = "Nicky está hablando (local)…"
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "nicky_local")
        textView.postDelayed({
            textView.text = "Listo, te escucho…"
            startListeningWindow("es-ES")
        }, 1200)
    }

    /** Inicia una sesión de STT. Al recibir texto final, responde y vuelve a escuchar. */
    // (Ya no se usa startListening() directamente tras permiso)

    private fun stopListeningIfNeeded() {
        windowJob?.cancel()
        windowJob = null
        listenJob?.cancel()
        listenJob = null
        finalizeJob?.cancel()
        finalizeJob = null
        partialDebounceJob?.cancel()
        partialDebounceJob = null
        lastPartial = null

        recognizer?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
        if (state == State.LISTENING) state = State.IDLE
    }

    override fun onDestroy() {
        stopListeningIfNeeded()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}