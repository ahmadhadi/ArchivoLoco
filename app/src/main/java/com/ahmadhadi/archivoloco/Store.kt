package com.ahmadhadi.archivoloco

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream

/**
 * Dedup log, the equivalent of `<output_dir>/.log.db`. One table keyed by (folder, url) so each
 * archive folder keeps its own history. URLs are normalised the same way as dedup.clean_url.
 */
class DedupLog(context: Context, private val folder: String, private val disabled: Boolean) :
    SQLiteOpenHelper(context, "dedup.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE log (folder TEXT NOT NULL, url TEXT NOT NULL, " +
                "date TEXT DEFAULT CURRENT_TIMESTAMP NOT NULL, PRIMARY KEY (folder, url))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun clean(url: String): String {
        val u = Uri.parse(url)
        return "https://${u.host}${u.path}"
    }

    fun isSaved(url: String): Boolean {
        if (disabled) return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM log WHERE folder = ? AND url = ? LIMIT 1",
            arrayOf(folder, clean(url)),
        ).use { return it.moveToFirst() }
    }

    fun save(url: String) {
        if (disabled) return
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO log (folder, url) VALUES (?, ?)",
            arrayOf(folder, clean(url)),
        )
    }
}

/** Writes files into Download/ArchivoLoco/<folder>/ through MediaStore (no storage permission). */
class OutputDir(private val context: Context, val folder: String) {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    private fun relPath(sub: String?) = buildString {
        append(Environment.DIRECTORY_DOWNLOADS).append("/ArchivoLoco/").append(folder).append('/')
        if (!sub.isNullOrEmpty()) append(sub).append('/')
    }

    private fun find(sub: String?, name: String): Uri? {
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(relPath(sub), name),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString())
        }
        return null
    }

    fun exists(sub: String?, name: String) = find(sub, name) != null

    /**
     * Writes a file. When [overwrite] is false and the file already exists it is left alone
     * (write_if_new). [takenAtSec] sets the media date so galleries sort by post date.
     */
    fun write(
        sub: String?,
        name: String,
        mime: String,
        overwrite: Boolean = false,
        takenAtSec: Long? = null,
        block: (OutputStream) -> Unit,
    ): Boolean {
        find(sub, name)?.let { existing ->
            if (!overwrite) return false
            resolver.openOutputStream(existing, "wt")?.use(block)
            return true
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath(sub))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            takenAtSec?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it * 1000) }
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Cannot create $name")
        try {
            resolver.openOutputStream(uri, "w")?.use(block)
                ?: throw IllegalStateException("Cannot open $name")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return true
    }

    fun writeText(sub: String?, name: String, text: String, overwrite: Boolean = false, takenAtSec: Long? = null) =
        write(sub, name, "application/json", overwrite, takenAtSec) { it.write(text.toByteArray()) }

    fun writeStream(sub: String?, name: String, mime: String, input: InputStream, takenAtSec: Long?) =
        write(sub, name, mime, overwrite = true, takenAtSec = takenAtSec) { out -> input.copyTo(out, 64 * 1024) }
}
