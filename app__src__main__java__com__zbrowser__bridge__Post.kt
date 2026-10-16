package com.zbrowser.bridge

import org.json.JSONArray
import org.json.JSONObject

/** One post read from the page. */
data class Post(
    val id: String,          // numeric status id
    val url: String,
    val author: String,      // handle without @, lowercase
    val text: String,
    val isAd: Boolean,
    val isReply: Boolean,
    val isOwn: Boolean,
    val alreadyReplied: Boolean // an own article directly follows it on the page
) {
    companion object {
        fun parseList(json: String): List<Post> {
            val out = ArrayList<Post>()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.getJSONObject(i)
                val url = o.optString("url")
                val id = UrlUtil.statusId(url) ?: continue
                out += Post(
                    id = id,
                    url = UrlUtil.canonical(url, o.optString("author")),
                    author = o.optString("author").lowercase().removePrefix("@"),
                    text = o.optString("text"),
                    isAd = o.optBoolean("isAd"),
                    isReply = o.optBoolean("isReply"),
                    isOwn = o.optBoolean("isOwn"),
                    alreadyReplied = o.optBoolean("alreadyReplied")
                )
            }
            return out
        }
    }
}

object UrlUtil {
    private val STATUS = Regex("""(?:x|twitter)\.com/([A-Za-z0-9_]+)/status(?:es)?/(\d+)""", RegexOption.IGNORE_CASE)
    private val STATUS_REL = Regex("""/([A-Za-z0-9_]+)/status(?:es)?/(\d+)""")

    /** Numeric status id from any x.com / twitter.com link, query and fragment ignored. */
    fun statusId(url: String): String? =
        (STATUS.find(url) ?: STATUS_REL.find(url))?.groupValues?.get(2)

    fun handleFromUrl(url: String): String? =
        (STATUS.find(url) ?: STATUS_REL.find(url))?.groupValues?.get(1)?.lowercase()

    fun canonical(url: String, author: String = ""): String {
        val id = statusId(url) ?: return url
        val h = handleFromUrl(url) ?: author.ifBlank { "i" }
        return "https://x.com/$h/status/$id"
    }
}
