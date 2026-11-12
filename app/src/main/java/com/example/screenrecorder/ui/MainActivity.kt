package com.example.screenrecorder.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.screenrecorder.R
import com.example.screenrecorder.service.FloatingControlService
import com.example.screenrecorder.service.ScreenRecordService

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Hasil dialog izin tangkap layar dari sistem. */
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenRecordService.start(this, result.resultCode, data)
            maybeStartFloatingButton()
        } else {
            Toast.makeText(this, R.string.permission_projection_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.permission_mic_denied, Toast.LENGTH_LONG).show()
        }
        // Rekaman tetap lanjut; AudioEncoder akan fallback ke video-saja bila perlu.
        requestProjection()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notifikasi hanya untuk kenyamanan; abaikan hasilnya. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            ScreenRecorderTheme {
                RecorderScreen(
                    viewModel = viewModel,
                    onStartClick = ::onStartRequested,
                    onStopClick = {
                        ScreenRecordService.send(this, ScreenRecordService.ACTION_STOP)
                    },
                    onPauseClick = {
                        ScreenRecordService.send(this, ScreenRecordService.ACTION_PAUSE)
                    },
                    onResumeClick = {
                        ScreenRecordService.send(this, ScreenRecordService.ACTION_RESUME)
                    },
                    onOpenOverlaySettings = ::openOverlaySettings,
                    onOpenGallery = ::openGallery,
                )
            }
        }
    }

    /**
     * Alur mulai: minta izin mikrofon (kalau perlu) -> izin tangkap layar -> service.
     */
    private fun onStartRequested() {
        val mode = viewModel.config.value.audioMode
        if (mode.needsMic && !hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        // Audio internal juga butuh RECORD_AUDIO di banyak perangkat.
        if (mode.needsPlayback && !hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun maybeStartFloatingButton() {
        // Service sendiri yang memeriksa preferensi useFloatingButton.
        if (!canDrawOverlays()) return
        startService(Intent(this, FloatingControlService::class.java))
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "video/*"
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, R.string.no_gallery_app, Toast.LENGTH_SHORT).show()
            }
    }
}
