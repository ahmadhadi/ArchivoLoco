package com.ahmadhadi.archivoloco

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class IgException(message: String) : IOException(message)
class SessionExpiredException :
    IOException("Instagram says you're not logged in. Log out and log in again in ArchivoLoco.")

/**
 * Port of instagram_archiver.client.InstagramClient.
 *
 * Instagram API calls go through [WebBridge] (a real browser on www.instagram.com, same session you
 * logged in with). Media bytes come from Meta's CDNs through [cdn], a plain HTTP client that sends
 * no cookies and can only reach the CDN/Instagram allowlist.
 */
class IgClient(context: Context, private val bridge: WebBridge) {

    val cdn: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("user-agent", userAgent).build())
        }
        .addNetworkInterceptor(HostPolicy.enforce)
        .build()

    private val userAgent: String =
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0 (Linux; Android)")

    fun csrfToken(): String? =
        CookieManager.getInstance().getCookie(IgConstants.BASE)
            ?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("csrftoken=") }
            ?.substringAfter("=")

    /** Turns any instagram.com URL into a same-origin path on www.instagram.com. */
    private fun pathOf(url: String, params: Map<String, String> = emptyMap()): String {
        val u = url.toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        require(HostPolicy.isInstagram(u.host)) { "Not an Instagram URL" }
        return u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
    }

    private fun snippet(body: String): String {
        val t = body.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        if (t.startsWith("<")) return " (Instagram sent a web page instead of data)"
        return " - Instagram said: " + t.take(160)
    }

    private fun check(r: BridgeResponse, path: String) {
        when {
            r.status == 302 -> throw IgException(
                "Instagram refused the request (redirected). Log out and in again, then retry.",
            )
            r.status == -1 -> throw IgException("Network error: ${r.body}")
            r.status == 401 || "login_required" in r.body -> throw SessionExpiredException()
            r.status !in 200..299 -> throw IgException(
                "HTTP ${r.status} for ${path.substringBefore('?')}${snippet(r.body)}",
            )
        }
    }

    /** Kept for parity with the Python flow; opening the bridge loads the Instagram origin. */
    suspend fun getPage(@Suppress("UNUSED_PARAMETER") url: String) = bridge.open()

    suspend fun getJson(
        url: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val path = pathOf(url, params)
        val referrer = headers["referer"] ?: "${IgConstants.BASE}/"
        val r = bridge.fetch("GET", path, referrer = referrer, headers = headers - "referer")
        check(r, path)
        if (r.body.trimStart().startsWith("<")) {
            throw IgException("Got a web page instead of data for ${path.substringBefore('?')} (blocked or logged out)")
        }
        return JSONObject(r.body)
    }

    /** Port of InstagramClient.graphql_query. Returns the `data` object or null. */
    suspend fun graphql(variables: JSONObject, docId: String, referrer: String = "${IgConstants.BASE}/"): JSONObject? {
        val r = bridge.fetch(
            "POST", "/graphql/query",
            form = mapOf("doc_id" to docId, "variables" to variables.toString()),
            referrer = referrer,
        )
        if (r.status == 302) check(r, "/graphql/query")
        if (r.status !in 200..299 || r.body.trimStart().startsWith("<")) return null
        val json = JSONObject(r.body)
        if (json.optString("status") != "ok") return null
        return json.optJSONObject("data")
    }

    // ---- Endpoint helpers --------------------------------------------------------------------

    suspend fun webProfileInfo(username: String): JSONObject? =
        getJson(
            "${IgConstants.BASE}/api/v1/users/web_profile_info/",
            mapOf("username" to username),
        ).optJSONObject("data")?.optJSONObject("user")

    suspend fun highlightIds(userId: String): List<String> {
        val tray = getJson("${IgConstants.BASE}/api/v1/highlights/$userId/highlights_tray/")
            .optJSONArray("tray") ?: return emptyList()
        return (0 until tray.length()).map { tray.getJSONObject(it).getString("id").substringAfterLast(':') }
    }

    /** Port of InstagramClient.reel_page_gallery. Returns the connection (edges + page_info). */
    suspend fun reelPageGallery(
        reelIds: List<String>,
        after: String?,
        isHighlight: Boolean,
        first: Int = 5,
    ): JSONObject? {
        if (reelIds.isEmpty()) return null
        val vars = JSONObject()
            .put("first", first)
            .put("initial_reel_id", reelIds.first())
            .put("last", JSONObject.NULL)
            .put("reel_ids", JSONArray(reelIds))
        val docId = if (after == null) {
            IgConstants.REEL_PAGE_GALLERY_DOC_ID
        } else {
            vars.put("after", after).put("before", JSONObject.NULL).put("is_highlight", isHighlight)
            IgConstants.REEL_PAGE_GALLERY_PAGINATION_DOC_ID
        }
        val data = graphql(vars, docId) ?: return null
        data.optJSONObject("xdt_api__v1__feed__reels_media")?.let {
            if (it.has("edges") && it.has("page_info")) return it
        }
        // The wrapper key is volatile; fall back to the first object that looks like a connection.
        val keys = data.keys()
        while (keys.hasNext()) {
            val v = data.opt(keys.next())
            if (v is JSONObject && v.has("edges") && v.has("page_info")) return v
        }
        return null
    }

    suspend fun timelinePage(username: String, after: String?): JSONObject? {
        val inner = JSONObject()
            .put("count", 12)
            .put("include_reel_media_seen_timestamp", true)
            .put("include_relationship_info", true)
            .put("latest_besties_reel_media", true)
            .put("latest_reel_media", true)
        val vars = JSONObject()
            .put("data", inner)
            .put("username", username)
            .put("__relay_internal__pv__PolarisIsLoggedInrelayprovider", true)
            .put("__relay_internal__pv__PolarisShareSheetV3relayprovider", true)
        if (after != null) {
            vars.put("after", after).put("before", JSONObject.NULL)
                .put("first", 12).put("last", JSONObject.NULL)
        }
        return graphql(vars, IgConstants.TIMELINE_DOC_ID, referrer = "${IgConstants.BASE}/$username/")
            ?.optJSONObject("xdt_api__v1__feed__user_timeline_graphql_connection")
    }

    /** Returns the media info JSON, or null if Instagram didn't return usable data. */
    suspend fun mediaInfo(pk: String): JSONObject? {
        val info = getJson("${IgConstants.BASE}/api/v1/media/$pk/info/")
        val items = info.optJSONArray("items")
        if (items == null || items.length() == 0) return null
        return info
    }

    /** Port of InstagramClient.save_comments (fetch part). */
    suspend fun comments(pk: String, shortcode: String?, withReplies: Boolean): JSONObject {
        val url = "${IgConstants.BASE}/api/v1/media/$pk/comments/"
        val headers = if (shortcode != null) mapOf("referer" to "${IgConstants.BASE}/p/$shortcode/") else emptyMap()
        var page = getJson(url, mapOf("can_support_threading" to "true", "permalink_enabled" to "false"), headers)
        val top = page
        val all = top.optJSONArray("comments") ?: JSONArray()
        while (page.optBoolean("can_view_more_preview_comments") && page.optString("next_min_id").isNotEmpty()) {
            page = try {
                getJson(
                    url,
                    mapOf(
                        "can_support_threading" to "true",
                        "min_id" to page.getString("next_min_id"),
                        "sort_order" to "popular",
                    ),
                    headers,
                )
            } catch (e: IOException) {
                break
            }
            page.optJSONArray("comments")?.let { arr -> for (i in 0 until arr.length()) all.put(arr.get(i)) }
        }
        top.put("comments", all)
        if (withReplies) {
            for (i in 0 until all.length()) {
                val c = all.optJSONObject(i) ?: continue
                if (c.optInt("child_comment_count") <= 0) continue
                val cpk = c.optString("pk").ifEmpty { c.optString("id") }
                if (cpk.isEmpty()) continue
                childComments(pk, cpk, headers)?.let { c.put("child_comments", it) }
            }
        }
        return top
    }

    private suspend fun childComments(pk: String, commentPk: String, headers: Map<String, String>): JSONArray? {
        val url = "${IgConstants.BASE}/api/v1/media/$pk/comments/$commentPk/child_comments/"
        var minId = ""
        val replies = JSONArray()
        while (true) {
            val page = try {
                getJson(
                    url,
                    mapOf("is_chronological" to "true", "min_id" to minId, "paging_direction" to "view_more"),
                    headers,
                )
            } catch (e: IOException) {
                return if (replies.length() > 0) replies else null
            }
            page.optJSONArray("child_comments")?.let { arr -> for (i in 0 until arr.length()) replies.put(arr.get(i)) }
            val next = page.optString("next_min_id")
            if (!page.optBoolean("has_more_head_child_comments") || next.isEmpty()) break
            minId = next
        }
        return replies
    }

    /** Saved posts feed. Unlike the Python tool, this paginates via next_max_id. */
    suspend fun savedPage(maxId: String?): JSONObject =
        getJson(
            "${IgConstants.BASE}/api/v1/feed/saved/posts/",
            if (maxId != null) mapOf("max_id" to maxId) else emptyMap(),
        )

    suspend fun unsave(mediaPk: String): Boolean =
        bridge.fetch("POST", "/web/save/$mediaPk/unsave/", form = emptyMap()).status in 200..299
}
