package com.glasslink.receiver

/**
 * Gates the presentation of a website video independently of its metadata.
 *
 * A site can publish an ever-increasing live duration without having rendered
 * a single frame. We therefore leave the page unmodified while its own player
 * initialises, request play, and require independent playback progress BEFORE
 * any fullscreen CSS is applied. After applying fullscreen, we require a
 * *second* progress observation before removing the native black cover.
 * If isolation itself stalls the source, we undo the changes once and let the
 * website resume normally. The page is never shown as a fallback.
 */
internal class WebPlaybackStartup {
    enum class Phase { WAITING, ISOLATING, VERIFYING, VISIBLE, RECOVERING }
    enum class Action { NONE, APPLY_ISOLATION, REVEAL, RESTORE_PAGE }

    var phase = Phase.WAITING
        private set
    private var isolateAtMs = 0L
    private var isolationAttempts = 0

    fun observe(progressConfirmed: Boolean, nowMs: Long): Action = when (phase) {
        Phase.WAITING -> if (progressConfirmed && isolationAttempts < 2) {
            phase = Phase.ISOLATING
            isolationAttempts++
            Action.APPLY_ISOLATION
        } else Action.NONE
        Phase.ISOLATING -> Action.NONE
        Phase.VERIFYING -> when {
            progressConfirmed -> {
                phase = Phase.VISIBLE
                Action.REVEAL
            }
            nowMs - isolateAtMs >= 6_000L -> {
                phase = Phase.RECOVERING
                Action.RESTORE_PAGE
            }
            else -> Action.NONE
        }
        Phase.RECOVERING -> Action.NONE
        Phase.VISIBLE -> Action.NONE
    }

    fun isolated(success: Boolean, nowMs: Long) {
        if (phase != Phase.ISOLATING) return
        if (success) {
            phase = Phase.VERIFYING
            isolateAtMs = nowMs
        } else {
            phase = Phase.RECOVERING
        }
    }

    fun restored() {
        if (phase == Phase.RECOVERING) phase = Phase.WAITING
    }

    fun canAttemptIsolation(): Boolean = isolationAttempts < 2
    fun isVisible(): Boolean = phase == Phase.VISIBLE
    fun isVerified(): Boolean = phase == Phase.VISIBLE || phase == Phase.VERIFYING
}
