package com.ahmadhadi.archivoloco

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

data class ArchiveOptions(
    val comments: Boolean = false,
    val replies: Boolean = false,
    val unsave: Boolean = false,
    val redownload: Boolean = false,
    /** Polite pause between posts, like the Python tool's one-request-at-a-time workers. */
    val delayMs: Long = 1500,
)

/**
 * Port of ProfileScraper / SavedScraper. Work is sequential (one request in flight), which is
 * what the original relies on to stay under Instagram's rate limits.
 */
class Archiver(private val context: Context, private val opts: ArchiveOptions) {
    private val bridge = WebBridge(context)
    private val client = IgClient(context, bridge)
    private val guest = GuestClient()

    /** Releases the hidden browser. Always called when a job ends. */
    suspend fun close() = bridge.close()
    private val failedUrls = mutableListOf<String>()
    private lateinit var out: OutputDir
    private lateinit var dedup: DedupLog

    private fun log(s: String) = JobBus.log(s)

    private fun open(folder: String) {
        require(folder.isNotEmpty() && !folder.startsWith(".") && ".." !in folder && "/" !in folder) {
            "Invalid folder name"
        }
        out = OutputDir(context, folder)
        dedup = DedupLog(context, folder, disabled = opts.redownload)
    }

    private fun requireLogin() {
        if (!Session.isLoggedIn() || client.csrfToken() == null) {
            throw IgException("Not logged in - tap \"Log in\" first.")
        }
    }

    // ---------------------------------------------------------------- profile mode

    suspend fun archiveProfile(username: String) {
        open(username)
        val loggedIn = Session.isLoggedIn()
        log("Opening profile @$username" + if (loggedIn) "" else " (guest mode, no login)")

        // 1. Profile from its public page (instaloader's approach; works with or without login).
        val user = guest.profile(username)
        out.writeText(null, "profile.json", user.toString(2), overwrite = true)
        val pic = user.optJSONObject("hd_profile_pic_url_info")?.optString("url")?.takeIf { it.isNotEmpty() }
            ?: user.optString("profile_pic_url").takeIf { it.isNotEmpty() }
        pic?.let {
            try {
                download(it, null, "profile_pic", null)
            } catch (e: IOException) {
                log("⚠ Profile picture: ${e.message}")
            }
        }
        val userPk = user.getString("pk")
        val pageId = user.optString("id")
        val isPrivate = user.optBoolean("is_private")
        val total = user.optJSONObject("polaris_ordered_timeline_connection")?.optInt("count", -1) ?: -1
        log("Profile found" + (if (total >= 0) ", $total posts" else "") + if (isPrivate) " (private)" else "")

        // 2. Stories and highlights need a login.
        if (loggedIn) {
            try {
                val ids = client.highlightIds(userPk)
                log("Highlights: ${ids.size}")
                if (ids.isNotEmpty()) archiveReels(ids, isHighlight = true, sub = "highlights")
            } catch (e: IOException) {
                log("⚠ Highlights skipped: ${e.message}")
            }
            try {
                archiveReels(listOf(userPk), isHighlight = false, sub = "stories")
            } catch (e: IOException) {
                log("⚠ Stories skipped: ${e.message}")
            }
        } else {
            log("Stories and highlights need a login - skipped")
            if (opts.comments) log("Comments need a login - skipped")
        }

        // 3. Posts.
        if (isPrivate) {
            if (!loggedIn) throw IgException("@$username is private. Log in with an account that follows it.")
            log("Private profile - fetching posts with your login…")
            archiveTimelineLoggedIn(username)
        } else {
            log("Fetching posts…")
            archiveTimelineGuest(username, pageId, user.optJSONObject("polaris_ordered_timeline_connection"))
        }
        writeFailed()
    }

    /** Public posts via the logged-out web app's queries (instaloader Profile.get_posts). */
    private suspend fun archiveTimelineGuest(username: String, pageId: String, first: JSONObject?) {
        var conn = first ?: throw IgException("The profile page didn't include its posts.")
        while (true) {
            currentCoroutineContext().ensureActive()
            val edges = conn.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until edges.length()) {
                edges.optJSONObject(i)?.optJSONObject("node")?.let { handleGuestPost(it) }
            }
            val pi = conn.optJSONObject("page_info")
            if (pi == null || !pi.optBoolean("has_next_page")) break
            val cursor = pi.optString("end_cursor").takeIf { it.isNotEmpty() } ?: break
            delay(opts.delayMs)
            conn = guest.postsPage(pageId, username, cursor) ?: run {
                log("⚠ Instagram stopped returning more posts (try again later to continue)")
                return
            }
        }
    }

    /** One public post: full media via the logged-out post query, saved like handleMedia. */
    private suspend fun handleGuestPost(node: JSONObject) {
        currentCoroutineContext().ensureActive()
        val code = node.optString("code").takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
            ?: return
        JobBus.stats { it.copy(posts = it.posts + 1) }
        val key = "${IgConstants.BASE}/p/$code/"
        if (dedup.isSaved(key)) {
            JobBus.stats { it.copy(skipped = it.skipped + 1) }
            return
        }
        try {
            val info = guest.post(code) ?: run {
                log("⚠ No media data for $key")
                failedUrls += key
                JobBus.stats { it.copy(failed = it.failed + 1) }
                return
            }
            val items = info.getJSONArray("items")
            val first = items.getJSONObject(0)
            val id = safeFileName(first.optString("id").ifEmpty { code })
            val takenAt = first.optLong("taken_at")
            out.writeText(null, "$id.json", node.toString(2), takenAtSec = takenAt)
            out.writeText(null, "$id-media-info-0000.json", info.toString(2), takenAtSec = takenAt)
            if (opts.comments && Session.isLoggedIn()) {
                first.optString("pk").takeIf { it.isNotEmpty() }?.let { saveComments(it, id, code) }
            }
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val ts = item.optLong("taken_at", takenAt)
                val carousel = item.optJSONArray("carousel_media")
                if (carousel != null && carousel.length() > 0) {
                    for (j in 0 until carousel.length()) saveItem(carousel.getJSONObject(j), null, ts)
                } else {
                    saveItem(item, null, ts)
                }
            }
            dedup.save(key)
            log("✓ $code")
            delay(opts.delayMs)
        } catch (e: IOException) {
            log("✖ $key: ${e.message}")
            failedUrls += key
            JobBus.stats { it.copy(failed = it.failed + 1) }
            if ("rate-limiting" in e.message.orEmpty() || "asking for a login" in e.message.orEmpty()) throw e
        } catch (e: JSONException) {
            log("✖ $key: unexpected response (${e.message})")
            failedUrls += key
            JobBus.stats { it.copy(failed = it.failed + 1) }
        }
    }

    /** Logged-in timeline (private profiles you follow), via the in-app browser session. */
    private suspend fun archiveTimelineLoggedIn(username: String) {
        var conn = client.timelinePage(username, null)
            ?: throw IgException("Instagram refused the posts query for this private profile.")
        while (true) {
            currentCoroutineContext().ensureActive()
            val edges = conn.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until edges.length()) {
                edges.optJSONObject(i)?.optJSONObject("node")?.let { handleMedia(it) }
            }
            val pi = conn.optJSONObject("page_info")
            if (pi == null || !pi.optBoolean("has_next_page")) break
            conn = client.timelinePage(username, pi.optString("end_cursor")) ?: break
        }
    }

    /** Port of ProfileScraper._dispatch_reel + save_reel_item. */
    private suspend fun archiveReels(reelIds: List<String>, isHighlight: Boolean, sub: String) {
        var after: String? = null
        var n = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val conn = client.reelPageGallery(reelIds, after, isHighlight) ?: break
            val edges = conn.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                val items = node.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    try {
                        saveItem(item, sub, item.optLong("taken_at"))
                        n++
                    } catch (e: IOException) {
                        if (e is SessionExpiredException) throw e
                        log("⚠ Story item ${item.optString("pk")}: ${e.message}")
                        JobBus.stats { it.copy(failed = it.failed + 1) }
                    }
                }
            }
            val pi = conn.optJSONObject("page_info")
            if (pi == null || !pi.optBoolean("has_next_page")) break
            after = pi.optString("end_cursor")
        }
        log("${if (isHighlight) "Highlight" else "Story"} items processed: $n")
    }

    // ---------------------------------------------------------------- saved mode

    suspend fun archiveSaved() {
        open("saved")
        requireLogin()
        log("Opening your saved posts")
        client.getPage("${IgConstants.BASE}/")
        var maxId: String? = null
        var page = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val feed = client.savedPage(maxId)
            val items = feed.optJSONArray("items") ?: JSONArray()
            page++
            log("Saved page $page: ${items.length()} posts")
            for (i in 0 until items.length()) {
                val media = items.optJSONObject(i)?.optJSONObject("media") ?: continue
                val ok = handleMedia(media)
                if (ok && opts.unsave) {
                    try {
                        if (client.unsave(media.getString("pk"))) {
                            JobBus.stats { it.copy(unsaved = it.unsaved + 1) }
                        } else {
                            log("⚠ Unsave failed for ${media.optString("code")}")
                        }
                    } catch (e: IOException) {
                        log("⚠ Unsave failed for ${media.optString("code")}: ${e.message}")
                    }
                }
            }
            maxId = feed.optString("next_max_id").takeIf { it.isNotEmpty() }
            if (!feed.optBoolean("more_available") || maxId == null) break
            delay(opts.delayMs)
        }
        writeFailed()
    }

    // ---------------------------------------------------------------- shared

    /** Port of InstagramClient.save_media + comments. Returns true when the post is archived. */
    private suspend fun handleMedia(node: JSONObject): Boolean {
        currentCoroutineContext().ensureActive()
        JobBus.stats { it.copy(posts = it.posts + 1) }
        val pk = node.optString("pk")
        val id = safeFileName(node.optString("id").ifEmpty { pk })
        val code = node.optString("code").takeIf { it.isNotEmpty() }
        if (pk.isEmpty() || !pk.all { it.isDigit() }) return false
        val permalink = "${IgConstants.BASE}/p/${code ?: pk}/"
        val infoUrl = "${IgConstants.BASE}/api/v1/media/$pk/info/"
        try {
            if (opts.comments) saveComments(pk, id, code)

            if (dedup.isSaved(infoUrl)) {
                JobBus.stats { it.copy(skipped = it.skipped + 1) }
                return true
            }
            val info = client.mediaInfo(pk)
            if (info == null) {
                log("⚠ No media data for $permalink")
                failedUrls += permalink
                JobBus.stats { it.copy(failed = it.failed + 1) }
                return false
            }
            val items = info.getJSONArray("items")
            val takenAt = items.getJSONObject(0).optLong("taken_at")
            out.writeText(null, "$id.json", node.toString(2), takenAtSec = takenAt)
            out.writeText(null, "$id-media-info-0000.json", info.toString(2), takenAtSec = takenAt)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val ts = item.optLong("taken_at", takenAt)
                val carousel = item.optJSONArray("carousel_media")
                if (carousel != null && carousel.length() > 0) {
                    for (j in 0 until carousel.length()) saveItem(carousel.getJSONObject(j), null, ts)
                } else {
                    saveItem(item, null, ts)
                }
            }
            dedup.save(infoUrl)
            log("✓ ${code ?: pk}")
            delay(opts.delayMs)
            return true
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: IOException) {
            log("✖ $permalink: ${e.message}")
        } catch (e: JSONException) {
            log("✖ $permalink: unexpected response (${e.message})")
        }
        failedUrls += permalink
        JobBus.stats { it.copy(failed = it.failed + 1) }
        return false
    }

    private suspend fun saveComments(pk: String, id: String, code: String?) {
        try {
            val data = client.comments(pk, code, opts.replies)
            out.writeText(null, "$id-comments.json", data.toString(2), overwrite = true)
            val n = data.optJSONArray("comments")?.length() ?: 0
            JobBus.stats { it.copy(comments = it.comments + n) }
        } catch (e: IOException) {
            if (e is SessionExpiredException) throw e
            log("⚠ Comments for ${code ?: pk}: ${e.message}")
        }
    }

    /**
     * Saves the best version of a single media item. Video items take the largest entry in
     * `video_versions` (the Python tool hands these to yt-dlp instead); images take the largest
     * `image_versions2` candidate, like save_image_versions2.
     */
    private suspend fun saveItem(item: JSONObject, sub: String?, takenAt: Long) {
        val id = safeFileName(item.optString("id").ifEmpty { item.optString("pk") })
        val videos = item.optJSONArray("video_versions")
        if (videos != null && videos.length() > 0) {
            best(videos)?.let { url ->
                if (download(url, sub, id, takenAt, forceExt = "mp4")) JobBus.stats { it.copy(videos = it.videos + 1) }
            }
            return
        }
        val candidates = item.optJSONObject("image_versions2")?.optJSONArray("candidates") ?: return
        best(candidates)?.let { url ->
            if (download(url, sub, id, takenAt)) JobBus.stats { it.copy(images = it.images + 1) }
        }
    }

    private fun best(arr: JSONArray): String? {
        var bestUrl: String? = null
        var bestArea = -1L
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val area = c.optLong("width") * c.optLong("height")
            if (area > bestArea && c.optString("url").isNotEmpty()) {
                bestArea = area
                bestUrl = c.getString("url")
            }
        }
        return bestUrl
    }

    /** Downloads a CDN file. Returns true if a new file was written. */
    private suspend fun download(
        url: String,
        sub: String?,
        baseName: String,
        takenAt: Long?,
        forceExt: String? = null,
    ): Boolean {
        if (dedup.isSaved(url)) {
            JobBus.stats { it.copy(skipped = it.skipped + 1) }
            return false
        }
        withContext(Dispatchers.IO) {
            client.cdn.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IgException("HTTP ${r.code} downloading media")
                val body = r.body ?: throw IgException("Empty media response")
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                    ?: if (forceExt == "mp4") "video/mp4" else "image/jpeg"
                val ext = forceExt ?: extensionFor(mime)
                body.byteStream().use { input ->
                    out.writeStream(sub, "$baseName.$ext", mime, input, takenAt?.takeIf { it > 0 })
                }
            }
        }
        dedup.save(url)
        return true
    }

    private fun extensionFor(mime: String) = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/avif" -> "avif"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        else -> "bin"
    }

    private fun writeFailed() {
        if (failedUrls.isEmpty()) return
        out.write(null, "failed.txt", "text/plain", overwrite = true) {
            it.write(failedUrls.joinToString("\n", postfix = "\n").toByteArray())
        }
        log("Wrote failed.txt with ${failedUrls.size} URLs")
    }
}
