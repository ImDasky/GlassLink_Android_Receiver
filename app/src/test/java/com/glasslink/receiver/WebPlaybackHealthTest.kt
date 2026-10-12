package com.glasslink.receiver

import org.junit.Assert.*
import org.junit.Test

class WebPlaybackHealthTest {
    private fun clip(time: Double, frames: Long = 0, paused: Boolean = false,
                     error: Int = 0) = WebPlaybackHealth.Sample(
        position = time, duration = 180.0, decodedFrames = frames,
        readyState = 2, paused = paused, waiting = true,
        ended = false, mediaError = error, bufferedAhead = 0.0,
        playRejected = false, videoWidth = 1920, videoHeight = 1080)

    @Test fun durationWithoutVideoFramesOrClockProgressNeverCountsAsPlayback() {
        val monitor = WebPlaybackHealth()
        monitor.observe(clip(0.0), 0L, true)
        assertEquals(WebPlaybackHealth.State.BUFFERING, monitor.observe(clip(0.0), 4_000L, true).state)
        assertTrue(monitor.observe(clip(0.0), 8_100L, true).shouldRetryPlay)
        assertTrue(monitor.observe(clip(0.0), 15_100L, true).shouldTryNative)
        assertTrue(monitor.observe(clip(0.0), 25_000L, true).shouldFail)
    }

    @Test fun clockOrDecodedFramesAdvancingConfirmsPlayback() {
        val monitor = WebPlaybackHealth()
        monitor.observe(clip(0.0, 10), 0L, false)
        assertEquals(WebPlaybackHealth.State.PLAYING, monitor.observe(clip(0.7, 12), 1_000L, false).state)
        assertEquals(WebPlaybackHealth.State.PAUSED, monitor.observe(clip(0.7, 12, true), 2_000L, false).state)
    }

    @Test fun unsupportedStreamReportsError() {
        val monitor = WebPlaybackHealth()
        assertTrue(monitor.observe(clip(0.0, error = 3), 0L, false).shouldFail)
    }

    @Test fun browserDecodeErrorTriesAnObservedNativeStreamOnce() {
        val monitor = WebPlaybackHealth()
        val first = monitor.observe(clip(0.0, error = 3), 0L, true)
        assertTrue(first.shouldTryNative)
        assertFalse(first.shouldFail)
        assertTrue(monitor.observe(clip(0.0, error = 3), 1_000L, true).shouldFail)
    }

    @Test fun advancingAudioClockWithoutVideoFramesIsNotVideoPlayback() {
        val monitor = WebPlaybackHealth()
        monitor.observe(clip(0.0), 0L, false)
        assertEquals(WebPlaybackHealth.State.BUFFERING,
            monitor.observe(clip(0.8), 1000L, false).state)
        assertEquals(WebPlaybackHealth.State.PLAYING,
            monitor.observe(clip(1.6, 3), 2000L, false).state)
    }

    @Test fun advancingAudioClockWithoutVideoDimensionsNeverCountsAsVideo() {
        val monitor = WebPlaybackHealth()
        fun audio(position: Double) = clip(position, 0).copy(videoWidth = 0, videoHeight = 0)
        monitor.observe(audio(0.0), 0L, true)
        assertEquals(WebPlaybackHealth.State.BUFFERING,
            monitor.observe(audio(1.0), 1_000L, true).state)
        assertEquals(WebPlaybackHealth.State.NO_VIDEO,
            monitor.observe(audio(5.0), 5_000L, true).state)
        assertTrue(monitor.observe(audio(9.0), 9_000L, true).shouldTryNative)
    }

    @Test fun audioContinuingAfterLastDecodedFrameIsNotPlayingVideo() {
        val monitor = WebPlaybackHealth()
        monitor.observe(clip(0.0, 2), 0L, false)
        assertEquals(WebPlaybackHealth.State.PLAYING,
            monitor.observe(clip(1.0, 3), 1_000L, false).state)
        assertEquals(WebPlaybackHealth.State.BUFFERING,
            monitor.observe(clip(2.0, 3), 2_000L, false).state)
        assertEquals(WebPlaybackHealth.State.NO_VIDEO,
            monitor.observe(clip(6.0, 3), 6_000L, false).state)
    }

    @Test fun unavailableFrameCountersStillRequirePositiveVideoDimensions() {
        val monitor = WebPlaybackHealth()
        monitor.observe(clip(0.0, -1).copy(videoWidth=0), 0L, false)
        assertEquals(WebPlaybackHealth.State.BUFFERING,
            monitor.observe(clip(1.0, -1).copy(videoWidth=0), 1_000L, false).state)
        assertEquals(WebPlaybackHealth.State.PLAYING,
            monitor.observe(clip(2.0, -1), 2_000L, false).state)
    }

    @Test fun noDirectCandidateNeverTriggersAnUnsupportedMediaFallback() {
        val monitor = WebPlaybackHealth()
        monitor.observe(clip(0.0), 0L, false)
        monitor.observe(clip(0.0), 8_100L, false)
        assertFalse(monitor.observe(clip(0.0), 18_000L, false).shouldTryNative)
    }
}
