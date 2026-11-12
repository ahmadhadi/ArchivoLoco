package com.ahmadhadi.archivoloco

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class IgException(message: String) : IOException(message)
class SessionExpiredException :
    IOException("Instagram redirected to login. Your session has expired - log in again.")

/**
 * Bridges the WebView's cookie store (where the login happened) into OkHttp, and writes any
 * cookies Instagram rotates (e.g. csrftoken) back so the WebView and the client stay in sync.
 */
private class WebViewCookieJar : CookieJar {
    private val cm = CookieManager.getInstance()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.endsWith("instagram.com")) return emptyList()
        val raw = cm.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@mapNotNull null
            Cookie.Builder()
                .name(part.substring(0, i).trim())
                .value(part.substring(i + 1).trim())
                .domain(url.host)
                .build()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.host.endsWith("instagram.com")) return
        cookies.forEach { cm.setCookie("https://www.instagram.com", it.toString()) }
        cm.flush()
    }
}

/** Port of instagram_archiver.client.InstagramClient (HTTP layer only). */
class IgClient {
    private val base = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder().header("user-agent", IgConstants.USER_AGENT)
            if (req.url.host.endsWith("instagram.com")) {
                IgConstants.SHARED_HEADERS.forEach { (k, v) ->
                    if (req.header(k) == null) b.header(k, v)
                }
                csrfToken()?.let { b.header("x-csrftoken", it) }
            }
            chain.proceed(b.build())
        }
        .build()

    /** API client: redirects are a sign of an expired session, so surface them. */
    private val api = base.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** CDN client for media bytes. */
    val cdn: OkHttpClient = base

    fun csrfToken(): String? =
        CookieManager.getInstance().getCookie(IgConstants.BASE)
            ?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("csrftoken=") }
            ?.substringAfter("=")

    private suspend fun execute(client: OkHttpClient, request: Request): Response =
        withContext(Dispatchers.IO) { client.newCall(request).execute() }

    private fun checkRedirect(r: Response) {
        if (r.code in 300..399) {
            val loc = r.header("location").orEmpty()
            r.close()
            if ("login" in loc || "challenge" in loc) throw SessionExpiredException()
            throw IgException("Unexpected redirect to $loc")
        }
    }

    suspend fun getPage(url: String) {
        val b = Request.Builder().url(url)
        IgConstants.PAGE_FETCH_HEADERS.forEach { (k, v) -> b.header(k, v) }
        // Follow redirects for pages; we only want the cookies primed.
        execute(base, b.build()).close()
    }

    suspend fun getJson(
        url: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val u = url.toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val b = Request.Builder().url(u)
        IgConstants.API_HEADERS.forEach { (k, v) -> b.header(k, v) }
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = execute(api, b.build())
        checkRedirect(r)
        r.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IgException("HTTP ${it.code} for ${u.encodedPath}")
            if (body.trimStart().startsWith("<")) {
                throw IgException("Got HTML instead of JSON for ${u.encodedPath} (blocked or logged out)")
            }
            return JSONObject(body)
        }
    }

    suspend fun post(url: String, form: Map<String, String> = emptyMap()): Response {
        val fb = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val b = Request.Builder().url(url).post(fb)
        IgConstants.API_HEADERS.forEach { (k, v) -> b.header(k, v) }
        return execute(api, b.build())
    }

    /** Port of InstagramClient.graphql_query. Returns the `data` object or null. */
    suspend fun graphql(variables: JSONObject, docId: String): JSONObject? {
        val r = post(
            "${IgConstants.BASE}/graphql/query",
            mapOf("doc_id" to docId, "variables" to variables.toString()),
        )
        checkRedirect(r)
        r.use {
            if (!it.isSuccessful) return null
            val text = it.body?.string().orEmpty()
            if (text.trimStart().startsWith("<")) return null
            val json = JSONObject(text)
            if (json.optString("status") != "ok") return null
            return json.optJSONObject("data")
        }
    }

    // ---- Endpoint helpers --------------------------------------------------------------------

    suspend fun webProfileInfo(username: String): JSONObject? =
        getJson(
            "https://i.instagram.com/api/v1/users/web_profile_info/",
            mapOf("username" to username),
        ).optJSONObject("data")?.optJSONObject("user")

    suspend fun highlightIds(userId: String): List<String> {
        val tray = getJson("https://i.instagram.com/api/v1/highlights/$userId/highlights_tray/")
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
        return graphql(vars, IgConstants.TIMELINE_DOC_ID)
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
        post("${IgConstants.BASE}/web/save/$mediaPk/unsave/").use { it.isSuccessful }
}
