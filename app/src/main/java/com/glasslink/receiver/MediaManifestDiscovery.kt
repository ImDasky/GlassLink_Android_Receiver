package com.glasslink.receiver

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * Records a public HLS/DASH manifest requested by this TV's own WebView.
 * No network fetch, cookie extraction, request interception, proxy or DRM
 * work is performed. A browser often exposes a blob: URL for MSE video;
 * that is NOT transferable to ExoPlayer. When a clear HTTP(S) manifest is
 * actually requested, Media3 may be able to play it natively instead.
 *
 * Signed links are kept only in memory. Never log or persist the URL.
 */
internal data class MediaManifestCandidate(val url: String, val referrer: String?, val kind: String)

internal class MediaManifestDiscovery {
    private val best = AtomicReference<MediaManifestCandidate?>()

    fun offer(url: String?, method: String?, requestHeaders: Map<String, String>?): Boolean {
        if (url.isNullOrBlank() || url.length > 8192 || !method.equals("GET", true)) return false
        val parsed = try { URI(url) } catch (_: Exception) { return false }
        if (!ReceiverContract.validURL(url) || parsed.fragment != null) return false
        val host = parsed.host?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".local") || host == "127.0.0.1" ||
            host == "::1" || host == "0.0.0.0" || host.startsWith("192.168.") ||
            host.startsWith("10.") || host.startsWith("172.16.") ||
            host.startsWith("172.17.") || host.startsWith("172.18.") ||
            host.startsWith("172.19.") || host.startsWith("172.2") ||
            host.startsWith("172.30.") || host.startsWith("172.31.")) return false
        val path = parsed.path.orEmpty().lowercase()
        // Only actual playlists/manifests, never media segments, ad URLs,
        // HTML watch pages or DRM license/key endpoints.
        if (listOf("/ads/", "/advert", "preroll", "/vast", "/license", "/drm/", "/keys/")
                .any { path.contains(it) }) return false
        val kind = when {
            path.endsWith(".m3u8") -> "hls"
            path.endsWith(".mpd") -> "dash"
            else -> return false
        }
        // Don't attempt to replay intentionally authenticated browser-only
        // requests through Media3 without their authorization context.
        if (requestHeaders.orEmpty().keys.any {
                it.equals("Authorization", true) || it.equals("Proxy-Authorization", true)
            }) return false
        val rawReferer = requestHeaders.orEmpty().entries
            .firstOrNull { it.key.equals("Referer", true) }?.value
        val safeReferer = CastRequestHeaders.cleanReferrer(rawReferer)
        val candidate = MediaManifestCandidate(url, safeReferer, kind)
        return best.compareAndSet(null, candidate)
    }

    fun candidate(): MediaManifestCandidate? = best.get()
    fun reset() { best.set(null) }
}
