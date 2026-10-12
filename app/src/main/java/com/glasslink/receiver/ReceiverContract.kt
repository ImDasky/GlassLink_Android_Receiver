package com.glasslink.receiver

import java.net.URI
import java.net.URLDecoder

/** Protocol for a GlassLink cast launched over a user-authorized ADB connection. */
object ReceiverContract {
    const val PACKAGE = "com.glasslink.receiver"
    const val PLAYER_COMPONENT = "com.glasslink.receiver/.PlayerActivity"
    const val EXTRA_MODE = "gl_mode"
    const val EXTRA_TITLE = "gl_title"
    const val EXTRA_MIME = "gl_mime"
    const val EXTRA_REQUEST = "gl_request"
    const val EXTRA_PAGE = "gl_page"
    const val EXTRA_REFERRER = "gl_referrer"
    const val EXTRA_USER_AGENT = "gl_useragent"

    enum class PlaybackKind { MEDIA, YOUTUBE, WEB_PAGE }

    fun validRequestID(value: String?): Boolean =
        value != null && value.length in 1..64 && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-'
        }

    fun validURL(value: String?): Boolean {
        if (value.isNullOrBlank() || value.length > 8192) return false
        return try {
            val uri = URI(value)
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        } catch (_: Exception) {
            false
        }
    }

    /** Identifies actual YouTube video webpages; never treat unrelated hosts as YouTube. */
    fun youtubeVideoID(value: String?): String? {
        if (!validURL(value)) return null
        val uri = try { URI(value) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty().trim('/')
        val candidate: String? = when {
            host == "youtu.be" || host == "www.youtu.be" -> path.substringBefore('/')
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> {
                when {
                    path == "watch" -> queryValue(uri.rawQuery, "v")
                    path.startsWith("shorts/") -> path.removePrefix("shorts/").substringBefore('/')
                    path.startsWith("live/") -> path.removePrefix("live/").substringBefore('/')
                    path.startsWith("embed/") -> path.removePrefix("embed/").substringBefore('/')
                    path.startsWith("v/") -> path.removePrefix("v/").substringBefore('/')
                    else -> null
                }
            }
            else -> null
        }
        return candidate?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    private fun queryValue(query: String?, key: String): String? {
        if (query == null) return null
        for (part in query.split('&')) {
            val encodedKey = part.substringBefore('=')
            if (encodedKey != key) continue
            return try {
                URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            } catch (_: IllegalArgumentException) { null }
        }
        return null
    }

    /**
     * Media3 cannot consume an HTML video webpage. Route it to a supported
     * webpage player instead of reporting a misleading container/codec error.
     * Also honors a YouTube origin page when the selected media URL is an
     * expiring googlevideo link that will not transfer to the TV.
     */
    fun playbackKind(mode: String?, address: String, mimeHint: String?, sourcePage: String?): PlaybackKind {
        // The iPhone's one-button Cast intentionally sends the original page.
        // Never reinterpret an explicitly requested webpage as a media file.
        // Even when GlassLink explicitly sends the original page, a known
        // YouTube video should open in its official full-viewport iframe player
        // rather than show YouTube's ordinary webpage around the video.
        if (youtubeVideoID(sourcePage) != null || youtubeVideoID(address) != null) {
            return PlaybackKind.YOUTUBE
        }
        if (mode == "page") return PlaybackKind.WEB_PAGE
        if (isLikelyMedia(address, mimeHint)) return PlaybackKind.MEDIA
        return PlaybackKind.WEB_PAGE
    }

    fun isLikelyMedia(url: String, hint: String?): Boolean {
        val mime = hint?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.startsWith("video/") || mime in setOf(
                "application/dash+xml", "application/vnd.apple.mpegurl",
                "application/x-mpegurl", "audio/x-mpegurl", "audio/mpegurl"
            )) return true
        val path = try { URI(url).path.orEmpty().lowercase() } catch (_: Exception) { return false }
        val suffix = path.substringAfterLast('.', "")
        if (suffix in setOf("mp4", "m4v", "mov", "mkv", "webm", "m3u8", "mpd", "ts", "avi", "ogg", "ogv", "3gp", "ism", "m2ts")) return true
        return path.contains("/videoplayback") || path.contains("/get_video") ||
            path.endsWith("/manifest")
    }

    fun mediaMime(hint: String?, url: String): String? {
        val candidate = hint?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (candidate == "application/vnd.apple.mpegurl" ||
            candidate == "application/x-mpegurl" || candidate == "audio/x-mpegurl" ||
            candidate == "audio/mpegurl") return "application/x-mpegURL"
        if (candidate == "application/dash+xml") return candidate
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".m3u8")) return "application/x-mpegURL"
        if (path.endsWith(".mpd")) return "application/dash+xml"
        // Let ExoPlayer sniff progressive media (including unknown extensions).
        return null
    }
}
