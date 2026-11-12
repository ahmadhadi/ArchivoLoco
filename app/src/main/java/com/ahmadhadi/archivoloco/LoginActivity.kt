@file:OptIn(ExperimentalMaterial3Api::class)

package com.ahmadhadi.archivoloco

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The Android stand-in for "read cookies from your browser": you log in to instagram.com in a
 * WebView, and the session cookies it stores are what the archiver uses. Your password never
 * touches the app's code - it goes straight from the web page to Instagram.
 */
class LoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        setContent {
            ArchivoTheme {
                var loading by remember { mutableStateOf(true) }
                var web by remember { mutableStateOf<WebView?>(null) }
                var canGoBack by remember { mutableStateOf(false) }

                BackHandler(enabled = canGoBack) { web?.goBack() }

                Scaffold(
                    topBar = {
                        Column {
                            TopAppBar(
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                    }
                                },
                                title = {
                                    Column {
                                        Text("Log in to Instagram", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Your password goes only to instagram.com",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                actions = {
                                    Icon(
                                        Icons.Filled.Lock, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 16.dp),
                                    )
                                },
                            )
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    },
                ) { padding ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Same desktop UA as the API client so the session matches the client hints.
                                settings.userAgentString = IgConstants.USER_AGENT
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                // Hardening: no local file/content access, no third-party cookies.
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.setSupportMultipleWindows(false)
                                settings.setGeolocationEnabled(false)
                                cm.setAcceptThirdPartyCookies(this, false)
                                webViewClient = object : WebViewClient() {
                                    // Top-level navigation stays on Instagram/Facebook login pages;
                                    // anything else (help links, ads) opens in the real browser.
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val u = request.url
                                        if (u.scheme == "https" && HostPolicy.isAllowedInLogin(u.host)) return false
                                        if (u.scheme == "https" || u.scheme == "http") {
                                            runCatching {
                                                startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE))
                                            }
                                        }
                                        return true
                                    }

                                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                        loading = true
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        loading = false
                                        canGoBack = view.canGoBack()
                                        if (Session.isLoggedIn()) {
                                            cm.flush()
                                            setResult(RESULT_OK)
                                            finish()
                                        }
                                    }
                                }
                                loadUrl("${IgConstants.BASE}/accounts/login/")
                                web = this
                            }
                        },
                    )
                }
            }
        }
    }
}

object Session {
    fun isLoggedIn(): Boolean {
        val c = CookieManager.getInstance().getCookie(IgConstants.BASE) ?: return false
        return c.split(";").any { it.trim().startsWith("sessionid=") && it.trim().length > "sessionid=".length }
    }

    fun logOut(done: () -> Unit) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { cm.flush(); done() }
    }
}
