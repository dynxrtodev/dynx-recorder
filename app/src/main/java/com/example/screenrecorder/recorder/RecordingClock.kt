package com.example.screenrecorder.recorder

/**
 * Menerjemahkan timestamp mentah encoder menjadi timestamp output yang
 * menghilangkan periode pause.
 *
 * Tanpa ini, pause 10 detik akan menghasilkan video dengan frame beku
 * selama 10 detik karena presentationTimeUs terus berjalan.
 */
class RecordingClock {
    private val lock = Any()

    /** Total durasi yang sudah dibuang karena pause, dalam mikrodetik. */
    private var pausedTotalUs = 0L
    private var pauseStartUs = -1L
    private var baseUs = -1L

    @Volatile
    var isPaused: Boolean = false
        private set

    /**
     * Konversi timestamp mentah ke timestamp output.
     * Mengembalikan null bila frame harus dibuang (jatuh saat pause).
     */
    fun adjust(rawUs: Long): Long? = synchronized(lock) {
        if (baseUs < 0) baseUs = rawUs
        if (pauseStartUs >= 0) {
            // Masih pause: frame ini tidak masuk file.
            return null
        }
        val result = rawUs - baseUs - pausedTotalUs
        if (result < 0) null else result
    }

    fun pause(nowUs: Long) = synchronized(lock) {
        if (pauseStartUs < 0) {
            pauseStartUs = nowUs
            isPaused = true
        }
    }

    fun resume(nowUs: Long) = synchronized(lock) {
        if (pauseStartUs >= 0) {
            pausedTotalUs += (nowUs - pauseStartUs).coerceAtLeast(0L)
            pauseStartUs = -1L
            isPaused = false
        }
    }

    /** Durasi efektif rekaman (tidak termasuk waktu pause) dalam mikrodetik. */
    fun elapsedUs(nowUs: Long): Long = synchronized(lock) {
        if (baseUs < 0) return 0L
        val pausedNow = if (pauseStartUs >= 0) nowUs - pauseStartUs else 0L
        (nowUs - baseUs - pausedTotalUs - pausedNow).coerceAtLeast(0L)
    }
}
