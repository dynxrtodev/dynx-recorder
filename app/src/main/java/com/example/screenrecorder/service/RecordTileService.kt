package com.example.screenrecorder.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.screenrecorder.recorder.RecorderState
import com.example.screenrecorder.ui.ProjectionRequestActivity

/**
 * Tile Quick Settings untuk mulai/stop rekaman tanpa membuka aplikasi.
 *
 * Saat belum merekam, tile membuka [ProjectionRequestActivity] karena
 * createScreenCaptureIntent() hanya boleh dipanggil dari Activity.
 */
@RequiresApi(Build.VERSION_CODES.N)
class RecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val recording = ScreenRecordService.state.value.let {
            it is RecorderState.Recording || it is RecorderState.Paused
        }

        if (recording) {
            ScreenRecordService.send(this, ScreenRecordService.ACTION_STOP)
        } else {
            val intent = ProjectionRequestActivity.intent(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val recording = ScreenRecordService.state.value.let {
            it is RecorderState.Recording || it is RecorderState.Paused
        }
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(
            if (recording) {
                com.example.screenrecorder.R.string.action_stop
            } else {
                com.example.screenrecorder.R.string.action_start
            }
        )
        tile.updateTile()
    }
}
