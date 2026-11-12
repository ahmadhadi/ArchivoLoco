package com.ahmadhadi.archivoloco

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var status: TextView
    private lateinit var btnLogin: Button
    private lateinit var modeProfile: RadioButton
    private lateinit var username: EditText
    private lateinit var optComments: CheckBox
    private lateinit var optReplies: CheckBox
    private lateinit var optUnsave: CheckBox
    private lateinit var optRedownload: CheckBox
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var progress: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        btnLogin = findViewById(R.id.btnLogin)
        modeProfile = findViewById(R.id.modeProfile)
        username = findViewById(R.id.username)
        optComments = findViewById(R.id.optComments)
        optReplies = findViewById(R.id.optReplies)
        optUnsave = findViewById(R.id.optUnsave)
        optRedownload = findViewById(R.id.optRedownload)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        progress = findViewById(R.id.progress)
        log = findViewById(R.id.log)

        btnLogin.setOnClickListener {
            if (Session.isLoggedIn()) {
                AlertDialog.Builder(this)
                    .setMessage("Log out of Instagram in ArchivoLoco?")
                    .setPositiveButton(R.string.log_out) { _, _ -> Session.logOut { refreshLogin() } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                startActivityForResult(Intent(this, LoginActivity::class.java), 1)
            }
        }
        findViewById<android.widget.RadioGroup>(R.id.mode).setOnCheckedChangeListener { _, _ -> refreshMode() }
        optReplies.setOnCheckedChangeListener { _, checked -> if (checked) optComments.isChecked = true }
        btnStart.setOnClickListener { start() }
        btnStop.setOnClickListener { ArchiveService.stop(this) }

        handleShare(intent)
        refreshMode()
        askNotificationPermission()

        ui.launch {
            JobBus.state.collect { s ->
                btnStart.isEnabled = !s.running
                btnStop.isEnabled = s.running
                progress.text = if (s.title.isEmpty()) "" else
                    "${s.title}${if (s.running) " — running" else ""}\n${s.stats.summary()}"
                log.text = s.lines.takeLast(200).reversed().joinToString("\n")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshLogin()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshLogin()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private fun refreshLogin() {
        val ok = Session.isLoggedIn()
        status.setText(if (ok) R.string.logged_in_as else R.string.not_logged_in)
        btnLogin.setText(if (ok) R.string.log_out else R.string.log_in)
    }

    private fun refreshMode() {
        val profile = modeProfile.isChecked
        username.visibility = if (profile) View.VISIBLE else View.GONE
        optUnsave.visibility = if (profile) View.GONE else View.VISIBLE
    }

    /** Accepts a profile link shared from the Instagram app. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        parseUsername(text)?.let {
            modeProfile.isChecked = true
            username.setText(it)
        }
    }

    private fun parseUsername(raw: String): String? {
        val s = raw.trim()
        Regex("instagram\\.com/([A-Za-z0-9._]+)").find(s)?.let { m ->
            val u = m.groupValues[1]
            if (u !in setOf("p", "reel", "reels", "stories", "explore", "accounts")) return u
        }
        val plain = s.removePrefix("@")
        return if (Regex("^[A-Za-z0-9._]{1,30}$").matches(plain)) plain else null
    }

    private fun start() {
        if (!Session.isLoggedIn()) {
            Toast.makeText(this, "Log in to Instagram first.", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = modeProfile.isChecked
        val user = if (profile) parseUsername(username.text.toString()) else null
        if (profile && user == null) {
            username.error = "Enter a username or profile link"
            return
        }
        val i = Intent(this, ArchiveService::class.java)
            .putExtra(ArchiveService.EXTRA_SAVED, !profile)
            .putExtra(ArchiveService.EXTRA_USERNAME, user)
            .putExtra(ArchiveService.EXTRA_COMMENTS, optComments.isChecked)
            .putExtra(ArchiveService.EXTRA_REPLIES, optReplies.isChecked)
            .putExtra(ArchiveService.EXTRA_UNSAVE, optUnsave.isChecked)
            .putExtra(ArchiveService.EXTRA_REDOWNLOAD, optRedownload.isChecked)
        startForegroundService(i)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }
}
