package com.ahmadhadi.archivoloco

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * Offline library: reads archives from the app's private storage (no network, no permissions)
 * and turns them into profiles, posts and comments.
 */

data class ArchivedFile(val name: String, val uri: Uri, val mime: String)

data class Media(val uri: Uri, val isVideo: Boolean, val width: Int, val height: Int)

data class Comment(
    val username: String,
    val text: String,
    val createdAt: Long,
    val likes: Int,
    val replies: List<Comment>,
)

data class ArchivedPost(
    val id: String,
    val code: String?,
    val takenAt: Long,
    val caption: String,
    val likes: Int?,
    val views: Int?,
    val commentCount: Int,
    val location: String?,
    val media: List<Media>,
    val comments: List<Comment>?,   // null = not archived
    val isReel: Boolean,
)

data class ArchivedProfile(
    val folder: String,
    val username: String,
    val fullName: String,
    val bio: String,
    val followers: Int?,
    val following: Int?,
    val postCountOnInstagram: Int?,
    val isVerified: Boolean,
    val picture: Uri?,
    val posts: List<ArchivedPost>,
    val stories: List<Media>,
    val highlights: List<Media>,
)

data class LibraryEntry(val folder: String, val picture: Uri?, val postCount: Int, val fileCount: Int)

object Library {
    private val PUBLIC_ROOT = "${Environment.DIRECTORY_DOWNLOADS}/ArchivoLoco/"
    private val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) { "json" -> "application/json"; "heic" -> "image/heic"; else -> "application/octet-stream" }
    }

    /** All archive files in private storage, grouped by relative dir ("wasej.sa/", "wasej.sa/stories/"). */
    private fun scan(ctx: Context, folder: String? = null): Map<String, List<ArchivedFile>> {
        val root = ArchiveStore.root(ctx)
        val start = folder?.let { ArchiveStore.dir(ctx, it) } ?: root
        val out = HashMap<String, MutableList<ArchivedFile>>()
        start.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
            val rel = f.parentFile!!.relativeTo(root).path.replace(File.separatorChar, '/') + "/"
            out.getOrPut(rel) { mutableListOf() } += ArchivedFile(f.name, Uri.fromFile(f), mimeOf(f.name))
        }
        return out
    }

    suspend fun list(ctx: Context): List<LibraryEntry> = withContext(Dispatchers.IO) {
        migrateFromDownloads(ctx)
        val byPath = scan(ctx)
        byPath.keys.map { it.substringBefore('/') }.distinct().filter { it.isNotEmpty() }.map { folder ->
            val root = byPath["$folder/"].orEmpty()
            LibraryEntry(
                folder = folder,
                picture = root.firstOrNull { it.name.startsWith("profile_pic.") }?.uri,
                postCount = root.count { it.name.endsWith("-media-info-0000.json") },
                fileCount = byPath.filterKeys { it.startsWith("$folder/") }.values.sumOf { it.size },
            )
        }.sortedBy { it.folder.lowercase() }
    }

    /**
     * One-time move of archives that older versions wrote to Download/ArchivoLoco/ (visible in the
     * gallery) into private storage. Exported logs are left where they are.
     */
    private fun migrateFromDownloads(ctx: Context) {
        val prefs = ctx.getSharedPreferences("storage", Context.MODE_PRIVATE)
        if (prefs.getBoolean("migrated_v1", false)) return
        val resolver = ctx.contentResolver
        resolver.query(
            downloads,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?",
            arrayOf("$PUBLIC_ROOT%", "${PUBLIC_ROOT}logs/%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(downloads, c.getLong(0))
                val rel = c.getString(2).removePrefix(PUBLIC_ROOT).trimEnd('/')
                if (rel.isEmpty() || rel.split('/').any { it == ".." || it.startsWith(".") }) continue
                val dest = File(ArchiveStore.root(ctx), rel).apply { mkdirs() }.let { File(it, c.getString(1)) }
                val ok = runCatching {
                    if (!dest.exists()) {
                        resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    }
                    true
                }.getOrDefault(false)
                if (ok) runCatching { resolver.delete(uri, null, null) }
            }
        }
        prefs.edit().putBoolean("migrated_v1", true).apply()
    }

    /** Copies a profile's archive to Download/ArchivoLoco/<folder>/ so it shows in the gallery. Returns files copied. */
    suspend fun exportToPhone(ctx: Context, folder: String): Int = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val root = ArchiveStore.root(ctx)
        var copied = 0
        ArchiveStore.dir(ctx, folder).walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
            val relDir = PUBLIC_ROOT + f.parentFile!!.relativeTo(root).path.replace(File.separatorChar, '/') + "/"
            val exists = resolver.query(
                downloads, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(relDir, f.name), null,
            )?.use { it.moveToFirst() } == true
            if (exists) return@forEach
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(f.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                put(MediaStore.MediaColumns.DATE_TAKEN, f.lastModified())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(downloads, values) ?: return@forEach
            val ok = runCatching {
                resolver.openOutputStream(uri, "w")?.use { out -> f.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            if (ok) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                copied++
            } else {
                resolver.delete(uri, null, null)
            }
        }
        copied
    }

    /**
     * Deletes a profile completely: its private archive, any copy saved to the phone with
     * "Save to phone", and its download history (so a new archive starts fresh).
     */
    suspend fun delete(ctx: Context, folder: String) = withContext(Dispatchers.IO) {
        require(folder.isNotEmpty() && !folder.contains('/') && !folder.startsWith(".")) { "Invalid folder" }
        ArchiveStore.dir(ctx, folder).deleteRecursively()
        runCatching {
            ctx.contentResolver.delete(
                downloads,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$PUBLIC_ROOT$folder/%"),
            )
        }
        DedupLog(ctx, folder, disabled = false).use { it.clear() }
    }

    /** Deletes every archived profile (logs exported to Downloads are kept). */
    suspend fun deleteAll(ctx: Context) = withContext(Dispatchers.IO) {
        ArchiveStore.root(ctx).listFiles()?.filter { it.isDirectory }?.forEach { delete(ctx, it.name) }
    }

    private fun readJson(ctx: Context, uri: Uri): JSONObject? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrNull()

    suspend fun list(ctx: Context): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val byPath = scan(ctx)
        byPath.keys.map { it.substringBefore('/') }.distinct().filter { it.isNotEmpty() && it != "logs" }.map { folder ->
            val root = byPath["$folder/"].orEmpty()
            LibraryEntry(
                folder = folder,
                picture = root.firstOrNull { it.name.startsWith("profile_pic.") }?.uri,
                postCount = root.count { it.name.endsWith("-media-info-0000.json") },
                fileCount = byPath.filterKeys { it.startsWith("$folder/") }.values.sumOf { it.size },
            )
        }.sortedBy { it.folder.lowercase() }
    }

    suspend fun load(ctx: Context, folder: String): ArchivedProfile = withContext(Dispatchers.IO) {
        val byPath = scan(ctx, folder)
        val root = byPath["$folder/"].orEmpty()
        val byName = root.associateBy { it.name }
        // Media files are saved as "<item id>.<ext>"; index them by id.
        val mediaById = root.filter { it.mime.startsWith("image/") || it.mime.startsWith("video/") }
            .associateBy { it.name.substringBeforeLast('.') }

        val profileJson = (byName["profile.json"] ?: byName["web_profile_info.json"])?.let { readJson(ctx, it.uri) }

        val posts = root.filter { it.name.endsWith("-media-info-0000.json") }.mapNotNull { f ->
            val info = readJson(ctx, f.uri) ?: return@mapNotNull null
            val id = f.name.removeSuffix("-media-info-0000.json")
            val commentsJson = (byName["$id-comments.json"] ?: byName["${id.substringBefore('_')}-comments.json"])
                ?.let { readJson(ctx, it.uri) }
            parsePost(id, info, mediaById, commentsJson)
        }.sortedByDescending { it.takenAt }

        fun mediaIn(sub: String) = byPath.filterKeys { it.startsWith("$folder/$sub/") }.values.flatten()
            .filter { it.mime.startsWith("image/") || it.mime.startsWith("video/") }
            .sortedBy { it.name }
            .map { Media(it.uri, it.mime.startsWith("video/"), 0, 0) }

        val p = profileJson ?: JSONObject()
        ArchivedProfile(
            folder = folder,
            username = p.optString("username").ifEmpty { folder },
            fullName = p.optString("full_name"),
            bio = p.optString("biography"),
            followers = p.intOrNull("follower_count") ?: p.optJSONObject("edge_followed_by")?.intOrNull("count"),
            following = p.intOrNull("following_count") ?: p.optJSONObject("edge_follow")?.intOrNull("count"),
            postCountOnInstagram = p.intOrNull("media_count")
                ?: p.optJSONObject("polaris_ordered_timeline_connection")?.intOrNull("count")
                ?: p.optJSONObject("edge_owner_to_timeline_media")?.intOrNull("count"),
            isVerified = p.optBoolean("is_verified"),
            picture = byName.values.firstOrNull { it.name.startsWith("profile_pic.") }?.uri,
            posts = posts,
            stories = mediaIn("stories"),
            highlights = mediaIn("highlights"),
        )
    }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun parsePost(
        id: String,
        info: JSONObject,
        mediaById: Map<String, ArchivedFile>,
        commentsJson: JSONObject?,
    ): ArchivedPost? {
        val item = info.optJSONArray("items")?.optJSONObject(0) ?: return null
        val children = item.optJSONArray("carousel_media")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            ?.takeIf { it.isNotEmpty() } ?: listOf(item)
        val media = children.mapNotNull { child ->
            val file = mediaById[safeFileName(child.optString("id").ifEmpty { child.optString("pk") })] ?: return@mapNotNull null
            Media(file.uri, file.mime.startsWith("video/"), child.optInt("original_width"), child.optInt("original_height"))
        }
        // Posts whose media failed to download are kept (shown with a placeholder) so counts add up.

        val comments = commentsJson?.optJSONArray("comments")?.let(::parseComments)
            ?: item.optJSONArray("preview_comments")?.takeIf { it.length() > 0 }?.let(::parseComments)
        return ArchivedPost(
            id = id,
            code = item.optString("code").takeIf { it.isNotEmpty() },
            takenAt = item.optLong("taken_at"),
            caption = item.optJSONObject("caption")?.optString("text").orEmpty(),
            likes = item.intOrNull("like_count")?.takeIf { !item.optBoolean("like_and_view_counts_disabled") },
            views = item.intOrNull("play_count") ?: item.intOrNull("view_count"),
            commentCount = item.optInt("comment_count"),
            location = item.optJSONObject("location")?.optString("name")?.takeIf { it.isNotEmpty() },
            media = media,
            comments = comments,
            isReel = item.optString("product_type") == "clips",
        )
    }

    private fun parseComments(arr: JSONArray): List<Comment> = (0 until arr.length()).mapNotNull { i ->
        val c = arr.optJSONObject(i) ?: return@mapNotNull null
        Comment(
            username = c.optJSONObject("user")?.optString("username").orEmpty().ifEmpty { "user" },
            text = c.optString("text"),
            createdAt = c.optLong("created_at"),
            likes = c.optInt("comment_like_count"),
            replies = c.optJSONArray("child_comments")?.let(::parseComments).orEmpty(),
        )
    }
}
