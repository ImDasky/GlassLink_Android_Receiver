package com.glasslink.receiver

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Gives Media3 a growing local HLS playlist while its ordinary native UI remains in use. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LiveDvrDataSource(private val upstream: DataSource) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveDvrDataSource(upstream.createDataSource())
    }

    private var body: ByteArray? = null
    private var position = 0
    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        body = null
        position = 0
        uri = dataSpec.uri
        headers = emptyMap()
        val sourceLength = upstream.open(dataSpec)
        if (dataSpec.position != 0L || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET ||
            !LiveDvr.mayIntercept(dataSpec.uri.toString())) return sourceLength
        try {
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            while (true) {
                val count = upstream.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                if (count == 0) continue
                if (output.size() > 4 * 1024 * 1024 - count)
                    throw IOException("HLS playlist exceeds DVR inspection limit")
                output.write(buffer, 0, count)
            }
            headers = upstream.responseHeaders
            val original = output.toByteArray()
            body = LiveDvr.transform(dataSpec.uri.toString(), String(original, Charsets.UTF_8))
                ?: original
            return body!!.size.toLong()
        } finally {
            upstream.close()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val data = body ?: return upstream.read(buffer, offset, length)
        if (position >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - position)
        data.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    override fun getUri(): Uri? = uri ?: upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> =
        if (body == null) upstream.responseHeaders else headers

    override fun close() {
        body = null
        position = 0
        uri = null
        headers = emptyMap()
        upstream.close()
    }
}
