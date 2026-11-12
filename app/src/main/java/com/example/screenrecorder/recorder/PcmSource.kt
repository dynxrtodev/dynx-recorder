package com.example.screenrecorder.recorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

/**
 * Satu sumber PCM 16-bit. Selalu menghasilkan frame stereo interleaved
 * supaya mixer tidak perlu tahu channel asli perangkat.
 */
class PcmSource private constructor(
    private val record: AudioRecord,
    private val channelCount: Int,
    private val label: String,
) {
    /** Buffer baca mentah, dipakai ulang agar tidak alokasi per iterasi. */
    private var rawBuffer = ShortArray(0)

    fun start() {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord '$label' gagal start")
        }
    }

    /**
     * Baca sampai [stereoFrames] frame stereo ke dalam [out] (interleaved L,R).
     * Mengembalikan jumlah short yang terisi. Sisa [out] di-nol-kan.
     */
    fun readStereo(out: ShortArray, stereoFrames: Int): Int {
        val wanted = stereoFrames * channelCount
        if (rawBuffer.size < wanted) rawBuffer = ShortArray(wanted)

        val read = record.read(rawBuffer, 0, wanted)
        if (read <= 0) {
            out.fill(0)
            return 0
        }

        if (channelCount == 2) {
            System.arraycopy(rawBuffer, 0, out, 0, read)
            if (read < out.size) out.fill(0, read, out.size)
            return read
        }

        // Mono -> stereo: duplikasi tiap sample ke kedua kanal.
        var o = 0
        for (i in 0 until read) {
            out[o++] = rawBuffer[i]
            out[o++] = rawBuffer[i]
        }
        if (o < out.size) out.fill(0, o, out.size)
        return o
    }

    fun release() {
        runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        runCatching { record.release() }
    }

    companion object {
        private const val TAG = "PcmSource"

        /** Mikrofon. Memerlukan izin RECORD_AUDIO. */
        @SuppressLint("MissingPermission")
        fun microphone(sampleRate: Int): PcmSource {
            // Coba stereo dulu; banyak perangkat hanya mendukung mono.
            for (channels in intArrayOf(2, 1)) {
                val mask = if (channels == 2) {
                    AudioFormat.CHANNEL_IN_STEREO
                } else {
                    AudioFormat.CHANNEL_IN_MONO
                }
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) continue
                val record = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        mask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * BUFFER_FACTOR,
                    )
                }.getOrNull() ?: continue
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return PcmSource(record, channels, "mic")
                }
                record.release()
            }
            throw IllegalStateException("Mikrofon tidak tersedia pada $sampleRate Hz")
        }

        /**
         * Audio internal lewat MediaProjection. Hanya bisa menangkap aplikasi
         * yang usage-nya MEDIA/GAME/UNKNOWN dan tidak menandai dirinya
         * sebagai tidak-boleh-direkam.
         */
        @SuppressLint("MissingPermission")
        fun internalPlayback(projection: MediaProjection, sampleRate: Int): PcmSource {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Audio internal butuh Android 10 (API 29) atau lebih baru"
            }
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * BUFFER_FACTOR)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("Audio internal gagal diinisialisasi")
            }
            Log.d(TAG, "Audio internal siap pada $sampleRate Hz stereo")
            return PcmSource(record, 2, "internal")
        }

        private const val BUFFER_FACTOR = 4
    }
}
