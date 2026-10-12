package com.glasslink.receiver

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrappedMediaSegmentTest {
    @Test fun pngWrappedTransportStreamIsDecoded() {
        val transport = ByteArray(188 * 6) { index ->
            if (index % 188 == 0) 0x47 else (index * 17).toByte()
        }
        val compressed = ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(transport) }
        }.toByteArray()
        val payload = ByteArray(12 + compressed.size)
        "TIKTIKPX".toByteArray(Charsets.US_ASCII).copyInto(payload)
        val length = compressed.size
        for (index in 0..3) payload[8 + index] = (length ushr (24 - index * 8)).toByte()
        compressed.copyInto(payload, 12)

        assertArrayEquals(transport, WrappedMediaSegment.decode(makePng(payload)))
    }

    @Test fun ordinaryPngIsNotTreatedAsVideo() {
        assertNull(WrappedMediaSegment.decode(makePng(ByteArray(96))))
    }

    /** Builds an RGB PNG with all five legal row filters, independent of Android graphics. */
    private fun makePng(payload: ByteArray): ByteArray {
        val width = 16
        val stride = width * 3
        val height = maxOf(5, (payload.size + stride - 1) / stride)
        val pixels = ByteArray(height * stride)
        payload.copyInto(pixels)
        val scanlines = ByteArrayOutputStream()
        for (row in 0 until height) {
            val filter = row % 5
            scanlines.write(filter)
            for (column in 0 until stride) {
                val index = row * stride + column
                val left = if (column >= 3) pixels[index - 3].toInt() and 0xff else 0
                val above = if (row > 0) pixels[index - stride].toInt() and 0xff else 0
                val upperLeft = if (row > 0 && column >= 3)
                    pixels[index - stride - 3].toInt() and 0xff else 0
                val predictor = when (filter) {
                    1 -> left
                    2 -> above
                    3 -> (left + above) / 2
                    4 -> paeth(left, above, upperLeft)
                    else -> 0
                }
                scanlines.write((pixels[index].toInt() - predictor) and 0xff)
            }
        }
        val compressed = ByteArrayOutputStream().also { bytes ->
            DeflaterOutputStream(bytes).use { it.write(scanlines.toByteArray()) }
        }.toByteArray()
        val header = ByteArrayOutputStream().apply {
            writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0))
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            writeChunk(this, "IHDR", header)
            writeChunk(this, "IDAT", compressed)
            writeChunk(this, "IEND", byteArrayOf())
        }.toByteArray()
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, body: ByteArray) {
        output.writeInt(body.size)
        val header = type.toByteArray(Charsets.US_ASCII)
        output.write(header)
        output.write(body)
        val crc = CRC32().apply { update(header); update(body) }
        output.writeInt(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        for (shift in 24 downTo 0 step 8) write(value ushr shift and 0xff)
    }

    private fun paeth(left: Int, above: Int, upperLeft: Int): Int {
        val prediction = left + above - upperLeft
        val a = kotlin.math.abs(prediction - left)
        val b = kotlin.math.abs(prediction - above)
        val c = kotlin.math.abs(prediction - upperLeft)
        return if (a <= b && a <= c) left else if (b <= c) above else upperLeft
    }
}
