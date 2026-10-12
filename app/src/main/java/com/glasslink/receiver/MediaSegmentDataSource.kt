package com.glasslink.receiver

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Sniffs response bytes instead of choosing a decoder from a website's hostname. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MediaSegmentDataSource(private val upstream: DataSource) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = MediaSegmentDataSource(upstream.createDataSource())
    }

    private var prefix = ByteArray(8)
    private var prefixLength = 0
    private var prefixPosition = 0
    private var buffered: ByteArray? = null
    private var bufferPosition = 0

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        prefixLength = 0
        prefixPosition = 0
        buffered = null
        bufferPosition = 0
        val sourceLength = upstream.open(dataSpec)
        // A ranged request may point into the middle of a container. Let the
        // upstream source handle it without guessing at its format.
        if (dataSpec.position != 0L || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET)
            return sourceLength
        while (prefixLength < prefix.size) {
            val count = upstream.read(prefix, prefixLength, prefix.size - prefixLength)
            if (count == C.RESULT_END_OF_INPUT) break
            if (count == 0) continue
            prefixLength += count
        }
        if (!WrappedMediaSegment.isPngHeader(prefix, prefixLength)) return sourceLength
        val output = ByteArrayOutputStream()
        output.write(prefix, 0, prefixLength)
        val block = ByteArray(8192)
        while (true) {
            val count = upstream.read(block, 0, block.size)
            if (count == C.RESULT_END_OF_INPUT) break
            if (count == 0) continue
            if (output.size() > WrappedMediaSegment.MAX_INPUT_BYTES - count)
                throw IOException("Image media segment exceeds size limit")
            output.write(block, 0, count)
        }
        val original = output.toByteArray()
        val media = WrappedMediaSegment.decode(original)
        if (media != null) Log.i("GlassLink", "Unwrapped image-contained MPEG-TS segment")
        buffered = media ?: original
        return buffered!!.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val memory = buffered
        if (memory != null) {
            if (bufferPosition == memory.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, memory.size - bufferPosition)
            memory.copyInto(buffer, offset, bufferPosition, bufferPosition + count)
            bufferPosition += count
            return count
        }
        if (prefixPosition < prefixLength) {
            val count = minOf(length, prefixLength - prefixPosition)
            prefix.copyInto(buffer, offset, prefixPosition, prefixPosition + count)
            prefixPosition += count
            return count
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        buffered = null
        prefixLength = 0
        prefixPosition = 0
        bufferPosition = 0
        upstream.close()
    }
}
