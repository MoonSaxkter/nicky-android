package com.example.android_app



import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView
import android.speech.tts.TextToSpeech
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var tts: TextToSpeech
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        val buttonTalk = findViewById<Button>(R.id.buttonTalk)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("es", "CR")
            }
        }

        // Set UtteranceProgressListener after tts is initialized
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    textView.text = "Nicky está hablando..."
                }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    textView.text = "Listo para escucharte"
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    textView.text = "Error al hablar"
                }
            }
        })

        buttonTalk.setOnClickListener {
            textView.text = "Preparando respuesta..."
            tts.speak(
                "Hola, soy Nicky, tu asistente en Android",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "nicky1"
            )
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}