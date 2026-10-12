package com.glasslink.receiver

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec

/** Per-cast browser HTTP hints applied to manifest/segment/playlist requests.
 * The MediaSessionService and Activity share this state within one process.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object ReceiverHttpContext {
    @Volatile private var referrer: String? = null
    @Volatile private var agent: String? = null

    fun configure(sourcePage: String?, userAgent: String?) {
        referrer = CastRequestHeaders.cleanReferrer(sourcePage)
        agent = CastRequestHeaders.cleanUserAgent(userAgent)
    }

    fun hasHints(): Boolean = referrer != null || agent != null

    fun apply(spec: DataSpec): DataSpec {
        val browserHeaders = CastRequestHeaders.forRequest(spec.uri.toString(), referrer, agent)
        // Keep any explicit media-source headers, Range, and headers used by other modules.
        return if (browserHeaders.isEmpty()) spec else spec.withAdditionalHeaders(browserHeaders)
    }
}
