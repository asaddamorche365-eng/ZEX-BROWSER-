package com.zbrowser.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.random.Random

/** Single WebView that survives Activity recreation (built with application context). */
object BrowserHolder {
    private var wv: WebView? = null
    fun get(ctx: Context): WebView = wv ?: create(ctx.applicationContext).also { wv = it }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context): WebView {
        val w = WebView(ctx)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = true
        w.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = WebViewClient()
        return w
    }
}

/** The one @JavascriptInterface object. Pages can only send small log/notes to the app. */
class JsHook(private val onNote: (String) -> Unit) {
    @JavascriptInterface
    fun note(s: String) = onNote(s.take(300))
}

class PageState(val login: Boolean, val error: String, val url: String, val articles: Int)
class ThreadInfo(val pageId: String, val mainFound: Boolean, val ownReplyVisible: Boolean, val ownReplyId: String = "")

/**
 * Native bridge. Each function wraps one JS snippet owned by the app.
 * All WebView calls run on the main thread; callers can be on any coroutine.
 */
class Bridge(private val webView: WebView, private val ctx: Context) {

    private val sel: JSONObject by lazy {
        JSONObject(ctx.assets.open("selectors/x.json").bufferedReader().use { it.readText() })
    }

    private val tsel: JSONObject by lazy {
        JSONObject(ctx.assets.open("selectors/telegram.json").bufferedReader().use { it.readText() })
    }

    @Volatile var ownHandle: String = ""
        private set

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c ->
            webView.evaluateJavascript(js) { raw -> c.resume(unquote(raw)) }
        }
    }

    /** evaluateJavascript returns a JSON-encoded string; unwrap it. */
    private fun unquote(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try { org.json.JSONTokener(raw).nextValue() as? String ?: raw } catch (e: Exception) { raw }
    }

    suspend fun load(url: String) = withContext(Dispatchers.Main) { webView.loadUrl(url) }

    suspend fun currentUrl(): String = withContext(Dispatchers.Main) { webView.url ?: "" }

    /** Reads the logged-in handle from the app bar profile link and stores it. */
    suspend fun detectOwnHandle(): String {
        val saved = com.zbrowser.App.prefs.getString("own_handle", "") ?: ""
        val r = JSONObject(eval(PageScripts.ownHandle(sel)).ifBlank { "{}" })
        val h = r.optString("handle")
        if (h.isNotBlank()) {
            ownHandle = h
            com.zbrowser.App.prefs.edit().putString("own_handle", h).apply()
        } else if (saved.isNotBlank()) {
            ownHandle = saved
        }
        return ownHandle
    }

    suspend fun pageState(): PageState {
        val o = JSONObject(eval(PageScripts.pageState(sel)).ifBlank { "{}" })
        return PageState(o.optBoolean("login"), o.optString("error"), o.optString("url"), o.optInt("articles"))
    }

    suspend fun getVisiblePosts(): List<Post> {
        val json = eval(PageScripts.visiblePosts(sel, ownHandle))
        if (json.isBlank()) return emptyList()
        return Post.parseList(json)
    }

    suspend fun threadInfo(): ThreadInfo {
        val o = JSONObject(eval(PageScripts.threadInfo(sel, ownHandle)).ifBlank { "{}" })
        return ThreadInfo(o.optString("pageId"), o.optBoolean("mainFound"), o.optBoolean("ownReplyVisible"), o.optString("ownId"))
    }

    /** Scrolls by a randomised amount, then waits for new posts to render. */
    suspend fun scrollNext() {
        val px = Random.nextInt(450, 900)
        eval(PageScripts.scrollBy(px))
        delay(Random.nextLong(1200, 2600))
    }

    /** Loads a post so a reply can be written, and waits until it is rendered. */
    suspend fun openPost(url: String, timeoutMs: Long = 15000): Boolean {
        load(url)
        val wantId = UrlUtil.statusId(url)
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            delay(700)
            val ti = threadInfo()
            if (ti.pageId == wantId && ti.mainFound) return true
        }
        return false
    }

    /** Real touch at the element's centre (MotionEvent), not a JS click. */
    suspend fun tap(selector: String): Boolean {
        val o = JSONObject(eval(PageScripts.rectOf(selector)).ifBlank { "{}" })
        if (!o.optBoolean("found")) return false
        delay(250)
        val o2 = JSONObject(eval(PageScripts.rectOf(selector)).ifBlank { "{}" })
        if (!o2.optBoolean("found")) return false
        return withContext(Dispatchers.Main) {
            val ratio = webView.width.toFloat() / o2.getDouble("iw").toFloat()
            val x = o2.getDouble("cx").toFloat() * ratio + Random.nextInt(-3, 4)
            val y = o2.getDouble("cy").toFloat() * ratio + Random.nextInt(-3, 4)
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
            webView.dispatchTouchEvent(down)
            webView.dispatchTouchEvent(up)
            down.recycle(); up.recycle()
            true
        }
    }

    /** Fills the reply box and submits. Returns false if anything is missing. */
    suspend fun typeAndSend(text: String): Boolean {
        val box = sel.getString("replyBox")
        if (!tap(box)) return false
        delay(600)
        val r = JSONObject(eval(PageScripts.insertText(box, text)).ifBlank { "{}" })
        if (!r.optBoolean("ok")) return false
        delay(Random.nextLong(700, 1500))
        val typed = JSONObject(eval(PageScripts.boxText(box)).ifBlank { "{}" }).optString("t")
        if (!typed.contains(text.take(10))) return false
        return tap(sel.getString("sendInline")) || tap(sel.getString("sendModal"))
    }

    suspend fun like(): Boolean = tap(sel.getString("likeButton"))

    // ---- profile actions (Follow / Unfollow engines) ----
    suspend fun profileState(expected: String): String =
        JSONObject(eval(PageScripts.profileState(sel, expected)).ifBlank { "{}" }).optString("s", "none")

    suspend fun followTap(): Boolean = tap(sel.getString("followButton"))

    suspend fun unfollowTap(): Boolean {
        if (!tap(sel.getString("unfollowButton"))) return false
        delay(Random.nextLong(900, 1600))
        return tap(sel.getString("unfollowConfirm"))
    }

    // ---- Telegram Web reading ----
    suspend fun pageTitle(): String = withContext(Dispatchers.Main) { webView.title ?: "" }

    suspend fun telegramLinks(): List<String> {
        val raw = eval(PageScripts.telegramLinks(tsel))
        if (raw.isBlank()) return emptyList()
        val arr = org.json.JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    suspend fun telegramScrollUp() {
        eval(PageScripts.scrollContainerUp(tsel.getString("scroll"), Random.nextInt(700, 1200)))
    }

    /** Drops the current page and history so the next load starts clean (stuck-page recovery). */
    suspend fun freshTab() = withContext(Dispatchers.Main) {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
    }

    /** Switches user agent and viewport, then reloads. */
    suspend fun setViewMode(desktop: Boolean) = withContext(Dispatchers.Main) {
        val s = webView.settings
        val mobileUa = WebSettings.getDefaultUserAgent(ctx)
        val desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        s.userAgentString = if (desktop) desktopUa else mobileUa
        s.useWideViewPort = desktop
        s.loadWithOverviewMode = desktop
        webView.reload()
    }
}
