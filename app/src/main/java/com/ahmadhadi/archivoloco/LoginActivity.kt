package com.ahmadhadi.archivoloco

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The Android stand-in for "read cookies from your browser": you log in to instagram.com in a
 * WebView, and the session cookies it stores are what the archiver uses. Your password never
 * touches the app's code - it goes straight from the web page to Instagram.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Same desktop UA as the API client so the session matches the client hints.
            userAgentString = IgConstants.USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (Session.isLoggedIn()) {
                    cm.flush()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        web.loadUrl("${IgConstants.BASE}/accounts/login/")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
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
