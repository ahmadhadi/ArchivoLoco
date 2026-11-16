package com.ahmadhadi.archivoloco

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/**
 * Logged-out access to public profiles, ported from instaloader 4.16+ (Nov 2026).
 *
 * Instagram now refuses the old web_profile_info endpoint, but it still renders a public profile
 * page with the profile and its first posts embedded as JSON, and the logged-out web app pages
 * through the rest with anonymous `api/graphql` queries. This client does exactly that.
 *
 * It never sends your Instagram login: it keeps its own throwaway, in-memory cookies, and the
 * shared HostPolicy allowlist applies to every request.
 */
class GuestClient {
    companion object {
        // Same plain desktop identity instaloader uses. No client-hint headers are sent, so there's
        // nothing for Instagram to find inconsistent.
        const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/142.0.0.0 Safari/537.36"
        const val PROFILE_POSTS_DOC_ID = "28904005159232689" // PolarisLoggedOutDesktopWWWProfilePostsTabContentQuery
        const val POST_DOC_ID = "27128499623469141" // media shortcode web_info
        const val REELS_DOC_ID = "7845543455542541" // PolarisProfileReelsTabContentQuery_connection

        fun reelsVariables(userPk: String, after: String?): JSONObject {
            val vars = JSONObject().put(
                "data",
                JSONObject().put("page_size", 12).put("include_feed_video", true).put("target_user_id", userPk),
            )
            if (after != null) {
                vars.put("after", after).put("before", JSONObject.NULL).put("first", 12).put("last", JSONObject.NULL)
            }
            return vars
        }
        val SCRIPT_JSON = Regex("""<script type="application/json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }

    /** In-memory, guest-only cookie store (csrftoken, mid...). Discarded when the job ends. */
    private val jar = object : CookieJar {
        private val cookies = LinkedHashMap<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (!HostPolicy.isInstagram(url.host)) return
            synchronized(this) { cookies.forEach { this.cookies[it.name] = it } }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            if (!HostPolicy.isInstagram(url.host)) emptyList()
            else synchronized(this) { cookies.values.filter { it.value.isNotEmpty() } }
        fun get(name: String) = synchronized(this) { cookies[name]?.value }
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addNetworkInterceptor(HostPolicy.enforce)
        .build()

    private var lsd: String? = null
    private var av: String? = null

    private suspend fun execute(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            if (r.code in 300..399) {
                val loc = r.header("location").orEmpty()
                if ("login" in loc || "challenge" in loc) {
                    throw IgException(
                        "Instagram is asking for a login right now (often a temporary limit on " +
                            "logged-out visits from this network). Try again later or log in.",
                    )
                }
                throw IgException("Instagram redirected to $loc")
            }
            r.code to (r.body?.string().orEmpty())
        }
    }

    private fun pageRequest(path: String) = Request.Builder()
        .url("${IgConstants.BASE}/$path")
        .header("user-agent", UA)
        .header("accept", "text/html")
        .header("accept-language", "en-US,en;q=0.8")
        // Instagram only embeds the data when the request looks like a browser page navigation.
        .header("sec-fetch-dest", "document")
        .header("sec-fetch-mode", "navigate")
        .header("sec-fetch-site", "none")
        .build()

    /** Data objects of every GraphQL result embedded in a www.instagram.com page, in page order. */
    private fun embeddedData(html: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    val data = v.optJSONObject("__bbox")?.optJSONObject("result")?.optJSONObject("data")
                    if (data != null) out += data
                    val keys = v.keys()
                    while (keys.hasNext()) walk(v.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        SCRIPT_JSON.findAll(html).forEach { m ->
            runCatching { JSONTokener(m.groupValues[1]).nextValue() }.getOrNull()?.let(::walk)
        }
        return out
    }

    /**
     * Resolves a profile from its public page (instaloader's Profile._from_page). Returns the
     * embedded user object: "pk" is the user ID, "id" is the page ID used for post queries, and
     * "polaris_ordered_timeline_connection" holds the first posts.
     */
    suspend fun profile(username: String): JSONObject {
        val (code, html) = execute(pageRequest("${username.lowercase()}/"))
        if (code == 404) throw IgException("Instagram has no public profile @$username (404).")
        if (code != 200) throw IgException("HTTP $code loading the profile page")
        val user = JSONObject()
        for (data in embeddedData(html)) {
            val part = data.optJSONObject("xig_user_by_username") ?: continue
            val keys = part.keys()
            while (keys.hasNext()) { val k = keys.next(); user.put(k, part.get(k)) }
        }
        if (user.optString("pk").isEmpty()) {
            throw IgException(
                "Couldn't read @$username's profile page (Instagram may be limiting logged-out visits; try later).",
            )
        }
        return user
    }

    /** Tokens the logged-out web app sends with api/graphql queries (instaloader _web_api_tokens). */
    private suspend fun ensureTokens() {
        if (lsd != null) return
        val (code, html) = execute(pageRequest(""))
        if (code != 200) throw IgException("HTTP $code loading instagram.com")
        lsd = Regex(""""LSD",\[\],\{[^{}]*"token":"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: throw IgException("Instagram's start page didn't include the expected token (lsd).")
        av = Regex(""""IG_USER_EIMU":"?(\d+)""").find(html)?.groupValues?.get(1)
    }

    private suspend fun webGraphql(docId: String, variables: JSONObject, referer: String): JSONObject {
        ensureTokens()
        val form = FormBody.Builder()
            .add("variables", variables.toString())
            .add("doc_id", docId)
            .add("server_timestamps", "true")
            .add("lsd", lsd!!)
            .apply { av?.let { add("av", it) } }
            .build()
        val req = Request.Builder()
            .url("${IgConstants.BASE}/api/graphql")
            .post(form)
            .header("user-agent", UA)
            .header("accept", "*/*")
            .header("accept-language", "en-US,en;q=0.8")
            .header("referer", referer)
            .header("x-ig-app-id", "936619743392459")
            .header("x-csrftoken", jar.get("csrftoken").orEmpty())
            .header("x-fb-lsd", lsd!!)
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .build()
        val (code, body) = execute(req)
        if (code == 429) throw IgException("Instagram is rate-limiting (HTTP 429). Wait a while and retry.")
        if (code != 200) throw IgException("HTTP $code from Instagram's web API")
        // api/graphql may prefix the JSON with a "for (;;);" guard.
        val json = body.removePrefix("for (;;);").trim()
        if (!json.startsWith("{")) throw IgException("Instagram sent a web page instead of data")
        return JSONObject(json)
    }

    /** Next page of a public profile's posts, by the page "id" (not the user pk). */
    suspend fun postsPage(pageId: String, username: String, after: String): JSONObject? {
        val vars = JSONObject()
            .put("id", pageId)
            .put("__relay_internal__pv__PolarisSEOIsCrawlerrelayprovider", false)
            .put("after", after).put("before", JSONObject.NULL)
            .put("first", 12).put("last", JSONObject.NULL)
        val resp = webGraphql(PROFILE_POSTS_DOC_ID, vars, "${IgConstants.BASE}/$username/")
        return resp.optJSONObject("data")?.optJSONObject("node")?.optJSONObject("polaris_ordered_timeline_connection")
            ?: throw IgException("Instagram didn't return more posts: ${describe(resp)}")
    }

    /** Short, log-friendly description of an unexpected GraphQL response. */
    private fun describe(resp: JSONObject): String {
        resp.optJSONArray("errors")?.optJSONObject(0)?.let { e ->
            return (e.optString("summary").ifEmpty { e.optString("message") }).take(140).ifEmpty { "error" }
        }
        resp.optString("message").takeIf { it.isNotEmpty() }?.let { return it.take(140) }
        return "keys=" + resp.keys().asSequence().toList().joinToString(",").take(140)
    }

    /**
     * The classic graphql/query endpoint, as instaloader uses for reels
     * (PolarisProfileReelsTabContentQuery).
     */
    private suspend fun graphqlQuery(docId: String, variables: JSONObject, referer: String): JSONObject {
        ensureTokens() // also obtains the csrftoken cookie
        val form = FormBody.Builder()
            .add("variables", variables.toString())
            .add("doc_id", docId)
            .add("server_timestamps", "true")
            .build()
        val req = Request.Builder()
            .url("${IgConstants.BASE}/graphql/query")
            .post(form)
            .header("user-agent", UA)
            .header("accept", "*/*")
            .header("accept-language", "en-US,en;q=0.8")
            .header("referer", referer)
            .header("x-ig-app-id", "936619743392459")
            .header("x-csrftoken", jar.get("csrftoken").orEmpty())
            .build()
        val (code, body) = execute(req)
        if (code == 429) throw IgException("Instagram is rate-limiting (HTTP 429). Wait a while and retry.")
        if (code != 200) throw IgException("HTTP $code from Instagram's GraphQL")
        val json = body.removePrefix("for (;;);").trim()
        if (!json.startsWith("{")) throw IgException("Instagram sent a web page instead of data")
        return JSONObject(json)
    }

    /** One page of a profile's Reels tab. Edges are {node: {media: {code, ...}}}. */
    suspend fun reelsPage(userPk: String, username: String, after: String?): JSONObject {
        val resp = graphqlQuery(REELS_DOC_ID, reelsVariables(userPk, after), "${IgConstants.BASE}/$username/")
        return resp.optJSONObject("data")?.optJSONObject("xdt_api__v1__clips__user__connection_v2")
            ?: throw IgException("Reels query refused: ${describe(resp)}")
    }

    /**
     * Full media info for one post (all carousel items, best image and video versions), in the same
     * `items` format as /api/v1/media/{pk}/info/.
     */
    suspend fun post(shortcode: String): JSONObject? {
        val vars = JSONObject()
            .put("shortcode", shortcode)
            .put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false)
        val info = webGraphql(POST_DOC_ID, vars, "${IgConstants.BASE}/p/$shortcode/")
            .optJSONObject("data")?.optJSONObject("xdt_api__v1__media__shortcode__web_info")
        val items = info?.optJSONArray("items")
        return if (items != null && items.length() > 0) info else null
    }
}
