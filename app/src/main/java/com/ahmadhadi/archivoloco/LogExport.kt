package com.ahmadhadi.archivoloco

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the activity log as a .txt in Download/ArchivoLoco/logs/ and shares it. */
object LogExport {
    private val relPath = "${Environment.DIRECTORY_DOWNLOADS}/ArchivoLoco/logs/"

    /** Writes the current log to a new .txt file and returns its content Uri. */
    fun save(ctx: Context): Uri {
        val text = JobBus.exportText(UpdateChecker.currentVersion(ctx))
        val name = "ArchivoLoco-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create $name")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray()) }
                ?: throw IllegalStateException("Couldn't open $name")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    /** Saves the log and opens the share sheet with the .txt attached. */
    fun share(ctx: Context) {
        val uri = save(ctx)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ArchivoLoco activity log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(send, "Share activity log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
