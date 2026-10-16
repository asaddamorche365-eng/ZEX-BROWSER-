package com.zbrowser.data

import com.zbrowser.App
import com.zbrowser.bridge.UrlUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** All memory rules in one place: duplicate check, project tagging, rules text, imports. */
object Memory {
    private val db get() = App.db

    suspend fun isDone(postId: String): Boolean = db.done().has(postId) > 0

    suspend fun markPending(postId: String, url: String, author: String, engine: String) {
        db.done().insertIgnore(DonePost(postId, url, author, "pending", "", "", engine, System.currentTimeMillis()))
    }

    suspend fun finish(postId: String, url: String, author: String, action: String, project: String, comment: String, engine: String) {
        db.done().upsert(DonePost(postId, url, author, action, project, comment, engine, System.currentTimeMillis()))
        if (action == "reply") bumpProject(project)
    }

    /** The user's own reply is stored as done so it is never treated as a new post. */
    suspend fun markOwn(postId: String, url: String, author: String, comment: String, engine: String) {
        db.done().insertIgnore(DonePost(postId, url, author, "own", "", comment, engine, System.currentTimeMillis()))
    }

    suspend fun actionOf(postId: String): String? = db.done().actionOf(postId)
    suspend fun recentComments(n: Int): List<String> = db.done().recentComments(n)

    // ---- saved link folders (stored in the rules table as folder:<name>) ----
    suspend fun folderNames(): List<String> = db.rules().keysWithPrefix("folder:").map { it.removePrefix("folder:") }

    suspend fun folderLinks(name: String): List<String> =
        getRule("folder:$name").lines().map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun addToFolder(name: String, links: Collection<String>) {
        val all = LinkedHashSet(folderLinks(name))
        all.addAll(links)
        setRule("folder:$name", all.joinToString("\n"))
    }

    // ---- people ----
    /** Creates the person if missing, then sets the status. */
    suspend fun setPerson(username: String, status: String, source: String = "import") {
        val u = username.lowercase().removePrefix("@")
        db.people().insertAll(listOf(Person(u, source, "new")))
        db.people().setStatus(u, status)
    }

    suspend fun addPeople(names: Collection<String>, source: String) {
        names.map { it.lowercase().removePrefix("@") }.filter { it.isNotEmpty() }.distinct()
            .chunked(500).forEach { c -> db.people().insertAll(c.map { Person(it, source, "new") }) }
    }

    suspend fun exportPeople(): String = db.people().all().joinToString("\n") { "${it.username},${it.status}" }

    suspend fun exportFailed(): String = db.done().historyBy("failed", 100000).joinToString("\n") { it.url }

    // ---- project tagging ----
    private val UNKNOWN = setOf("", "unknown", "none", "n/a", "na", "null", "no project", "other", "-")

    suspend fun normalizeProject(raw: String): String? {
        val n = raw.trim().lowercase().removePrefix("@").removePrefix("$").trim()
        if (n in UNKNOWN) return null
        val aliasMap = parseAliases(getRule("aliases"))
        return aliasMap[n] ?: n
    }

    /** aliases rule format, one per line: canonical = alias1, alias2 */
    private fun parseAliases(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        text.lines().forEach { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                val canon = parts[0].trim().lowercase()
                if (canon.isNotEmpty()) {
                    m[canon] = canon
                    parts[1].split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { m[it] = canon }
                }
            }
        }
        return m
    }

    private suspend fun bumpProject(project: String) {
        val name = normalizeProject(project) ?: return // unknown/empty stays out of the count
        val now = System.currentTimeMillis()
        db.projects().insert(Project(name = name, aliases = "", commentCount = 0, lastSeen = now))
        db.projects().bump(name, now)
    }

    // ---- rules ----
    suspend fun getRule(key: String): String = db.rules().get(key) ?: ""
    suspend fun setRule(key: String, value: String) = db.rules().put(Rule(key, value))

    // ---- imports ----
    class ImportResult(val added: Int, val skipped: Int)

    /** Accepts TXT (one link per line), CSV (any column with links) or JSON (array of strings/objects). */
    suspend fun importLinks(stream: InputStream, fileName: String): ImportResult {
        val text = stream.bufferedReader().use { it.readText() }
        val urls = LinkedHashSet<String>()
        if (fileName.lowercase().endsWith(".json") || text.trimStart().startsWith("[") || text.trimStart().startsWith("{")) {
            try { collectJson(JSONArray(if (text.trimStart().startsWith("{")) "[${text}]" else text), urls) } catch (e: Exception) { collectText(text, urls) }
        } else collectText(text, urls)
        val now = System.currentTimeMillis()
        val rows = ArrayList<DonePost>()
        var bad = 0
        for (u in urls) {
            val id = UrlUtil.statusId(u)
            if (id == null) { bad++; continue }
            rows += DonePost(id, UrlUtil.canonical(u), UrlUtil.handleFromUrl(u) ?: "", "imported", "", "", "import", now)
        }
        rows.chunked(500).forEach { db.done().insertAllIgnore(it) }
        return ImportResult(rows.size, bad)
    }

    private fun collectText(text: String, out: MutableSet<String>) {
        Regex("""https?://(?:www\.|mobile\.)?(?:x|twitter)\.com/\S+?/status(?:es)?/\d+""").findAll(text).forEach { out += it.value }
    }

    private fun collectJson(arr: JSONArray, out: MutableSet<String>) {
        for (i in 0 until arr.length()) {
            when (val v = arr.get(i)) {
                is String -> collectText(v, out)
                is JSONObject -> collectText(v.toString(), out)
                is JSONArray -> collectJson(v, out)
            }
        }
    }

    suspend fun importPeople(stream: InputStream, source: String): Int {
        val text = stream.bufferedReader().use { it.readText() }
        val names = text.split(Regex("[\\s,;]+"))
            .map { it.trim().removePrefix("@").lowercase() }
            .filter { it.matches(Regex("[a-z0-9_]{1,15}")) }
            .distinct()
        names.chunked(500).forEach { chunk -> db.people().insertAll(chunk.map { Person(it, source, "new") }) }
        return names.size
    }
}
