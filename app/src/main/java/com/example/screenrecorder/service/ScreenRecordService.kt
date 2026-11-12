package com.example.screenrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.screenrecorder.R
import com.example.screenrecorder.data.SettingsRepository
import com.example.screenrecorder.recorder.RecorderState
import com.example.screenrecorder.recorder.ScreenRecorder
import com.example.screenrecorder.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service yang memiliki sesi rekaman.
 *
 * MediaProjection pada Android 10+ hanya boleh dipakai dari foreground service
 * bertipe mediaProjection, dan pada Android 14+ notifikasinya harus sudah
 * tampil sebelum MediaProjection dipakai.
 */
class ScreenRecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: ScreenRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timerJob: Job? = null
    private var maxDurationSeconds = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            else -> Log.w(TAG, "Action tidak dikenal: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (recorder?.isActive == true) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            Log.e(TAG, "Izin tangkap layar tidak valid")
            stopSelf()
            return
        }

        // Notifikasi harus aktif SEBELUM MediaProjection dipakai (Android 14+).
        startForegroundNotification(buildNotification(State.STARTING, 0L))

        scope.launch {
            val config = SettingsRepository(applicationContext).current()
            maxDurationSeconds = config.maxDurationSeconds

            if (config.countdownSeconds > 0) {
                for (i in config.countdownSeconds downTo 1) {
                    updateNotification(buildNotification(State.STARTING, 0L, countdown = i))
                    delay(1000)
                }
            }

            acquireWakeLock()

            val rec = ScreenRecorder(applicationContext, ::onRecorderState)
            recorder = rec
            rec.start(resultCode, data, config)
        }
    }

    private fun onRecorderState(state: RecorderState) {
        _state.value = state
        when (state) {
            is RecorderState.Recording -> {
                updateNotification(buildNotification(State.RECORDING, recorder?.elapsedMs() ?: 0L))
                startTimer()
            }

            is RecorderState.Paused -> {
                stopTimer()
                updateNotification(buildNotification(State.PAUSED, state.elapsedMs))
            }

            is RecorderState.Finished -> {
                stopTimer()
                notifySaved(state.uri, state.durationMs)
                shutdown()
            }

            is RecorderState.Failed -> {
                stopTimer()
                Toast.makeText(this, getString(R.string.record_failed, state.message), Toast.LENGTH_LONG)
                    .show()
                shutdown()
            }

            RecorderState.Idle -> Unit
        }
    }

    private fun startTimer() {
        stopTimer()
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                val rec = recorder ?: break
                if (!rec.isActive) break
                val elapsed = rec.elapsedMs()
                if (maxDurationSeconds > 0 && elapsed >= maxDurationSeconds * 1000L) {
                    Log.d(TAG, "Batas durasi tercapai, menghentikan rekaman")
                    handleStop()
                    break
                }
                if (!rec.isPaused) {
                    updateNotification(buildNotification(State.RECORDING, elapsed))
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun handlePause() {
        recorder?.pause()
    }

    private fun handleResume() {
        recorder?.resume()
    }

    private fun handleStop() {
        val rec = recorder
        if (rec == null || !rec.isActive) {
            shutdown()
            return
        }
        rec.stop()
    }

    private fun shutdown() {
        stopTimer()
        releaseWakeLock()
        recorder = null
        _state.value = RecorderState.Idle
        stopService(Intent(this, FloatingControlService::class.java))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Jangan biarkan file setengah jadi bila service dimatikan sistem.
        recorder?.takeIf { it.isActive }?.stop()
        stopTimer()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // --- Wake lock ---

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::record").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // --- Notifikasi ---

    private enum class State { STARTING, RECORDING, PAUSED }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SAVED_ID,
                getString(R.string.channel_saved),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private fun startForegroundNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIF_ID, notification) }
    }

    private fun buildNotification(
        state: State,
        elapsedMs: Long,
        countdown: Int = 0,
    ): Notification {
        val title = when {
            countdown > 0 -> getString(R.string.notif_countdown, countdown)
            state == State.PAUSED -> getString(R.string.notif_paused)
            state == State.RECORDING -> getString(R.string.notif_recording)
            else -> getString(R.string.notif_preparing)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(formatDuration(elapsedMs))
            .setSmallIcon(R.drawable.ic_record)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )

        if (state != State.STARTING) {
            if (state == State.RECORDING) {
                builder.addAction(
                    R.drawable.ic_pause,
                    getString(R.string.action_pause),
                    actionIntent(ACTION_PAUSE),
                )
            } else {
                builder.addAction(
                    R.drawable.ic_record,
                    getString(R.string.action_resume),
                    actionIntent(ACTION_RESUME),
                )
            }
            builder.addAction(
                R.drawable.ic_stop,
                getString(R.string.action_stop),
                actionIntent(ACTION_STOP),
            )
        }

        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notifySaved(uri: Uri?, durationMs: Long) {
        if (uri == null) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
            return
        }
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val share = PendingIntent.getActivity(
            this,
            2,
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.action_share),
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SAVED_ID)
            .setContentTitle(getString(R.string.notif_saved))
            .setContentText(
                getString(R.string.notif_saved_detail, formatDuration(durationMs))
            )
            .setSmallIcon(R.drawable.ic_record)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_share, getString(R.string.action_share), share)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_SAVED_ID, notification)
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "recording"
        private const val CHANNEL_SAVED_ID = "saved"
        private const val NOTIF_ID = 1001
        private const val NOTIF_SAVED_ID = 1002
        private const val MAX_WAKELOCK_MS = 4L * 60 * 60 * 1000

        const val ACTION_START = "com.example.screenrecorder.START"
        const val ACTION_PAUSE = "com.example.screenrecorder.PAUSE"
        const val ACTION_RESUME = "com.example.screenrecorder.RESUME"
        const val ACTION_STOP = "com.example.screenrecorder.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** State global agar UI dan floating button ikut berubah. */
        private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            // startForegroundService baru ada di API 26.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun send(context: Context, action: String) {
            val intent = Intent(context, ScreenRecordService::class.java).setAction(action)
            runCatching { context.startService(intent) }
        }
    }
}
