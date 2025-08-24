package com.example.android_app.util

import com.example.android_app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object OpenAIService {
    private val client by lazy { OkHttpClient() }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Pide a OpenAI que convierta el texto del usuario en una query corta y efectiva para Google.
     * Ej: "clima San José CR hoy", "mejores restaurantes ticos en Heredia", etc.
     */
    suspend fun refineSearchQuery(userText: String, lang: String = "es-ES"): String {
        val apiKey = BuildConfig.OPENAI_API_KEY.orEmpty()
        if (apiKey.isBlank()) return userText.trim()

        val system = """
            Eres un optimizador de búsquedas. 
            Devuelve SOLO una consulta corta y efectiva para buscadores (sin comillas, sin explicación).
            Usa operadores si ayudan (site:, intitle:, filetype:, etc.). Idioma objetivo: $lang.
        """.trimIndent()
        val user = "Necesito buscar: $userText"

        val body = """
            {
              "model": "gpt-4o-mini",
              "messages": [
                {"role": "system", "content": ${jsonStr(system)}},
                {"role": "user",   "content": ${jsonStr(user)}}
              ],
              "temperature": 0.2,
              "max_tokens": 64
            }
        """.trimIndent()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || txt.isBlank()) return userText.trim()
            return try {
                JSONObject(txt)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } catch (_: Exception) {
                userText.trim()
            }
        }
    }

    private fun jsonStr(s: String) =
        JSONObject.quote(s) // escapa seguro para JSON
}