package com.example.screenrecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Menerjemahkan tombol notifikasi menjadi perintah ke service. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            ScreenRecordService.ACTION_PAUSE,
            ScreenRecordService.ACTION_RESUME,
            ScreenRecordService.ACTION_STOP,
            -> ScreenRecordService.send(context, action)
        }
    }
}
