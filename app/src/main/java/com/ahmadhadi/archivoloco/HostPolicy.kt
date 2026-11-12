package com.ahmadhadi.archivoloco

import okhttp3.Interceptor
import java.io.IOException

/**
 * The app's complete network allowlist. Every request (including redirects) is checked here, so
 * ArchivoLoco can only ever talk to Instagram and Meta's media CDNs, over HTTPS.
 */
object HostPolicy {
    private fun isOrSubdomainOf(host: String, domain: String) =
        host == domain || host.endsWith(".$domain")

    /** Hosts that may receive the Instagram session cookie and CSRF header. */
    fun isInstagram(host: String) = isOrSubdomainOf(host.lowercase(), "instagram.com")

    /** Hosts that serve media bytes. No cookies are ever sent to these. */
    fun isMediaCdn(host: String): Boolean {
        val h = host.lowercase()
        return isOrSubdomainOf(h, "cdninstagram.com") || isOrSubdomainOf(h, "fbcdn.net")
    }

    fun isAllowed(scheme: String, host: String) =
        scheme == "https" && (isInstagram(host) || isMediaCdn(host))

    /** Hosts the login WebView may navigate to at top level (login, 2FA, "Continue with Facebook"). */
    fun isAllowedInLogin(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return isInstagram(h) || isOrSubdomainOf(h, "facebook.com") || isMediaCdn(h)
    }

    /** Network interceptor: runs for the original request and for every redirect hop. */
    val enforce = Interceptor { chain ->
        val url = chain.request().url
        if (!isAllowed(url.scheme, url.host)) {
            throw BlockedHostException("${url.scheme}://${url.host}")
        }
        chain.proceed(chain.request())
    }
}

class BlockedHostException(target: String) :
    IOException("Blocked request to non-Instagram host: $target")

/** Makes a server-supplied id safe to use as a file name. */
fun safeFileName(raw: String): String {
    val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
    return cleaned.ifEmpty { "item" }.take(120)
}
