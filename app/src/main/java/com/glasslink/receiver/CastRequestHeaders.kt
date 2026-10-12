package com.glasslink.receiver

import java.net.URI

/**
 * Public HTTP browser hints only. Never send cookies, bearer tokens, authorization,
 * or full page query strings over an ADB shell command. Credentials stay on the phone.
 * This improves public hotlink-protected streams; it cannot impersonate an authenticated browser.
 */
internal object CastRequestHeaders {
    fun cleanUserAgent(raw: String?): String? {
        val value = raw?.trim() ?: return null
        return value.takeIf { it.isNotEmpty() && it.length <= 384 &&
            it.all { character -> character.code in 32..126 } }
    }

    fun cleanReferrer(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 8192) return null
        return try {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host ?: return null
            if (scheme !in setOf("http", "https") || uri.userInfo != null) return null
            val origin = "$scheme://$host" + (if (uri.port in 1..65535) ":${uri.port}" else "")
            val path = uri.rawPath.orEmpty().take(900)
            val safe = origin + path
            safe.takeIf { it.length <= 1024 && it.all { ch -> ch.code in 32..126 } }
        } catch (_: Exception) { null }
    }

    fun forRequest(mediaURL: String, rawReferrer: String?, rawAgent: String?): Map<String, String> {
        val mediaScheme = try { URI(mediaURL).scheme?.lowercase() } catch (_: Exception) { null }
        if (mediaScheme != "http" && mediaScheme != "https") return emptyMap()
        val headers = linkedMapOf<String, String>()
        val referrer = cleanReferrer(rawReferrer)
        // A secure page shouldn't be disclosed via a plaintext media request.
        if (referrer != null && !(mediaScheme == "http" && referrer.startsWith("https://"))) {
            headers["Referer"] = referrer
        }
        cleanUserAgent(rawAgent)?.let { headers["User-Agent"] = it }
        return headers
    }
}
