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
import android.content.pm.PackageManager
import android.os.SystemClock
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import com.example.android_app.util.Bt

private const val LISTEN_WINDOW_MS = 6000L
private const val FOLLOWUP_MS = 10000L

// Espera mínima antes de reabrir el micro tras hablar (online u offline)
private const val MIC_REOPEN_DELAY_MS = 0L

// Cooldown para evitar “flapping” si se intenta abrir el micro demasiado seguido
private const val MIC_COOLDOWN_MS = 900L
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
private var lastMicOpenAt: Long = 0L
private val ttsCallbacks = mutableMapOf<String, () -> Unit>()

private var onMicGranted: (() -> Unit)? = null

class MainActivity : AppCompatActivity() {


    private lateinit var textView: TextView
    private lateinit var buttonTalk: Button
    private lateinit var buttonListen: Button

    private var isOnline: Boolean = false
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false

    private val enableBtLauncher = registerForActivityResult(StartActivityForResult()) { _ ->
        textView.text = if (Bt.isEnabled()) "Bluetooth activado ✅" else "No se activó Bluetooth"
        // reabrir escucha si corresponde
        if (state != State.SPEAKING) startListeningWindow("es-ES")
    }

    private enum class State { IDLE, SPEAKING, LISTENING }
    private var state: State = State.IDLE

    // Permiso micrófono (ejecuta acción pendiente al conceder)
    private val askAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onMicGranted?.let { it() }
            onMicGranted = null
        } else {
            textView.text = "Permiso de micrófono denegado"
        }
    }

    private fun ensureBtPermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31) {
            val perm = android.Manifest.permission.BLUETOOTH_CONNECT
            if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                // Reutilizamos el mismo launcher de permisos (RequestPermission)
                onMicGranted = onGranted
                askAudioPermission.launch(perm)
            }
        } else {
            onGranted()
        }
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
            ensureTtsLanguage(status)
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { /* no-op */ }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == null) return
                    val cb = synchronized(ttsCallbacks) { ttsCallbacks.remove(utteranceId) }
                    if (cb != null) runOnUiThread { cb() }
                }
                override fun onError(utteranceId: String?) {
                    synchronized(ttsCallbacks) { ttsCallbacks.remove(utteranceId) }
                }
            })
        }

        // Botón Hablar: inicia el ciclo manos libres con palabra clave
        buttonTalk.setOnClickListener {
            stopListeningIfNeeded()
            tts?.stop()

            val action = {
                speakAndThenListen("Hola, soy Nicky. ¿Listo para conversar?")
            }

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                action()
            } else {
                onMicGranted = action
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
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    action()
                } else {
                    onMicGranted = action
                    askAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    /**
     * Busca un Locale de español disponible para TTS.
     */
    private fun pickAvailableSpanishLocale(tts: TextToSpeech?): Locale {
        val locales = listOf(
            Locale("es", "ES"),
            Locale("es", "MX"),
            Locale("es", "AR"),
            Locale("es", "US"),
            Locale("es"),
            Locale("es", "CO"),
            Locale("es", "CL")
        )
        if (tts != null) {
            for (loc in locales) {
                val result = tts.isLanguageAvailable(loc)
                if (result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    result == TextToSpeech.LANG_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                ) {
                    return loc
                }
            }
        }
        // Fallback
        return Locale("es", "ES")
    }

    /**
     * Asegura que el TTS use un idioma español disponible.
     */
    private fun ensureTtsLanguage(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val loc = pickAvailableSpanishLocale(tts)
            tts?.language = loc
        }
    }

    private fun startListeningWindow(language: String = "es-ES") {

        // Evitar abrir micro si el TTS sigue hablando
        if (state == State.SPEAKING) {
            textView.postDelayed({ startListeningWindow(language) }, 200)
            return
        }

        // Cooldown para evitar "flapping" del micrófono
        val nowTs = SystemClock.elapsedRealtime()
        val sinceLastOpen = nowTs - lastMicOpenAt
        if (sinceLastOpen in 0..MIC_COOLDOWN_MS) {
            // Reintentar luego del cooldown restante
            val remaining = MIC_COOLDOWN_MS - sinceLastOpen
            textView.postDelayed({ startListeningWindow(language) }, remaining)
            return
        }
        lastMicOpenAt = nowTs
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
                                            kotlinx.coroutines.delay(MIC_REOPEN_DELAY_MS)
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
                    preferOffline = !isOnline
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
                                            onDone  = { textView.postDelayed({ startListeningWindow(language) }, MIC_REOPEN_DELAY_MS) },
                                            onError = { msg -> textView.text = "Error: $msg" }
                                        )
                                    } else {
                                        textView.text = "Nicky está hablando (local)…"
                                        tts?.speak(aviso, TextToSpeech.QUEUE_FLUSH, null, "nicky_prompt_kw")
                                        launch {
                                            kotlinx.coroutines.delay(MIC_REOPEN_DELAY_MS)
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
                        // --- Chequeo de errores de idioma y fallback ---
                        val errLower = err.lowercase()
                        val isLanguageIssue = errLower.contains("idioma") ||
                                errLower.contains("language") ||
                                errLower.contains("not supported") ||
                                errLower.contains("no soporta") ||
                                errLower.contains("no soportado")
                        if (isLanguageIssue) {
                            if (isOnline) {
                                textView.text = "⚠️ El reconocimiento no soporta el idioma $language. Probando español estándar…"
                                startListeningWindow("es-ES")
                            } else {
                                textView.text = "Sin Internet (modo local). Instalá el paquete de voz ‘Español’ para dictado sin conexión (Gboard &gt; Voice typing &gt; Offline speech recognition)."
                                // No reintentar en offline para evitar flapping
                            }
                            return@let
                        }

                        // Limpiar estados locales de esta sesión
                        partialDebounceJob?.cancel(); partialDebounceJob = null
                        finalizeJob?.cancel(); finalizeJob = null
                        lastPartial = null
                        isListening = false
                        state = State.IDLE

                        val isNetworkish = errLower.contains("red") ||
                                errLower.contains("network") ||
                                errLower.contains("timeout") ||
                                errLower.contains("tiempo de red")

                        if (!isOnline) {
                            // OFFLINE: NO reintentar de inmediato para evitar el "flapping" del micrófono.
                            textView.text = "Sin Internet (modo local). Para usar dictado sin conexión, descargá el paquete de voz ‘Español’ (Gboard > Voice typing > Offline speech recognition). Tocá “Hablar” cuando estés listo."
                            // Importante: NO llamar a startListeningWindow aquí.
                        } else {
                            // ONLINE: mostramos el error y reintentamos.
                            textView.text = "⚠️ $err"
                            textView.postDelayed({ startListeningWindow(language) }, MIC_REOPEN_DELAY_MS)
                        }
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
        // --- Comandos de salida (funcionan siempre, con o sin keyword) ---
        run {
            val exitCommands = setOf("salir", "cerrar", "terminar", "cierra", "apagar")
            if (exitCommands.contains(low.trim())) {
                handlingUtterance = true
                windowJob?.cancel()
                finalizeJob?.cancel()
                partialDebounceJob?.cancel()

                val despedida = "Hasta luego."
                if (isOnline) {
                    lifecycleScope.launch {
                        VoiceService.speak(
                            context = this@MainActivity,
                            text = despedida,
                            onStart = { textView.text = "Nicky está cerrando la aplicación…" },
                            onDone  = { finishAffinity() },
                            onError = { finishAffinity() }
                        )
                    }
                } else {
                    textView.text = "Nicky está cerrando la aplicación…"
                    tts?.speak(despedida, TextToSpeech.QUEUE_FLUSH, null, "nicky_exit")
                    textView.postDelayed({ finishAffinity() }, 1000)
                }
                return
            }
        }
        // --- Comandos Bluetooth ---
        run {
            val low2 = low // alias
            if (low2.contains("bluetooth")) {
                if (!Bt.isSupported()) {
                    responderYVolver(language, "Tu dispositivo no tiene Bluetooth.")
                    return
                }
                when {
                    low2.contains("activar") || low2.contains("encender") || low2.contains("prender") -> {
                        ensureBtPermission {
                            responderPrevio(language, "Abriendo diálogo para activar Bluetooth…")
                            enableBtLauncher.launch(Bt.requestEnableIntent())
                        }
                        return
                    }
                    low2.contains("apagar") || low2.contains("desactivar") -> {
                        ensureBtPermission {
                            val ok = Bt.tryDisable()
                            val msg = if (ok) "Apagando Bluetooth…" else "No pude apagarlo automáticamente en este dispositivo."
                            responderYVolver(language, msg)
                        }
                        return
                    }
                    low2.contains("estado") || low2.contains("está encendido") || low2.contains("esta encendido") -> {
                        val msg = if (Bt.isEnabled()) "Bluetooth está activado." else "Bluetooth está desactivado."
                        responderYVolver(language, msg)
                        return
                    }
                }
            }
        }
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
                        onDone  = { textView.postDelayed({ startListeningWindow(language) }, MIC_REOPEN_DELAY_MS) },
                        onError = { msg -> textView.text = "Error: $msg" }
                    )
                }
            } else {
                textView.text = "Nicky está hablando (local)…"
                tts?.speak(aviso, TextToSpeech.QUEUE_FLUSH, null, "nicky_prompt_kw")
                textView.postDelayed({ startListeningWindow(language) }, MIC_REOPEN_DELAY_MS)
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
                    onDone  = { textView.postDelayed({ startListeningWindow(language) }, MIC_REOPEN_DELAY_MS) },
                    onError = { msg -> textView.text = "Error: $msg" }
                )
            }
        } else {
            textView.text = "Nicky está hablando (local)…"
            tts?.speak(respuesta, TextToSpeech.QUEUE_FLUSH, null, "nicky_reply")
            textView.postDelayed({ startListeningWindow(language) }, MIC_REOPEN_DELAY_MS)
        }
    }

    private fun responderPrevio(language: String, texto: String) {
        if (isOnline) {
            lifecycleScope.launch {
                VoiceService.speak(
                    context = this@MainActivity,
                    text = texto,
                    onStart = { textView.text = "Nicky está hablando (nube)..." },
                    onDone  = { /* el launcher/flujo continuará */ },
                    onError = { /* ignoramos errores breves aquí */ }
                )
            }
        } else {
            textView.text = "Nicky está hablando (local)…"
            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "bt_prev")
        }
    }

    private fun responderYVolver(language: String, texto: String) {
        if (isOnline) {
            lifecycleScope.launch {
                VoiceService.speak(
                    context = this@MainActivity,
                    text = texto,
                    onStart = { textView.text = "Nicky está hablando (nube)..." },
                    onDone  = { textView.postDelayed({ startListeningWindow(language) }, MIC_REOPEN_DELAY_MS) },
                    onError = { msg -> textView.text = "Error: $msg" }
                )
            }
        } else {
            textView.text = "Nicky está hablando (local)…"
            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "bt_reply")
            textView.postDelayed({ startListeningWindow(language) }, MIC_REOPEN_DELAY_MS)
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
                        state = State.IDLE
                        textView.text = "Listo, te escucho…"
                        followupUntil = System.currentTimeMillis() + FOLLOWUP_MS
                        startListeningWindow("es-ES")
                    },
                    onError = { msg ->
                        textView.text = "Error: $msg. Paso a modo local."
                        val utteranceId = "local_${System.currentTimeMillis()}"
                        synchronized(ttsCallbacks) {
                            ttsCallbacks[utteranceId] = {
                                if (state == State.SPEAKING) {
                                    state = State.IDLE
                                    textView.text = "Listo, te escucho…"
                                    followupUntil = System.currentTimeMillis() + FOLLOWUP_MS
                                    textView.postDelayed({ startListeningWindow("es-ES") }, MIC_REOPEN_DELAY_MS)
                                }
                            }
                        }
                        tts?.speak(
                            "Estoy en modo local por ahora.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            utteranceId
                        )
                    }
                )
            }
        } else {
            state = State.SPEAKING
            textView.text = "Nicky está hablando (local)…"

            val utteranceId = "local_${System.currentTimeMillis()}"
            synchronized(ttsCallbacks) {
                ttsCallbacks[utteranceId] = {
                    // Se llama EXACTAMENTE cuando termina de hablar
                    if (state != State.SPEAKING) {
                        // Ignorar si el estado ya cambió
                    } else {
                        state = State.IDLE
                        textView.text = "Listo, te escucho…"
                        followupUntil = System.currentTimeMillis() + FOLLOWUP_MS
                        textView.postDelayed({ startListeningWindow("es-ES") }, MIC_REOPEN_DELAY_MS)
                    }
                }
            }
            tts?.speak(
                "Hola, soy Nicky en modo local por desconexión de internet.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )
        }
    }

    /** TTS local y al terminar escuchar (sin delays fijos) */
    private fun speakLocalThenListen(texto: String) {
        state = State.SPEAKING
        textView.text = "Nicky está hablando (local)…"
        val utteranceId = "local_${System.currentTimeMillis()}"
        synchronized(ttsCallbacks) {
            ttsCallbacks[utteranceId] = {
                if (state != State.SPEAKING) {
                    // Ignorar si ya no estamos en SPEAKING
                } else {
                    textView.text = "Listo, te escucho…"
                    textView.postDelayed({ startListeningWindow("es-ES") }, MIC_REOPEN_DELAY_MS)
                }
            }
        }
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** Inicia una sesión de STT. Al recibir texto final, responde y vuelve a escuchar. */
    private fun startListening() {
        // Crea recognizer por sesión
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        state = State.LISTENING
        textView.text = "🎙️ Escuchando… hablá con normalidad"

        lifecycleScope.launch {
            Speech.listenFlow(
                recognizer = recognizer!!,
                language = "es-ES",      // lo que probaste en la tablet
                preferOffline = !isOnline
            ).collectLatest { ev ->
                ev.listening?.let { listening ->
                    if (!listening && state == State.LISTENING && textView.text.contains("Escuchando")) {
                        textView.text = "Procesando…"
                    }
                }
                ev.partial?.let { partial ->
                    // Mostrar parcial, no cambia estado
                    textView.text = "🗣️ $partial"
                }
                ev.finalText?.let { final ->
                    // Tenemos texto final: responder y volver a escuchar
                    val low = final.lowercase().trim()
                    val respuesta = when {
                        low.startsWith("buscar ") || low.startsWith("investiga ") -> {
                            val q = low.removePrefix("buscar ").removePrefix("investiga ").trim()
                            if (q.isBlank()) "¿Qué querés que busque?" else "Buscaré: $q. (demo)"
                        }
                        low.contains("hola") -> "¡Hola! ¿En qué te ayudo?"
                        else -> "Te escuché: $final"
                    }

                    // Habla respuesta y vuelve a escuchar
                    speakAndThenListen(respuesta)
                }
                ev.error?.let { err ->
                    textView.text = "⚠️ $err"
                    // En errores comunes, intenta escuchar de nuevo
                    if (err.contains("No te entendí", true) ||
                        err.contains("No detecté voz", true) ||
                        err.contains("Tiempo de red", true) ||
                        err.contains("Error de red", true)
                    ) {
                        textView.postDelayed({ startListening() }, 600)
                    } else {
                        state = State.IDLE
                    }
                }
            }
        }
    }

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