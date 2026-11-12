package com.example.screenrecorder.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/**
 * Pembungkus [MediaMuxer] yang aman dipakai dua encoder dari thread berbeda.
 *
 * MediaMuxer tidak boleh di-start sebelum semua track ditambahkan, dan
 * writeSampleData tidak thread-safe. Kelas ini menyerialkan akses dan menahan
 * penulisan sampai jumlah track yang diharapkan sudah terdaftar.
 */
class MuxerController(
    outputPath: String,
    private val expectedTracks: Int,
) {
    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()
    private var addedTracks = 0
    private var started = false
    private var stopped = false

    /** True setelah semua track terdaftar dan muxer siap menerima sample. */
    val isStarted: Boolean get() = synchronized(lock) { started }

    fun addTrack(format: MediaFormat): Int = synchronized(lock) {
        check(!started) { "Tidak bisa menambah track setelah muxer start" }
        val index = muxer.addTrack(format)
        addedTracks++
        if (addedTracks == expectedTracks) {
            muxer.start()
            started = true
            Log.d(TAG, "Muxer start dengan $addedTracks track")
        }
        index
    }

    fun writeSample(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started || stopped) return
            if (info.size <= 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            runCatching { muxer.writeSampleData(trackIndex, buffer, info) }
                .onFailure { Log.e(TAG, "writeSampleData gagal", it) }
        }
    }

    /** Tutup muxer. Aman dipanggil berulang. */
    fun release() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            if (started) {
                runCatching { muxer.stop() }.onFailure { Log.e(TAG, "muxer.stop gagal", it) }
            }
            runCatching { muxer.release() }.onFailure { Log.e(TAG, "muxer.release gagal", it) }
        }
    }

    private companion object {
        const val TAG = "MuxerController"
    }
}
