package com.glasslink.receiver

/**
 * Media3 can successfully play an audio track while producing no video.
 * `Player.isPlaying` alone cannot establish video output. This tracks the
 * selected audio/video streams and `onRenderedFirstFrame()` separately.
 * It deliberately does not mutate track selection automatically: changing
 * adaptive quality without knowing the TV decoder may make a stream worse.
 */
internal class NativeVideoOutputMonitor {
    data class TracksInfo(
        val audioSelected: Boolean = false,
        val videoPresent: Boolean = false,
        val videoSupported: Boolean = false,
        val videoSelected: Boolean = false
    )

    enum class Result { WAITING, FRAME_RENDERED, AUDIO_ONLY, UNSUPPORTED_CODEC, VIDEO_NOT_SELECTED, VIDEO_NOT_RENDERED }

    private var startAtMs = -1L
    private var startPositionMs = 0L
    private var firstFrame = false
    private var tracks = TracksInfo()

    fun updateTracks(value: TracksInfo) { tracks = value }
    fun onRenderedFrame() { firstFrame = true }
    fun hasRenderedFrame(): Boolean = firstFrame

    fun observe(nowMs: Long, playbackPositionMs: Long, isPlaying: Boolean,
                videoExpected: Boolean): Result {
        if (!videoExpected) return Result.FRAME_RENDERED
        if (firstFrame) return Result.FRAME_RENDERED
        if (!isPlaying) { startAtMs = -1L; return Result.WAITING }
        if (startAtMs < 0) { startAtMs = nowMs; startPositionMs = playbackPositionMs }
        if (nowMs - startAtMs < 10_000L || playbackPositionMs - startPositionMs < 1_500L)
            return Result.WAITING
        return when {
            !tracks.videoPresent && tracks.audioSelected -> Result.AUDIO_ONLY
            tracks.videoPresent && !tracks.videoSupported -> Result.UNSUPPORTED_CODEC
            tracks.videoPresent && !tracks.videoSelected -> Result.VIDEO_NOT_SELECTED
            tracks.videoSelected -> Result.VIDEO_NOT_RENDERED
            else -> Result.WAITING
        }
    }
}
