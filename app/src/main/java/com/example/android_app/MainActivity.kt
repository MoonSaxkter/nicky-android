package com.example.android_app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.example.android_app.voice.VoiceService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        val buttonTalk = findViewById<Button>(R.id.buttonTalk)

        buttonTalk.setOnClickListener {
            // Texto de prueba (puedes usar lo que quieras aquí)
            val texto = "Hola, soy Nicky en Android. ¿Listo para jugar?"

            lifecycleScope.launch {
                VoiceService.speak(
                    context = this@MainActivity,
                    text = texto,
                    onStart = { textView.text = "Nicky está hablando..." },
                    onDone  = { textView.text = "Listo para escucharte" },
                    onError = { msg -> textView.text = "Error: $msg" }
                )
            }
        }
    }
}