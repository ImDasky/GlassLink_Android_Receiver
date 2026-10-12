package com.glasslink.receiver

import java.net.URI

/** The plain MPEG-TS subset of live HLS that can be recorded without changing media semantics. */
internal object HlsDvrPlaylist {
    data class Segment(
        val sourceSequence: Long,
        val sourceUrl: String,
        val durationTag: String,
        val programDateTimeTag: String?,
        val discontinuity: Boolean
    )

    data class Snapshot(val targetDuration: Int, val segments: List<Segment>)

    fun parse(baseUrl: String, source: String): Snapshot? {
        val lines = source.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U" || lines.size > 4096) return null
        // Encrypted, byte-range, fragmented MP4, and low-latency part playlists
        // need more state than a simple segment recorder can safely preserve.
        if (lines.any {
                it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-MAP:") ||
                    it.startsWith("#EXT-X-BYTERANGE:") || it.startsWith("#EXT-X-PART:") ||
                    it.startsWith("#EXT-X-ENDLIST") ||
                    (it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE"))
            }) return null
        val sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.substringAfter(':')?.toLongOrNull() ?: 0L
        val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
            ?.substringAfter(':')?.toIntOrNull()?.takeIf { it in 1..120 } ?: return null
        val base = try { URI(baseUrl) } catch (_: Exception) { return null }
        val segments = ArrayList<Segment>()
        var duration: String? = null
        var date: String? = null
        var discontinuity = false
        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXTINF:") -> duration = line
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> date = line
                line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
                line.startsWith('#') -> Unit
                else -> {
                    val tag = duration ?: return null
                    val url = try { base.resolve(line).toString() } catch (_: Exception) { return null }
                    if (!ReceiverContract.validURL(url)) return null
                    segments += Segment(sequence + segments.size, url, tag, date, discontinuity)
                    duration = null
                    date = null
                    discontinuity = false
                }
            }
        }
        return if (segments.isNotEmpty() && duration == null) Snapshot(target, segments) else null
    }

    fun render(targetDuration: Int, firstSequence: Long, segments: List<Pair<Segment, String>>): String =
        buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(firstSequence).append('\n')
            var previousOriginal: Long? = null
            for ((segment, localUrl) in segments) {
                if (segment.discontinuity || previousOriginal?.let {
                        segment.sourceSequence != it + 1
                    } == true) append("#EXT-X-DISCONTINUITY\n")
                segment.programDateTimeTag?.let { append(it).append('\n') }
                append(segment.durationTag).append('\n')
                append(localUrl).append('\n')
                previousOriginal = segment.sourceSequence
            }
        }
}
