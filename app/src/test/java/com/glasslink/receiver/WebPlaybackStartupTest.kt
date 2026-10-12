package com.glasslink.receiver

import org.junit.Assert.*
import org.junit.Test

class WebPlaybackStartupTest {
    @Test fun metadataAloneNeverMakesPageVisible() {
        val gate = WebPlaybackStartup()
        assertEquals(WebPlaybackStartup.Action.NONE, gate.observe(false, 0))
        assertEquals(WebPlaybackStartup.Action.NONE, gate.observe(false, 20_000))
        assertEquals(WebPlaybackStartup.Phase.WAITING, gate.phase)
    }

    @Test fun requiresProgressBeforeAndAfterIsolation() {
        val gate = WebPlaybackStartup()
        assertEquals(WebPlaybackStartup.Action.APPLY_ISOLATION, gate.observe(true, 1000))
        assertEquals(WebPlaybackStartup.Action.NONE, gate.observe(true, 1200))
        gate.isolated(true, 1300)
        assertEquals(WebPlaybackStartup.Action.NONE, gate.observe(false, 1500))
        assertEquals(WebPlaybackStartup.Action.REVEAL, gate.observe(true, 2600))
        assertTrue(gate.isVisible())
    }

    @Test fun stalledFullscreenIsRestoredAndOneRetryIsAllowed() {
        val gate = WebPlaybackStartup()
        assertEquals(WebPlaybackStartup.Action.APPLY_ISOLATION, gate.observe(true, 0))
        gate.isolated(true, 100)
        assertEquals(WebPlaybackStartup.Action.NONE, gate.observe(false, 4000))
        assertEquals(WebPlaybackStartup.Action.RESTORE_PAGE, gate.observe(false, 6200))
        gate.restored()
        assertEquals(WebPlaybackStartup.Action.APPLY_ISOLATION, gate.observe(true, 9000))
        gate.isolated(true, 9100)
        assertEquals(WebPlaybackStartup.Action.RESTORE_PAGE, gate.observe(false, 16000))
        gate.restored()
        assertFalse(gate.canAttemptIsolation())
        assertEquals(WebPlaybackStartup.Action.NONE, gate.observe(true, 20000))
    }
}
