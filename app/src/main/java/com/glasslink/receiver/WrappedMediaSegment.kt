package com.glasslink.receiver

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Decodes a PNG transport wrapper when its pixels carry a gzip media segment. */
internal object WrappedMediaSegment {
    private val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val wrapperSignature = "TIKTIKPX".toByteArray(Charsets.US_ASCII)
    const val MAX_INPUT_BYTES = 24 * 1024 * 1024
    private const val MAX_PIXEL_BYTES = 48 * 1024 * 1024
    private const val MAX_MEDIA_BYTES = 48 * 1024 * 1024

    fun isPngHeader(bytes: ByteArray, length: Int = bytes.size): Boolean =
        length >= pngSignature.size && pngSignature.indices.all { bytes[it] == pngSignature[it] }

    /** Returns null for an ordinary PNG or any other unrecognized payload. */
    @Throws(IOException::class)
    fun decode(bytes: ByteArray): ByteArray? {
        if (!isPngHeader(bytes) || bytes.size > MAX_INPUT_BYTES) return null
        val pixels = decodeRgbPixels(bytes) ?: return null
        if (pixels.size < 12 || !wrapperSignature.indices.all { pixels[it] == wrapperSignature[it] })
            return null
        val length = unsignedInt(pixels, 8)
        if (length < 10 || length > pixels.size - 12 || length > MAX_INPUT_BYTES) return null
        if (pixels[12] != 0x1f.toByte() || pixels[13] != 0x8b.toByte()) return null
        val media = GZIPInputStream(ByteArrayInputStream(pixels, 12, length)).use {
            readBounded(it, MAX_MEDIA_BYTES)
        }
        // Keep unknown image payloads out of the HLS extractor. The recognized
        // wrapper carries MPEG-TS, which starts with sync bytes every 188 bytes.
        if (media.size < 188 || media[0] != 0x47.toByte() ||
            (media.size > 188 && media[188] != 0x47.toByte())) return null
        return media
    }

    private fun decodeRgbPixels(png: ByteArray): ByteArray? {
        var offset = pngSignature.size
        var width = 0
        var height = 0
        val idat = ByteArrayOutputStream()
        var ended = false
        while (offset <= png.size - 12) {
            val lengthLong = unsignedInt(png, offset).toLong() and 0xffffffffL
            if (lengthLong > png.size - offset - 12) return null
            val length = lengthLong.toInt()
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            val dataOffset = offset + 8
            when (type) {
                "IHDR" -> {
                    if (length != 13 || width != 0) return null
                    width = unsignedInt(png, dataOffset)
                    height = unsignedInt(png, dataOffset + 4)
                    // 8-bit RGB, normal PNG filters, no interlace.
                    if (png[dataOffset + 8] != 8.toByte() ||
                        png[dataOffset + 9] != 2.toByte() ||
                        png[dataOffset + 10] != 0.toByte() ||
                        png[dataOffset + 11] != 0.toByte() ||
                        png[dataOffset + 12] != 0.toByte()) return null
                    if (width <= 0 || height <= 0 ||
                        width.toLong() * height * 3 > MAX_PIXEL_BYTES) return null
                }
                "IDAT" -> {
                    if (width == 0 || idat.size() + length > MAX_INPUT_BYTES) return null
                    idat.write(png, dataOffset, length)
                }
                "IEND" -> { ended = true; break }
            }
            offset += length + 12
        }
        if (!ended || idat.size() == 0) return null
        val stride = width * 3
        val expected = (stride.toLong() + 1) * height
        if (expected > MAX_PIXEL_BYTES + height.toLong()) return null
        val filtered = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use {
            readBounded(it, expected.toInt())
        }
        if (filtered.size != expected.toInt()) return null
        val pixels = ByteArray(stride * height)
        var source = 0
        for (row in 0 until height) {
            val filter = filtered[source++].toInt() and 0xff
            if (filter > 4) return null
            val start = row * stride
            for (column in 0 until stride) {
                val value = filtered[source++].toInt() and 0xff
                val left = if (column >= 3) pixels[start + column - 3].toInt() and 0xff else 0
                val above = if (row > 0) pixels[start + column - stride].toInt() and 0xff else 0
                val upperLeft = if (row > 0 && column >= 3)
                    pixels[start + column - stride - 3].toInt() and 0xff else 0
                val predictor = when (filter) {
                    1 -> left
                    2 -> above
                    3 -> (left + above) / 2
                    4 -> paeth(left, above, upperLeft)
                    else -> 0
                }
                pixels[start + column] = (value + predictor).toByte()
            }
        }
        return pixels
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun paeth(left: Int, above: Int, upperLeft: Int): Int {
        val prediction = left + above - upperLeft
        val leftDistance = kotlin.math.abs(prediction - left)
        val aboveDistance = kotlin.math.abs(prediction - above)
        val cornerDistance = kotlin.math.abs(prediction - upperLeft)
        return if (leftDistance <= aboveDistance && leftDistance <= cornerDistance) left
            else if (aboveDistance <= cornerDistance) above else upperLeft
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            val count = input.read(block)
            if (count < 0) return output.toByteArray()
            if (output.size() > limit - count) throw IOException("Media segment exceeds size limit")
            output.write(block, 0, count)
        }
    }
}
