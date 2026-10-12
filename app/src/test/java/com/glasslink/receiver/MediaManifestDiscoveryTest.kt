package com.glasslink.receiver

import org.junit.Assert.*
import org.junit.Test

class MediaManifestDiscoveryTest {
    @Test fun acceptsOnlyObservedPublicAdaptiveManifests() {
        val catalog = MediaManifestDiscovery()
        assertFalse(catalog.offer("https://cdn.example/watch", "GET", emptyMap()))
        assertFalse(catalog.offer("https://cdn.example/s0.ts", "GET", emptyMap()))
        assertFalse(catalog.offer("https://cdn.example/ads/preroll.m3u8", "GET", emptyMap()))
        assertFalse(catalog.offer("https://cdn.example/live.m3u8", "POST", emptyMap()))
        assertFalse(catalog.offer("https://localhost/private.m3u8", "GET", emptyMap()))
        assertFalse(catalog.offer("https://user:pass@cdn.example/live.m3u8", "GET", emptyMap()))
        assertTrue(catalog.offer("https://cdn.example/live/master.m3u8?sig=abc", "GET",
            mapOf("Referer" to "https://web.example/watch/channel/abc?private=token")))
        assertEquals("hls", catalog.candidate()?.kind)
        assertEquals("https://web.example/watch/channel/abc", catalog.candidate()?.referrer)
        assertFalse(catalog.offer("https://another.example/master.mpd", "GET", emptyMap()))
        catalog.reset()
        assertTrue(catalog.offer("https://another.example/master.mpd", "GET", emptyMap()))
        assertEquals("dash", catalog.candidate()?.kind)
    }

    @Test fun neverReplaysAuthorizedBrowserOnlyRequestsThroughNativePlayer() {
        val catalog = MediaManifestDiscovery()
        assertFalse(catalog.offer("https://cdn.example/channel.m3u8", "GET",
            mapOf("Authorization" to "Bearer secret")))
        assertNull(catalog.candidate())
    }
}
