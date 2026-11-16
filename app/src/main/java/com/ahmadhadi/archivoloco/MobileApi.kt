package com.ahmadhadi.archivoloco

import android.util.Base64
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Instagram's mobile-app API (i.instagram.com), as instaloader's get_iphone_json uses it: iPad app
 * headers plus an "Authorization: Bearer IGT:2:..." token derived from your logged-in session.
 * Used for highlights, highlight/story items and the Reels tab, which the website refuses for some
 * sessions. Needs a login; talks only to i.instagram.com (HostPolicy), never sends cookies elsewhere.
 */
class MobileApi {
    private companion object {
        const val BASE = "https://i.instagram.com"
        val STATIC_HEADERS = mapOf(
            "user-agent" to "Instagram 361.0.0.35.82 (iPad13,8; iOS 18_0; en_US; en-US; scale=2.00; 2048x2732; 674117118) AppleWebKit/420+",
            "x-ads-opt-out" to "1",
            "x-bloks-is-panorama-enabled" to "true",
            "x-bloks-version-id" to "16b7bd25c6c06886d57c4d455265669345a2d96625385b8ee30026ac2dc5ed97",
            "x-fb-client-ip" to "True",
            "x-fb-connection-type" to "wifi",
            "x-fb-http-engine" to "Liger",
            "x-fb-server-cluster" to "True",
            "x-fb" to "1",
            "x-ig-abr-connection-speed-kbps" to "2",
            "x-ig-app-id" to "124024574287414",
            "x-ig-app-locale" to "en-US",
            "x-ig-app-startup-country" to "US",
            "x-ig-bandwidth-speed-kbps" to "0.000",
            "x-ig-capabilities" to "36r/F/8=",
            "x-ig-connection-type" to "WiFi",
            "x-ig-device-locale" to "en-US",
            "x-ig-mapped-locale" to "en-US",
            "x-ig-www-claim" to "0",
            "x-tigon-is-retry" to "False",
            "x-whatsapp" to "0",
        )
    }

    private val pigeonSession = UUID.randomUUID().toString()
    private val connectionSpeed = "${(1000..20000).random()}kbps"

    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addNetworkInterceptor(HostPolicy.enforce)
        .build()

    private fun cookies(): Map<String, String> =
        CookieManager.getInstance().getCookie(IgConstants.BASE).orEmpty().split(";").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()

    private fun headers(): Map<String, String> {
        val c = cookies()
        val sessionId = c["sessionid"]?.takeIf { it.isNotEmpty() } ?: throw SessionExpiredException()
        val userId = c["ds_user_id"]?.takeIf { it.isNotEmpty() } ?: throw SessionExpiredException()
        val token = Base64.encodeToString(
            JSONObject().put("ds_user_id", userId).put("sessionid", sessionId).toString().toByteArray(),
            Base64.NO_WRAP,
        )
        val h = LinkedHashMap(STATIC_HEADERS)
        h["x-ig-connection-speed"] = connectionSpeed
        h["x-pigeon-session-id"] = pigeonSession
        h["x-pigeon-rawclienttime"] = String.format(java.util.Locale.US, "%.6f", System.currentTimeMillis() / 1000.0)
        h["x-ig-timezone-offset"] = (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toString()
        h["ig-intended-user-id"] = userId
        h["ig-u-ds-user-id"] = userId
        h["authorization"] = "Bearer IGT:2:$token"
        c["mid"]?.let { h["x-mid"] = it }
        c["ig_did"]?.let { h["x-ig-device-id"] = it; h["x-ig-family-device-id"] = it }
        c["rur"]?.let { h["ig-u-rur"] = it.trim('"').replace("\\054", ",") }
        return h
    }

    private suspend fun call(req: Request.Builder, path: String): JSONObject = withContext(Dispatchers.IO) {
        headers().forEach { (k, v) -> req.header(k, v) }
        http.newCall(req.build()).execute().use { r ->
            if (r.code in 300..399) {
                val loc = r.header("location").orEmpty()
                if ("login" in loc || "challenge" in loc) throw SessionExpiredException()
                throw IgException("App API redirected $path")
            }
            val body = r.body.string()
            if (r.code == 429) throw IgException("Instagram is rate-limiting (HTTP 429). Wait a while and retry.")
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (r.code == 401 || json?.optString("message") == "login_required") throw SessionExpiredException()
            if (r.code !in 200..299 || json == null) {
                throw IgException("App API HTTP ${r.code} for $path" + (json?.optString("message")?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""))
            }
            if (json.optString("status") == "fail") throw IgException("App API refused $path: ${json.optString("message")}")
            json
        }
    }

    private suspend fun get(path: String, params: List<Pair<String, String>> = emptyList()): JSONObject {
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return call(Request.Builder().url(url), path)
    }

    private suspend fun post(path: String, form: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return call(Request.Builder().url("$BASE/$path").post(body), path)
    }

    /** Cover image URL per highlight id, filled by [highlightsTray]. */
    val highlightCovers = HashMap<String, String>()

    /** The profile's highlights as (numeric id, title). */
    suspend fun highlightsTray(userPk: String): List<Pair<String, String>> {
        val tray = get("api/v1/highlights/$userPk/highlights_tray/").optJSONArray("tray") ?: return emptyList()
        return (0 until tray.length()).mapNotNull { i ->
            val t = tray.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("id").substringAfterLast(':')
            t.optJSONObject("cover_media")?.let { c ->
                (c.optJSONObject("cropped_image_version") ?: c.optJSONObject("full_image_version"))
                    ?.optString("url")?.takeIf { it.isNotEmpty() }
            }?.let { highlightCovers[id] = it }
            if (id.isEmpty() || !id.all { it.isDigit() }) null else id to t.optString("title")
        }
    }

    /**
     * Items of reels by id: "highlight:<id>" for highlights, the user pk for current stories.
     * Returns reel id -> items (image_versions2 / video_versions format).
     */
    suspend fun reelsMedia(reelIds: List<String>): Map<String, JSONArray> {
        val json = get("api/v1/feed/reels_media/", reelIds.map { "reel_ids" to it })
        val reels = json.optJSONObject("reels") ?: return emptyMap()
        val out = LinkedHashMap<String, JSONArray>()
        val keys = reels.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            reels.optJSONObject(k)?.optJSONArray("items")?.let { out[k] = it }
        }
        return out
    }

    /** One page of the Reels tab. Returns (media list, next max_id or null). */
    suspend fun clips(userPk: String, maxId: String?): Pair<List<JSONObject>, String?> {
        val form = linkedMapOf("target_user_id" to userPk, "page_size" to "12", "include_feed_video" to "true")
        maxId?.let { form["max_id"] = it }
        val json = post("api/v1/clips/user/", form)
        val items = json.optJSONArray("items") ?: JSONArray()
        val media = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("media") }
        val paging = json.optJSONObject("paging_info")
        val next = paging?.takeIf { it.optBoolean("more_available") }?.optString("max_id")?.takeIf { it.isNotEmpty() }
        return media to next
    }
}
