package com.zbrowser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.zbrowser.agent.AgentLoop
import com.zbrowser.bridge.Bridge
import com.zbrowser.bridge.BrowserHolder
import com.zbrowser.data.Memory
import com.zbrowser.model.LlmManager
import kotlinx.coroutines.*

/** Programmatic UI (no XML) so every edit stays small. Tabs: Browser, Control, Projects, Log. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var content: FrameLayout
    private lateinit var browserPanel: LinearLayout
    private lateinit var controlPanel: ScrollView
    private lateinit var projectsPanel: LinearLayout
    private lateinit var historyPanel: LinearLayout
    private lateinit var historyText: TextView
    private lateinit var historyCards: TextView
    private var historyFilter = "all"
    private lateinit var logPanel: ScrollView
    private lateinit var logText: TextView
    private lateinit var projectsText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var urlBox: EditText
    private lateinit var webHolder: FrameLayout
    private var desktop = false

    private val fields = LinkedHashMap<String, EditText>()
    // fields stored as numbers / plain text in settings (everything else goes to the rules table)
    private val intDefaults = mapOf("max_replies" to 100, "custom_posts" to 50, "custom_minutes" to 20, "max_follows" to 50, "scrolls" to 10)
    private val prefStr = setOf("api_key", "tg_start", "folder_name")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val bridge by lazy { Bridge(BrowserHolder.get(this), applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content = FrameLayout(this)
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        buildBrowserPanel()
        buildControlPanel()
        buildProjectsPanel()
        buildHistoryPanel()
        buildLogPanel()

        val panels = listOf<Pair<String, View>>(
            "Browser" to browserPanel, "Control" to controlPanel, "Projects" to projectsPanel, "History" to historyPanel, "Log" to logPanel
        )
        panels.forEach { (name, v) ->
            content.addView(v, FrameLayout.LayoutParams(-1, -1))
            tabs.addView(Button(this).apply {
                text = name; textSize = 11f; isAllCaps = false
                setOnClickListener { show(v, panels.map { it.second }); if (v === projectsPanel) refreshProjects(); if (v === historyPanel) refreshHistory() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        show(browserPanel, panels.map { it.second })

        AgentLoop.listener = { line -> runOnUiThread { logText.append(line + "\n") } }
        logText.text = AgentLoop.history() + "\n"

        val wv = BrowserHolder.get(this)
        if (wv.url == null) wv.loadUrl("https://x.com/home")
        scope.launch { loadFields(); refreshStatus() }
    }

    private fun show(v: View, all: List<View>) { all.forEach { it.visibility = if (it === v) View.VISIBLE else View.GONE } }

    override fun onDestroy() {
        AgentLoop.listener = null
        (BrowserHolder.get(this).parent as? ViewGroup)?.removeView(BrowserHolder.get(this))
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- helpers ----------------
    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(10), 0, dp(2)); textSize = 13f }

    private fun field(key: String, hint: String, lines: Int = 1, password: Boolean = false): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            if (lines > 1) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = lines; gravity = android.view.Gravity.TOP }
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 13f
        }
        fields[key] = e
        return e
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    // ---------------- Browser tab (Step 1) ----------------
    private fun buildBrowserPanel() {
        browserPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlBox = EditText(this).apply { setSingleLine(); hint = "https://x.com/home"; textSize = 13f }
        bar.addView(urlBox, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Go") {
            var u = urlBox.text.toString().trim()
            if (u.isNotEmpty()) { if (!u.startsWith("http")) u = "https://$u"; BrowserHolder.get(this).loadUrl(u) }
        })
        browserPanel.addView(bar)

        webHolder = FrameLayout(this)
        val wv = BrowserHolder.get(this)
        (wv.parent as? ViewGroup)?.removeView(wv)
        webHolder.addView(wv, FrameLayout.LayoutParams(-1, -1))
        browserPanel.addView(webHolder, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Desktop/Mobile") {
            desktop = !desktop
            scope.launch { bridge.setViewMode(desktop); toast(if (desktop) "Desktop view" else "Mobile view") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("Detect (log only)") {
            scope.launch {
                val r = withContext(Dispatchers.Default) { try { AgentLoop.detectOnce(this@MainActivity) } catch (e: Exception) { "Error: ${e.message}" } }
                AgentLoop.log("DETECT\n$r")
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        browserPanel.addView(row)
    }

    // ---------------- Control tab ----------------
    private fun buildControlPanel() {
        controlPanel = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(24)) }
        controlPanel.addView(col)

        statusText = TextView(this).apply { textSize = 12f }
        col.addView(statusText)

        col.addView(label("Engines (log in to X in the Browser tab first)"))
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(btn("Feed engine") { startEngine("feed", "") }, LinearLayout.LayoutParams(0, -2, 1f))
        r1.addView(btn("Search engine") { startEngine("search", "") }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(r1)
        col.addView(field("list_url", "X list link (https://x.com/i/lists/...)"))
        col.addView(btn("List engine") { startEngine("list", fields["list_url"]!!.text.toString()) })

        col.addView(label("Telegram (open the group in the Browser tab first)"))
        col.addView(field("tg_start", "Start from X post id (optional: oldest link to include)").apply { inputType = InputType.TYPE_CLASS_NUMBER })
        col.addView(field("scrolls", "Scrolls to load history / collect (default 10)").apply { inputType = InputType.TYPE_CLASS_NUMBER })
        col.addView(btn("Telegram Engage") { startEngine("tg", "") })
        col.addView(field("folder_name", "Saved folder name (for Sweep)"))
        col.addView(btn("Telegram Sweep (run saved folder)") { startEngine("sweep", fields["folder_name"]!!.text.toString().trim()) })
        col.addView(btn("Link Collect (current X page)") { startEngine("collect", "") })
        col.addView(btn("Retry failed links (once each)") { startEngine("retry", "") })

        col.addView(label("Follow / Unfollow (log in to X first)"))
        col.addView(field("max_follows", "Max follows or unfollows per run (default 50)").apply { inputType = InputType.TYPE_CLASS_NUMBER })
        col.addView(field("unfollow_list", "Usernames to unfollow, one per line", 3))
        val rf = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rf.addView(btn("Follow engine") { startEngine("follow", "") }, LinearLayout.LayoutParams(0, -2, 1f))
        rf.addView(btn("Unfollow engine") { startEngine("unfollow", "") }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(rf)
        col.addView(btn("Export people list") { scope.launch { share(Memory.exportPeople()) } })

        col.addView(btn("STOP") { AgentLoop.requestStop() })

        col.addView(label("Pace and safety"))
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r2.addView(btn("FAST") { App.prefs.edit().putString("preset", "fast").apply(); toast("FAST preset"); scope.launch { refreshStatus() } }, LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(btn("SLOW (2.5x)") { App.prefs.edit().putString("preset", "slow").apply(); toast("SLOW preset"); scope.launch { refreshStatus() } }, LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(btn("CUSTOM") {
            App.prefs.edit().putString("preset", "custom").apply()
            scope.launch { saveFields(); val w = AgentLoop.paceWarning(); toast(w ?: "CUSTOM preset"); refreshStatus() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(r2)
        col.addView(field("custom_posts", "CUSTOM: number of posts (e.g. 50)").apply { inputType = InputType.TYPE_CLASS_NUMBER })
        col.addView(field("custom_minutes", "CUSTOM: in how many minutes (e.g. 20)").apply { inputType = InputType.TYPE_CLASS_NUMBER })
        col.addView(label("Reply tone"))
        val rt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Auto" to "auto", "Human" to "human", "Crypto" to "crypto").forEach { (t, v) ->
            rt.addView(btn(t) { App.prefs.edit().putString("tone", v).apply(); toast("Tone: $t"); scope.launch { refreshStatus() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(rt)
        col.addView(CheckBox(this).apply {
            text = "Like the post before replying"
            isChecked = App.prefs.getBoolean("like_first", true)
            setOnCheckedChangeListener { _, c -> App.prefs.edit().putBoolean("like_first", c).apply() }
        })
        col.addView(CheckBox(this).apply {
            text = "Keep screen on while running"
            isChecked = App.prefs.getBoolean("keep_on", false)
            BrowserHolder.get(this@MainActivity).keepScreenOn = isChecked
            setOnCheckedChangeListener { _, c ->
                App.prefs.edit().putBoolean("keep_on", c).apply()
                BrowserHolder.get(this@MainActivity).keepScreenOn = c
            }
        })
        col.addView(field("max_replies", "Max replies per run (default 100)").apply { inputType = InputType.TYPE_CLASS_NUMBER })

        col.addView(label("Reply rules (one place for the model)"))
        col.addView(field("rules", "Rules: tone, length, what to avoid", 3))
        col.addView(field("examples", "3-5 example comments, one per line", 4))
        col.addView(field("banned", "Banned words/phrases, comma separated", 2))
        col.addView(field("blocked", "Blocked accounts, comma separated", 2))
        col.addView(field("keywords", "Keyword filter (Feed/Search/List): only posts containing one of these, comma separated. Empty = all", 2))
        col.addView(field("topics", "Search queries, one per line (start a line with // to switch it off)", 3))
        col.addView(field("aliases", "Project aliases, one per line:  canonical = alias1, alias2", 3))
        col.addView(field("api_key", "Optional Gemini API key (fallback only)", 1, true))
        col.addView(btn("Save rules and settings") { scope.launch { saveFields(); toast("Saved") } })

        col.addView(label("Offline model (Step 3). File stays out of GitHub and the APK"))
        col.addView(btn("Import model file (.task)") { pick(1) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(progress)
        col.addView(btn("Load model") {
            scope.launch {
                statusText.text = "Loading model... (10-30 s)"
                val r = LlmManager.load(applicationContext)
                toast(r.fold({ "Loaded: $it" }, { "Load failed: ${it.message}" }))
                AgentLoop.log(r.fold({ "Model loaded: $it" }, { "Model load failed: ${it.message}" }))
                refreshStatus()
            }
        })
        col.addView(btn("Test prompt") {
            scope.launch {
                val r = LlmManager.generate("Reply with the single word: ready")
                val m = r.fold({ "Model says: $it" }, { "Failed: ${it.message}" })
                toast(m); AgentLoop.log(m)
            }
        })
        col.addView(btn("Test decision on sample post") {
            scope.launch {
                val d = com.zbrowser.agent.Decider.decide("Just shipped v2 of our open-source wallet. Faster swaps, lower fees. Feedback welcome!")
                AgentLoop.log("Decision: ${d.action} project='${d.project}' comment='${d.comment}' via ${d.source}")
                toast("See Log tab")
            }
        })

        col.addView(label("Import (Step 2)"))
        col.addView(btn("Import already-engaged links (TXT/CSV/JSON)") { pick(2) })
        col.addView(btn("Import follow list (TXT/CSV)") { pick(3) })
    }

    private fun pick(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        @Suppress("DEPRECATION") startActivityForResult(i, code)
    }

    @Deprecated("simple picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        scope.launch {
            when (requestCode) {
                1 -> {
                    toast("Copying model...")
                    val r = LlmManager.importModel(applicationContext, uri) { p -> runOnUiThread { progress.progress = p } }
                    toast(r.fold({ "Model imported. Tap Load model." }, { "Import failed: ${it.message}" }))
                    refreshStatus()
                }
                2 -> {
                    val name = uri.lastPathSegment ?: "file"
                    val r = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { Memory.importLinks(it, name) } }
                    toast("Imported ${r.added} links (${r.skipped} unreadable)"); refreshStatus()
                }
                3 -> {
                    val n = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { Memory.importPeople(it, "follow_list") } }
                    toast("Imported $n usernames"); refreshStatus()
                }
            }
        }
    }

    private fun startEngine(engine: String, arg: String) {
        scope.launch {
            saveFields()
            if (engine !in setOf("follow", "unfollow", "collect") && !LlmManager.isLoaded() && !com.zbrowser.model.ApiFallback.hasKey()) {
                toast("No model loaded and no API key. Posts will be skipped."); AgentLoop.log("Warning: no model/API, every post will be skipped.")
            }
            AgentLoop.start(applicationContext, engine, arg)
            toast("$engine engine started. See Log tab.")
        }
    }

    private suspend fun loadFields() {
        for ((k, e) in fields) {
            val v = when (k) {
                "list_url" -> ""
                in prefStr -> App.prefs.getString(k, "") ?: ""
                in intDefaults -> App.prefs.getInt(k, intDefaults[k]!!).toString()
                else -> Memory.getRule(k)
            }
            e.setText(v)
        }
    }

    private suspend fun saveFields() {
        for ((k, e) in fields) {
            val v = e.text.toString()
            when (k) {
                "list_url" -> {}
                in prefStr -> App.prefs.edit().putString(k, v.trim()).apply()
                in intDefaults -> App.prefs.edit().putInt(k, v.toIntOrNull() ?: intDefaults[k]!!).apply()
                else -> Memory.setRule(k, v)
            }
        }
    }

    private suspend fun refreshStatus() {
        val done = App.db.done().count()
        val replies = App.db.done().countAction("reply")
        val people = App.db.people().count()
        val file = LlmManager.importedModel(this)
        statusText.text = "Done posts: $done (replies $replies) | Follow list: $people\n" +
            "Model: ${if (LlmManager.isLoaded()) "loaded (${LlmManager.loadedName})" else if (file != null) "imported, not loaded" else "none"}\n" +
            "Preset: ${App.prefs.getString("preset", "fast")} | Tone: ${App.prefs.getString("tone", "auto")} | Own handle: @${App.prefs.getString("own_handle", "?")}"
    }

    // ---------------- Projects tab (Step 6) ----------------
    private fun buildProjectsPanel() {
        projectsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        projectsPanel.addView(btn("Refresh") { refreshProjects() })
        val sv = ScrollView(this)
        projectsText = TextView(this).apply { textSize = 14f }
        sv.addView(projectsText)
        projectsPanel.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun refreshProjects() {
        scope.launch {
            val list = App.db.projects().all()
            projectsText.text = if (list.isEmpty()) "No projects yet." else
                list.joinToString("\n") { "${it.name}  —  ${it.commentCount} comments" }
        }
    }

    // ---------------- History tab ----------------
    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "Export"))
    }

    private fun buildHistoryPanel() {
        historyPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        historyCards = TextView(this).apply { textSize = 12f }
        historyPanel.addView(historyCards)
        fun row(items: List<Pair<String, String>>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { (t, f) -> r.addView(btn(t) { historyFilter = f; refreshHistory() }, LinearLayout.LayoutParams(0, -2, 1f)) }
            historyPanel.addView(r)
        }
        row(listOf("All" to "all", "Replies" to "reply", "Skipped" to "skip"))
        row(listOf("Failed" to "failed", "Stuck" to "stuck", "No reply" to "noreply"))
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r2.addView(btn("Export failed links") { scope.launch { share(Memory.exportFailed()) } }, LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(btn("Retry failed") { startEngine("retry", "") }, LinearLayout.LayoutParams(0, -2, 1f))
        historyPanel.addView(r2)
        val sv = ScrollView(this)
        historyText = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        sv.addView(historyText)
        historyPanel.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun refreshHistory() {
        scope.launch {
            val d = App.db.done()
            val stuckBefore = System.currentTimeMillis() - 10 * 60_000L
            historyCards.text = "FAILED ${d.countAction("failed")}   NO GET REPLY ${d.countNoReply()}   STUCK ${d.countStuck(stuckBefore)}\nFilter: $historyFilter"
            val rows = when (historyFilter) {
                "reply" -> d.historyBy("reply", 200)
                "skip" -> d.historyBy("skip", 200)
                "failed" -> d.historyBy("failed", 200)
                "stuck" -> d.historyStuck(stuckBefore, 200)
                "noreply" -> d.historyNoReply(200)
                else -> d.historyAll(200)
            }
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
            historyText.text = if (rows.isEmpty()) "Nothing here." else rows.joinToString("\n\n") {
                "${fmt.format(java.util.Date(it.timestamp))} | ${it.engine} | ${it.action.uppercase()}\n@${it.author} ${it.postId}" +
                    (if (it.project.isNotEmpty()) "\nproject: ${it.project}" else "") +
                    (if (it.comment.isNotEmpty()) "\n${it.comment}" else "")
            }
        }
    }

    // ---------------- Log tab ----------------
    private fun buildLogPanel() {
        logPanel = ScrollView(this)
        logText = TextView(this).apply { textSize = 11f; setPadding(dp(8), dp(8), dp(8), dp(8)); setTextIsSelectable(true) }
        logPanel.addView(logText)
    }
}
