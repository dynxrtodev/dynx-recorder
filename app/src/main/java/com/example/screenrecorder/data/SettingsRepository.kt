package com.example.screenrecorder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.screenrecorder.model.AudioSourceMode
import com.example.screenrecorder.model.RecordConfig
import com.example.screenrecorder.model.VideoQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "recorder_settings")

/** Menyimpan preferensi rekaman supaya bertahan antar sesi. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val QUALITY = stringPreferencesKey("quality")
        val FPS = intPreferencesKey("fps")
        val VIDEO_BITRATE = intPreferencesKey("video_bitrate")
        val AUDIO_MODE = stringPreferencesKey("audio_mode")
        val AUDIO_BITRATE = intPreferencesKey("audio_bitrate")
        val MIC_GAIN = floatPreferencesKey("mic_gain")
        val INTERNAL_GAIN = floatPreferencesKey("internal_gain")
        val FLOATING = booleanPreferencesKey("floating_button")
        val COUNTDOWN = intPreferencesKey("countdown")
        val MAX_DURATION = intPreferencesKey("max_duration")
    }

    val config: Flow<RecordConfig> = context.dataStore.data.map { prefs ->
        val default = RecordConfig()
        RecordConfig(
            quality = prefs[Keys.QUALITY]?.let { name ->
                runCatching { VideoQuality.valueOf(name) }.getOrNull()
            } ?: default.quality,
            fps = prefs[Keys.FPS] ?: default.fps,
            videoBitrate = prefs[Keys.VIDEO_BITRATE] ?: default.videoBitrate,
            audioMode = prefs[Keys.AUDIO_MODE]?.let { name ->
                runCatching { AudioSourceMode.valueOf(name) }.getOrNull()
            } ?: default.audioMode,
            audioBitrate = prefs[Keys.AUDIO_BITRATE] ?: default.audioBitrate,
            micGain = prefs[Keys.MIC_GAIN] ?: default.micGain,
            internalGain = prefs[Keys.INTERNAL_GAIN] ?: default.internalGain,
            useFloatingButton = prefs[Keys.FLOATING] ?: default.useFloatingButton,
            countdownSeconds = prefs[Keys.COUNTDOWN] ?: default.countdownSeconds,
            maxDurationSeconds = prefs[Keys.MAX_DURATION] ?: default.maxDurationSeconds,
        )
    }

    suspend fun current(): RecordConfig = config.first()

    suspend fun save(config: RecordConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.QUALITY] = config.quality.name
            prefs[Keys.FPS] = config.fps
            prefs[Keys.VIDEO_BITRATE] = config.videoBitrate
            prefs[Keys.AUDIO_MODE] = config.audioMode.name
            prefs[Keys.AUDIO_BITRATE] = config.audioBitrate
            prefs[Keys.MIC_GAIN] = config.micGain
            prefs[Keys.INTERNAL_GAIN] = config.internalGain
            prefs[Keys.FLOATING] = config.useFloatingButton
            prefs[Keys.COUNTDOWN] = config.countdownSeconds
            prefs[Keys.MAX_DURATION] = config.maxDurationSeconds
        }
    }
}
