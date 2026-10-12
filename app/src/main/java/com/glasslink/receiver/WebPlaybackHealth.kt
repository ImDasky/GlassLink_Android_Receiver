package com.glasslink.receiver

/**
 * Playback time is NOT evidence that the video track is rendering: an audio
 * decoder can advance the HTMLMediaElement clock while the video decoder is
 * stalled, unsupported or absent. Only advancing video frames, or (on older
 * devices without video-frame counters) advancing time WITH nonzero video
 * dimensions, count as visible video progress.
 */
internal class WebPlaybackHealth {
    data class Sample(
        val position: Double,
        val duration: Double,
        val decodedFrames: Long,
        val readyState: Int,
        val paused: Boolean,
        val waiting: Boolean,
        val ended: Boolean,
        val mediaError: Int,
        val bufferedAhead: Double,
        val playRejected: Boolean,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0
    )

    enum class State { STARTING, BUFFERING, PLAYING, PAUSED, ENDED, STALLED, NO_VIDEO, ERROR }

    data class Result(
        val state: State,
        val shouldRetryPlay: Boolean = false,
        val shouldTryNative: Boolean = false,
        val shouldFail: Boolean = false
    )

    private var firstAtMs = -1L
    private var lastProgressAtMs = -1L
    private var lastPosition = Double.NaN
    private var lastFrames = -1L
    private var missingVideoAtMs = -1L
    private var retryIssued = false
    private var nativeFallbackIssued = false
    private var everProgressed = false

    fun observe(sample: Sample, nowMs: Long, hasDirectMediaCandidate: Boolean): Result {
        if (firstAtMs < 0) {
            firstAtMs = nowMs
            lastProgressAtMs = nowMs
        }
        if (sample.mediaError != 0) {
            if (hasDirectMediaCandidate && !nativeFallbackIssued) {
                nativeFallbackIssued = true
                return Result(State.ERROR, shouldTryNative = true)
            }
            return Result(State.ERROR, shouldFail = true)
        }
        if (sample.ended) return Result(State.ENDED)

        val positionAdvanced = sample.position.isFinite() && lastPosition.isFinite() &&
            sample.position > lastPosition + 0.12 && sample.position - lastPosition < 12.0
        val framesAdvanced = sample.decodedFrames >= 0 && lastFrames >= 0 &&
            sample.decodedFrames > lastFrames
        if (sample.position.isFinite() && sample.position >= 0) lastPosition = sample.position
        if (sample.decodedFrames >= 0) lastFrames = sample.decodedFrames

        val videoDimensions = sample.videoWidth > 0 && sample.videoHeight > 0
        // If the browser reports frame counts, require NEW decoded frames.
        // A nonzero but frozen frame counter alongside advancing audio is not
        // proof that a moving picture is still being rendered.
        val videoProgress = !sample.paused && videoDimensions &&
            (framesAdvanced || (sample.decodedFrames < 0 && positionAdvanced))
        if (videoProgress) {
            missingVideoAtMs = -1L
            lastProgressAtMs = nowMs
            everProgressed = true
            return Result(State.PLAYING)
        }

        if (sample.paused && everProgressed && !sample.playRejected) {
            missingVideoAtMs = -1L
            lastProgressAtMs = nowMs
            return Result(State.PAUSED)
        }

        // An advancing audio clock with zero/unmoving video frames is the
        // user's specific symptom. Detect it separately and preferentially
        // try Media3 when an ordinary HLS/DASH media candidate is available.
        if (!sample.paused && positionAdvanced) {
            if (missingVideoAtMs < 0) missingVideoAtMs = nowMs
        }
        if (missingVideoAtMs >= 0) {
            val noVideoMs = (nowMs - missingVideoAtMs).coerceAtLeast(0L)
            if (noVideoMs >= 6_000L && hasDirectMediaCandidate && !nativeFallbackIssued) {
                nativeFallbackIssued = true
                return Result(State.NO_VIDEO, shouldTryNative = true)
            }
            if (noVideoMs >= 16_000L) return Result(State.NO_VIDEO, shouldFail = true)
            if (noVideoMs >= 3_000L) return Result(State.NO_VIDEO)
        }

        val idleMs = (nowMs - lastProgressAtMs).coerceAtLeast(0L)
        val sinceStartMs = (nowMs - firstAtMs).coerceAtLeast(0L)
        if (idleMs >= 8_000L && !retryIssued) {
            retryIssued = true
            return Result(State.BUFFERING, shouldRetryPlay = true)
        }
        if (idleMs >= 15_000L && hasDirectMediaCandidate && !nativeFallbackIssued) {
            nativeFallbackIssued = true
            return Result(State.STALLED, shouldTryNative = true)
        }
        if (idleMs >= 24_000L && sinceStartMs >= 24_000L) {
            return Result(State.STALLED, shouldFail = true)
        }
        if (idleMs >= 11_000L) return Result(State.STALLED)
        if (sample.paused && !sample.playRejected && sample.readyState >= 2 && sinceStartMs >= 3_000L)
            return Result(State.PAUSED)
        return Result(if (sinceStartMs < 3_000L && !sample.waiting) State.STARTING else State.BUFFERING)
    }
}
