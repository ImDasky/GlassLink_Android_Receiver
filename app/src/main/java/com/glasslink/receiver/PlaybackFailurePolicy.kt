package com.glasslink.receiver

/** Policy is intentionally independent of Android to allow desktop JVM tests. */
internal object PlaybackFailurePolicy {
    /** Some CDNs reject the browser identity; retry once with native HTTP defaults. */
    fun retryWithoutHints(status: Int?, hintsPresent: Boolean, alreadyRetried: Boolean): Boolean =
        !alreadyRetried && hintsPresent && status in setOf(403, 406)

    fun useWebPage(httpStatus: Int?, parsingFailed: Boolean, pageAvailable: Boolean,
                   pageDiffers: Boolean, returningFromWeb: Boolean): Boolean {
        if (!pageAvailable) return false
        if (parsingFailed || returningFromWeb) return true
        if (!pageDiffers) return false
        return httpStatus in setOf(401, 403, 404, 406, 410, 415, 429, 451) ||
            (httpStatus != null && httpStatus in 500..599)
    }

    fun readableHttpStatus(code: Int?): String = when (code) {
        401 -> "HTTP 401: sign-in required"
        403 -> "HTTP 403: site refused direct playback"
        404, 410 -> "HTTP $code: video link expired or missing"
        429 -> "HTTP 429: site is rate-limiting requests"
        null -> "HTTP request failed"
        else -> "HTTP $code: video server refused the request"
    }
}
