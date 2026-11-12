package com.ahmadhadi.archivoloco

/**
 * Ported from instagram_archiver/constants.py.
 *
 * The Python tool impersonates desktop Chrome (User-Agent + matching Sec-CH-UA hints). ArchivoLoco
 * doesn't need to: requests are made by a real WebView (see WebBridge), which sends its own
 * consistent browser headers. Only Instagram's web-app headers and GraphQL doc_ids live here.
 */
object IgConstants {
    val API_HEADERS = mapOf(
        "x-asbd-id" to "359341",
        "x-ig-app-id" to "936619743392459",
    )

    const val TIMELINE_DOC_ID = "9806959572732215"
    const val REEL_PAGE_GALLERY_DOC_ID = "26659189347081290"
    const val REEL_PAGE_GALLERY_PAGINATION_DOC_ID = "27002830962682635"

    const val BASE = "https://www.instagram.com"
}
