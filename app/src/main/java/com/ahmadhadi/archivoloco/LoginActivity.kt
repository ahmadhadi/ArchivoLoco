@file:OptIn(ExperimentalMaterial3Api::class)

package com.ahmadhadi.archivoloco

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Android stand-in for "read cookies from your browser": you log in to instagram.com in a
 * WebView, and the session cookies it stores are what the archiver uses. Your password never
 * touches the app's code - it goes straight from the web page to Instagram.
 */
class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ArchivoTheme {
                LoginScreen(onDone = { setResult(RESULT_OK); finish() }, onBack = { finish() })
            }
        }
    }
}

private const val LOGIN_URL = "${IgConstants.BASE}/accounts/login/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cm = remember { CookieManager.getInstance().apply { setAcceptCookie(true) } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var desktop by remember { mutableStateOf(BrowserIdentity.isDesktop(ctx)) }
    var progress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(LOGIN_URL) }
    var problem by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun explainAppLink() = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(
            "The Instagram app can't share its login with ArchivoLoco. Log in on this page instead.",
            withDismissAction = true,
        )
    }

    fun reload(switchDesktop: Boolean? = null) {
        switchDesktop?.let {
            desktop = it
            BrowserIdentity.setDesktop(ctx, it)
        }
        problem = null
        web?.apply {
            settings.userAgentString = BrowserIdentity.userAgent(ctx)
            loadUrl(LOGIN_URL)
        }
    }

    /** After a page finishes, make sure it shows something; a blank page gets a fallback card. */
    fun checkNotBlank(view: WebView) = scope.launch {
        delay(2500)
        view.evaluateJavascript(
            "(function(){var b=document.body;if(!b)return 0;" +
                "return (b.innerText||'').trim().length + document.querySelectorAll('input,button,img,svg').length;})()",
        ) { r ->
            if ((r?.toIntOrNull() ?: 0) == 0 && progress >= 100 && problem == null) {
                problem = "The login page came up empty."
            }
        }
    }

    BackHandler(enabled = canGoBack) { web?.goBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    title = {
                        Column {
                            Text("Log in to Instagram", style = MaterialTheme.typography.titleMedium)
                            Text(
                                currentUrl.removePrefix("https://").substringBefore('?'),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Desktop site") },
                                    trailingIcon = { Checkbox(checked = desktop, onCheckedChange = null) },
                                    onClick = { menu = false; reload(switchDesktop = !desktop) },
                                )
                            }
                        }
                    },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        "Log in with your username and password. Your password goes only to instagram.com.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = BrowserIdentity.userAgent(c)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // Hardening: no local file/content access, no third-party cookies, no pop-ups.
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.setGeolocationEnabled(false)
                        cm.setAcceptThirdPartyCookies(this, false)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            // Top-level navigation stays on Instagram/Facebook login pages; app hand-off
                            // links are blocked (they can't bring the session back); other links open
                            // in the real browser.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                val isAppLink = u.scheme !in setOf("https", "http") ||
                                    u.path.orEmpty().startsWith("/_n/") ||
                                    u.path.orEmpty().startsWith("/download")
                                if (isAppLink) {
                                    explainAppLink()
                                    return true
                                }
                                if (u.scheme == "https" && HostPolicy.isAllowedInLogin(u.host)) return false
                                if (u.scheme == "https" || u.scheme == "http") {
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE))
                                    }
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                url?.let { currentUrl = it }
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                currentUrl = url
                                canGoBack = view.canGoBack()
                                if (Session.isLoggedIn()) {
                                    cm.flush()
                                    onDone()
                                    return
                                }
                                checkNotBlank(view)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (request.isForMainFrame) problem = "The page couldn't load: ${error.description}"
                            }
                        }
                        loadUrl(LOGIN_URL)
                        web = this
                    }
                },
                onRelease = { it.destroy() },
            )

            problem?.let { msg ->
                Card(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(msg, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(
                            if (desktop) "Try reloading, or switch back to the mobile site."
                            else "Try reloading, or load Instagram's desktop login page instead.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { reload() }) { Text("Reload") }
                            Button(onClick = { reload(switchDesktop = !desktop) }) {
                                Text(if (desktop) "Mobile site" else "Try desktop site")
                            }
                        }
                    }
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
