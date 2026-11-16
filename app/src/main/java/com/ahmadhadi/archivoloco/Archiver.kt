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
    private val mobile = MobileApi()

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
        val user = withBackoff("profile page") { guest.profile(username) }
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
        val total = user.optJSONObject("polaris_ordered_timeline_connection")?.optInt("count", -1)?.takeIf { it >= 0 }
            ?: user.optInt("media_count", -1)
        log("Profile found" + (if (total >= 0) ", $total posts" else "") + if (isPrivate) " (private)" else "")

        // 2. Stories and highlights need a login.
        if (loggedIn) {
            archiveHighlights(userPk, username)
            archiveStories(userPk, username)
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

        // 4. Reels tab (reels that also appear in the grid are already saved and get skipped).
        log("Fetching reels…")
        archiveReelsTab(userPk, username, loggedIn)
        writeFailed()
    }

    /** Public posts via the logged-out web app's queries (instaloader Profile.get_posts). */
    private suspend fun archiveTimelineGuest(username: String, pageId: String, first: JSONObject?) {
        var conn = first ?: throw IgException("The profile page didn't include its posts.")
        while (true) {
            currentCoroutineContext().ensureActive()
            val edges = conn.optJSONArray("edges") ?: JSONArray()
            try {
                for (i in 0 until edges.length()) {
                    edges.optJSONObject(i)?.optJSONObject("node")?.let { handleGuestPost(it) }
                }
            } catch (e: LimitedException) {
                log("⏹ ${e.message} - skipping the remaining posts for now. Run again later to continue.")
                return
            }
            val pi = conn.optJSONObject("page_info")
            if (pi == null || !pi.optBoolean("has_next_page")) break
            val cursor = pi.optString("end_cursor").takeIf { it.isNotEmpty() } ?: break
            delay(opts.delayMs)
            conn = try {
                withBackoff("more posts") { guest.postsPage(pageId, username, cursor) } ?: break
            } catch (e: IOException) {
                log("⚠ Stopped fetching posts: ${e.message}. Run again later to continue.")
                return
            }
        }
    }

    /** The profile's Reels tab (instaloader Profile.get_reels): guest first, then the login session. */
    private suspend fun archiveReelsTab(userPk: String, username: String, loggedIn: Boolean) {
        var useLogin = false
        var after: String? = null
        var count = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val conn = try {
                if (useLogin) client.reelsPage(userPk, username, after)
                    ?: throw IgException("Reels query refused for this session")
                else withBackoff("reels") { guest.reelsPage(userPk, username, after) }
            } catch (e: IOException) {
                if (!useLogin && loggedIn) {
                    log("Reels as guest refused (${e.message}); trying the app API with your login…")
                    try {
                        count += archiveReelsViaApp(userPk)
                        log("Reels processed: $count")
                        return
                    } catch (e2: IOException) {
                        if (e2 is SessionExpiredException) throw e2
                        log("Reels (app API) failed: ${e2.message}; trying the website with your login…")
                    }
                    useLogin = true
                    continue
                }
                log(
                    "⚠ Reels skipped: ${e.message}" +
                        if (!loggedIn) " - Instagram may require a login for the Reels tab." else "",
                )
                break
            }
            val edges = conn.optJSONArray("edges") ?: JSONArray()
            try {
                for (i in 0 until edges.length()) {
                    val media = edges.optJSONObject(i)?.optJSONObject("node")?.optJSONObject("media") ?: continue
                    count++
                    handleGuestPost(media, label = "reel")
                }
            } catch (e: LimitedException) {
                log("⏹ ${e.message} - skipping the remaining reels for now. Run again later to continue.")
                break
            }
            val pi = conn.optJSONObject("page_info")
            if (pi == null || !pi.optBoolean("has_next_page")) break
            after = pi.optString("end_cursor").takeIf { it.isNotEmpty() } ?: break
            delay(opts.delayMs)
        }
        log("Reels processed: $count")
    }

    /** Reels tab through the app API (clips/user), paging by max_id. Returns reels seen. */
    private suspend fun archiveReelsViaApp(userPk: String): Int {
        var maxId: String? = null
        var count = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val (media, next) = mobile.clips(userPk, maxId)
            if (count == 0 && media.isEmpty()) log("App API returned no reels")
            try {
                for (m in media) {
                    count++
                    handleGuestPost(m, label = "reel")
                }
            } catch (e: LimitedException) {
                log("⏹ ${e.message} - skipping the remaining reels for now. Run again later to continue.")
                return count
            }
            maxId = next ?: break
            delay(opts.delayMs)
        }
        return count
    }

    /** Instagram kept limiting logged-out requests even after backing off. */
    private class LimitedException(message: String) : IOException(message)

    private fun isLimit(e: IOException) =
        e.message.orEmpty().let { "rate-limiting" in it || "asking for a login" in it }

    /** Runs [block], waiting 1, 2 and 4 minutes when Instagram limits logged-out requests. */
    private suspend fun <T> withBackoff(what: String, block: suspend () -> T): T {
        var wait = 60_000L
        repeat(3) {
            try {
                return block()
            } catch (e: IOException) {
                if (!isLimit(e)) throw e
                log("⏸ Instagram is limiting requests ($what). Waiting ${wait / 60_000} min…")
                delay(wait)
                wait *= 2
            }
        }
        try {
            return block()
        } catch (e: IOException) {
            if (isLimit(e)) throw LimitedException("Instagram is still limiting requests")
            throw e
        }
    }

    /**
     * True when a timeline/reels node already carries every media URL we need, so no per-post
     * request is necessary (the big saver: Instagram allows only a few logged-out API calls).
     */
    private fun hasFullMedia(m: JSONObject): Boolean {
        val carousel = m.optJSONArray("carousel_media")
        val carouselCount = m.optInt("carousel_media_count", 0)
        if (carouselCount > 1 || (carousel != null && carousel.length() > 0)) {
            if (carousel == null || carousel.length() < maxOf(carouselCount, 1)) return false
            return (0 until carousel.length()).all { carousel.optJSONObject(it)?.let(::hasFullMedia) == true }
        }
        val isVideo = m.optInt("media_type") == 2 || m.optString("product_type") == "clips"
        val hasVideo = (m.optJSONArray("video_versions")?.length() ?: 0) > 0
        val hasImage = (m.optJSONObject("image_versions2")?.optJSONArray("candidates")?.length() ?: 0) > 0
        return if (isVideo) hasVideo else hasImage
    }

    /** One public post: full media via the logged-out post query, saved like handleMedia. */
    private suspend fun handleGuestPost(node: JSONObject, label: String = "post") {
        currentCoroutineContext().ensureActive()
        val code = node.optString("code").takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
            ?: return
        JobBus.stats { it.copy(posts = it.posts + 1) }
        val key = "${IgConstants.BASE}/p/$code/"
        if (dedup.isSaved(key)) {
            JobBus.stats { it.copy(skipped = it.skipped + 1) }
            // Already archived: only fill in comments if they were asked for and are missing.
            if (opts.comments && Session.isLoggedIn()) {
                val pk = node.optString("pk").ifEmpty { node.optString("id").substringBefore('_') }
                val id = safeFileName(node.optString("id").ifEmpty { pk })
                if (pk.isNotEmpty() && pk.all { it.isDigit() } && !out.exists(null, "$id-comments.json")) {
                    saveComments(pk, id, code)
                    log("✓ comments for $label $code")
                    delay(opts.delayMs)
                }
            }
            return
        }
        try {
            val info = if (hasFullMedia(node)) {
                JSONObject().put("items", JSONArray().put(node))
            } else withBackoff("$label $code") { guest.post(code) } ?: run {
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
            log("✓ $label $code")
            delay(opts.delayMs)
        } catch (e: LimitedException) {
            throw e // let the section stop; the rest is picked up on the next run
        } catch (e: IOException) {
            log("✖ $label $key: ${e.message}")
            failedUrls += key
            JobBus.stats { it.copy(failed = it.failed + 1) }
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

    private fun highlightSub(title: String, id: String): String {
        // Keep any language's letters (e.g. Arabic titles), drop path-unsafe characters, add the id to stay unique.
        val clean = title.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()
            .replace(' ', '_').take(40)
        return "highlights/" + (if (clean.isEmpty()) "highlight" else clean) + "_" + safeFileName(id).takeLast(6)
    }

    /**
     * Highlights: find the list (embedded in the profile page first - no extra API call - then the
     * logged-in page, then the older queries), and fetch each highlight's items through the
     * reel-gallery GraphQL query (the path that works with your login), with the classic query as
     * fallback.
     */
    private suspend fun archiveHighlights(userPk: String, username: String) {
        var reels = guest.pageHighlights
        if (reels.isEmpty()) {
            reels = try {
                GuestClient.highlightsIn(client.profilePageHtml(username))
            } catch (e: IOException) {
                if (e is SessionExpiredException) throw e
                emptyList()
            }
        }
        if (reels.isEmpty()) {
            reels = try {
                mobile.highlightsTray(userPk).also { if (it.isNotEmpty()) log("Highlights list via app API") }
            } catch (e: IOException) {
                if (e is SessionExpiredException) throw e
                log("Highlights list (app API) failed: ${e.message}")
                emptyList()
            }
        }
        if (reels.isEmpty()) reels = runCatching { client.highlightReels(userPk, username) }.getOrDefault(emptyList())
        if (reels.isEmpty()) reels = runCatching { client.highlightIds(userPk).map { it to "" } }.getOrDefault(emptyList())
        if (reels.isEmpty()) {
            log("Highlights: none found on the profile")
            return
        }
        log("Highlights: ${reels.size}")
        // Fast path: the app API returns up to 10 highlights' items per request.
        val viaApp = HashSet<String>()
        for (batch in reels.chunked(10)) {
            currentCoroutineContext().ensureActive()
            val got = try {
                mobile.reelsMedia(batch.map { "highlight:${it.first}" })
            } catch (e: IOException) {
                if (e is SessionExpiredException) throw e
                log("Highlight items (app API) failed: ${e.message}; trying other methods…")
                break
            }
            for ((id, title) in batch) {
                val items = got["highlight:$id"] ?: continue
                val sub = highlightSub(title, id)
                var n = 0
                for (i in 0 until items.length()) items.optJSONObject(i)?.let { if (saveStoryItem(it, sub)) n++ }
                log("✓ highlight \"${title.ifEmpty { id }}\": ${items.length()} items ($n new)")
                viaApp += id
            }
            delay(opts.delayMs)
        }
        for ((id, title) in reels) {
            if (id in viaApp) continue
            currentCoroutineContext().ensureActive()
            val name = title.ifEmpty { id }
            val sub = highlightSub(title, id)
            var saved = try {
                archiveReels(listOf(id), isHighlight = true, sub = sub, label = "highlight \"$name\"")
            } catch (e: IOException) {
                if (e is SessionExpiredException) throw e
                log("Highlight \"$name\" (gallery query) failed: ${e.message}")
                -1
            }
            if (saved <= 0) {
                // Fallback: classic query_hash query.
                try {
                    val items = client.storyItems(id, isHighlight = true, username)
                    var n = 0
                    for (i in 0 until items.length()) items.optJSONObject(i)?.let { if (saveStoryItem(it, sub)) n++ }
                    if (items.length() > 0) log("✓ highlight \"$name\": ${items.length()} items ($n new)")
                    saved = maxOf(saved, items.length())
                } catch (e: IOException) {
                    if (e is SessionExpiredException) throw e
                    if (saved < 0) {
                        log("⚠ Highlight \"$name\": ${e.message}")
                        JobBus.stats { it.copy(failed = it.failed + 1) }
                    }
                }
            }
            delay(opts.delayMs)
        }
    }

    /** Current stories: reel-gallery query first (works with your login), classic query as fallback. */
    private suspend fun archiveStories(userPk: String, username: String) {
        val n = try {
            archiveReels(listOf(userPk), isHighlight = false, sub = "stories", label = "current story")
        } catch (e: IOException) {
            if (e is SessionExpiredException) throw e
            -1
        }
        if (n > 0) return
        try {
            val items = mobile.reelsMedia(listOf(userPk))[userPk]
            if (items != null && items.length() > 0) {
                var saved = 0
                for (i in 0 until items.length()) items.optJSONObject(i)?.let { if (saveStoryItem(it, "stories")) saved++ }
                log("Current stories via app API: ${items.length()} ($saved new)")
                return
            }
        } catch (e: IOException) {
            if (e is SessionExpiredException) throw e
        }
        try {
            val items = client.storyItems(userPk, isHighlight = false, username)
            var saved = 0
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { if (saveStoryItem(it, "stories")) saved++ }
            log("Current stories: ${items.length()} ($saved new)")
        } catch (e: IOException) {
            if (e is SessionExpiredException) throw e
            if (n < 0) log("⚠ Stories skipped: ${e.message}") else log("Current stories: none")
        }
    }

    /**
     * Saves one story/highlight item. Handles the classic GraphQL format (display_resources /
     * video_resources, as instaloader's StoryItem) and the newer API format (image_versions2 /
     * video_versions). Returns true if a new file was written.
     */
    private suspend fun saveStoryItem(item: JSONObject, sub: String): Boolean {
        if (item.has("image_versions2") || item.has("video_versions")) {
            val before = JobBus.state.value.stats
            saveItem(item, sub, item.optLong("taken_at"))
            val after = JobBus.state.value.stats
            return after.images + after.videos > before.images + before.videos
        }
        val id = safeFileName(item.optString("id").ifEmpty { return false })
        val ts = item.optLong("taken_at_timestamp").takeIf { it > 0 }
        fun bestSrc(arr: JSONArray?): String? {
            if (arr == null) return null
            var best: String? = null
            var area = -1L
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val a = r.optLong("config_width") * r.optLong("config_height")
                if (a >= area && r.optString("src").isNotEmpty()) { area = a; best = r.getString("src") }
            }
            return best
        }
        return if (item.optBoolean("is_video")) {
            val url = bestSrc(item.optJSONArray("video_resources")) ?: return false
            download(url, sub, id, ts, forceExt = "mp4").also { if (it) JobBus.stats { s -> s.copy(videos = s.videos + 1) } }
        } else {
            val url = bestSrc(item.optJSONArray("display_resources")) ?: item.optString("display_url").ifEmpty { return false }
            download(url, sub, id, ts).also { if (it) JobBus.stats { s -> s.copy(images = s.images + 1) } }
        }
    }

    /** Port of ProfileScraper._dispatch_reel + save_reel_item. */
    private suspend fun archiveReels(reelIds: List<String>, isHighlight: Boolean, sub: String, label: String? = null): Int {
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
        if (n > 0 || label == null) {
            log(if (label != null) "✓ $label: $n items" else "${if (isHighlight) "Highlight" else "Story"} items processed: $n")
        }
        return n
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
