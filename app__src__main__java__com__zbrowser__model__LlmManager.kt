package com.zbrowser.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline model via MediaPipe LLM Inference. Model file is imported by the user,
 * never bundled and never committed. Loaded once, kept in memory.
 */
object LlmManager {
    @Volatile private var llm: LlmInference? = null
    @Volatile var loadedName: String = ""
        private set

    fun modelsDir(ctx: Context) = File(ctx.filesDir, "models").also { it.mkdirs() }

    fun importedModel(ctx: Context): File? =
        modelsDir(ctx).listFiles()?.firstOrNull { it.extension in setOf("task", "litertlm") }

    fun freeBytes(ctx: Context): Long = ctx.filesDir.usableSpace

    /** Copies the picked file into filesDir/models with progress (0..100). */
    suspend fun importModel(ctx: Context, uri: Uri, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                var name = "model.task"
                var size = -1L
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni)
                        if (si >= 0) size = c.getLong(si)
                    }
                }
                val ext = if (name.endsWith(".litertlm")) "litertlm" else "task"
                if (size > 0 && freeBytes(ctx) < size + 200L * 1024 * 1024)
                    return@withContext Result.failure(Exception("Not enough free storage (need ${size / 1048576} MB)"))
                modelsDir(ctx).listFiles()?.forEach { it.delete() }
                close()
                val out = File(modelsDir(ctx), "model.$ext")
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (size > 0) {
                                val p = (done * 100 / size).toInt()
                                if (p != last) { last = p; onProgress(p) }
                            }
                        }
                    }
                }
                com.zbrowser.App.prefs.edit().putString("model_name", name).apply()
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Load once on a background thread. Tries GPU first, then CPU. */
    suspend fun load(ctx: Context): Result<String> = withContext(Dispatchers.Default) {
        if (llm != null) return@withContext Result.success(loadedName)
        val f = importedModel(ctx) ?: return@withContext Result.failure(Exception("No model imported yet"))
        if (f.extension == "litertlm")
            return@withContext Result.failure(Exception(".litertlm may not load in MediaPipe. Use a .task file (E2B or Gemma 3 1B)."))
        val gpu = tryLoad(ctx, f, LlmInference.Backend.GPU)
        val res = if (gpu.isSuccess) gpu else tryLoad(ctx, f, LlmInference.Backend.CPU)
        res.onSuccess {
            llm = it
            loadedName = com.zbrowser.App.prefs.getString("model_name", f.name) ?: f.name
        }
        res.map { loadedName }
    }

    private fun tryLoad(ctx: Context, f: File, backend: LlmInference.Backend): Result<LlmInference> = try {
        val opts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(f.absolutePath)
            .setMaxTokens(2048)
            .setPreferredBackend(backend)
            .build()
        Result.success(LlmInference.createFromOptions(ctx.applicationContext, opts))
    } catch (e: Throwable) { // includes OutOfMemoryError
        Result.failure(Exception("${backend.name}: ${e.message}"))
    }

    fun isLoaded() = llm != null

    /** Low temperature, short answers. Never call on the main thread. */
    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.Default) {
        val m = llm ?: return@withContext Result.failure(Exception("Model not loaded"))
        try {
            val so = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(1)
                .setTemperature(0.2f)
                .build()
            LlmInferenceSession.createFromOptions(m, so).use { s ->
                s.addQueryChunk(prompt)
                Result.success(s.generateResponse())
            }
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: "generate failed"))
        }
    }

    fun close() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
        loadedName = ""
    }
}
