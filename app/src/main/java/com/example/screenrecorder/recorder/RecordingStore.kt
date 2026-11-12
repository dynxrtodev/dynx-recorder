package com.example.screenrecorder.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mengurus lokasi file hasil rekaman.
 *
 * MediaMuxer butuh path file biasa, sedangkan Android 10+ membatasi tulis
 * langsung ke penyimpanan bersama. Jadi rekaman ditulis ke cache aplikasi
 * lalu dipindahkan ke MediaStore setelah selesai.
 */
class RecordingStore(private val context: Context) {

    fun createTempFile(): File {
        val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
        return File(dir, "rec_${System.currentTimeMillis()}.mp4")
    }

    fun displayName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "ScreenRec_$stamp.mp4"
    }

    /**
     * Pindahkan [temp] ke galeri publik. Mengembalikan Uri hasil, atau null
     * bila gagal (file sementara tetap dibiarkan agar data tidak hilang).
     */
    fun publish(temp: File, name: String): Uri? {
        if (!temp.exists() || temp.length() == 0L) {
            Log.w(TAG, "File sementara kosong, tidak dipublikasikan")
            return null
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$ALBUM",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()

        if (uri == null) {
            Log.e(TAG, "Gagal membuat entri MediaStore")
            return null
        }

        val copied = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(temp).use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            } ?: throw IllegalStateException("OutputStream null")
        }

        if (copied.isFailure) {
            Log.e(TAG, "Gagal menyalin rekaman ke MediaStore", copied.exceptionOrNull())
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
        }

        temp.delete()
        return uri
    }

    /** Bersihkan file sementara dari sesi yang gagal/crash sebelumnya. */
    fun cleanupStaleTempFiles() {
        val dir = File(context.cacheDir, "recordings")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - STALE_AGE_MS
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    private companion object {
        const val TAG = "RecordingStore"
        const val ALBUM = "ScreenRecorder"
        const val STALE_AGE_MS = 24L * 60 * 60 * 1000
    }
}
