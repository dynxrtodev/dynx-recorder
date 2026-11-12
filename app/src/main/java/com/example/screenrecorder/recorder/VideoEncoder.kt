package com.example.screenrecorder.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Meng-encode frame dari [Surface] (diisi oleh VirtualDisplay) menjadi H.264.
 *
 * Encoder berjalan di thread sendiri yang menarik buffer keluaran dan
 * menyerahkannya ke [MuxerController].
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val muxer: MuxerController,
    private val clock: RecordingClock,
    private val onError: (Throwable) -> Unit,
) {
    private val codec: MediaCodec
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var trackIndex = -1

    /** Surface tempat VirtualDisplay menggambar. */
    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Keyframe tiap 1 detik: seeking cepat dan kerusakan file lebih terbatas.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            }
        }
        codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        codec.start()
        thread = Thread({ drainLoop() }, "VideoEncoder").also { it.start() }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (trackIndex < 0) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                        }
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && trackIndex >= 0) {
                            // Buang frame yang jatuh pada periode pause, dan
                            // geser timestamp agar tidak ada jeda di file hasil.
                            val adjusted = clock.adjust(info.presentationTimeUs)
                            if (adjusted != null) {
                                info.presentationTimeUs = adjusted
                                muxer.writeSample(trackIndex, buffer, info)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Log.e(TAG, "Video encoder error", t)
                onError(t)
            }
        }
    }

    /** Minta encoder mengeluarkan keyframe (dipakai saat resume dari pause). */
    fun requestKeyFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        runCatching {
            codec.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { codec.signalEndOfInputStream() }
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    private companion object {
        const val TAG = "VideoEncoder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val TIMEOUT_US = 10_000L
        const val STOP_TIMEOUT_MS = 2_000L
    }
}
