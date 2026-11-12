package com.ahmadhadi.archivoloco

/**
 * Ported from instagram_archiver/constants.py.
 *
 * Instagram's edge checks that the User-Agent agrees with the Sec-CH-UA client hints and serves
 * the HTML app shell instead of JSON when they disagree, so these must change together. The login
 * WebView uses the same User-Agent so the session cookies match what the API client sends.
 */
object IgConstants {
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/148.0.0.0 Safari/537.36"

    val SHARED_HEADERS = linkedMapOf(
        "accept" to "*/*",
        "cache-control" to "no-cache",
        "dnt" to "1",
        "pragma" to "no-cache",
        "sec-ch-ua" to "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
        "sec-ch-ua-full-version-list" to
            "\"Chromium\";v=\"148.0.7778.56\", \"Google Chrome\";v=\"148.0.7778.56\", \"Not/A)Brand\";v=\"99.0.0.0\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-model" to "\"\"",
        "sec-ch-ua-platform" to "\"Linux\"",
        "sec-ch-ua-platform-version" to "\"\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-origin",
        "sec-gpc" to "1",
    )

    val API_HEADERS = mapOf(
        "x-asbd-id" to "359341",
        "x-ig-app-id" to "936619743392459",
    )

    val PAGE_FETCH_HEADERS = mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "dpr" to "1.5",
        "sec-fetch-mode" to "navigate",
        "viewport-width" to "3840",
    )

    const val TIMELINE_DOC_ID = "9806959572732215"
    const val REEL_PAGE_GALLERY_DOC_ID = "26659189347081290"
    const val REEL_PAGE_GALLERY_PAGINATION_DOC_ID = "27002830962682635"

    const val BASE = "https://www.instagram.com"
}
