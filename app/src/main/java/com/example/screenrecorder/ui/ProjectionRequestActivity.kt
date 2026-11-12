package com.example.screenrecorder.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.screenrecorder.service.FloatingControlService
import com.example.screenrecorder.service.ScreenRecordService

/**
 * Activity transparan tanpa UI yang satu-satunya tugasnya meminta izin
 * tangkap layar, lalu langsung menutup diri.
 *
 * Dibutuhkan karena createScreenCaptureIntent() harus dipanggil dari Activity,
 * sementara rekaman dikendalikan dari service/floating button.
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenRecordService.start(this, result.resultCode, data)
            startFloatingButtonIfEnabled()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    /**
     * Saat rekaman dimulai dari tile, tombol mengambang jadi satu-satunya
     * kontrol yang praktis — notification shade sendiri ikut terekam.
     *
     * Service yang memeriksa preferensi useFloatingButton, bukan Activity ini,
     * karena Activity langsung finish() dan coroutine-nya akan dibatalkan.
     */
    private fun startFloatingButtonIfEnabled() {
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
        if (!canOverlay) return
        startService(Intent(this, FloatingControlService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Jangan minta izin dua kali setelah rotasi.
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        launcher.launch(manager.createScreenCaptureIntent())
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ProjectionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
