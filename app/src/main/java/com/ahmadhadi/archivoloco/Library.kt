package com.ahmadhadi.archivoloco

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Offline library: reads what the archiver saved under Download/ArchivoLoco/ (via MediaStore,
 * no network, no extra permissions) and turns it into profiles, posts and comments.
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
    private val ROOT = "${Environment.DIRECTORY_DOWNLOADS}/ArchivoLoco/"
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    /** All files under Download/ArchivoLoco/, grouped by relative sub-path ("wasej.sa/", "wasej.sa/stories/"). */
    private fun scan(ctx: Context, folder: String? = null): Map<String, List<ArchivedFile>> {
        val prefix = ROOT + (folder?.let { "$it/" } ?: "")
        val out = HashMap<String, MutableList<ArchivedFile>>()
        ctx.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf("$prefix%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val rel = c.getString(2).removePrefix(ROOT)
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                out.getOrPut(rel) { mutableListOf() } += ArchivedFile(c.getString(1), uri, c.getString(3) ?: "")
            }
        }
        return out
    }

    private fun readJson(ctx: Context, uri: Uri): JSONObject? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrNull()

    suspend fun list(ctx: Context): List<LibraryEntry> = withContext(Dispatchers.IO) {
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
            val commentsJson = byName["$id-comments.json"]?.let { readJson(ctx, it.uri) }
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
        if (media.isEmpty()) return null

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
