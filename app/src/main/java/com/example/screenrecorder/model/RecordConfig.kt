package com.example.screenrecorder.model

/** Sumber audio yang direkam bersama video. */
enum class AudioSourceMode {
    /** Tanpa audio. */
    NONE,

    /** Hanya mikrofon. */
    MIC,

    /** Hanya audio internal aplikasi lain (butuh Android 10+). */
    INTERNAL,

    /** Mikrofon + audio internal, di-mix jadi satu track (butuh Android 10+). */
    MIC_AND_INTERNAL;

    val needsMic: Boolean get() = this == MIC || this == MIC_AND_INTERNAL
    val needsPlayback: Boolean get() = this == INTERNAL || this == MIC_AND_INTERNAL
    val needsAnyAudio: Boolean get() = this != NONE
}

/** Preset resolusi. Nilai dipakai sebagai tinggi target pada orientasi portrait. */
enum class VideoQuality(val shortEdge: Int, val label: String) {
    P480(480, "480p"),
    P720(720, "720p HD"),
    P1080(1080, "1080p Full HD"),
    P1440(1440, "1440p QHD"),
    NATIVE(0, "Native (sesuai layar)");
}

data class RecordConfig(
    val quality: VideoQuality = VideoQuality.P1080,
    val fps: Int = 60,
    /** Bitrate video dalam bit per detik. 0 = hitung otomatis dari resolusi & fps. */
    val videoBitrate: Int = 0,
    val audioMode: AudioSourceMode = AudioSourceMode.MIC_AND_INTERNAL,
    val audioBitrate: Int = 192_000,
    val audioSampleRate: Int = 48_000,
    /** Gain mikrofon saat di-mix (1.0 = normal). */
    val micGain: Float = 1.0f,
    /** Gain audio internal saat di-mix (1.0 = normal). */
    val internalGain: Float = 1.0f,
    val useFloatingButton: Boolean = true,
    val countdownSeconds: Int = 3,
    /** Batas durasi dalam detik, 0 = tanpa batas. */
    val maxDurationSeconds: Int = 0,
) {
    /**
     * Hitung ukuran encoder dari ukuran layar sambil menjaga aspect ratio.
     * Hasil selalu genap karena encoder H.264 menolak dimensi ganjil.
     */
    fun resolveSize(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        if (quality == VideoQuality.NATIVE) {
            return align(screenWidth) to align(screenHeight)
        }
        val shortEdge = minOf(screenWidth, screenHeight)
        if (shortEdge <= quality.shortEdge) {
            // Jangan upscale — buang-buang bitrate tanpa menambah detail.
            return align(screenWidth) to align(screenHeight)
        }
        val scale = quality.shortEdge.toFloat() / shortEdge
        return align((screenWidth * scale).toInt()) to align((screenHeight * scale).toInt())
    }

    fun resolveVideoBitrate(width: Int, height: Int): Int {
        if (videoBitrate > 0) return videoBitrate
        // ~0.12 bit per pixel per frame: cukup untuk konten layar yang banyak teks.
        val computed = (width.toLong() * height * fps * 0.12).toLong()
        return computed.coerceIn(2_000_000L, 60_000_000L).toInt()
    }

    private fun align(value: Int): Int = if (value % 2 == 0) value else value - 1
}
