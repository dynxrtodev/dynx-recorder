package com.example.screenrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.screenrecorder.data.SettingsRepository
import com.example.screenrecorder.model.AudioSourceMode
import com.example.screenrecorder.model.RecordConfig
import com.example.screenrecorder.model.VideoQuality
import com.example.screenrecorder.recorder.RecorderState
import com.example.screenrecorder.service.ScreenRecordService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    val config: StateFlow<RecordConfig> = repo.config.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordConfig(),
    )

    val recorderState: StateFlow<RecorderState> = ScreenRecordService.state

    private fun update(transform: (RecordConfig) -> RecordConfig) {
        viewModelScope.launch { repo.save(transform(repo.current())) }
    }

    fun setQuality(quality: VideoQuality) = update { it.copy(quality = quality) }
    fun setFps(fps: Int) = update { it.copy(fps = fps) }
    fun setAudioMode(mode: AudioSourceMode) = update { it.copy(audioMode = mode) }
    fun setMicGain(gain: Float) = update { it.copy(micGain = gain) }
    fun setInternalGain(gain: Float) = update { it.copy(internalGain = gain) }
    fun setFloatingButton(enabled: Boolean) = update { it.copy(useFloatingButton = enabled) }
    fun setCountdown(seconds: Int) = update { it.copy(countdownSeconds = seconds) }
    fun setMaxDuration(seconds: Int) = update { it.copy(maxDurationSeconds = seconds) }
    fun setVideoBitrate(bps: Int) = update { it.copy(videoBitrate = bps) }
}
