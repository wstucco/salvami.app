package org.salvami.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Does the little ffmpeg used to do for yt-dlp, with Android's own MediaExtractor/MediaMuxer
 * (no re-encoding): merges separate video and audio streams, and rewraps MPEG-TS from HLS as MP4.
 */
object Muxer {
    /** yt-dlp names unmerged streams "<title> [<id>].f<format>.<ext>". */
    private val PART = Regex("""^(.*)\.f[^.]+\.([A-Za-z0-9]+)$""")
    private val VIDEO_EXT = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp")

    /** Turns what yt-dlp left in [dir] into one playable file per video, named like its .info.json. */
    fun finish(dir: File) {
        val parts = dir.listFiles().orEmpty().filter { it.isFile && PART.matches(it.name) }
        parts.groupBy { PART.find(it.name)!!.groupValues[1] }.forEach { (base, files) ->
            if (files.size == 1) {
                val f = files[0]
                f.renameTo(File(dir, "$base.${f.extension}"))
            } else {
                val webm = files.all { it.extension.equals("webm", ignoreCase = true) }
                val out = File(dir, "$base.${if (webm) "webm" else "mp4"}")
                mux(files, out, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                files.forEach { it.delete() }
            }
        }
        dir.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in VIDEO_EXT && isMpegTs(it) }.forEach { ts ->
            val tmp = File(dir, ts.nameWithoutExtension + ".remux.mp4")
            mux(listOf(ts), tmp, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            ts.delete()
            tmp.renameTo(File(dir, ts.nameWithoutExtension + ".mp4"))
        }
    }

    /** HLS fragments are MPEG-TS: 188-byte packets starting with the 0x47 sync byte. */
    private fun isMpegTs(f: File): Boolean {
        if (f.length() < 189) return false
        val b = ByteArray(189)
        f.inputStream().use { if (it.read(b) < b.size) return false }
        return b[0] == 0x47.toByte() && b[188] == 0x47.toByte()
    }

    /** Copies the first video and the first audio track found in [inputs] into [out]. */
    private fun mux(inputs: List<File>, out: File, format: Int) {
        class Track(val extractor: MediaExtractor, val index: Int, val format: MediaFormat)

        val extractors = inputs.map { f -> MediaExtractor().apply { setDataSource(f.absolutePath) } }
        val muxer = MediaMuxer(out.absolutePath, format)
        try {
            val tracks = mutableListOf<Track>()
            for (kind in listOf("video/", "audio/")) {
                found@ for (ex in extractors) {
                    for (i in 0 until ex.trackCount) {
                        val fmt = ex.getTrackFormat(i)
                        if (fmt.getString(MediaFormat.KEY_MIME).orEmpty().startsWith(kind)) {
                            tracks += Track(ex, i, fmt)
                            break@found
                        }
                    }
                }
            }
            require(tracks.isNotEmpty()) { "no audio or video track in ${inputs.joinToString { it.name }}" }

            tracks.firstOrNull { it.format.containsKey(MediaFormat.KEY_ROTATION) }
                ?.let { muxer.setOrientationHint(it.format.getInteger(MediaFormat.KEY_ROTATION)) }
            val outIndex = tracks.map { muxer.addTrack(it.format) }
            tracks.forEach { it.extractor.selectTrack(it.index) }

            // HLS streams rarely start at 0: shift every track by the earliest timestamp.
            val start = tracks.minOf { t ->
                t.extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                t.extractor.sampleTime.takeIf { it >= 0 } ?: 0L
            }

            muxer.start()
            val buffer = ByteBuffer.allocate(
                tracks.maxOf { if (it.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
                    .coerceAtLeast(4 * 1024 * 1024)
            )
            val info = MediaCodec.BufferInfo()
            tracks.forEachIndexed { n, t ->
                val ex = t.extractor
                // Two tracks can share one extractor (TS): read only this one.
                for (i in 0 until ex.trackCount) if (i != t.index) runCatching { ex.unselectTrack(i) }
                ex.selectTrack(t.index)
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    info.size = ex.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    info.offset = 0
                    info.presentationTimeUs = (ex.sampleTime - start).coerceAtLeast(0)
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(outIndex[n], buffer, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } catch (e: Throwable) {
            out.delete()
            throw IllegalStateException("Could not merge ${inputs.joinToString { it.name }}: ${e.message}", e)
        } finally {
            runCatching { muxer.release() }
            extractors.forEach { it.release() }
        }
    }
}
