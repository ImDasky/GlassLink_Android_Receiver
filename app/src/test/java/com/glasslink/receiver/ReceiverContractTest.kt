package com.glasslink.receiver

import org.junit.Assert.*
import org.junit.Test

class ReceiverContractTest {
    @Test fun acceptsSafeWebURLs() {
        assertTrue(ReceiverContract.validURL("https://cdn.example.org/video.mp4?token=a%26b"))
        assertFalse(ReceiverContract.validURL("https://user:password@cdn.example.org/video.mp4"))
        assertFalse(ReceiverContract.validURL("file:///sdcard/movie.mp4"))
        assertFalse(ReceiverContract.validURL("javascript:alert(1)"))
        assertFalse(ReceiverContract.validURL("https://"))
        assertFalse(ReceiverContract.validURL("https://example.com/" + "x".repeat(8193)))
    }

    @Test fun rejectsUnsafeCastIdentifiers() {
        assertTrue(ReceiverContract.validRequestID("A1BC-09"))
        assertFalse(ReceiverContract.validRequestID(null))
        assertFalse(ReceiverContract.validRequestID(""))
        assertFalse(ReceiverContract.validRequestID("abc|PLAYING"))
        assertFalse(ReceiverContract.validRequestID("a b"))
        assertFalse(ReceiverContract.validRequestID("z".repeat(65)))
    }

    @Test fun choosesAdaptiveMIME() {
        assertEquals("application/x-mpegURL", ReceiverContract.mediaMime(
            "application/vnd.apple.mpegurl", "https://cdn.example.org/stream"))
        assertEquals("application/x-mpegURL", ReceiverContract.mediaMime(
            null, "https://cdn.example.org/master.m3u8?token=abc"))
        assertEquals("application/dash+xml", ReceiverContract.mediaMime(
            null, "https://cdn.example.org/master.mpd"))
        assertNull(ReceiverContract.mediaMime("video/mp4", "https://cdn.example.org/movie.mp4"))
    }

    @Test fun recognizesYoutubeVideoPages() {
        val id = "M7lc1UVf-VE"
        assertEquals(id, ReceiverContract.youtubeVideoID("https://www.youtube.com/watch?v=$id&t=123"))
        assertEquals(id, ReceiverContract.youtubeVideoID("https://m.youtube.com/shorts/$id"))
        assertEquals(id, ReceiverContract.youtubeVideoID("https://www.youtube.com/live/$id"))
        assertEquals(id, ReceiverContract.youtubeVideoID("https://youtu.be/$id?si=hello"))
        assertEquals(id, ReceiverContract.youtubeVideoID("https://www.youtube-nocookie.com/embed/$id"))
        assertEquals(id, ReceiverContract.youtubeVideoID("https://music.youtube.com/watch?v=$id"))
        assertNull(ReceiverContract.youtubeVideoID("https://www.youtube.com/"))
        assertNull(ReceiverContract.youtubeVideoID("https://www.youtube.com/watch?v=invalid"))
        assertNull(ReceiverContract.youtubeVideoID("https://www.youtube.com.evil.test/watch?v=$id"))
    }

    @Test fun routesPagesBeforeNativeMedia() {
        val yt = "https://www.youtube.com/watch?v=M7lc1UVf-VE"
        assertEquals(ReceiverContract.PlaybackKind.YOUTUBE,
            ReceiverContract.playbackKind("media", yt, "video/mp4", null))
        assertEquals(ReceiverContract.PlaybackKind.YOUTUBE,
            ReceiverContract.playbackKind("media", "https://r1.googlevideo.com/videoplayback?expire=12", "video/mp4", yt))
        assertEquals(ReceiverContract.PlaybackKind.MEDIA,
            ReceiverContract.playbackKind("media", "https://cdn.example.org/movie.mp4", "video/mp4", null))
        assertEquals(ReceiverContract.PlaybackKind.MEDIA,
            ReceiverContract.playbackKind("media", "https://cdn.example.org/hls?token=abc", "application/vnd.apple.mpegurl", null))
        assertEquals(ReceiverContract.PlaybackKind.WEB_PAGE,
            ReceiverContract.playbackKind("media", "https://vimeo.com/123456", null, null))
        assertEquals(ReceiverContract.PlaybackKind.WEB_PAGE,
            ReceiverContract.playbackKind("page", "https://site.example.org/watch/123", null, null))
        assertEquals(ReceiverContract.PlaybackKind.YOUTUBE,
            ReceiverContract.playbackKind("page", "https://www.youtube.com/watch?v=M7lc1UVf-VE", "video/mp4", null))
        assertEquals(ReceiverContract.PlaybackKind.WEB_PAGE,
            ReceiverContract.playbackKind("youtube", "https://www.youtube.com/", null, null))
    }

    @Test fun websiteVideoNeverRevealsSurroundingPage() {
        assertTrue(WebVideoScript.probe.contains("glVideo()"))
        assertTrue(WebVideoScript.isolate.contains("100vw"))
        assertTrue(WebVideoScript.isolate.contains("100vh"))
        assertTrue(WebVideoScript.isolate.contains("window.__glasslinkIsolation"))
        assertTrue(WebVideoScript.restore.contains("__glasslinkRestore"))
        assertTrue(WebVideoScript.isolate.contains("visibility','hidden"))
        assertTrue(WebVideoScript.isolate.contains("t.video.controls=false"))
        assertFalse(WebVideoScript.isolate.contains("scale("))
        assertNull(WebVideoScript.command("unknown"))
        assertTrue(WebVideoScript.command("seek", 10.0)!!.contains("currentTime"))
    }

    @Test fun youtubeUsesReceiverRemoteControls() {
        val html = YouTubeEmbed.html("M7lc1UVf-VE")
        assertTrue(html.contains("controls:0"))
        assertTrue(html.contains("window.glasslinkRemote"))
        assertTrue(html.contains("window.glasslinkInfo"))
        assertTrue(html.contains("youtube.com/iframe_api"))
        try { YouTubeEmbed.html("<script>"); fail("bad ID was accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun restoresPublicBrowserHeadersWithoutSecrets() {
        val headers = CastRequestHeaders.forRequest(
            "https://cdn.example.org/track.m3u8?sig=private",
            "https://web.example.org/watch/1?session=private#fragment",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X)")
        assertEquals("https://web.example.org/watch/1", headers["Referer"])
        assertTrue(headers["User-Agent"]!!.contains("iPhone"))
        assertFalse(headers.values.joinToString().contains("private"))
        assertNull(CastRequestHeaders.cleanUserAgent("test\r\nCookie: secret"))
        assertNull(CastRequestHeaders.cleanReferrer("https://user:pass@web.example.org/page"))
        assertFalse(CastRequestHeaders.forRequest("http://cdn.example.org/v.mp4",
            "https://web.example.org/watch", "Test").containsKey("Referer"))
    }

    @Test fun httpErrorsUsePageFallbackOnlyWhenAvailable() {
        assertTrue(PlaybackFailurePolicy.useWebPage(403, false, true, true, false))
        assertTrue(PlaybackFailurePolicy.useWebPage(404, false, true, true, false))
        assertTrue(PlaybackFailurePolicy.useWebPage(null, true, true, false, false))
        assertTrue(PlaybackFailurePolicy.useWebPage(null, true, true, true, true))
        assertTrue(PlaybackFailurePolicy.useWebPage(403, false, true, false, true))
        assertFalse(PlaybackFailurePolicy.useWebPage(403, false, false, true, false))
        assertFalse(PlaybackFailurePolicy.useWebPage(403, false, true, false, false))
        assertFalse(PlaybackFailurePolicy.useWebPage(200, false, true, true, false))
        assertTrue(PlaybackFailurePolicy.retryWithoutHints(403, true, false))
        assertFalse(PlaybackFailurePolicy.retryWithoutHints(403, true, true))
        assertFalse(PlaybackFailurePolicy.retryWithoutHints(401, true, false))
        assertFalse(PlaybackFailurePolicy.retryWithoutHints(403, false, false))
        assertTrue(PlaybackFailurePolicy.readableHttpStatus(403).contains("403"))
    }

}
