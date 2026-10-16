package com.zbrowser.agent

import android.content.Context
import com.zbrowser.App
import com.zbrowser.bridge.Bridge
import com.zbrowser.bridge.BrowserHolder
import com.zbrowser.bridge.Post
import com.zbrowser.bridge.UrlUtil
import com.zbrowser.data.Memory
import kotlinx.coroutines.*
import java.net.URLEncoder
import kotlin.random.Random

/** Plain Kotlin loop. The model only makes small decisions; code does everything else. */
object AgentLoop {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    @Volatile private var stopFlag = false
    @Volatile private var errorsInRow = 0
    @Volatile private var errorPauseUsed = false
    private var repliesThisRun = 0
    @Volatile private var stuckInRow = 0
    private val resetTimes = ArrayList<Long>()

    private val logLines = ArrayDeque<String>()
    @Volatile var listener: ((String) -> Unit)? = null

    fun isRunning() = job?.isActive == true
    fun history(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun log(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val line = "$t $msg"
        synchronized(logLines) { logLines.addLast(line); while (logLines.size > 400) logLines.removeFirst() }
        listener?.invoke(line)
    }

    fun requestStop() { stopFlag = true; log("Stop requested. Ends after the current step.") }

    private class StopLoop(val reason: String) : Exception()

    private fun bridge(ctx: Context) = Bridge(BrowserHolder.get(ctx), ctx.applicationContext)

    // ---------- Step 1: log-only detection (no actions) ----------
    suspend fun detectOnce(ctx: Context): String {
        val b = bridge(ctx)
        val me = b.detectOwnHandle()
        val st = b.pageState()
        val posts = b.getVisiblePosts()
        val sb = StringBuilder("own=@$me url=${st.url} login=${st.login} err='${st.error}' articles=${st.articles}\n")
        for (p in posts) {
            val inMem = Memory.isDone(p.id)
            sb.append("• ${p.id} @${p.author} ad=${p.isAd} reply=${p.isReply} own=${p.isOwn} followedByOwn=${p.alreadyReplied} inMemory=$inMem\n")
        }
        if (posts.isEmpty()) sb.append("(no posts detected: check selectors/x.json on the live page)\n")
        return sb.toString()
    }

    // ---------- Engines ----------
    fun start(ctx: Context, engine: String, arg: String) {
        if (isRunning()) { log("Already running."); return }
        stopFlag = false; errorsInRow = 0; errorPauseUsed = false; repliesThisRun = 0; stuckInRow = 0
        AgentService.start(ctx, "Running $engine engine")
        job = scope.launch {
            try {
                val b = bridge(ctx)
                withContext(Dispatchers.Main) { }
                val me = b.detectOwnHandle()
                if (me.isBlank()) throw StopLoop("Cannot read your X handle. Open x.com/home, log in, then start again (for Telegram engines open x.com/home once, then go back to Telegram).")
                log("Start $engine as @$me")
                paceWarning()?.let { log("WARNING: $it") }
                when (engine) {
                    "feed" -> repeatRounds(listOf("https://x.com/home"), "feed", b, 20)
                    "search" -> {
                        val topics = (Memory.getRule("topics").ifBlank { arg }).split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
                        if (topics.isEmpty()) throw StopLoop("Add search topics first (a line starting with // is switched off).")
                        val urls = topics.map { "https://x.com/search?q=${URLEncoder.encode(it, "UTF-8")}&src=typed_query&f=live" }
                        repeatRounds(urls, "search", b, 15)
                    }
                    "list" -> {
                        if (!arg.contains("/i/lists/") && !arg.contains("/lists/")) throw StopLoop("Paste a valid X list link.")
                        repeatRounds(listOf(arg.trim()), "list", b, 20)
                    }
                    "follow" -> followRun(b, false)
                    "unfollow" -> followRun(b, true)
                    "tg" -> tgRun(b)
                    "sweep" -> {
                        if (arg.isBlank()) throw StopLoop("Type a saved folder name first.")
                        sweepRun(arg.trim(), b)
                    }
                    "collect" -> collectRun(b)
                    "retry" -> retryRun(b)
                }
            } catch (e: StopLoop) {
                log("STOPPED: ${e.reason}")
                if (e.reason != "user") AgentService.alert(ctx, e.reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("STOPPED on error: ${e.message}")
                AgentService.alert(ctx, "Agent error: ${e.message}")
            } finally {
                AgentService.stop(ctx)
                log("Agent ended. Replies this run: $repliesThisRun")
            }
        }
    }

    private suspend fun repeatRounds(urls: List<String>, engine: String, b: Bridge, scrolls: Int) {
        while (true) {
            for (u in urls) {
                checkStop()
                runCycle(engine, u, b, scrolls)
            }
            checkStop()
            val wait = Random.nextLong(60_000, 180_000)
            log("Round done. Refresh in ${wait / 1000}s")
            sleepStoppable(wait)
        }
    }

    private fun checkStop() { if (stopFlag) throw StopLoop("user") }

    private suspend fun sleepStoppable(ms: Long) {
        var left = ms
        while (left > 0) { checkStop(); val s = minOf(left, 1000L); delay(s); left -= s }
    }

    /** Reads the page for a login wall or error page. Stops after 3 errors in a row. */
    private suspend fun guard(b: Bridge) {
        val st = b.pageState()
        if (st.login) throw StopLoop("Login wall detected. Log in to X in the browser tab, then start again.")
        if (st.error.isNotEmpty()) {
            errorsInRow++
            log("Page error '${st.error}' ($errorsInRow/3)")
            if (st.error.contains("automated", true) || st.error.contains("unable", true) || errorsInRow >= 3) throw StopLoop("X showed '${st.error}'. Take a break before running again.")
            sleepStoppable(20_000) // wait about 20 s after the first error
            b.load(st.url.ifBlank { "https://x.com/home" })
            delay(4000)
        }
    }

    private suspend fun runCycle(engine: String, startUrl: String, b: Bridge, maxScrolls: Int) {
        b.load(startUrl); sleepStoppable(4500)
        var scrolls = 0
        while (scrolls < maxScrolls) {
            checkStop()
            guard(b)
            val blocked = Memory.getRule("blocked").split(",", "\n").map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }.toSet()
            val keywords = Memory.getRule("keywords").split(",", "\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val posts = b.getVisiblePosts()
            var reloaded = false
            for (p in posts) {
                checkStop()
                // ---- filter in code, no AI ----
                if (p.isOwn) { Memory.markOwn(p.id, p.url, p.author, p.text, engine); continue }
                if (p.isAd) continue
                if (p.isReply) continue                  // act only on top-level posts
                if (p.author in blocked) continue
                if (Memory.isDone(p.id)) continue
                if (p.alreadyReplied) {                  // an own reply already sits under it
                    Memory.finish(p.id, p.url, p.author, "skip", "", "already replied (seen)", engine); continue
                }
                if (p.text.isBlank()) continue
                if (keywords.isNotEmpty() && keywords.none { p.text.lowercase().contains(it) }) continue

                val handled = handlePost(p, engine, b)
                if (handled == Handled.REPLIED_OR_FAILED) { reloaded = true; break }
                sleepStoppable(Random.nextLong(1200, 3200))
            }
            if (reloaded) {
                b.load(startUrl); sleepStoppable(4500)
            } else {
                b.scrollNext()
            }
            scrolls++
        }
    }

    private enum class Handled { SKIPPED, REPLIED_OR_FAILED }

    private suspend fun handlePost(p: Post, engine: String, b: Bridge, alreadyOpen: Boolean = false): Handled {
        val d = Decider.decide(p.text)
        log("@${p.author} ${p.id} -> ${d.action} (${d.source})")
        if (d.action != "reply") {
            Memory.finish(p.id, p.url, p.author, "skip", "", if (d.source == "invalid") "invalid model output" else "", engine)
            return Handled.SKIPPED
        }
        if (repliesThisRun >= (App.prefs.getInt("max_replies", 100))) throw StopLoop("Reached max replies for this run (${App.prefs.getInt("max_replies", 100)}).")

        // Write target id to memory BEFORE sending
        Memory.markPending(p.id, p.url, p.author, engine)

        val opened = alreadyOpen || b.openPost(p.url)
        if (!opened) { maybeFreshTab(b); return fail(p, d, engine, "post did not open / wrong id") }
        stuckInRow = 0
        sleepStoppable(Random.nextLong(1500, 3500))
        guard(b)
        val ti = b.threadInfo()
        if (ti.pageId != p.id || !ti.mainFound) return fail(p, d, engine, "opened id mismatch")
        if (ti.ownReplyVisible) {      // an own article is already under this post
            Memory.finish(p.id, p.url, p.author, "skip", "", "own reply already visible", engine)
            log("Own reply already visible, skip ${p.id}")
            return Handled.REPLIED_OR_FAILED
        }
        if (App.prefs.getBoolean("like_first", true)) {
            if (b.like()) log("Liked ${p.id}")
            sleepStoppable(Random.nextLong(900, 2200))
        }
        val sent = b.typeAndSend(d.comment)
        if (!sent) return fail(p, d, engine, "could not type/send")
        sleepStoppable(3500)
        val after = b.threadInfo()
        if (!after.ownReplyVisible) return fail(p, d, engine, "reply not visible after sending")
        // store the new reply's own id right away so it is never treated as a new post
        if (after.ownReplyId.isNotBlank() && after.ownReplyId != p.id)
            Memory.markOwn(after.ownReplyId, "https://x.com/${b.ownHandle}/status/${after.ownReplyId}", b.ownHandle, d.comment, engine)

        val proj = Memory.normalizeProject(d.project) ?: ""
        Memory.finish(p.id, p.url, p.author, "reply", proj, d.comment, engine)
        errorsInRow = 0
        repliesThisRun++
        log("REPLIED ${p.id} project='${proj}' \"${d.comment}\"")
        pace(repliesThisRun)
        return Handled.REPLIED_OR_FAILED
    }

    /** A failed post is stored as failed and is not retried automatically. */
    private suspend fun fail(p: Post, d: Decision, engine: String, why: String): Handled {
        Memory.finish(p.id, p.url, p.author, "failed", "", why, engine)
        errorsInRow++
        log("FAILED ${p.id}: $why ($errorsInRow/3)")
        if (errorsInRow >= 3) throw StopLoop("3 errors in a row. Last: $why")
        return Handled.REPLIED_OR_FAILED
    }

    // ---------------- pace ----------------
    private fun slowFactor() = if (App.prefs.getString("preset", "fast") == "slow") 2.5 else 1.0

    /** Delay after one action. FAST/SLOW use the base range; CUSTOM spreads "N actions in M minutes". */
    private fun itemDelayMs(baseMin: Long, baseMax: Long): Long {
        if (App.prefs.getString("preset", "fast") == "custom") {
            val n = App.prefs.getInt("custom_posts", 50).coerceAtLeast(1)
            val m = App.prefs.getInt("custom_minutes", 20).coerceAtLeast(1)
            val per = m * 60_000L / n
            return (per * Random.nextDouble(0.8, 1.2)).toLong().coerceAtLeast(10_000L)
        }
        return (Random.nextLong(baseMin, baseMax) * slowFactor()).toLong()
    }

    /** Warning text when the CUSTOM pace is too fast, else null. */
    fun paceWarning(): String? {
        if (App.prefs.getString("preset", "fast") != "custom") return null
        val n = App.prefs.getInt("custom_posts", 50).coerceAtLeast(1)
        val m = App.prefs.getInt("custom_minutes", 20).coerceAtLeast(1)
        val per = m * 60.0 / n
        return if (per < 20.0) "Too fast: ${per.toInt()} s per action. High risk of X limits. The app never goes below 10 s." else null
    }

    private suspend fun pace(count: Int, minMs: Long = 20_000, maxMs: Long = 45_000) {
        val ms = itemDelayMs(minMs, maxMs)
        log("Wait ${ms / 1000}s")
        sleepStoppable(ms)
        if (count > 0 && count % 50 == 0) {
            val brk = (Random.nextLong(240_000, 480_000) * slowFactor()).toLong()
            log("Long break ${brk / 60000} min after $count actions")
            sleepStoppable(brk)
        }
    }

    private fun bump(why: String) {
        errorsInRow++
        log("$why ($errorsInRow/3)")
        if (errorsInRow >= 3) throw StopLoop("3 errors in a row. Last: $why")
    }

    /** Stuck on the wrong page twice in a row: reset to a clean page. Max 3 times per 30 minutes. */
    private suspend fun maybeFreshTab(b: Bridge) {
        stuckInRow++
        if (stuckInRow < 2) return
        val now = System.currentTimeMillis()
        resetTimes.removeAll { now - it > 30 * 60_000L }
        if (resetTimes.size >= 3) { log("Fresh page limit reached (3 per 30 min)"); return }
        resetTimes += now
        stuckInRow = 0
        log("Stuck on the wrong page. Opening a fresh page.")
        b.freshTab()
        sleepStoppable(2000)
    }

    private suspend fun blockedSet(): Set<String> =
        Memory.getRule("blocked").split(",", "\n").map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }.toSet()

    // ---------------- link-list engines (Telegram Engage, Sweep, Retry) ----------------

    /** Opens one post link and handles it. Returns Done, Fail, Skip or Already completed. */
    private suspend fun processLink(url: String, engine: String, b: Bridge): String {
        val id = UrlUtil.statusId(url) ?: return "Skip"
        val canon = UrlUtil.canonical(url)
        val author = UrlUtil.handleFromUrl(url) ?: ""
        if (Memory.isDone(id)) return "Already completed"
        if (author in blockedSet() || (author.isNotEmpty() && author == b.ownHandle)) return "Skip"
        if (!b.openPost(url)) {
            Memory.finish(id, canon, author, "failed", "", "post did not open / wrong id", engine)
            maybeFreshTab(b); bump("post did not open")
            return "Fail"
        }
        sleepStoppable(Random.nextLong(1200, 2500))
        guard(b)
        val p = b.getVisiblePosts().firstOrNull { it.id == id }
        if (p == null) {
            Memory.finish(id, canon, author, "failed", "", "main post not readable", engine)
            bump("main post not readable")
            return "Fail"
        }
        if (p.isAd || p.text.isBlank()) {
            Memory.finish(id, canon, p.author, "skip", "", "ad or empty", engine)
            return "Skip"
        }
        handlePost(p, engine, b, alreadyOpen = true)
        return when (Memory.actionOf(id)) { "reply" -> "Done"; "failed" -> "Fail"; else -> "Skip" }
    }

    /** Works through links top to bottom, logs a status per link, saves failed links to a folder. */
    private suspend fun processLinks(urls: List<String>, engine: String, b: Bridge, failedFolder: String?) {
        val failed = ArrayList<String>()
        try {
            var i = 0
            for (u in urls) {
                checkStop()
                i++
                val id = UrlUtil.statusId(u) ?: continue
                log("[Next] $i/${urls.size} $id")
                val st = processLink(u, engine, b)
                log("[$st] $id")
                if (st == "Fail") failed += UrlUtil.canonical(u)
                if (st != "Already completed") sleepStoppable(Random.nextLong(1500, 3000))
            }
        } finally {
            if (failed.isNotEmpty() && failedFolder != null) {
                Memory.addToFolder(failedFolder, failed)
                log("Saved ${failed.size} failed links to folder '$failedFolder'")
            }
        }
    }

    /** Telegram Engage: reads X links from the open Telegram group, oldest first, from a start post id. */
    private suspend fun tgRun(b: Bridge) {
        val st = b.pageState()
        if (!st.url.contains("telegram", true)) throw StopLoop("Open the Telegram group in the Browser tab first, then start Telegram Engage.")
        val group = b.pageTitle().trim().take(40)
        App.prefs.edit().putString("tg_group", group).apply()
        val scrolls = App.prefs.getInt("scrolls", 10)
        val startId = App.prefs.getString("tg_start", "")?.trim()?.toLongOrNull() ?: 0L
        val found = java.util.TreeMap<Long, String>() // X post ids grow with time, so this is oldest first
        for (n in 0..scrolls) {
            checkStop()
            for (u in b.telegramLinks()) {
                val id = UrlUtil.statusId(u)?.toLongOrNull() ?: continue
                found.putIfAbsent(id, UrlUtil.canonical(u))
            }
            if (n < scrolls) { b.telegramScrollUp(); sleepStoppable(Random.nextLong(1200, 2200)) }
        }
        val list = found.tailMap(startId, true).values.toList()
        log("Telegram '$group': ${list.size} X links found (from id $startId)")
        if (list.isEmpty()) throw StopLoop("No X links found. Check selectors/telegram.json on the live page.")
        val folder = group.ifBlank { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()) }
        processLinks(list, "tg", b, folder)
    }

    /** Telegram Sweep: works through a saved folder of links. */
    private suspend fun sweepRun(folder: String, b: Bridge) {
        val links = Memory.folderLinks(folder)
        if (links.isEmpty()) throw StopLoop("Folder '$folder' is empty or does not exist. Saved folders: ${Memory.folderNames().joinToString()}")
        log("Sweep '$folder': ${links.size} links")
        processLinks(links, "sweep", b, "$folder-failed")
    }

    /** Retry: every failed link gets one more try. A second failure is final. */
    private suspend fun retryRun(b: Bridge) {
        val rows = App.db.done().failedForRetry()
        if (rows.isEmpty()) throw StopLoop("No failed links to retry.")
        log("Retrying ${rows.size} failed links (once each)")
        for (r in rows) {
            checkStop()
            App.db.done().delete(r.postId)
            val st = processLink(r.url, "retry", b)
            log("[$st] ${r.postId}")
            if (st != "Already completed") sleepStoppable(Random.nextLong(1500, 3000))
        }
    }

    /** Link Collect: reads links and usernames from the open X page. Nothing is posted. */
    private suspend fun collectRun(b: Bridge) {
        val st = b.pageState()
        if (!st.url.contains("x.com") && !st.url.contains("twitter.com")) throw StopLoop("Open an X page (feed, search or list) in the Browser tab first.")
        val scrolls = App.prefs.getInt("scrolls", 10)
        val links = LinkedHashSet<String>()
        val users = LinkedHashSet<String>()
        for (n in 0..scrolls) {
            checkStop()
            guard(b)
            for (p in b.getVisiblePosts()) {
                if (p.isAd || p.isOwn) continue
                links += p.url
                if (p.author.isNotEmpty()) users += p.author
            }
            if (n < scrolls) b.scrollNext()
        }
        val folder = "collected-" + java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        Memory.addToFolder(folder, links)
        Memory.addPeople(users, "collect")
        log("Collected ${links.size} links and ${users.size} usernames. Folder '$folder'")
    }

    // ---------------- Follow / Unfollow ----------------

    /** Follow: usernames from the follow list that are still "new". Unfollow: usernames from the unfollow list. */
    private suspend fun followRun(b: Bridge, unfollow: Boolean) {
        val max = App.prefs.getInt("max_follows", 50)
        val word = if (unfollow) "unfollows" else "follows"
        val names: List<String> = if (unfollow)
            Memory.getRule("unfollow_list").split(Regex("[\\s,;]+")).map { it.trim().removePrefix("@").lowercase() }
                .filter { it.matches(Regex("[a-z0-9_]{1,15}")) }.distinct()
        else App.db.people().byStatus("new", 100000)
        if (names.isEmpty()) throw StopLoop(if (unfollow) "Add usernames to the unfollow list first." else "No new usernames. Import a follow list or run Link Collect first.")
        log("${if (unfollow) "Unfollow" else "Follow"}: ${names.size} usernames, max $max per run")
        var did = 0
        for (u in names) {
            checkStop()
            if (did >= max) throw StopLoop("Reached max $word for this run ($max).")
            b.load("https://x.com/$u")
            sleepStoppable(Random.nextLong(3500, 6000))
            guard(b)
            val ps = b.profileState(u)
            if (!unfollow) {
                when (ps) {
                    "following" -> { Memory.setPerson(u, "followed"); log("@$u already followed, skip"); continue }
                    "not_following" -> {
                        if (!b.followTap()) { bump("could not tap Follow on @$u"); continue }
                        sleepStoppable(2500)
                        if (b.profileState(u) == "following") {
                            Memory.setPerson(u, "followed"); did++; errorsInRow = 0
                            log("FOLLOWED @$u ($did/$max)")
                        } else { bump("follow not confirmed for @$u"); continue }
                    }
                    else -> { Memory.setPerson(u, "skipped"); log("@$u: no Follow button (missing, protected or wrong page), skip"); continue }
                }
            } else {
                when (ps) {
                    "not_following" -> { Memory.setPerson(u, "unfollowed"); log("@$u not followed, skip"); continue }
                    "following" -> {
                        if (!b.unfollowTap()) { bump("could not unfollow @$u"); continue }
                        sleepStoppable(2500)
                        if (b.profileState(u) == "not_following") {
                            Memory.setPerson(u, "unfollowed"); did++; errorsInRow = 0
                            log("UNFOLLOWED @$u ($did/$max)")
                        } else { bump("unfollow not confirmed for @$u"); continue }
                    }
                    else -> { log("@$u: no Follow/Unfollow button, skip"); continue }
                }
            }
            pace(did, 25_000, 50_000)
        }
        log("List finished. $did $word.")
    }
}
