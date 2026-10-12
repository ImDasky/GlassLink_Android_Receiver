package com.glasslink.receiver

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque

/** Records one plain HLS transport stream for the current viewing session. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object LiveDvr {
    private const val FREE_SPACE_RESERVE = 512L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private var directory: File? = null
    private var sourceFactory: DataSource.Factory? = null
    @Volatile private var session: Session? = null

    @Synchronized fun install(cacheDir: File, source: DataSource.Factory) {
        stop()
        val root = File(cacheDir, "glasslink-live-dvr")
        root.deleteRecursively() // Interrupted viewing sessions never become permanent files.
        root.mkdirs()
        directory = root
        sourceFactory = source
    }

    @Synchronized fun start(manifestUrl: String): Boolean {
        stop()
        val root = directory ?: return false
        val factory = sourceFactory ?: return false
        if (!ReceiverContract.validURL(manifestUrl)) return false
        val folder = File(root, SystemClock.elapsedRealtimeNanos().toString())
        if (!folder.mkdirs()) return false
        session = Session(folder, factory, manifestUrl)
        return true
    }

    @Synchronized fun stop() {
        session?.stop()
        session = null
    }

    fun isRecording(): Boolean = session?.isRecording() == true

    fun mayIntercept(url: String): Boolean = session?.mayIntercept(url) == true

    fun transform(url: String, text: String): ByteArray? {
        val current = session ?: return null
        val parsed = HlsDvrPlaylist.parse(url, text) ?: return null
        if (!current.submit(url, parsed)) return null
        val playlist = current.awaitPlaylist(10_000L) ?: return null
        return playlist.toByteArray(Charsets.UTF_8)
    }

    private class Session(
        private val folder: File,
        private val sourceFactory: DataSource.Factory,
        private val initialUrl: String
    ) {
        private data class Stored(val segment: HlsDvrPlaylist.Segment, val file: File)
        private val lock = Object()
        private val pending = ArrayDeque<HlsDvrPlaylist.Segment>()
        private val known = HashSet<String>()
        private val stored = ArrayDeque<Stored>()
        @Volatile private var active = true
        @Volatile private var supported = true
        private var selectedUrl: String? = null
        private var targetDuration = 6
        private var firstSequence = 0L
        private var nextFile = 0L
        private var lastPollAt = 0L
        private val worker = Thread({ runRecorder() }, "GlassLink-live-DVR").apply {
            isDaemon = true
            start()
        }

        fun isRecording(): Boolean = active && supported && selectedUrl != null

        fun mayIntercept(url: String): Boolean = active && supported &&
            (url == initialUrl || Uri.parse(url).path.orEmpty().lowercase().endsWith(".m3u8"))

        fun submit(url: String, playlist: HlsDvrPlaylist.Snapshot): Boolean {
            synchronized(lock) {
                if (!active || !supported) return false
                if (selectedUrl == null) selectedUrl = url
                if (selectedUrl != url) return false
                targetDuration = maxOf(targetDuration, playlist.targetDuration)
                for (segment in playlist.segments) {
                    if (known.add(segment.sourceUrl)) pending.addLast(segment)
                }
                lock.notifyAll()
                return true
            }
        }

        fun awaitPlaylist(waitMs: Long): String? {
            synchronized(lock) {
                val deadline = SystemClock.elapsedRealtime() + waitMs
                while (active && supported && stored.isEmpty()) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    lock.wait(remaining)
                }
                if (!active || !supported || stored.isEmpty()) return null
                return HlsDvrPlaylist.render(targetDuration, firstSequence,
                    stored.map { it.segment to it.file.toURI().toASCIIString() })
            }
        }

        fun stop() {
            active = false
            synchronized(lock) { lock.notifyAll() }
            worker.interrupt()
        }

        private fun runRecorder() {
            try {
                while (active && supported) {
                    val selected = synchronized(lock) { selectedUrl }
                    if (selected == null) {
                        synchronized(lock) { if (active) lock.wait(1000L) }
                        continue
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPollAt >= maxOf(1500L, targetDuration * 500L)) {
                        lastPollAt = now
                        try {
                            val text = String(readAll(selected, MAX_MANIFEST_BYTES), Charsets.UTF_8)
                            HlsDvrPlaylist.parse(selected, text)?.let { submit(selected, it) }
                        } catch (_: IOException) { /* The next poll can recover a transient fetch. */ }
                    }
                    val next = synchronized(lock) { if (pending.isEmpty()) null else pending.removeFirst() }
                    if (next == null) {
                        synchronized(lock) { if (active) lock.wait(750L) }
                    } else {
                        try {
                            save(next)
                        } catch (error: IOException) {
                            Log.w("GlassLink", "Live DVR segment unavailable: ${error.javaClass.simpleName}")
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // A new cast ended this viewing session.
            } finally {
                // A recorder disabled by low storage leaves its already saved
                // files intact until playback actually switches away.
                synchronized(lock) {
                    while (active) {
                        try { lock.wait(1000L) } catch (_: InterruptedException) { break }
                    }
                }
                folder.deleteRecursively()
            }
        }

        private fun save(segment: HlsDvrPlaylist.Segment) {
            if (!active) return
            makeRoom()
            if (folder.usableSpace < FREE_SPACE_RESERVE) {
                supported = false
                synchronized(lock) { lock.notifyAll() }
                Log.w("GlassLink", "Live DVR stopped to preserve TV storage")
                return
            }
            val part = File(folder, "${nextFile++}.part")
            val finished = File(folder, part.name.removeSuffix(".part") + ".ts")
            try {
                val source = sourceFactory.createDataSource()
                try {
                    source.open(DataSpec(Uri.parse(segment.sourceUrl)))
                    FileOutputStream(part).use { output ->
                        val block = ByteArray(8192)
                        var first = true
                        while (active) {
                            val count = source.read(block, 0, block.size)
                            if (count == C.RESULT_END_OF_INPUT) break
                            if (count == 0) continue
                            if (first) {
                                if (block[0] != 0x47.toByte()) {
                                    supported = false
                                    synchronized(lock) { lock.notifyAll() }
                                    throw IOException("HLS segment is not MPEG-TS")
                                }
                                first = false
                            }
                            output.write(block, 0, count)
                        }
                    }
                } finally { source.close() }
                if (!active || !part.renameTo(finished)) throw IOException("DVR file incomplete")
                synchronized(lock) {
                    stored.addLast(Stored(segment, finished))
                    lock.notifyAll()
                }
            } finally { part.delete() }
        }

        private fun makeRoom() {
            synchronized(lock) {
                while (folder.usableSpace < FREE_SPACE_RESERVE && stored.size > 1) {
                    val oldest = stored.removeFirst()
                    oldest.file.delete()
                    firstSequence++
                }
            }
        }

        private fun readAll(url: String, maxBytes: Int): ByteArray {
            val source = sourceFactory.createDataSource()
            try {
                source.open(DataSpec(Uri.parse(url)))
                val output = ByteArrayOutputStream()
                val block = ByteArray(8192)
                while (active) {
                    val count = source.read(block, 0, block.size)
                    if (count == C.RESULT_END_OF_INPUT) return output.toByteArray()
                    if (count == 0) continue
                    if (output.size() > maxBytes - count) throw IOException("HLS playlist too large")
                    output.write(block, 0, count)
                }
                throw IOException("Viewing session ended")
            } finally { source.close() }
        }
    }
}
