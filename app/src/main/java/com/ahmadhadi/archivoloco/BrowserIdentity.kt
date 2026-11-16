package com.ahmadhadi.archivoloco

import android.content.Context
import android.webkit.WebSettings

/**
 * The User-Agent the app's WebViews present to Instagram.
 *
 * Android's default WebView UA carries an in-app-browser marker ("; wv" and "Version/4.0"), which
 * makes Instagram treat the page as an embedded browser and push the user to the Instagram app,
 * leaving a blank page when that hand-off is blocked. Removing the marker makes it a normal mobile
 * Chrome identity. "Desktop site" switches to a desktop Chrome identity instead.
 */
object BrowserIdentity {
    private const val PREFS = "browser"
    private const val KEY_DESKTOP = "desktop_site"

    const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/142.0.0.0 Safari/537.36"

    fun isDesktop(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DESKTOP, false)

    fun setDesktop(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DESKTOP, on).apply()

    fun mobileUserAgent(ctx: Context): String {
        val ua = runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull()
            ?: return "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
        return ua.replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")
    }

    /** UA for login and the hidden archiving browser: both must match so the session stays valid. */
    fun userAgent(ctx: Context) = if (isDesktop(ctx)) DESKTOP_UA else mobileUserAgent(ctx)
}
