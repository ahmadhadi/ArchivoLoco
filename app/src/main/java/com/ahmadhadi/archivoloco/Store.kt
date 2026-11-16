package com.ahmadhadi.archivoloco

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
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

    /** Forgets everything archived for this folder (used when a profile is deleted). */
    fun clear() {
        writableDatabase.execSQL("DELETE FROM log WHERE folder = ?", arrayOf(folder))
    }

    fun save(url: String) {
        if (disabled) return
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO log (folder, url) VALUES (?, ?)",
            arrayOf(folder, clean(url)),
        )
    }
}

/**
 * Where archives live: app-private storage (filesDir/archives/<folder>/). Not visible to the gallery,
 * file managers or other apps, and removed if ArchivoLoco is uninstalled. Use "Save to phone" in
 * the library to export a profile to Download/ArchivoLoco/.
 */
object ArchiveStore {
    fun root(ctx: Context): File = File(ctx.filesDir, "archives").apply { mkdirs() }
    fun dir(ctx: Context, folder: String): File = File(root(ctx), folder)
}

/** Writes an archive's files into private storage. */
class OutputDir(private val context: Context, val folder: String) {
    private fun target(sub: String?, name: String): File {
        val dir = ArchiveStore.dir(context, folder).let { if (sub.isNullOrEmpty()) it else File(it, sub) }
        dir.mkdirs()
        return File(dir, name)
    }

    fun exists(sub: String?, name: String) = target(sub, name).exists()

    /**
     * Writes a file atomically (temp file + rename). When [overwrite] is false and the file exists
     * it is left alone (write_if_new). [takenAtSec] sets the file time so it sorts by post date.
     */
    fun write(
        sub: String?,
        name: String,
        @Suppress("UNUSED_PARAMETER") mime: String,
        overwrite: Boolean = false,
        takenAtSec: Long? = null,
        block: (OutputStream) -> Unit,
    ): Boolean {
        val f = target(sub, name)
        if (f.exists() && !overwrite) return false
        val tmp = File(f.parentFile, ".$name.part")
        try {
            tmp.outputStream().use(block)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) throw IllegalStateException("Cannot save $name")
            }
            takenAtSec?.takeIf { it > 0 }?.let { f.setLastModified(it * 1000) }
        } finally {
            tmp.delete()
        }
        return true
    }

    fun writeText(sub: String?, name: String, text: String, overwrite: Boolean = false, takenAtSec: Long? = null) =
        write(sub, name, "application/json", overwrite, takenAtSec) { it.write(text.toByteArray()) }

    fun writeStream(sub: String?, name: String, mime: String, input: InputStream, takenAtSec: Long?) =
        write(sub, name, mime, overwrite = true, takenAtSec = takenAtSec) { out -> input.copyTo(out, 64 * 1024) }
}
