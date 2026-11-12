package com.example.screenrecorder.service

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.screenrecorder.R
import com.example.screenrecorder.data.SettingsRepository
import com.example.screenrecorder.recorder.RecorderState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Tombol mengambang untuk pause/stop tanpa membuka notification shade
 * (notification shade sendiri akan ikut terekam, jadi ini lebih praktis).
 */
class FloatingControlService : LifecycleService() {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Preferensi dibaca di sini, bukan di pemanggil, supaya semua jalur
        // masuk (tombol di aplikasi maupun tile Quick Settings) konsisten.
        lifecycleScope.launch {
            if (!SettingsRepository(applicationContext).current().useFloatingButton) {
                stopSelf()
                return@launch
            }
            addOverlay()
            observeState()
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun addOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        val view = LayoutInflater.from(this).inflate(R.layout.floating_controls, null)
        rootView = view

        val toggle = view.findViewById<ImageButton>(R.id.btn_toggle)
        val stop = view.findViewById<ImageButton>(R.id.btn_stop)

        toggle.setOnClickListener {
            val paused = ScreenRecordService.state.value is RecorderState.Paused
            ScreenRecordService.send(
                this,
                if (paused) ScreenRecordService.ACTION_RESUME else ScreenRecordService.ACTION_PAUSE,
            )
        }
        stop.setOnClickListener {
            ScreenRecordService.send(this, ScreenRecordService.ACTION_STOP)
        }

        // Drag untuk memindahkan tombol agar tidak menutupi konten penting.
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP)) {
                            dragging = true
                        }
                        if (dragging) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            runCatching { windowManager?.updateViewLayout(rootView, params) }
                        }
                    }

                    MotionEvent.ACTION_UP -> return dragging
                }
                return false
            }
        })

        runCatching { windowManager?.addView(view, params) }
            .onFailure {
                // Izin overlay dicabut saat jalan: matikan saja, rekaman tetap lanjut.
                stopSelf()
            }
    }

    private fun observeState() {
        lifecycleScope.launch {
            ScreenRecordService.state.collectLatest { state ->
                val toggle = rootView?.findViewById<ImageButton>(R.id.btn_toggle) ?: return@collectLatest
                when (state) {
                    is RecorderState.Paused -> {
                        toggle.setImageResource(R.drawable.ic_record)
                        toggle.contentDescription = getString(R.string.action_resume)
                    }

                    is RecorderState.Recording -> {
                        toggle.setImageResource(R.drawable.ic_pause)
                        toggle.contentDescription = getString(R.string.action_pause)
                    }

                    RecorderState.Idle, is RecorderState.Finished, is RecorderState.Failed ->
                        stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        rootView?.let { v -> runCatching { windowManager?.removeView(v) } }
        rootView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private companion object {
        const val TOUCH_SLOP = 12f
    }
}
