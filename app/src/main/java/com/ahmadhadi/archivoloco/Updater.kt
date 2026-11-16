package com.ahmadhadi.archivoloco

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object NeedsPermission : UpdateState
    data class Downloading(val bytes: Long, val total: Long) : UpdateState {
        val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * In-app updater: downloads the release APK straight into an Android PackageInstaller session
 * (with progress), verifies GitHub's SHA-256, and asks Android to install it. Android itself also
 * refuses any APK not signed with the same key as the installed app.
 */
object Updater {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private const val ACTION_RESULT = "com.ahmadhadi.archivoloco.INSTALL_RESULT"

    /** Only GitHub release downloads (and the CDN they redirect to), over HTTPS. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val u = chain.request().url
            val ok = u.scheme == "https" && (u.host == "github.com" || u.host.endsWith(".githubusercontent.com"))
            if (!ok) throw BlockedHostException("${u.scheme}://${u.host}")
            chain.proceed(chain.request())
        }
        .build()

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system "Install unknown apps" switch for ArchivoLoco. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun reset() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    internal fun report(state: UpdateState) {
        _state.value = state
    }

    suspend fun downloadAndInstall(ctx: Context, info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        if (!canInstall(ctx)) {
            _state.value = UpdateState.NeedsPermission
            return
        }
        val app = ctx.applicationContext
        val installer = app.packageManager.packageInstaller
        var sessionId = -1
        try {
            _state.value = UpdateState.Downloading(0, info.size)
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                if (info.size > 0) setSize(info.size)
                if (Build.VERSION.SDK_INT >= 31) {
                    // Lets later updates skip the prompt once ArchivoLoco has installed itself.
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            sessionId = installer.createSession(params)

            withContext(Dispatchers.IO) {
                installer.openSession(sessionId).use { session ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                        if (!r.isSuccessful) throw IgException("Download failed (HTTP ${r.code})")
                        val body = r.body ?: throw IgException("Empty download")
                        val total = if (info.size > 0) info.size else body.contentLength()
                        session.openWrite("base.apk", 0, if (total > 0) total else -1).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                var lastReport = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    digest.update(buf, 0, n)
                                    done += n
                                    if (done - lastReport > 64 * 1024 || done == total) {
                                        lastReport = done
                                        _state.value = UpdateState.Downloading(done, total)
                                    }
                                }
                                session.fsync(out)
                            }
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (info.sha256 != null && actual != info.sha256) {
                        throw IgException("Downloaded file failed the integrity check - not installing.")
                    }
                    _state.value = UpdateState.Installing
                    val callback = PendingIntent.getBroadcast(
                        app, sessionId,
                        Intent(ACTION_RESULT).setPackage(app.packageName).setClass(app, InstallResultReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(callback.intentSender)
                }
            }
        } catch (e: Exception) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            _state.value = if (e is kotlinx.coroutines.CancellationException) UpdateState.Idle
            else UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }
}

/** Receives the installer's result; shows Android's confirmation screen when it asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.report(UpdateState.Idle) // the app restarts on the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Updater.report(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) UpdateState.Idle
                    else UpdateState.Failed(
                        when (status) {
                            PackageInstaller.STATUS_FAILURE_CONFLICT,
                            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                                "Android refused the update (signature mismatch). Uninstall once, then install the new version."
                            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                            else -> "Install failed${msg?.let { ": $it" } ?: ""}"
                        },
                    ),
                )
            }
        }
    }
}
