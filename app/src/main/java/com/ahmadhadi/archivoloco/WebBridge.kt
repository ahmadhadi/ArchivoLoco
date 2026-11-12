package com.ahmadhadi.archivoloco

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

data class BridgeResponse(val status: Int, val body: String)

/**
 * Sends Instagram requests from inside a hidden WebView on https://www.instagram.com, so they come
 * from the same browser engine, cookies, client hints and TLS stack you logged in with - exactly
 * what Instagram's own web app sends. Instagram rejects requests whose browser signals don't agree
 * (a plain HTTP client claiming to be desktop Chrome gets 404s and redirects).
 *
 * Security: no addJavascriptInterface. The page can't call into the app; the app starts a fetch
 * with evaluateJavascript and polls for the result. Navigation is pinned to instagram.com.
 */
class WebBridge(private val context: Context) {
    private var web: WebView? = null
    private val ids = AtomicInteger()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun open() = withContext(Dispatchers.Main) {
        if (web != null) return@withContext
        val loaded = CompletableDeferred<Unit>()
        val w = WebView(context.applicationContext)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkImage = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return !(u.scheme == "https" && HostPolicy.isInstagram(u.host.orEmpty()))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }
        // A tiny same-origin page: gives us the www.instagram.com origin without running Instagram's app.
        w.loadUrl("${IgConstants.BASE}/robots.txt")
        web = w
        withTimeout(30_000) { loaded.await() }
    }

    suspend fun close() = withContext(Dispatchers.Main) {
        web?.apply {
            stopLoading()
            destroy()
        }
        web = null
    }

    private suspend fun eval(js: String): String? = withContext(Dispatchers.Main) {
        val w = web ?: throw IgException("Browser bridge is not open")
        val result = CompletableDeferred<String?>()
        w.evaluateJavascript(js) { result.complete(it) }
        result.await()
    }

    /**
     * Performs a same-origin fetch on www.instagram.com. [path] must start with "/".
     * Returns status 302 for redirects (not followed) and -1 for network errors.
     */
    suspend fun fetch(
        method: String,
        path: String,
        form: Map<String, String>? = null,
        referrer: String = "${IgConstants.BASE}/",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000,
    ): BridgeResponse {
        require(path.startsWith("/")) { "Bridge paths must be same-origin" }
        open()
        val id = "r${ids.incrementAndGet()}"
        val body = form?.entries?.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val extra = JSONObject(IgConstants.API_HEADERS + headers)
        val js = """
            (function(id, method, path, body, extra, referrer) {
              window.__al = window.__al || {};
              var m = document.cookie.match(/(?:^|; )csrftoken=([^;]+)/);
              var h = Object.assign({'x-requested-with': 'XMLHttpRequest',
                                     'x-csrftoken': m ? decodeURIComponent(m[1]) : ''}, extra);
              var opt = {method: method, headers: h, credentials: 'include',
                         redirect: 'manual', referrer: referrer};
              if (body !== null) { opt.body = body; h['content-type'] = 'application/x-www-form-urlencoded'; }
              fetch(path, opt).then(function(r) {
                if (r.type === 'opaqueredirect') { window.__al[id] = {s: 302, b: ''}; return; }
                return r.text().then(function(t) { window.__al[id] = {s: r.status, b: t}; });
              }).catch(function(e) { window.__al[id] = {s: -1, b: String(e)}; });
              return 1;
            })(${JSONObject.quote(id)}, ${JSONObject.quote(method)}, ${JSONObject.quote(path)},
               ${if (body == null) "null" else JSONObject.quote(body)}, $extra, ${JSONObject.quote(referrer)});
        """.trimIndent()
        eval(js)

        val poll = """
            (function(){ var v = window.__al && window.__al[${JSONObject.quote(id)}];
              if (!v) return null; delete window.__al[${JSONObject.quote(id)}]; return JSON.stringify(v); })();
        """.trimIndent()
        val raw = withTimeoutOrNull(timeoutMs) {
            var wait = 40L
            while (true) {
                val r = eval(poll)
                if (r != null && r != "null") return@withTimeoutOrNull r
                delay(wait)
                wait = minOf(wait * 2, 250L)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: throw IgException("Instagram didn't answer in time")
        // evaluateJavascript returns a JS string literal; decode it, then parse the JSON inside.
        val jsonText = JSONTokener(raw).nextValue() as? String ?: throw IgException("Bad bridge response")
        val obj = JSONObject(jsonText)
        return BridgeResponse(obj.optInt("s", -1), obj.optString("b"))
    }
}
