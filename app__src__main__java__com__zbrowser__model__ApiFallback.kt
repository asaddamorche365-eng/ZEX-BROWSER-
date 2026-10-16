package com.zbrowser.model

import com.zbrowser.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Optional Gemini fallback. Key lives in app settings only, never in the repo. */
object ApiFallback {
    fun hasKey() = !App.prefs.getString("api_key", "").isNullOrBlank()

    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val key = App.prefs.getString("api_key", "") ?: ""
        if (key.isBlank()) return@withContext Result.failure(Exception("No API key"))
        try {
            val model = App.prefs.getString("api_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
            val u = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val c = u.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 120))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) return@withContext Result.failure(Exception("API ${c.responseCode}"))
            val resp = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val t = resp.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            Result.success(t)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
