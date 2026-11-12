package com.example.screenrecorder.recorder

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.screenrecorder.model.RecordConfig
import java.io.File

/** Status rekaman yang diobservasi UI dan notifikasi. */
sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val startedAtRealtime: Long) : RecorderState
    data class Paused(val elapsedMs: Long) : RecorderState
    data class Finished(val uri: Uri?, val durationMs: Long) : RecorderState
    data class Failed(val message: String) : RecorderState
}

/**
 * Orkestrator rekaman: MediaProjection -> VirtualDisplay -> VideoEncoder,
 * dan (opsional) mic/internal -> AudioEncoder, keduanya ke satu MuxerController.
 */
class ScreenRecorder(
    private val context: Context,
    private val onStateChange: (RecorderState) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val store = RecordingStore(context)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var muxer: MuxerController? = null
    private var clock: RecordingClock? = null
    private var tempFile: File? = null
    private var config: RecordConfig = RecordConfig()

    @Volatile
    private var active = false

    val isActive: Boolean get() = active
    val isPaused: Boolean get() = clock?.isPaused == true

    /** Durasi efektif rekaman dalam milidetik (tidak termasuk pause). */
    fun elapsedMs(): Long =
        clock?.elapsedUs(SystemClock.elapsedRealtimeNanos() / 1000)?.div(1000) ?: 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User menghentikan screen-sharing dari panel sistem.
            Log.d(TAG, "MediaProjection dihentikan sistem")
            mainHandler.post { stop() }
        }
    }

    /**
     * Mulai merekam. [resultCode] dan [data] berasal dari
     * onActivityResult createScreenCaptureIntent.
     */
    fun start(resultCode: Int, data: Intent, config: RecordConfig) {
        if (active) {
            Log.w(TAG, "start() diabaikan, rekaman sudah aktif")
            return
        }
        this.config = config
        store.cleanupStaleTempFiles()

        try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val proj = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection tidak diberikan")
            projection = proj
            proj.registerCallback(projectionCallback, mainHandler)

            val (screenW, screenH, density) = screenMetrics()
            val (width, height) = config.resolveSize(screenW, screenH)
            val bitrate = config.resolveVideoBitrate(width, height)
            Log.d(TAG, "Rekam ${width}x$height @ ${config.fps}fps, ${bitrate / 1000}kbps")

            val recClock = RecordingClock().also { clock = it }
            val temp = store.createTempFile().also { tempFile = it }

            // Audio internal butuh MediaProjection yang sama; cek dukungan OS dulu.
            val wantAudio = config.audioMode.needsAnyAudio &&
                (!config.audioMode.needsPlayback || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

            val onErr: (Throwable) -> Unit = { t ->
                mainHandler.post { fail(t.message ?: "Encoder gagal") }
            }

            // Siapkan sumber audio SEBELUM membuat muxer: jumlah track harus
            // sudah pasti saat MuxerController dikonstruksi.
            var audio: AudioEncoder? = null
            if (wantAudio) {
                val candidate = AudioEncoder(config, proj, onErr)
                try {
                    candidate.prepare()
                    audio = candidate
                } catch (t: Throwable) {
                    Log.w(TAG, "Audio tidak tersedia, rekam video saja", t)
                    candidate.stop()
                }
            }
            audioEncoder = audio

            val mux = MuxerController(
                temp.absolutePath,
                if (audio != null) 2 else 1,
            ).also { muxer = it }

            audio?.attach(mux, recClock)

            val video = VideoEncoder(
                width = width,
                height = height,
                bitrate = bitrate,
                fps = config.fps,
                muxer = mux,
                clock = recClock,
                onError = onErr,
            ).also { videoEncoder = it }

            virtualDisplay = proj.createVirtualDisplay(
                "ScreenRecorder",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                video.inputSurface,
                null,
                mainHandler,
            ) ?: throw IllegalStateException("VirtualDisplay gagal dibuat")

            video.start()
            audio?.start()

            active = true
            onStateChange(RecorderState.Recording(SystemClock.elapsedRealtime()))
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal memulai rekaman", t)
            releaseAll()
            onStateChange(RecorderState.Failed(t.message ?: "Gagal memulai rekaman"))
        }
    }

    fun pause() {

        if (!active) return
        val c = clock ?: return
        if (c.isPaused) return
        c.pause(SystemClock.elapsedRealtimeNanos() / 1000)
        onStateChange(RecorderState.Paused(elapsedMs()))
    }

    fun resume() {
        if (!active) return
        val c = clock ?: return
        if (!c.isPaused) return
        c.resume(SystemClock.elapsedRealtimeNanos() / 1000)
        // Keyframe baru supaya frame pertama setelah resume tidak bergantung
        // pada referensi dari sebelum pause.
        videoEncoder?.requestKeyFrame()
        onStateChange(RecorderState.Recording(SystemClock.elapsedRealtime()))
    }

    fun stop() {
        if (!active) return
        active = false
        // Durasi harus diambil SEBELUM releaseAll() menihilkan clock.
        val duration = elapsedMs()
        val temp = tempFile
        tempFile = null

        releaseAll()
        publishAsync(temp, duration, fallbackMessage = null)
    }

    private fun fail(message: String) {
        if (!active) return
        active = false
        val duration = elapsedMs()
        val temp = tempFile
        tempFile = null

        releaseAll()
        // Tetap coba selamatkan apa yang sudah terekam.
        publishAsync(temp?.takeIf { it.length() > 0 }, duration, fallbackMessage = message)
    }

    /**
     * Pindahkan rekaman ke galeri di thread terpisah.
     *
     * Menyalin file video bisa memakan ratusan MB; melakukannya di main thread
     * akan memicu ANR tepat saat user menekan stop.
     */
    private fun publishAsync(temp: File?, durationMs: Long, fallbackMessage: String?) {
        if (temp == null) {
            onStateChange(
                if (fallbackMessage != null) {
                    RecorderState.Failed(fallbackMessage)
                } else {
                    RecorderState.Finished(null, durationMs)
                }
            )
            return
        }
        Thread({
            val uri = store.publish(temp, store.displayName())
            mainHandler.post {
                onStateChange(
                    if (uri == null && fallbackMessage != null) {
                        RecorderState.Failed(fallbackMessage)
                    } else {
                        RecorderState.Finished(uri, durationMs)
                    }
                )
            }
        }, "PublishRecording").start()
    }

    private fun releaseAll() {
        // Urutan penting: hentikan sumber frame dulu, lalu encoder, lalu muxer.
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { audioEncoder?.stop() }
        audioEncoder = null

        runCatching { videoEncoder?.stop() }
        videoEncoder = null

        runCatching { muxer?.release() }
        muxer = null

        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        projection = null
        clock = null
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val density = context.resources.configuration.densityDpi
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private companion object {
        const val TAG = "ScreenRecorder"
    }
}
