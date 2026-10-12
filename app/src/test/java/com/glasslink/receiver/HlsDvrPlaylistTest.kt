package com.glasslink.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsDvrPlaylistTest {
    @Test fun growingPlaylistPreservesOlderSegmentsForSeeking() {
        val first = HlsDvrPlaylist.parse("https://media.example/live/index.m3u8", """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:41
            #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z
            #EXTINF:6.0,
            a.ts
            #EXTINF:6.0,
            b.ts
        """.trimIndent())!!
        assertEquals(41L, first.segments.first().sourceSequence)
        assertEquals("https://media.example/live/a.ts", first.segments.first().sourceUrl)
        val next = HlsDvrPlaylist.parse("https://media.example/live/index.m3u8", """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:42
            #EXTINF:6.0,
            b.ts
            #EXTINF:6.0,
            c.ts
        """.trimIndent())!!
        val all = first.segments + next.segments.last()
        val rendered = HlsDvrPlaylist.render(6, 0L,
            all.mapIndexed { index, segment -> segment to "file:///private/cache/$index.ts" })
        assertEquals(3, rendered.lines().count { it.startsWith("#EXTINF:") })
        assertTrue(rendered.contains("#EXT-X-MEDIA-SEQUENCE:0"))
        assertTrue(rendered.contains("file:///private/cache/0.ts"))
        assertTrue(rendered.contains("file:///private/cache/2.ts"))
    }

    @Test fun encryptedAndFragmentedPlaylistsStayWithNormalPlayer() {
        assertNull(HlsDvrPlaylist.parse("https://media.example/live.m3u8", """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6.0,
            segment.ts
        """.trimIndent()))
        assertNull(HlsDvrPlaylist.parse("https://media.example/live.m3u8", """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            segment.m4s
        """.trimIndent()))
    }
}
