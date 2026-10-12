package com.glasslink.receiver

import android.util.Log

/** Short, non-sensitive playback telemetry queried by GlassLink over trusted ADB. */
internal object ReceiverStatus {
    const val TAG = "GlassLinkRx"
    const val PREFIX = "GLASSLINK"

    fun report(requestID: String?, event: String, detail: String = "") {
        if (!ReceiverContract.validRequestID(requestID)) return
        // Never log URL, cookies, tokens or the full Media3 exception message.
        val safeDetail = detail.replace(Regex("[\\r\\n|]"), " ").take(100)
        Log.i(TAG, "$PREFIX|$requestID|$event|$safeDetail")
    }
}
