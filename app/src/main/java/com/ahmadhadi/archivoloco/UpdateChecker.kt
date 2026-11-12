package com.ahmadhadi.archivoloco

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val pageUrl: String,
)

/**
 * Asks this app's own GitHub repo for the latest release. Uses a separate HTTP client that can only
 * reach api.github.com over HTTPS and sends no cookies, so it's isolated from the Instagram client.
 */
object UpdateChecker {
    private const val LATEST = "https://api.github.com/repos/ahmadhadi/ArchivoLoco/releases/latest"
    private const val PREFS = "updates"
    private const val KEY_AUTO = "auto"
    private const val KEY_LAST = "last_check"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .addNetworkInterceptor { chain ->
            val u = chain.request().url
            if (u.scheme != "https" || u.host != "api.github.com") throw BlockedHostException("${u.scheme}://${u.host}")
            chain.proceed(chain.request())
        }
        .build()

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutoEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO, true)

    fun setAutoEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply()

    /** True when auto-check is on and the last check was over a day ago. */
    fun isAutoCheckDue(ctx: Context) =
        isAutoEnabled(ctx) && System.currentTimeMillis() - prefs(ctx).getLong(KEY_LAST, 0) > DAY_MS

    /** Returns the latest release if it's newer than the installed app, otherwise null. */
    suspend fun check(ctx: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST)
            .header("accept", "application/vnd.github+json")
            .header("user-agent", "ArchivoLoco")
            .build()
        val json = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IgException("GitHub returned HTTP ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
        prefs(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()

        val remote = json.optString("tag_name").removePrefix("v")
        if (!isNewer(remote, currentVersion(ctx))) return@withContext null
        val pageUrl = json.optString("html_url")
        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
        }
        // Only ever hand the browser a link inside this repo on github.com.
        val trusted = "https://github.com/ahmadhadi/ArchivoLoco/"
        UpdateInfo(
            version = remote,
            apkUrl = apkUrl.takeIf { it.startsWith(trusted) } ?: pageUrl.takeIf { it.startsWith(trusted) } ?: "${trusted}releases/latest",
            pageUrl = pageUrl.takeIf { it.startsWith(trusted) } ?: "${trusted}releases/latest",
        )
    }

    /** Compares dotted versions numerically: 1.0.10 > 1.0.9. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
