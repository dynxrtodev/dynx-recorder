package com.example.screenrecorder.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecInfo
import android.media.projection.MediaProjection
import android.util.Log
import com.example.screenrecorder.model.AudioSourceMode
import com.example.screenrecorder.model.RecordConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Membaca satu atau dua sumber PCM, me-mix-nya, lalu meng-encode ke AAC.
 *
 * Mixing dilakukan di domain Int lalu di-clamp ke rentang Short agar tidak
 * terjadi wrap-around yang terdengar sebagai bunyi pecah/klik.
 */
class AudioEncoder(
    private val config: RecordConfig,
    private val projection: MediaProjection?,
    private val onError: (Throwable) -> Unit,
) {
    private val codec: MediaCodec
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var trackIndex = -1

    private var muxer: MuxerController? = null
    private var clock: RecordingClock? = null

    private var micSource: PcmSource? = null
    private var internalSource: PcmSource? = null


    /** Jumlah sample (bukan frame) yang sudah dikirim ke encoder. */
    private var sentSamples = 0L

    init {
        val format = MediaFormat.createAudioFormat(
            MIME, config.audioSampleRate, CHANNEL_COUNT,
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES_PER_CHUNK * CHANNEL_COUNT * 2)
        }
        codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    /** Siapkan sumber audio. Lempar exception bila sumber wajib tidak tersedia. */
    fun prepare() {
        if (config.audioMode.needsMic) {
            micSource = PcmSource.microphone(config.audioSampleRate)
        }
        if (config.audioMode.needsPlayback) {
            val proj = projection
                ?: throw IllegalStateException("MediaProjection wajib untuk audio internal")
            try {
                internalSource = PcmSource.internalPlayback(proj, config.audioSampleRate)
            } catch (t: Throwable) {
                // Kalau mode gabungan, mic saja masih berguna — jangan gagalkan rekaman.
                if (config.audioMode == AudioSourceMode.MIC_AND_INTERNAL) {
                    Log.w(TAG, "Audio internal gagal, lanjut dengan mikrofon saja", t)
                } else {
                    throw t
                }
            }
        }
        if (micSource == null && internalSource == null) {
            throw IllegalStateException("Tidak ada sumber audio yang bisa dipakai")
        }
    }

    /**
     * Hubungkan ke muxer dan clock. Dipanggil setelah [prepare] berhasil,
     * karena jumlah track muxer baru pasti setelah sumber audio terbukti ada.
     */
    fun attach(muxer: MuxerController, clock: RecordingClock) {
        this.muxer = muxer
        this.clock = clock
    }

    fun start() {
        if (muxer == null || clock == null) {
            throw IllegalStateException("attach() harus dipanggil sebelum start()")
        }
        if (!running.compareAndSet(false, true)) return
        codec.start()
        micSource?.start()
        internalSource?.start()
        thread = Thread({ captureLoop() }, "AudioEncoder").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    private fun captureLoop() {
        val chunkShorts = FRAMES_PER_CHUNK * CHANNEL_COUNT
        val micBuf = ShortArray(chunkShorts)
        val intBuf = ShortArray(chunkShorts)
        val mixed = ShortArray(chunkShorts)
        val byteBuf = ByteArray(chunkShorts * 2)
        val info = MediaCodec.BufferInfo()

        val mic = micSource
        val internal = internalSource
        val micGain = config.micGain
        val intGain = config.internalGain
        val recClock = clock ?: return

        try {
            while (running.get()) {
                val micRead = mic?.readStereo(micBuf, FRAMES_PER_CHUNK) ?: 0
                val intRead = internal?.readStereo(intBuf, FRAMES_PER_CHUNK) ?: 0

                if (recClock.isPaused) {
                    // Tetap kuras sumber audio agar buffer driver tidak meluap,
                    // tapi jangan kirim apa pun ke encoder.
                    continue
                }

                if (micRead <= 0 && intRead <= 0) continue

                when {
                    mic != null && internal != null -> {
                        for (i in 0 until chunkShorts) {
                            val sum = (micBuf[i] * micGain).roundToInt() +
                                (intBuf[i] * intGain).roundToInt()
                            mixed[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                .toShort()
                        }
                    }

                    mic != null -> applyGain(micBuf, mixed, micGain, chunkShorts)
                    else -> applyGain(intBuf, mixed, intGain, chunkShorts)
                }

                // PCM 16-bit little endian.
                var b = 0
                for (i in 0 until chunkShorts) {
                    val s = mixed[i].toInt()
                    byteBuf[b++] = (s and 0xFF).toByte()
                    byteBuf[b++] = ((s shr 8) and 0xFF).toByte()
                }

                feedEncoder(byteBuf, chunkShorts * 2)
                drainEncoder(info, endOfStream = false)
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Log.e(TAG, "Audio encoder error", t)
                onError(t)
            }
        } finally {
            runCatching {
                feedEndOfStream()
                drainEncoder(info, endOfStream = true)
            }
        }
    }

    private fun applyGain(src: ShortArray, dst: ShortArray, gain: Float, count: Int) {
        if (gain == 1.0f) {
            System.arraycopy(src, 0, dst, 0, count)
            return
        }
        for (i in 0 until count) {
            dst[i] = (src[i] * gain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun feedEncoder(data: ByteArray, size: Int) {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(data, 0, size)
        // Timestamp dihitung dari jumlah sample, bukan jam sistem: ini menjaga
        // audio tetap sinkron walau thread sempat tersendat.
        val ptsUs = sentSamples * 1_000_000L / (config.audioSampleRate.toLong() * CHANNEL_COUNT)
        sentSamples += size / 2
        codec.queueInputBuffer(index, 0, size, ptsUs, 0)
    }

    private fun feedEndOfStream() {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val ptsUs = sentSamples * 1_000_000L / (config.audioSampleRate.toLong() * CHANNEL_COUNT)
        codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drainEncoder(info: MediaCodec.BufferInfo, endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val mux = muxer
                    if (trackIndex < 0 && mux != null) trackIndex = mux.addTrack(codec.outputFormat)
                }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val mux = muxer
                    if (buffer != null && mux != null && trackIndex >= 0) {
                        mux.writeSample(trackIndex, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
        micSource?.release()
        internalSource?.release()
        micSource = null
        internalSource = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val TAG = "AudioEncoder"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val CHANNEL_COUNT = 2
        /** 1024 frame = satu AAC frame; 2 chunk menjaga latensi tetap rendah. */
        const val FRAMES_PER_CHUNK = 1024
        const val TIMEOUT_US = 10_000L
        const val STOP_TIMEOUT_MS = 2_000L
    }
}
