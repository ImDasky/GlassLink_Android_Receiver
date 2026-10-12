package com.glasslink.receiver

import org.junit.Assert.*
import org.junit.Test

class FramePlaybackScriptTest {
    @Test fun frameAgentReportsOnlyMediaStateWithoutRawUrlsOrCookies() {
        val script = FramePlaybackScript.install
        assertTrue(script.contains("window.parent.postMessage"))
        assertTrue(script.contains("e.source"))
        assertTrue(script.contains("realFrame(e.source)"))
        assertTrue(script.contains("window.__glasslinkFrameAgent"))
        assertTrue(script.contains("isolate()"))
        assertFalse(script.contains("document.cookie"))
        assertFalse(script.contains("XMLHttpRequest"))
        assertFalse(script.contains("fetch("))
    }

    @Test fun mainPageCanDetectAndControlAnEmbeddedVideoWithoutExposingWebsite() {
        assertTrue(WebVideoScript.probe.contains("frame-video"))
        assertTrue(WebVideoScript.isolate.contains("__glasslinkFrameAgent.isolate"))
        assertTrue(WebVideoScript.command("pause")!!.contains("__glasslinkFrameAgent.control"))
        assertTrue(WebVideoScript.attemptPlay.contains("__glasslinkFrameAgent"))
        assertTrue(WebVideoScript.attemptPlay.contains("agent.control('unmute'"))
        assertTrue(WebVideoScript.restore.contains("__glasslinkFrameAgent.restore"))
    }
}
