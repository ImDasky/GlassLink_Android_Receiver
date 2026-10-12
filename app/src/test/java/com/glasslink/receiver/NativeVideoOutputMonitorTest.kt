package com.glasslink.receiver

import org.junit.Assert.*
import org.junit.Test

class NativeVideoOutputMonitorTest {
    private val playingAudio = NativeVideoOutputMonitor.TracksInfo(audioSelected = true)

    @Test fun audioOnlySourceIsNotMistakenForVideo() {
        val watch = NativeVideoOutputMonitor()
        watch.updateTracks(playingAudio)
        assertEquals(NativeVideoOutputMonitor.Result.WAITING, watch.observe(0L, 0L, true, true))
        assertEquals(NativeVideoOutputMonitor.Result.AUDIO_ONLY,
            watch.observe(11000L, 3000L, true, true))
    }
    @Test fun unsupportedVideoCodecIsIdentified() {
        val watch = NativeVideoOutputMonitor()
        watch.updateTracks(playingAudio.copy(videoPresent=true, videoSupported=false))
        watch.observe(0L, 0L, true, true)
        assertEquals(NativeVideoOutputMonitor.Result.UNSUPPORTED_CODEC,
            watch.observe(11000L, 3000L, true, true))
    }
    @Test fun selectedVideoTrackWithNoFrameIsStalled() {
        val watch = NativeVideoOutputMonitor()
        watch.updateTracks(playingAudio.copy(videoPresent=true, videoSupported=true, videoSelected=true))
        watch.observe(0L, 0L, true, true)
        assertEquals(NativeVideoOutputMonitor.Result.VIDEO_NOT_RENDERED,
            watch.observe(11000L, 3000L, true, true))
        watch.onRenderedFrame()
        assertEquals(NativeVideoOutputMonitor.Result.FRAME_RENDERED,
            watch.observe(12000L, 3500L, true, true))
    }
    @Test fun noFalseFailureOnAudioOnlyCastOrInitialLoading() {
        val watch = NativeVideoOutputMonitor()
        watch.updateTracks(playingAudio)
        assertEquals(NativeVideoOutputMonitor.Result.FRAME_RENDERED, watch.observe(0L, 0L, true, false))
        assertEquals(NativeVideoOutputMonitor.Result.WAITING, watch.observe(4000L, 0L, false, true))
        assertEquals(NativeVideoOutputMonitor.Result.WAITING, watch.observe(5000L, 100L, true, true))
    }
}
