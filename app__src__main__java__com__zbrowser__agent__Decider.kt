package com.zbrowser.agent

import com.zbrowser.App
import com.zbrowser.data.Memory
import com.zbrowser.model.ApiFallback
import com.zbrowser.model.LlmManager
import org.json.JSONObject

class Decision(val action: String, val project: String, val comment: String, val source: String)

/** Asks the model for a small JSON decision, validates it, retries once, then API fallback. */
object Decider {

    private const val MAX_POST_CHARS = 4800 // ~1200 tokens

    suspend fun buildPrompt(postText: String): String {
        val rules = Memory.getRule("rules").ifBlank { "Write a short, relevant, natural reply. No hashtags, no links, no emojis spam." }
        val examples = Memory.getRule("examples").lines().filter { it.isNotBlank() }.take(5)
        val ex = if (examples.isEmpty()) "" else "Example comments:\n" + examples.joinToString("\n") { "- $it" } + "\n\n"
        val tone = when (App.prefs.getString("tone", "auto")) {
            "human" -> "Tone: casual and natural, plain words, contractions, like a real person.\n"
            "crypto" -> "Tone: natural crypto-community voice, light slang at most, no hype, no shilling.\n"
            else -> ""
        }
        return "You write replies to posts.\nRules:\n$rules\n$tone\n$ex" +
            "Post:\n\"\"\"\n${postText.take(MAX_POST_CHARS)}\n\"\"\"\n\n" +
            "Answer with JSON only, no other text: {\"action\":\"reply\" or \"skip\",\"project\":\"project name or empty\",\"comment\":\"max 20 words\"}"
    }

    suspend fun decide(postText: String): Decision {
        val prompt = buildPrompt(postText)
        val banned = Memory.getRule("banned").split(",", "\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        // duplicate-reply avoidance: never reuse a recent comment
        val recent = Memory.recentComments(60).map { norm(it) }.toSet()

        if (LlmManager.isLoaded()) {
            repeat(2) { i -> // first try + one retry
                val pr = if (i == 1) prompt + "\nUse different wording than any earlier comment." else prompt
                val r = LlmManager.generate(pr).getOrNull()
                val d = r?.let { parse(it, banned, "offline", recent) }
                if (d != null) return d
            }
        }
        if (ApiFallback.hasKey()) {
            val r = ApiFallback.generate(prompt).getOrNull()
            val d = r?.let { parse(it, banned, "api", recent) }
            if (d != null) return d
        }
        return Decision("skip", "", "", "invalid")
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun parse(raw: String, banned: List<String>, source: String, recent: Set<String> = emptySet()): Decision? {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val o = try { JSONObject(raw.substring(s, e + 1)) } catch (ex: Exception) { return null }
        val action = o.optString("action").lowercase()
        if (action != "reply" && action != "skip") return null
        val project = o.optString("project").trim()
        if (action == "skip") return Decision("skip", project, "", source)
        val comment = o.optString("comment").trim()
        if (comment.isEmpty()) return null
        if (comment.split(Regex("\\s+")).size > 20) return null
        val low = comment.lowercase()
        if (banned.any { low.contains(it) }) return null
        if (norm(comment) in recent) return null
        return Decision("reply", project, comment, source)
    }
}
