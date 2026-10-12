package com.glasslink.receiver

import androidx.media3.datasource.HttpDataSource

internal object ReceiverPlaybackErrors {
    /** Unwrap Media3's nested loading exceptions, without logging sensitive URLs or headers. */
    fun httpStatus(failure: Throwable): Int? {
        var current: Throwable? = failure
        repeat(12) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return (current as HttpDataSource.InvalidResponseCodeException).responseCode
            }
            current = current?.cause ?: return null
        }
        return null
    }
}
